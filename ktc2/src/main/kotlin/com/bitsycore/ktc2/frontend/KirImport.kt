@file:OptIn(UnsafeDuringIrConstructionAPI::class)

package com.bitsycore.ktc2.frontend

import com.bitsycore.ktc2.kir.*
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.isGetter
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.render
import java.io.File

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

private val kCKeywords = setOf(
	"auto", "break", "case", "char", "const", "continue", "default", "do", "double", "else", "enum", "extern",
	"float", "for", "goto", "if", "inline", "int", "long", "register", "restrict", "return", "short", "signed",
	"sizeof", "static", "struct", "switch", "typedef", "union", "unsigned", "void", "volatile", "while", "bool",
	"true", "false", "main", "NULL",
)

private val kBoolType = KPrimType(KPrim.BOOLEAN)
private val kIntType = KPrimType(KPrim.INT)

private val kInverse = mapOf(
	KBinaryOp.EQ to KBinaryOp.NE, KBinaryOp.NE to KBinaryOp.EQ, KBinaryOp.LT to KBinaryOp.GE,
	KBinaryOp.GE to KBinaryOp.LT, KBinaryOp.GT to KBinaryOp.LE, KBinaryOp.LE to KBinaryOp.GT,
)

/** The constant 1 of an integer type. */
private fun one(inPrim: KPrim): KConst =
	if (inPrim == KPrim.LONG || inPrim == KPrim.ULONG) KConst(1L, KPrimType(inPrim)) else KConst(1, KPrimType(inPrim))

/** Logical negation, folded into the comparison when that keeps NaN behavior intact. */
private fun not(inValue: KExpr): KExpr = when {
	inValue is KUnary && inValue.op == KUnaryOp.NOT -> inValue.operand
	inValue is KBinary && inValue.op in kInverse &&
		(!inValue.operandType.isFloating || inValue.op == KBinaryOp.EQ || inValue.op == KBinaryOp.NE) ->
		KBinary(kInverse.getValue(inValue.op), inValue.left, inValue.right, inValue.operandType, inValue.type)
	else -> KUnary(KUnaryOp.NOT, inValue, kBoolType)
}

/** Replaces every character of [inName] that C identifiers do not allow. */
private fun cSuffix(inName: String): String =
	inName.map { if (it.isLetterOrDigit() && it.code < 128 || it == '_') it else '_' }.joinToString("")

/** Makes [inName] a valid, non-reserved C identifier. */
private fun cIdentifier(inName: String): String {
	val vClean = cSuffix(inName)
	return if (vClean.isEmpty() || vClean[0].isDigit() || vClean in kCKeywords) "_$vClean" else vClean
}

/**
 * Translates the K2 module IR into KIR.
 *
 * Phase 1 scope: top-level functions and properties, `@Struct` classes over primitives,
 * control flow, and `print` / `println`. Anything else is recorded in [errors] with its source position.
 */
class KirImporter {

	val errors = linkedSetOf<String>()

	private val mFunctions = HashMap<IrFunction, KFunction>()
	private val mStructs = HashMap<IrClass, KStruct>()
	private val mFields = HashMap<IrField, KField>()
	private val mGlobals = HashMap<IrField, KGlobal>()
	private val mGlobalGetters = HashMap<IrFunction, KGlobal>()
	private val mGlobalSetters = HashMap<IrFunction, KGlobal>()
	private val mFileOf = HashMap<IrDeclaration, IrFile>()

	// ==================
	// MARK: Declarations
	// ==================

	fun import(inModule: IrModuleFragment): KModule {
		val vUnits = ArrayList<KUnit>()
		val vPending = ArrayList<Pair<IrFunction, IrFile>>()
		val vOverloads = HashMap<String, Int>()
		val vAllFunctions = ArrayList<IrFunction>()
		val vInitializers = LinkedHashMap<KUnit, MutableList<Pair<KGlobal, IrExpression>>>()
		val vFileOfUnit = HashMap<KUnit, IrFile>()

		for (vFile in inModule.files) {
			for (vDeclaration in vFile.declarations) {
				when (vDeclaration) {
					is IrSimpleFunction -> vAllFunctions += vDeclaration
					is IrClass -> vAllFunctions += vDeclaration.declarations.filterIsInstance<IrFunction>()
					else -> {}
				}
			}
		}
		for (vFunction in vAllFunctions) vOverloads.merge(vFunction.kotlinFqName.asString(), 1, Int::plus)

		// Structs first: function signatures refer to them.
		for (vFile in inModule.files) {
			for (vClass in vFile.declarations.filterIsInstance<IrClass>()) {
				mFileOf[vClass] = vFile
				val vUnit = KUnit(vFile.packageFqName.asString(), vClass.name.asString())
				vUnits += vUnit
				declareStruct(vClass, vUnit, vFile)
			}
		}
		for (vFile in inModule.files) {
			val vFacade = KUnit(vFile.packageFqName.asString(), facadeName(vFile))
			for (vDeclaration in vFile.declarations) {
				mFileOf[vDeclaration] = vFile
				when (vDeclaration) {
					is IrSimpleFunction -> {
						declareFunction(vDeclaration, vFacade, vFile, vOverloads)?.let { vPending += vDeclaration to vFile }
					}
					is IrClass -> {
						val vUnit = mStructs[vDeclaration]?.unit ?: continue
						for (vMember in vDeclaration.declarations) {
							if (vMember !is IrFunction || isSkippedMember(vMember)) continue
							declareFunction(vMember, vUnit, vFile, vOverloads)?.let { vPending += vMember to vFile }
						}
					}
					is IrProperty -> {
						val vInit = declareProperty(vDeclaration, vFacade, vFile, vOverloads, vPending)
						if (vInit != null) vInitializers.getOrPut(vFacade) { ArrayList() } += vInit
						vFileOfUnit[vFacade] = vFile
					}
					else -> unsupported(vFile, vDeclaration, "this kind of top-level declaration")
				}
			}
			if (vFacade.functions.isNotEmpty() || vFacade.globals.isNotEmpty()) vUnits += vFacade
		}

		for ((vUnit, vInits) in vInitializers) {
			val vPrefix = if (vUnit.pkg.isEmpty()) "kt_" else vUnit.pkg.replace('.', '_') + "_"
			val vFunction = KFunction("${vPrefix}${vUnit.name}_init", vUnit, emptyList(), KUnitType)
			vUnit.initializer = vFunction
			FunctionBuilder(vFunction, null, vFileOfUnit.getValue(vUnit)).buildInitializer(vInits)
		}

		for ((vFunction, vFile) in vPending) {
			FunctionBuilder(mFunctions.getValue(vFunction), vFunction, vFile).build()
		}

		val vEntry = vAllFunctions.firstOrNull { it.name.asString() == "main" && it.parameters.isEmpty() && it.parent is IrFile }
		return KModule(vUnits, vEntry?.let { mFunctions[it] })
	}

	/** JVM-style facade class name of a file: `Utils.kt` → `UtilsKt`. */
	private fun facadeName(inFile: IrFile): String {
		val vBase = File(inFile.fileEntry.name).nameWithoutExtension
		return cIdentifier(vBase.replaceFirstChar { it.uppercase() }) + "Kt"
	}

	/** Members K2 synthesizes that Phase 1 has no use for (inherited `Any` fakes). */
	private fun isSkippedMember(inMember: IrFunction): Boolean =
		inMember is IrSimpleFunction && inMember.isFakeOverride

	private fun declareStruct(inClass: IrClass, inUnit: KUnit, inFile: IrFile) {
		if (inClass.annotations.none { it.type.classFqName?.asString() == "ktc2.Struct" }) {
			unsupported(inFile, inClass, "class '${inClass.name}' (only @Struct classes are supported so far)")
			return
		}
		val vPrefix = if (inUnit.pkg.isEmpty()) "kt_" else inUnit.pkg.replace('.', '_') + "_"
		val vStruct = KStruct(vPrefix + cSuffix(inClass.name.asString()), inUnit)
		inUnit.struct = vStruct
		mStructs[inClass] = vStruct
		for (vProperty in inClass.declarations.filterIsInstance<IrProperty>()) {
			val vBacking = vProperty.backingField ?: continue
			if (vProperty.isVar) unsupported(inFile, vProperty, "'var' property in a @Struct class")
			val vType = kTypeOf(vBacking.type) ?: run {
				unsupported(inFile, vProperty, "property type '${vBacking.type.render()}'")
				continue
			}
			if (vType is KStructType && vType.struct === vStruct) {
				unsupported(inFile, vProperty, "a @Struct containing itself")
				continue
			}
			val vField = KField(cIdentifier(vProperty.name.asString()), vType)
			vStruct.fields += vField
			mFields[vBacking] = vField
		}
	}

	/**
	 * Declares a top-level property: a global for its backing field, direct access for default
	 * accessors, functions for custom ones.
	 *
	 * @return the global and its initializer when that initializer has to run at startup
	 */
	private fun declareProperty(
		inProperty: IrProperty,
		inUnit: KUnit,
		inFile: IrFile,
		inOverloads: Map<String, Int>,
		inPending: MutableList<Pair<IrFunction, IrFile>>,
	): Pair<KGlobal, IrExpression>? {
		if (inProperty.isDelegated || inProperty.isLateinit) {
			unsupported(inFile, inProperty, "delegated or lateinit property")
			return null
		}
		var vGlobal: KGlobal? = null
		var vStartup: Pair<KGlobal, IrExpression>? = null
		inProperty.backingField?.let { vBacking ->
			val vType = kTypeOf(vBacking.type)
			if (vType == null || vType == KUnitType || vType == KNothingType) {
				unsupported(inFile, inProperty, "property type '${vBacking.type.render()}'")
				return null
			}
			val vPrefix = if (inUnit.pkg.isEmpty()) "kt_" else inUnit.pkg.replace('.', '_') + "_"
			val vNew = KGlobal(vPrefix + cSuffix(inProperty.name.asString()), vType, inUnit)
			inUnit.globals += vNew
			mGlobals[vBacking] = vNew
			vGlobal = vNew
			val vInit = vBacking.initializer?.expression
			val vValue = (vInit as? IrConst)?.value
			if (vInit is IrConst && vType is KPrimType && vValue != null && vValue !is String) {
				vNew.constInit = KConst(vValue, vType)
			} else if (vInit != null) {
				vStartup = vNew to vInit
			}
		}
		for (vAccessor in listOfNotNull(inProperty.getter, inProperty.setter)) {
			val vTarget = vGlobal
			if (vTarget != null && vAccessor.origin == IrDeclarationOrigin.DEFAULT_PROPERTY_ACCESSOR) {
				if (vAccessor === inProperty.getter) mGlobalGetters[vAccessor] = vTarget else mGlobalSetters[vAccessor] = vTarget
			} else {
				declareFunction(vAccessor, inUnit, inFile, inOverloads)?.let { inPending += vAccessor to inFile }
			}
		}
		return vStartup
	}

	private fun declareFunction(inFunction: IrFunction, inUnit: KUnit, inFile: IrFile, inOverloads: Map<String, Int>): KFunction? {
		var vOk = true
		if (inFunction.typeParameters.isNotEmpty() && inFunction !is IrConstructor) {
			unsupported(inFile, inFunction, "generic function")
			vOk = false
		}
		val vNames = HashSet<String>()
		val vParams = inFunction.parameters.map { vParam ->
			val vType = kTypeOf(vParam.type) ?: run {
				unsupported(inFile, vParam, "parameter type '${vParam.type.render()}'")
				vOk = false
				KUnitType
			}
			var vName = if (vParam.kind == IrParameterKind.Regular) cIdentifier(vParam.name.asString()) else "self"
			while (!vNames.add(vName)) vName += "_"
			KLocal(vName, vType)
		}
		val vReturn = if (inFunction is IrConstructor) {
			mStructs[inFunction.parent]?.let { KStructType(it) }
		} else {
			kTypeOf(inFunction.returnType)
		}
		if (vReturn == null) {
			unsupported(inFile, inFunction, "return type '${inFunction.returnType.render()}'")
			return null
		}
		if (!vOk) return null

		val vOwner = (inFunction.parent as? IrClass)?.let { mStructs[it]?.cName }
		val vPrefix = vOwner?.plus("_") ?: if (inUnit.pkg.isEmpty()) "kt_" else inUnit.pkg.replace('.', '_') + "_"
		var vName = vPrefix + if (inFunction is IrConstructor) "new" else cSuffix(inFunction.name.asString())
		if ((inOverloads[inFunction.kotlinFqName.asString()] ?: 0) > 1) {
			vName += "__" + inFunction.parameters.joinToString("_") { cSuffix(it.type.render().substringAfterLast('.')) }
		}
		return KFunction(vName, inUnit, vParams, vReturn).also {
			inUnit.functions += it
			mFunctions[inFunction] = it
		}
	}

	private fun kTypeOf(inType: IrType): KType? = when {
		inType.isBoolean() -> kBoolType
		inType.isByte() -> KPrimType(KPrim.BYTE)
		inType.isShort() -> KPrimType(KPrim.SHORT)
		inType.isInt() -> kIntType
		inType.isLong() -> KPrimType(KPrim.LONG)
		inType.isChar() -> KPrimType(KPrim.CHAR)
		inType.isFloat() -> KPrimType(KPrim.FLOAT)
		inType.isDouble() -> KPrimType(KPrim.DOUBLE)
		inType.isUByte() -> KPrimType(KPrim.UBYTE)
		inType.isUShort() -> KPrimType(KPrim.USHORT)
		inType.isUInt() -> KPrimType(KPrim.UINT)
		inType.isULong() -> KPrimType(KPrim.ULONG)
		inType.isUnit() -> KUnitType
		inType.isNothing() -> KNothingType
		inType.isMarkedNullable() -> null
		else -> inType.classOrNull?.owner?.let { mStructs[it] }?.let { KStructType(it) }
	}

	private fun unsupported(inFile: IrFile, inElement: IrElement, inWhat: String) {
		val vLine = if (inElement.startOffset >= 0) inFile.fileEntry.getLineNumber(inElement.startOffset) + 1 else 0
		errors += "${File(inFile.fileEntry.name).name}:$vLine: not supported yet: $inWhat"
	}

	// ==================
	// MARK: Function bodies
	// ==================

	private inner class FunctionBuilder(
		private val mFunction: KFunction,
		private val mIrFunction: IrFunction?,
		private val mFile: IrFile,
	) {
		private val mLocals = HashMap<IrValueDeclaration, KLocal>()
		private val mUsedNames = HashMap<String, Int>()
		private val mLoops = HashMap<IrLoop, Int>()
		private val mTemps = HashSet<KLocal>()
		private val mNotSelectable = HashSet<IrWhen>()
		private var mBlock: MutableList<KStmt> = mFunction.body

		/** Body of a unit initializer: assigns each global its startup value, in declaration order. */
		fun buildInitializer(inValues: List<Pair<KGlobal, IrExpression>>) {
			for ((vGlobal, vValue) in inValues) mBlock += KGlobalSet(vGlobal, lowerRhs(vValue))
		}

		fun build() {
			val mIrFunction = mIrFunction!!
			mIrFunction.parameters.forEachIndexed { vIndex, vParam ->
				val vLocal = mFunction.params[vIndex]
				mLocals[vParam] = vLocal
				mUsedNames[vLocal.cName] = 1
			}
			if (mIrFunction is IrConstructor) {
				buildConstructor(mIrFunction)
				return
			}
			when (val vBody = mIrFunction.body) {
				is IrBlockBody -> vBody.statements.forEach { lowerStatement(it) }
				is IrExpressionBody -> emitReturn(vBody.expression)
				else -> fail(mIrFunction, "function without a body")
			}
		}

		/** A `@Struct` constructor: each field takes its property initializer. */
		private fun buildConstructor(inConstructor: IrConstructor) {
			val vClass = inConstructor.parent as IrClass
			val vStruct = mStructs.getValue(vClass)
			if (!inConstructor.isPrimary) fail(inConstructor, "secondary constructor")
			if (vClass.declarations.any { it is IrAnonymousInitializer }) fail(inConstructor, "init block in a @Struct class")
			val vValues = ArrayList<KExpr>()
			for (vProperty in vClass.declarations.filterIsInstance<IrProperty>()) {
				val vBacking = vProperty.backingField ?: continue
				if (mFields[vBacking] == null) continue
				val vInit = vBacking.initializer?.expression
				vValues += if (vInit == null) fail(vProperty, "property without initializer") else spill(lowerPure(vInit))
			}
			if (vValues.size == vStruct.fields.size) mBlock += KReturn(KStructNew(vStruct, vValues))
		}

		// ============
		//  Helpers

		private fun fail(inElement: IrElement, inWhat: String): KExpr {
			unsupported(mFile, inElement, inWhat)
			return KUnitValue
		}

		private fun freshName(inBase: String): String {
			val vBase = cIdentifier(inBase)
			val vCount = mUsedNames.merge(vBase, 1, Int::plus)!!
			if (vCount == 1) return vBase
			return freshName("${vBase}_$vCount")
		}

		private fun newTemp(inType: KType): KLocal = KLocal(freshName("t"), inType).also { mTemps += it }

		private fun localOf(inDeclaration: IrValueDeclaration): KLocal? = mLocals[inDeclaration]

		/** Names [inValue] with a temporary unless it already cannot change. */
		private fun spill(inValue: KExpr): KExpr {
			if (inValue is KConst || inValue === KUnitValue || (inValue is KRead && inValue.local in mTemps)) return inValue
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
			kTypeOf(inExpression.type) ?: fail(inExpression, "type '${inExpression.type.render()}'").type

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

		// ============
		//  Statements

		private fun lowerStatement(inStatement: IrStatement) {
			when (inStatement) {
				is IrVariable -> {
					val vType = kTypeOf(inStatement.type) ?: run {
						fail(inStatement, "local of type '${inStatement.type.render()}'")
						return
					}
					val vInit = inStatement.initializer?.let { lowerRhs(it) }
					val vLocal = KLocal(freshName(inStatement.name.asString()), vType)
					mLocals[inStatement] = vLocal
					if (vType != KUnitType) mBlock += KLet(vLocal, vInit)
				}
				is IrExpression -> discard(lowerRhs(inStatement))
				else -> fail(inStatement, "local declaration")
			}
		}

		/** Keeps the side effect of an unused value (a call), drops pure expressions. */
		private fun discard(inValue: KExpr) {
			if (inValue is KCall) mBlock += KEval(inValue)
		}

		private fun emitReturn(inValue: IrExpression?) {
			if (inValue == null || mFunction.returnType == KUnitType) {
				inValue?.let { discard(lowerRhs(it)) }
				mBlock += KReturn(null)
			} else {
				mBlock += KReturn(lowerRhs(inValue))
			}
		}

		// ============
		//  Expressions

		/** Lowers to a pure expression, naming a call result with a temporary. */
		private fun lowerPure(inExpression: IrExpression): KExpr {
			val vValue = lowerRhs(inExpression)
			if (vValue !is KCall) return vValue
			if (vValue.type == KUnitType) {
				mBlock += KEval(vValue)
				return KUnitValue
			}
			return spill(vValue)
		}

		/**
		 * Lowers operands left to right. If a later operand needed statements, the earlier
		 * operands are named first so those statements cannot change what they read.
		 */
		private fun lowerOperands(inExpressions: List<IrExpression>): MutableList<KExpr> {
			val vResults = ArrayList<KExpr>()
			for (vExpression in inExpressions) {
				val vMark = mBlock.size
				val vValue = lowerPure(vExpression)
				if (mBlock.size > vMark) {
					var vInsertAt = vMark
					for (vIndex in vResults.indices) {
						val vEarlier = vResults[vIndex]
						if (vEarlier is KConst || vEarlier === KUnitValue || (vEarlier is KRead && vEarlier.local in mTemps)) continue
						val vTemp = newTemp(vEarlier.type)
						mBlock.add(vInsertAt++, KLet(vTemp, vEarlier))
						vResults[vIndex] = KRead(vTemp)
					}
				}
				vResults += vValue
			}
			return vResults
		}

		/** Lowers to an expression that may be a [KCall]; the caller consumes it immediately. */
		private fun lowerRhs(inExpression: IrExpression): KExpr = when (inExpression) {
			is IrConst -> lowerConst(inExpression)
			is IrGetValue -> localOf(inExpression.symbol.owner)?.let { if (it.type == KUnitType) KUnitValue else KRead(it) }
				?: fail(inExpression, "reference to '${inExpression.symbol.owner.name}'")
			is IrSetValue -> {
				val vValue = lowerRhs(inExpression.value)
				localOf(inExpression.symbol.owner)?.let { mBlock += KSet(it, vValue) }
					?: fail(inExpression, "assignment to '${inExpression.symbol.owner.name}'")
				KUnitValue
			}
			is IrCall -> lowerCall(inExpression)
			is IrConstructorCall -> lowerConstructorCall(inExpression)
			is IrGetField -> lowerGetField(inExpression)
			is IrSetField -> {
				val vValue = lowerRhs(inExpression.value)
				mGlobals[inExpression.symbol.owner]?.let { mBlock += KGlobalSet(it, vValue) } ?: fail(inExpression, "field assignment")
				KUnitValue
			}
			is IrWhen -> lowerWhen(inExpression)
			is IrContainerExpression -> lowerContainer(inExpression)
			is IrReturn -> {
				emitReturn(inExpression.value.takeUnless { isUnitObject(it) })
				KUnitValue
			}
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
			is IrGetObjectValue -> if (isUnitObject(inExpression)) KUnitValue else fail(inExpression, "object reference")
			is IrStringConcatenation -> fail(inExpression, "string template outside print/println (strings arrive in Phase 2)")
			else -> fail(inExpression, inExpression::class.simpleName?.removeSuffix("Impl") ?: "expression")
		}

		private fun isUnitObject(inExpression: IrExpression): Boolean =
			inExpression is IrGetObjectValue && inExpression.symbol.owner.kotlinFqName.asString() == "kotlin.Unit"

		private fun loopId(inLoop: IrLoop): Int = mLoops.getOrPut(inLoop) { mLoops.size }

		private fun lowerConst(inConst: IrConst): KExpr {
			val vType = typeOf(inConst) as? KPrimType
			val vValue = inConst.value
			if (vType == null || vValue == null || vValue is String) {
				return fail(inConst, if (vValue is String) "string value (strings arrive in Phase 2)" else "null")
			}
			return KConst(vValue, vType)
		}

		private fun lowerTypeOperator(inExpression: IrTypeOperatorCall): KExpr = when (inExpression.operator) {
			IrTypeOperator.IMPLICIT_COERCION_TO_UNIT -> {
				discard(lowerRhs(inExpression.argument))
				KUnitValue
			}
			IrTypeOperator.IMPLICIT_CAST, IrTypeOperator.CAST, IrTypeOperator.IMPLICIT_INTEGER_COERCION -> {
				val vValue = lowerRhs(inExpression.argument)
				val vTo = typeOf(inExpression)
				when {
					vValue.type == vTo -> vValue
					vTo is KPrimType && inExpression.operator == IrTypeOperator.IMPLICIT_INTEGER_COERCION ->
						convert(if (vValue is KCall) spill(vValue) else vValue, vTo.prim)
					else -> fail(inExpression, "cast to '${inExpression.typeOperand.render()}'")
				}
			}
			else -> fail(inExpression, "type operator ${inExpression.operator}")
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
		//  when / if

		private fun isElse(inBranch: IrBranch): Boolean =
			inBranch is IrElseBranch || (inBranch.condition as? IrConst)?.value == true

		private fun lowerWhen(inWhen: IrWhen): KExpr {
			val vType = typeOf(inWhen)
			trySelect(inWhen, vType)?.let { return it }
			val vResult = if (vType is KUnitType || vType is KNothingType) null else newTemp(vType).also { mTemps -= it }
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
				mBlock += KSet(inTarget, vValue)
			} else {
				discard(vValue)
			}
		}

		/** A `when` whose conditions and results are all pure becomes a `?:` chain. */
		private fun trySelect(inWhen: IrWhen, inType: KType): KExpr? {
			if (inType !is KPrimType && inType !is KStructType) return null
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
			if (vScratch.isNotEmpty() || vArms.any { it.first is KCall || it.second is KCall || it.second === KUnitValue }) {
				mNotSelectable += inWhen
				return null
			}
			var vResult = vArms.last().second
			for (vIndex in vArms.size - 2 downTo 0) {
				vResult = KSelect(vArms[vIndex].first!!, vArms[vIndex].second, vResult, inType)
			}
			return vResult
		}

		// ============
		//  for over a range

		private fun lowerFor(inBlock: IrContainerExpression): KExpr {
			val vIterator = inBlock.statements.getOrNull(0) as? IrVariable
			val vLoop = inBlock.statements.getOrNull(1) as? IrWhileLoop
			val vInner = vLoop?.body as? IrContainerExpression
			val vLoopVariable = vInner?.statements?.firstOrNull() as? IrVariable
			fun nameOf(inCall: IrCall?) = inCall?.symbol?.owner?.kotlinFqName?.asString()?.substringAfterLast('.')
			var vRange = (vIterator?.initializer as? IrCall)?.arguments?.firstOrNull() as? IrCall
			var vStepArgument: IrExpression? = null
			if (nameOf(vRange) == "step" && vRange?.arguments?.size == 2) {
				vStepArgument = vRange.arguments[1]
				vRange = vRange.arguments[0] as? IrCall
			}
			val vKind = nameOf(vRange)
			val vElement = vLoopVariable?.let { kTypeOf(it.type) } as? KPrimType
			if (vLoop == null || vInner == null || vLoopVariable == null || vRange == null || vRange.arguments.size != 2 ||
				vKind !in setOf("until", "rangeUntil", "rangeTo", "downTo") ||
				vElement == null || (vElement.prim != KPrim.INT && vElement.prim != KPrim.LONG)
			) {
				return fail(inBlock, "'for' over anything but an Int/Long range (until, .., ..<, downTo, step)")
			}
			val vPrim = vElement.prim
			val vDown = vKind == "downTo"

			val vBounds = lowerOperands(vRange.arguments.map { it!! } + listOfNotNull(vStepArgument))
			val vCounter = KLocal(freshName("i"), vElement)
			mBlock += KLet(vCounter, convert(vBounds[0], vElement.prim))
			val vEnd = spill(convert(vBounds[1], vElement.prim))
			val vId = loopId(vLoop)
			val vStepValue = if (vStepArgument != null) spill(convert(vBounds[2], vPrim)) else one(vPrim)
			if (vStepArgument != null) {
				mBlock += KEval(KIntrinsic("ktc2_check_step", listOf(convert(vStepValue, KPrim.LONG)), KUnitType))
			}
			val vStep = KSet(vCounter, KBinary(if (vDown) KBinaryOp.SUB else KBinaryOp.ADD, KRead(vCounter), vStepValue, vPrim, vElement))
			fun compare(inOp: KBinaryOp, inRight: KExpr = vEnd) = KBinary(inOp, KRead(vCounter), inRight, vPrim, kBoolType)

			val vVariable = KLocal(freshName(vLoopVariable.name.asString()), vElement)
			mLocals[vLoopVariable] = vVariable
			val vExclusive = vKind == "until" || vKind == "rangeUntil"
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

		// ============
		//  Calls

		private fun lowerConstructorCall(inCall: IrConstructorCall): KExpr {
			val vTarget = mFunctions[inCall.symbol.owner]
				?: return fail(inCall, "construction of '${inCall.symbol.owner.parent.kotlinFqName}'")
			return callFunction(inCall, vTarget, inCall.symbol.owner, inCall.arguments)
		}

		private fun callFunction(inCall: IrExpression, inTarget: KFunction, inCallee: IrFunction, inArguments: List<IrExpression?>): KExpr {
			if (inArguments.any { it == null }) return callWithDefaults(inCall, inTarget, inCallee, inArguments)
			val vArguments = lowerOperands(inArguments.map { it!! })
			if (vArguments.any { it === KUnitValue }) return fail(inCall, "Unit passed as an argument")
			return KCall(inTarget, vArguments)
		}

		/**
		 * Omitted arguments take the callee's default expressions, evaluated here after the given
		 * arguments, with the callee's parameters bound to the values already computed.
		 */
		private fun callWithDefaults(inCall: IrExpression, inTarget: KFunction, inCallee: IrFunction, inArguments: List<IrExpression?>): KExpr {
			val vGiven = lowerOperands(inArguments.filterNotNull())
			if (vGiven.any { it === KUnitValue }) return fail(inCall, "Unit passed as an argument")
			val vSaved = inCallee.parameters.associateWith { mLocals[it] }
			val vBound = arrayOfNulls<KLocal>(inArguments.size)
			var vNext = 0
			inArguments.forEachIndexed { vIndex, vArgument ->
				if (vArgument == null) return@forEachIndexed
				val vTemp = newTemp(inTarget.params[vIndex].type)
				mBlock += KLet(vTemp, vGiven[vNext++])
				vBound[vIndex] = vTemp
			}
			var vFailed = false
			inArguments.forEachIndexed { vIndex, vArgument ->
				val vParam = inCallee.parameters[vIndex]
				if (vArgument == null) {
					val vDefault = vParam.defaultValue?.expression
					if (vDefault == null) {
						vFailed = true
						return@forEachIndexed
					}
					val vTemp = newTemp(inTarget.params[vIndex].type)
					mBlock += KLet(vTemp, lowerRhs(vDefault))
					vBound[vIndex] = vTemp
				}
				mLocals[vParam] = vBound[vIndex]!!
			}
			for ((vParam, vLocal) in vSaved) {
				if (vLocal == null) mLocals.remove(vParam) else mLocals[vParam] = vLocal
			}
			if (vFailed) return fail(inCall, "call with a missing argument")
			return KCall(inTarget, vBound.map { KRead(it!!) })
		}

		private fun lowerGetField(inExpression: IrGetField): KExpr {
			mGlobals[inExpression.symbol.owner]?.let { return KGlobalRead(it) }
			val vField = mFields[inExpression.symbol.owner] ?: return fail(inExpression, "field access")
			val vReceiver = inExpression.receiver ?: return fail(inExpression, "static field access")
			return KFieldRead(lowerPure(vReceiver), vField)
		}

		private fun lowerCall(inCall: IrCall): KExpr {
			val vCallee = inCall.symbol.owner
			mFunctions[vCallee]?.let { return callFunction(inCall, it, vCallee, inCall.arguments) }
			mGlobalGetters[vCallee]?.let { return KGlobalRead(it) }
			mGlobalSetters[vCallee]?.let { vGlobal ->
				mBlock += KGlobalSet(vGlobal, lowerRhs(inCall.arguments.last()!!))
				return KUnitValue
			}

			val vName = vCallee.name.asString()
			val vParent = vCallee.parent.kotlinFqName.asString()
			val vArguments = inCall.arguments

			// A @Struct property getter reads the field directly.
			vCallee.correspondingPropertySymbol?.owner?.backingField?.let { mFields[it] }?.let { vField ->
				if (vCallee.isGetter) return KFieldRead(lowerPure(vArguments[0]!!), vField)
			}
			if (vParent == "kotlin.io" && (vName == "println" || vName == "print")) {
				lowerPrint(vArguments.firstOrNull(), vName == "println")
				return KUnitValue
			}
			if (vArguments.any { it == null }) return fail(inCall, "call to '$vParent.$vName'")
			val vOperandTypes = vArguments.map { kTypeOf(it!!.type) as? KPrimType }
			if (vOperandTypes.isEmpty() || vOperandTypes.any { it == null }) return fail(inCall, "call to '$vParent.$vName'")
			val vResultType = typeOf(inCall) as? KPrimType ?: return fail(inCall, "call to '$vParent.$vName'")
			val vOperands = lowerOperands(vArguments.map { it!! })
			val vFirst = vOperandTypes[0]!!.prim
			if (vName == "<get-code>" && vFirst == KPrim.CHAR) return convert(vOperands[0], KPrim.INT)
			// toUInt() and friends on signed types are extensions in package kotlin.
			if (vParent == "kotlin" && vOperands.size == 1) kConversionByName[vName]?.let { return convert(vOperands[0], it) }

			if (vParent == "kotlin.internal.ir" && vOperands.size == 2) {
				val vOp = kComparisonByName[vName] ?: return fail(inCall, "intrinsic '$vName'")
				val vCommon = promote(vFirst, vOperandTypes[1]!!.prim).let { if (vFirst == KPrim.CHAR && vOperandTypes[1]!!.prim == KPrim.CHAR) KPrim.CHAR else it }
				return KBinary(vOp, convert(vOperands[0], vCommon), convert(vOperands[1], vCommon), vCommon, kBoolType)
			}
			if (kPrimByClass[vParent] != vFirst) return fail(inCall, "call to '$vParent.$vName'")

			if (vOperands.size == 1) {
				kConversionByName[vName]?.let { return convert(vOperands[0], if (vName == "<get-code>") KPrim.INT else it) }
				// Arithmetic on Byte / Short / Char is done in Int, then narrowed back.
				val vWork = promote(vFirst, vFirst)
				val vOperand = convert(vOperands[0], vWork)
				val vWorkType = KPrimType(vWork)
				val vOne = if (vWork == KPrim.BOOLEAN) KConst(true, vWorkType) else one(vWork)
				val vValue: KExpr = when (vName) {
					"unaryPlus" -> vOperand
					"unaryMinus" -> KUnary(KUnaryOp.NEG, vOperand, vWorkType)
					"inv" -> KUnary(KUnaryOp.INV, vOperand, vWorkType)
					"not" -> not(vOperand)
					"inc" -> KBinary(KBinaryOp.ADD, vOperand, vOne, vWork, vWorkType)
					"dec" -> KBinary(KBinaryOp.SUB, vOperand, vOne, vWork, vWorkType)
					else -> return fail(inCall, "call to '$vParent.$vName'")
				}
				return convert(vValue, vResultType.prim)
			}
			if (vOperands.size == 2) {
				val vOp = kBinaryByName[vName] ?: return fail(inCall, "call to '$vParent.$vName'")
				val vSecond = vOperandTypes[1]!!.prim
				val vIsShift = vOp == KBinaryOp.SHL || vOp == KBinaryOp.SHR || vOp == KBinaryOp.USHR
				val vWork = if (vIsShift) vFirst else promote(vFirst, vSecond)
				val vLeft = convert(vOperands[0], vWork)
				val vRight = convert(vOperands[1], if (vIsShift) KPrim.INT else vWork)
				val vType = if (vOp == KBinaryOp.CMP) kIntType else KPrimType(vWork)
				return convert(KBinary(vOp, vLeft, vRight, vWork, vType), vResultType.prim)
			}
			return fail(inCall, "call to '$vParent.$vName'")
		}

		private fun lowerPrint(inArgument: IrExpression?, inNewline: Boolean) {
			val vPieces: List<IrExpression> = when (inArgument) {
				null -> emptyList()
				is IrStringConcatenation -> inArgument.arguments
				else -> listOf(inArgument)
			}
			val vValues = lowerOperands(vPieces.filter { !(it is IrConst && it.value is String) })
			var vNext = 0
			val vParts = ArrayList<KPrintPart>()
			for (vPiece in vPieces) {
				if (vPiece is IrConst && vPiece.value is String) {
					vParts += KPrintText(vPiece.value as String)
					continue
				}
				val vValue = vValues[vNext++]
				if (vValue.type is KPrimType) vParts += KPrintValue(vValue) else fail(vPiece, "printing a value of type '${vPiece.type.render()}'")
			}
			mBlock += KPrint(vParts, inNewline)
		}
	}
}
