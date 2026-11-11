@file:OptIn(UnsafeDuringIrConstructionAPI::class)

package com.bitsycore.ktc2.frontend

import com.bitsycore.ktc2.kir.*
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.fileOrNull
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.util.isObject
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.ir.util.resolveFakeOverride
import java.io.File

private val kCKeywords = setOf(
	"auto", "break", "case", "char", "const", "continue", "default", "do", "double", "else", "enum", "extern",
	"float", "for", "goto", "if", "inline", "int", "long", "register", "restrict", "return", "short", "signed",
	"sizeof", "static", "struct", "switch", "typedef", "union", "unsigned", "void", "volatile", "while", "bool",
	"true", "false", "main", "NULL",
)

/** Replaces every character of [inName] that C identifiers do not allow. */
internal fun cSuffix(inName: String): String =
	inName.map { if (it.isLetterOrDigit() && it.code < 128 || it == '_') it else '_' }.joinToString("")

/** Makes [inName] a valid, non-reserved C identifier. */
internal fun cIdentifier(inName: String): String {
	val vClean = cSuffix(inName)
	return if (vClean.isEmpty() || vClean[0].isDigit() || vClean in kCKeywords) "_$vClean" else vClean
}

private fun cPrefix(inPackage: String): String = if (inPackage.isEmpty()) "kt_" else inPackage.replace('.', '_') + "_"

internal fun isAnyClass(inClass: IrClass): Boolean = inClass.kotlinFqName.asString() == "kotlin.Any"

/** True when [inClass] is [inAncestor] or inherits from it (class or interface). */
internal fun isSubtype(inClass: IrClass, inAncestor: IrClass): Boolean =
	inClass == inAncestor || inClass.superTypes.any { vSuper -> vSuper.classOrNull?.owner?.let { isSubtype(it, inAncestor) } == true }

/** True when [inFunction] overrides [inBase], directly or through intermediate overrides. */
private fun overrides(inFunction: IrSimpleFunction, inBase: IrSimpleFunction): Boolean =
	inFunction.overriddenSymbols.any { it.owner == inBase || overrides(it.owner, inBase) }

/** Name of the `kotlin.Any` member [inFunction] is or overrides, if any. */
private fun anyMemberName(inFunction: IrSimpleFunction): String? {
	if (isAnyClass(inFunction.parentAsClass)) return inFunction.name.asString()
	return inFunction.overriddenSymbols.firstNotNullOfOrNull { anyMemberName(it.owner) }
}

/**
 * Translates the K2 module IR into KIR, starting from `main` and importing only what is
 * reachable (functions, classes, the properties of every file that is touched).
 *
 * Unsupported constructs are recorded in [errors] with their source position.
 */
class KirImporter(internal val mBuiltIns: IrBuiltIns) {

	val errors = linkedSetOf<String>()

	private val mUnits = ArrayList<KUnit>()
	private val mFacades = HashMap<IrFile, KUnit>()
	private val mInterfaceUnits = HashMap<IrClass, KUnit>()
	private val mDispatchUnit = KUnit("", "KtDispatch")
	private val mRuntimeUnit = KUnit("", "ktc2_core")
	private val mFunctions = HashMap<IrFunction, KFunction?>()
	private val mStructs = HashMap<IrClass, KStruct>()
	private val mClasses = HashMap<IrClass, KClass?>()
	private val mClassList = ArrayList<KClass>()
	private val mInterfaces = HashMap<IrClass, KInterface>()
	private val mFields = HashMap<IrField, KField>()
	private val mGlobals = HashMap<IrField, KGlobal>()
	private val mGlobalGetters = HashMap<IrFunction, KGlobal>()
	private val mGlobalSetters = HashMap<IrFunction, KGlobal>()
	private val mOverloads = HashMap<String, Int>()
	private val mPending = ArrayDeque<() -> Unit>()
	private val mInstantiated = LinkedHashSet<IrClass>()
	private val mDispatchers = LinkedHashMap<IrSimpleFunction, KFunction>()
	private val mDispatchDone = HashSet<Pair<IrSimpleFunction, IrClass>>()
	private val mRuntime = HashMap<String, KFunction>()

	// ==================
	// MARK: Entry
	// ==================

	fun import(inModule: IrModuleFragment): KModule {
		for (vFile in inModule.files) countOverloads(vFile.declarations)
		val vMain = inModule.files.firstNotNullOfOrNull { vFile ->
			vFile.declarations.filterIsInstance<IrSimpleFunction>().firstOrNull { it.name.asString() == "main" && it.parameters.isEmpty() }
		}
		val vEntry = vMain?.let { functionFor(it) }

		// Reachability fixpoint: bodies reach new functions and classes, new classes add
		// implementations to the virtual calls already seen.
		do {
			while (mPending.isNotEmpty()) mPending.removeFirst().invoke()
		} while (resolveDispatch() || mPending.isNotEmpty())

		if (mDispatchUnit.functions.isNotEmpty()) mUnits += mDispatchUnit
		return KModule(mUnits, vEntry, mClassList)
	}

	private fun countOverloads(inDeclarations: List<IrDeclaration>) {
		for (vDeclaration in inDeclarations) {
			when (vDeclaration) {
				is IrFunction -> mOverloads.merge(vDeclaration.kotlinFqName.asString(), 1, Int::plus)
				is IrClass -> countOverloads(vDeclaration.declarations)
				else -> {}
			}
		}
	}

	internal fun unsupported(inFile: IrFile?, inElement: IrElement, inWhat: String) {
		val vLine = if (inFile != null && inElement.startOffset >= 0) inFile.fileEntry.getLineNumber(inElement.startOffset) + 1 else 0
		val vName = inFile?.let { File(it.fileEntry.name).name } ?: "<library>"
		errors += "$vName:$vLine: not supported yet: $inWhat"
	}

	// ==================
	// MARK: Types
	// ==================

	private fun primOf(inType: IrType): KPrim? = when {
		inType.isBoolean() -> KPrim.BOOLEAN
		inType.isByte() -> KPrim.BYTE
		inType.isShort() -> KPrim.SHORT
		inType.isInt() -> KPrim.INT
		inType.isLong() -> KPrim.LONG
		inType.isChar() -> KPrim.CHAR
		inType.isFloat() -> KPrim.FLOAT
		inType.isDouble() -> KPrim.DOUBLE
		inType.isUByte() -> KPrim.UBYTE
		inType.isUShort() -> KPrim.USHORT
		inType.isUInt() -> KPrim.UINT
		inType.isULong() -> KPrim.ULONG
		else -> null
	}

	/** The KIR type of [inType], or null when it is not supported (the caller reports where). */
	internal fun kTypeOf(inType: IrType): KType? {
		primOf(inType)?.let { return KPrimType(it) }
		val vBase = inType.makeNotNull()
		if (inType.isMarkedNullable()) primOf(vBase)?.let { return KOptType(it) }
		return when {
			vBase.isUnit() -> KUnitType
			vBase.isNothing() -> if (inType.isMarkedNullable()) KObjType else KNothingType
			vBase.isString() -> KStringType
			vBase.isAny() -> KObjType
			else -> {
				val vClass = vBase.classOrNull?.owner ?: return null
				when {
					vClass.isInterface -> KObjType
					isStruct(vClass) -> structFor(vClass)?.let { KStructType(it) }
					else -> classFor(vClass)?.let { KClassType(it) }
				}
			}
		}
	}

	private fun isStruct(inClass: IrClass): Boolean =
		inClass.annotations.any { it.type.classFqName?.asString() == "ktc2.Struct" }

	private fun nestedName(inClass: IrClass): String =
		generateSequence(inClass) { it.parent as? IrClass }.toList().reversed().joinToString(".") { it.name.asString() }

	// ==================
	// MARK: Units
	// ==================

	/** The unit holding the top-level declarations of [inFile]; touching it brings in the file's properties. */
	private fun facadeFor(inFile: IrFile): KUnit {
		mFacades[inFile]?.let { return it }
		val vBase = File(inFile.fileEntry.name).nameWithoutExtension
		val vUnit = KUnit(inFile.packageFqName.asString(), cIdentifier(vBase.replaceFirstChar { it.uppercase() }) + "Kt")
		mFacades[inFile] = vUnit
		mUnits += vUnit

		val vStartup = ArrayList<Pair<KGlobal, IrExpression>>()
		for (vProperty in inFile.declarations.filterIsInstance<IrProperty>()) {
			declareProperty(vProperty, vUnit, inFile)?.let { vStartup += it }
		}
		if (vStartup.isNotEmpty()) {
			val vInit = KFunction("${cPrefix(vUnit.pkg)}${vUnit.name}_init", vUnit, emptyList(), KUnitType)
			vUnit.initializer = vInit
			mPending += { BodyBuilder(this, vInit, null, inFile).buildGlobalInitializer(vStartup) }
		}
		return vUnit
	}

	private fun interfaceUnitFor(inClass: IrClass): KUnit = mInterfaceUnits.getOrPut(inClass) {
		KUnit(inClass.fileOrNull?.packageFqName?.asString() ?: "", nestedName(inClass)).also { mUnits += it }
	}

	// ==================
	// MARK: Classes
	// ==================

	internal fun structFor(inClass: IrClass): KStruct? {
		mStructs[inClass]?.let { return it }
		val vFile = inClass.fileOrNull ?: return null
		val vUnit = KUnit(vFile.packageFqName.asString(), nestedName(inClass))
		val vStruct = KStruct(cPrefix(vUnit.pkg) + cSuffix(nestedName(inClass)), vUnit)
		vUnit.struct = vStruct
		mStructs[inClass] = vStruct
		mUnits += vUnit
		for (vProperty in inClass.declarations.filterIsInstance<IrProperty>()) {
			val vBacking = vProperty.backingField ?: continue
			if (vProperty.isVar) unsupported(vFile, vProperty, "'var' property in a @Struct class")
			val vType = kTypeOf(vBacking.type)
			if (vType == null || vType is KRefType || (vType is KStructType && vType.struct === vStruct)) {
				unsupported(vFile, vProperty, "@Struct property of type '${vBacking.type.render()}'")
				continue
			}
			val vField = KField(cIdentifier(vProperty.name.asString()), vType)
			vStruct.fields += vField
			mFields[vBacking] = vField
		}
		return vStruct
	}

	/** The reference class for [inClass], declared (layout only) on first use. */
	internal fun classFor(inClass: IrClass): KClass? {
		if (inClass in mClasses) return mClasses[inClass]
		val vFile = inClass.fileOrNull
		if (vFile == null || inClass.kind == org.jetbrains.kotlin.descriptors.ClassKind.ENUM_CLASS || inClass.kind == org.jetbrains.kotlin.descriptors.ClassKind.ENUM_ENTRY || inClass.isInterface) {
			mClasses[inClass] = null
			return null
		}
		val vSuperIr = inClass.superTypes.mapNotNull { it.classOrNull?.owner }.firstOrNull { !it.isInterface && !isAnyClass(it) }
		val vSuper = vSuperIr?.let { classFor(it) }
		if (vSuperIr != null && vSuper == null) {
			unsupported(vFile, inClass, "superclass '${vSuperIr.kotlinFqName}'")
			mClasses[inClass] = null
			return null
		}
		val vUnit = KUnit(vFile.packageFqName.asString(), nestedName(inClass))
		val vClass = KClass(cPrefix(vUnit.pkg) + cSuffix(nestedName(inClass)), vUnit, vSuper, inClass.name.asString())
		vUnit.cls = vClass
		mClasses[inClass] = vClass
		mClassList += vClass
		mUnits += vUnit

		for (vProperty in inClass.declarations.filterIsInstance<IrProperty>()) {
			val vBacking = vProperty.backingField ?: continue
			val vType = kTypeOf(vBacking.type)
			if (vType == null || vType == KUnitType || vType == KNothingType) {
				unsupported(vFile, vProperty, "property of type '${vBacking.type.render()}'")
				continue
			}
			val vField = KField(cIdentifier(vProperty.name.asString()), vType, vClass)
			vClass.fields += vField
			mFields[vBacking] = vField
		}
		collectInterfaces(inClass, vClass.interfaces)

		if (inClass.isObject) {
			vClass.isSingleton = true
			instantiate(inClass)
			val vConstructor = inClass.declarations.filterIsInstance<IrConstructor>().firstOrNull()
			val vInit = vConstructor?.let { functionFor(it) }
			if (vInit != null) {
				val vStatic = KFunction("${vClass.cName}_static_init", vUnit, emptyList(), KUnitType)
				vStatic.body += KEval(KCall(vInit, listOf(KSingletonRead(vClass))))
				vUnit.initializer = vStatic
			}
		}
		return vClass
	}

	private fun collectInterfaces(inClass: IrClass, outInterfaces: MutableSet<KInterface>) {
		for (vSuper in inClass.superTypes.mapNotNull { it.classOrNull?.owner }) {
			if (vSuper.isInterface) outInterfaces += interfaceFor(vSuper)
		}
	}

	internal fun interfaceFor(inClass: IrClass): KInterface {
		mInterfaces[inClass]?.let { return it }
		val vInterface = KInterface(inClass.name.asString())
		mInterfaces[inClass] = vInterface
		for (vSuper in inClass.superTypes.mapNotNull { it.classOrNull?.owner }.filter { it.isInterface }) {
			val vParent = interfaceFor(vSuper)
			vInterface.supers += vParent
			vInterface.supers += vParent.supers
		}
		return vInterface
	}

	/** Records that the program creates instances of [inClass]: it becomes a dispatch target. */
	internal fun instantiate(inClass: IrClass) {
		if (mInstantiated.add(inClass)) classFor(inClass)?.isInstantiated = true
	}

	internal fun fieldFor(inField: IrField): KField? {
		mFields[inField]?.let { return it }
		val vOwner = inField.parent as? IrClass ?: return null
		if (isStruct(vOwner)) structFor(vOwner) else classFor(vOwner)
		return mFields[inField]
	}

	// ==================
	// MARK: Properties
	// ==================

	internal fun globalFor(inField: IrField): KGlobal? {
		mGlobals[inField]?.let { return it }
		(inField.parent as? IrFile)?.let { facadeFor(it) }
		return mGlobals[inField]
	}

	internal fun globalGetter(inFunction: IrFunction): KGlobal? {
		touchFile(inFunction)
		return mGlobalGetters[inFunction]
	}

	internal fun globalSetter(inFunction: IrFunction): KGlobal? {
		touchFile(inFunction)
		return mGlobalSetters[inFunction]
	}

	private fun touchFile(inFunction: IrFunction) {
		val vProperty = (inFunction as? IrSimpleFunction)?.correspondingPropertySymbol?.owner ?: return
		(vProperty.parent as? IrFile)?.let { facadeFor(it) }
	}

	/**
	 * Declares a top-level property: a global for its backing field, direct access for default
	 * accessors (custom ones are ordinary functions).
	 *
	 * @return the global and its initializer when that initializer has to run at startup
	 */
	private fun declareProperty(inProperty: IrProperty, inUnit: KUnit, inFile: IrFile): Pair<KGlobal, IrExpression>? {
		if (inProperty.isDelegated || inProperty.isLateinit) {
			unsupported(inFile, inProperty, "delegated or lateinit property")
			return null
		}
		val vBacking = inProperty.backingField ?: return null
		val vType = kTypeOf(vBacking.type)
		if (vType == null || vType == KUnitType || vType == KNothingType) {
			unsupported(inFile, inProperty, "property type '${vBacking.type.render()}'")
			return null
		}
		val vGlobal = KGlobal(cPrefix(inUnit.pkg) + cSuffix(inProperty.name.asString()), vType, inUnit)
		inUnit.globals += vGlobal
		mGlobals[vBacking] = vGlobal
		for (vAccessor in listOfNotNull(inProperty.getter, inProperty.setter)) {
			if (vAccessor.origin != IrDeclarationOrigin.DEFAULT_PROPERTY_ACCESSOR) continue
			if (vAccessor === inProperty.getter) mGlobalGetters[vAccessor] = vGlobal else mGlobalSetters[vAccessor] = vGlobal
		}
		val vInit = vBacking.initializer?.expression ?: return null
		val vValue = (vInit as? IrConst)?.value
		if (vInit is IrConst && vType is KPrimType && vValue != null && vValue !is String) {
			vGlobal.constInit = KConst(vValue, vType)
			return null
		}
		return vGlobal to vInit
	}

	// ==================
	// MARK: Functions
	// ==================

	/** A function implemented by the C runtime. */
	internal fun runtime(inName: String, inParams: List<KType>, inReturn: KType): KFunction = mRuntime.getOrPut(inName) {
		KFunction(inName, mRuntimeUnit, inParams.mapIndexed { vIndex, vType -> KLocal("p$vIndex", vType) }, inReturn).apply { isExternal = true }
	}

	/** The runtime default of a `kotlin.Any` member (`toString`, `equals`, `hashCode`). */
	private fun anyDefault(inName: String): KFunction? = when (inName) {
		"toString" -> runtime("ktc2_any_toString", listOf(KObjType), KStringType)
		"equals" -> runtime("ktc2_any_equals", listOf(KObjType, KObjType), KPrimType(KPrim.BOOLEAN))
		"hashCode" -> runtime("ktc2_any_hashCode", listOf(KObjType), KPrimType(KPrim.INT))
		else -> null
	}

	private class Signature(val params: List<KLocal>, val returnType: KType)

	private fun signatureOf(inFunction: IrFunction, inFile: IrFile?): Signature? {
		if (inFunction.typeParameters.isNotEmpty() && inFunction !is IrConstructor) {
			unsupported(inFile, inFunction, "generic function '${inFunction.name}'")
			return null
		}
		var vOk = true
		val vNames = HashSet<String>()
		val vParams = ArrayList<KLocal>()
		for (vParam in inFunction.parameters) {
			val vType = kTypeOf(vParam.type)
			if (vType == null || vType == KUnitType || vType == KNothingType) {
				unsupported(inFile, vParam, "parameter type '${vParam.type.render()}'")
				vOk = false
				continue
			}
			var vName = if (vParam.kind == IrParameterKind.Regular) cIdentifier(vParam.name.asString()) else "self"
			while (!vNames.add(vName)) vName += "_"
			vParams += KLocal(vName, vType)
		}
		val vReturn = kTypeOf(inFunction.returnType)
		if (vReturn == null) {
			unsupported(inFile, inFunction, "return type '${inFunction.returnType.render()}'")
			return null
		}
		return if (vOk) Signature(vParams, vReturn) else null
	}

	private fun overloadSuffix(inFunction: IrFunction): String {
		if ((mOverloads[inFunction.kotlinFqName.asString()] ?: 0) <= 1) return ""
		val vRegular = inFunction.parameters.filter { it.kind == IrParameterKind.Regular }
		return "__" + vRegular.joinToString("_") { cSuffix(it.type.render().substringAfterLast('.')) }
	}

	/** The KIR function for [inFunction], declared on first use with its body queued for import. */
	internal fun functionFor(inFunction: IrFunction): KFunction? {
		if (inFunction in mFunctions) return mFunctions[inFunction]
		if (inFunction is IrSimpleFunction && inFunction.isFakeOverride) {
			val vReal = inFunction.resolveFakeOverride()
			return (if (vReal == null || vReal == inFunction) null else functionFor(vReal)).also { mFunctions[inFunction] = it }
		}
		val vOwner = inFunction.parent as? IrClass
		if (vOwner != null && isAnyClass(vOwner)) {
			return anyDefault(inFunction.name.asString()).also { mFunctions[inFunction] = it }
		}
		val vFile = inFunction.fileOrNull
		val vIsDefaultAccessor = inFunction.origin == IrDeclarationOrigin.DEFAULT_PROPERTY_ACCESSOR
		if (vFile == null || (inFunction.body == null && !vIsDefaultAccessor && inFunction !is IrConstructor)) {
			mFunctions[inFunction] = null
			return null
		}
		mFunctions[inFunction] = null
		val vSignature = signatureOf(inFunction, vFile) ?: return null

		val vStruct = vOwner?.takeIf { isStruct(it) }?.let { structFor(it) }
		val vClass = if (vOwner != null && vStruct == null && !vOwner.isInterface) classFor(vOwner) else null
		if (vOwner != null && vStruct == null && vClass == null && !vOwner.isInterface) {
			unsupported(vFile, vOwner, "class '${vOwner.name}'")
			return null
		}
		val vUnit = when {
			vStruct != null -> vStruct.unit
			vClass != null -> vClass.unit
			vOwner != null -> interfaceUnitFor(vOwner)
			else -> facadeFor(vFile)
		}
		val vScope = when {
			vStruct != null -> vStruct.cName + "_"
			vClass != null -> vClass.cName + "_"
			vOwner != null -> cPrefix(vUnit.pkg) + cSuffix(nestedName(vOwner)) + "_"
			else -> cPrefix(vUnit.pkg)
		}
		val vIsConstructor = inFunction is IrConstructor
		val vBase = when {
			vIsConstructor && vStruct != null -> "new"
			vIsConstructor -> "init"
			else -> cSuffix(inFunction.name.asString())
		}
		// A class constructor initializes an object allocated by the caller.
		val vParams = if (vIsConstructor && vClass != null) listOf(KLocal("self", KClassType(vClass))) + vSignature.params else vSignature.params
		val vReturn = when {
			vIsConstructor && vStruct != null -> KStructType(vStruct)
			vIsConstructor -> KUnitType
			else -> vSignature.returnType
		}
		val vFunction = KFunction(vScope + vBase + overloadSuffix(inFunction), vUnit, vParams, vReturn)
		vUnit.functions += vFunction
		mFunctions[inFunction] = vFunction
		mPending += { BodyBuilder(this, vFunction, inFunction, vFile).build() }
		return vFunction
	}

	// ==================
	// MARK: Virtual dispatch
	// ==================

	/** The dispatcher called for a virtual call to [inFunction]; its targets are filled in by [resolveDispatch]. */
	internal fun dispatcherFor(inFunction: IrSimpleFunction): KFunction? {
		mDispatchers[inFunction]?.let { return it }
		val vOwner = inFunction.parentAsClass
		val vFile = vOwner.fileOrNull
		val vSignature = signatureOf(inFunction, vFile) ?: return null
		val vClass = if (vFile != null && !vOwner.isInterface) classFor(vOwner) else null
		val vUnit = when {
			vClass != null -> vClass.unit
			vFile != null -> interfaceUnitFor(vOwner)
			else -> mDispatchUnit
		}
		val vScope = vClass?.cName ?: (cPrefix(vFile?.packageFqName?.asString() ?: "") + cSuffix(nestedName(vOwner)))
		val vName = "${vScope}_${cSuffix(inFunction.name.asString())}${overloadSuffix(inFunction)}__v"
		val vDispatcher = KFunction(vName, vUnit, vSignature.params, vSignature.returnType)
		vDispatcher.dispatch = mutableListOf()
		vDispatcher.dispatchDefault = anyMemberName(inFunction)?.let { anyDefault(it) }
		vUnit.functions += vDispatcher
		mDispatchers[inFunction] = vDispatcher
		return vDispatcher
	}

	private fun membersOf(inClass: IrClass): List<IrSimpleFunction> = inClass.declarations.flatMap { vDeclaration ->
		when (vDeclaration) {
			is IrSimpleFunction -> listOf(vDeclaration)
			is IrProperty -> listOfNotNull(vDeclaration.getter, vDeclaration.setter)
			else -> emptyList()
		}
	}

	/**
	 * Gives every dispatcher the implementation of each instantiated class it applies to.
	 *
	 * @return true when new targets were added (their bodies are now pending)
	 */
	private fun resolveDispatch(): Boolean {
		var vChanged = false
		for ((vFunction, vDispatcher) in mDispatchers.entries.toList()) {
			val vOwner = vFunction.parentAsClass
			for (vConcrete in mInstantiated.toList()) {
				if (!mDispatchDone.add(vFunction to vConcrete) || !isSubtype(vConcrete, vOwner)) continue
				val vMember = membersOf(vConcrete).firstOrNull { it == vFunction || overrides(it, vFunction) } ?: continue
				val vImplementation = if (vMember.isFakeOverride) vMember.resolveFakeOverride() else vMember
				if (vImplementation == null || isAnyClass(vImplementation.parentAsClass)) continue
				val vTarget = functionFor(vImplementation) ?: continue
				val vClass = classFor(vConcrete) ?: continue
				vDispatcher.dispatch!! += vClass to vTarget
				vChanged = true
			}
		}
		return vChanged
	}

	/** True when a call to [inFunction] must go through a dispatcher. */
	internal fun isVirtual(inFunction: IrSimpleFunction): Boolean {
		val vOwner = inFunction.parent as? IrClass ?: return false
		if (isStruct(vOwner)) return false
		return vOwner.isInterface || (inFunction.modality != Modality.FINAL && vOwner.modality != Modality.FINAL)
	}
}
