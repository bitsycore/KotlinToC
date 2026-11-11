@file:OptIn(UnsafeDuringIrConstructionAPI::class)

package com.bitsycore.ktc2.frontend

import com.bitsycore.ktc2.kir.*
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.fileOrNull
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.util.isObject
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.ir.util.resolveFakeOverride
import org.jetbrains.kotlin.ir.util.substitute
import java.io.File

/** What each type parameter in scope stands for. */
internal typealias Subst = Map<IrTypeParameterSymbol, IrType>

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

/** The runtime function that starts a thread; a program reaching it is built with atomic counts. */
private const val kThreadStart = "ktc2_threadStart"

/** The `kotlinx.cinterop` classes whose values, and whose subclasses' values, are bare C addresses. */
private val kPointerRoots = setOf(
	"kotlinx.cinterop.NativePtr", "kotlinx.cinterop.NativePointed", "kotlinx.cinterop.CValuesRef", "kotlinx.cinterop.StableRef",
)

/**
 * True for a class of C interop that stands for an address: a pointer, or the thing pointed at
 * (a C variable or struct, which Kotlin code only ever holds by its location).
 */
internal fun isPointerClass(inClass: IrClass): Boolean =
	inClass.kotlinFqName.asString() in kPointerRoots || inClass.superTypes.any { vSuper -> vSuper.classOrNull?.owner?.let { isPointerClass(it) } == true }

/** The `#include` operands a file of bindings names with `@file:CHeaders`. */
internal fun cHeadersOf(inFile: IrFile?): List<String> {
	val vAnnotation = inFile?.annotations?.firstOrNull { it.type.classFqName?.asString() == "kotlinx.cinterop.internal.CHeaders" } ?: return emptyList()
	val vNames = vAnnotation.arguments.firstOrNull() as? IrVararg ?: return emptyList()
	return vNames.elements.mapNotNull { (it as? IrConst)?.value as? String }
}

internal fun isAnyClass(inClass: IrClass): Boolean = inClass.kotlinFqName.asString() == "kotlin.Any"

internal fun isThrowableClass(inClass: IrClass): Boolean = inClass.kotlinFqName.asString() == "kotlin.Throwable"

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

/** A short name for [inType] in mangled C identifiers. */
private fun mangle(inType: KType): String = when (inType) {
	is KPrimType -> inType.prim.name.lowercase().replaceFirstChar { it.uppercase() }
	is KOptType -> mangle(KPrimType(inType.prim)) + "Opt"
	is KStructType -> inType.struct.cName
	KPtrType -> "Ptr"
	KStringType -> "String"
	is KClassType -> inType.cls.name
	KObjType -> "Any"
	is KArrayType -> mangle(inType.element) + "Array"
	KUnitType -> "Unit"
	KNothingType -> "Nothing"
}

/**
 * Translates the K2 module IR into KIR, starting from `main` and importing only what is
 * reachable (functions, classes, the properties of every file that is touched).
 *
 * Generic declarations are instantiated per distinct tuple of type arguments. All reference
 * types count as one (`Any?`): they are all pointers, so `Box<String>` and `Box<User>` share
 * one instantiation while `Box<Int>` gets its own, unboxed.
 *
 * Unsupported constructs are recorded in [errors] with their source position.
 */
class KirImporter(internal val mBuiltIns: IrBuiltIns) {

	val errors = linkedSetOf<String>()

	/** A generic declaration with the KIR types of its type arguments. */
	private data class Mono(val declaration: IrDeclaration, val arguments: List<KType>)

	/** The K2 side of an imported class: its declaration and what its type parameters stand for. */
	internal class ClassInfo(val irClass: IrClass, val subst: Subst, val type: IrSimpleType)

	private val mUnits = ArrayList<KUnit>()
	private val mFacades = HashMap<IrFile, KUnit>()
	private val mInterfaceUnits = HashMap<IrClass, KUnit>()
	private val mDispatchUnit = KUnit("", "KtDispatch")
	private val mRuntimeUnit = KUnit("", "ktc2_core")
	private val mFunctions = HashMap<Mono, KFunction?>()
	private val mStructs = HashMap<IrClass, KStruct>()
	private val mClasses = HashMap<Mono, KClass?>()
	private val mClassInfo = HashMap<KClass, ClassInfo>()
	private val mClassList = ArrayList<KClass>()
	private val mInterfaces = HashMap<IrClass, KInterface>()
	private val mFields = HashMap<Pair<IrField, KClass?>, KField>()
	private val mGlobals = HashMap<IrField, KGlobal>()
	private val mGlobalGetters = HashMap<IrFunction, KGlobal>()
	private val mGlobalSetters = HashMap<IrFunction, KGlobal>()
	private val mOverloads = HashMap<String, Int>()
	private val mPending = ArrayDeque<() -> Unit>()
	private val mInstantiated = LinkedHashSet<KClass>()

	private class Dispatcher(val function: IrSimpleFunction, val subst: Subst, val classArguments: List<KType>, val target: KFunction)

	private val mDispatchers = LinkedHashMap<Mono, Dispatcher>()
	private val mDispatchDone = HashSet<Pair<Mono, KClass>>()
	private val mRuntime = HashMap<String, KFunction>()
	private val mObjectStatics = HashMap<IrClass, KStatic>()
	private val mEnumStatics = HashMap<IrEnumEntry, KStatic>()
	private val mEnumEntries = HashMap<IrClass, List<KStatic>>()
	private var mEnumBase: KClass? = null
	private val mEnumFunctions = HashMap<String, KFunction?>()

	/** A closure class and the function type it implements (`FunctionN` with these type arguments). */
	private class Closure(val functionClass: IrClass, val arguments: List<KType>, val cls: KClass, val invoke: KFunction)

	private val mClosures = ArrayList<Closure>()
	private val mCells = HashMap<KType, Pair<KClass, KField>>()

	internal val captures = CaptureAnalysis()

	/** Queues [inTask] (a body to import) for the reachability loop. */
	internal fun later(inTask: () -> Unit) {
		mPending += inTask
	}

	// ==================
	// MARK: Closures
	// ==================

	/** The class of the cell holding a captured mutable variable of type [inType], and its `value` field. */
	internal fun cellFor(inType: KType): Pair<KClass, KField> = mCells.getOrPut(inType) {
		val vUnit = KUnit("ktc2", "Cell_${mangle(inType)}")
		val vClass = KClass("ktc2_Cell_${mangle(inType)}", vUnit, null, "Cell")
		vUnit.cls = vClass
		val vField = KField("value", inType, vClass)
		vClass.fields += vField
		mClassList += vClass
		mUnits += vUnit
		instantiate(vClass)
		vClass to vField
	}

	/** A new class for a lambda declared in [inFile], with one field per captured value. */
	internal fun newClosureClass(inFile: IrFile, inCaptures: List<Pair<String, KType>>): KClass {
		val vFacade = facadeFor(inFile)
		val vNumber = mClosures.size + mClassList.count { it.name == "lambda" }
		val vUnit = KUnit(vFacade.pkg, "${vFacade.name}.lambda$vNumber")
		val vClass = KClass("${cPrefix(vFacade.pkg)}${vFacade.name}_lambda$vNumber", vUnit, null, "lambda")
		vUnit.cls = vClass
		val vNames = HashSet<String>()
		for ((vName, vType) in inCaptures) {
			var vUnique = cIdentifier(vName)
			while (!vNames.add(vUnique)) vUnique += "_"
			vClass.fields += KField(vUnique, vType, vClass, isImmutable = true)
		}
		mClassList += vClass
		mUnits += vUnit
		instantiate(vClass)
		return vClass
	}

	/** Makes [inClass] callable as a function value through [inInvoke] (its first parameter is the closure). */
	internal fun registerClosure(inClass: KClass, inInvoke: KFunction) {
		fun shared(inType: KType): KType = if (inType is KRefType) KObjType else inType
		val vParams = inInvoke.params.drop(1)
		val vArguments = vParams.map { shared(it.type) } + shared(inInvoke.returnType)
		mClosures += Closure(mBuiltIns.functionN(vParams.size), vArguments, inClass, inInvoke)
	}

	// ==================
	// MARK: Entry
	// ==================

	/** The functions of the platform layer's `ktc2.internal` package, by name. */
	private val mInternal = HashMap<String, IrSimpleFunction>()

	/** A function of `ktc2.internal`, which library calls without a Kotlin body are redirected to. */
	internal fun internalFunction(inName: String): IrSimpleFunction? = mInternal[inName]

	/** Top-level functions that have a body in the module, by qualified name and parameter types. */
	private val mSourceFunctions = HashMap<String, IrSimpleFunction>()

	private fun sourceKey(inFunction: IrSimpleFunction): String =
		inFunction.kotlinFqName.asString() + inFunction.parameters.joinToString(",", "(", ")") { it.type.render() }

	/**
	 * The function a call to [inFunction] should run: when the module has a function with a
	 * body under the same name and signature, that one — the source always wins over the jar,
	 * and an `actual` over its `expect`.
	 */
	internal fun sourceOf(inFunction: IrSimpleFunction): IrSimpleFunction {
		if (inFunction.parent is IrClass || (inFunction.body != null && inFunction.fileOrNull != null)) return inFunction
		return mSourceFunctions[sourceKey(inFunction)] ?: inFunction
	}

	fun import(inModule: IrModuleFragment): KModule {
		for (vFile in inModule.files) countOverloads(vFile.declarations)
		for (vFile in inModule.files) {
			for (vFunction in vFile.declarations.filterIsInstance<IrSimpleFunction>()) {
				if (vFunction.body != null || vFunction.isExternal) mSourceFunctions[sourceKey(vFunction)] = vFunction
			}
		}
		mPointerType = inModule.files.filter { it.packageFqName.asString() == "kotlinx.cinterop" }
			.firstNotNullOfOrNull { vFile -> vFile.declarations.filterIsInstance<IrClass>().firstOrNull { it.name.asString() == "NativePtr" } }
			?.symbol?.defaultType
		for (vFile in inModule.files.filter { it.packageFqName.asString() == "ktc2.internal" }) {
			vFile.declarations.filterIsInstance<IrSimpleFunction>().forEach { mInternal[it.name.asString()] = it }
		}
		val vMain = inModule.files.filter { !it.packageFqName.asString().startsWith("kotlin") }.firstNotNullOfOrNull { vFile ->
			vFile.declarations.filterIsInstance<IrSimpleFunction>().firstOrNull { it.name.asString() == "main" && it.parameters.isEmpty() }
		}
		val vEntry = vMain?.let { functionFor(it) }

		// Reachability fixpoint: bodies reach new functions and classes, new classes add
		// implementations to the virtual calls already seen.
		do {
			while (mPending.isNotEmpty()) mPending.removeFirst().invoke()
		} while (resolveDispatch() || mPending.isNotEmpty())

		if (mDispatchUnit.functions.isNotEmpty()) mUnits += mDispatchUnit
		return KModule(mUnits, vEntry, mClassList).also { it.usesThreads = kThreadStart in mRuntime }
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

	private val mPrimArrays = mapOf(
		"kotlin.BooleanArray" to KPrim.BOOLEAN, "kotlin.ByteArray" to KPrim.BYTE, "kotlin.ShortArray" to KPrim.SHORT,
		"kotlin.IntArray" to KPrim.INT, "kotlin.LongArray" to KPrim.LONG, "kotlin.CharArray" to KPrim.CHAR,
		"kotlin.FloatArray" to KPrim.FLOAT, "kotlin.DoubleArray" to KPrim.DOUBLE,
	)

	/** [inType] with the type parameters of [inSubst] replaced. */
	internal fun close(inType: IrType, inSubst: Subst): IrType = if (inSubst.isEmpty()) inType else inType.substitute(inSubst)

	/** The type arguments of [inType]; a star projection counts as `Any?`. */
	internal fun argumentsOf(inType: IrType): List<IrType> =
		(inType as? IrSimpleType)?.arguments?.map { it.typeOrNull ?: mBuiltIns.anyNType } ?: emptyList()

	/** The array type of [inType] (`Array<T>` or a primitive array), or null when it is not an array. */
	private fun arrayTypeOf(inType: IrType): KArrayType? {
		val vName = inType.classOrNull?.owner?.kotlinFqName?.asString() ?: return null
		mPrimArrays[vName]?.let { return KArrayType(KPrimType(it)) }
		val vElement = argumentsOf(inType).firstOrNull()?.let { kTypeOf(it) } ?: return null
		if (vElement == KUnitType || vElement == KNothingType) return null
		return when (vName) {
			// As in Kotlin, Array<Int> holds boxed values (IntArray is the unboxed one): code may
			// cast freely between Array<T> and Array<Comparable<T>>.
			"kotlin.Array" -> KArrayType(if (vElement is KPrimType || vElement is KOptType) KObjType else vElement)
			// The platform layer's storage block keeps its elements as they are, unboxed.
			"ktc2.internal.Storage" -> KArrayType(vElement)
			else -> null
		}
	}

	/**
	 * The KIR type of [inType] under [inSubst], or null when it is not supported (the caller
	 * reports where). A type parameter left unbound is treated as `Any?`.
	 */
	internal fun kTypeOf(inType: IrType, inSubst: Subst = emptyMap()): KType? {
		val vType = close(inType, inSubst)
		primOf(vType)?.let { return KPrimType(it) }
		val vBase = vType.makeNotNull()
		if (vType.isMarkedNullable()) primOf(vBase)?.let { return KOptType(it) }
		return when {
			vBase.isUnit() -> KUnitType
			vBase.isNothing() -> if (vType.isMarkedNullable()) KObjType else KNothingType
			vBase.isString() -> KStringType
			vBase.isAny() -> KObjType
			vBase.classifierOrNull is IrTypeParameterSymbol -> KObjType
			else -> {
				arrayTypeOf(vBase)?.let { return it }
				val vClass = vBase.classOrNull?.owner ?: return null
				when {
					// A C struct held by value: a counted block of bytes with the struct inside.
					vClass.kotlinFqName.asString() == "kotlinx.cinterop.CValue" -> KArrayType(KPrimType(KPrim.BYTE))
					isPointerClass(vClass) -> KPtrType
					vClass.kotlinFqName.asString() == "kotlin.Enum" -> KClassType(enumBase())
					vClass.isInterface -> KObjType
					isStruct(vClass) -> structFor(vClass)?.let { KStructType(it) }
					else -> classFor(vClass, argumentsOf(vBase))?.let { KClassType(it) }
				}
			}
		}
	}

	/** A type argument as used to pick an instantiation: every reference type is `Any?`. */
	private fun canonical(inType: IrType): IrType = when (kTypeOf(inType)) {
		is KRefType -> mBuiltIns.anyNType
		// Every C address type shares one instantiation too, without being boxed.
		KPtrType -> mPointerType ?: inType
		else -> inType
	}

	/** `kotlinx.cinterop.NativePtr`, standing for every C address type as a type argument. */
	private var mPointerType: IrType? = null

	private fun isStruct(inClass: IrClass): Boolean =
		inClass.annotations.any { it.type.classFqName?.asString() == "ktc2.Struct" }

	/** The dotted name of [inClass] within its file; an anonymous object is called "object". */
	private fun nestedName(inClass: IrClass): String =
		generateSequence(inClass) { it.parent as? IrClass }.toList().reversed().joinToString(".") { vClass ->
			if (vClass.name.isSpecial) "object" else vClass.name.asString()
		}

	/** [inType] seen as its supertype [inTarget], with that class's type arguments filled in. */
	private fun findSuperType(inType: IrType, inTarget: IrClass): IrSimpleType? {
		val vType = inType.makeNotNull() as? IrSimpleType ?: return null
		val vClass = vType.classOrNull?.owner ?: return null
		if (vClass == inTarget) return vType
		val vSubst = vClass.typeParameters.map { it.symbol }.zip(argumentsOf(vType)).toMap()
		return vClass.superTypes.firstNotNullOfOrNull { findSuperType(close(it, vSubst), inTarget) }
	}

	/** The type arguments [inTarget] has when a value of [inType] is seen as it (`Any?` when unknown). */
	internal fun superArguments(inType: IrType?, inTarget: IrClass): List<IrType> =
		inType?.let { findSuperType(it, inTarget) }?.let { argumentsOf(it) } ?: inTarget.typeParameters.map { mBuiltIns.anyNType }

	/** The substitution for a call to [inFunction]: its class's type arguments, then its own. */
	internal fun substFor(inFunction: IrFunction, inClassArguments: List<IrType>, inOwnArguments: List<IrType?>): Subst {
		val vOwner = inFunction.parent as? IrClass
		val vSubst = HashMap<IrTypeParameterSymbol, IrType>()
		vOwner?.typeParameters?.forEachIndexed { vIndex, vParam ->
			vSubst[vParam.symbol] = canonical(inClassArguments.getOrNull(vIndex) ?: mBuiltIns.anyNType)
		}
		if (inFunction !is IrConstructor) {
			inFunction.typeParameters.forEachIndexed { vIndex, vParam ->
				vSubst[vParam.symbol] = canonical(inOwnArguments.getOrNull(vIndex) ?: mBuiltIns.anyNType)
			}
		}
		return vSubst
	}

	private fun ownParameters(inFunction: IrFunction): List<IrTypeParameter> =
		if (inFunction is IrConstructor) emptyList() else inFunction.typeParameters

	private fun monoOf(inFunction: IrFunction, inSubst: Subst, inOwner: KClass? = null): Mono {
		val vParams = ((inFunction.parent as? IrClass)?.typeParameters ?: emptyList()) + ownParameters(inFunction)
		val vArguments = vParams.map { kTypeOf(inSubst[it.symbol] ?: mBuiltIns.anyNType) ?: KObjType }
		// A local class is declared anew each time its declaration is lowered (once per expansion
		// of an inline function): its members belong to that particular class.
		val vLocal = inOwner?.takeIf { it in mLocalCaptures } ?: (inFunction.parent as? IrClass)?.let { mLatestLocalClass[it] }
		return Mono(inFunction, if (vLocal == null) vArguments else vArguments + KClassType(vLocal))
	}

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
			mPending += { BodyBuilder(this, vInit, null, inFile, emptyMap()).buildGlobalInitializer(vStartup) }
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
			mFields[vBacking to null] = vField
		}
		return vStruct
	}

	/**
	 * The reference class for [inClass] with [inArguments] for its type parameters, declared
	 * (layout only) on first use.
	 */
	internal fun classFor(inClass: IrClass, inArguments: List<IrType> = emptyList(), inFresh: Boolean = false): KClass? {
		if (isThrowableClass(inClass)) return throwableBase()
		val vArguments = inClass.typeParameters.indices.map { canonical(inArguments.getOrNull(it) ?: mBuiltIns.anyNType) }
		val vKey = Mono(inClass, vArguments.map { kTypeOf(it) ?: KObjType })
		if (vKey in mClasses && !inFresh) return mClasses[vKey]
		val vFile = inClass.fileOrNull
		if (vFile == null || inClass.isInterface) {
			mClasses[vKey] = null
			return null
		}
		mClasses[vKey] = null
		val vSubst = inClass.typeParameters.map { it.symbol }.zip(vArguments).toMap()
		val vSuperType = inClass.superTypes.firstOrNull { vType -> vType.classOrNull?.owner?.let { !it.isInterface && !isAnyClass(it) } == true }
		val vSuperIr = vSuperType?.classOrNull?.owner
		val vSuper = vSuperIr?.let {
			if (it.kotlinFqName.asString() == "kotlin.Enum") enumBase() else classFor(it, argumentsOf(close(vSuperType, vSubst)))
		}
		if (vSuperIr != null && vSuper == null) {
			unsupported(vFile, inClass, "superclass '${vSuperIr.kotlinFqName}'")
			return null
		}
		var vSuffix = if (vKey.arguments.isEmpty()) "" else "__" + vKey.arguments.joinToString("_") { mangle(it) }
		// A local class has no name of its own that is unique in the program.
		if (inClass.parent !is IrClass && inClass.parent !is IrFile) vSuffix += "__local${mClassList.size}"
		val vUnit = KUnit(vFile.packageFqName.asString(), nestedName(inClass) + vSuffix)
		val vClass = KClass(cPrefix(vUnit.pkg) + cSuffix(nestedName(inClass)) + vSuffix, vUnit, vSuper, jvmNameOf(inClass))
		vUnit.cls = vClass
		mClasses[vKey] = vClass
		mClassInfo[vClass] = ClassInfo(inClass, vSubst, inClass.symbol.typeWith(vArguments))
		mClassList += vClass
		mUnits += vUnit

		for (vProperty in inClass.declarations.filterIsInstance<IrProperty>()) {
			val vBacking = vProperty.backingField ?: continue
			val vType = kTypeOf(vBacking.type, vSubst)
			if (vType == null || vType == KUnitType || vType == KNothingType) {
				unsupported(vFile, vProperty, "property of type '${vBacking.type.render()}'")
				continue
			}
			val vIsWeak = isWeak(vProperty, vBacking, vType, vFile)
			val vField = KField(cIdentifier(vProperty.name.asString()), if (vIsWeak) KObjType else vType, vClass, isImmutable = !vProperty.isVar)
			if (vIsWeak) vField.weakType = vType
			vField.isAcyclic = vProperty.annotations.any { it.type.classFqName?.asString() == "ktc2.Acyclic" }
			inlineSizeOf(vProperty, vBacking, vType, vClass, vFile)?.let { vField.inlineSize = it }
			vClass.fields += vField
			mFields[vBacking to vClass] = vField
		}
		collectInterfaces(inClass, vClass.interfaces)

		if (inClass.isObject) {
			val vInstance = KStatic("${vClass.cName}_instance", vClass, vUnit)
			vUnit.statics += vInstance
			mObjectStatics[inClass] = vInstance
			instantiate(vClass)
			val vConstructor = inClass.declarations.filterIsInstance<IrConstructor>().firstOrNull()
			val vInit = vConstructor?.let { functionFor(it) }
			if (vInit != null) {
				val vStartup = KFunction("${vClass.cName}_static_init", vUnit, emptyList(), KUnitType)
				vStartup.body += KEval(KCall(vInit, listOf(KStaticRead(vInstance))))
				vUnit.initializer = vStartup
			}
		}
		if (inClass.kind == ClassKind.ENUM_CLASS) declareEnumEntries(inClass, vClass, vFile)
		return vClass
	}

	/** True for a property marked `@Weak`, which has to be a nullable reference. */
	private fun isWeak(inProperty: IrProperty, inBacking: IrField, inType: KType, inFile: IrFile): Boolean {
		if (inProperty.annotations.none { it.type.classFqName?.asString() == "ktc2.Weak" }) return false
		if (inType !is KRefType || !inBacking.type.isMarkedNullable()) {
			unsupported(inFile, inProperty, "@Weak on '${inProperty.name}' (it needs a nullable reference type)")
			return false
		}
		return true
	}

	/** The runtime function giving the weak cell of an object. */
	internal fun weakCellFunction(): KFunction = runtime("ktc2_weakCell", listOf(KObjType), KObjType)

	/** The runtime function reading the target of a weak cell. */
	internal fun weakGetFunction(): KFunction = runtime("ktc2_weakGet", listOf(KObjType), KObjType)

	/**
	 * The inline storage a property asks for with `@Value` (0) or `@Size(N)` (N), on its type or
	 * on the property itself; null for an ordinary field.
	 */
	private fun inlineSizeOf(inProperty: IrProperty, inBacking: IrField, inType: KType, inOwner: KClass, inFile: IrFile): Int? {
		val vAnnotations = inBacking.type.annotations + inProperty.annotations + inBacking.annotations
		fun find(inName: String) = vAnnotations.firstOrNull { it.type.classFqName?.asString() == inName }
		val vSize = find("ktc2.Size")
		if (vSize != null) {
			val vCount = (vSize.arguments.firstOrNull() as? IrConst)?.value as? Int
			if (inType !is KArrayType || vCount == null || vCount < 1 || inBacking.type.isMarkedNullable()) {
				unsupported(inFile, inProperty, "@Size on '${inProperty.name}' (it needs a non-null array type and a positive constant)")
				return null
			}
			return vCount
		}
		if (find("ktc2.Value") != null) {
			val vClass = (inType as? KClassType)?.cls
			val vIr = inBacking.type.classOrNull?.owner
			if (vClass == null || vIr == null || vIr.modality != Modality.FINAL || vClass === inOwner || inBacking.type.isMarkedNullable()) {
				unsupported(inFile, inProperty, "@Value on '${inProperty.name}' (it needs a non-null final class other than its owner)")
				return null
			}
			vClass.isUsedInline = true
			return 0
		}
		return null
	}

	internal fun infoOf(inClass: KClass): ClassInfo? = mClassInfo[inClass]

	/** What a local class takes from the function that declares it. */
	internal class LocalCaptures(
		/** Outer values, each held in a field of the object. */
		val bindings: List<BodyBuilder.Binding>,
		/** Lambdas bound to `inline` parameters that its members call: expanded in those members. */
		val lambdas: Map<IrValueDeclaration, BodyBuilder.InlineLambda>,
	)

	private val mLocalCaptures = HashMap<KClass, LocalCaptures>()
	private val mLatestLocalClass = HashMap<IrClass, KClass>()

	/**
	 * Declares the local class [inClass] with one extra field per captured outer value.
	 *
	 * @param inCaptures each captured declaration with its name, the type of the local that
	 *   holds it in the declaring function, and the cell field when it is a shared variable
	 * @return the fields to fill when an instance is created, in the order of [inCaptures]
	 */
	internal fun declareLocalClass(
		inClass: IrClass,
		inCaptures: List<Triple<IrValueDeclaration, KType, KField?>>,
		inLambdas: Map<IrValueDeclaration, BodyBuilder.InlineLambda>,
	): List<KField>? {
		val vClass = classFor(inClass, inFresh = true) ?: return null
		mLatestLocalClass[inClass] = vClass
		val vNames = vClass.fields.map { it.cName }.toHashSet()
		val vBindings = inCaptures.mapIndexed { vIndex, (vDeclaration, vType, vCell) ->
			var vName = "captured$vIndex"
			while (!vNames.add(vName)) vName += "_"
			val vField = KField(vName, vType, vClass)
			vClass.fields += vField
			BodyBuilder.Binding(vDeclaration, vField, vCell, null)
		}
		mLocalCaptures[vClass] = LocalCaptures(vBindings, inLambdas)
		return vBindings.map { it.field }
	}

	internal fun localBindingsOf(inClass: KClass): List<BodyBuilder.Binding> = mLocalCaptures[inClass]?.bindings ?: emptyList()

	/** The instance of an `object` declaration. */
	internal fun objectFor(inClass: IrClass): KStatic? {
		classFor(inClass)
		return mObjectStatics[inClass]
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
	internal fun instantiate(inClass: KClass) {
		if (mInstantiated.add(inClass)) inClass.isInstantiated = true
	}

	/** The field behind [inField] in class instance [inClass] (or in its `@Struct` when null). */
	internal fun fieldIn(inClass: KClass?, inField: IrField): KField? = mFields[inField to inClass]

	/**
	 * The field behind [inField] for a receiver of type [inReceiver] (already closed): the
	 * receiver's view of the declaring class picks the instantiation.
	 */
	internal fun fieldFor(inField: IrField, inReceiver: IrType?): KField? {
		val vOwner = inField.parent as? IrClass ?: return null
		if (isStruct(vOwner)) {
			structFor(vOwner)
			return mFields[inField to null]
		}
		val vClass = classFor(vOwner, superArguments(inReceiver, vOwner)) ?: return null
		return mFields[inField to vClass]
	}

	// ==================
	// MARK: Throwable
	// ==================

	private var mThrowableBase: KClass? = null
	private val mThrowableFunctions = HashMap<String, KFunction?>()

	lateinit var throwableMessageField: KField
		private set
	lateinit var throwableCauseField: KField
		private set

	/**
	 * The name a class reports at run time (`toString`, an uncaught exception): its qualified
	 * name, and for the standard exceptions the `java.lang` / `java.util` name they have on
	 * the JVM, so that messages read the same.
	 */
	private fun jvmNameOf(inClass: IrClass): String {
		val vName = inClass.kotlinFqName.asString()
		if (inClass.fileOrNull?.packageFqName?.asString() != "kotlin" || inClass.parent !is IrFile) return vName
		return when (val vSimple = inClass.name.asString()) {
			"NoSuchElementException", "ConcurrentModificationException" -> "java.util.$vSimple"
			"Error", "Exception", "RuntimeException", "IllegalArgumentException", "IllegalStateException", "IndexOutOfBoundsException",
			"UnsupportedOperationException", "NumberFormatException", "NullPointerException", "ClassCastException", "AssertionError",
			"ArithmeticException" -> "java.lang.$vSimple"
			else -> vName
		}
	}

	/** `kotlin.Throwable`: the base of every exception, holding `message` and `cause` (same layout as `ktc2_Throwable`). */
	internal fun throwableBase(): KClass {
		mThrowableBase?.let { return it }
		val vUnit = KUnit("kotlin", "Throwable")
		val vClass = KClass("kotlin_Throwable", vUnit, null, "java.lang.Throwable")
		vUnit.cls = vClass
		mThrowableBase = vClass
		throwableMessageField = KField("message", KStringType, vClass, isImmutable = true)
		throwableCauseField = KField("cause", KClassType(vClass), vClass, isImmutable = true)
		vClass.fields += throwableMessageField
		vClass.fields += throwableCauseField
		mClassList += vClass
		mUnits += vUnit
		return vClass
	}

	/** `Throwable.toString` when [inImplementation] is `Any.toString` reached on an exception class, else null. */
	internal fun throwableToStringFor(inClass: KClass?, inImplementation: IrFunction): KFunction? {
		val vBase = mThrowableBase ?: return null
		if (inClass == null || inImplementation.name.asString() != "toString" || !inClass.isSubclassOf(vBase)) return null
		return throwableFunction("toString")
	}

	/** The members of `kotlin.Throwable`, written directly in KIR. */
	internal fun throwableFunction(inName: String): KFunction? = mThrowableFunctions.getOrPut(inName) {
		val vClass = throwableBase()
		val vSelf = KLocal("self", KClassType(vClass))
		fun define(inSuffix: String, inReturn: KType, inValue: KExpr): KFunction =
			KFunction("kotlin_Throwable_$inSuffix", vClass.unit, listOf(vSelf), inReturn).also {
				it.body += KReturn(inValue)
				vClass.unit.functions += it
			}
		when (inName) {
			"<get-message>" -> define("message", KStringType, KFieldRead(KRead(vSelf), throwableMessageField))
			"<get-cause>" -> define("cause", KClassType(vClass), KFieldRead(KRead(vSelf), throwableCauseField))
			"toString" -> {
				val vDescribe = runtime("ktc2_throwable_toString", listOf(KObjType, KStringType), KStringType)
				define("toString", KStringType, KCall(vDescribe, listOf(KRefCast(KRead(vSelf), KObjType), KFieldRead(KRead(vSelf), throwableMessageField))))
			}
			else -> anyDefault(inName)
		}
	}

	// ==================
	// MARK: Enums
	// ==================

	lateinit var enumNameField: KField
		private set
	lateinit var enumOrdinalField: KField
		private set

	/** `kotlin.Enum`: the common base of enum classes, holding `name` and `ordinal`. */
	internal fun enumBase(): KClass {
		mEnumBase?.let { return it }
		val vUnit = KUnit("kotlin", "Enum")
		val vClass = KClass("kotlin_Enum", vUnit, null, "Enum")
		vUnit.cls = vClass
		enumNameField = KField("name", KStringType, vClass)
		enumOrdinalField = KField("ordinal", KPrimType(KPrim.INT), vClass)
		vClass.fields += enumNameField
		vClass.fields += enumOrdinalField
		mEnumBase = vClass
		mClassList += vClass
		mUnits += vUnit
		return vClass
	}

	/** The members of `kotlin.Enum`, written directly in KIR. */
	private fun enumFunction(inName: String): KFunction? = mEnumFunctions.getOrPut(inName) {
		val vClass = enumBase()
		val vSelf = KLocal("self", KClassType(vClass))
		fun define(inSuffix: String, inParams: List<KLocal>, inReturn: KType, inValue: KExpr): KFunction =
			KFunction("kotlin_Enum_$inSuffix", vClass.unit, inParams, inReturn).also {
				it.body += KReturn(inValue)
				vClass.unit.functions += it
			}
		val vInt = KPrimType(KPrim.INT)
		when (inName) {
			"toString", "<get-name>" -> define(cSuffix(inName), listOf(vSelf), KStringType, KFieldRead(KRead(vSelf), enumNameField))
			"<get-ordinal>" -> define("ordinal", listOf(vSelf), vInt, KFieldRead(KRead(vSelf), enumOrdinalField))
			"compareTo" -> {
				val vOther = KLocal("other", KClassType(vClass))
				val vCompare = KBinary(KBinaryOp.SUB, KFieldRead(KRead(vSelf), enumOrdinalField), KFieldRead(KRead(vOther), enumOrdinalField), KPrim.INT, vInt)
				define("compareTo", listOf(vSelf, vOther), vInt, vCompare)
			}
			else -> anyDefault(inName)
		}
	}

	/** Declares one static instance per entry and the startup function that constructs them. */
	private fun declareEnumEntries(inClass: IrClass, inEnum: KClass, inFile: IrFile) {
		val vEntries = inClass.declarations.filterIsInstance<IrEnumEntry>()
		val vStatics = ArrayList<KStatic>()
		for (vEntry in vEntries) {
			val vEntryClass = vEntry.correspondingClass?.let { classFor(it) } ?: inEnum
			instantiate(vEntryClass)
			val vStatic = KStatic("${inEnum.cName}__${cSuffix(vEntry.name.asString())}", vEntryClass, inEnum.unit)
			inEnum.unit.statics += vStatic
			mEnumStatics[vEntry] = vStatic
			vStatics += vStatic
		}
		mEnumEntries[inClass] = vStatics
		val vStartup = KFunction("${inEnum.cName}_static_init", inEnum.unit, emptyList(), KUnitType)
		inEnum.unit.initializer = vStartup
		mPending += { BodyBuilder(this, vStartup, null, inFile, emptyMap()).buildEnumInitializer(vEntries, vStatics) }
	}

	internal fun enumEntryFor(inEntry: IrEnumEntry): KStatic? {
		classFor(inEntry.parentAsClass)
		return mEnumStatics[inEntry]
	}

	/** The entries of an enum class, in declaration order. */
	internal fun enumEntriesOf(inClass: IrClass): List<KStatic> {
		classFor(inClass)
		return mEnumEntries[inClass] ?: emptyList()
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

	private fun signatureOf(inFunction: IrFunction, inFile: IrFile?, inSubst: Subst): Signature? {
		var vOk = true
		val vNames = HashSet<String>()
		val vParams = ArrayList<KLocal>()
		for (vParam in inFunction.parameters) {
			// A parameter of type Nothing can never receive a value; it is declared as a plain reference.
			val vType = kTypeOf(vParam.type, inSubst).let { if (it == KNothingType) KObjType else it }
			if (vType == null || vType == KUnitType) {
				unsupported(inFile, vParam, "parameter type '${vParam.type.render()}'")
				vOk = false
				continue
			}
			var vName = if (vParam.kind == IrParameterKind.Regular) cIdentifier(vParam.name.asString()) else "self"
			while (!vNames.add(vName)) vName += "_"
			vParams += KLocal(vName, vType)
		}
		val vReturn = kTypeOf(inFunction.returnType, inSubst)
		if (vReturn == null) {
			unsupported(inFile, inFunction, "return type '${inFunction.returnType.render()}'")
			return null
		}
		return if (vOk) Signature(vParams, vReturn) else null
	}

	/** How many functions share the qualified name of [inFunction]. */
	private fun overloadCount(inFunction: IrFunction): Int {
		val vOwner = inFunction.parent as? IrClass
		// A library class is not part of the module scan: count among its own members.
		if (vOwner != null && vOwner.fileOrNull == null) {
			return vOwner.declarations.count { it is IrFunction && it.name == inFunction.name }
		}
		return mOverloads[inFunction.kotlinFqName.asString()] ?: 0
	}

	/** The class or type-parameter name of [inType], for mangled names. */
	private fun shortName(inType: IrType): String {
		val vName = inType.classOrNull?.owner?.name?.asString() ?: (inType.classifierOrNull?.owner as? IrTypeParameter)?.name?.asString() ?: "Any"
		return cSuffix(vName) + if (inType.isMarkedNullable()) "Opt" else ""
	}

	private val mUsedNames = HashSet<String>()

	/** [inName], or a numbered variant when another function already took it. */
	private fun uniqueName(inName: String): String {
		var vName = inName
		var vNumber = 2
		while (!mUsedNames.add(vName)) vName = "${inName}_${vNumber++}"
		return vName
	}

	/** Suffix telling apart overloads and the instantiations of a generic function. */
	private fun nameSuffix(inFunction: IrFunction, inSubst: Subst): String {
		var vSuffix = ""
		if (overloadCount(inFunction) > 1) {
			// Overloads can differ by their receiver alone (Iterable.first / List.first).
			val vParams = inFunction.parameters.filter { it.kind != IrParameterKind.DispatchReceiver }
			vSuffix += "__" + vParams.joinToString("_") { shortName(it.type) }
		}
		val vOwn = ownParameters(inFunction)
		if (vOwn.isNotEmpty()) {
			vSuffix += "__" + vOwn.joinToString("_") { mangle(kTypeOf(inSubst[it.symbol] ?: mBuiltIns.anyNType) ?: KObjType) }
		}
		return vSuffix
	}

	/**
	 * A member as it is actually implemented for a receiver of type [inReceiver]: a fake
	 * override is followed to the real function, and the substitution covers that function's
	 * class.
	 */
	internal fun resolveMember(inFunction: IrSimpleFunction, inReceiver: IrType?, inOwnArguments: List<IrType?>): Pair<IrSimpleFunction, Subst> {
		val vReal = if (inFunction.isFakeOverride) inFunction.resolveFakeOverride() ?: inFunction else inFunction
		return vReal to substFor(vReal, superArguments(inReceiver, vReal.parentAsClass), inOwnArguments)
	}

	/**
	 * The KIR function for [inFunction] under [inSubst], declared on first use with its body
	 * queued for import. [inFunction] must not be a fake override (see [resolveMember]).
	 */
	internal fun functionFor(inFunction: IrFunction, inSubst: Subst = emptyMap(), inOwnerClass: KClass? = null): KFunction? {
		val vKey = monoOf(inFunction, inSubst, inOwnerClass)
		if (vKey in mFunctions) return mFunctions[vKey]
		val vOwner = inFunction.parent as? IrClass
		if (vOwner != null && isAnyClass(vOwner)) {
			return anyDefault(inFunction.name.asString()).also { mFunctions[vKey] = it }
		}
		if (vOwner != null && vOwner.kotlinFqName.asString() == "kotlin.Enum") {
			return enumFunction(inFunction.name.asString()).also { mFunctions[vKey] = it }
		}
		if (vOwner != null && isThrowableClass(vOwner) && inFunction !is IrConstructor) {
			return throwableFunction(inFunction.name.asString()).also { mFunctions[vKey] = it }
		}
		val vFile = inFunction.fileOrNull
		val vIsDefaultAccessor = inFunction.origin == IrDeclarationOrigin.DEFAULT_PROPERTY_ACCESSOR
		mFunctions[vKey] = null
		if (inFunction.isExternal && vOwner == null) {
			// An `external` function is implemented by the C runtime under the name ktc2_<name>.
			val vExternal = signatureOf(inFunction, vFile, inSubst) ?: return null
			// The runtime starts a thread in `ktc2.internal.threadEntry`, which has to be generated then.
			if ("ktc2_${inFunction.name.asString()}" == kThreadStart) internalFunction("threadEntry")?.let { functionFor(it) }
			return runtime("ktc2_${inFunction.name.asString()}", vExternal.params.map { it.type }, vExternal.returnType).also { mFunctions[vKey] = it }
		}
		if (vFile == null || (inFunction.body == null && !vIsDefaultAccessor && inFunction !is IrConstructor)) return null
		val vSignature = signatureOf(inFunction, vFile, inSubst) ?: return null

		val vStruct = vOwner?.takeIf { isStruct(it) }?.let { structFor(it) }
		// A member of a C interop class takes the address as `self`; there is no object behind it.
		val vIsPointer = vOwner != null && isPointerClass(vOwner)
		val vClass = if (vOwner != null && vStruct == null && !vOwner.isInterface && !vIsPointer) {
			inOwnerClass ?: classFor(vOwner, vOwner.typeParameters.map { inSubst[it.symbol] ?: mBuiltIns.anyNType })
		} else {
			null
		}
		if (vOwner != null && vStruct == null && vClass == null && !vOwner.isInterface && !vIsPointer) {
			unsupported(vFile, vOwner, "class '${vOwner.name}'")
			return null
		}
		val vUnit = when {
			vStruct != null -> vStruct.unit
			vClass != null -> vClass.unit
			vIsPointer -> facadeFor(vFile)
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
		val vFunction = KFunction(uniqueName(vScope + vBase + nameSuffix(inFunction, inSubst)), vUnit, vParams, vReturn)
		vUnit.functions += vFunction
		mFunctions[vKey] = vFunction
		// The members of a local class read the values it captured from its fields.
		val vCaptures = vClass?.let { mLocalCaptures[it] }
		mPending += { BodyBuilder(this, vFunction, inFunction, vFile, inSubst).buildClosure(vCaptures?.bindings ?: emptyList(), null, vCaptures?.lambdas ?: emptyMap()) }
		return vFunction
	}

	// ==================
	// MARK: Virtual dispatch
	// ==================

	/**
	 * The dispatcher called for a virtual call to [inFunction] under [inSubst]; its targets are
	 * filled in by [resolveDispatch].
	 */
	internal fun dispatcherFor(inFunction: IrSimpleFunction, inSubst: Subst = emptyMap()): KFunction? {
		val vKey = monoOf(inFunction, inSubst)
		mDispatchers[vKey]?.let { return it.target }
		val vOwner = inFunction.parentAsClass
		val vFile = vOwner.fileOrNull
		val vSignature = signatureOf(inFunction, vFile, inSubst) ?: return null
		val vClassArguments = vOwner.typeParameters.map { inSubst[it.symbol] ?: mBuiltIns.anyNType }
		val vClass = if (vFile != null && !vOwner.isInterface) classFor(vOwner, vClassArguments) else null
		val vUnit = when {
			vClass != null -> vClass.unit
			vFile != null -> interfaceUnitFor(vOwner)
			else -> mDispatchUnit
		}
		val vScope = vClass?.cName ?: (cPrefix(vFile?.packageFqName?.asString() ?: vOwner.kotlinFqName.parent().asString()) + cSuffix(nestedName(vOwner)))
		val vGeneric = if (vClass == null && vKey.arguments.isNotEmpty()) "__" + vKey.arguments.joinToString("_") { mangle(it) } else ""
		val vName = uniqueName("${vScope}_${cSuffix(inFunction.name.asString())}${nameSuffix(inFunction, inSubst)}${vGeneric}__v")
		val vTarget = KFunction(vName, vUnit, vSignature.params, vSignature.returnType)
		vTarget.dispatch = mutableListOf()
		vTarget.dispatchDefault = anyMemberName(inFunction)?.let { anyDefault(it) }
		// A plain Throwable, created directly, has no class of its own in the module.
		if (isThrowableClass(vOwner)) vTarget.dispatchDefault = throwableFunction(inFunction.name.asString())
		// Comparable<T> on a reference T also receives strings and boxed primitives.
		val vIsCompare = vOwner.kotlinFqName.asString() == "kotlin.Comparable" && inFunction.name.asString() == "compareTo"
		if (vIsCompare && vSignature.params.all { it.type == KObjType }) {
			vTarget.dispatchDefault = runtime("ktc2_any_compareTo", listOf(KObjType, KObjType), KPrimType(KPrim.INT))
		}
		// A String is also a CharSequence.
		if (vOwner.kotlinFqName.asString() == "kotlin.CharSequence") {
			vTarget.dispatchDefault = when (inFunction.name.asString()) {
				"<get-length>" -> runtime("ktc2_cs_length", listOf(KObjType), KPrimType(KPrim.INT))
				"get" -> runtime("ktc2_cs_get", listOf(KObjType, KPrimType(KPrim.INT)), KPrimType(KPrim.CHAR))
				"subSequence" -> runtime("ktc2_cs_subSequence", listOf(KObjType, KPrimType(KPrim.INT), KPrimType(KPrim.INT)), KObjType)
				else -> vTarget.dispatchDefault
			}
		}
		vUnit.functions += vTarget
		mDispatchers[vKey] = Dispatcher(inFunction, inSubst, vClassArguments.map { kTypeOf(it) ?: KObjType }, vTarget)
		return vTarget
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
		for ((vKey, vDispatcher) in mDispatchers.entries.toList()) {
			val vFunction = vDispatcher.function
			val vOwner = vFunction.parentAsClass
			if (vFunction.name.asString() == "invoke") {
				for (vClosure in mClosures.toList()) {
					if (vClosure.functionClass != vOwner || !accepts(vDispatcher.classArguments, vClosure.arguments)) continue
					if (!mDispatchDone.add(vKey to vClosure.cls)) continue
					vDispatcher.target.dispatch!! += vClosure.cls to vClosure.invoke
					vChanged = true
				}
			}
			for (vConcrete in mInstantiated.toList()) {
				if (!mDispatchDone.add(vKey to vConcrete)) continue
				val vInfo = mClassInfo[vConcrete] ?: continue
				if (!isSubtype(vInfo.irClass, vOwner)) continue
				// A generic owner matches the instantiation the call was made on.
				val vSeenAs = superArguments(vInfo.type, vOwner).map { kTypeOf(canonical(it)) ?: KObjType }
				if (!accepts(vDispatcher.classArguments, vSeenAs)) continue
				val vMember = membersOf(vInfo.irClass).firstOrNull { it == vFunction || overrides(it, vFunction) } ?: continue
				val vOwnArguments = vFunction.typeParameters.map { vDispatcher.subst[it.symbol] }
				val (vImplementation, vSubst) = resolveMember(vMember, vInfo.type, vOwnArguments)
				if (isAnyClass(vImplementation.parentAsClass)) {
					// An exception that does not override toString() describes itself as "Class: message".
					val vDescribe = throwableToStringFor(vConcrete, vImplementation) ?: continue
					vDispatcher.target.dispatch!! += vConcrete to vDescribe
					vChanged = true
					continue
				}
				val vOwnerClass = vConcrete.takeIf { it in mLocalCaptures && vImplementation.parent == vInfo.irClass }
				val vTarget = functionFor(vImplementation, vSubst, vOwnerClass) ?: continue
				vDispatcher.target.dispatch!! += vConcrete to vTarget
				vChanged = true
			}
		}
		return vChanged
	}

	/**
	 * True when a dispatcher for type arguments [inDispatcher] can reach an implementation
	 * instantiated with [inActual]. A reference (`Any?`) argument of the dispatcher also stands
	 * for a star projection or an unknown type, so it accepts a primitive instantiation too:
	 * the dispatcher then boxes and unboxes at that boundary.
	 */
	private fun accepts(inDispatcher: List<KType>, inActual: List<KType>): Boolean =
		inDispatcher.size == inActual.size && inDispatcher.indices.all { inDispatcher[it] == inActual[it] || inDispatcher[it] == KObjType }

	/** True when a call to [inFunction] must go through a dispatcher. */
	internal fun isVirtual(inFunction: IrSimpleFunction): Boolean {
		val vOwner = inFunction.parent as? IrClass ?: return false
		if (isStruct(vOwner) || isPointerClass(vOwner)) return false
		return vOwner.isInterface || (inFunction.modality != Modality.FINAL && vOwner.modality != Modality.FINAL)
	}
}
