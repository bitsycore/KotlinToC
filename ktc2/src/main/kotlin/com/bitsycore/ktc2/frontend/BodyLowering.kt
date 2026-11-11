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
	is KRefCast -> isNull(inValue.operand).let { if (it === kFalse) it else KRefEquals(inValue, KNull(inValue.type)) }
	is KNull -> kTrue
	else -> KRefEquals(inValue, KNull(inValue.type))
}

/**
 * Lowers one function body from K2 IR to KIR statements. Every side effect becomes a statement
 * in evaluation order; what is left in expressions is pure.
 */
internal class BodyBuilder(
	private val mImporter: KirImporter,
	private val mFunction: KFunction,
	private val mIrFunction: IrFunction?,
	private val mFile: IrFile,
	/** What the type parameters in scope stand for in this instantiation. */
	private var mSubst: Subst,
) {
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
	private fun buildEnumSynthetic(inFunction: IrFunction, inKind: IrSyntheticBodyKind) {
		val vEntries = mImporter.enumEntriesOf(inFunction.parentAsClass)
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
			IrSyntheticBodyKind.ENUM_VALUEOF -> {
				val vName = KRead(mFunction.params[0])
				for (vEntry in vEntries) {
					val vEntryName = KFieldRead(coerce(KStaticRead(vEntry), vBaseType), mImporter.enumNameField)
					val vMatches = KIntrinsic("ktc2_str_equals", listOf(vEntryName, vName), kBoolType)
					mBlock += KIf(vMatches, listOf(KReturn(coerce(KStaticRead(vEntry), mFunction.returnType))), emptyList())
				}
				mBlock += KEval(KIntrinsic("ktc2_fail_enum_value", listOf(vName), KUnitType, isPure = false))
			}
			else -> fail(inFunction, "enum '${inFunction.name}'")
		}
	}

	fun build() {
		val vIr = mIrFunction!!
		mFunction.params.forEach { mUsedNames[it.cName] = 1 }
		val vOffset = mFunction.params.size - vIr.parameters.size
		vIr.parameters.forEachIndexed { vIndex, vParam -> mLocals[vParam] = mFunction.params[vIndex + vOffset] }
		when {
			vIr is IrConstructor && mFunction.returnType is KStructType -> buildStructConstructor(vIr)
			vIr is IrConstructor -> {
				vIr.parentAsClass.thisReceiver?.let { mLocals[it] = mFunction.params[0] }
				(vIr.body as? IrBlockBody)?.statements?.forEach { lowerStatement(it) }
			}
			vIr.origin == IrDeclarationOrigin.DEFAULT_PROPERTY_ACCESSOR -> buildDefaultAccessor(vIr as IrSimpleFunction)
			else -> when (val vBody = vIr.body) {
				is IrBlockBody -> vBody.statements.forEach { lowerStatement(it) }
				is IrExpressionBody -> emitReturn(vBody.expression)
				is IrSyntheticBody -> buildEnumSynthetic(vIr, vBody.kind)
				else -> fail(vIr, "function without a body")
			}
		}
	}

	/** A `@Struct` constructor: each field takes its property initializer. */
	private fun buildStructConstructor(inConstructor: IrConstructor) {
		val vClass = inConstructor.parentAsClass
		val vStruct = (mFunction.returnType as KStructType).struct
		if (!inConstructor.isPrimary) fail(inConstructor, "secondary constructor in a @Struct class")
		if (vClass.declarations.any { it is IrAnonymousInitializer }) fail(inConstructor, "init block in a @Struct class")
		val vValues = ArrayList<KExpr>()
		for (vProperty in vClass.declarations.filterIsInstance<IrProperty>()) {
			val vBacking = vProperty.backingField ?: continue
			val vField = mImporter.fieldFor(vBacking, null) ?: continue
			val vInit = vBacking.initializer?.expression
			vValues += if (vInit == null) fail(vProperty, "property without initializer") else spill(coerce(lowerPure(vInit), vField.type))
		}
		if (vValues.size == vStruct.fields.size) mBlock += KReturn(KStructNew(vStruct, vValues))
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
			mBlock += KReturn(KFieldRead(vSelf, vField))
		} else {
			mBlock += KFieldSet(vSelf, vField, KRead(mFunction.params[1]))
		}
	}

	// ==================
	// MARK: Helpers
	// ==================

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

	private fun newTemp(inType: KType): KLocal = KLocal(freshName("t"), inType).also { mTemps += it }

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
		if (vFrom == inTo || inValue === KUnitValue) return inValue
		return when {
			inTo is KRefType && vFrom is KRefType -> if (inValue is KNull) KNull(inTo) else KRefCast(inValue, inTo)
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
				val vLocal = KLocal(freshName(inStatement.name.asString()), vType)
				mLocals[inStatement] = vLocal
				if (vType == KUnitType || vType == KNothingType) {
					vInit?.let { discard(it) }
				} else {
					mBlock += KLet(vLocal, vInit)
				}
			}
			is IrExpression -> discard(lowerRhs(inStatement))
			else -> fail(inStatement, "local declaration")
		}
	}

	/** Keeps the side effect of an unused value, drops pure expressions. */
	private fun discard(inValue: KExpr) {
		if (inValue.isImpure()) mBlock += KEval(inValue)
	}

	private fun emitReturn(inValue: IrExpression?) {
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
		is IrGetValue -> mLocals[inExpression.symbol.owner]?.let { if (it.type == KUnitType) KUnitValue else KRead(it) }
			?: fail(inExpression, "reference to '${inExpression.symbol.owner.name}'")
		is IrSetValue -> {
			val vLocal = mLocals[inExpression.symbol.owner]
			if (vLocal == null) {
				fail(inExpression, "assignment to '${inExpression.symbol.owner.name}'")
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
			if (vInline == null) {
				emitReturn(inExpression.value.takeUnless { isUnitObject(it) })
			} else {
				val vValue = lowerRhs(inExpression.value)
				if (vInline.result != null && vValue !== KUnitValue) mBlock += KSet(vInline.result, coerce(vValue, vInline.result.type)) else discard(vValue)
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
				inExpression.body?.let { discard(lowerRhs(it)) }
			}
			mBlock += KLoop(vId, vBody, emptyList())
			KUnitValue
		}
		is IrDoWhileLoop -> {
			val vId = loopId(inExpression)
			val vBody = inBlock { inExpression.body?.let { discard(lowerRhs(it)) } }
			val vLatch = inBlock {
				val vCondition = lowerPure(inExpression.condition)
				mBlock += KIf(not(vCondition), listOf(KBreak(vId)), emptyList())
			}
			mBlock += KLoop(vId, vBody, vLatch)
			KUnitValue
		}
		is IrBreak -> {
			mBlock += KBreak(loopId(inExpression.loop))
			KUnitValue
		}
		is IrContinue -> {
			mBlock += KContinue(loopId(inExpression.loop))
			KUnitValue
		}
		is IrTypeOperatorCall -> lowerTypeOperator(inExpression)
		is IrGetObjectValue -> lowerObject(inExpression)
		is IrGetEnumValue -> mImporter.enumEntryFor(inExpression.symbol.owner)?.let { coerce(KStaticRead(it), typeOf(inExpression)) }
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

	private fun lowerObject(inExpression: IrGetObjectValue): KExpr {
		if (isUnitObject(inExpression)) return KUnitValue
		val vInstance = mImporter.objectFor(inExpression.symbol.owner) ?: return fail(inExpression, "object '${inExpression.symbol.owner.name}'")
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
		if (vClass.isInterface) return KInterfaceTarget(mImporter.interfaceFor(vClass))
		return mImporter.classFor(vClass, mImporter.argumentsOf(closed(vBase)))?.let { KClassTarget(it) }
	}

	/** `inValue is inType`, including the nullable and `Any` cases. */
	private fun instanceTest(inElement: IrElement, inValue: KExpr, inType: IrType): KExpr {
		if (inValue.type !is KRefType) return fail(inElement, "type test on a primitive")
		// A type parameter standing for a reference type is `Any?` here: the test always holds.
		val vType = closed(inType)
		val vIsNull = isNull(inValue)
		if (vType.makeNotNull().isAny()) return if (vType.isMarkedNullable()) kTrue else not(vIsNull)
		val vTest = KInstanceOf(inValue, typeTargetOf(vType) ?: return fail(inElement, "type test against '${inType.render()}'"))
		return if (vType.isMarkedNullable()) KSelect(vIsNull, kTrue, vTest, kBoolType) else vTest
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
		IrTypeOperator.CAST -> {
			val vValue = spill(lowerPure(inExpression.argument))
			val vTo = typeOf(inExpression)
			if (vValue.type == vTo && vTo !is KRefType) {
				vValue
			} else if (vValue.type == KObjType && (vTo is KPrimType || vTo is KOptType)) {
				// Unboxing checks the kind of box itself.
				coerce(vValue, vTo)
			} else if (vValue.type !is KRefType || vTo !is KRefType) {
				fail(inExpression, "cast to '${inExpression.typeOperand.render()}'")
			} else {
				val vTest = instanceTest(inExpression, vValue, inExpression.typeOperand)
				if (vTest !== kTrue) {
					mBlock += KIf(not(vTest), listOf(KEval(KIntrinsic("ktc2_fail_cast", emptyList(), KUnitType, isPure = false))), emptyList())
				}
				coerce(vValue, vTo)
			}
		}
		IrTypeOperator.SAFE_CAST -> {
			val vValue = spill(lowerPure(inExpression.argument))
			val vTo = typeOf(inExpression)
			if (vValue.type !is KRefType || vTo !is KRefType) {
				fail(inExpression, "safe cast to '${inExpression.typeOperand.render()}'")
			} else {
				KSelect(instanceTest(inExpression, vValue, inExpression.typeOperand), coerce(vValue, vTo), KNull(vTo), vTo)
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
		val vThen = inBlock { lowerBranchResult(vBranch.result, inResult) }
		val vElse = inBlock { lowerBranches(inBranches, inIndex + 1, inResult) }
		mBlock += KIf(vCondition, vThen, vElse)
	}

	private fun lowerBranchResult(inResult: IrExpression, inTarget: KLocal?) {
		val vValue = lowerRhs(inResult)
		if (inTarget != null && !inResult.type.isNothing() && vValue !== KUnitValue) {
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
	//  Inlined lambdas

	private fun containsBreak(inStatements: List<KStmt>, inId: Int): Boolean = inStatements.any { vStatement ->
		when (vStatement) {
			is KBreak -> vStatement.loopId == inId
			is KIf -> containsBreak(vStatement.thenBlock, inId) || containsBreak(vStatement.elseBlock, inId)
			is KLoop -> containsBreak(vStatement.body, inId) || containsBreak(vStatement.latch, inId)
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
			val vType = kType(vParam.type) ?: return fail(vParam, "lambda parameter of type '${vParam.type.render()}'")
			val vLocal = KLocal(freshName(vParam.name.asString().trim('<', '>')), vType)
			mBlock += KLet(vLocal, coerce(inArguments[vIndex], vType))
			mLocals[vParam] = vLocal
		}
		val vResult = if (inResultType == KUnitType || inResultType == KNothingType) null else newTemp(inResultType)
		vResult?.let { mBlock += KLet(it, null) }
		val vId = mNextLoopId++
		mInlineTargets[vFunction.symbol] = InlineTarget(vResult, vId)
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

	/** `IntArray(n)`, `IntArray(n) { ... }`, `Array(n) { ... }`. */
	private fun lowerArrayConstructor(inCall: IrConstructorCall, inType: KArrayType): KExpr {
		val vSize = inCall.arguments.getOrNull(0) ?: return fail(inCall, "array constructor")
		val vInit = inCall.arguments.getOrNull(1)
		val vLength = spill(lowerPure(vSize))
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
		val vType = typeOf(inVararg) as? KArrayType ?: return fail(inVararg, "vararg of type '${inVararg.type.render()}'")
		if (inVararg.elements.any { it !is IrExpression }) return fail(inVararg, "spread (*) in a vararg")
		val vValues = lowerOperands(inVararg.elements.map { it as IrExpression })
		val vArray = newTemp(vType)
		mBlock += KLet(vArray, KArrayNew(KConst(vValues.size, kIntType), vType))
		vValues.forEachIndexed { vIndex, vValue ->
			mBlock += KIndexSet(KRead(vArray), KConst(vIndex, kIntType), coerce(vValue, vType.element))
		}
		return KRead(vArray)
	}

	/** Members of `Array` and the primitive arrays. */
	private fun lowerArrayCall(inCall: IrCall, inName: String, inArguments: List<IrExpression>, inType: KArrayType): KExpr = when (inName) {
		"<get-size>" -> arrayLength(lowerPure(inArguments[0]))
		"get" -> {
			val vOperands = lowerOperands(inArguments)
			KIndexRead(vOperands[0], vOperands[1], inType.element)
		}
		"set" -> {
			val vOperands = lowerOperands(inArguments, inLastMayBeImpure = true)
			mBlock += KIndexSet(vOperands[0], vOperands[1], coerce(vOperands[2], inType.element))
			KUnitValue
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
			inBody.forEach { lowerStatement(it) }
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
			return fail(inBlock, "'for' over anything but an array or an Int/Long range (until, .., ..<, downTo, step)")
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
			for (vIndex in 1 until vInner.statements.size) lowerStatement(vInner.statements[vIndex])
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

	/** The receiver of a field access, seen as the class that declares the field. */
	private fun fieldTarget(inReceiver: KExpr, inField: KField): KExpr =
		inField.owner?.let { coerce(inReceiver, KClassType(it)) } ?: inReceiver

	private fun lowerConstructorCall(inCall: IrConstructorCall): KExpr {
		val vConstructor = inCall.symbol.owner
		val vOwner = vConstructor.parentAsClass
		(typeOf(inCall) as? KArrayType)?.takeIf { vOwner.fileOrNull == null }?.let { return lowerArrayConstructor(inCall, it) }
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
		mBlock += KLet(vObject, KAlloc(vClass))
		mBlock += KEval(vCall)
		return KRead(vObject)
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
		if (isAnyClass(vConstructor.parentAsClass)) return KUnitValue
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
					mBlock += KFieldSet(fieldTarget(selfRead(), vField), vField, coerce(lowerRhs(vInit), vField.type))
				}
				is IrAnonymousInitializer -> vDeclaration.body.statements.forEach { lowerStatement(it) }
				else -> {}
			}
		}
		return KUnitValue
	}

	private fun lowerGetField(inExpression: IrGetField): KExpr {
		mImporter.globalFor(inExpression.symbol.owner)?.let { return KGlobalRead(it) }
		val vReceiver = inExpression.receiver ?: return fail(inExpression, "static field access")
		val vField = mImporter.fieldFor(inExpression.symbol.owner, closed(vReceiver.type)) ?: return fail(inExpression, "field access")
		return adapt(KFieldRead(fieldTarget(lowerPure(vReceiver), vField), vField), inExpression)
	}

	private fun lowerSetField(inExpression: IrSetField): KExpr {
		val vGlobal = mImporter.globalFor(inExpression.symbol.owner)
		if (vGlobal != null) {
			mBlock += KGlobalSet(vGlobal, coerce(lowerRhs(inExpression.value), vGlobal.type))
			return KUnitValue
		}
		val vReceiver = inExpression.receiver ?: return fail(inExpression, "static field assignment")
		val vField = mImporter.fieldFor(inExpression.symbol.owner, closed(vReceiver.type)) ?: return fail(inExpression, "field assignment")
		val vOperands = lowerOperands(listOf(vReceiver, inExpression.value), inLastMayBeImpure = true)
		mBlock += KFieldSet(fieldTarget(vOperands[0], vField), vField, coerce(vOperands[1], vField.type))
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
		if (vArguments.any { it === KUnitValue }) return fail(inCall, "Unit passed as an argument")
		return KCall(inTarget, listOfNotNull(inPrefix) + vArguments.mapIndexed { vIndex, vValue -> coerce(vValue, inTarget.params[vIndex + vOffset].type) })
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
		if (vGiven.any { it === KUnitValue }) return fail(inCall, "Unit passed as an argument")
		val vSaved = inCallee.parameters.associateWith { mLocals[it] }
		val vBound = arrayOfNulls<KLocal>(inArguments.size)
		var vNext = 0
		inArguments.forEachIndexed { vIndex, vArgument ->
			if (vArgument == null) return@forEachIndexed
			val vTemp = newTemp(inTarget.params[vIndex + vOffset].type)
			mBlock += KLet(vTemp, coerce(vGiven[vNext++], vTemp.type))
			vBound[vIndex] = vTemp
		}
		var vFailed = false
		inArguments.forEachIndexed { vIndex, vArgument ->
			if (vArgument == null) {
				val vDefault = defaultOf(inCallee, vIndex)
				if (vDefault == null) {
					vFailed = true
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
			mLocals[inCallee.parameters[vIndex]] = vBound[vIndex]!!
		}
		for ((vParam, vLocal) in vSaved) {
			if (vLocal == null) mLocals.remove(vParam) else mLocals[vParam] = vLocal
		}
		if (vFailed) return fail(inCall, "call with a missing argument")
		return KCall(inTarget, listOfNotNull(inPrefix) + vBound.map { KRead(it!!) })
	}

	/**
	 * The function a member call lands on — a dispatcher when the member can be overridden —
	 * with the substitution its declaration is read under. [inReceiver] is the closed type of
	 * the receiver, which picks the instantiation of a generic class.
	 */
	private fun memberTarget(inFunction: IrSimpleFunction, inIsSuper: Boolean, inReceiver: IrType?, inOwnArguments: List<IrType?>): Pair<KFunction, Subst>? {
		if (!inIsSuper && mImporter.isVirtual(inFunction)) {
			val vSubst = mImporter.substFor(inFunction, mImporter.superArguments(inReceiver, inFunction.parentAsClass), inOwnArguments)
			return mImporter.dispatcherFor(inFunction, vSubst)?.let { it to vSubst }
		}
		val (vReal, vSubst) = mImporter.resolveMember(inFunction, inReceiver, inOwnArguments)
		return mImporter.functionFor(vReal, vSubst)?.let { it to vSubst }
	}

	/** Calls member [inFunction] on already-lowered arguments (receiver first, of closed type [inReceiver]). */
	private fun callMember(inElement: IrElement, inFunction: IrSimpleFunction, inArguments: List<KExpr>, inReceiver: IrType?): KExpr {
		val vTarget = memberTarget(inFunction, false, inReceiver, emptyList())?.first ?: return fail(inElement, "call to '${inFunction.kotlinFqName}'")
		return KCall(vTarget, inArguments.mapIndexed { vIndex, vValue -> coerce(vValue, vTarget.params[vIndex].type) })
	}

	private fun lowerCall(inCall: IrCall): KExpr {
		val vCallee = inCall.symbol.owner
		val vOwner = vCallee.parent as? IrClass
		if (vCallee.fileOrNull == null && vOwner != null && vOwner.isInterface) {
			// A library interface (Comparable…): primitives and strings have direct answers,
			// everything else is an ordinary interface call.
			val vReceiverType = inCall.arguments.firstOrNull()?.let { kType(it.type) }
			if (vReceiverType !is KRefType || vReceiverType == KStringType) return lowerLibraryCall(inCall)
		} else if (vCallee.fileOrNull == null && !(vOwner != null && isAnyClass(vOwner))) {
			return lowerLibraryCall(inCall)
		}

		val vArguments = inCall.arguments
		val vOwnArguments = inCall.typeArguments.map { vType -> vType?.let { closed(it) } }
		val vProperty = vCallee.correspondingPropertySymbol?.owner
		if (vOwner == null) {
			mImporter.globalGetter(vCallee)?.let { return KGlobalRead(it) }
			mImporter.globalSetter(vCallee)?.let { vGlobal ->
				mBlock += KGlobalSet(vGlobal, coerce(lowerRhs(vArguments.last()!!), vGlobal.type))
				return KUnitValue
			}
			val vSubst = mImporter.substFor(vCallee, emptyList(), vOwnArguments)
			val vTarget = mImporter.functionFor(vCallee, vSubst) ?: return fail(inCall, "call to '${vCallee.kotlinFqName}'")
			return adapt(callFunction(inCall, vTarget, vCallee, vArguments, null, vSubst), inCall)
		}

		val vReceiver = vArguments.firstOrNull()?.let { closed(it.type) }
		val vIsSuper = inCall.superQualifierSymbol != null
		// A default accessor that cannot be overridden is a plain field access.
		if (vProperty != null && (vIsSuper || !mImporter.isVirtual(vCallee))) {
			val vReal = if (vCallee.isFakeOverride) vCallee.resolveFakeOverride() ?: vCallee else vCallee
			val vRealProperty = vReal.correspondingPropertySymbol?.owner
			val vField = vRealProperty?.backingField?.let { mImporter.fieldFor(it, vReceiver) }
			if (vField != null && vReal.origin == IrDeclarationOrigin.DEFAULT_PROPERTY_ACCESSOR) {
				if (vReal === vRealProperty.getter) {
					return adapt(KFieldRead(fieldTarget(lowerPure(vArguments[0]!!), vField), vField), inCall)
				}
				val vOperands = lowerOperands(listOf(vArguments[0]!!, vArguments[1]!!), inLastMayBeImpure = true)
				mBlock += KFieldSet(fieldTarget(vOperands[0], vField), vField, coerce(vOperands[1], vField.type))
				return KUnitValue
			}
		}
		val (vTarget, vSubst) = memberTarget(vCallee, vIsSuper, vReceiver, vOwnArguments) ?: return fail(inCall, "call to '${vCallee.kotlinFqName}'")
		return adapt(callFunction(inCall, vTarget, vCallee, vArguments, null, vSubst), inCall)
	}

	// ==================
	// MARK: Library intrinsics
	// ==================

	private fun lowerLibraryCall(inCall: IrCall): KExpr {
		val vCallee = inCall.symbol.owner
		val vName = vCallee.name.asString()
		val vParent = vCallee.parent.kotlinFqName.asString()
		val vArguments = inCall.arguments
		val vWhat = "call to '$vParent.$vName'"

		if (vParent == "kotlin.io" && (vName == "println" || vName == "print")) {
			val vPieces = when (val vArgument = vArguments.firstOrNull()) {
				null -> emptyList()
				is IrStringConcatenation -> vArgument.arguments
				else -> listOf(vArgument)
			}
			mBlock += KPrint(lowerParts(vPieces), vName == "println")
			return KUnitValue
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
				if (vTypes.any { it !is KPrimType }) return lowerEquality(inCall, vIrArguments[0], vIrArguments[1], vName == "EQEQEQ")
			}
		}
		if (vParent == "kotlin.String") return lowerStringCall(inCall, vName, vIrArguments)
		if (vParent == "kotlin.Comparable" && vName == "compareTo" && vIrArguments.size == 2) {
			val vSides = lowerOperands(vIrArguments)
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
			return KArrayNew(lowerPure(vIrArguments[0]), vType)
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

		// K2 can leave a smart cast implicit (an Any known to be an Int passed where an Int is
		// expected), so an operand takes the parameter's type when its own is not primitive.
		val vOperandTypes = vIrArguments.mapIndexed { vIndex, vArgument ->
			kType(vArgument.type) as? KPrimType ?: kType(vCallee.parameters[vIndex].type) as? KPrimType
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
			return KBinary(vOp, convert(vOperands[0], vCommon), convert(vOperands[1], vCommon), vCommon, kBoolType)
		}
		if (kPrimByClass[vParent] != vFirst) return fail(inCall, vWhat)

		if (vOperands.size == 1) {
			kConversionByName[vName]?.let { return convert(vOperands[0], it) }
			if (vName == "hashCode") {
				return when (vFirst) {
					KPrim.BOOLEAN, KPrim.LONG, KPrim.ULONG, KPrim.FLOAT, KPrim.DOUBLE ->
						KIntrinsic("ktc2_hash_${vFirst.name.lowercase()}", listOf(vOperands[0]), kIntType)
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
			val vOp = kBinaryByName[vName] ?: return fail(inCall, vWhat)
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
		val vFail = listOf<KStmt>(KEval(KIntrinsic("ktc2_fail_null", emptyList(), KUnitType, isPure = false)))
		return when (val vType = vValue.type) {
			is KOptType -> {
				mBlock += KIf(not(KOptHas(vValue)), vFail, emptyList())
				KOptValue(vValue)
			}
			is KRefType -> {
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
		val vOperands = lowerOperands(listOf(inLeft, inRight))
		val vLeft = vOperands[0]
		val vRight = vOperands[1]
		val vLeftType = vLeft.type
		val vRightType = vRight.type
		return when {
			vLeftType is KOptType || vRightType is KOptType -> optEquals(inCall, spill(vLeft), spill(vRight))
			vLeft is KNull && vRight is KNull -> kTrue
			// A primitive compared with an Any: box it and compare as objects.
			vLeftType == KObjType && vRightType is KPrimType -> objectEquals(inCall, inLeft.type, spill(vLeft), coerce(vRight, KObjType))
			vRightType == KObjType && vLeftType is KPrimType -> objectEquals(inCall, inRight.type, spill(vRight), coerce(vLeft, KObjType))
			vLeftType !is KRefType || vRightType !is KRefType -> fail(inCall, "equality between '${inLeft.type.render()}' and '${inRight.type.render()}'")
			vRight is KNull -> isNull(vLeft)
			vLeft is KNull -> isNull(vRight)
			inIdentity -> KRefEquals(vLeft, vRight)
			vLeftType == KStringType && vRightType == KStringType ->
				KIntrinsic("ktc2_str_equals", listOf(vLeft, vRight), kBoolType)
			else -> objectEquals(inCall, inLeft.type, spill(vLeft), spill(vRight))
		}
	}

	private fun optEquals(inCall: IrCall, inLeft: KExpr, inRight: KExpr): KExpr {
		fun valueOf(inValue: KExpr) = if (inValue.type is KOptType) KOptValue(inValue) else inValue
		fun has(inValue: KExpr) = if (inValue.type is KOptType) KOptHas(inValue) else kTrue
		if (inRight is KNull) return not(has(inLeft))
		if (inLeft is KNull) return not(has(inRight))
		val vPrim = primOf(valueOf(inLeft)) ?: return fail(inCall, "equality on this type")
		val vSame = KBinary(KBinaryOp.EQ, valueOf(inLeft), convert(valueOf(inRight), vPrim), vPrim, kBoolType)
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
		for (vPiece in inPieces) {
			if (vPiece is IrConst && vPiece.value is String) {
				vParts += KPrintText(vPiece.value as String)
				continue
			}
			val vMark = mBlock.size
			var vValue = lowerPure(vPiece)
			if (vValue.type is KClassType || vValue.type == KObjType) vValue = toStringOf(vPiece, spill(vValue), vPiece.type)
			if (vValue === KUnitValue) {
				fail(vPiece, "printing a value of type '${vPiece.type.render()}'")
				continue
			}
			protect(vValues, vMark)
			vSlots += vParts.size
			vValues += vValue
			vParts += KPrintValue(vValue)
		}
		vSlots.forEachIndexed { vIndex, vSlot -> vParts[vSlot] = KPrintValue(vValues[vIndex]) }
		return vParts
	}

	private fun lowerStringCall(inCall: IrCall, inName: String, inArguments: List<IrExpression>): KExpr {
		if (inName == "plus" && inArguments.size == 2) return concat(lowerParts(inArguments))
		val vOperands = lowerOperands(inArguments)
		return when {
			inName == "<get-length>" -> KIntrinsic("ktc2_str_length", vOperands, kIntType)
			inName == "get" -> KIntrinsic("ktc2_str_get", vOperands, KPrimType(KPrim.CHAR))
			inName == "compareTo" -> KIntrinsic("ktc2_str_compare", vOperands, kIntType)
			inName == "hashCode" -> KIntrinsic("ktc2_str_hashCode", vOperands, kIntType)
			inName == "toString" -> vOperands[0]
			inName == "equals" && vOperands[1].type == KStringType -> KIntrinsic("ktc2_str_equals", vOperands, kBoolType)
			inName == "equals" -> KCall(
				mImporter.runtime("ktc2_any_equals", listOf(KObjType, KObjType), kBoolType),
				vOperands.map { coerce(it, KObjType) },
			)
			else -> fail(inCall, "call to 'kotlin.String.$inName'")
		}
	}
}
