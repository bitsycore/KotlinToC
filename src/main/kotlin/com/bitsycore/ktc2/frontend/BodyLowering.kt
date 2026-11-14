@file:OptIn(UnsafeDuringIrConstructionAPI::class)

package com.bitsycore.ktc2.frontend

import com.bitsycore.ktc2.kir.*
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.fileOrNull
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.ir.util.resolveFakeOverride

private val kPrimByClass = mapOf(
	"kotlin.Boolean" to KPrim.BOOLEAN, "kotlin.Byte" to KPrim.BYTE, "kotlin.Short" to KPrim.SHORT,
	"kotlin.Int" to KPrim.INT, "kotlin.Long" to KPrim.LONG, "kotlin.Char" to KPrim.CHAR,
	"kotlin.Float" to KPrim.FLOAT, "kotlin.Double" to KPrim.DOUBLE,
	"kotlin.UByte" to KPrim.UBYTE, "kotlin.UShort" to KPrim.USHORT, "kotlin.UInt" to KPrim.UINT, "kotlin.ULong" to KPrim.ULONG,
)

private val kBinaryByName = mapOf(
	"plus" to KBinaryOp.ADD, "minus" to KBinaryOp.SUB, "times" to KBinaryOp.MUL, "div" to KBinaryOp.DIV,
	"rem" to KBinaryOp.REM, "and" to KBinaryOp.AND, "or" to KBinaryOp.OR, "xor" to KBinaryOp.XOR,
	"shl" to KBinaryOp.SHL, "shr" to KBinaryOp.SHR, "ushr" to KBinaryOp.USHR, "compareTo" to KBinaryOp.CMP,
)

private val kComparisonByName = mapOf(
	"less" to KBinaryOp.LT, "lessOrEqual" to KBinaryOp.LE, "greater" to KBinaryOp.GT,
	"greaterOrEqual" to KBinaryOp.GE, "EQEQ" to KBinaryOp.EQ, "EQEQEQ" to KBinaryOp.EQ, "ieee754equals" to KBinaryOp.EQ,
)

private val kConversionByName = mapOf(
	"toByte" to KPrim.BYTE, "toShort" to KPrim.SHORT, "toInt" to KPrim.INT, "toLong" to KPrim.LONG,
	"toChar" to KPrim.CHAR, "toFloat" to KPrim.FLOAT, "toDouble" to KPrim.DOUBLE, "<get-code>" to KPrim.INT,
	"toUByte" to KPrim.UBYTE, "toUShort" to KPrim.USHORT, "toUInt" to KPrim.UINT, "toULong" to KPrim.ULONG,
)

private val kInverse = mapOf(
	KBinaryOp.EQ to KBinaryOp.NE, KBinaryOp.NE to KBinaryOp.EQ, KBinaryOp.LT to KBinaryOp.GE,
	KBinaryOp.GE to KBinaryOp.LT, KBinaryOp.GT to KBinaryOp.LE, KBinaryOp.LE to KBinaryOp.GT,
)

private val kBoolType = KPrimType(KPrim.BOOLEAN)
private val kIntType = KPrimType(KPrim.INT)
private val kTrue = KConst(true, kBoolType)
private val kFalse = KConst(false, kBoolType)

/** The constant 1 of an integer type. */
private fun one(inPrim: KPrim): KConst =
	if (inPrim == KPrim.LONG || inPrim == KPrim.ULONG) KConst(1L, KPrimType(inPrim)) else KConst(1, KPrimType(inPrim))

/** Logical negation, folded into the comparison when that keeps NaN behavior intact. */
private fun not(inValue: KExpr): KExpr = when {
	inValue === kTrue -> kFalse
	inValue === kFalse -> kTrue
	inValue is KUnary && inValue.op == KUnaryOp.NOT -> inValue.operand
	inValue is KRefEquals -> KRefEquals(inValue.left, inValue.right, !inValue.negated)
	inValue is KBinary && inValue.op in kInverse &&
		(!inValue.operandType.isFloating || inValue.op == KBinaryOp.EQ || inValue.op == KBinaryOp.NE) ->
		KBinary(kInverse.getValue(inValue.op), inValue.left, inValue.right, inValue.operandType, inValue.type)
	else -> KUnary(KUnaryOp.NOT, inValue, kBoolType)
}

private fun and(inLeft: KExpr, inRight: KExpr): KExpr = when {
	inLeft === kFalse || inRight === kFalse -> kFalse
	inLeft === kTrue -> inRight
	inRight === kTrue -> inLeft
	else -> KSelect(inLeft, inRight, kFalse, kBoolType)
}

/** `inValue == null`, already decided for values that can never be null (literals, static instances). */
private fun isNull(inValue: KExpr): KExpr = when (inValue) {
	is KStringLit, is KStaticRead -> kFalse
	// The Unit object is never null (and gcc refuses a test of an address against NULL).
	is KIntrinsic -> if (inValue.name == "ktc2_unit_object") kFalse else KRefEquals(inValue, KNull(inValue.type))
	is KRefCast -> isNull(inValue.operand).let { if (it === kFalse) it else KRefEquals(inValue, KNull(inValue.type)) }
	is KNull -> kTrue
	else -> KRefEquals(inValue, KNull(inValue.type))
}

/** The built-in classes whose companion the runtime has an object for (`ktc2_companions`, and one more for any other). */
private val kBuiltInCompanions = listOf("Boolean", "Byte", "Short", "Int", "Long", "Char", "Float", "Double", "String")

/** Library functions written with `iterator { }` that the platform layer has under their name (`redirectToPlatform`). */
private val kWithoutCoroutine = setOf(
	"windowedIterator", "runningFold", "runningFoldIndexed", "runningReduce", "runningReduceIndexed", "zipWithNext", "ifEmpty", "shuffled", "flatMapIndexed",
)

/**
 * Lowers one function body from K2 IR to KIR statements. Every side effect becomes a statement
 * in evaluation order; what is left in expressions is pure.
 */
internal class BodyBuilder(
	private val mImporter: KirImporter,
	private val mFunction: KFunction,
	private val mIrFunction: IrFunction?,
	private val mFile: IrFile,
	inSubst: Subst,
) {
	/** What the type parameters in scope stand for in this instantiation. The importer is told: a local class is of the instantiation of the code around it. */
	private var mSubst: Subst = inSubst
		set(inValue) {
			field = inValue
			mImporter.contextSubst = inValue
		}

	init {
		mImporter.contextSubst = inSubst
	}

	private fun kType(inType: IrType): KType? = mImporter.kTypeOf(inType, mSubst)

	private fun closed(inType: IrType): IrType = mImporter.close(inType, mSubst)

	/** Adapts the result of a call or field read to the type the expression has here (a generic member returns `Any?`). */
	private fun adapt(inValue: KExpr, inExpression: IrExpression): KExpr {
		val vExpected = kType(inExpression.type)
		if (vExpected == null || vExpected == KUnitType || vExpected == KNothingType || inValue === KUnitValue) return inValue
		return coerce(inValue, vExpected)
	}

	private val mLocals = HashMap<IrValueDeclaration, KLocal>()
	private val mUsedNames = HashMap<String, Int>()
	private val mLoops = HashMap<IrLoop, Int>()
	private val mTemps = HashSet<KLocal>()
	private val mNotSelectable = HashSet<IrWhen>()
	private var mNextLoopId = 0

	/** Where a `return` from an inlined lambda goes: its result local and the block to leave. */
	private class InlineTarget(val result: KLocal?, val blockId: Int)

	private val mInlineTargets = HashMap<Any, InlineTarget>()

	/** A lambda bound to a parameter of an `inline` function being expanded, with the substitution it was written under. */
	internal class InlineLambda(val expression: IrFunctionExpression, val subst: Subst)

	private val mInlineLambdas = HashMap<IrValueDeclaration, InlineLambda>()

	/** Mutable variables shared with a closure: the local holds a cell, the value is in this field of it. */
	private val mCells = HashMap<IrValueDeclaration, KField>()
	private val mCellVariables = HashSet<IrValueDeclaration>()

	/** A local function: the closure object in [holder], called through [invoke]. */
	private class LocalFunction(val invoke: KFunction, val holder: KLocal)

	private val mLocalFunctions = HashMap<IrFunction, LocalFunction>()

	/** What a closure body finds in its closure object: a captured value, cell or local function. */
	internal class Binding(val declaration: Any, val field: KField, val cell: KField?, val invoke: KFunction?)

	private var mBindings: List<Binding> = emptyList()
	private var mSelfFunction: IrFunction? = null
	private var mBlock: MutableList<KStmt> = mFunction.body

	// ==================
	// MARK: Entry points
	// ==================

	/** Body of a unit initializer: assigns each global its startup value, in declaration order. */
	fun buildGlobalInitializer(inValues: List<Pair<KGlobal, IrExpression>>) {
		for ((vGlobal, vValue) in inValues) mBlock += KGlobalSet(vGlobal, coerce(lowerRhs(vValue), vGlobal.type))
	}

	/** Startup of an enum class: gives each entry its name and ordinal, then runs its constructor. */
	fun buildEnumInitializer(inEntries: List<IrEnumEntry>, inStatics: List<KStatic>) {
		val vBaseType = KClassType(mImporter.enumBase())
		inEntries.forEachIndexed { vOrdinal, vEntry ->
			val vInstance = KStaticRead(inStatics[vOrdinal])
			mBlock += KFieldSet(coerce(vInstance, vBaseType), mImporter.enumNameField, KStringLit(vEntry.name.asString()))
			mBlock += KFieldSet(coerce(vInstance, vBaseType), mImporter.enumOrdinalField, KConst(vOrdinal, kIntType))
			val vCall = vEntry.initializerExpression?.expression as? IrEnumConstructorCall ?: return@forEachIndexed
			val vConstructor = vCall.symbol.owner
			val vTarget = mImporter.functionFor(vConstructor)
			if (vTarget == null) {
				fail(vEntry, "enum entry '${vEntry.name}'")
			} else {
				val vInit = callFunction(vCall, vTarget, vConstructor, vCall.arguments, coerce(vInstance, vTarget.params[0].type))
				if (vInit is KCall) mBlock += KEval(vInit)
			}
		}
	}

	/** `values()` and `valueOf()` of an enum class. */
	/** `values()`, `entries` and `valueOf()` of a `@SimpleEnum` class: the entries are constants. */
	private fun buildSimpleEnumSynthetic(inEnum: KirImporter.SimpleEnum, inFunction: IrFunction, inKind: IrSyntheticBodyKind) {
		when (inKind) {
			IrSyntheticBodyKind.ENUM_VALUES, IrSyntheticBodyKind.ENUM_ENTRIES -> {
				// The list of `entries` holds objects: the ones kept for the entries.
				val vType = if (inKind == IrSyntheticBodyKind.ENUM_VALUES) mFunction.returnType as KArrayType else KArrayType(KObjType)
				val vArray = newTemp(vType)
				mBlock += KLet(vArray, KArrayNew(KConst(inEnum.entries.size, kIntType), vType))
				inEnum.entries.forEachIndexed { vIndex, vEntry ->
					mBlock += KIndexSet(KRead(vArray), KConst(vIndex, kIntType), coerce(inEnum.constant(vEntry), vType.element))
				}
				if (inKind == IrSyntheticBodyKind.ENUM_VALUES) {
					mBlock += KReturn(KRead(vArray))
				} else {
					val vList = mImporter.internalFunction("enumEntriesOf")?.let { mImporter.functionFor(it) }
					if (vList == null) fail(inFunction, "enum entries") else mBlock += KReturn(coerce(spill(KCall(vList, listOf(KRead(vArray)))), mFunction.returnType))
				}
			}
			IrSyntheticBodyKind.ENUM_VALUEOF -> {
				val vName = KRead(mFunction.params[0])
				for (vEntry in inEnum.entries) {
					val vMatches = KIntrinsic("ktc2_str_equals", listOf(KStringLit(vEntry.name), vName), kBoolType)
					mBlock += KIf(vMatches, listOf(KReturn(coerce(inEnum.constant(vEntry), mFunction.returnType))), emptyList())
				}
				noEnumConstant(inFunction, vName)
			}
			else -> fail(inFunction, "enum '${inFunction.name}'")
		}
	}

	/** The end of a `valueOf` that found no entry called [inName]: an `IllegalArgumentException`, as on the JVM. */
	private fun noEnumConstant(inFunction: IrFunction, inName: KExpr) {
		val vThrow = mImporter.internalFunction("throwNoEnumConstant")?.let { mImporter.functionFor(it) }
		if (vThrow == null) {
			mBlock += KEval(KIntrinsic("ktc2_fail_enum_value", listOf(inName), KUnitType, isPure = false))
		} else {
			mBlock += KEval(KCall(vThrow, listOf(KStringLit(inFunction.parentAsClass.kotlinFqName.asString()), inName)))
		}
	}

	private fun buildEnumSynthetic(inFunction: IrFunction, inKind: IrSyntheticBodyKind) {
		mImporter.simpleEnumOf(inFunction.parentAsClass)?.let { return buildSimpleEnumSynthetic(it, inFunction, inKind) }
		val vEntries = mImporter.enumEntriesOf(inFunction.parentAsClass)
		ensureInitialized(inFunction.parentAsClass)
		val vBaseType = KClassType(mImporter.enumBase())
		when (inKind) {
			IrSyntheticBodyKind.ENUM_VALUES -> {
				val vType = mFunction.returnType as KArrayType
				val vArray = newTemp(vType)
				mBlock += KLet(vArray, KArrayNew(KConst(vEntries.size, kIntType), vType))
				vEntries.forEachIndexed { vIndex, vEntry ->
					mBlock += KIndexSet(KRead(vArray), KConst(vIndex, kIntType), coerce(KStaticRead(vEntry), vType.element))
				}
				mBlock += KReturn(KRead(vArray))
			}
			IrSyntheticBodyKind.ENUM_ENTRIES -> {
				val vType = KArrayType(KObjType)
				val vArray = newTemp(vType)
				mBlock += KLet(vArray, KArrayNew(KConst(vEntries.size, kIntType), vType))
				vEntries.forEachIndexed { vIndex, vEntry ->
					mBlock += KIndexSet(KRead(vArray), KConst(vIndex, kIntType), coerce(KStaticRead(vEntry), KObjType))
				}
				val vList = mImporter.internalFunction("enumEntriesOf")?.let { mImporter.functionFor(it) }
				if (vList == null) {
					fail(inFunction, "enum entries")
				} else {
					mBlock += KReturn(coerce(spill(KCall(vList, listOf(KRead(vArray)))), mFunction.returnType))
				}
			}
			IrSyntheticBodyKind.ENUM_VALUEOF -> {
				val vName = KRead(mFunction.params[0])
				for (vEntry in vEntries) {
					val vEntryName = KFieldRead(coerce(KStaticRead(vEntry), vBaseType), mImporter.enumNameField)
					val vMatches = KIntrinsic("ktc2_str_equals", listOf(vEntryName, vName), kBoolType)
					mBlock += KIf(vMatches, listOf(KReturn(coerce(KStaticRead(vEntry), mFunction.returnType))), emptyList())
				}
				noEnumConstant(inFunction, vName)
			}
			else -> fail(inFunction, "enum '${inFunction.name}'")
		}
	}

	/** Body of a closure's `invoke`: the captured values are read from the closure object first. */
	fun buildClosure(inBindings: List<Binding>, inSelfFunction: IrFunction?, inLambdas: Map<IrValueDeclaration, InlineLambda> = emptyMap()) {
		mBindings = inBindings
		mSelfFunction = inSelfFunction
		mInlineLambdas += inLambdas
		build()
	}

	/** What a closure or local class takes from the function it is created in. */
	private class CaptureSet {
		val values = LinkedHashMap<IrValueDeclaration, KLocal>()
		val functions = LinkedHashMap<IrFunction, LocalFunction>()
		val lambdas = LinkedHashMap<IrValueDeclaration, InlineLambda>()
	}

	/**
	 * Sorts the free variables [inFree] into [outSet]. A lambda bound to an `inline` parameter
	 * (`crossinline`) is not a value: it is passed along to be expanded inside the closure, and
	 * what it uses itself is captured too.
	 */
	private fun collectCaptures(inFree: List<Any>, outSet: CaptureSet) {
		for (vFree in inFree) {
			when (vFree) {
				is IrValueDeclaration -> {
					val vLocal = mLocals[vFree]
					val vLambda = mInlineLambdas[vFree]
					if (vLocal != null) {
						outSet.values[vFree] = vLocal
					} else if (vLambda != null && outSet.lambdas.put(vFree, vLambda) == null) {
						collectCaptures(mImporter.captures.freeVariables(vLambda.expression.function), outSet)
					}
				}
				is IrFunction -> mLocalFunctions[vFree]?.let { outSet.functions[vFree] = it }
			}
		}
	}

	/** Body of the `invoke` of a function reference: forwards to [inTarget] with the bound values first. */
	fun buildReferenceInvoke(inTarget: KFunction, inBoundFields: List<KField>) {
		val vSelf = KRead(mFunction.params[0])
		val vArguments = inBoundFields.map<KField, KExpr> { KFieldRead(vSelf, it) } + mFunction.params.drop(1).map { KRead(it) }
		val vCall = KCall(inTarget, vArguments.mapIndexed { vIndex, vValue -> coerce(vValue, inTarget.params[vIndex].type) })
		if (mFunction.returnType == KUnitType) {
			mBlock += KEval(vCall)
		} else {
			mBlock += KReturn(coerce(vCall, mFunction.returnType))
		}
	}

	fun build() {
		val vIr = mIrFunction!!
		mFunction.params.forEach { mUsedNames[it.cName] = 1 }
		// Calling a top-level function is a use of its file: the file's properties get their values first.
		if (vIr is IrSimpleFunction && vIr.parent is IrFile) mImporter.fileEnsureFor(mFunction.unit, mFunction)?.let { mBlock += KEval(KCall(it, emptyList())) }
		val vOffset = mFunction.params.size - vIr.parameters.size
		// The first parameters of a function reference's `invoke` are its bound values: they come from the closure, below.
		vIr.parameters.forEachIndexed { vIndex, vParam -> if (vIndex + vOffset >= 0) mLocals[vParam] = mFunction.params[vIndex + vOffset] }
		mCellVariables += mImporter.captures.cellVariables(vIr)
		for (vBinding in mBindings) {
			val vLocal = KLocal(freshName("captured"), vBinding.field.type)
			mTemps += vLocal
			mBlock += KLet(vLocal, KFieldRead(fieldTarget(KRead(mFunction.params[0]), vBinding.field), vBinding.field))
			when (val vDeclaration = vBinding.declaration) {
				is IrValueDeclaration -> {
					mLocals[vDeclaration] = vLocal
					vBinding.cell?.let { mCells[vDeclaration] = it }
				}
				is IrFunction -> mLocalFunctions[vDeclaration] = LocalFunction(vBinding.invoke!!, vLocal)
			}
		}
		mSelfFunction?.let { mLocalFunctions[it] = LocalFunction(mFunction, mFunction.params[0]) }
		when {
			vIr is IrConstructor && mFunction.returnType is KStructType -> buildStructConstructor(vIr)
			vIr is IrConstructor -> {
				vIr.parentAsClass.thisReceiver?.let { mLocals[it] = mFunction.params[0] }
				(vIr.body as? IrBlockBody)?.statements?.forEach { lowerStatement(it) }
			}
			vIr.origin == IrDeclarationOrigin.DEFAULT_PROPERTY_ACCESSOR -> buildDefaultAccessor(vIr as IrSimpleFunction)
			vIr is IrSimpleFunction && vIr.isTailrec && vIr.body !is IrSyntheticBody -> buildTailRecursive(vIr)
			else -> buildBody(vIr)
		}
	}

	private fun buildBody(inFunction: IrFunction) {
		when (val vBody = inFunction.body) {
			is IrBlockBody -> vBody.statements.forEach { lowerStatement(it) }
			is IrExpressionBody -> emitReturn(vBody.expression)
			is IrSyntheticBody -> buildEnumSynthetic(inFunction, vBody.kind)
			else -> fail(inFunction, "function without a body")
		}
	}

	/** The `tailrec` function being built, and the loop its body runs in. */
	private var mTailFunction: IrSimpleFunction? = null
	private var mTailLoop = -1

	/**
	 * A `tailrec` function is a loop: its parameters become variables, and `return f(args)`
	 * gives them their new values and goes round again instead of calling.
	 */
	private fun buildTailRecursive(inFunction: IrSimpleFunction) {
		for (vParam in inFunction.parameters) {
			val vGiven = mLocals[vParam] ?: continue
			val vVariable = KLocal(freshName(if (vParam.kind == IrParameterKind.Regular) vParam.name.asString() else "self"), vGiven.type)
			mBlock += KLet(vVariable, KRead(vGiven))
			mLocals[vParam] = vVariable
		}
		mTailFunction = inFunction
		mTailLoop = mNextLoopId++
		val vBody = inBlock { buildBody(inFunction) }
		mBlock += KLoop(mTailLoop, vBody + KBreak(mTailLoop), emptyList())
		mTailFunction = null
	}

	/** `return f(args)` in the `tailrec` function `f`: true when it was turned into another round of the loop. */
	private fun tailCall(inValue: IrExpression?): Boolean {
		val vFunction = mTailFunction ?: return false
		val vCall = inValue as? IrCall ?: return false
		// Out of a `try` or an expanded inline function the jump would skip what they have to do: an ordinary call then.
		if (vCall.symbol.owner !== vFunction || mControl.isNotEmpty() || vCall.arguments.any { it == null }) return false
		val vValues = lowerOperands(vCall.arguments.map { it!! }).map { spill(it) }
		vFunction.parameters.forEachIndexed { vIndex, vParam ->
			val vVariable = mLocals[vParam] ?: return@forEachIndexed
			mBlock += KSet(vVariable, coerce(vValues[vIndex], vVariable.type))
		}
		mBlock += KContinue(mTailLoop)
		return true
	}

	/**
	 * A struct constructor returns the new value. The primary one gives each field its property
	 * initializer and then runs the `init` blocks, which see the finished value as `this`; a
	 * secondary one starts from the value the constructor it delegates to returns.
	 */
	private fun buildStructConstructor(inConstructor: IrConstructor) {
		val vClass = inConstructor.parentAsClass
		val vStruct = (mFunction.returnType as KStructType).struct
		val vSelf = KLocal(freshName("self"), mFunction.returnType)
		if (!inConstructor.isPrimary) {
			for (vStatement in (inConstructor.body as? IrBlockBody)?.statements ?: emptyList()) {
				when (vStatement) {
					is IrDelegatingConstructorCall -> {
						val vDelegate = vStatement.symbol.owner
						val vTarget = mImporter.functionFor(vDelegate) ?: return run { fail(vStatement, "constructor of '${vClass.name}'") }
						mBlock += KLet(vSelf, callFunction(vStatement, vTarget, vDelegate, vStatement.arguments, null))
						vClass.thisReceiver?.let { mLocals[it] = vSelf }
					}
					is IrInstanceInitializerCall -> {}
					else -> lowerStatement(vStatement)
				}
			}
			mBlock += KReturn(KRead(vSelf))
			return
		}
		val vValues = ArrayList<KExpr>()
		for (vProperty in vClass.declarations.filterIsInstance<IrProperty>()) {
			val vBacking = vProperty.backingField ?: continue
			val vField = mImporter.fieldFor(vBacking, null) ?: continue
			val vInit = vBacking.initializer?.expression
			vValues += if (vInit == null) fail(vProperty, "property without initializer") else spill(coerce(lowerPure(vInit), vField.type))
		}
		if (vValues.size != vStruct.fields.size) return
		val vInits = vClass.declarations.filterIsInstance<IrAnonymousInitializer>()
		if (vInits.isEmpty()) {
			mBlock += KReturn(KStructNew(vStruct, vValues))
			return
		}
		mBlock += KLet(vSelf, KStructNew(vStruct, vValues))
		vClass.thisReceiver?.let { mLocals[it] = vSelf }
		for (vInit in vInits) vInit.body.statements.forEach { lowerStatement(it) }
		mBlock += KReturn(KRead(vSelf))
	}

	/** A property accessor without a body reads or writes the backing field. */
	private fun buildDefaultAccessor(inAccessor: IrSimpleFunction) {
		val vSelfClass = (mFunction.params[0].type as? KClassType)?.cls
		val vField = inAccessor.correspondingPropertySymbol?.owner?.backingField?.let { mImporter.fieldIn(vSelfClass, it) }
		if (vField == null) {
			fail(inAccessor, "accessor of a property without a field")
			return
		}
		val vSelf = KRead(mFunction.params[0])
		if (mFunction.params.size == 1) {
			val vProperty = inAccessor.correspondingPropertySymbol?.owner
			val vRead: KExpr = KFieldRead(vSelf, vField)
			mBlock += KReturn(if (vProperty?.isLateinit == true) initialized(vRead, vProperty.name.asString()) else vRead)
		} else {
			mBlock += KFieldSet(vSelf, vField, KRead(mFunction.params[1]))
		}
	}

	// ==================
	// MARK: Helpers
	// ==================

	/** "File.kt:12" for [inElement], for reports. */
	private fun originOf(inElement: IrElement): String? {
		if (inElement.startOffset < 0) return null
		return java.io.File(mFile.fileEntry.name).name + ":" + (mFile.fileEntry.getLineNumber(inElement.startOffset) + 1)
	}

	private fun fail(inElement: IrElement, inWhat: String): KExpr {
		mImporter.unsupported(mFile, inElement, inWhat)
		return KUnitValue
	}

	private fun freshName(inBase: String): String {
		val vBase = cIdentifier(inBase)
		val vCount = mUsedNames.merge(vBase, 1, Int::plus)!!
		if (vCount == 1) return vBase
		return freshName("${vBase}_$vCount")
	}

	private fun newTemp(inType: KType): KLocal = KLocal(freshName("t"), inType).also {
		it.isTemporary = true
		mTemps += it
	}

	/** True when [inValue] cannot be changed by a later statement. */
	private fun isStable(inValue: KExpr): Boolean = when (inValue) {
		is KConst, KUnitValue, is KNull, is KStringLit, is KStaticRead -> true
		is KRead -> inValue.local in mTemps
		is KRefCast -> isStable(inValue.operand)
		is KOptSome -> isStable(inValue.operand)
		is KOptValue -> isStable(inValue.operand)
		else -> false
	}

	/** Names [inValue] with a temporary unless it already cannot change. */
	private fun spill(inValue: KExpr): KExpr {
		if (isStable(inValue)) return inValue
		if (inValue.type == KUnitType || inValue.type == KNothingType) {
			if (inValue.isImpure()) mBlock += KEval(inValue)
			return KUnitValue
		}
		val vTemp = newTemp(inValue.type)
		mBlock += KLet(vTemp, inValue)
		return KRead(vTemp)
	}

	private inline fun inBlock(inFill: () -> Unit): List<KStmt> {
		val vSaved = mBlock
		val vBlock = ArrayList<KStmt>()
		mBlock = vBlock
		try {
			inFill()
		} finally {
			mBlock = vSaved
		}
		return vBlock
	}

	private fun typeOf(inExpression: IrExpression): KType =
		kType(inExpression.type) ?: fail(inExpression, "type '${inExpression.type.render()}'").type

	private fun primOf(inExpression: KExpr): KPrim? = (inExpression.type as? KPrimType)?.prim

	private fun convert(inValue: KExpr, inTo: KPrim): KExpr {
		val vFrom = primOf(inValue) ?: return inValue
		if (vFrom == inTo) return inValue
		if (inValue is KConst && inValue.value is Char && inTo == KPrim.INT) return KConst(inValue.value.code, kIntType)
		if (inValue is KConst && inValue.value is Number && inTo != KPrim.BOOLEAN && inTo != KPrim.CHAR && !vFrom.isUnsigned && !inTo.isUnsigned) {
			val vNumber = inValue.value
			val vFolded: Any? = when (inTo) {
				KPrim.INT -> if (vFrom.isFloating) null else vNumber.toInt()
				KPrim.LONG -> if (vFrom.isFloating) null else vNumber.toLong()
				KPrim.DOUBLE -> if (vFrom == KPrim.LONG) null else vNumber.toDouble()
				else -> null
			}
			if (vFolded != null) return KConst(vFolded, KPrimType(inTo))
		}
		return KConvert(inValue, vFrom, inTo)
	}

	/**
	 * Adapts [inValue] to the static type [inTo]: a pointer cast between reference types, and
	 * wrapping / unwrapping of nullable primitives. K2 leaves these conversions implicit.
	 */
	private fun coerce(inValue: KExpr, inTo: KType): KExpr {
		val vFrom = inValue.type
		// `Unit` where an object is asked for (the result of a lambda that has none, kept as a `T`): the runtime's one.
		if (inValue === KUnitValue && inTo is KRefType) {
			return KIntrinsic("ktc2_unit_object", emptyList(), inTo).also { it.template = "((${if (inTo == KStringType) "ktc2_String" else "ktc2_Obj"}*)&ktc2_unit_object)" }
		}
		if (vFrom == inTo || inValue === KUnitValue) return inValue
		return when {
			inTo is KRefType && vFrom is KRefType -> if (inValue is KNull) KNull(inTo) else KRefCast(inValue, inTo)
			// A struct where an object is needed (Any, an interface, a nullable): it is put in its box.
			// A value of a `@SimpleEnum` class: the object kept for its entry.
			vFrom is KStructType && inTo is KRefType && mImporter.simpleEnumOf(vFrom.struct) != null -> {
				mImporter.boxFor(vFrom.struct)
				coerce(spill(KCall(vFrom.struct.boxFunction!!, listOf(if (inValue.isImpure()) spill(inValue) else inValue))), inTo)
			}
			vFrom is KStructType && inTo is KRefType -> {
				val vBox = mImporter.boxFor(vFrom.struct)
				mImporter.instantiate(vBox)
				val vObject = newTemp(KClassType(vBox))
				mBlock += KLet(vObject, KAlloc(vBox))
				mBlock += KFieldSet(KRead(vObject), vBox.fields[0], if (inValue.isImpure()) spill(inValue) else inValue)
				coerce(KRead(vObject), inTo)
			}
			// And back: the value in the box.
			inTo is KStructType && vFrom is KRefType -> {
				val vBox = mImporter.boxFor(inTo.struct)
				val vObject = if (inValue.isImpure()) spill(inValue) else inValue
				KFieldRead(if (vFrom == KClassType(vBox)) vObject else KRefCast(vObject, KClassType(vBox)), vBox.fields[0])
			}
			// A struct seen as another value type only happens in a branch that is never taken
			// (`if (element is Char)` in generic code): anything of the right type will do.
			(vFrom is KStructType || inTo is KStructType) && vFrom !is KRefType && inTo !is KRefType && inTo != KUnitType && inTo != KNothingType -> KZero(inTo)
			inTo == KPtrType && inValue is KNull -> KNull(KPtrType)
			// An address held as Any (an element of a List<CPointer<…>>) is boxed like a Long.
			inTo is KRefType && vFrom == KPtrType -> coerce(spill(KIntrinsic("ktc2_box_ptr", listOf(if (inValue.isImpure()) spill(inValue) else inValue), KObjType, isPure = false)), inTo)
			// A struct value given where C wants a pointer: the address of the struct inside it.
			inTo == KPtrType && vFrom is KArrayType -> KIntrinsic("ktc2_cvalue_ptr", listOf(inValue), KPtrType)
			inTo == KPtrType && vFrom is KRefType -> KIntrinsic("ktc2_unbox_ptr", listOf(if (inValue.isImpure()) spill(inValue) else inValue), KPtrType)
			inTo == KObjType && vFrom is KPrimType -> spill(box(if (inValue.isImpure()) spill(inValue) else inValue))
			inTo == KObjType && vFrom is KOptType -> {
				val vOptional = spill(inValue)
				val vBoxed = newTemp(KObjType)
				mBlock += KLet(vBoxed, null)
				mBlock += KIf(KOptHas(vOptional), listOf(KSet(vBoxed, box(KOptValue(vOptional)))), listOf(KSet(vBoxed, KNull(KObjType))))
				KRead(vBoxed)
			}
			inTo is KPrimType && vFrom == KObjType -> unbox(if (inValue.isImpure()) spill(inValue) else inValue, inTo.prim)
			inTo is KOptType && vFrom == KObjType -> {
				val vObject = spill(inValue)
				KSelect(KRefEquals(vObject, KNull(KObjType)), KNull(inTo), KOptSome(unbox(vObject, inTo.prim), inTo), inTo)
			}
			inTo is KOptType && inValue is KNull -> KNull(inTo)
			inTo is KOptType && vFrom is KPrimType -> KOptSome(convert(if (inValue.isImpure()) spill(inValue) else inValue, inTo.prim), inTo)
			inTo is KPrimType && vFrom is KOptType -> convert(KOptValue(if (inValue.isImpure()) spill(inValue) else inValue), inTo.prim)
			else -> inValue
		}
	}

	/** A new box object holding the primitive [inValue]. */
	private fun box(inValue: KExpr): KExpr =
		KIntrinsic("ktc2_box_${primOf(inValue)!!.name.lowercase()}", listOf(inValue), KObjType, isPure = false)

	/** The primitive inside a box; fails when the object is not a box of that kind. */
	private fun unbox(inObject: KExpr, inPrim: KPrim): KExpr =
		KIntrinsic("ktc2_unbox_${inPrim.name.lowercase()}", listOf(inObject), KPrimType(inPrim))

	/** Common arithmetic type of two primitives (Kotlin's numeric promotion). */
	private fun promote(inLeft: KPrim, inRight: KPrim): KPrim = when {
		inLeft == KPrim.BOOLEAN && inRight == KPrim.BOOLEAN -> KPrim.BOOLEAN
		inLeft == KPrim.ULONG || inRight == KPrim.ULONG -> KPrim.ULONG
		inLeft.isUnsigned && inRight.isUnsigned -> KPrim.UINT
		inLeft == KPrim.DOUBLE || inRight == KPrim.DOUBLE -> KPrim.DOUBLE
		inLeft == KPrim.FLOAT || inRight == KPrim.FLOAT -> KPrim.FLOAT
		inLeft == KPrim.LONG || inRight == KPrim.LONG -> KPrim.LONG
		else -> KPrim.INT
	}

	// ==================
	// MARK: Statements
	// ==================

	private fun lowerStatement(inStatement: IrStatement) {
		when (inStatement) {
			is IrVariable -> {
				val vType = kType(inStatement.type) ?: run {
					fail(inStatement, "local of type '${inStatement.type.render()}'")
					return
				}
				val vInit = inStatement.initializer?.let { coerce(lowerRhs(it), vType) }
				if (!inStatement.isVar && isUncertain(inStatement.initializer)) mUncertain += inStatement
				forceOnStack(inStatement, vInit)
				if (inStatement in mCellVariables && vType != KUnitType && vType != KNothingType) {
					// Shared with a closure: the value lives in a cell both sides point to.
					val (vCellClass, vValueField) = mImporter.cellFor(vType)
					val vCell = KLocal(freshName(inStatement.name.asString()), KClassType(vCellClass))
					mTemps += vCell
					val vValue = vInit?.let { if (it.isImpure()) spill(it) else it }
					mBlock += KLet(vCell, KAlloc(vCellClass))
					if (vValue != null) mBlock += KFieldSet(KRead(vCell), vValueField, vValue)
					mLocals[inStatement] = vCell
					mCells[inStatement] = vValueField
					return
				}
				val vLocal = KLocal(freshName(inStatement.name.asString()), if (vType == KNothingType) KObjType else vType)
				mLocals[inStatement] = vLocal
				if (vType == KNothingType) {
					// It never gets a value (`val key = error("…")`): what follows is not reached, but may name it.
					vInit?.let { discard(it) }
					mBlock += KLet(vLocal, KNull(KObjType))
				} else if (vType == KUnitType) {
					vInit?.let { discard(it) }
				} else {
					mBlock += KLet(vLocal, vInit)
				}
			}
			is IrExpression -> discard(lowerRhs(inStatement))
			is IrSimpleFunction -> makeClosure(inStatement, inStatement, inStatement)?.let { (vHolder, vInvoke) ->
				mLocalFunctions[inStatement] = LocalFunction(vInvoke, vHolder)
			}
			is IrClass -> declareLocalClass(inStatement)
			// `val x by delegate`: the delegate is a local, and reading or writing `x` calls a local function.
			is IrLocalDelegatedProperty -> {
				inStatement.delegate?.let { lowerStatement(it) }
				for (vAccessor in listOfNotNull(inStatement.getter, inStatement.setter)) {
					makeClosure(vAccessor, vAccessor, vAccessor)?.let { (vHolder, vInvoke) -> mLocalFunctions[vAccessor] = LocalFunction(vInvoke, vHolder) }
				}
			}
			else -> fail(inStatement, "local declaration")
		}
	}

	/** Keeps the side effect of an unused value, drops pure expressions. */
	private fun discard(inValue: KExpr) {
		// A value nobody takes is still computed when computing it can fail: `1 / zero`, `array[9]`.
		if (inValue.isImpure() || inValue.anyNode { it.isRuntimeCheck() }) mBlock += KEval(inValue)
	}

	private fun emitReturn(inValue: IrExpression?) {
		if (tailCall(inValue)) return
		if (inValue == null || mFunction.returnType == KUnitType) {
			inValue?.let { discard(lowerRhs(it)) }
			mBlock += KReturn(null)
		} else {
			mBlock += KReturn(coerce(lowerRhs(inValue), mFunction.returnType))
		}
	}

	// ==================
	// MARK: Expressions
	// ==================

	/** Lowers to a pure expression, naming the result of a call with a temporary. */
	private fun lowerPure(inExpression: IrExpression): KExpr {
		val vValue = lowerRhs(inExpression)
		return if (vValue.isImpure()) spill(vValue) else vValue
	}

	/** Names the already-lowered [ioResults] that a statement emitted after index [inMark] could change. */
	private fun protect(ioResults: MutableList<KExpr>, inMark: Int) {
		if (mBlock.size <= inMark) return
		var vInsertAt = inMark
		for (vIndex in ioResults.indices) {
			val vEarlier = ioResults[vIndex]
			if (isStable(vEarlier)) continue
			val vTemp = newTemp(vEarlier.type)
			mBlock.add(vInsertAt++, KLet(vTemp, vEarlier))
			ioResults[vIndex] = KRead(vTemp)
		}
	}

	/**
	 * Lowers operands left to right. If a later operand needed statements, the earlier
	 * operands are named first so those statements cannot change what they read.
	 *
	 * @param inLastMayBeImpure leave the last operand as a call when it is one
	 */
	private fun lowerOperands(inExpressions: List<IrExpression>, inLastMayBeImpure: Boolean = false): MutableList<KExpr> {
		val vResults = ArrayList<KExpr>()
		inExpressions.forEachIndexed { vIndex, vExpression ->
			val vMark = mBlock.size
			val vIsLast = vIndex == inExpressions.size - 1
			val vValue = if (vIsLast && inLastMayBeImpure) lowerRhs(vExpression) else lowerPure(vExpression)
			protect(vResults, vMark)
			if (vValue.isImpure()) vResults.indices.forEach { vResults[it] = spill(vResults[it]) }
			vResults += vValue
		}
		return vResults
	}

	/** Lowers to an expression that may be impure; the caller consumes it immediately. */
	private fun lowerRhs(inExpression: IrExpression): KExpr = when (inExpression) {
		is IrConst -> lowerConst(inExpression)
		is IrGetValue -> {
			val vDeclaration = inExpression.symbol.owner
			val vLocal = mLocals[vDeclaration]
			val vCell = mCells[vDeclaration]
			when {
				vLocal == null -> fail(inExpression, "reference to '${vDeclaration.name}'")
				vCell != null -> KFieldRead(KRead(vLocal), vCell)
				vLocal.type == KUnitType -> KUnitValue
				else -> KRead(vLocal)
			}.let { vRead -> if ((vDeclaration as? IrVariable)?.isLateinit == true && vLocal != null) initialized(vRead, vDeclaration.name.asString()) else vRead }
		}
		is IrFunctionExpression -> makeClosure(inExpression, inExpression.function, null)?.let { KRead(it.first) } ?: KUnitValue
		is IrFunctionReference -> lowerFunctionReference(inExpression)
		is IrRichFunctionReference -> lowerRichFunctionReference(inExpression)
		// The property a delegate is told about (`getValue(thisRef, property)`): there is no
		// reflection here, so it is passed as null - fine for the delegates that ignore it.
		is IrRichPropertyReference -> if (inExpression.origin == IrStatementOrigin.PROPERTY_REFERENCE_FOR_DELEGATE) {
			propertyObject(inExpression, (inExpression.reflectionTargetSymbol?.owner as? IrDeclarationWithName)?.name?.asString())
		} else {
			// `String::length`, `item::name` as a value: the function that reads the property.
			val vBound = lowerOperands(inExpression.boundValues).mapIndexed { vIndex, vValue ->
				val vType = kType(inExpression.getterFunction.parameters[vIndex].type) ?: vValue.type
				KLocal(freshName("bound"), vType).also {
					mTemps += it
					mBlock += KLet(it, coerce(vValue, vType))
				}
			}
			val vGetter = makeClosure(inExpression, inExpression.getterFunction, null, null, vBound)?.let { KRead(it.first) } ?: return KUnitValue
			val vSetter = inExpression.setterFunction
			val vKind = closed(inExpression.type).classOrNull?.owner?.kotlinFqName?.asString()
			if (vSetter == null || vKind == null || !vKind.startsWith("kotlin.reflect.KMutableProperty")) return vGetter
			// A reference to a `var` as a KMutableProperty: the getter, and the setter beside it (`MutableReference0`).
			val vSetClosure = makeClosure(inExpression, vSetter, null, null, vBound)?.let { KRead(it.first) } ?: return vGetter
			val vArguments = mImporter.argumentsOf(closed(inExpression.type))
			val vFactory = mImporter.internalFunction(if (vArguments.size == 1) "mutableReference0" else "mutableReference1") ?: return vGetter
			val vTarget = mImporter.functionFor(vFactory, mImporter.substFor(vFactory, emptyList(), vArguments)) ?: return vGetter
			spill(KCall(vTarget, listOf(coerce(spill(vGetter), vTarget.params[0].type), coerce(spill(vSetClosure), vTarget.params[1].type))))
		}
		is IrPropertyReference -> propertyObject(inExpression, inExpression.symbol.owner.name.asString())
		is IrLocalDelegatedPropertyReference -> propertyObject(inExpression, inExpression.symbol.owner.name.asString())
		is IrSetValue -> {
			val vLocal = mLocals[inExpression.symbol.owner]
			val vCell = mCells[inExpression.symbol.owner]
			if (vLocal == null) {
				fail(inExpression, "assignment to '${inExpression.symbol.owner.name}'")
			} else if (vCell != null) {
				mBlock += KFieldSet(KRead(vLocal), vCell, coerce(lowerRhs(inExpression.value), vCell.type))
				KUnitValue
			} else {
				mBlock += KSet(vLocal, coerce(lowerRhs(inExpression.value), vLocal.type))
				KUnitValue
			}
		}
		is IrCall -> lowerCall(inExpression)
		is IrConstructorCall -> lowerConstructorCall(inExpression)
		is IrDelegatingConstructorCall -> lowerDelegatingConstructorCall(inExpression)
		is IrInstanceInitializerCall -> lowerInstanceInitializer(inExpression)
		is IrGetField -> lowerGetField(inExpression)
		is IrSetField -> lowerSetField(inExpression)
		is IrWhen -> lowerWhen(inExpression)
		is IrContainerExpression -> lowerContainer(inExpression)
		is IrStringConcatenation -> concat(lowerParts(inExpression.arguments))
		is IrReturn -> {
			val vInline = mInlineTargets[inExpression.returnTargetSymbol]
			val vResultExpression = inExpression.value.takeUnless { isUnitObject(it) }
			if (vInline == null && mControl.none { it is FinallyMark }) {
				emitReturn(vResultExpression)
			} else if (vInline == null) {
				// The result is computed first, then every enclosing `finally` runs, then the function returns.
				val vValue = if (vResultExpression == null || mFunction.returnType == KUnitType) {
					vResultExpression?.let { discard(lowerRhs(it)) }
					null
				} else {
					spill(coerce(lowerRhs(vResultExpression), mFunction.returnType))
				}
				runFinallys { false }
				mBlock += KReturn(vValue)
			} else {
				val vValue = lowerRhs(inExpression.value)
				if (vInline.result != null && vValue !== KUnitValue) mBlock += KSet(vInline.result, coerce(vValue, vInline.result.type)) else discard(vValue)
				runFinallys { it is InlineMark && it.symbol === inExpression.returnTargetSymbol }
				mBlock += KBreak(vInline.blockId)
			}
			KUnitValue
		}
		is IrVararg -> lowerVararg(inExpression)
		is IrWhileLoop -> {
			val vId = loopId(inExpression)
			val vBody = inBlock {
				val vCondition = lowerPure(inExpression.condition)
				mBlock += KIf(not(vCondition), listOf(KBreak(vId)), emptyList())
				inLoopBody(inExpression) { inExpression.body?.let { discard(lowerRhs(it)) } }
			}
			mBlock += KLoop(vId, vBody, emptyList())
			KUnitValue
		}
		is IrDoWhileLoop -> {
			val vId = loopId(inExpression)
			val vBody = inBlock { inLoopBody(inExpression) { inExpression.body?.let { discard(lowerRhs(it)) } } }
			val vLatch = inBlock {
				val vCondition = lowerPure(inExpression.condition)
				mBlock += KIf(not(vCondition), listOf(KBreak(vId)), emptyList())
			}
			mBlock += KLoop(vId, vBody, vLatch)
			KUnitValue
		}
		is IrBreak -> {
			runFinallys { it is LoopMark && it.loop === inExpression.loop }
			mBlock += KBreak(loopId(inExpression.loop))
			KUnitValue
		}
		is IrContinue -> {
			runFinallys { it is LoopMark && it.loop === inExpression.loop }
			mBlock += KContinue(loopId(inExpression.loop))
			KUnitValue
		}
		is IrTypeOperatorCall -> lowerTypeOperator(inExpression)
		is IrThrow -> {
			mBlock += KThrow(coerce(spill(lowerPure(inExpression.value)), KObjType))
			KUnitValue
		}
		is IrTry -> lowerTry(inExpression)
		is IrGetObjectValue -> lowerObject(inExpression)
		// Under the name the runtime has for the class (an exception has the JVM's there), so that it equals what `x::class` gives.
		is IrClassReference -> classValueOf(inExpression, closed(inExpression.classType))
		is IrGetClass -> {
			// `x::class`: of an object, the class it is of; of a number or a text, the one its type names.
			val vValue = lowerPure(inExpression.argument)
			if (vValue.type is KRefType && vValue.type != KStringType) {
				val vFactory = mImporter.internalFunction("classOf")?.let { mImporter.functionFor(it) }
				if (vFactory == null) fail(inExpression, "class reference") else spill(KCall(vFactory, listOf(coerce(vValue, KObjType))))
			} else {
				discard(vValue)
				classObject(inExpression, closed(inExpression.argument.type).makeNotNull().classOrNull?.owner?.kotlinFqName?.asString())
			}
		}
		is IrGetEnumValue -> mImporter.simpleEnumOf(inExpression.symbol.owner.parentAsClass)?.let { vEnum ->
			val vEntry = vEnum.entries.firstOrNull { it.name == inExpression.symbol.owner.name.asString() }
			if (vEntry == null) fail(inExpression, "enum entry '${inExpression.symbol.owner.name}'") else coerce(vEnum.constant(vEntry), typeOf(inExpression))
		} ?: mImporter.enumEntryFor(inExpression.symbol.owner)?.let {
			ensureInitialized(inExpression.symbol.owner.parentAsClass)
			coerce(KStaticRead(it), typeOf(inExpression))
		}
			?: fail(inExpression, "enum entry '${inExpression.symbol.owner.name}'")
		is IrEnumConstructorCall -> lowerEnumConstructorCall(inExpression)
		else -> fail(inExpression, inExpression::class.simpleName?.removeSuffix("Impl") ?: "expression")
	}

	private fun isUnitObject(inExpression: IrExpression): Boolean =
		inExpression is IrGetObjectValue && inExpression.symbol.owner.kotlinFqName.asString() == "kotlin.Unit"

	private fun loopId(inLoop: IrLoop): Int = mLoops.getOrPut(inLoop) { mNextLoopId++ }

	private fun lowerConst(inConst: IrConst): KExpr {
		val vValue = inConst.value
		if (vValue == null) return KNull(KObjType)
		if (vValue is String) return KStringLit(vValue)
		val vType = typeOf(inConst) as? KPrimType ?: return fail(inConst, "constant of type '${inConst.type.render()}'")
		return KConst(vValue, vType)
	}

	/** An `object` or an enum class is created when it is first used: here, if not before. */
	/** [inGlobal], after making sure the top-level properties of its file have their values (a constant needs none). */
	private fun ensuredFile(inGlobal: KGlobal): KGlobal {
		if (inGlobal.constInit == null) mImporter.fileEnsureFor(inGlobal.unit, mFunction)?.let { mBlock += KEval(KCall(it, emptyList())) }
		return inGlobal
	}

	private fun ensureInitialized(inClass: IrClass) {
		mImporter.ensureFor(inClass)?.let { mBlock += KEval(KCall(it, emptyList())) }
	}

	private fun lowerObject(inExpression: IrGetObjectValue): KExpr {
		if (isUnitObject(inExpression)) return KUnitValue
		// The companion of a built-in class is only a name to hang extensions on; as a value (`Int` passed as an
		// `Any`) it is one object of the runtime, the same wherever it is named.
		inExpression.symbol.owner.let {
			if (it.isCompanion && it.fileOrNull == null) {
				val vIndex = kBuiltInCompanions.indexOf(it.parentAsClass.name.asString()).let { vFound -> if (vFound < 0) kBuiltInCompanions.size else vFound }
				return KIntrinsic("ktc2_companion", emptyList(), KObjType).also { vObject -> vObject.template = "(&ktc2_companions[$vIndex])" }
			}
		}
		val vInstance = mImporter.objectFor(inExpression.symbol.owner) ?: return fail(inExpression, "object '${inExpression.symbol.owner.name}'")
		ensureInitialized(inExpression.symbol.owner)
		return KStaticRead(vInstance)
	}

	private fun lowerContainer(inBlock: IrContainerExpression): KExpr {
		if (inBlock.origin == IrStatementOrigin.FOR_LOOP) return lowerFor(inBlock)
		val vStatements = inBlock.statements
		for (vIndex in 0 until vStatements.size - 1) lowerStatement(vStatements[vIndex])
		val vLast = vStatements.lastOrNull() ?: return KUnitValue
		if (vLast is IrExpression && !inBlock.type.isUnit()) return lowerRhs(vLast)
		lowerStatement(vLast)
		return KUnitValue
	}

	// ============
	//  Type operators

	private fun typeTargetOf(inType: IrType): KTypeTarget? {
		val vBase = inType.makeNotNull()
		if (vBase.isString()) return KStringTarget
		(kType(vBase) as? KPrimType)?.let { return KBoxTarget(it.prim) }
		val vClass = vBase.classOrNull?.owner ?: return null
		if (vClass.isInterface || isNumberClass(vClass)) return KInterfaceTarget(mImporter.interfaceFor(vClass))
		if (vClass.kotlinFqName.asString() == "kotlin.Enum") return KClassTarget(mImporter.enumBase())
		// An object holding a struct is its box.
		(kType(vBase) as? KStructType)?.let { return KClassTarget(mImporter.boxFor(it.struct).also { vBox -> mImporter.instantiate(vBox) }) }
		// Kotlin tests the class, whatever its type arguments: `x is Pair<A, B>` holds for every pair.
		return mImporter.classFor(vClass, mImporter.argumentsOf(closed(vBase)))?.let { KClassTarget(it, mImporter.hasInstantiations(vClass)) }
	}

	/** `inValue is inType`, including the nullable and `Any` cases. */
	private fun instanceTest(inElement: IrElement, inValue: KExpr, inType: IrType): KExpr {
		val vType = closed(inType)
		val vPrim = inValue.type as? KPrimType
		if (vPrim != null) {
			// A primitive known statically (a type parameter standing for Int): the answer is a constant.
			val vTargetName = vType.classOrNull?.owner?.kotlinFqName?.asString()
			val vIsNumber = vPrim.prim != KPrim.BOOLEAN && vPrim.prim != KPrim.CHAR && !vPrim.prim.isUnsigned
			val vHolds = kType(vType.makeNotNull()) == vPrim || vTargetName == "kotlin.Any" || vTargetName == "kotlin.Comparable" ||
				(vTargetName == "kotlin.Number" && vIsNumber)
			return if (vHolds) kTrue else kFalse
		}
		// A primitive or null (a type parameter standing for `Int?`): what the type says of the number, when there is one.
		(inValue.type as? KOptType)?.let { vOpt ->
			val vTargetName = vType.classOrNull?.owner?.kotlinFqName?.asString()
			val vIsNumber = vOpt.prim != KPrim.BOOLEAN && vOpt.prim != KPrim.CHAR && !vOpt.prim.isUnsigned
			val vHolds = kType(vType.makeNotNull()) == KPrimType(vOpt.prim) || vTargetName == "kotlin.Any" || vTargetName == "kotlin.Comparable" ||
				(vTargetName == "kotlin.Number" && vIsNumber)
			val vHas = KOptHas(inValue)
			return when {
				vHolds && vType.isMarkedNullable() -> kTrue
				vHolds -> vHas
				vType.isMarkedNullable() -> KUnary(KUnaryOp.NOT, vHas, KPrimType(KPrim.BOOLEAN))
				else -> kFalse
			}
		}
		// The class of a struct value is known exactly: the answer is a constant.
		(inValue.type as? KStructType)?.let { vStructType ->
			val vTarget = vType.classOrNull?.owner ?: return kFalse
			return if (isSubtype(mImporter.structClassOf(vStructType.struct), vTarget)) kTrue else kFalse
		}
		if (inValue.type !is KRefType) return fail(inElement, "type test on a value of type '${inType.render()}'")
		// A String is a CharSequence and a Comparable.
		if (inValue.type == KStringType) {
			val vTargetName = vType.classOrNull?.owner?.kotlinFqName?.asString()
			if (vTargetName == "kotlin.CharSequence" || vTargetName == "kotlin.Comparable") {
				return if (vType.isMarkedNullable()) kTrue else not(isNull(inValue))
			}
		}
		// An array records the kind of its elements (ints, references…), which is what can be tested.
		(kType(vType.makeNotNull()) as? KArrayType)?.let { vArrayType ->
			if (inValue.type is KArrayType) return if (vType.isMarkedNullable()) kTrue else not(isNull(inValue))
			val vKind = when (val vElement = vArrayType.element) {
				is KPrimType -> "KTC2_TYPE_ARRAY_${vElement.prim.name}"
				is KRefType -> "KTC2_TYPE_ARRAY_REF"
				else -> "KTC2_TYPE_ARRAY_VALUE"
			}
			val vTest = KIntrinsic("ktc2_array_is", listOf(inValue), kBoolType).also { it.template = "ktc2_array_is($0, $vKind)" }
			return if (vType.isMarkedNullable()) KSelect(isNull(inValue), kTrue, vTest, kBoolType) else vTest
		}
		// A type parameter standing for a reference type is `Any?` here: the test always holds.
		val vIsNull = isNull(inValue)
		// So is a type parameter nothing here stands for (that of a class around a local object).
		val vIsUnbound = vType.classifierOrNull is org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
		if (vType.makeNotNull().isAny() || vIsUnbound) return if (vType.isMarkedNullable()) kTrue else not(vIsNull)
		// A `Number` is one of the boxed numbers: they have no class, the runtime knows them by their kind.
		// A `Number` is a boxed number, which has no class (the runtime knows it by its kind), or an object of a class that extends it.
		val vOfClass = KInstanceOf(inValue, typeTargetOf(vType) ?: return fail(inElement, "type test against '${inType.render()}'"))
		val vTest = if (vType.classOrNull?.owner?.let { isNumberClass(it) } == true) {
			KSelect(KIntrinsic("ktc2_is_number", listOf(inValue), kBoolType), kTrue, vOfClass, kBoolType)
		} else {
			vOfClass
		}
		return if (vType.isMarkedNullable()) KSelect(vIsNull, kTrue, vTest, kBoolType) else vTest
	}

	/** `null as T` for a `T` that is not nullable is a `NullPointerException`, as on the JVM, not a failed cast. */
	private fun nullCastCheck(inExpression: IrTypeOperatorCall, inValue: KExpr) {
		// A type parameter may stand for a nullable type: only a type that is written out says no to null.
		val vType = closed(inExpression.typeOperand)
		if (vType.isMarkedNullable() || inExpression.typeOperand.isMarkedNullable()) return
		if (vType.classifierOrNull is org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol || inExpression.typeOperand.classifierOrNull is org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol) return
		val vIsNull = isNull(inValue)
		if (vIsNull !== kFalse) mBlock += KIf(vIsNull, listOf(failure("throwNullPointer", "ktc2_fail_null")), emptyList())
	}

	private fun lowerTypeOperator(inExpression: IrTypeOperatorCall): KExpr = when (inExpression.operator) {
		IrTypeOperator.IMPLICIT_COERCION_TO_UNIT -> {
			discard(lowerRhs(inExpression.argument))
			KUnitValue
		}
		IrTypeOperator.IMPLICIT_CAST, IrTypeOperator.IMPLICIT_NOTNULL -> coerce(lowerRhs(inExpression.argument), typeOf(inExpression))
		IrTypeOperator.IMPLICIT_INTEGER_COERCION -> {
			val vTo = typeOf(inExpression) as? KPrimType
			if (vTo == null) fail(inExpression, "integer coercion") else convert(lowerPure(inExpression.argument), vTo.prim)
		}
		IrTypeOperator.INSTANCEOF -> instanceTest(inExpression, spill(lowerPure(inExpression.argument)), inExpression.typeOperand)
		IrTypeOperator.NOT_INSTANCEOF -> not(instanceTest(inExpression, spill(lowerPure(inExpression.argument)), inExpression.typeOperand))
		IrTypeOperator.CAST -> if (typeOf(inExpression) == KNothingType && inExpression.typeOperand.classifierOrNull is org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol) {
			// A cast to a type parameter that stands for `Nothing` here (`Result<Nothing>.getOrElse`): no test, as for any type parameter.
			lowerRhs(inExpression.argument)
		} else {
			val vTo = typeOf(inExpression)
			// A struct cast to anything but its own type is cast as the object it would be.
			val vValue = spill(lowerPure(inExpression.argument)).let {
				when {
					it === KUnitValue && vTo is KRefType -> coerce(it, KObjType)
					it.type is KStructType && it.type != vTo && vTo !is KRefType -> spill(coerce(it, KObjType))
					// A number, or one that may be null, cast to a type of objects: its box.
					(it.type is KPrimType || it.type is KOptType) && vTo is KRefType -> spill(coerce(it, KObjType))
					else -> it
				}
			}
			if (vValue.type == vTo && vTo !is KRefType) {
				vValue
			} else if (vValue.type == KObjType && (vTo is KPrimType || vTo is KOptType)) {
				// Unboxing checks the kind of box itself.
				coerce(vValue, vTo)
			} else if (vValue.type is KStructType && vTo is KRefType) {
				coerce(vValue, vTo)
			} else if (vValue.type is KOptType && vTo is KPrimType) {
				// `x as V` where x is a `V?` and V a number: the number that is there.
				coerce(vValue, vTo)
			} else if (vValue.type is KPrimType && vTo is KOptType && vTo.prim == (vValue.type as KPrimType).prim) {
				// `1 as Int?`: the number, as one that may be null.
				coerce(vValue, vTo)
			} else if (vValue.type is KRefType && vTo is KStructType) {
				nullCastCheck(inExpression, vValue)
				val vTest = instanceTest(inExpression, vValue, inExpression.typeOperand)
				if (vTest !== kTrue) mBlock += KIf(not(vTest), listOf(failure("throwClassCast", "ktc2_fail_cast")), emptyList())
				coerce(vValue, vTo)
			} else if (vValue.type !is KRefType || vTo !is KRefType) {
				fail(inExpression, "cast to '${inExpression.typeOperand.render()}'")
			} else {
				nullCastCheck(inExpression, vValue)
				val vTest = instanceTest(inExpression, vValue, inExpression.typeOperand)
				if (vTest !== kTrue) {
					mBlock += KIf(not(vTest), listOf(failure("throwClassCast", "ktc2_fail_cast")), emptyList())
				}
				coerce(vValue, vTo)
			}
		}
		IrTypeOperator.SAFE_CAST -> {
			val vTo = typeOf(inExpression)
			val vValue = spill(lowerPure(inExpression.argument)).let {
				if (it.type is KStructType || ((it.type is KPrimType || it.type is KOptType) && vTo is KRefType)) spill(coerce(it, KObjType)) else it
			}
			if (vValue.type is KRefType && vTo is KOptType) {
				// `x as? Int`: the number in the box when it is one of that kind, otherwise null.
				val vTest = instanceTest(inExpression, vValue, inExpression.typeOperand)
				KSelect(vTest, KOptSome(KIntrinsic("ktc2_unbox_${vTo.prim.name.lowercase()}", listOf(vValue), KPrimType(vTo.prim)), vTo), KNull(vTo), vTo)
			} else if (vValue.type is KPrimType && vTo is KOptType) {
				// A number whose kind is known: the cast holds or it does not.
				if (instanceTest(inExpression, vValue, inExpression.typeOperand) === kTrue) coerce(vValue, vTo) else KNull(vTo)
			} else if (vValue.type !is KRefType || vTo !is KRefType) {
				fail(inExpression, "safe cast to '${inExpression.typeOperand.render()}'")
			} else {
				KSelect(instanceTest(inExpression, vValue, inExpression.typeOperand), coerce(vValue, vTo), KNull(vTo), vTo)
			}
		}
		IrTypeOperator.SAM_CONVERSION -> {
			// `Comparator { a, b -> … }`: the lambda's object implements the interface itself.
			val vLambda = inExpression.argument as? IrFunctionExpression
			if (vLambda == null) {
				wrapFunctionValue(inExpression)
			} else {
				makeClosure(inExpression, vLambda.function, null, inExpression.typeOperand)?.let { coerce(KRead(it.first), typeOf(inExpression)) } ?: KUnitValue
			}
		}
		else -> fail(inExpression, "type operator ${inExpression.operator}")
	}

	// ============
	//  when / if

	private fun isElse(inBranch: IrBranch): Boolean =
		inBranch is IrElseBranch || (inBranch.condition as? IrConst)?.value == true

	private fun lowerWhen(inWhen: IrWhen): KExpr {
		val vType = typeOf(inWhen)
		trySelect(inWhen, vType)?.let { return it }
		val vResult = if (vType is KUnitType || vType is KNothingType) null else newTemp(vType)
		vResult?.let { mBlock += KLet(it, null) }
		lowerBranches(inWhen.branches, 0, vResult)
		return vResult?.let { KRead(it) } ?: KUnitValue
	}

	private fun lowerBranches(inBranches: List<IrBranch>, inIndex: Int, inResult: KLocal?) {
		if (inIndex >= inBranches.size) return
		val vBranch = inBranches[inIndex]
		if (isElse(vBranch)) {
			lowerBranchResult(vBranch.result, inResult)
			return
		}
		val vCondition = lowerPure(vBranch.condition)
		// A branch this instantiation can never take (`is Array<*>` of what is an Int here): not lowered at all,
		// for what it does with the value would not fit its type here.
		if (vCondition === kFalse) {
			lowerBranches(inBranches, inIndex + 1, inResult)
			return
		}
		val vThen = inBlock { lowerBranchResult(vBranch.result, inResult) }
		val vElse = inBlock { lowerBranches(inBranches, inIndex + 1, inResult) }
		mBlock += KIf(vCondition, vThen, vElse)
	}

	private fun lowerBranchResult(inResult: IrExpression, inTarget: KLocal?) {
		val vValue = lowerRhs(inResult)
		// A branch of type Unit in a `when` whose value is not used (its other branches give something else) has nothing to store.
		if (inTarget != null && !inResult.type.isNothing() && vValue !== KUnitValue && vValue.type != KUnitType && vValue.type != KNothingType) {
			mBlock += KSet(inTarget, coerce(vValue, inTarget.type))
		} else {
			discard(vValue)
		}
	}

	/** A `when` whose conditions and results are all pure becomes a `?:` chain. */
	private fun trySelect(inWhen: IrWhen, inType: KType): KExpr? {
		if (inType is KUnitType || inType is KNothingType) return null
		if (inWhen in mNotSelectable || !isElse(inWhen.branches.last())) return null
		if (inWhen.branches.any { it.result.type.isNothing() || it.result.type.isUnit() }) return null
		val vSaved = mBlock
		val vScratch = ArrayList<KStmt>()
		mBlock = vScratch
		val vArms = try {
			inWhen.branches.map { vBranch ->
				val vCondition = if (isElse(vBranch)) null else lowerRhs(vBranch.condition)
				vCondition to lowerRhs(vBranch.result)
			}
		} finally {
			mBlock = vSaved
		}
		if (vScratch.isNotEmpty() || vArms.any { it.first?.isImpure() == true || it.second.isImpure() || it.second === KUnitValue }) {
			mNotSelectable += inWhen
			return null
		}
		var vResult = coerce(vArms.last().second, inType)
		for (vIndex in vArms.size - 2 downTo 0) {
			vResult = KSelect(vArms[vIndex].first!!, coerce(vArms[vIndex].second, inType), vResult, inType)
		}
		return vResult
	}

	// ============
	//  Exceptions

	/** What a jump has to pass on its way out: loops, `finally` blocks, and inline expansions. */
	private sealed class Control

	private class LoopMark(val loop: IrLoop) : Control()

	private class FinallyMark(val body: IrExpression) : Control()

	private class InlineMark(val symbol: Any) : Control()

	private val mControl = ArrayList<Control>()

	private inline fun inLoopBody(inLoop: IrLoop, inFill: () -> Unit) {
		mControl += LoopMark(inLoop)
		try {
			inFill()
		} finally {
			mControl.removeLast()
		}
	}

	/**
	 * Lowers, innermost first, the `finally` blocks a jump leaves, down to the entry [inStop]
	 * accepts (the loop or expansion the jump stays inside). Each block is lowered as if the
	 * jump had already left it.
	 */
	private fun runFinallys(inStop: (Control) -> Boolean) {
		var vIndex = mControl.size - 1
		while (vIndex >= 0 && !inStop(mControl[vIndex])) {
			val vEntry = mControl[vIndex]
			if (vEntry is FinallyMark) {
				val vLeft = ArrayList(mControl.subList(vIndex, mControl.size))
				while (mControl.size > vIndex) mControl.removeLast()
				discard(lowerRhs(vEntry.body))
				mControl += vLeft
			}
			vIndex--
		}
	}

	/** A call to the platform layer's function that throws the failure, or the fatal runtime fallback. */
	/**
	 * [inValue], read from a `lateinit` property or variable called [inName]: before it was
	 * given a value the read fails with `UninitializedPropertyAccessException`, as on the JVM.
	 */
	private fun initialized(inValue: KExpr, inName: String): KExpr {
		val vValue = spill(inValue)
		val vThrower = mImporter.internalFunction("throwUninitialized")?.let { mImporter.functionFor(it) }
		val vFail: KStmt = if (vThrower != null) KEval(KCall(vThrower, listOf(KStringLit(inName)))) else KEval(KIntrinsic("ktc2_fail_null", emptyList(), KUnitType, isPure = false))
		mBlock += KIf(isNull(vValue), listOf(vFail), emptyList())
		return vValue
	}

	/** The name of the `lateinit` property [inField] is the field of; null for any other field. */
	private fun lateinitName(inField: IrField): String? = inField.correspondingPropertySymbol?.owner?.takeIf { it.isLateinit }?.name?.asString()

	private fun failure(inThrower: String, inFallback: String): KStmt {
		val vTarget = mImporter.internalFunction(inThrower)?.let { mImporter.functionFor(it) }
		return KEval(if (vTarget != null) KCall(vTarget, emptyList()) else KIntrinsic(inFallback, emptyList(), KUnitType, isPure = false))
	}

	/** The `message` and `cause` given to a constructor of `kotlin.Throwable`. */
	private fun setThrowableFields(inObject: KExpr, inConstructor: IrConstructor, inArguments: List<IrExpression?>) {
		inConstructor.parameters.forEachIndexed { vIndex, vParam ->
			val vArgument = inArguments.getOrNull(vIndex) ?: return@forEachIndexed
			val vField = when (vParam.name.asString()) {
				"message" -> mImporter.throwableMessageField
				"cause" -> mImporter.throwableCauseField
				else -> return@forEachIndexed
			}
			mBlock += KFieldSet(inObject, vField, coerce(lowerRhs(vArgument), vField.type))
		}
	}

	private fun hasPending(): KExpr = KIntrinsic("ktc2_has_pending", emptyList(), kBoolType)

	private fun takePending(): KExpr {
		val vTaken = newTemp(KObjType)
		mBlock += KLet(vTaken, KIntrinsic("ktc2_take_pending", emptyList(), KObjType, isPure = false))
		return KRead(vTaken)
	}

	/**
	 * `try` / `catch` / `finally`. The protected part is a region an exception leaves at once;
	 * after it, a parked exception is taken and matched against the `catch` clauses in order,
	 * and thrown again if none applies. A `finally` block runs after that, on every path, with
	 * any exception still in flight set aside while it runs.
	 */
	private fun lowerTry(inTry: IrTry): KExpr {
		val vType = typeOf(inTry)
		val vResult = if (vType is KUnitType || vType is KNothingType) null else newTemp(vType)
		vResult?.let { mBlock += KLet(it, null) }
		fun assign(inExpression: IrExpression) {
			val vValue = lowerRhs(inExpression)
			if (vResult != null && !inExpression.type.isNothing() && vValue !== KUnitValue) mBlock += KSet(vResult, coerce(vValue, vResult.type)) else discard(vValue)
		}

		fun handlers(inCaught: KExpr, inIndex: Int) {
			if (inIndex == inTry.catches.size) {
				mBlock += KThrow(inCaught)
				return
			}
			val vCatch = inTry.catches[inIndex]
			val vParameter = vCatch.catchParameter
			val vMatches = instanceTest(vCatch, inCaught, vParameter.type)
			val vThen = inBlock {
				val vLocalType = kType(vParameter.type) ?: KObjType
				val vLocal = KLocal(freshName(vParameter.name.asString()), vLocalType)
				mBlock += KLet(vLocal, coerce(inCaught, vLocalType))
				mLocals[vParameter] = vLocal
				assign(vCatch.result)
			}
			val vElse = inBlock { handlers(inCaught, inIndex + 1) }
			mBlock += KIf(vMatches, vThen, vElse)
		}

		fun protectedPart() {
			mBlock += KTryRegion(mNextLoopId++, inBlock { assign(inTry.tryResult) })
			if (inTry.catches.isNotEmpty()) {
				mBlock += KIf(hasPending(), inBlock { handlers(takePending(), 0) }, emptyList())
			}
		}

		val vFinally = inTry.finallyExpression
		if (vFinally == null) {
			protectedPart()
		} else {
			mControl += FinallyMark(vFinally)
			mBlock += KTryRegion(mNextLoopId++, inBlock { protectedPart() })
			mControl.removeLast()
			val vInFlight = takePending()
			discard(lowerRhs(vFinally))
			mBlock += KIf(not(isNull(vInFlight)), listOf(KThrow(vInFlight)), emptyList())
		}
		return vResult?.let { KRead(it) } ?: KUnitValue
	}

	// ============
	//  Inlined lambdas

	private fun containsBreak(inStatements: List<KStmt>, inId: Int): Boolean = inStatements.any { vStatement ->
		when (vStatement) {
			is KBreak -> vStatement.loopId == inId
			is KIf -> containsBreak(vStatement.thenBlock, inId) || containsBreak(vStatement.elseBlock, inId)
			is KLoop -> containsBreak(vStatement.body, inId) || containsBreak(vStatement.latch, inId)
			is KTryRegion -> containsBreak(vStatement.body, inId)
			else -> false
		}
	}

	/**
	 * Expands the body of [inLambda] here, with its parameters bound to [inArguments]. A `return`
	 * from the lambda assigns the result and leaves the expanded block.
	 */
	private fun inlineLambda(inLambda: IrFunctionExpression, inArguments: List<KExpr>, inResultType: KType): KExpr {
		val vFunction = inLambda.function
		vFunction.parameters.forEachIndexed { vIndex, vParam ->
			// A parameter of type `Nothing` (the element of an `emptyList<Nothing>()`) never receives a value: it is declared as a plain reference.
			val vType = (kType(vParam.type) ?: return fail(vParam, "lambda parameter of type '${vParam.type.render()}'")).let { if (it == KNothingType) KObjType else it }
			val vLocal = KLocal(freshName(vParam.name.asString().trim('<', '>')), vType)
			mBlock += KLet(vLocal, if (inArguments[vIndex].type == KNothingType || inArguments[vIndex].type == KUnitType) KNull(KObjType) else coerce(inArguments[vIndex], vType))
			mLocals[vParam] = vLocal
		}
		return expandBody(vFunction, inResultType)
	}

	/** Expands the body of [vFunction] (its parameters already bound); a `return` from it leaves the expansion. */
	private fun expandBody(vFunction: IrFunction, inResultType: KType): KExpr {
		val inLambda = vFunction
		val vResult = if (inResultType == KUnitType || inResultType == KNothingType) null else newTemp(inResultType)
		vResult?.let { mBlock += KLet(it, null) }
		val vId = mNextLoopId++
		mInlineTargets[vFunction.symbol] = InlineTarget(vResult, vId)
		mControl += InlineMark(vFunction.symbol)
		val vBody = inBlock {
			when (val vIrBody = vFunction.body) {
				is IrBlockBody -> vIrBody.statements.forEach { lowerStatement(it) }
				is IrExpressionBody -> {
					val vValue = lowerRhs(vIrBody.expression)
					if (vResult != null) mBlock += KSet(vResult, coerce(vValue, vResult.type)) else discard(vValue)
				}
				else -> fail(inLambda, "lambda without a body")
			}
		}
		mControl.removeLast()
		mInlineTargets.remove(vFunction.symbol)
		// The usual lambda returns once, at its end: then no block is needed to return from.
		val vTail = vBody.lastOrNull()
		val vHead = if (vTail is KBreak && vTail.loopId == vId) vBody.dropLast(1) else vBody
		if (containsBreak(vHead, vId)) {
			mBlock += KLoop(vId, vHead + KBreak(vId), emptyList())
		} else {
			mBlock += vHead
		}
		return vResult?.let { KRead(it) } ?: KUnitValue
	}

	// ============
	//  Arrays

	private fun arrayLength(inArray: KExpr): KExpr = KIntrinsic("ktc2_array_length", listOf(inArray), kIntType)

	/** `counter` from 0 until [inLength], running [inBody] with the counter each time. */
	private fun countedLoop(inLength: KExpr, inBody: (KExpr) -> Unit) {
		val vCounter = KLocal(freshName("i"), kIntType)
		mBlock += KLet(vCounter, KConst(0, kIntType))
		val vId = mNextLoopId++
		val vBody = inBlock {
			mBlock += KIf(KBinary(KBinaryOp.GE, KRead(vCounter), inLength, KPrim.INT, kBoolType), listOf(KBreak(vId)), emptyList())
			inBody(KRead(vCounter))
		}
		mBlock += KLoop(vId, vBody, listOf(KSet(vCounter, KBinary(KBinaryOp.ADD, KRead(vCounter), KConst(1, kIntType), KPrim.INT, kIntType))))
	}

	/** [inLength] as the length of a new array: one below zero, when it can be, throws `NegativeArraySizeException`. */
	private fun checkedLength(inLength: KExpr): KExpr {
		if (((inLength as? KConst)?.value as? Int)?.let { it >= 0 } == true) return inLength
		val vThrower = mImporter.internalFunction("throwNegativeArraySize")?.let { mImporter.functionFor(it) } ?: return inLength
		val vLength = spill(inLength)
		mBlock += KIf(KBinary(KBinaryOp.LT, vLength, KConst(0, kIntType), KPrim.INT, kBoolType), listOf(KEval(KCall(vThrower, listOf(vLength)))), emptyList())
		return vLength
	}

	/** `IntArray(n)`, `IntArray(n) { ... }`, `Array(n) { ... }`. */
	private fun lowerArrayConstructor(inCall: IrConstructorCall, inType: KArrayType): KExpr {
		val vSize = inCall.arguments.getOrNull(0) ?: return fail(inCall, "array constructor")
		val vInit = inCall.arguments.getOrNull(1)
		val vLength = checkedLength(spill(lowerPure(vSize)))
		val vArray = newTemp(inType)
		mBlock += KLet(vArray, KArrayNew(vLength, inType))
		if (vInit != null) {
			val vLambda = vInit as? IrFunctionExpression ?: return fail(vInit, "array initializer that is not a lambda")
			countedLoop(vLength) { vIndex ->
				val vValue = inlineLambda(vLambda, listOf(vIndex), inType.element)
				mBlock += KIndexSet(KRead(vArray), vIndex, coerce(vValue, inType.element))
			}
		}
		return KRead(vArray)
	}

	/** A `vararg` argument or `arrayOf(...)` list: a new array holding the elements. */
	private fun lowerVararg(inVararg: IrVararg): KExpr {
		val vDeclared = typeOf(inVararg)
		// `vararg elements: UInt` is a UIntArray: the unsigned values in the signed array that class wraps.
		val vWrapper = (vDeclared as? KStructType)?.struct?.takeIf { it.fields.size == 1 && it.fields[0].type is KArrayType }
		val vType = (vWrapper?.fields?.get(0)?.type ?: vDeclared) as? KArrayType ?: return fail(inVararg, "vararg of type '${inVararg.type.render()}'")
		if (inVararg.elements.any { it !is IrExpression }) {
			if (vWrapper != null) return fail(inVararg, "spread (*) in a vararg of an unsigned type")
			return lowerSpreadVararg(inVararg, vType)
		}
		if (vWrapper != null) {
			val vElement = (vType.element as? KPrimType)?.prim ?: return fail(inVararg, "vararg of type '${inVararg.type.render()}'")
			val vValues = lowerOperands(inVararg.elements.map { it as IrExpression })
			val vArray = newTemp(vType)
			mBlock += KLet(vArray, KArrayNew(KConst(vValues.size, kIntType), vType))
			vValues.forEachIndexed { vIndex, vValue -> mBlock += KIndexSet(KRead(vArray), KConst(vIndex, kIntType), convert(vValue, vElement)) }
			return KStructNew(vWrapper, listOf(KRead(vArray)))
		}
		val vValues = lowerOperands(inVararg.elements.map { it as IrExpression })
		val vArray = newTemp(vType)
		mBlock += KLet(vArray, KArrayNew(KConst(vValues.size, kIntType), vType))
		vValues.forEachIndexed { vIndex, vValue ->
			mBlock += KIndexSet(KRead(vArray), KConst(vIndex, kIntType), coerce(vValue, vType.element))
		}
		return KRead(vArray)
	}

	/** The empty array a `vararg` parameter receives when the call names no element; null for any other parameter. */
	private fun emptyVararg(inParam: IrValueParameter, inType: KType): KExpr? {
		if (inParam.varargElementType == null) return null
		// `vararg elements: UInt` is a UIntArray: the class around the signed array.
		val vWrapper = (inType as? KStructType)?.struct?.takeIf { it.fields.size == 1 && it.fields[0].type is KArrayType }
		if (vWrapper != null) {
			val vArray = newTemp(vWrapper.fields[0].type)
			mBlock += KLet(vArray, KArrayNew(KConst(0, kIntType), vWrapper.fields[0].type as KArrayType))
			return KStructNew(vWrapper, listOf(KRead(vArray)))
		}
		if (inType !is KArrayType) return null
		return KArrayNew(KConst(0, kIntType), inType)
	}

	/** `f(1, *more, 2)`: a new array with the single elements and the elements of each spread array, in order. */
	private fun lowerSpreadVararg(inVararg: IrVararg, inType: KArrayType): KExpr {
		val vExpressions = inVararg.elements.map { if (it is IrSpreadElement) it.expression else it as IrExpression }
		val vValues = lowerOperands(vExpressions).map { spill(it) }
		var vTotal: KExpr = KConst(inVararg.elements.count { it !is IrSpreadElement }, kIntType)
		inVararg.elements.forEachIndexed { vIndex, vElement ->
			if (vElement is IrSpreadElement) vTotal = KBinary(KBinaryOp.ADD, vTotal, arrayLength(vValues[vIndex]), KPrim.INT, kIntType)
		}
		val vArray = newTemp(inType)
		mBlock += KLet(vArray, KArrayNew(spill(vTotal), inType))
		val vAt = KLocal(freshName("at"), kIntType)
		mBlock += KLet(vAt, KConst(0, kIntType))
		fun advance(inBy: KExpr) {
			mBlock += KSet(vAt, KBinary(KBinaryOp.ADD, KRead(vAt), inBy, KPrim.INT, kIntType))
		}
		inVararg.elements.forEachIndexed { vIndex, vElement ->
			val vValue = vValues[vIndex]
			if (vElement is IrSpreadElement) {
				val vLength = spill(arrayLength(vValue))
				val vElementType = (vValue.type as? KArrayType)?.element ?: inType.element
				countedLoop(vLength) { vOffset ->
					val vTarget = KBinary(KBinaryOp.ADD, KRead(vAt), vOffset, KPrim.INT, kIntType)
					mBlock += KIndexSet(KRead(vArray), vTarget, coerce(KIndexRead(vValue, vOffset, vElementType), inType.element))
				}
				advance(vLength)
			} else {
				mBlock += KIndexSet(KRead(vArray), KRead(vAt), coerce(vValue, inType.element))
				advance(KConst(1, kIntType))
			}
		}
		return KRead(vArray)
	}

	/** Members of `Array` and the primitive arrays. */
	private fun lowerArrayCall(inCall: IrCall, inName: String, inArguments: List<IrExpression>, inType: KArrayType): KExpr = when (inName) {
		"<get-size>" -> arrayLength(lowerPure(inArguments[0]))
		"iterator" -> {
			// The platform layer's iterator class, for the element type of this array.
			val vFunction = mImporter.internalFunction("arrayIterator")
			val vElement = mImporter.argumentsOf(closed(inArguments[0].type)).firstOrNull()
			// An array of numbers (`IntArray`) has the iterator of its kind (`IntIterator`).
			val vOfNumbers = (inType.element as? KPrimType)?.takeIf { vElement == null }
				?.let { mImporter.internalFunction(it.prim.name.lowercase() + "ArrayIterator") }?.let { mImporter.functionFor(it) }
			val vTarget = vOfNumbers ?: if (vFunction == null || vElement == null) null else mImporter.functionFor(vFunction, mImporter.substFor(vFunction, emptyList(), listOf(vElement)))
			if (vTarget == null) fail(inCall, "iterator() on this array") else adapt(KCall(vTarget, listOf(coerce(lowerPure(inArguments[0]), vTarget.params[0].type))), inCall)
		}
		"get" -> {
			val vOperands = lowerOperands(inArguments)
			adapt(KIndexRead(vOperands[0], vOperands[1], inType.element), inCall)
		}
		"set" -> {
			val vOperands = lowerOperands(inArguments, inLastMayBeImpure = true)
			mBlock += KIndexSet(vOperands[0], vOperands[1], coerce(vOperands[2], inType.element))
			KUnitValue
		}
		// `Storage.at` and `Storage.put`: the platform layer has made sure of the index.
		"at" -> {
			val vOperands = lowerOperands(inArguments)
			adapt(KIndexRead(vOperands[0], vOperands[1], inType.element).also { it.isChecked = true }, inCall)
		}
		"put" -> {
			val vOperands = lowerOperands(inArguments, inLastMayBeImpure = true)
			mBlock += KIndexSet(vOperands[0], vOperands[1], coerce(vOperands[2], inType.element)).also { it.isChecked = true }
			KUnitValue
		}
		// `Storage.drop(index)`: the slot gives up the reference it holds; numbers are left as they are.
		"drop" -> {
			val vOperands = lowerOperands(inArguments)
			val vElement = inType.element
			if (vElement.isCounted()) mBlock += KIndexSet(vOperands[0], vOperands[1], if (vElement is KRefType) KNull(vElement) else KZero(vElement))
			KUnitValue
		}
		// An array is described, hashed and compared as an object that says nothing of itself.
		"toString" -> KCall(mImporter.runtime("ktc2_any_toString", listOf(KObjType), KStringType), listOf(coerce(lowerPure(inArguments[0]), KObjType)))
		"hashCode" -> KCall(mImporter.runtime("ktc2_any_hashCode", listOf(KObjType), kIntType), listOf(coerce(lowerPure(inArguments[0]), KObjType)))
		"equals" -> {
			val vOperands = lowerOperands(inArguments)
			KCall(mImporter.runtime("ktc2_any_equals", listOf(KObjType, KObjType), kBoolType), listOf(coerce(vOperands[0], KObjType), coerce(vOperands[1], KObjType)))
		}
		else -> fail(inCall, "call to 'kotlin.Array.$inName'")
	}

	/** `for (x in array)`: an index loop over the array as it is when the loop starts. */
	private fun lowerArrayFor(inSource: IrExpression, inLoop: IrWhileLoop, inVariable: IrVariable, inBody: List<IrStatement>, inType: KArrayType): KExpr {
		val vArray = spill(lowerPure(inSource))
		val vLength = spill(arrayLength(vArray))
		val vCounter = KLocal(freshName("i"), kIntType)
		mBlock += KLet(vCounter, KConst(0, kIntType))
		val vId = loopId(inLoop)
		val vElement = KLocal(freshName(inVariable.name.asString()), kType(inVariable.type) ?: inType.element)
		mLocals[inVariable] = vElement
		val vBody = inBlock {
			mBlock += KIf(KBinary(KBinaryOp.GE, KRead(vCounter), vLength, KPrim.INT, kBoolType), listOf(KBreak(vId)), emptyList())
			mBlock += KLet(vElement, coerce(KIndexRead(vArray, KRead(vCounter), inType.element), vElement.type))
			inLoopBody(inLoop) { inBody.forEach { lowerStatement(it) } }
		}
		mBlock += KLoop(vId, vBody, listOf(KSet(vCounter, KBinary(KBinaryOp.ADD, KRead(vCounter), KConst(1, kIntType), KPrim.INT, kIntType))))
		return KUnitValue
	}

	// ============
	//  for over a range

	private fun lowerFor(inBlock: IrContainerExpression): KExpr {
		val vIterator = inBlock.statements.getOrNull(0) as? IrVariable
		val vLoop = inBlock.statements.getOrNull(1) as? IrWhileLoop
		val vInner = vLoop?.body as? IrContainerExpression
		val vLoopVariable = vInner?.statements?.firstOrNull() as? IrVariable
		fun nameOf(inCall: IrCall?) = inCall?.symbol?.owner?.kotlinFqName?.asString()?.substringAfterLast('.')
		val vSource = (vIterator?.initializer as? IrCall)?.arguments?.firstOrNull()
		val vSourceType = vSource?.let { kType(it.type) }
		if (vSource != null && vSourceType is KArrayType && vLoop != null && vInner != null && vLoopVariable != null) {
			return lowerArrayFor(vSource, vLoop, vLoopVariable, vInner.statements.drop(1), vSourceType)
		}
		var vRange = vSource as? IrCall
		var vStepArgument: IrExpression? = null
		if (nameOf(vRange) == "step" && vRange?.arguments?.size == 2) {
			vStepArgument = vRange.arguments[1]
			vRange = vRange.arguments[0] as? IrCall
		}
		val vKind = nameOf(vRange)
		val vElement = vLoopVariable?.let { kType(it.type) } as? KPrimType
		// `array.indices` is the range 0 until size.
		val vIndicesOf = vRange?.takeIf { vKind == "<get-indices>" && it.arguments.size == 1 }?.arguments?.get(0)
			?.takeIf { kType(it.type) is KArrayType }
		val vIsRange = vRange != null && vRange.arguments.size == 2 && vKind in setOf("until", "rangeUntil", "rangeTo", "downTo")
		if (vLoop == null || vInner == null || vLoopVariable == null || vRange == null || (!vIsRange && vIndicesOf == null) ||
			vElement == null || (vElement.prim != KPrim.INT && vElement.prim != KPrim.LONG)
		) {
			// Anything else is iterated through its iterator(), exactly as K2 wrote it.
			for (vIndex in 0 until inBlock.statements.size - 1) lowerStatement(inBlock.statements[vIndex])
			inBlock.statements.lastOrNull()?.let { lowerStatement(it) }
			return KUnitValue
		}
		val vPrim = vElement.prim
		val vDown = vKind == "downTo"

		val vBounds = if (vIndicesOf != null) {
			val vArray = lowerPure(vIndicesOf)
			val vStepValues = listOfNotNull(vStepArgument).map { lowerPure(it) }
			(listOf<KExpr>(KConst(0, kIntType), spill(arrayLength(vArray))) + vStepValues).toMutableList()
		} else {
			lowerOperands(vRange.arguments.map { it!! } + listOfNotNull(vStepArgument))
		}
		val vCounter = KLocal(freshName("i"), vElement)
		mBlock += KLet(vCounter, convert(vBounds[0], vPrim))
		val vEnd = spill(convert(vBounds[1], vPrim))
		val vId = loopId(vLoop)
		val vStepValue = if (vStepArgument != null) spill(convert(vBounds[2], vPrim)) else one(vPrim)
		if (vStepArgument != null) {
			mBlock += KEval(KIntrinsic("ktc2_check_step", listOf(convert(vStepValue, KPrim.LONG)), KUnitType, isPure = false))
		}
		val vStep = KSet(vCounter, KBinary(if (vDown) KBinaryOp.SUB else KBinaryOp.ADD, KRead(vCounter), vStepValue, vPrim, vElement))
		fun compare(inOp: KBinaryOp, inRight: KExpr = vEnd) = KBinary(inOp, KRead(vCounter), inRight, vPrim, kBoolType)

		val vVariable = KLocal(freshName(vLoopVariable.name.asString()), vElement)
		mLocals[vLoopVariable] = vVariable
		val vExclusive = vKind == "until" || vKind == "rangeUntil" || vIndicesOf != null
		val vBody = inBlock {
			if (vExclusive && vStepArgument == null) mBlock += KIf(compare(KBinaryOp.GE), listOf(KBreak(vId)), emptyList())
			mBlock += KLet(vVariable, KRead(vCounter))
			inLoopBody(vLoop) { for (vIndex in 1 until vInner.statements.size) lowerStatement(vInner.statements[vIndex]) }
		}
		if (vExclusive && vStepArgument == null) {
			mBlock += KLoop(vId, vBody, listOf(vStep))
			return KUnitValue
		}
		// Inclusive and stepped ranges stop on equality with the last element, so the counter
		// never steps past the bound (no overflow at Int.MAX_VALUE).
		val vEnter = compare(if (vDown) KBinaryOp.GE else if (vExclusive) KBinaryOp.LT else KBinaryOp.LE)
		val vGuarded = inBlock {
			var vLast = vEnd
			if (vStepArgument != null) {
				val vInclusive = if (vExclusive) KBinary(KBinaryOp.SUB, vEnd, one(vPrim), vPrim, vElement) else vEnd
				val vHelper = "ktc2_${if (vPrim == KPrim.LONG) "l" else "i"}range_last_${if (vDown) "down" else "up"}"
				vLast = spill(KIntrinsic(vHelper, listOf(KRead(vCounter), vInclusive, vStepValue), vElement))
			}
			val vLatch = listOf(KIf(compare(KBinaryOp.EQ, vLast), listOf(KBreak(vId)), emptyList()), vStep)
			mBlock += KLoop(vId, vBody, vLatch)
		}
		mBlock += KIf(vEnter, vGuarded, emptyList())
		return KUnitValue
	}

	// ==================
	// MARK: Objects
	// ==================

	private fun selfRead(): KExpr = KRead(mFunction.params[0])

	/**
	 * Reads [inField] of [inTarget]. An inline field yields a pointer into its owner: the owner
	 * is held by a local for as long as that pointer is in scope, and the pointer gets a local
	 * of its own so the escape analysis can see where it goes.
	 */
	private fun readField(inTarget: KExpr, inField: KField): KExpr {
		val vWeakType = inField.weakType
		if (vWeakType != null) {
			// The field holds the weak cell; the read asks it for the target, null once that died.
			val vAlive = newTemp(KObjType)
			mBlock += KLet(vAlive, KCall(mImporter.weakGetFunction(), listOf(KFieldRead(inTarget, inField))))
			return coerce(KRead(vAlive), vWeakType)
		}
		if (!inField.isInline) return KFieldRead(inTarget, inField)
		val vOwner = newTemp(inTarget.type)
		mBlock += KLet(vOwner, inTarget)
		val vInside = newTemp(inField.type)
		mBlock += KLet(vInside, KFieldRead(KRead(vOwner), inField))
		return KRead(vInside)
	}

	/** Writes [inField] of [inTarget]; an inline field takes a copy of the value's content. */
	private fun writeField(inTarget: KExpr, inField: KField, inValue: KExpr) {
		if (inField.weakType != null) {
			val vCell = newTemp(KObjType)
			mBlock += KLet(vCell, KCall(mImporter.weakCellFunction(), listOf(coerce(inValue, KObjType))))
			mBlock += KFieldSet(inTarget, inField, KRead(vCell))
			return
		}
		mBlock += KFieldSet(inTarget, inField, if (inField.isInline) spill(inValue) else inValue)
	}

	/** The receiver of a field access, seen as the class that declares the field. */
	private fun fieldTarget(inReceiver: KExpr, inField: KField): KExpr =
		inField.owner?.let { coerce(inReceiver, KClassType(it)) } ?: inReceiver

	private fun lowerConstructorCall(inCall: IrConstructorCall): KExpr {
		val vConstructor = inCall.symbol.owner
		val vOwner = vConstructor.parentAsClass
		if (isThrowableClass(vOwner)) {
			// A plain Throwable: the built-in base class itself.
			val vBase = mImporter.throwableBase()
			mImporter.instantiate(vBase)
			val vObject = newTemp(KClassType(vBase))
			mBlock += KLet(vObject, KAlloc(vBase).also { it.origin = originOf(inCall) })
			setThrowableFields(KRead(vObject), vConstructor, inCall.arguments)
			return KRead(vObject)
		}
		if (isAnyClass(vOwner)) {
			// `Any()`: an object with nothing in it, of use as a lock or as a mark.
			val vFactory = mImporter.internalFunction("newObject")?.let { mImporter.functionFor(it) } ?: return fail(inCall, "construction of 'kotlin.Any'")
			return spill(KCall(vFactory, emptyList()))
		}
		val vIsStorage = vOwner.kotlinFqName.asString() == "ktc2.internal.Storage"
		(typeOf(inCall) as? KArrayType)?.takeIf { vOwner.fileOrNull == null || vIsStorage }?.let { return lowerArrayConstructor(inCall, it) }
		val vClassArguments = mImporter.argumentsOf(closed(inCall.type))
		val vSubst = mImporter.substFor(vConstructor, vClassArguments, emptyList())
		val vTarget = mImporter.functionFor(vConstructor, vSubst)
			?: return fail(inCall, "construction of '${vOwner.kotlinFqName}'")
		if (vTarget.returnType is KStructType) return callFunction(inCall, vTarget, vConstructor, inCall.arguments, null)
		val vClass = mImporter.classFor(vOwner, vClassArguments) ?: return fail(inCall, "construction of '${vOwner.kotlinFqName}'")
		mImporter.instantiate(vClass)
		// Arguments first, as in Kotlin; then allocate and run the constructor on the new object.
		val vObject = newTemp(KClassType(vClass))
		val vCall = callFunction(inCall, vTarget, vConstructor, inCall.arguments, KRead(vObject), vSubst)
		if (vCall !is KCall) return vCall
		mBlock += KLet(vObject, KAlloc(vClass).also { it.origin = originOf(inCall) })
		// A local class instance carries the outer values it uses; they are set before its constructor runs.
		// An inner class instance carries the object it was created on: the receiver of the constructor call.
		val vOuterThis = if (vOwner.isInner) vOwner.parentAsClass.thisReceiver else null
		for (vBinding in mImporter.localBindingsOf(vClass)) {
			val vSource = if (vBinding.declaration === vOuterThis) vCall.arguments.getOrNull(1) else mLocals[vBinding.declaration as IrValueDeclaration]?.let { KRead(it) }
			if (vSource == null) {
				fail(inCall, "creating a local class instance where '${(vBinding.declaration as IrValueDeclaration).name}' is not in scope")
			} else {
				mBlock += KFieldSet(fieldTarget(KRead(vObject), vBinding.field), vBinding.field, coerce(vSource, vBinding.field.type))
			}
		}
		mBlock += KEval(vCall)
		return KRead(vObject)
	}

	/**
	 * `@Size(N) val buffer = ByteArray(N)` and `val p: @Value Vec2 = Vec2(…)`: the program
	 * requires the array or object to live in the stack frame. The allocation that initializes
	 * the local is marked; `EscapeAnalysis` then refuses the program if it cannot comply.
	 */
	private fun forceOnStack(inVariable: IrVariable, inInit: KExpr?) {
		val vAnnotations = inVariable.annotations + inVariable.type.annotations
		val vSize = vAnnotations.firstOrNull { it.type.classFqName?.asString() == "ktc2.Size" }
		val vValue = vAnnotations.firstOrNull { it.type.classFqName?.asString() == "ktc2.Value" }
		if (vSize == null && vValue == null) return
		val vName = if (vSize != null) "@Size" else "@Value"
		var vSource = inInit
		while (vSource is KRefCast) vSource = vSource.operand
		val vTemp = (vSource as? KRead)?.local
		var vAllocation = (mBlock.lastOrNull { it is KLet && it.local === vTemp } as? KLet)?.init
		while (vAllocation is KRefCast) vAllocation = vAllocation.operand
		when {
			vSize != null && vAllocation is KArrayNew -> {
				val vCount = (vSize.arguments.firstOrNull() as? IrConst)?.value as? Int
				val vLength = (vAllocation.length as? KConst)?.value as? Int
				if (vCount == null || vLength != vCount) {
					fail(inVariable, "@Size($vCount) on '${inVariable.name}', whose array is not created with that constant size")
				} else {
					vAllocation.forcedBy = vName
				}
			}
			vValue != null && vAllocation is KAlloc -> vAllocation.forcedBy = vName
			// Written on the local itself it is a requirement. On the type alone it may only have come
			// along with a value read from an inline field, which is already where it should be.
			inVariable.annotations.any { it === vSize || it === vValue } ->
				fail(inVariable, "$vName on '${inVariable.name}' (the local has to be initialized with a new ${if (vSize != null) "array" else "object"})")
			else -> {}
		}
	}

	/** A class declared inside a function (or an object expression): its captures become fields. */
	private fun declareLocalClass(inClass: IrClass) {
		val vSet = CaptureSet()
		collectCaptures(mImporter.captures.freeVariablesOfClass(inClass), vSet)
		val vCaptures = vSet.values.map { (vDeclaration, vLocal) -> Triple(vDeclaration, vLocal.type, mCells[vDeclaration]) }
		if (mImporter.declareLocalClass(inClass, vCaptures, HashMap(vSet.lambdas)) == null) fail(inClass, "local class '${inClass.name}'")
	}

	/** An enum constructor delegating to its superclass constructor (`kotlin.Enum` needs nothing). */
	private fun lowerEnumConstructorCall(inCall: IrEnumConstructorCall): KExpr {
		val vConstructor = inCall.symbol.owner
		if (vConstructor.parentAsClass.kotlinFqName.asString() == "kotlin.Enum") return KUnitValue
		val vTarget = mImporter.functionFor(vConstructor) ?: return fail(inCall, "enum constructor")
		val vCall = callFunction(inCall, vTarget, vConstructor, inCall.arguments, coerce(selfRead(), vTarget.params[0].type))
		if (vCall is KCall) mBlock += KEval(vCall)
		return KUnitValue
	}

	private fun lowerDelegatingConstructorCall(inCall: IrDelegatingConstructorCall): KExpr {
		val vConstructor = inCall.symbol.owner
		if (isAnyClass(vConstructor.parentAsClass) || isNumberClass(vConstructor.parentAsClass)) return KUnitValue
		if (isThrowableClass(vConstructor.parentAsClass)) {
			setThrowableFields(coerce(selfRead(), KClassType(mImporter.throwableBase())), vConstructor, inCall.arguments)
			return KUnitValue
		}
		val vSubst = mImporter.substFor(vConstructor, inCall.typeArguments.map { vType -> vType?.let { closed(it) } ?: mImporter.mBuiltIns.anyNType }, emptyList())
		val vTarget = mImporter.functionFor(vConstructor, vSubst)
			?: return fail(inCall, "superclass constructor of '${vConstructor.parentAsClass.kotlinFqName}'")
		val vCall = callFunction(inCall, vTarget, vConstructor, inCall.arguments, coerce(selfRead(), vTarget.params[0].type), vSubst)
		if (vCall is KCall) mBlock += KEval(vCall)
		return KUnitValue
	}

	/** Property initializers and `init` blocks of the class, in declaration order. */
	private fun lowerInstanceInitializer(inCall: IrInstanceInitializerCall): KExpr {
		for (vDeclaration in inCall.classSymbol.owner.declarations) {
			when (vDeclaration) {
				is IrProperty -> {
					val vBacking = vDeclaration.backingField ?: continue
					val vInit = vBacking.initializer?.expression ?: continue
					val vField = mImporter.fieldIn((mFunction.params[0].type as? KClassType)?.cls, vBacking) ?: continue
					// A folded cleaner: the property holds the resource `createCleaner` was given.
					val vValue = mImporter.cleanerOf(vField)?.resource ?: vInit
					writeField(fieldTarget(selfRead(), vField), vField, coerce(lowerRhs(vValue), vField.type))
				}
				is IrAnonymousInitializer -> vDeclaration.body.statements.forEach { lowerStatement(it) }
				// What an interface is delegated to (`by list`), kept in a field of no property.
				is IrField -> {
					val vInit = vDeclaration.initializer?.expression ?: continue
					val vField = mImporter.fieldIn((mFunction.params[0].type as? KClassType)?.cls, vDeclaration) ?: continue
					writeField(fieldTarget(selfRead(), vField), vField, coerce(lowerRhs(vInit), vField.type))
				}
				else -> {}
			}
		}
		return KUnitValue
	}

	/**
	 * `name`, `ordinal`, `toString()` and `compareTo` of a value of a `@SimpleEnum` class, which
	 * every enum has from `kotlin.Enum`: answered from the number, without the object kept for
	 * the entry. Null for any other call, and for a `toString` the class wrote itself.
	 */
	private fun lowerSimpleEnumMember(inCall: IrCall, inCallee: IrSimpleFunction, inOwner: IrClass): KExpr? {
		val vName = inCallee.name.asString()
		if (vName != "<get-name>" && vName != "<get-ordinal>" && vName != "toString" && vName != "compareTo") return null
		if (inOwner.kotlinFqName.asString() != "kotlin.Enum" && !(mImporter.isSimpleEnum(inOwner) && inCallee.isFakeOverride)) return null
		if (inCallee.dispatchReceiverParameter == null) return null
		val vReceiver = inCall.arguments.getOrNull(0) ?: return null
		val vType = kType(vReceiver.type) as? KStructType ?: return null
		val vEnum = mImporter.simpleEnumOf(vType.struct) ?: return null
		fun ordinalOf(inValue: KExpr): KExpr =
			if (vEnum.isNumbered) spill(KCall(mImporter.simpleEnumBuiltin(vEnum, "ordinal"), listOf(inValue))) else KFieldRead(inValue, vEnum.valueField)
		return when (vName) {
			"<get-ordinal>" -> adapt(ordinalOf(lowerPure(vReceiver)), inCall)
			"compareTo" -> {
				val vOther = inCall.arguments.getOrNull(1) ?: return null
				if (kType(vOther.type) != vType) return null
				val vSides = lowerOperands(listOf(vReceiver, vOther))
				// As `Enum.compareTo` on the JVM: the difference of the ordinals, not only its sign.
				adapt(KBinary(KBinaryOp.SUB, ordinalOf(vSides[0]), ordinalOf(vSides[1]), KPrim.INT, kIntType), inCall)
			}
			else -> adapt(spill(KCall(mImporter.simpleEnumBuiltin(vEnum, "name"), listOf(lowerPure(vReceiver)))), inCall)
		}
	}

	private fun lowerGetField(inExpression: IrGetField): KExpr {
		val vLate = lateinitName(inExpression.symbol.owner)
		mImporter.globalFor(inExpression.symbol.owner)?.let { vGlobal ->
			val vRead = KGlobalRead(ensuredFile(vGlobal))
			return if (vLate != null) initialized(vRead, vLate) else vRead
		}
		val vReceiver = inExpression.receiver ?: return fail(inExpression, "static field access")
		// A property of a `@SimpleEnum` class is not stored: it is looked up from the number.
		(inExpression.symbol.owner.parent as? IrClass)?.takeIf { mImporter.isSimpleEnum(it) }?.let {
			val vLookup = mImporter.simpleEnumProperty(inExpression.symbol.owner) ?: return fail(inExpression, "field access")
			return adapt(spill(KCall(vLookup, listOf(coerce(lowerPure(vReceiver), vLookup.params[0].type)))), inExpression)
		}
		// Of an object that may be of another instantiation of its class than this code was made for: through its getter.
		if (vLate == null) erasedFieldRead(inExpression, false)?.let { return it }
		val vField = mImporter.fieldFor(inExpression.symbol.owner, closed(vReceiver.type))
			?: return fail(inExpression, "access to the field '${inExpression.symbol.owner.name}' of '${closed(vReceiver.type).render()}'")
		val vRead = readField(fieldTarget(lowerPure(vReceiver), vField), vField)
		return adapt(if (vLate != null) initialized(vRead, vLate) else vRead, inExpression)
	}

	private fun lowerSetField(inExpression: IrSetField): KExpr {
		val vGlobal = mImporter.globalFor(inExpression.symbol.owner)
		if (vGlobal != null) {
			mBlock += KGlobalSet(ensuredFile(vGlobal), coerce(lowerRhs(inExpression.value), vGlobal.type))
			return KUnitValue
		}
		val vReceiver = inExpression.receiver ?: return fail(inExpression, "static field assignment")
		val vField = mImporter.fieldFor(inExpression.symbol.owner, closed(vReceiver.type)) ?: return fail(inExpression, "field assignment")
		val vOperands = lowerOperands(listOf(vReceiver, inExpression.value), inLastMayBeImpure = true)
		writeField(fieldTarget(vOperands[0], vField), vField, coerce(vOperands[1], vField.type))
		return KUnitValue
	}

	// ==================
	// MARK: Calls
	// ==================

	/** The default value of parameter [inIndex], inherited from an overridden function if needed. */
	private fun defaultOf(inFunction: IrFunction, inIndex: Int): IrExpression? {
		inFunction.parameters[inIndex].defaultValue?.let { return it.expression }
		return (inFunction as? IrSimpleFunction)?.overriddenSymbols?.firstNotNullOfOrNull { defaultOf(it.owner, inIndex) }
	}

	/**
	 * Builds the call to [inTarget]. [inPrefix], when given, is passed before the arguments
	 * (the object a constructor initializes).
	 */
	/**
	 * [inValue], lowered from [inGiven], as an argument of type [inType]. An argument that never
	 * has a value (`error("…")` handed on, a `Nothing`) is anything at all: the call is not reached.
	 * `Unit` given where an object is asked for is the runtime's Unit object.
	 */
	private fun argument(inValue: KExpr, inGiven: IrExpression, inType: KType): KExpr {
		if (inValue !== KUnitValue || inType == KUnitType) return coerce(inValue, inType)
		if (inGiven.type.isNothing() || inType !is KRefType) return KZero(inType)
		return coerce(inValue, inType)
	}

	private fun callFunction(
		inCall: IrElement,
		inTarget: KFunction,
		inCallee: IrFunction,
		inArguments: List<IrExpression?>,
		inPrefix: KExpr?,
		inCalleeSubst: Subst = emptyMap(),
	): KExpr {
		val vOffset = if (inPrefix != null) 1 else 0
		if (inArguments.any { it == null }) return callWithDefaults(inCall, inTarget, inCallee, inArguments, inPrefix, inCalleeSubst)
		val vArguments = lowerOperands(inArguments.map { it!! })
		return KCall(inTarget, listOfNotNull(inPrefix) + vArguments.mapIndexed { vIndex, vValue -> argument(vValue, inArguments[vIndex]!!, inTarget.params[vIndex + vOffset].type) })
	}

	/**
	 * Omitted arguments take the callee's default expressions, evaluated here after the given
	 * arguments, with the callee's parameters bound to the values already computed.
	 */
	private fun callWithDefaults(
		inCall: IrElement,
		inTarget: KFunction,
		inCallee: IrFunction,
		inArguments: List<IrExpression?>,
		inPrefix: KExpr?,
		inCalleeSubst: Subst,
	): KExpr {
		val vOffset = if (inPrefix != null) 1 else 0
		val vGiven = lowerOperands(inArguments.filterNotNull())
		// A default expression may be the one of a function this one overrides (an inherited member, a fake
		// override): it names the parameters of that function, which are bound as well.
		val vVersions = generateSequence(listOf(inCallee)) { vLevel ->
			vLevel.flatMap { (it as? IrSimpleFunction)?.overriddenSymbols?.map { vSymbol -> vSymbol.owner } ?: emptyList() }.takeIf { it.isNotEmpty() }
		}.take(16).flatten().distinct().filter { it.parameters.size == inCallee.parameters.size }.toList()
		val vSaved = vVersions.flatMap { it.parameters }.associateWith { mLocals[it] }
		val vBound = arrayOfNulls<KLocal>(inArguments.size)
		var vNext = 0
		inArguments.forEachIndexed { vIndex, vArgument ->
			if (vArgument == null) return@forEachIndexed
			val vTemp = newTemp(inTarget.params[vIndex + vOffset].type)
			mBlock += KLet(vTemp, argument(vGiven[vNext++], vArgument, vTemp.type))
			vBound[vIndex] = vTemp
		}
		var vFailed = false
		inArguments.forEachIndexed { vIndex, vArgument ->
			if (vArgument == null) {
				val vDefault = defaultOf(inCallee, vIndex)
				if (vDefault == null) {
					// A `vararg` given nothing is an empty array.
					val vEmpty = emptyVararg(inCallee.parameters[vIndex], inTarget.params[vIndex + vOffset].type)
					if (vEmpty == null) {
						vFailed = true
					} else {
						val vHolder = newTemp(inTarget.params[vIndex + vOffset].type)
						mBlock += KLet(vHolder, vEmpty)
						vBound[vIndex] = vHolder
					}
					return@forEachIndexed
				}
				val vTemp = newTemp(inTarget.params[vIndex + vOffset].type)
				// The default expression belongs to the callee: its types are read under the callee's substitution.
				val vOuter = mSubst
				mSubst = inCalleeSubst
				val vValue = try {
					lowerRhs(vDefault)
				} finally {
					mSubst = vOuter
				}
				mBlock += KLet(vTemp, coerce(vValue, vTemp.type))
				vBound[vIndex] = vTemp
			}
			for (vVersion in vVersions) mLocals[vVersion.parameters[vIndex]] = vBound[vIndex]!!
		}
		for ((vParam, vLocal) in vSaved) {
			if (vLocal == null) mLocals.remove(vParam) else mLocals[vParam] = vLocal
		}
		if (vFailed) return fail(inCall, "call with a missing argument")
		return KCall(inTarget, listOfNotNull(inPrefix) + vBound.map { KRead(it!!) })
	}

	/**
	 * The function a member call lands on - a dispatcher when the member can be overridden -
	 * with the substitution its declaration is read under. [inReceiver] is the closed type of
	 * the receiver, which picks the instantiation of a generic class.
	 */
	/**
	 * True when the member [inFunction] of what [inReceiver] gives has to be found by the class of
	 * the object: the object may be of another instantiation of its generic class than the type
	 * says (see `mayBeOtherInstantiation`). Not for `this`, which is of the instantiation the
	 * function was made for.
	 */
	/** The variables that were given the result of a cast to a generic class: see [isUncertain]. */
	private val mUncertain = HashSet<IrValueDeclaration>()

	/**
	 * True when [inValue] comes from a cast to a generic class: `other as Pair<A, B>`. Kotlin
	 * only tests the class there, so the object may be of any instantiation, whatever the type
	 * says: its members are reached under the type that fits them all ([receiverView]).
	 */
	private fun isUncertain(inValue: IrExpression?): Boolean = when (inValue) {
		is IrTypeOperatorCall -> {
			val vTo = inValue.typeOperand.classOrNull?.owner
			(inValue.operator == IrTypeOperator.CAST || inValue.operator == IrTypeOperator.IMPLICIT_CAST || inValue.operator == IrTypeOperator.SAFE_CAST) &&
				vTo != null && mImporter.hasInstantiations(vTo) && inValue.argument.type.classOrNull?.owner != vTo
		}
		is IrGetValue -> inValue.symbol.owner in mUncertain
		else -> false
	}

	/** The type under which the member [inFunction] of [inReceiver] is looked up. */
	private fun receiverView(inReceiver: IrExpression?, inFunction: IrSimpleFunction, inClosed: IrType?): IrType? {
		if (!isUncertain(inReceiver)) return inClosed
		val vReal = if (inFunction.isFakeOverride) inFunction.resolveFakeOverride() ?: inFunction else inFunction
		return mImporter.anyViewOf(vReal.parentAsClass)
	}

	/**
	 * A field of an object whose instantiation the type does not settle, read through the getter
	 * of its property, by the class of the object; null when [inRead] is no such read. With
	 * [inAsObject] the value is left as the object the getter gives (a number in its box): what
	 * `==` wants of it.
	 */
	private fun erasedFieldRead(inRead: IrGetField, inAsObject: Boolean): KExpr? {
		val vReceiver = inRead.receiver ?: return null
		val vGetter = inRead.symbol.owner.correspondingPropertySymbol?.owner?.getter ?: return null
		if (!isErasedReceiver(vReceiver, vGetter)) return null
		val vTarget = memberTarget(vGetter, false, receiverView(vReceiver, vGetter, closed(vReceiver.type)), emptyList(), true)?.first ?: return null
		val vCall = spill(KCall(vTarget, listOf(coerce(lowerPure(vReceiver), vTarget.params[0].type))))
		return if (inAsObject) vCall else adapt(vCall, inRead)
	}

	private fun isErasedReceiver(inReceiver: IrExpression?, inFunction: IrSimpleFunction): Boolean {
		if (inReceiver == null || inFunction.dispatchReceiverParameter == null) return false
		val vThis = (mIrFunction as? IrSimpleFunction)?.dispatchReceiverParameter ?: (mIrFunction as? IrConstructor)?.parentAsClass?.thisReceiver
		if (inReceiver is IrGetValue && vThis != null && inReceiver.symbol.owner == vThis) return false
		if (kType(inReceiver.type) !is KClassType) return false
		if (isUncertain(inReceiver)) return true
		val vReal = if (inFunction.isFakeOverride) inFunction.resolveFakeOverride() ?: inFunction else inFunction
		return mImporter.mayBeOtherInstantiation(closed(inReceiver.type), vReal.parentAsClass)
	}

	private fun memberTarget(inFunction: IrSimpleFunction, inIsSuper: Boolean, inReceiver: IrType?, inOwnArguments: List<IrType?>, inIsErased: Boolean = false): Pair<KFunction, Subst>? {
		// The class of a struct value is known exactly: its implementation is called directly, the value never boxed.
		if (inReceiver != null && kType(inReceiver) is KStructType) {
			val vImplementation = inReceiver.classOrNull?.owner?.let { mImporter.implementationIn(it, inFunction) }
			if (vImplementation != null) {
				val (vReal, vSubst) = mImporter.resolveMember(vImplementation, inReceiver, inOwnArguments)
				if (!isAnyClass(vReal.parentAsClass)) mImporter.functionFor(vReal, vSubst)?.let { return it to vSubst }
			}
		}
		if (!inIsSuper && (mImporter.isVirtual(inFunction) || inIsErased)) {
			val vSubst = mImporter.substFor(inFunction, mImporter.superArguments(inReceiver, inFunction.parentAsClass), inOwnArguments)
			return mImporter.dispatcherFor(inFunction, vSubst)?.let { it to vSubst }
		}
		val (vReal, vSubst) = mImporter.resolveMember(inFunction, inReceiver, inOwnArguments)
		if (isAnyClass(vReal.parentAsClass)) {
			val vReceiverClass = (inReceiver?.let { kType(it) } as? KClassType)?.cls
			mImporter.throwableToStringFor(vReceiverClass, vReal)?.let { return it to vSubst }
		}
		return mImporter.functionFor(vReal, vSubst)?.let { it to vSubst }
	}

	/** Calls member [inFunction] on already-lowered arguments (receiver first, of closed type [inReceiver]). */
	private fun callMember(inElement: IrElement, inFunction: IrSimpleFunction, inArguments: List<KExpr>, inReceiver: IrType?): KExpr {
		val vTarget = memberTarget(inFunction, false, inReceiver, emptyList())?.first ?: return fail(inElement, "call to '${inFunction.kotlinFqName}'")
		return KCall(vTarget, inArguments.mapIndexed { vIndex, vValue -> coerce(vValue, vTarget.params[vIndex].type) })
	}

	private fun lowerCall(inCall: IrCall): KExpr {
		var vCallee = mImporter.sourceOf(inCall.symbol.owner)
		// `f(x)` on a reference (`val f = ::name`, a KFunctionN; `String::length`, a KProperty1) is the `invoke` of the function type it extends.
		if (vCallee.name.asString() == "invoke" && (vCallee.parent as? IrClass)?.kotlinFqName?.asString()?.let { it.startsWith("kotlin.reflect.KFunction") || it.startsWith("kotlin.reflect.KProperty") || it.startsWith("kotlin.reflect.KMutableProperty") } == true) {
			vCallee.overriddenSymbols.map { it.owner }.firstOrNull { isFunctionType(it.parentAsClass.symbol.typeWith()) }?.let { vCallee = it }
		}
		// `get()` of a property reference (`::top`, `data::name`, `String::length`) reads it as its `invoke` does:
		// a reference is the function that reads the property (what `val x by ::top` calls).
		val vReflected = (vCallee.parent as? IrClass)?.kotlinFqName?.asString()
		if (vCallee.name.asString() == "get" && vReflected != null && (vReflected.startsWith("kotlin.reflect.KProperty") || vReflected.startsWith("kotlin.reflect.KMutableProperty"))) {
			val vFunctionType = generateSequence(listOf(vCallee.parentAsClass)) { vLevel -> vLevel.flatMap { vClass -> vClass.superTypes.mapNotNull { it.classOrNull?.owner } }.takeIf { it.isNotEmpty() } }
				.flatten().flatMap { it.superTypes }.firstOrNull { isFunctionType(it) }?.classOrNull?.owner
			vFunctionType?.declarations?.filterIsInstance<IrSimpleFunction>()?.firstOrNull { it.name.asString() == "invoke" }?.let { vCallee = it }
		}
		// `set(value)` of a reference to a `var`: through the setter it holds (`MutableReference0`).
		if (vCallee.name.asString() == "set" && (vReflected == "kotlin.reflect.KMutableProperty0" || vReflected == "kotlin.reflect.KMutableProperty1")) {
			val vSetter = mImporter.internalFunction(if (vReflected.endsWith("0")) "mutableReferenceSet0" else "mutableReferenceSet1")
			val vReceiverType = inCall.arguments.firstOrNull()?.let { closed(it.type) }
			if (vSetter != null && vReceiverType != null) {
				val vSubst = mImporter.substFor(vSetter, emptyList(), mImporter.superArguments(vReceiverType, vCallee.parentAsClass))
				mImporter.functionFor(vSetter, vSubst)?.let { vTarget -> return adapt(callFunction(inCall, vTarget, vSetter, inCall.arguments, null, vSubst), inCall) }
			}
		}
		// A call into a `companion { }` block has no receiver: it is a call to a top-level function.
		val vOwner = (vCallee.parent as? IrClass)?.takeUnless { isStaticMember(vCallee) }
		if (vOwner == null && vCallee.name.asString() == "newArray" && vCallee.kotlinFqName.asString() == "ktc2.internal.newArray") {
			val vType = typeOf(inCall) as? KArrayType ?: return fail(inCall, "newArray of '${inCall.type.render()}'")
			return KArrayNew(lowerPure(inCall.arguments[0]!!), vType)
		}
		if (vOwner == null && vCallee.parent.kotlinFqName.asString() == "kotlinx.cinterop.internal") {
			lowerInteropIntrinsic(inCall, vCallee.name.asString())?.let { return it }
		}
		// The field of an exception that holds what was suppressed for it.
		if (vOwner == null && vCallee.parent.kotlinFqName.asString() == "ktc2.internal" && vCallee.name.asString() in setOf("throwableSuppressed", "setThrowableSuppressed")) {
			val vBase = KClassType(mImporter.throwableBase())
			if (vCallee.name.asString() == "throwableSuppressed") {
				return KFieldRead(coerce(lowerPure(inCall.arguments[0]!!), vBase), mImporter.throwableSuppressedField)
			}
			val vSides = lowerOperands(listOf(inCall.arguments[0]!!, inCall.arguments[1]!!), inLastMayBeImpure = true)
			mBlock += KFieldSet(coerce(vSides[0], vBase), mImporter.throwableSuppressedField, coerce(vSides[1], KObjType))
			return KUnitValue
		}
		if (vOwner != null) lowerSimpleEnumMember(inCall, vCallee, vOwner)?.let { return it }
		// `enumOf<T>(number) { … }` for a `@SimpleEnum` class with numbers of its own: the number is compared with theirs.
		if (vOwner == null && vCallee.name.asString() == "enumOf" && vCallee.kotlinFqName.asString() == "ktc2.enumOf") {
			val vEnum = inCall.typeArguments.firstOrNull()?.let { closed(it).classOrNull?.owner }?.let { mImporter.simpleEnumOf(it) }
			val vType = typeOf(inCall)
			if (vEnum != null && vEnum.isNumbered && vType != null) {
				val vNumber = spill(lowerPure(inCall.arguments[0]!!))
				val vResult = newTemp(vType)
				mBlock += KLet(vResult, KNull(vType))
				for (vEntry in vEnum.entries) {
					val vFound = inBlock { mBlock += KSet(vResult, coerce(vEnum.constant(vEntry), vType)) }
					mBlock += KIf(KBinary(KBinaryOp.EQ, vNumber, KConst(vEntry.value, kIntType), KPrim.INT, kBoolType), vFound, emptyList())
				}
				return KRead(vResult)
			}
		}
		// `typeOf<T>()`: built where it is written, from the type T stands for there.
		if (vOwner == null && vCallee.name.asString() == "typeOf" && vCallee.kotlinFqName.asString() == "kotlin.reflect.typeOf") {
			val vType = inCall.typeArguments.firstOrNull()?.let { closed(it) } ?: return fail(inCall, "typeOf")
			return adapt(typeValueOf(inCall, vType), inCall)
		}
		// `listOf(a, b, c)` is `elements.asList()` unless there is no element, which the call shows: the
		// list is then of one class for the passes that follow the classes of values.
		if (vOwner == null && vCallee.name.asString() == "listOf" && vCallee.kotlinFqName.asString() == "kotlin.collections.listOf") {
			val vElements = (inCall.arguments.singleOrNull() as? IrVararg)?.elements
			if (vElements != null && vElements.isNotEmpty() && vElements.none { it is IrSpreadElement }) {
				val vFunction = mImporter.internalFunction("kotlin_collections_asList")
				val vSubst = vFunction?.let { mImporter.substFor(it, emptyList(), inCall.typeArguments.map { vType -> vType?.let { closed(it) } }) }
				val vTarget = if (vFunction == null || vSubst == null) null else mImporter.functionFor(vFunction, vSubst)
				if (vFunction != null && vSubst != null && vTarget != null) return adapt(callFunction(inCall, vTarget, vFunction, inCall.arguments, null, vSubst), inCall)
			}
		}
		vCallee.annotations.firstOrNull { it.type.classFqName?.asString() == "kotlinx.cinterop.internal.CVariadic" }?.let { vAnnotation ->
			return lowerVariadicCall(inCall, vCallee, (vAnnotation.arguments.firstOrNull() as? IrConst)?.value as? String ?: "")
		}
		if (vOwner == null && vCallee.kotlinFqName.asString() == "kotlinx.cinterop.staticCFunction") {
			lowerInteropIntrinsic(inCall, "cFunction")?.let { return it }
		}
		if (vOwner == null && (vCallee.name.asString() == "println" || vCallee.name.asString() == "print") && vCallee.parent.kotlinFqName.asString() == "kotlin.io") {
			return lowerLibraryCall(inCall)
		}
		mLocalFunctions[vCallee]?.let { return adapt(callFunction(inCall, it.invoke, vCallee, inCall.arguments, KRead(it.holder), mSubst), inCall) }
		if (vOwner != null && vCallee.name.asString() == "invoke" && isFunctionType(vOwner.symbol.typeWith())) {
			// Calling a lambda bound to an inline parameter expands it; any other function value is an interface call.
			val vBoundLambda = (inCall.arguments.firstOrNull() as? IrGetValue)?.let { mInlineLambdas[it.symbol.owner] }
			if (vBoundLambda != null) return adapt(expandLambda(vBoundLambda, inCall.arguments.drop(1)), inCall)
		}
		if (vCallee.fileOrNull == null && vOwner != null && (vOwner.isInterface || isNumberClass(vOwner))) {
			// A member the JDK gives a library interface and Kotlin has as an extension (`removeFirst` of a list): the platform layer's.
			redirectToPlatform(inCall)?.let { return it }
			// A library interface (Comparable…): primitives and strings have direct answers,
			// everything else is an ordinary interface call.
			val vReceiverType = inCall.arguments.firstOrNull()?.let { kType(it.type) }
			if ((vReceiverType !is KRefType && vReceiverType !is KStructType) || vReceiverType == KStringType) return lowerLibraryCall(inCall)
		} else if (vCallee.fileOrNull == null && !(vOwner != null && (isAnyClass(vOwner) || isThrowableClass(vOwner) || vOwner.kotlinFqName.asString() == "kotlin.Enum"))) {
			return lowerLibraryCall(inCall)
		}

		val vArguments = inCall.arguments
		val vOwnArguments = inCall.typeArguments.map { vType -> vType?.let { closed(it) } }
		val vProperty = vCallee.correspondingPropertySymbol?.owner
		if (vOwner == null) {
			mImporter.globalGetter(vCallee)?.let { vGlobal ->
				val vRead = KGlobalRead(ensuredFile(vGlobal))
				val vLate = vCallee.correspondingPropertySymbol?.owner?.takeIf { it.isLateinit }?.name?.asString()
				return if (vLate != null) initialized(vRead, vLate) else vRead
			}
			mImporter.globalSetter(vCallee)?.let { vGlobal ->
				mBlock += KGlobalSet(ensuredFile(vGlobal), coerce(lowerRhs(vArguments.last()!!), vGlobal.type))
				return KUnitValue
			}
			if (isExpanded(vCallee)) return expandInline(inCall, vCallee, exactSubst(vCallee, emptyList(), vOwnArguments))
			// `enumEntries<T>()`: the enum class is known where the call is written, and these are its `entries`.
			if (vCallee.name.asString() == "enumEntriesIntrinsic") {
				val vEnum = vOwnArguments.firstOrNull()?.classOrNull?.owner
				val vGetter = vEnum?.declarations?.flatMap { vDeclaration ->
					when (vDeclaration) {
						is IrProperty -> listOfNotNull(vDeclaration.getter)
						is IrSimpleFunction -> listOf(vDeclaration)
						else -> emptyList()
					}
				}?.firstOrNull { (it.body as? IrSyntheticBody)?.kind == IrSyntheticBodyKind.ENUM_ENTRIES }
				vGetter?.let { mImporter.functionFor(it) }?.let { return adapt(spill(KCall(it, emptyList())), inCall) }
			}
			// A function the library writes as a coroutine and the platform layer without one.
			// (Of the same name as the platform's and the same parameters: `zipWithNext()` calls `zipWithNext(transform)`.)
			if (vCallee.name.asString() in kWithoutCoroutine && mImporter.internalFunction(vCallee.kotlinFqName.asString().replace('.', '_'))?.parameters?.size == vCallee.parameters.size) {
				redirectToPlatform(inCall)?.let { return it }
			}
			val vSubst = mImporter.substFor(vCallee, emptyList(), vOwnArguments)
			val vTarget = mImporter.functionFor(vCallee, vSubst) ?: return fail(inCall, "call to '${vCallee.kotlinFqName}'")
			return adapt(callFunction(inCall, vTarget, vCallee, vArguments, null, vSubst), inCall)
		}

		val vReceiver = vArguments.firstOrNull()?.let { closed(it.type) }
		if (vOwner.kotlinFqName.asString() == "ktc2.internal.Storage") {
			val vStorage = vReceiver?.let { kType(it) } as? KArrayType ?: return fail(inCall, "use of Storage")
			return lowerArrayCall(inCall, vCallee.name.asString(), vArguments.map { it!! }, vStorage)
		}
		val vIsSuper = inCall.superQualifierSymbol != null
		if (vIsSuper || !mImporter.isVirtual(vCallee)) {
			val vInlined = if (vCallee.isFakeOverride) vCallee.resolveFakeOverride() ?: vCallee else vCallee
			if (isExpanded(vInlined)) {
				return expandInline(inCall, vInlined, exactSubst(vInlined, mImporter.superArguments(vReceiver, vInlined.parentAsClass), vOwnArguments))
			}
		}
		val vIsErased = !vIsSuper && isErasedReceiver(vArguments.firstOrNull(), vCallee)
		// A default accessor that cannot be overridden is a plain field access.
		if (vProperty != null && (vIsSuper || !(mImporter.isVirtual(vCallee) || vIsErased))) {
			val vReal = if (vCallee.isFakeOverride) vCallee.resolveFakeOverride() ?: vCallee else vCallee
			val vRealProperty = vReal.correspondingPropertySymbol?.owner
			val vField = vRealProperty?.backingField?.let { mImporter.fieldFor(it, vReceiver) }
			if (vField != null && vReal.origin == IrDeclarationOrigin.DEFAULT_PROPERTY_ACCESSOR) {
				if (vReal === vRealProperty.getter) {
					val vRead = readField(fieldTarget(lowerPure(vArguments[0]!!), vField), vField)
					return adapt(if (vRealProperty.isLateinit) initialized(vRead, vRealProperty.name.asString()) else vRead, inCall)
				}
				val vOperands = lowerOperands(listOf(vArguments[0]!!, vArguments[1]!!), inLastMayBeImpure = true)
				writeField(fieldTarget(vOperands[0], vField), vField, coerce(vOperands[1], vField.type))
				return KUnitValue
			}
		}
		val vView = if (vIsErased) receiverView(vArguments.firstOrNull(), vCallee, vReceiver) else vReceiver
		val (vTarget, vSubst) = memberTarget(vCallee, vIsSuper, vView, vOwnArguments, vIsErased) ?: return fail(inCall, "call to '${vCallee.kotlinFqName}'")
		return adapt(callFunction(inCall, vTarget, vCallee, vArguments, null, vSubst), inCall)
	}

	// ==================
	// MARK: Inline functions and closures
	// ==================

	/** Runs [inBlock] with the type parameters read under [inSubst]. */
	private inline fun <R> under(inSubst: Subst, inBlock: () -> R): R {
		val vOuter = mSubst
		mSubst = inSubst
		try {
			return inBlock()
		} finally {
			mSubst = vOuter
		}
	}

	/** Calls a lambda bound to an `inline` parameter: its body is expanded here. */
	private fun expandLambda(inLambda: InlineLambda, inArguments: List<IrExpression?>): KExpr {
		val vValues = lowerOperands(inArguments.filterNotNull()).map { spill(it) }
		return under(inLambda.subst) {
			val vFunction = inLambda.expression.function
			inlineLambda(inLambda.expression, vValues, kType(vFunction.returnType) ?: KUnitType)
		}
	}

	/** The substitution for an expanded call: the actual type arguments, not shared between reference types. */
	private fun exactSubst(inFunction: IrFunction, inClassArguments: List<IrType>, inOwnArguments: List<IrType?>): Subst {
		val vSubst = HashMap<org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol, IrType>()
		(inFunction.parent as? IrClass)?.let { classParameters(it) }?.forEachIndexed { vIndex, vParam ->
			vSubst[vParam.symbol] = inClassArguments.getOrNull(vIndex) ?: mImporter.mBuiltIns.anyNType
		}
		inFunction.typeParameters.forEachIndexed { vIndex, vParam ->
			vSubst[vParam.symbol] = inOwnArguments.getOrNull(vIndex) ?: mImporter.mBuiltIns.anyNType
		}
		return vSubst
	}

	/**
	 * Expands a call to an `inline` function: arguments are evaluated once into locals, lambda
	 * arguments are bound without being evaluated, and the callee's body is lowered here.
	 */
	private fun expandInline(inCall: IrCall, inCallee: IrSimpleFunction, inSubst: Subst): KExpr {
		val vParams = inCallee.parameters
		val vArguments = inCall.arguments
		val vLambdas = vParams.indices.map { vIndex ->
			if (!isInlinedParameter(vParams[vIndex])) return@map null
			when (val vArgument = vArguments[vIndex]) {
				is IrFunctionExpression -> InlineLambda(vArgument, mSubst)
				is IrGetValue -> mInlineLambdas[vArgument.symbol.owner]
				else -> null
			}
		}
		val vValueIndices = vParams.indices.filter { vArguments[it] != null && vLambdas[it] == null }
		val vValues = lowerOperands(vValueIndices.map { vArguments[it]!! })

		val vSavedLocals = HashMap(mLocals)
		val vSavedLambdas = HashMap(mInlineLambdas)
		val vSavedCells = HashMap(mCells)
		val vResult = under(inSubst) {
			fun bind(inParam: IrValueParameter, inValue: KExpr) {
				val vType = kType(inParam.type)
				if (vType == null || vType == KUnitType || vType == KNothingType) {
					fail(inParam, "inline parameter of type '${inParam.type.render()}'")
					return
				}
				val vLocal = KLocal(freshName(if (inParam.kind == IrParameterKind.Regular) inParam.name.asString() else "self"), vType)
				mTemps += vLocal
				mBlock += KLet(vLocal, coerce(inValue, vType))
				mLocals[inParam] = vLocal
			}
			vValueIndices.forEachIndexed { vPosition, vIndex -> bind(vParams[vIndex], vValues[vPosition]) }
			vParams.indices.forEach { vIndex -> vLambdas[vIndex]?.let { mInlineLambdas[vParams[vIndex]] = it } }
			for (vIndex in vParams.indices) {
				if (vArguments[vIndex] != null) continue
				val vDefault = defaultOf(inCallee, vIndex)
				when {
					vDefault == null -> {
						val vEmpty = kType(vParams[vIndex].type)?.let { emptyVararg(vParams[vIndex], it) }
						if (vEmpty == null) fail(inCall, "call with a missing argument") else bind(vParams[vIndex], vEmpty)
					}
					vDefault is IrFunctionExpression && isInlinedParameter(vParams[vIndex]) -> mInlineLambdas[vParams[vIndex]] = InlineLambda(vDefault, inSubst)
					else -> bind(vParams[vIndex], lowerRhs(vDefault))
				}
			}
			mCellVariables += mImporter.captures.cellVariables(inCallee)
			expandBody(inCallee, kType(inCallee.returnType) ?: KUnitType)
		}
		mLocals.clear()
		mLocals += vSavedLocals
		mInlineLambdas.clear()
		mInlineLambdas += vSavedLambdas
		mCells.clear()
		mCells += vSavedCells
		return adapt(vResult, inCall)
	}

	/**
	 * Creates the closure object for [inFunction] (a lambda or a local function): a class with
	 * one field per captured value, and an `invoke` whose body is imported later.
	 *
	 * @param inSelf the function itself when it is a local function that may call itself
	 * @return the local holding the new object, and its `invoke`
	 */
	private fun makeClosure(
		inElement: IrElement,
		inFunction: IrSimpleFunction,
		inSelf: IrFunction?,
		inSam: IrType? = null,
		/** The values bound to the first parameters of [inFunction] (a function reference `x::f`). */
		inBound: List<KLocal> = emptyList(),
	): Pair<KLocal, KFunction>? {
		class Capture(val declaration: Any, val name: String, val source: KLocal, val cell: KField?, val invoke: KFunction?)

		val vSet = CaptureSet()
		collectCaptures(mImporter.captures.freeVariables(inFunction), vSet)
		val vCaptures = ArrayList<Capture>()
		inBound.forEachIndexed { vIndex, vLocal -> vCaptures += Capture(inFunction.parameters[vIndex], "bound$vIndex", vLocal, null, null) }
		for ((vDeclaration, vLocal) in vSet.values) {
			val vName = if (vDeclaration is IrValueParameter && vDeclaration.kind != IrParameterKind.Regular) "self" else vDeclaration.name.asString()
			vCaptures += Capture(vDeclaration, vName, vLocal, mCells[vDeclaration], null)
		}
		for ((vLocalFunction, vHolder) in vSet.functions) {
			vCaptures += Capture(vLocalFunction, vLocalFunction.name.asString(), vHolder.holder, null, vHolder.invoke)
		}
		val vLambdas = HashMap(vSet.lambdas)
		val vReturn = kType(inFunction.returnType)
		val vNames = HashSet<String>()
		val vParams = ArrayList<KLocal>()
		for (vParam in inFunction.parameters.drop(inBound.size)) {
			val vType = kType(vParam.type)
			if (vType == null || vType == KUnitType || vType == KNothingType) {
				fail(vParam, "lambda parameter of type '${vParam.type.render()}'")
				return null
			}
			var vName = cIdentifier(vParam.name.asString().trim('<', '>'))
			while (!vNames.add(vName) || vName == "self") vName += "_"
			vParams += KLocal(vName, vType)
		}
		if (vReturn == null) {
			fail(inElement, "lambda returning '${inFunction.returnType.render()}'")
			return null
		}
		val vClass = mImporter.newClosureClass(mFile, vCaptures.map { it.name to it.source.type })
		val vInvoke = KFunction("${vClass.cName}_invoke", vClass.unit, listOf(KLocal("self", KClassType(vClass))) + vParams, vReturn)
		vClass.unit.functions += vInvoke
		val vSamClass = inSam?.classOrNull?.owner
		if (vSamClass != null) mImporter.registerSam(vClass, vInvoke, vSamClass, mImporter.argumentsOf(closed(inSam))) else mImporter.registerClosure(vClass, vInvoke)
		val vBindings = vCaptures.mapIndexed { vIndex, vCapture -> Binding(vCapture.declaration, vClass.fields[vIndex], vCapture.cell, vCapture.invoke) }
		val vSubst = mSubst
		mImporter.later { BodyBuilder(mImporter, vInvoke, inFunction, mFile, vSubst).buildClosure(vBindings, inSelf, vLambdas) }

		val vObject = newTemp(KClassType(vClass))
		mBlock += KLet(vObject, KAlloc(vClass))
		vCaptures.forEachIndexed { vIndex, vCapture -> mBlock += KFieldSet(KRead(vObject), vClass.fields[vIndex], KRead(vCapture.source)) }
		return vObject to vInvoke
	}

	/** `::function`, `value::method`: a closure object forwarding to the function, holding the bound receiver if any. */
	/**
	 * `Comparator(compare)` where `compare` is a function value: an object implementing the
	 * interface, whose one method calls the function it holds.
	 */
	private fun wrapFunctionValue(inConversion: IrTypeOperatorCall): KExpr {
		val vWhat = "conversion of a function value to '${inConversion.typeOperand.render()}'"
		val vType = closed(inConversion.typeOperand)
		val vInterface = vType.classOrNull?.owner ?: return fail(inConversion, vWhat)
		val vMethod = vInterface.declarations.filterIsInstance<IrSimpleFunction>().singleOrNull { it.modality == org.jetbrains.kotlin.descriptors.Modality.ABSTRACT }
			?: return fail(inConversion, vWhat)
		val vSubst = classParameters(vInterface).map { it.symbol }.zip(mImporter.argumentsOf(vType)).toMap()
		val vParameters = vMethod.parameters.filter { it.kind == IrParameterKind.Regular }.map { mImporter.close(it.type, vSubst) }
		val vReturn = mImporter.close(vMethod.returnType, vSubst)
		val vParamTypes = vParameters.map { kType(it) ?: return fail(inConversion, vWhat) }
		val vReturnType = kType(vReturn) ?: return fail(inConversion, vWhat)
		// The function is called as any function value is: through `invoke` of its FunctionN.
		val vInvokeOwner = mImporter.mBuiltIns.functionN(vParameters.size)
		val vInvoke = vInvokeOwner.declarations.filterIsInstance<IrSimpleFunction>().firstOrNull { it.name.asString() == "invoke" }
			?: return fail(inConversion, vWhat)
		val vDispatcher = mImporter.dispatcherFor(vInvoke, mImporter.substFor(vInvoke, vParameters + vReturn, emptyList()))
			?: return fail(inConversion, vWhat)
		val vFunction = spill(coerce(lowerPure(inConversion.argument), KObjType))

		val vClass = mImporter.newClosureClass(mFile, listOf("function" to KObjType))
		val vParams = listOf(KLocal("self", KClassType(vClass))) + vParamTypes.mapIndexed { vIndex, vParamType -> KLocal("p$vIndex", vParamType) }
		val vCall = KFunction("${vClass.cName}_invoke", vClass.unit, vParams, vReturnType)
		vClass.unit.functions += vCall
		mImporter.registerSam(vClass, vCall, vInterface, mImporter.argumentsOf(vType))
		BodyBuilder(mImporter, vCall, null, mFile, mSubst).buildReferenceInvoke(vDispatcher, vClass.fields)

		val vObject = newTemp(KClassType(vClass))
		mBlock += KLet(vObject, KAlloc(vClass))
		mBlock += KFieldSet(KRead(vObject), vClass.fields[0], vFunction)
		return coerce(KRead(vObject), typeOf(inConversion))
	}

	/**
	 * `::f`, `x::f`, `Type::f` as the compiler hands them over since Kotlin 2.4: an `invoke`
	 * function that calls the target, whose first parameters take the bound values. It is
	 * lowered like a lambda that captures those values.
	 */
	private fun lowerRichFunctionReference(inReference: IrRichFunctionReference): KExpr {
		val vBound = lowerOperands(inReference.boundValues).mapIndexed { vIndex, vValue ->
			val vType = kType(inReference.invokeFunction.parameters[vIndex].type) ?: vValue.type
			KLocal(freshName("bound"), vType).also {
				mTemps += it
				mBlock += KLet(it, coerce(vValue, vType))
			}
		}
		// A reference used where a `fun interface` is expected implements that interface.
		val vInterface = inReference.overriddenFunctionSymbol.owner.parentAsClass
		val vSam = inReference.type.takeIf { !isFunctionType(vInterface.symbol.typeWith()) && !vInterface.kotlinFqName.asString().startsWith("kotlin.reflect.") }
		return makeClosure(inReference, inReference.invokeFunction, null, vSam, vBound)?.let { KRead(it.first) } ?: KUnitValue
	}

	/**
	 * `::name` as a value, which is what a delegate receives as its `property`: an object that
	 * knows the name and nothing else, there being no reflection.
	 */
	private fun propertyObject(inElement: IrElement, inName: String?): KExpr {
		mImporter.propertyObjectFor(inName ?: "<unknown>")?.let { return coerce(KStaticRead(it), KObjType) }
		val vFactory = mImporter.internalFunction("propertyReference")?.let { mImporter.functionFor(it) }
			?: return fail(inElement, "property reference")
		return spill(KCall(vFactory, listOf(KStringLit(inName ?: "<unknown>"))))
	}

	/** `Name::class` as a value: an object that knows the name of the class, there being no reflection. */
	/** `Name::class` for the closed type [inType]. */
	private fun classValueOf(inElement: IrElement, inType: IrType): KExpr {
		val vClass = inType.classOrNull?.owner
		// A class whose values are plain structs is, as an object, its box.
		val vStruct = (kType(inType.makeNotNull()) as? KStructType)?.struct
		return classObject(inElement, vStruct?.let { mImporter.boxFor(it).name }
			?: vClass?.takeIf { !it.isInterface && it.fileOrNull != null }?.let { mImporter.classFor(it)?.name } ?: vClass?.kotlinFqName?.asString())
	}

	/**
	 * `typeOf<T>()` for the closed type [inType], known where it is written (T is reified): its class, the
	 * projections of its arguments, and whether it may be null (`ktc2.internal.TypeReference`).
	 */
	private fun typeValueOf(inElement: IrElement, inType: IrType): KExpr {
		val vSimple = inType as? IrSimpleType ?: return fail(inElement, "typeOf of '${inType.render()}'")
		if (vSimple.classOrNull == null) return fail(inElement, "typeOf of '${inType.render()}', which is not a class")
		val vReference = mImporter.internalFunction("typeReference")?.let { mImporter.functionFor(it) } ?: return fail(inElement, "typeOf")
		val vProjection = mImporter.internalFunction("typeProjection")?.let { mImporter.functionFor(it) } ?: return fail(inElement, "typeOf")
		val vClassifier = spill(classValueOf(inElement, vSimple.makeNotNull()))
		val vArgumentsType = vReference.params[2].type as KArrayType
		val vArguments = newTemp(vArgumentsType)
		mBlock += KLet(vArguments, KArrayNew(KConst(vSimple.arguments.size, kIntType), vArgumentsType))
		vSimple.arguments.forEachIndexed { vIndex, vArgument ->
			// Star -1, invariant 0, in 1, out 2: `typeProjection` in the platform layer.
			val vProjected = when (vArgument) {
				is IrTypeProjection -> {
					val vVariance = when (vArgument.variance) {
						org.jetbrains.kotlin.types.Variance.IN_VARIANCE -> 1
						org.jetbrains.kotlin.types.Variance.OUT_VARIANCE -> 2
						else -> 0
					}
					val vInner = spill(typeValueOf(inElement, vArgument.type))
					spill(KCall(vProjection, listOf(KConst(vVariance, kIntType), coerce(vInner, vProjection.params[1].type))))
				}
				else -> spill(KCall(vProjection, listOf(KConst(-1, kIntType), KNull(vProjection.params[1].type))))
			}
			mBlock += KIndexSet(KRead(vArguments), KConst(vIndex, kIntType), coerce(vProjected, vArgumentsType.element))
		}
		return spill(KCall(vReference, listOf(coerce(vClassifier, vReference.params[0].type), KConst(vSimple.isMarkedNullable(), kBoolType), KRead(vArguments))))
	}

	private fun classObject(inElement: IrElement, inName: String?): KExpr {
		val vFactory = mImporter.internalFunction("classReference")?.let { mImporter.functionFor(it) }
		if (vFactory == null || inName == null) return fail(inElement, "class reference")
		return spill(KCall(vFactory, listOf(KStringLit(inName))))
	}

	private fun lowerFunctionReference(inReference: IrFunctionReference): KExpr {
		val vTarget = inReference.symbol.owner as? IrSimpleFunction ?: return fail(inReference, "constructor reference")
		val vTypes = mImporter.argumentsOf(closed(inReference.type))
		val vBoundIndices = inReference.arguments.indices.filter { inReference.arguments[it] != null }
		val vBound = lowerOperands(vBoundIndices.map { inReference.arguments[it]!! }).map { spill(it) }
		val vOwnArguments = inReference.typeArguments.map { vType -> vType?.let { closed(it) } }
		val vFunction = if (vTarget.parent is IrClass) {
			val vReceiver = vBoundIndices.firstOrNull()?.let { closed(inReference.arguments[it]!!.type) } ?: vTypes.firstOrNull()
			memberTarget(vTarget, false, vReceiver, vOwnArguments)?.first
		} else {
			mImporter.functionFor(vTarget, mImporter.substFor(vTarget, emptyList(), vOwnArguments))
		} ?: return fail(inReference, "reference to '${vTarget.kotlinFqName}'")

		val vParamTypes = vTypes.dropLast(1).map { kType(it) ?: KObjType }
		val vReturn = vTypes.lastOrNull()?.let { kType(it) } ?: KUnitType
		val vClass = mImporter.newClosureClass(mFile, vBound.mapIndexed { vIndex, vValue -> "bound$vIndex" to vValue.type })
		val vParams = listOf(KLocal("self", KClassType(vClass))) + vParamTypes.mapIndexed { vIndex, vType -> KLocal("p$vIndex", vType) }
		val vInvoke = KFunction("${vClass.cName}_invoke", vClass.unit, vParams, vReturn)
		vClass.unit.functions += vInvoke
		mImporter.registerClosure(vClass, vInvoke)
		BodyBuilder(mImporter, vInvoke, null, mFile, mSubst).buildReferenceInvoke(vFunction, vClass.fields)

		val vObject = newTemp(KClassType(vClass))
		mBlock += KLet(vObject, KAlloc(vClass))
		vBound.forEachIndexed { vIndex, vValue -> mBlock += KFieldSet(KRead(vObject), vClass.fields[vIndex], vValue) }
		return KRead(vObject)
	}

	// ==================
	// MARK: C interop
	// ==================

	/** The C spelling `@CType` gives the class of [inType] (`struct point`, `int32_t`). */
	private fun cSpelling(inType: IrType): Pair<String, List<String>>? {
		val vClass = closed(inType).classOrNull?.owner ?: return null
		val vAnnotation = vClass.annotations.firstOrNull { it.type.classFqName?.asString() == "kotlinx.cinterop.internal.CType" } ?: return null
		val vSpelling = (vAnnotation.arguments.firstOrNull() as? IrConst)?.value as? String ?: return null
		return vSpelling to cHeadersOf(vClass.fileOrNull)
	}

	/** A String handed to C: its bytes, or NULL for null when [inExpression] may be null. */
	private fun cArgument(inValue: KExpr, inExpression: IrExpression): KExpr {
		if (inValue.type != KStringType) return inValue
		return KIntrinsic(if (inExpression.type.isMarkedNullable()) "ktc2_str_cptr" else "ktc2_str_bytes", listOf(inValue), KPtrType)
	}

	/**
	 * A call to a C function taking `...` (`printf`). [inText] is the C call with `$n` for the
	 * declared parameters and `$*` where the extra arguments of this call go, each passed as it is.
	 */
	private fun lowerVariadicCall(inCall: IrCall, inCallee: IrSimpleFunction, inText: String): KExpr {
		val vFixed = inCall.arguments.dropLast(1).map { it ?: return fail(inCall, "call to '${inCallee.name}' with a default argument") }
		val vExtra = ((inCall.arguments.lastOrNull() as? IrVararg)?.elements ?: emptyList())
			.map { it as? IrExpression ?: return fail(inCall, "call to '${inCallee.name}' with a spread argument") }
		val vExpressions = vFixed + vExtra
		val vArguments = lowerOperands(vExpressions).mapIndexed { vIndex, vValue -> cArgument(vValue, vExpressions[vIndex]) }
		val vType = kType(inCall.type) ?: return fail(inCall, "result of '${inCallee.name}'")
		return KIntrinsic(inCallee.name.asString(), vArguments, vType, isPure = false).also {
			it.template = inText.replace("$*", vExtra.indices.joinToString("") { vIndex -> ", $${vFixed.size + vIndex}" })
			it.headers = cHeadersOf(inCallee.fileOrNull)
		}
	}

	/**
	 * The functions of `kotlinx.cinterop.internal` that the interop library and generated
	 * bindings are written with; null when [inName] is an ordinary function of that package.
	 *
	 * - `cExpr<R>("C text with $0 $1", a, b)` is that C expression, of type `R`. A String
	 *   argument stands for its `const char*`.
	 * - `cSizeOf<T>()` / `cAlignOf<T>()` are `sizeof` / `_Alignof` of the C type behind `T`.
	 * - `cFunction(::f)` is the address of the C function generated for `f`.
	 */
	private fun lowerInteropIntrinsic(inCall: IrCall, inName: String): KExpr? {
		when (inName) {
			"cExpr" -> {
				val vTemplate = (inCall.arguments[0] as? IrConst)?.value as? String ?: return fail(inCall, "cExpr with a text that is not a string literal")
				val vElements = (inCall.arguments.getOrNull(1) as? IrVararg)?.elements ?: emptyList()
				val vExpressions = vElements.map { it as? IrExpression ?: return fail(inCall, "cExpr with a spread argument") }
				val vArguments = lowerOperands(vExpressions).mapIndexed { vIndex, vValue -> cArgument(vValue, vExpressions[vIndex]) }
				val vType = kType(inCall.type) ?: return fail(inCall, "cExpr of type '${inCall.type.render()}'")
				return KIntrinsic("cExpr", vArguments, vType, isPure = false).also {
					it.template = vTemplate
					it.headers = cHeadersOf(mFile)
				}
			}
			"cSizeOf", "cAlignOf" -> {
				val vType = inCall.typeArguments.firstOrNull() ?: return fail(inCall, "$inName without a type")
				val (vSpelling, vHeaders) = cSpelling(vType) ?: return fail(inCall, "$inName of '${closed(vType).render()}', which is not a C type")
				val vOperator = if (inName == "cSizeOf") "sizeof" else "_Alignof"
				return KIntrinsic(inName, emptyList(), kType(inCall.type) ?: kIntType).also {
					it.template = (if (inName == "cSizeOf") "(int64_t)" else "(int32_t)") + "$vOperator($vSpelling)"
					it.headers = vHeaders
				}
			}
			"cCall" -> {
				// A call through a C function pointer: the emitter casts it to the C type the arguments and the result give.
				val vElements = (inCall.arguments.getOrNull(1) as? IrVararg)?.elements ?: emptyList()
				val vExpressions = listOf(inCall.arguments[0]!!) + vElements.map { it as? IrExpression ?: return fail(inCall, "cCall with a spread argument") }
				val vArguments = lowerOperands(vExpressions).mapIndexed { vIndex, vValue -> cArgument(vValue, vExpressions[vIndex]) }
				val vType = kType(inCall.type) ?: return fail(inCall, "cCall of type '${inCall.type.render()}'")
				return KIntrinsic("ktc2_call_pointer", vArguments, vType, isPure = false)
			}
			"cFunction" -> {
				(inCall.arguments[0] as? IrFunctionExpression)?.let { vLambda ->
					// A lambda that uses nothing around it is a C function of its own.
					if (mImporter.captures.freeVariables(vLambda.function).isNotEmpty()) {
						return fail(inCall, "staticCFunction of a lambda that uses values from around it (C has nowhere to keep them; pass them through a StableRef)")
					}
					val vFunction = mImporter.staticFunctionFor(vLambda.function, mFile, mSubst)
						?: return fail(inCall, "staticCFunction of this lambda")
					return callbackPointer(vFunction, vLambda.function)
				}
				(inCall.arguments[0] as? IrRichFunctionReference)?.let { vRich ->
					if (vRich.boundValues.isNotEmpty()) return fail(inCall, "staticCFunction of a bound reference (C has nowhere to keep the receiver)")
					val vNamed = vRich.reflectionTargetSymbol?.owner as? IrSimpleFunction
					val vFunction = if (vNamed != null && vNamed.parent !is IrClass) {
						mImporter.functionFor(vNamed, mImporter.substFor(vNamed, emptyList(), emptyList()))
					} else {
						mImporter.staticFunctionFor(vRich.invokeFunction, mFile, mSubst)
					} ?: return fail(inCall, "staticCFunction of this reference")
					return callbackPointer(vFunction, vNamed?.takeIf { it.parent !is IrClass } ?: vRich.invokeFunction)
				}
				val vReference = inCall.arguments[0] as? IrFunctionReference ?: return fail(inCall, "staticCFunction of something else than a function reference (::name)")
				val vTarget = vReference.symbol.owner as? IrSimpleFunction
				if (vTarget == null || vTarget.parent is IrClass || vReference.arguments.any { it != null }) {
					return fail(inCall, "staticCFunction of something else than a top-level function")
				}
				val vFunction = mImporter.functionFor(vTarget, mImporter.substFor(vTarget, emptyList(), emptyList()))
					?: return fail(inCall, "reference to '${vTarget.kotlinFqName}'")
				return callbackPointer(vFunction, vTarget)
			}
		}
		return null
	}

	/**
	 * The address C calls for [inFunction], generated from the Kotlin function [inSource]. When
	 * that one takes or returns a struct by value (`CValue<T>`), C calls a function of its own
	 * shape placed in front of it, which moves the structs in and out of `CValue`s.
	 */
	private fun callbackPointer(inFunction: KFunction, inSource: IrFunction): KExpr {
		val vHeaders = LinkedHashSet<String>()
		fun structOf(inType: IrType): String? {
			val vType = closed(inType) as? IrSimpleType ?: return null
			if (vType.classOrNull?.owner?.kotlinFqName?.asString() != "kotlinx.cinterop.CValue") return null
			val vStruct = vType.arguments.firstOrNull()?.typeOrNull ?: return null
			return cSpelling(vStruct)?.also { vHeaders += it.second }?.first
		}
		val vParams = inSource.parameters.map { structOf(it.type) }
		val vResult = structOf(inSource.returnType)
		if ((vParams.all { it == null } && vResult == null) || vParams.size != inFunction.params.size) {
			return KIntrinsic("cFunction", emptyList(), KPtrType).also {
				it.template = "(void*)${inFunction.cName}"
				it.functions = listOf(inFunction)
			}
		}
		val vTrampoline = KTrampoline("${inFunction.cName}_c", inFunction, vParams, vResult)
		return KIntrinsic("cFunction", emptyList(), KPtrType).also {
			it.template = "(void*)${vTrampoline.name}"
			it.functions = listOf(inFunction)
			it.headers = vHeaders.toList()
			it.trampoline = vTrampoline
		}
	}

	// ==================
	// MARK: Library intrinsics
	// ==================

	/**
	 * A library function without a Kotlin body can be provided by the platform layer: a function
	 * of `ktc2.internal` named after it, with dots as underscores (`kotlin_collections_asList`).
	 */
	private fun redirectToPlatform(inCall: IrCall): KExpr? {
		val vCallee = inCall.symbol.owner
		// `a..b` between integers is settled by the kinds of both sides, not by the class of the first (`1..<Long.MIN_VALUE`): see `lowerLibraryCall`.
		if (vCallee.name.asString().let { it == "rangeTo" || it == "rangeUntil" } && kPrimByClass[vCallee.parent.kotlinFqName.asString()].let { it != null && it != KPrim.CHAR }) return null
		val vBase = vCallee.kotlinFqName.asString().replace('.', '_')
		// A name ending in __<ReceiverClass> covers one overload among several (single() on a CharArray).
		val vReceiverClass = inCall.arguments.firstOrNull()?.let { closed(it.type).classOrNull?.owner?.name?.asString() }
		val vFunction = (vReceiverClass?.let { mImporter.internalFunction("${vBase}__$it") } ?: mImporter.internalFunction(vBase)) ?: return null
		val vOwnArguments = inCall.typeArguments.map { vType -> vType?.let { closed(it) } }
		if (isExpanded(vFunction)) return expandInline(inCall, vFunction, exactSubst(vFunction, emptyList(), vOwnArguments))
		val vSubst = mImporter.substFor(vFunction, emptyList(), vOwnArguments)
		val vTarget = mImporter.functionFor(vFunction, vSubst) ?: return null
		return adapt(callFunction(inCall, vTarget, vFunction, inCall.arguments, null, vSubst), inCall)
	}

	private fun lowerLibraryCall(inCall: IrCall): KExpr {
		val vCallee = inCall.symbol.owner
		val vName = vCallee.name.asString()
		val vParent = vCallee.parent.kotlinFqName.asString()
		val vArguments = inCall.arguments
		val vWhat = "call to '$vParent.$vName'"
		redirectToPlatform(inCall)?.let { return it }

		if (vParent.startsWith("kotlin.coroutines")) {
			// There are no coroutines. A library function that uses them on one of its paths (`windowed`
			// on something that is not a list) still works on the others: this path fails when it is taken.
			mBlock += failure("throwNoCoroutines", "ktc2_fail_when")
			return KZero(typeOf(inCall)?.takeIf { it != KNothingType } ?: KObjType)
		}
		if (vParent == "kotlin.io" && (vName == "println" || vName == "print")) {
			val vPieces = when (val vArgument = vArguments.firstOrNull()) {
				null -> emptyList()
				is IrStringConcatenation -> vArgument.arguments
				else -> listOf(vArgument)
			}
			mBlock += KPrint(lowerParts(vPieces), vName == "println")
			return KUnitValue
		}
		if (vParent == "kotlin" && vName == "<get-isInitialized>") {
			// `::property.isInitialized`: whether the field of a `lateinit` property holds something yet.
			val vReference = vArguments.firstOrNull()
			val vProperty = when (vReference) {
				is IrRichPropertyReference -> vReference.reflectionTargetSymbol?.owner as? IrProperty
				is IrPropertyReference -> vReference.symbol.owner
				else -> null
			}
			val vBacking = vProperty?.backingField ?: return fail(inCall, vWhat)
			mImporter.globalFor(vBacking)?.let { return not(isNull(spill(KGlobalRead(ensuredFile(it))))) }
			val vBound = when (vReference) {
				is IrRichPropertyReference -> vReference.boundValues.firstOrNull()
				is IrPropertyReference -> vReference.arguments.firstOrNull()
				else -> null
			} ?: return fail(inCall, vWhat)
			val vField = mImporter.fieldFor(vBacking, closed(vBound.type)) ?: return fail(inCall, vWhat)
			return not(isNull(spill(readField(fieldTarget(lowerPure(vBound), vField), vField))))
		}
		if (vParent == "kotlin" && (vName == "enumValues" || vName == "enumValueOf")) {
			// The enum class is known where the call is written: these are its `values()` and `valueOf()`.
			val vEnum = inCall.typeArguments.firstOrNull()?.let { closed(it).classOrNull?.owner } ?: return fail(inCall, vWhat)
			val vWanted = if (vName == "enumValues") "values" else "valueOf"
			val vTarget = vEnum.declarations.filterIsInstance<IrSimpleFunction>().firstOrNull { it.name.asString() == vWanted && it.body is IrSyntheticBody }
				?.let { mImporter.functionFor(it) } ?: return fail(inCall, vWhat)
			val vValues = lowerOperands(vArguments.filterNotNull())
			return adapt(spill(KCall(vTarget, vValues.mapIndexed { vIndex, vValue -> coerce(vValue, vTarget.params[vIndex].type) })), inCall)
		}
		if (vParent == "kotlin.internal.ir" && vName == "noWhenBranchMatchedException") {
			mBlock += KEval(KIntrinsic("ktc2_fail_when", emptyList(), KUnitType, isPure = false))
			return KUnitValue
		}
		// arrayOf() / emptyArray(): no elements, so no vararg argument at all.
		if (vParent == "kotlin" && (vName == "emptyArray" || (vName.endsWith("rrayOf") && vArguments.all { it == null }))) {
			val vType = typeOf(inCall) as? KArrayType ?: return fail(inCall, vWhat)
			return KArrayNew(KConst(0, kIntType), vType)
		}
		if (vArguments.isEmpty() || vArguments.any { it == null }) return fail(inCall, vWhat)
		val vIrArguments = vArguments.map { it!! }

		if (vParent == "kotlin.internal.ir") {
			if (vName == "CHECK_NOT_NULL") return lowerNotNull(inCall, vIrArguments[0])
			if (vName == "EQEQ" || vName == "EQEQEQ" || vName == "ieee754equals") {
				val vTypes = vIrArguments.map { kType(it.type) }
				// A field of an object of an uncertain instantiation is compared as the object it is read as.
				val vIsUncertainRead = vIrArguments.any { it is IrGetField && isUncertain(it.receiver) }
				if (vTypes.any { it !is KPrimType } || vIsUncertainRead) return lowerEquality(inCall, vIrArguments[0], vIrArguments[1], vName == "EQEQEQ")
			}
		}
		if (vParent == "kotlin.String") return lowerStringCall(inCall, vName, vIrArguments)
		if (vParent == "kotlin.Comparable" && vName == "compareTo" && vIrArguments.size == 2) {
			// A number that may be null and is known here not to be (`min !== null && max !== null`): the number.
			val vSides = lowerOperands(vIrArguments).map { if (it.type is KOptType) KOptValue(it) else it }
			val vPrim = primOf(vSides[0])
			return when {
				vSides[0].type == KStringType -> KIntrinsic("ktc2_str_compare", listOf(vSides[0], coerce(vSides[1], KStringType)), kIntType)
				vPrim != null -> KBinary(KBinaryOp.CMP, vSides[0], coerce(vSides[1], KPrimType(vPrim)), vPrim, kIntType)
				else -> fail(inCall, vWhat)
			}
		}
		(kType(vIrArguments[0].type) as? KArrayType)?.let { vArrayType ->
			if (isArrayClassName(vParent)) return lowerArrayCall(inCall, vName, vIrArguments, vArrayType)
		}
		// arrayOf(1, 2), intArrayOf(...): the vararg already is the array.
		if (vParent == "kotlin" && vName.endsWith("rrayOf") && vIrArguments.size == 1 && vIrArguments[0] is IrVararg) {
			return lowerRhs(vIrArguments[0])
		}
		if (vParent == "kotlin" && vName == "arrayOfNulls" && vIrArguments.size == 1) {
			val vType = typeOf(inCall) as? KArrayType ?: return fail(inCall, vWhat)
			return KArrayNew(checkedLength(lowerPure(vIrArguments[0])), vType)
		}
		// String?.plus(Any?) is an extension in package kotlin.
		if (vParent == "kotlin" && vName == "plus" && vIrArguments.size == 2 && kType(vIrArguments[0].type) == KStringType) {
			return concat(lowerParts(vIrArguments))
		}
		if (vName == "toString" && vIrArguments.size == 1) {
			return toStringOf(inCall, spill(lowerPure(vIrArguments[0])), vIrArguments[0].type)
		}

		if (vName == "contains" && vParent.startsWith("kotlin.ranges.") && vIrArguments.size == 2) {
			lowerRangeContains(vIrArguments[0], vIrArguments[1])?.let { return it }
		}

		// `toInt()`, `toDouble()`... of a `Number` whose kind is known here: of the number itself. On an object it is a call like any other.
		if (vParent == "kotlin.Number" && vIrArguments.size == 1) {
			val vTo = kConversionByName[vName]?.takeIf { vName in kNumberConversions } ?: return fail(inCall, vWhat)
			val vReceiver = lowerPure(vIrArguments[0])
			return if (vReceiver.type is KPrimType) convert(vReceiver, vTo) else fail(inCall, vWhat)
		}

		// `a.equals(b)` written out on a number: the `equals` of the object the number is as an `Any`
		// (0.0 is not -0.0 there, NaN is NaN, and a number of another kind is another thing).
		if (vName == "equals" && vIrArguments.size == 2 && kPrimByClass[vParent] != null) {
			val vSides = lowerOperands(vIrArguments)
			return KCall(mImporter.runtime("ktc2_any_equals", listOf(KObjType, KObjType), kBoolType), vSides.map { coerce(it, KObjType) })
		}
		// `a..b` and `a..<b` on integers of any width: the range of `Int`, `Long`, `UInt` or `ULong` both fit in.
		if ((vName == "rangeTo" || vName == "rangeUntil") && vIrArguments.size == 2 && kPrimByClass[vParent] != null) {
			val vKinds = vIrArguments.map { (kType(it.type) as? KPrimType)?.prim }
			val vWork = if (vKinds.any { it == null || it.isFloating || it == KPrim.BOOLEAN || it == KPrim.CHAR }) null else promote(vKinds[0]!!, vKinds[1]!!)
			val vClassName = kPrimByClass.entries.firstOrNull { it.value == vWork }?.key?.removePrefix("kotlin.")
			val vTarget = vClassName?.let { mImporter.internalFunction("kotlin_${it}_$vName") }?.let { mImporter.functionFor(it) }
			if (vWork != null && vTarget != null) {
				val vSides = lowerOperands(vIrArguments)
				return adapt(KCall(vTarget, vSides.map { convert(it, vWork) }), inCall)
			}
		}

		// K2 can leave a smart cast implicit (an Any known to be an Int passed where an Int is
		// expected), so an operand takes the parameter's type when its own is not primitive.
		val vOperandTypes = vIrArguments.mapIndexed { vIndex, vArgument ->
			kType(vArgument.type) as? KPrimType ?: kType(vCallee.parameters[vIndex].type) as? KPrimType
				// A member a number inherits (`hashCode` of a `Double?` known not to be null): its receiver is that number.
				?: kPrimByClass[vParent]?.takeIf { vIndex == 0 }?.let { KPrimType(it) }
		}
		if (vOperandTypes.any { it == null }) return fail(inCall, vWhat)
		val vResultType = typeOf(inCall) as? KPrimType ?: return fail(inCall, vWhat)
		val vOperands = lowerOperands(vIrArguments)
		vOperands.indices.forEach { vOperands[it] = coerce(vOperands[it], vOperandTypes[it]!!) }
		val vFirst = vOperandTypes[0]!!.prim
		if (vName == "<get-code>" && vFirst == KPrim.CHAR) return convert(vOperands[0], KPrim.INT)
		// toUInt() and friends on signed types are extensions in package kotlin.
		if (vParent == "kotlin" && vOperands.size == 1) kConversionByName[vName]?.let { return convert(vOperands[0], it) }

		if (vParent == "kotlin.internal.ir" && vOperands.size == 2) {
			val vOp = kComparisonByName[vName] ?: return fail(inCall, "intrinsic '$vName'")
			val vSecond = vOperandTypes[1]!!.prim
			val vCommon = if (vFirst == KPrim.CHAR && vSecond == KPrim.CHAR) KPrim.CHAR else promote(vFirst, vSecond)
			// `==` on two values the compiler does not know to be Float or Double where it is written (a `T`
			// that stands for Double here) is `equals`: NaN equals NaN, and 0.0 is not -0.0. Where it does
			// know, it asks for `ieee754equals`.
			if (vName == "EQEQ" && vCommon.isFloating) {
				val vOrder = KBinary(KBinaryOp.CMP, convert(vOperands[0], vCommon), convert(vOperands[1], vCommon), vCommon, kIntType)
				return KBinary(KBinaryOp.EQ, vOrder, KConst(0, kIntType), KPrim.INT, kBoolType)
			}
			return KBinary(vOp, convert(vOperands[0], vCommon), convert(vOperands[1], vCommon), vCommon, kBoolType)
		}
		// `key.hashCode()` where `K : Comparable<K>` is a number here: the number's own.
		if (kPrimByClass[vParent] != vFirst && !(vParent == "kotlin.Comparable" && vName == "hashCode")) return fail(inCall, vWhat)

		if (vOperands.size == 1) {
			kConversionByName[vName]?.let { return convert(vOperands[0], it) }
			if (vName == "hashCode") {
				return when (vFirst) {
					KPrim.BOOLEAN, KPrim.LONG, KPrim.ULONG, KPrim.FLOAT, KPrim.DOUBLE ->
						KIntrinsic("ktc2_hash_${vFirst.name.lowercase()}", listOf(vOperands[0]), kIntType)
					// An unsigned number hashes as the signed one it is kept as: `UShort.MAX_VALUE` as -1.
					KPrim.UBYTE -> convert(convert(vOperands[0], KPrim.BYTE), KPrim.INT)
					KPrim.USHORT -> convert(convert(vOperands[0], KPrim.SHORT), KPrim.INT)
					else -> convert(vOperands[0], KPrim.INT)
				}
			}
			// Arithmetic on Byte / Short / Char is done in Int, then narrowed back.
			val vWork = promote(vFirst, vFirst)
			val vOperand = convert(vOperands[0], vWork)
			val vWorkType = KPrimType(vWork)
			val vOne = if (vWork == KPrim.BOOLEAN) kTrue else one(vWork)
			val vValue: KExpr = when (vName) {
				"unaryPlus" -> vOperand
				"unaryMinus" -> KUnary(KUnaryOp.NEG, vOperand, vWorkType)
				"inv" -> KUnary(KUnaryOp.INV, vOperand, vWorkType)
				"not" -> not(vOperand)
				"inc" -> KBinary(KBinaryOp.ADD, vOperand, vOne, vWork, vWorkType)
				"dec" -> KBinary(KBinaryOp.SUB, vOperand, vOne, vWork, vWorkType)
				else -> return fail(inCall, vWhat)
			}
			return convert(vValue, vResultType.prim)
		}
		if (vOperands.size == 2) {
			// `floorDiv` and `mod` of unsigned numbers, which have no sign to round by, are `/` and `%`.
			val vUnsignedOp = when {
				!vFirst.isUnsigned -> null
				vName == "floorDiv" -> KBinaryOp.DIV
				vName == "mod" -> KBinaryOp.REM
				else -> null
			}
			val vOp = vUnsignedOp ?: kBinaryByName[vName] ?: return fail(inCall, vWhat)
			val vSecond = vOperandTypes[1]!!.prim
			val vIsShift = vOp == KBinaryOp.SHL || vOp == KBinaryOp.SHR || vOp == KBinaryOp.USHR
			val vWork = if (vIsShift) vFirst else promote(vFirst, vSecond)
			val vLeft = convert(vOperands[0], vWork)
			val vRight = convert(vOperands[1], if (vIsShift) KPrim.INT else vWork)
			val vType = if (vOp == KBinaryOp.CMP) kIntType else KPrimType(vWork)
			return convert(KBinary(vOp, vLeft, vRight, vWork, vType), vResultType.prim)
		}
		return fail(inCall, vWhat)
	}

	/** `x in a..b` (also `until`, `..<`) on Int / Long / Char: two comparisons, no range object. */
	private fun lowerRangeContains(inRange: IrExpression, inValue: IrExpression): KExpr? {
		val vRange = inRange as? IrCall ?: return null
		val vKind = vRange.symbol.owner.name.asString()
		if (vKind !in setOf("rangeTo", "until", "rangeUntil") || vRange.arguments.size != 2) return null
		val vPieces = listOf(vRange.arguments[0]!!, vRange.arguments[1]!!, inValue)
		val vPrims = vPieces.map { (kType(it.type) as? KPrimType)?.prim ?: return null }
		val vCommon = if (vPrims.all { it == KPrim.CHAR }) KPrim.CHAR else promote(promote(vPrims[0], vPrims[1]), vPrims[2])
		if (vCommon.isFloating) return null
		val vOperands = lowerOperands(vPieces).map { spill(convert(it, vCommon)) }
		val vUpper = if (vKind == "rangeTo") KBinaryOp.LE else KBinaryOp.LT
		return and(
			KBinary(KBinaryOp.LE, vOperands[0], vOperands[2], vCommon, kBoolType),
			KBinary(vUpper, vOperands[2], vOperands[1], vCommon, kBoolType),
		)
	}

	private fun isArrayClassName(inParent: String): Boolean = inParent == "kotlin.Array" || (inParent.startsWith("kotlin.") && inParent.endsWith("Array"))

	/** `x!!`: fails on null, otherwise yields the value as non-null. */
	private fun lowerNotNull(inCall: IrCall, inArgument: IrExpression): KExpr {
		val vValue = spill(lowerPure(inArgument))
		val vFail = listOf(failure("throwNullPointer", "ktc2_fail_null"))
		return when (val vType = vValue.type) {
			is KOptType -> {
				mBlock += KIf(not(KOptHas(vValue)), vFail, emptyList())
				KOptValue(vValue)
			}
			is KRefType, KPtrType -> {
				val vIsNull = isNull(vValue)
				if (vIsNull !== kFalse) mBlock += KIf(vIsNull, vFail, emptyList())
				coerce(vValue, typeOf(inCall))
			}
			else -> vValue
		}
	}

	// ============
	//  Equality

	private fun lowerEquality(inCall: IrCall, inLeft: IrExpression, inRight: IrExpression, inIdentity: Boolean): KExpr {
		fun objectRead(inSide: IrExpression): KExpr? = (inSide as? IrGetField)?.takeIf { isUncertain(it.receiver) }?.let { erasedFieldRead(it, true) }
		val vOperands = if ((inLeft is IrGetField && isUncertain(inLeft.receiver)) || (inRight is IrGetField && isUncertain(inRight.receiver))) {
			val vFirst = objectRead(inLeft) ?: spill(lowerPure(inLeft))
			mutableListOf(vFirst, objectRead(inRight) ?: lowerPure(inRight))
		} else {
			lowerOperands(listOf(inLeft, inRight))
		}
		// `Unit` compared with something is the Unit object.
		val vLeft = vOperands[0].let { if (it === KUnitValue) coerce(it, KObjType) else it }
		val vRight = vOperands[1].let { if (it === KUnitValue) coerce(it, KObjType) else it }
		val vLeftType = vLeft.type
		val vRightType = vRight.type
		return when {
			// Two numbers, one of them read through a getter (a field that is a number in every instantiation).
			vLeftType is KPrimType && vRightType is KPrimType -> {
				val vCommon = if (vLeftType.prim == vRightType.prim) vLeftType.prim else promote(vLeftType.prim, vRightType.prim)
				val vFirst = convert(vLeft, vCommon)
				val vSecond = convert(vRight, vCommon)
				if (vCommon.isFloating) {
					KBinary(KBinaryOp.EQ, KBinary(KBinaryOp.CMP, vFirst, vSecond, vCommon, kIntType), KConst(0, kIntType), KPrim.INT, kBoolType)
				} else {
					KBinary(KBinaryOp.EQ, vFirst, vSecond, vCommon, kBoolType)
				}
			}
			// `===` between a number that may be null and an object: only two nulls are the same.
			inIdentity && vLeftType is KOptType && vRightType is KRefType && vRight !is KNull -> and(not(KOptHas(spill(vLeft))), isNull(spill(vRight)))
			inIdentity && vRightType is KOptType && vLeftType is KRefType && vLeft !is KNull -> and(not(KOptHas(spill(vRight))), isNull(spill(vLeft)))
			// A number that may be null compared with an object: as an object itself.
			vLeftType is KRefType && vRightType is KOptType && vLeft !is KNull -> objectEquals(inCall, inLeft.type, spill(vLeft), coerce(vRight, KObjType))
			vRightType is KRefType && vLeftType is KOptType && vRight !is KNull -> objectEquals(inCall, inRight.type, spill(vRight), coerce(vLeft, KObjType))
			// Of a type parameter that stands for Double here (`K?` and `K`): `equals`, as Kotlin has it for what it does not
			// know to be a floating number where it is written. Of a type `Double?`: as numbers (IEEE 754).
			vLeftType is KOptType || vRightType is KOptType ->
				optEquals(inCall, spill(vLeft), spill(vRight), inLeft.type.classifierOrNull is org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol || inRight.type.classifierOrNull is org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol)
			vLeft is KNull && vRight is KNull -> kTrue
			// A primitive compared with an Any: box it and compare as objects.
			// `===` between a primitive and an object: a boxed number is never that object.
			inIdentity && (vLeftType is KRefType) != (vRightType is KRefType) && vLeft !is KNull && vRight !is KNull -> kFalse
			vLeftType == KObjType && vRightType is KPrimType -> objectEquals(inCall, inLeft.type, spill(vLeft), coerce(vRight, KObjType))
			vRightType == KObjType && vLeftType is KPrimType -> objectEquals(inCall, inRight.type, spill(vRight), coerce(vLeft, KObjType))
			// Structs compare with their own equals; they have no identity and are never null.
			// Two values of a `@SimpleEnum` class are two numbers.
			vLeftType is KStructType && vLeftType == vRightType && mImporter.simpleEnumOf(vLeftType.struct) != null -> {
				val vField = vLeftType.struct.fields[0]
				KBinary(KBinaryOp.EQ, KFieldRead(vLeft, vField), KFieldRead(vRight, vField), KPrim.INT, kBoolType)
			}
			vLeftType is KStructType || vRightType is KStructType -> when {
				vLeft is KNull || vRight is KNull -> kFalse
				inIdentity -> fail(inCall, "'===' on a @Struct value, which has no identity")
				vLeftType is KStructType -> structEquals(inCall, inLeft.type, spill(vLeft), vRight)
				else -> structEquals(inCall, inRight.type, spill(vRight), vLeft)
			}
			// C addresses compare by value.
			vLeftType == KPtrType && vRight is KNull -> isNull(vLeft)
			vRightType == KPtrType && vLeft is KNull -> isNull(vRight)
			vLeftType == KPtrType && vRightType == KPtrType -> KRefEquals(vLeft, vRight)
			// A value of type Nothing is never there (`allEqualBy { error("…") }`): the comparison is never made.
			vLeftType == KNothingType || vRightType == KNothingType -> kFalse
			vLeftType !is KRefType || vRightType !is KRefType -> fail(inCall, "equality between '${inLeft.type.render()}' and '${inRight.type.render()}'")
			vRight is KNull -> isNull(vLeft)
			vLeft is KNull -> isNull(vRight)
			inIdentity -> KRefEquals(vLeft, vRight)
			vLeftType == KStringType && vRightType == KStringType ->
				KIntrinsic("ktc2_str_equals", listOf(vLeft, vRight), kBoolType)
			else -> objectEquals(inCall, inLeft.type, spill(vLeft), spill(vRight))
		}
	}

	private fun optEquals(inCall: IrCall, inLeft: KExpr, inRight: KExpr, inByEquals: Boolean = false): KExpr {
		fun valueOf(inValue: KExpr) = if (inValue.type is KOptType) KOptValue(inValue) else inValue
		fun has(inValue: KExpr) = if (inValue.type is KOptType) KOptHas(inValue) else kTrue
		if (inRight is KNull) return not(has(inLeft))
		if (inLeft is KNull) return not(has(inRight))
		val vPrim = primOf(valueOf(inLeft)) ?: return fail(inCall, "equality on this type")
		// `equals` of Double and Float: the total order of `compareTo` (NaN equals NaN, -0.0 is not 0.0).
		val vSame = if (inByEquals && vPrim.isFloating) {
			KBinary(KBinaryOp.EQ, KBinary(KBinaryOp.CMP, valueOf(inLeft), convert(valueOf(inRight), vPrim), vPrim, kIntType), KConst(0, kIntType), KPrim.INT, kBoolType)
		} else {
			KBinary(KBinaryOp.EQ, valueOf(inLeft), convert(valueOf(inRight), vPrim), vPrim, kBoolType)
		}
		return when {
			inLeft.type !is KOptType -> and(has(inRight), vSame)
			inRight.type !is KOptType -> and(has(inLeft), vSame)
			else -> KSelect(has(inLeft), and(has(inRight), vSame), not(has(inRight)), kBoolType)
		}
	}

	/** `a == b` on objects: identical, or both non-null and `a.equals(b)`. */
	private fun objectEquals(inCall: IrCall, inLeftType: IrType, inLeft: KExpr, inRight: KExpr): KExpr {
		val vEquals = anyMemberOf(inLeftType, "equals", 1) ?: return fail(inCall, "equals on '${inLeftType.render()}'")
		val vTarget = memberTarget(vEquals, false, closed(inLeftType), emptyList())?.first ?: return fail(inCall, "equals on '${inLeftType.render()}'")
		// A final class that does not override equals compares by identity.
		if (vTarget.isExternal && vTarget.dispatch == null && inLeft.type is KClassType) return KRefEquals(inLeft, inRight)
		val vResult = newTemp(kBoolType)
		val vBothSet = and(not(isNull(inLeft)), not(isNull(inRight)))
		val vCall = KCall(vTarget, listOf(coerce(inLeft, vTarget.params[0].type), coerce(inRight, vTarget.params[1].type)))
		mBlock += KLet(vResult, null)
		mBlock += KIf(
			KRefEquals(inLeft, inRight),
			listOf(KSet(vResult, kTrue)),
			listOf(KIf(vBothSet, listOf(KSet(vResult, vCall)), listOf(KSet(vResult, kFalse)))),
		)
		return KRead(vResult)
	}

	/** `inStruct == inOther` through the `equals` the struct's class declares. */
	private fun structEquals(inCall: IrCall, inStructType: IrType, inStruct: KExpr, inOther: KExpr): KExpr {
		// In generic code the type is a type parameter standing for the struct.
		val vEquals = anyMemberOf(closed(inStructType), "equals", 1)
		if (vEquals == null || isAnyClass(vEquals.parentAsClass)) {
			return fail(inCall, "'==' on the @Struct class '${inStructType.render()}', which declares no equals (a data class does)")
		}
		val vTarget = memberTarget(vEquals, false, closed(inStructType), emptyList())?.first ?: return fail(inCall, "equals on '${inStructType.render()}'")
		return KCall(vTarget, listOf(coerce(inStruct, vTarget.params[0].type), coerce(inOther, vTarget.params[1].type)))
	}

	/** The member of `kotlin.Any` named [inName] as seen by the class of [inType] (its override, if any). */
	private fun anyMemberOf(inType: IrType, inName: String, inRegularParams: Int): IrSimpleFunction? {
		fun find(inClass: IrClass) = inClass.declarations.filterIsInstance<IrSimpleFunction>().firstOrNull { vFunction ->
			vFunction.name.asString() == inName && vFunction.parameters.count { it.kind == IrParameterKind.Regular } == inRegularParams
		}
		val vClass = inType.makeNotNull().classOrNull?.owner
		return vClass?.takeIf { !it.isInterface }?.let { find(it) } ?: find(mImporter.mBuiltIns.anyClass.owner)
	}

	// ============
	//  Strings

	private fun concat(inParts: List<KPrintPart>): KExpr {
		// A concatenation of literals is itself a literal (a compile-time constant in Kotlin).
		if (inParts.all { it is KPrintText }) return KStringLit(inParts.joinToString("") { (it as KPrintText).text })
		val vTarget = newTemp(KStringType)
		mBlock += KConcat(vTarget, inParts)
		return KRead(vTarget)
	}

	/** A String for [inValue] (already stable), calling `toString()` on objects; "null" for null. */
	private fun toStringOf(inElement: IrElement, inValue: KExpr, inType: IrType): KExpr = when (inValue.type) {
		KStringType -> if (closed(inType).isMarkedNullable()) concat(listOf(KPrintValue(inValue))) else inValue
		is KPrimType, is KOptType -> concat(listOf(KPrintValue(inValue)))
		is KStructType -> {
			val vFunction = anyMemberOf(closed(inType), "toString", 0)
			val vCall = vFunction?.takeIf { !isAnyClass(it.parentAsClass) }?.let { callMember(inElement, it, listOf(inValue), closed(inType)) }
			// Without a toString of its own it is described as any object is.
			if (vCall is KCall) spill(vCall) else toStringOf(inElement, spill(coerce(inValue, KObjType)), mImporter.mBuiltIns.anyType)
		}
		is KClassType, KObjType -> {
			val vFunction = anyMemberOf(inType, "toString", 0)
			val vCall = vFunction?.let { callMember(inElement, it, listOf(inValue), closed(inType)) }
			if (vCall !is KCall) {
				fail(inElement, "toString on '${inType.render()}'")
			} else if (!closed(inType).isMarkedNullable()) {
				spill(vCall)
			} else {
				val vResult = newTemp(KStringType)
				mBlock += KLet(vResult, null)
				mBlock += KIf(isNull(inValue), listOf(KSet(vResult, KStringLit("null"))), listOf(KSet(vResult, vCall)))
				KRead(vResult)
			}
		}
		else -> fail(inElement, "toString on '${inType.render()}'")
	}

	/**
	 * The parts of a template or `print` argument, evaluated and converted in order: each value
	 * is turned into text before the next piece is evaluated, as Kotlin does.
	 */
	private fun lowerParts(inPieces: List<IrExpression>): List<KPrintPart> {
		val vParts = ArrayList<KPrintPart>()
		val vValues = ArrayList<KExpr>()
		val vSlots = ArrayList<Int>()
		val vObjects = ArrayList<Pair<Int, IrExpression>>()
		for (vPiece in inPieces) {
			if (vPiece is IrConst && vPiece.value is String) {
				vParts += KPrintText(vPiece.value as String)
				continue
			}
			val vMark = mBlock.size
			var vValue = lowerPure(vPiece)
			if (vValue === KUnitValue && vPiece.type.isUnit()) {
				// What a function without a result gives, in a template: its name.
				vParts += KPrintText("kotlin.Unit")
				continue
			}
			if (vValue === KUnitValue) {
				fail(vPiece, "printing a value of type '${vPiece.type.render()}'")
				continue
			}
			// An array says nothing of itself: it is described as any object is.
			if (vValue.type is KArrayType) vValue = spill(KCall(mImporter.runtime("ktc2_any_toString", listOf(KObjType), KStringType), listOf(coerce(vValue, KObjType))))
			if (vValue.type is KClassType || vValue.type == KObjType || vValue.type is KStructType) {
				vValue = spill(vValue)
				vObjects += vValues.size to vPiece
			}
			protect(vValues, vMark)
			vSlots += vParts.size
			vValues += vValue
			vParts += KPrintValue(vValue)
		}
		// As on the JVM, every part is evaluated before the first `toString` runs: a `toString` that
		// changes something does not change what the parts after it show.
		for ((vIndex, vPiece) in vObjects) {
			val vMark = mBlock.size
			val vText = toStringOf(vPiece, vValues[vIndex], vPiece.type)
			if (vText === KUnitValue) {
				fail(vPiece, "printing a value of type '${vPiece.type.render()}'")
				continue
			}
			protect(vValues, vMark)
			vValues[vIndex] = vText
		}
		vSlots.forEachIndexed { vIndex, vSlot -> vParts[vSlot] = KPrintValue(vValues[vIndex]) }
		return vParts
	}

	/** The pieces of a chain of `+` on strings and of a template, as one list: `a + b + "$c$d"` is built in one go. */
	private fun concatPieces(inExpression: IrExpression): List<IrExpression> {
		if (inExpression is IrStringConcatenation) return inExpression.arguments
		val vCall = inExpression as? IrCall ?: return listOf(inExpression)
		val vCallee = vCall.symbol.owner
		val vIsStringPlus = vCallee.name.asString() == "plus" && (vCallee.parent as? IrClass)?.kotlinFqName?.asString() == "kotlin.String"
		val vReceiver = vCall.arguments.getOrNull(0)
		val vOther = vCall.arguments.getOrNull(1)
		if (!vIsStringPlus || vCall.arguments.size != 2 || vReceiver == null || vOther == null) return listOf(inExpression)
		return concatPieces(vReceiver) + vOther
	}

	private fun lowerStringCall(inCall: IrCall, inName: String, inArguments: List<IrExpression>): KExpr {
		if (inName == "plus" && inArguments.size == 2) return concat(lowerParts(concatPieces(inArguments[0]) + inArguments[1]))
		val vOperands = lowerOperands(inArguments)
		return when {
			inName == "<get-length>" -> KIntrinsic("ktc2_str_length", vOperands, kIntType)
			inName == "get" -> KIntrinsic("ktc2_str_get", vOperands, KPrimType(KPrim.CHAR))
			inName == "compareTo" -> KIntrinsic("ktc2_str_compare", vOperands, kIntType)
			inName == "hashCode" -> KIntrinsic("ktc2_str_hashCode", vOperands, kIntType)
			inName == "toString" -> vOperands[0]
			// `subSequence` of a string is `substring`: the runtime function behind it.
			inName == "subSequence" -> mImporter.internalFunction("stringSubstring")?.let { mImporter.functionFor(it) }
				?.let { adapt(KCall(it, vOperands), inCall) } ?: fail(inCall, "call to 'kotlin.String.subSequence'")
			inName == "equals" && vOperands[1].type == KStringType -> KIntrinsic("ktc2_str_equals", vOperands, kBoolType)
			inName == "equals" -> KCall(
				mImporter.runtime("ktc2_any_equals", listOf(KObjType, KObjType), kBoolType),
				vOperands.map { coerce(it, KObjType) },
			)
			else -> fail(inCall, "call to 'kotlin.String.$inName'")
		}
	}
}
