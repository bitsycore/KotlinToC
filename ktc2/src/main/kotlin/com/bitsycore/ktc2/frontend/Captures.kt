@file:OptIn(UnsafeDuringIrConstructionAPI::class)

package com.bitsycore.ktc2.frontend

import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid

/** True for `(A) -> B` and the other `kotlin.FunctionN` types. */
internal fun isFunctionType(inType: IrType): Boolean =
	inType.classOrNull?.owner?.kotlinFqName?.asString()?.let { it.startsWith("kotlin.Function") && it.removePrefix("kotlin.Function").all(Char::isDigit) } == true

/** True when [inParameter] of an `inline` function takes a lambda that is expanded in place. */
internal fun isInlinedParameter(inParameter: IrValueParameter): Boolean =
	inParameter.kind == IrParameterKind.Regular && !inParameter.isNoinline && isFunctionType(inParameter.type)

/** True when calls to [inFunction] are expanded in place rather than emitted as calls. */
internal fun isExpanded(inFunction: IrFunction): Boolean =
	inFunction.isInline && inFunction.body != null &&
		(inFunction.parameters.any { isInlinedParameter(it) } || inFunction.typeParameters.any { it.isReified } || isInteropAccessor(inFunction))

/** The small `inline` functions of C interop (`ptr`, `pointed`, `value`…) are a line of C each: always expanded. */
private fun isInteropAccessor(inFunction: IrFunction): Boolean =
	inFunction.parent !is IrClass && inFunction.kotlinFqName.asString().startsWith("kotlinx.cinterop.")

/**
 * Finds what closures capture. A lambda passed to an expanded `inline` function (or to an
 * array constructor) is not a closure: its body runs in the enclosing function.
 */
internal class CaptureAnalysis {

	private val mCellCache = HashMap<IrElement, Set<IrValueDeclaration>>()
	private val mFreeCache = HashMap<IrFunction, List<Any>>()

	/** The lambda arguments of [inCall] that are expanded in place. */
	private fun inlinedArguments(inCall: IrCall): List<IrFunctionExpression> {
		val vCallee = inCall.symbol.owner
		if (!vCallee.isInline) return emptyList()
		return inCall.arguments.mapIndexedNotNull { vIndex, vArgument ->
			(vArgument as? IrFunctionExpression)?.takeIf { isInlinedParameter(vCallee.parameters[vIndex]) }
		}
	}

	/**
	 * The mutable variables declared under [inRoot] that a closure uses: they have to live in a
	 * shared cell so that the closure and the enclosing code see the same value.
	 */
	fun cellVariables(inRoot: IrElement): Set<IrValueDeclaration> = mCellCache.getOrPut(inRoot) {
		val vCells = LinkedHashSet<IrValueDeclaration>()
		val vDepthOf = HashMap<IrValueDeclaration, Int>()
		val vInlined = HashSet<IrFunctionExpression>()
		var vDepth = 0
		inRoot.acceptVoid(object : IrVisitorVoid() {
			override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)

			override fun visitVariable(declaration: IrVariable) {
				vDepthOf[declaration] = vDepth
				super.visitVariable(declaration)
			}

			override fun visitClass(declaration: IrClass) {
				// A local class or object expression uses outer variables like a closure does.
				vDepth++
				super.visitClass(declaration)
				vDepth--
			}

			override fun visitCall(expression: IrCall) {
				vInlined += inlinedArguments(expression)
				super.visitCall(expression)
			}

			override fun visitConstructorCall(expression: IrConstructorCall) {
				// Array(n) { ... } runs its initializer in place.
				if (expression.symbol.owner.parent.kotlinFqName.asString().endsWith("Array")) {
					expression.arguments.filterIsInstance<IrFunctionExpression>().forEach { vInlined += it }
				}
				super.visitConstructorCall(expression)
			}

			override fun visitFunctionExpression(expression: IrFunctionExpression) {
				if (expression in vInlined) {
					super.visitFunctionExpression(expression)
				} else {
					vDepth++
					super.visitFunctionExpression(expression)
					vDepth--
				}
			}

			override fun visitSimpleFunction(declaration: IrSimpleFunction) {
				// A lambda's function is reached through its expression; anything else here is a local function.
				if (declaration === inRoot || declaration.origin.toString().contains("LAMBDA")) {
					super.visitSimpleFunction(declaration)
				} else {
					vDepth++
					super.visitSimpleFunction(declaration)
					vDepth--
				}
			}

			private fun use(inDeclaration: IrValueDeclaration) {
				val vDeclaredAt = vDepthOf[inDeclaration] ?: return
				if (inDeclaration is IrVariable && inDeclaration.isVar && vDeclaredAt < vDepth) vCells += inDeclaration
			}

			override fun visitGetValue(expression: IrGetValue) {
				use(expression.symbol.owner)
				super.visitGetValue(expression)
			}

			override fun visitSetValue(expression: IrSetValue) {
				use(expression.symbol.owner)
				super.visitSetValue(expression)
			}
		})
		vCells
	}

	/**
	 * The value declarations a local class (or object expression) uses from the function it is
	 * declared in, in first-use order.
	 */
	fun freeVariablesOfClass(inClass: IrClass): List<IrValueDeclaration> {
		val vDeclared = HashSet<Any>()
		val vFree = LinkedHashSet<IrValueDeclaration>()
		inClass.acceptVoid(object : IrVisitorVoid() {
			override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)

			override fun visitClass(declaration: IrClass) {
				declaration.thisReceiver?.let { vDeclared += it }
				super.visitClass(declaration)
			}

			override fun visitFunction(declaration: IrFunction) {
				vDeclared.addAll(declaration.parameters)
				super.visitFunction(declaration)
			}

			override fun visitVariable(declaration: IrVariable) {
				vDeclared += declaration
				super.visitVariable(declaration)
			}

			override fun visitGetValue(expression: IrGetValue) {
				if (expression.symbol.owner !in vDeclared) vFree += expression.symbol.owner
				super.visitGetValue(expression)
			}

			override fun visitSetValue(expression: IrSetValue) {
				if (expression.symbol.owner !in vDeclared) vFree += expression.symbol.owner
				super.visitSetValue(expression)
			}
		})
		return vFree.toList()
	}

	/**
	 * What the closure [inFunction] uses from outside itself, in first-use order: value
	 * declarations ([IrValueDeclaration]) and local functions ([IrSimpleFunction]).
	 */
	fun freeVariables(inFunction: IrFunction): List<Any> = mFreeCache.getOrPut(inFunction) {
		val vDeclared = HashSet<Any>()
		val vFree = LinkedHashSet<Any>()
		vDeclared += inFunction
		vDeclared.addAll(inFunction.parameters)
		inFunction.acceptChildrenVoid(object : IrVisitorVoid() {
			override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)

			override fun visitVariable(declaration: IrVariable) {
				vDeclared += declaration
				super.visitVariable(declaration)
			}

			override fun visitSimpleFunction(declaration: IrSimpleFunction) {
				vDeclared += declaration
				vDeclared.addAll(declaration.parameters)
				super.visitSimpleFunction(declaration)
			}

			override fun visitGetValue(expression: IrGetValue) {
				if (expression.symbol.owner !in vDeclared) vFree += expression.symbol.owner
				super.visitGetValue(expression)
			}

			override fun visitSetValue(expression: IrSetValue) {
				if (expression.symbol.owner !in vDeclared) vFree += expression.symbol.owner
				super.visitSetValue(expression)
			}

			override fun visitCall(expression: IrCall) {
				val vCallee = expression.symbol.owner
				if (vCallee.parent is IrFunction && vCallee !in vDeclared) vFree += vCallee
				super.visitCall(expression)
			}
		})
		vFree.toList()
	}
}
