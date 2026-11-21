package com.bitsycore.ktc2.frontend

import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionAccessExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrSetField
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import java.io.File

/**
 * The cycles a program makes with a library's API that the program can avoid with that API as it is (`PLAN.md`, "Who
 * answers a cycle", rule 4): warnings, in the program's own files only, saying what to write instead. What this pass
 * looks for is what `CycleLint` cannot see on classes and fields, because it goes through a coroutine or a lambda:
 * - the continuation of a coroutine (the parameter of a `suspendCoroutine { }` block) kept in a variable the coroutine
 *   captures, or in a property of an object the coroutine holds (one it captures, or its receiver), never cleared: once
 *   it has resumed, the continuation holds the coroutine, which holds the variable or the object;
 * - a lambda that resumes that continuation, put into a collection held by such an object;
 * - a `var` given what a call returns when the call was handed a lambda that uses the same `var` (a registration whose
 *   handler disposes of itself through it): the registration holds the lambda, which holds the variable;
 * - a property given what a call or a constructor returns when it was handed a lambda that uses the object
 *   (`val f = DeepRecursiveFunction { ... this ... }`): three objects at least, which no check of a pair frees. A lambda
 *   stored as it is, and what an inline function makes (`Delegates.observable`), are a pair the runtime frees; `lazy`
 *   lets go of its lambda once read, so only a value of it that keeps a lambda using the object is one.
 */
class UserCycleLint(private val mLibraryFiles: Set<String>, private val mOutDir: File) {

	val warnings = ArrayList<String>()

	/** What takes something out of a collection: one that is drained does not keep what it was given for ever. */
	private val kDraining = setOf("remove", "removeAt", "removeFirst", "removeLast", "removeAll", "clear", "poll", "removeFirstOrNull", "removeLastOrNull")

	/** The functions whose block receives the continuation of the coroutine that calls them. */
	private val kContinuationBlocks = setOf(
		"kotlin.coroutines.suspendCoroutine",
		"kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn",
		"kotlinx.coroutines.suspendCancellableCoroutine",
		"kotlinx.coroutines.suspendCancellableCoroutineReusable",
	)

	fun check(inFiles: List<IrFile>) {
		val vOut = mOutDir.absoluteFile.normalize().invariantSeparatorsPath
		val vCache = com.bitsycore.ktc2.Settings.cacheDirectory().absoluteFile.normalize().invariantSeparatorsPath
		for (vFile in inFiles) {
			val vPath = File(vFile.fileEntry.name).absoluteFile.normalize().invariantSeparatorsPath
			// A library's own files (the stdlib, a bundled module's sources, wherever they were copied) are not the program's.
			if (vPath in mLibraryFiles || vPath.startsWith("$vCache/") || vPath.startsWith("$vOut/modules/") || vPath.startsWith("$vOut/gen/") || "/ktc2/library-" in vPath) continue
			FileCheck(vFile).run()
		}
	}

	private inner class FileCheck(private val mFile: IrFile) {
		/** The places a `null` is stored into: variables and fields cleared somewhere in the file. */
		private val mCleared = HashSet<IrSymbol>()

		/** The continuation each block parameter is, with the coroutine whose it is. */
		private val mContinuations = HashMap<IrValueDeclaration, IrFunction>()

		/** The collections (a property or a field) something is taken out of somewhere in the file: drained, not kept for ever. */
		private val mDrained = HashSet<IrSymbol>()

		private val mFunctions = ArrayList<IrFunction>()
		private val mReported = HashSet<Int>()

		fun run() {
			mFile.acceptVoid(object : IrVisitorVoid() {
				override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)
				override fun visitSetValue(expression: IrSetValue) {
					if (isNull(expression.value)) mCleared += expression.symbol
					super.visitSetValue(expression)
				}
				override fun visitSetField(expression: IrSetField) {
					if (isNull(expression.value)) mCleared += expression.symbol
					super.visitSetField(expression)
				}
				override fun visitCall(expression: IrCall) {
					val vProperty = expression.symbol.owner.correspondingPropertySymbol
					if (vProperty != null && expression.symbol.owner.name.asString().startsWith("<set-") && expression.arguments.lastOrNull()?.let { isNull(it) } == true) {
						mCleared += vProperty
						vProperty.owner.backingField?.let { mCleared += it.symbol }
					}
					if (expression.symbol.owner.name.asString() in kDraining) expression.arguments.firstOrNull()?.let { collectionOf(it) }?.let { mDrained += it }
					super.visitCall(expression)
				}
			})
			mFile.acceptVoid(Visitor())
		}

		private fun isNull(inValue: IrExpression): Boolean = inValue is IrConst && inValue.value == null

		/** The property or field [inValue] reads, when it reads one. */
		private fun collectionOf(inValue: IrExpression): IrSymbol? = when (val vValue = stripped(inValue)) {
			is IrCall -> vValue.symbol.owner.correspondingPropertySymbol
			is IrGetField -> vValue.symbol
			else -> null
		}

		/**
		 * Whether [inFunction] keeps what it is given as its parameter [inIndex]: a `vararg`, a constructor's parameter that
		 * is a field, or one stored in a field, returned, or handed on to what keeps it ([inDepth] calls at most). Unknown (no
		 * body): not kept.
		 */
		private fun keeps(inFunction: IrFunction, inIndex: Int, inDepth: Int): Boolean {
			val vParameter = inFunction.parameters.getOrNull(inIndex) ?: return false
			if (vParameter.varargElementType != null) return true
			if (inFunction is org.jetbrains.kotlin.ir.declarations.IrConstructor) {
				val vFields = inFunction.parentAsClass.declarations.mapNotNull { (it as? IrField) ?: (it as? org.jetbrains.kotlin.ir.declarations.IrProperty)?.backingField }
				if (vFields.any { vField -> (vField.initializer?.expression?.let { stripped(it) } as? IrGetValue)?.symbol?.owner === vParameter }) return true
			}
			val vBody = inFunction.body ?: return false
			var vKept = false
			fun isParameter(inValue: IrExpression?): Boolean = (inValue?.let { stripped(it) } as? IrGetValue)?.symbol?.owner === vParameter
			vBody.acceptVoid(object : IrVisitorVoid() {
				override fun visitElement(element: IrElement) { if (!vKept) element.acceptChildrenVoid(this) }
				override fun visitSetField(expression: IrSetField) {
					if (isParameter(expression.value)) vKept = true
					super.visitSetField(expression)
				}
				override fun visitReturn(expression: org.jetbrains.kotlin.ir.expressions.IrReturn) {
					if (isParameter(expression.value)) vKept = true
					super.visitReturn(expression)
				}
				override fun visitFunctionAccess(expression: IrFunctionAccessExpression) {
					if (inDepth > 0) expression.arguments.forEachIndexed { vAt, vArgument -> if (isParameter(vArgument) && keeps(expression.symbol.owner, vAt, inDepth - 1)) vKept = true }
					super.visitFunctionAccess(expression)
				}
			})
			return vKept
		}

		/** Whether [inCall] makes a job (`launch`, `async`): a job lets go of its block once it completes. */
		private fun makesJob(inCall: IrFunctionAccessExpression): Boolean {
			val vClass = inCall.type.classOrNull?.owner ?: return false
			return generateSequence(listOf(vClass)) { vLevel -> vLevel.flatMap { vOne -> vOne.superTypes.mapNotNull { it.classOrNull?.owner } }.takeIf { it.isNotEmpty() } }
				.flatten().any { it.kotlinFqName.asString() == "kotlinx.coroutines.Job" }
		}

		private fun stripped(inValue: IrExpression): IrExpression = if (inValue is IrTypeOperatorCall) stripped(inValue.argument) else inValue

		/** The coroutine that runs here: the innermost `suspend` function or lambda. */
		private fun coroutine(): IrFunction? = mFunctions.lastOrNull { (it as? IrSimpleFunction)?.isSuspend == true }

		/** The coroutine [inValue] is the continuation of, when it is one. */
		private fun continuationOf(inValue: IrExpression): IrFunction? = (stripped(inValue) as? IrGetValue)?.symbol?.owner?.let { mContinuations[it] }

		/** Whether [inParent] is [inFunction] or inside it. */
		private fun isWithin(inParent: IrDeclarationParent, inFunction: IrFunction): Boolean =
			generateSequence(inParent) { (it as? IrDeclaration)?.parent }.any { it === inFunction }

		/**
		 * Whether [inCoroutine] holds what [inValue] reads: a value it captures (declared outside it), or the receiver of a
		 * `suspend` lambda (a named `suspend` function that waits is its caller's continuation, which does not hold its `this`).
		 */
		private fun isHeldBy(inValue: IrExpression?, inCoroutine: IrFunction): Boolean {
			val vValue = (inValue?.let { stripped(it) } as? IrGetValue)?.symbol?.owner ?: return false
			if (vValue is IrValueParameter && vValue.parent === inCoroutine) {
				return vValue.kind != org.jetbrains.kotlin.ir.declarations.IrParameterKind.Regular && inCoroutine.origin == IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA
			}
			return !isWithin(vValue.parent, inCoroutine)
		}

		private fun warn(inElement: IrElement, inMessage: String) {
			if (!mReported.add(inElement.startOffset)) return
			val vLine = mFile.fileEntry.getLineNumber(inElement.startOffset) + 1
			warnings += "${File(mFile.fileEntry.name).name}:$vLine: $inMessage"
		}

		/** Whether [inLambda] reads a continuation of [inCoroutine]. */
		private fun resumes(inLambda: IrFunctionExpression, inCoroutine: IrFunction): Boolean {
			var vFound = false
			inLambda.function.acceptVoid(object : IrVisitorVoid() {
				override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)
				override fun visitGetValue(expression: IrGetValue) {
					if (mContinuations[expression.symbol.owner] === inCoroutine) vFound = true
				}
			})
			return vFound
		}

		/** The lambdas and object expressions written inside [inLambda]. */
		private fun innerLambdas(inLambda: IrFunctionExpression): List<IrFunctionExpression> {
			val vFound = ArrayList<IrFunctionExpression>()
			inLambda.function.acceptChildrenVoid(object : IrVisitorVoid() {
				override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)
				override fun visitFunctionExpression(expression: IrFunctionExpression) {
					vFound += expression
					super.visitFunctionExpression(expression)
				}
			})
			return vFound
		}

		/** Whether [inLambda] reads [inVariable]. */
		private fun reads(inLambda: IrFunctionExpression, inVariable: IrValueDeclaration): Boolean {
			var vFound = false
			inLambda.function.acceptVoid(object : IrVisitorVoid() {
				override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)
				override fun visitGetValue(expression: IrGetValue) {
					if (expression.symbol.owner === inVariable) vFound = true
				}
			})
			return vFound
		}

		private inner class Visitor : IrVisitorVoid() {
			override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)

			override fun visitFunction(declaration: IrFunction) {
				mFunctions += declaration
				super.visitFunction(declaration)
				mFunctions.removeAt(mFunctions.lastIndex)
			}

			override fun visitField(declaration: IrField) {
				val vClass = declaration.parent as? IrClass
				val vThis = vClass?.thisReceiver
				val vCall = declaration.initializer?.expression?.let { stripped(it) } as? IrFunctionAccessExpression
				if (vThis != null && vCall != null && !vCall.symbol.owner.isInline && !makesJob(vCall)) {
					val vIsLazy = vCall.symbol.owner.kotlinFqName.asString() == "kotlin.lazy"
					// Only a lambda the call keeps (a builder that calls it and lets go of it is no cycle).
					val vLambdas = vCall.arguments.withIndex().filter { (vIndex, vArgument) -> vArgument is IrFunctionExpression && keeps(vCall.symbol.owner, vIndex, 3) }.map { it.value as IrFunctionExpression }
					// `lazy` lets go of its lambda once read: what stays is its value, which keeps the object if it keeps a lambda that
					// uses it.
					val vKeeps = if (vIsLazy) vLambdas.any { vLambda -> innerLambdas(vLambda).any { reads(it, vThis) } } else vLambdas.any { reads(it, vThis) }
					if (vKeeps) {
						val vName = declaration.name.asString().removeSuffix("\$delegate")
						val vWhat = if (vIsLazy) "the value of '$vName' keeps a lambda that uses this object" else "'$vName' keeps a lambda that uses this object (${vCall.symbol.owner.name.asString().let { if (it == "<init>") vCall.symbol.owner.parentAsClass.name.asString() else it }} keeps it)"
						warn(declaration, "$vWhat: the object and it hold each other for ever, three objects or more that no check of a pair frees; have the lambda capture a WeakRef of the object made outside it (a property of the object is read through it), or only what it needs of it")
					}
				}
				super.visitField(declaration)
			}

			override fun visitVariable(declaration: IrVariable) {
				declaration.initializer?.let { registration(declaration, declaration, it) }
				super.visitVariable(declaration)
			}

			override fun visitSetValue(expression: IrSetValue) {
				val vVariable = expression.symbol.owner
				val vCoroutine = continuationOf(expression.value)
				if (vCoroutine != null && !isWithin(vVariable.parent, vCoroutine) && expression.symbol !in mCleared) {
					warn(expression, "the continuation of a coroutine is kept in '${vVariable.name}', which the coroutine itself holds: a reference cycle, never freed once it has resumed; set '${vVariable.name}' to null once it is resumed, or keep a WeakRef of the continuation")
				}
				registration(expression, vVariable, expression.value)
				super.visitSetValue(expression)
			}

			override fun visitSetField(expression: IrSetField) {
				val vCoroutine = continuationOf(expression.value)
				if (vCoroutine != null && isHeldBy(expression.receiver, vCoroutine) && expression.symbol !in mCleared) {
					warn(expression, "the continuation of a coroutine is kept in '${expression.symbol.owner.name}' of an object the coroutine itself holds: a reference cycle, never freed once it has resumed; set it to null once it is resumed, or keep a WeakRef of the continuation")
				}
				super.visitSetField(expression)
			}

			override fun visitCall(expression: IrCall) {
				val vCallee = expression.symbol.owner
				// The block of `suspendCoroutine { }` and the like receives the continuation of the coroutine that runs here.
				if (vCallee.kotlinFqName.asString() in kContinuationBlocks) {
					val vCoroutine = coroutine()
					val vBlock = expression.arguments.filterIsInstance<IrFunctionExpression>().lastOrNull()
					val vParameter = vBlock?.function?.parameters?.firstOrNull { it.kind == org.jetbrains.kotlin.ir.declarations.IrParameterKind.Regular }
					if (vCoroutine != null && vParameter != null) mContinuations[vParameter] = vCoroutine
				}
				val vProperty = vCallee.correspondingPropertySymbol
				val vValue = expression.arguments.lastOrNull()
				if (vProperty != null && vCallee.name.asString().startsWith("<set-") && vValue != null) {
					val vCoroutine = continuationOf(vValue)
					if (vCoroutine != null && isHeldBy(expression.arguments.firstOrNull(), vCoroutine) && vProperty !in mCleared) {
						warn(expression, "the continuation of a coroutine is kept in '${vProperty.owner.name}' of an object the coroutine itself holds: a reference cycle, never freed once it has resumed; set it to null once it is resumed, or keep a WeakRef of the continuation")
					}
				}
				// A lambda that resumes the coroutine, handed to a collection that an object the coroutine holds keeps.
				val vCoroutine = coroutine()
				val vHolder = expression.arguments.firstOrNull()?.let { stripped(it) }
				val vHolderOwner = when (vHolder) {
					is IrCall -> vHolder.takeIf { it.symbol.owner.correspondingPropertySymbol != null }?.arguments?.firstOrNull()
					is IrGetField -> vHolder.receiver
					else -> null
				}
				if (vCoroutine != null && vHolderOwner != null && isHeldBy(vHolderOwner, vCoroutine) && vHolder?.let { collectionOf(it) } !in mDrained) {
					val vLambda = expression.arguments.drop(1).filterIsInstance<IrFunctionExpression>().firstOrNull { vArgument -> mContinuations.values.any { it === vCoroutine } && resumes(vArgument, vCoroutine) }
					if (vLambda != null) {
						warn(expression, "a lambda that resumes a coroutine is kept by an object the coroutine itself holds: a reference cycle, never freed while the lambda is kept; take it out once it has run (also when it throws), or have it hold a WeakRef of the continuation")
					}
				}
				super.visitCall(expression)
			}

			/** `x = register { ... x ... }`: what the call returns may keep the lambda, which keeps `x`. */
			private fun registration(inElement: IrElement, inVariable: IrValueDeclaration, inValue: IrExpression) {
				if (inVariable !is IrVariable || !inVariable.isVar || inVariable.symbol in mCleared) return
				val vCall = stripped(inValue) as? IrCall ?: return
			if (makesJob(vCall)) return
				if (vCall.arguments.filterIsInstance<IrFunctionExpression>().any { reads(it, inVariable) }) {
					warn(inElement, "'${inVariable.name}' is given what a call returns, and that call was handed a lambda that uses '${inVariable.name}': if what it returns keeps the lambda (a registration, a handle), the two hold each other for ever; set '${inVariable.name}' to null once done, or have the lambda hold a WeakRef")
				}
			}
		}
	}
}
