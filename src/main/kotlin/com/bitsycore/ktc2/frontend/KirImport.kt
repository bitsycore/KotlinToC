@file:OptIn(UnsafeDuringIrConstructionAPI::class)

package com.bitsycore.ktc2.frontend

import com.bitsycore.ktc2.kir.*
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrEnumConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrGetEnumValue
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
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

/** The functions of `ktc2.internal` that throw for the language's own checks. */
private val kThrowers = listOf("throwIndexOutOfBounds", "throwStringIndexOutOfBounds", "throwSubstringRange", "throwDivideByZero")

/**
 * The type parameters a class is instantiated with: its own, then - for an `inner` class,
 * whose members also use them - those of the classes around it. Types of the class list
 * their arguments in this order.
 */
internal fun classParameters(inClass: IrClass): List<IrTypeParameter> =
	inClass.typeParameters + if (inClass.isInner) classParameters(inClass.parentAsClass) else emptyList()

/**
 * True for a function declared in a `companion { }` block: it sits in its class but has no
 * receiver, so it is a top-level function in everything but its name.
 */
internal fun isStaticMember(inFunction: IrFunction): Boolean =
	inFunction is IrSimpleFunction && inFunction.parent is IrClass && inFunction.dispatchReceiverParameter == null &&
		inFunction.origin != IrDeclarationOrigin.ENUM_CLASS_SPECIAL_MEMBER

/** True for a property of a `companion { }` block: one variable for the whole program. */
internal fun isStaticProperty(inProperty: IrProperty): Boolean {
	val vClass = inProperty.parent as? IrClass ?: return false
	val vGetter = inProperty.getter
	if (vGetter != null) return isStaticMember(vGetter)
	return !vClass.isObject && inProperty.backingField?.isStatic == true
}

/**
 * True for the function the program starts with: `fun main()` or `fun main(args: Array<String>)`,
 * or, when the module file names one (`main = "app.start"`), that function.
 */
internal fun isEntryPoint(inFunction: IrSimpleFunction): Boolean {
	if (inFunction.parent !is IrFile) return false
	val vNamed = System.getProperty("ktc2.main")
	if (if (vNamed != null) inFunction.kotlinFqName.asString() != vNamed else inFunction.name.asString() != "main") return false
	val vParams = inFunction.parameters
	return vParams.isEmpty() || (vParams.size == 1 && vParams[0].type.classFqName?.asString() == "kotlin.Array")
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

	/** What the program most likely did not mean, though it compiles. */
	val warnings = linkedSetOf<String>()

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

	/** `ktc2.Deinit.deinit`, when the program is compiled with the `ktc2.` package. */
	private var mDeinit: IrSimpleFunction? = null

	/** The dispatcher of [mDeinit], made when the program creates an object that implements `Deinit`. */
	private var mDeinitDispatcher: KFunction? = null

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
	/**
	 * A lambda turned into an object. [functionClass] is the interface it stands for - a
	 * `FunctionN`, or the `fun interface` it was converted to ([isSam]), whose single abstract
	 * method is then what [invoke] implements.
	 */
	private class Closure(val functionClass: IrClass, val arguments: List<KType>, val cls: KClass, val invoke: KFunction, val isSam: Boolean = false)

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

	private var mStaticFunctions = 0

	/** A C function for the lambda [inLambda], which captures nothing: what `staticCFunction { }` points at. */
	internal fun staticFunctionFor(inLambda: IrSimpleFunction, inFile: IrFile, inSubst: Subst): KFunction? {
		val vSignature = signatureOf(inLambda, inFile, inSubst) ?: return null
		val vUnit = facadeFor(inFile)
		val vFunction = KFunction(uniqueName("${cPrefix(vUnit.pkg)}${vUnit.name}_cfunction${mStaticFunctions++}"), vUnit, vSignature.params, vSignature.returnType)
		vUnit.functions += vFunction
		mPending += { BodyBuilder(this, vFunction, inLambda, inFile, inSubst).buildClosure(emptyList(), null) }
		return vFunction
	}

	/**
	 * Registers the closure [inClass] as an implementation of the `fun interface` [inInterface]
	 * instantiated with [inArguments]: `Comparator { a, b -> … }`.
	 */
	internal fun registerSam(inClass: KClass, inInvoke: KFunction, inInterface: IrClass, inArguments: List<IrType>) {
		inClass.interfaces += interfaceFor(inInterface)
		mClosures += Closure(inInterface, inArguments.map { kTypeOf(canonical(it)) ?: KObjType }, inClass, inInvoke, isSam = true)
	}

	// ==================
	// MARK: Entry
	// ==================

	/** The functions of the platform layer's `ktc2.internal` package, by name. */
	private val mInternal = HashMap<String, IrSimpleFunction>()

	/** A function of `ktc2.internal`, which library calls without a Kotlin body are redirected to. */
	internal fun internalFunction(inName: String): IrSimpleFunction? = mInternal[inName]

	/** The classes of the platform layer's `ktc2.internal` package, by name. */
	private val mInternalClasses = HashMap<String, IrClass>()

	/** The object standing for each property a delegate is told about, by the name of the property. */
	private val mPropertyObjects = HashMap<String, KStatic>()

	/**
	 * The object a delegate receives as its `property` for a property called [inName]: one for
	 * the whole program, which exists from the start, instead of a new one at each access.
	 */
	internal fun propertyObjectFor(inName: String): KStatic? {
		mPropertyObjects[inName]?.let { return it }
		val vClass = mInternalClasses["PropertyReference"]?.let { classFor(it) } ?: return null
		val vField = vClass.fields.firstOrNull { it.cName == "name" } ?: return null
		instantiate(vClass)
		val vUnit = vClass.unit
		val vStatic = KStatic(uniqueName("${vClass.cName}_of_${cSuffix(inName)}"), vClass, vUnit)
		vUnit.statics += vStatic
		// Its name is put in when the program starts.
		val vStartup = vUnit.initializer ?: KFunction("${vClass.cName}_names_init", vUnit, emptyList(), KUnitType).also { vUnit.initializer = it }
		vStartup.body += KFieldSet(KStaticRead(vStatic), vField, KStringLit(inName))
		mPropertyObjects[inName] = vStatic
		return vStatic
	}

	/** Top-level functions that have a body in the module, by qualified name and parameter types. */
	private val mSourceFunctions = HashMap<String, IrSimpleFunction>()

	private fun sourceKey(inFunction: IrSimpleFunction): String =
		inFunction.kotlinFqName.asString() + inFunction.parameters.joinToString(",", "(", ")") { it.type.render() }

	/**
	 * The function a call to [inFunction] should run: when the module has a function with a
	 * body under the same name and signature, that one - the source always wins over the jar,
	 * and an `actual` over its `expect`.
	 */
	internal fun sourceOf(inFunction: IrSimpleFunction): IrSimpleFunction {
		if (inFunction.parent is IrClass || (inFunction.body != null && inFunction.fileOrNull != null)) return inFunction
		return mSourceFunctions[sourceKey(inFunction)] ?: inFunction
	}

	fun import(inModule: IrModuleFragment): KModule {
		for (vFile in inModule.files) countOverloads(vFile.declarations)
		for (vFile in inModule.files) {
			// Top-level functions, and the accessors of top-level properties (`val Long.sign get() = …`).
			val vAccessors = vFile.declarations.filterIsInstance<IrProperty>().flatMap { listOfNotNull(it.getter, it.setter) }
			for (vFunction in vFile.declarations.filterIsInstance<IrSimpleFunction>() + vAccessors) {
				if (vFunction.body != null || vFunction.isExternal) mSourceFunctions[sourceKey(vFunction)] = vFunction
			}
		}
		mPointerType = inModule.files.filter { it.packageFqName.asString() == "kotlinx.cinterop" }
			.firstNotNullOfOrNull { vFile -> vFile.declarations.filterIsInstance<IrClass>().firstOrNull { it.name.asString() == "NativePtr" } }
			?.symbol?.defaultType
		for (vFile in inModule.files.filter { it.packageFqName.asString() == "ktc2.internal" }) {
			vFile.declarations.filterIsInstance<IrSimpleFunction>().forEach { mInternal[it.name.asString()] = it }
			vFile.declarations.filterIsInstance<IrClass>().forEach { mInternalClasses[it.name.asString()] = it }
		}
		val vMain = inModule.files.filter { !it.packageFqName.asString().startsWith("kotlin") }.firstNotNullOfOrNull { vFile ->
			vFile.declarations.filterIsInstance<IrSimpleFunction>().firstOrNull { isEntryPoint(it) }
		}
		val vEntry = vMain?.let { functionFor(it) }
		mDeinit = inModule.files.filter { it.packageFqName.asString() == "ktc2" }
			.firstNotNullOfOrNull { vFile -> vFile.declarations.filterIsInstance<IrClass>().firstOrNull { it.name.asString() == "Deinit" } }
			?.declarations?.filterIsInstance<IrSimpleFunction>()?.firstOrNull { it.name.asString() == "deinit" }

		// Reachability fixpoint: bodies reach new functions and classes, new classes add
		// implementations to the virtual calls already seen.
		do {
			while (mPending.isNotEmpty()) mPending.removeFirst().invoke()
		} while (resolveDispatch() || mPending.isNotEmpty())

		// A program that indexes or divides needs the functions that throw when that fails.
		val vThrowers = HashMap<String, KFunction>()
		val vHasChecks = mUnits.any { vUnit ->
			vUnit.functions.any { vFunction ->
				vFunction.body.anyStatement { vStatement ->
					vStatement is KIndexSet || vStatement.ownExpressions().any { vRoot -> vRoot.anyNode { it.isRuntimeCheck() || (it is KCall && it.function.raisesInRuntime()) } }
				}
			}
		}
		if (vHasChecks && System.getProperty("ktc2.checks") != "abort") {
			for (vName in kThrowers) internalFunction(vName)?.let { functionFor(it) }?.let { vThrowers[vName] = it }
			do {
				while (mPending.isNotEmpty()) mPending.removeFirst().invoke()
			} while (resolveDispatch() || mPending.isNotEmpty())
		}

		settleDeinit()
		settleInitialization()
		if (mDispatchUnit.functions.isNotEmpty()) mUnits += mDispatchUnit
		return KModule(mUnits, vEntry, mClassList).also {
			it.usesThreads = kThreadStart in mRuntime
			if (vThrowers.size == kThrowers.size) it.throwers = vThrowers
		}
	}

	/**
	 * Gives each class the program creates what the runtime calls when an instance ends
	 * ([KClass.deinit]): the `deinit` the dispatcher of `Deinit.deinit` found for it, or, for a
	 * class with folded cleaners (its own or a superclass's), a function that calls that
	 * `deinit` and then each cleanup action with its resource, the most derived class first.
	 */
	private fun settleDeinit() {
		val vDeinits = mDeinitDispatcher?.dispatch?.toMap() ?: emptyMap()
		val vChecked = HashSet<IrClass>()
		for (vClass in mInstantiated.toList()) {
			val vDeinit = vDeinits[vClass]
			if (vDeinit != null) mClassInfo[vClass]?.irClass?.let { checkSuperDeinit(it, vChecked) }
			val vCleaners = generateSequence(vClass) { it.superClass }.flatMap { it.cleaners }.toList()
			if (vDeinit == null && vCleaners.isEmpty()) continue
			val vIrClass = mClassInfo[vClass]?.irClass
			val vName = vIrClass?.name?.asString() ?: vClass.name
			val vWhat = if (vDeinit != null) "implements ktc2.Deinit" else "has a cleaner"
			// A value is copied, and a copy has no last reference of its own.
			if (vIrClass != null && isStruct(vIrClass)) {
				errors += "'$vName' $vWhat: a @Struct or value class cannot, it is copied"
				continue
			}
			if (vClass.isUsedInline) {
				errors += "'$vName' $vWhat: it cannot be held in place by a @Value field, which copies it"
				continue
			}
			if (vCleaners.isEmpty()) {
				vClass.deinit = vDeinit
				continue
			}
			val vSelf = KLocal("self", KClassType(vClass))
			fun selfAs(inType: KType): KExpr = if (inType == vSelf.type) KRead(vSelf) else KRefCast(KRead(vSelf), inType)
			val vEnds = KFunction(uniqueName("${vClass.cName}__ends"), vClass.unit, listOf(vSelf), KUnitType)
			vDeinit?.let { vEnds.body += KEval(KCall(it, listOf(selfAs(it.params[0].type)))) }
			for ((vField, vAction) in vCleaners) {
				vEnds.body += KEval(KCall(vAction, listOf(KFieldRead(selfAs(KClassType(vField.owner!!)), vField))))
			}
			vClass.unit.functions += vEnds
			vClass.deinit = vEnds
		}
	}

	/**
	 * Warns of each `deinit` of [inClass] and its superclasses that overrides the `deinit` of a
	 * superclass without calling it. As for any `override`, the one of the superclass then does
	 * not run: what it releases stays, which is seldom what was meant.
	 */
	private fun checkSuperDeinit(inClass: IrClass, inChecked: MutableSet<IrClass>) {
		var vClass: IrClass? = inClass
		while (vClass != null && inChecked.add(vClass)) {
			val vSuper = vClass.superTypes.firstNotNullOfOrNull { vType -> vType.classOrNull?.owner?.takeIf { !it.isInterface } }
			val vOwn = vClass.declarations.filterIsInstance<IrSimpleFunction>().firstOrNull { it.name.asString() == "deinit" && !it.isFakeOverride && it.overriddenSymbols.isNotEmpty() }
			// The `deinit` it would inherit: one written in a class above, not the declaration of the interface.
			val vInherited = vSuper?.declarations?.filterIsInstance<IrSimpleFunction>()?.firstOrNull { it.name.asString() == "deinit" && it.overriddenSymbols.isNotEmpty() }
				?.let { if (it.isFakeOverride) it.resolveFakeOverride() else it }
				?.takeIf { it.body != null && !it.parentAsClass.isInterface }
			if (vOwn != null && vInherited != null) {
				var vCallsSuper = false
				vOwn.body?.acceptChildrenVoid(object : IrVisitorVoid() {
					override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)

					override fun visitCall(expression: IrCall) {
						if (expression.superQualifierSymbol != null && expression.symbol.owner.name.asString() == "deinit") vCallsSuper = true
						super.visitCall(expression)
					}
				})
				if (!vCallsSuper) {
					val vAbove = vInherited.parentAsClass.name.asString()
					warnings += "'${vClass.name.asString()}.deinit' overrides the deinit of '$vAbove' without calling super.deinit(): what '$vAbove' gives up there is kept"
				}
			}
			vClass = vSuper
		}
	}

	// ==================
	// MARK: Cleaners
	// ==================

	/** A property `private val x = createCleaner(resource) { … }` that needs no `Cleaner` object: see [foldedCleaner]. */
	internal class FoldedCleaner(val resource: IrExpression, val action: IrSimpleFunction, val resourceType: IrType)

	private val mFoldedCleaners = HashMap<IrField, FoldedCleaner?>()

	/** The fields that hold the resource of a folded cleaner. */
	private val mCleanerFields = HashMap<KField, FoldedCleaner>()

	/**
	 * The backing field of the property of [inClass] that [inResource] reads from `this`, when
	 * it is just that: a `val` with a field and no accessor of its own. Null for anything else.
	 */
	private fun resourceProperty(inResource: IrExpression, inClass: IrClass): IrField? {
		fun isThis(inReceiver: IrExpression?): Boolean = inReceiver is IrGetValue && inReceiver.symbol == inClass.thisReceiver?.symbol
		val vBacking = when (inResource) {
			is IrGetField -> inResource.symbol.owner.takeIf { isThis(inResource.receiver) }
			is IrCall -> {
				val vGetter = inResource.symbol.owner
				val vProperty = vGetter.correspondingPropertySymbol?.owner
				vProperty?.backingField?.takeIf {
					vProperty.getter == vGetter && vGetter.origin == IrDeclarationOrigin.DEFAULT_PROPERTY_ACCESSOR && isThis(inResource.dispatchReceiver)
				}
			}
			else -> null
		} ?: return null
		val vProperty = vBacking.correspondingPropertySymbol?.owner ?: return null
		return vBacking.takeIf { !vProperty.isVar && !vProperty.isDelegated && vProperty.parent == inClass }
	}

	/** What the folded cleaner behind [inField] was declared with; null for any other field. */
	internal fun cleanerOf(inField: KField): FoldedCleaner? = mCleanerFields[inField]

	/**
	 * `createCleaner` returns an object that runs an action with a resource when it ends. Kept
	 * in a property nobody reads, that object only exists to end with its owner: the owner then
	 * holds the resource in the property instead, and runs the action itself when it ends. The
	 * program cannot tell, and the `Cleaner` and the lambda object are never made.
	 *
	 * This is the case for a `private val` (nothing outside the class can read it) that nothing
	 * in the class reads either, initialized by `createCleaner` with a lambda that uses nothing
	 * from around it (as Kotlin/Native requires). Any other use of `createCleaner` makes the
	 * object.
	 */
	private fun foldedCleaner(inProperty: IrProperty, inBacking: IrField, inClass: IrClass): FoldedCleaner? {
		if (inBacking in mFoldedCleaners) return mFoldedCleaners[inBacking]
		mFoldedCleaners[inBacking] = null
		if (inProperty.isVar || inProperty.isDelegated || inProperty.visibility != DescriptorVisibilities.PRIVATE) return null
		val vCall = inBacking.initializer?.expression as? IrCall ?: return null
		if (vCall.symbol.owner.kotlinFqName.asString() != "ktc2.createCleaner") return null
		val vResource = vCall.arguments.getOrNull(0) ?: return null
		val vAction = (vCall.arguments.getOrNull(1) as? IrFunctionExpression)?.function ?: return null
		val vType = vCall.typeArguments.getOrNull(0) ?: return null
		if (captures.freeVariables(vAction).isNotEmpty()) return null
		var vIsRead = false
		inClass.acceptChildrenVoid(object : IrVisitorVoid() {
			override fun visitElement(element: IrElement) {
				if (!vIsRead) element.acceptChildrenVoid(this)
			}

			// The property's own accessor reads the field: it is not a use.
			override fun visitProperty(declaration: IrProperty) {
				if (declaration !== inProperty) super.visitProperty(declaration)
			}

			override fun visitGetField(expression: IrGetField) {
				if (expression.symbol == inBacking.symbol) vIsRead = true
				super.visitGetField(expression)
			}

			override fun visitCall(expression: IrCall) {
				if (expression.symbol == inProperty.getter?.symbol) vIsRead = true
				super.visitCall(expression)
			}
		})
		if (vIsRead) return null
		return FoldedCleaner(vResource, vAction, vType).also { mFoldedCleaners[inBacking] = it }
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
			"kotlin.Array" -> KArrayType(if (vElement is KPrimType || vElement is KOptType || vElement is KStructType) KObjType else vElement)
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
					// `Number` is what every number is when its kind is not known: an object, the number in its box.
					vClass.kotlinFqName.asString() == "kotlin.Number" -> KObjType
					vClass.isInterface -> KObjType
					// A struct that may be null is held in its box: null needs a pointer.
					isStruct(vClass) -> structFor(vClass)?.let { if (vType.isMarkedNullable()) KClassType(boxFor(it)) else KStructType(it) }
					else -> classFor(vClass, argumentsOf(vBase))?.let { KClassType(it) }
				}
			}
		}
	}

	/** A type argument as used to pick an instantiation: every reference type is `Any?`. */
	private fun canonical(inType: IrType): IrType = when (val vKind = kTypeOf(inType)) {
		is KRefType -> mBuiltIns.anyNType
		// A struct holding references is kept in its box inside generic code, whose storage
		// (the blocks behind collections) only knows how to release objects.
		is KStructType -> if (vKind.struct.holdsReferences) mBuiltIns.anyNType else inType
		// Every C address type shares one instantiation too, without being boxed.
		KPtrType -> mPointerType ?: inType
		else -> inType
	}

	/** `kotlinx.cinterop.NativePtr`, standing for every C address type as a type argument. */
	private var mPointerType: IrType? = null

	private val mValueStructs = HashMap<IrClass, Boolean>()

	/**
	 * True for a class whose values are plain C structs: one marked `@Struct`, or a final
	 * `value class` holding only numbers and other such values. A value class has no identity
	 * in Kotlin either, so keeping it out of the heap changes nothing a program can observe.
	 */
	private fun isStruct(inClass: IrClass): Boolean {
		if (inClass.annotations.any { it.type.classFqName?.asString() == "ktc2.Struct" }) return true
		if (isSimpleEnum(inClass)) return true
		mValueStructs[inClass]?.let { return it }
		// While its own fields are being looked at, a class is not yet known to be a struct.
		mValueStructs[inClass] = false
		val vIsValue = inClass.isValue && inClass.kind == ClassKind.CLASS && inClass.modality == Modality.FINAL &&
			inClass.typeParameters.isEmpty() && inClass.fileOrNull != null && !isPointerClass(inClass)
		val vFields = inClass.declarations.filterIsInstance<IrProperty>().mapNotNull { it.backingField }
		val vHolds = vIsValue && vFields.isNotEmpty() && vFields.all { vField ->
			val vType = vField.type.makeNotNull()
			// Numbers, other such values, and references of any kind; not a type parameter, and not itself.
			primOf(vType) != null || vType.classOrNull?.owner?.let { it != inClass } == true
		}
		mValueStructs[inClass] = vHolds
		return vHolds
	}

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
		val vSubst = classParameters(vClass).map { it.symbol }.zip(argumentsOf(vType)).toMap()
		return vClass.superTypes.firstNotNullOfOrNull { findSuperType(close(it, vSubst), inTarget) }
	}

	/** The type arguments [inTarget] has when a value of [inType] is seen as it (`Any?` when unknown). */
	internal fun superArguments(inType: IrType?, inTarget: IrClass): List<IrType> =
		inType?.let { findSuperType(it, inTarget) }?.let { argumentsOf(it) } ?: classParameters(inTarget).map { mBuiltIns.anyNType }

	/** The substitution for a call to [inFunction]: its class's type arguments, then its own. */
	internal fun substFor(inFunction: IrFunction, inClassArguments: List<IrType>, inOwnArguments: List<IrType?>): Subst {
		val vOwner = inFunction.parent as? IrClass
		val vSubst = HashMap<IrTypeParameterSymbol, IrType>()
		vOwner?.let { classParameters(it) }?.forEachIndexed { vIndex, vParam ->
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
		val vParams = ((inFunction.parent as? IrClass)?.let { classParameters(it) } ?: emptyList()) + ownParameters(inFunction)
		val vArguments = vParams.map { kTypeOf(inSubst[it.symbol] ?: mBuiltIns.anyNType) ?: KObjType }
		// A local class is declared anew each time its declaration is lowered (once per expansion
		// of an inline function): its members belong to that particular class.
		val vLocal = inOwner?.takeIf { it in mLocalCaptures && it !in mInnerClasses } ?: (inFunction.parent as? IrClass)?.let { mLatestLocalClass[it] }
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
		// The properties of `companion { }` blocks are variables of the program like the top-level ones.
		fun declareStatics(inClass: IrClass) {
			for (vDeclaration in inClass.declarations) {
				if (vDeclaration is IrProperty && isStaticProperty(vDeclaration)) declareProperty(vDeclaration, vUnit, inFile, inClass)?.let { vStartup += it }
				if (vDeclaration is IrClass) declareStatics(vDeclaration)
			}
		}
		inFile.declarations.filterIsInstance<IrClass>().forEach { declareStatics(it) }
		if (vStartup.isNotEmpty()) {
			val vInit = KFunction("${cPrefix(vUnit.pkg)}${vUnit.name}_init", vUnit, emptyList(), KUnitType)
			vUnit.initializer = vInit
			mFileEnsures[vUnit] = newEnsure("${cPrefix(vUnit.pkg)}${vUnit.name}", vUnit, vInit, vUnit)
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
		mStructClasses[vStruct] = inClass
		mUnits += vUnit
		if (isSimpleEnum(inClass)) {
			declareSimpleEnum(inClass, vStruct, vFile)
			return vStruct
		}
		for (vProperty in inClass.declarations.filterIsInstance<IrProperty>()) {
			val vBacking = vProperty.backingField ?: continue
			if (vProperty.isVar) unsupported(vFile, vProperty, "'var' property in a @Struct class")
			val vType = kTypeOf(vBacking.type)
			if (vType == null || vType == KUnitType || vType == KNothingType || (vType is KStructType && vType.struct === vStruct)) {
				unsupported(vFile, vProperty, "@Struct property of type '${vBacking.type.render()}'")
				continue
			}
			val vField = KField(cIdentifier(vProperty.name.asString()), vType)
			vStruct.fields += vField
			mFields[vBacking to null] = vField
		}
		return vStruct
	}

	private val mStructClasses = HashMap<KStruct, IrClass>()

	/** The `@Struct` class [inStruct] was declared as. */
	internal fun structClassOf(inStruct: KStruct): IrClass = mStructClasses.getValue(inStruct)

	/**
	 * The box of [inStruct]: the object a struct value becomes where Kotlin needs one - as
	 * `Any`, as an interface it implements, or when it may be null. It answers the calls made
	 * on such an object (`toString`, `equals`, interface methods) with the struct's own
	 * functions. A struct that is only ever used by its own type never gets one.
	 */
	internal fun boxFor(inStruct: KStruct): KClass {
		inStruct.box?.let { return it }
		mSimpleEnums[inStruct]?.let { return simpleEnumBox(it) }
		val vIrClass = mStructClasses.getValue(inStruct)
		val vUnit = KUnit(inStruct.unit.pkg, inStruct.unit.name + ".Box")
		val vClass = KClass(inStruct.cName + "_Box", vUnit, null, jvmNameOf(vIrClass))
		vUnit.cls = vClass
		inStruct.box = vClass
		vClass.fields += KField("value", KStructType(inStruct), vClass, isImmutable = true)
		collectInterfaces(vIrClass, vClass.interfaces)
		mClassInfo[vClass] = ClassInfo(vIrClass, emptyMap(), vIrClass.symbol.typeWith())
		mClassList += vClass
		mUnits += vUnit
		return vClass
	}

	/**
	 * The reference class for [inClass] with [inArguments] for its type parameters, declared
	 * (layout only) on first use.
	 */
	internal fun classFor(inClass: IrClass, inArguments: List<IrType> = emptyList(), inFresh: Boolean = false): KClass? {
		if (isThrowableClass(inClass)) return throwableBase()
		val vArguments = classParameters(inClass).indices.map { canonical(inArguments.getOrNull(it) ?: mBuiltIns.anyNType) }
		val vKey = Mono(inClass, vArguments.map { kTypeOf(it) ?: KObjType })
		if (vKey in mClasses && !inFresh) return mClasses[vKey]
		val vFile = inClass.fileOrNull
		if (vFile == null || inClass.isInterface) {
			mClasses[vKey] = null
			return null
		}
		mClasses[vKey] = null
		val vSubst = classParameters(inClass).map { it.symbol }.zip(vArguments).toMap()
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
		vClass.family = inClass
		vUnit.cls = vClass
		mClasses[vKey] = vClass
		mClassInfo[vClass] = ClassInfo(inClass, vSubst, inClass.symbol.typeWith(vArguments))
		mClassList += vClass
		mUnits += vUnit

		for (vProperty in inClass.declarations.filterIsInstance<IrProperty>()) {
			val vBacking = vProperty.backingField ?: continue
			// A cleaner nobody reads is not kept: the property holds its resource, and the class runs the action.
			val vFolded = if (vFile != null) foldedCleaner(vProperty, vBacking, inClass) else null
			val vResourceType = vFolded?.let { kTypeOf(it.resourceType, vSubst) }?.takeIf { it != KUnitType && it != KNothingType }
			if (vFolded != null && vResourceType != null) {
				// The resource is a `val` of this class, declared before: that property is where the action
				// finds it, and the cleaner takes no room at all. Anything else is kept in a field of its own.
				val vKept = resourceProperty(vFolded.resource, inClass)?.let { mFields[it to vClass] }
					?.takeIf { it.isImmutable && it.type == vResourceType && it.weakType == null && !it.isInline && it !in mCleanerFields }
				val vField = vKept ?: KField(cIdentifier(vProperty.name.asString()), vResourceType, vClass, isImmutable = true).also {
					vClass.fields += it
					mFields[vBacking to vClass] = it
					mCleanerFields[it] = vFolded
				}
				val vAction = KFunction(uniqueName("${vClass.cName}_${cIdentifier(vProperty.name.asString())}__clean"), vUnit, listOf(KLocal("resource", vResourceType)), KUnitType)
				vUnit.functions += vAction
				vClass.cleaners += vField to vAction
				later { BodyBuilder(this, vAction, vFolded.action, vFile!!, vSubst).buildClosure(emptyList(), null) }
				continue
			}
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
		if (inClass.isInner) declareOuter(inClass, vClass, vSubst, vFile)

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
				declareEnsure(vClass, vUnit, vStartup)
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

	/** The classes declared `inner`: their one capture is the object around them. */
	private val mInnerClasses = HashSet<KClass>()

	/**
	 * Gives the `inner` class [inClass] its reference to the enclosing object. It is held like
	 * the capture of a local class: a field set when an instance is created, read at the start
	 * of each member. A subclass that is an inner class of the same outer class uses the field
	 * it inherits.
	 */
	private fun declareOuter(inIrClass: IrClass, inClass: KClass, inSubst: Subst, inFile: IrFile) {
		val vOuterThis = inIrClass.parentAsClass.thisReceiver ?: return
		mInnerClasses += inClass
		val vInherited = generateSequence(inClass.superClass) { it.superClass }
			.firstNotNullOfOrNull { vAncestor -> mLocalCaptures[vAncestor]?.bindings?.firstOrNull { it.declaration === vOuterThis } }
		if (vInherited != null) {
			mLocalCaptures[inClass] = LocalCaptures(listOf(vInherited), emptyMap())
			return
		}
		val vType = kTypeOf(vOuterThis.type, inSubst)
		if (vType == null) {
			unsupported(inFile, inIrClass, "inner class '${inIrClass.name}'")
			return
		}
		var vName = "outer"
		while (inClass.fields.any { it.cName == vName }) vName += "_"
		val vField = KField(vName, vType, inClass, isImmutable = true)
		inClass.fields += vField
		mLocalCaptures[inClass] = LocalCaptures(listOf(BodyBuilder.Binding(vOuterThis, vField, null, null)), emptyMap())
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

	// ==================
	// MARK: Simple enums
	// ==================

	/** An entry of a `@SimpleEnum` class: its [value] is what it is in C, [arguments] what it gives the constructor. */
	internal class SimpleEntry(val name: String, val ordinal: Int, val value: Int, val arguments: List<IrExpression?>)

	/**
	 * A `@SimpleEnum` class: an enum whose values are numbers (a C `enum`, [struct] with its one
	 * field). The objects that stand for them where Kotlin needs one are made by [simpleEnumBox].
	 */
	internal class SimpleEnum(
		val irClass: IrClass,
		val file: IrFile,
		val struct: KStruct,
		val entries: List<SimpleEntry>,
		val parameters: List<IrValueParameter>,
		/** True when the numbers are given by an `@EnumValue` parameter, false when they are the ordinals. */
		val isNumbered: Boolean,
	) {
		val valueField: KField get() = struct.fields[0]

		/** The object kept for each entry, in the order of [entries]. */
		var boxes: List<KStatic> = emptyList()

		fun constant(inEntry: SimpleEntry): KExpr = KStructNew(struct, listOf(KConst(inEntry.value, KPrimType(KPrim.INT))))

		/** `inValue` is the entry [inEntry]. */
		fun isEntry(inValue: KExpr, inEntry: SimpleEntry): KExpr =
			KBinary(KBinaryOp.EQ, KFieldRead(inValue, valueField), KConst(inEntry.value, KPrimType(KPrim.INT)), KPrim.INT, KPrimType(KPrim.BOOLEAN))
	}

	private val mSimpleEnums = HashMap<KStruct, SimpleEnum>()
	private val mSimpleEnumProperties = HashMap<IrField, KFunction?>()

	internal fun isSimpleEnum(inClass: IrClass): Boolean =
		inClass.kind == ClassKind.ENUM_CLASS && inClass.annotations.any { it.type.classFqName?.asString() == "ktc2.SimpleEnum" }

	/** What is known of [inClass] when it is a `@SimpleEnum` class; null for any other. */
	internal fun simpleEnumOf(inClass: IrClass): SimpleEnum? = if (isSimpleEnum(inClass)) structFor(inClass)?.let { mSimpleEnums[it] } else null

	internal fun simpleEnumOf(inStruct: KStruct): SimpleEnum? = mSimpleEnums[inStruct]

	private fun refuse(inFile: IrFile, inElement: IrElement, inWhat: String) {
		val vLine = if (inElement.startOffset >= 0) inFile.fileEntry.getLineNumber(inElement.startOffset) + 1 else 0
		errors += "${File(inFile.fileEntry.name).name}:$vLine: $inWhat"
	}

	/** Reads the entries of the `@SimpleEnum` class [inClass] and refuses what cannot be a number. */
	private fun declareSimpleEnum(inClass: IrClass, inStruct: KStruct, inFile: IrFile) {
		val vName = inClass.name.asString()
		inStruct.fields += KField("value", KPrimType(KPrim.INT))
		val vConstructor = inClass.declarations.filterIsInstance<IrConstructor>().firstOrNull { it.isPrimary }
		val vParams = vConstructor?.parameters?.filter { it.kind == IrParameterKind.Regular } ?: emptyList()
		val vMarked = vParams.filter { vParam -> vParam.annotations.any { it.type.classFqName?.asString() == "ktc2.EnumValue" } }
		if (vMarked.size > 1) refuse(inFile, vMarked[1], "@SimpleEnum class '$vName': only one parameter can be the @EnumValue")
		val vNumber = vMarked.firstOrNull()
		if (vNumber != null && !vNumber.type.isInt() && !vNumber.type.isUInt()) refuse(inFile, vNumber, "@SimpleEnum class '$vName': the @EnumValue parameter '${vNumber.name}' has to be an Int or a UInt")
		for (vProperty in inClass.declarations.filterIsInstance<IrProperty>()) {
			val vBacking = vProperty.backingField ?: continue
			val vSource = (vBacking.initializer?.expression as? IrGetValue)?.symbol?.owner
			if (vProperty.isVar || vSource !in vParams) {
				refuse(inFile, vProperty, "@SimpleEnum class '$vName': property '${vProperty.name}' holds a value of its own; only a 'val' of the constructor can be looked up from a number")
			}
		}
		if (inClass.declarations.any { it is IrAnonymousInitializer }) refuse(inFile, inClass, "@SimpleEnum class '$vName': an init block cannot run for a number")
		val vEntries = inClass.declarations.filterIsInstance<IrEnumEntry>().mapIndexed { vOrdinal, vEntry ->
			if (vEntry.correspondingClass != null) refuse(inFile, vEntry, "@SimpleEnum class '$vName': entry '${vEntry.name}' has a body, which needs an object")
			val vCall = vEntry.initializerExpression?.expression as? IrEnumConstructorCall
			val vArguments = vParams.map { vParam -> vCall?.arguments?.getOrNull(vConstructor!!.parameters.indexOf(vParam)) ?: vParam.defaultValue?.expression }
			var vValue = vOrdinal
			if (vNumber != null) {
				val vGiven = constantOf(vArguments[vParams.indexOf(vNumber)])?.value as? Int
				if (vGiven == null) refuse(inFile, vEntry, "@SimpleEnum class '$vName': the @EnumValue of entry '${vEntry.name}' has to be a constant") else vValue = vGiven
			}
			SimpleEntry(vEntry.name.asString(), vOrdinal, vValue, vArguments)
		}
		vEntries.groupBy { it.value }.values.firstOrNull { it.size > 1 }?.let {
			refuse(inFile, inClass, "@SimpleEnum class '$vName': entries '${it[0].name}' and '${it[1].name}' have the same @EnumValue ${it[0].value}")
		}
		inStruct.enumEntries = vEntries.map { "${inStruct.cName}_${cSuffix(it.name)}" to it.value }
		mSimpleEnums[inStruct] = SimpleEnum(inClass, inFile, inStruct, vEntries, vParams, vNumber != null)
	}

	/** [inValue] as a constant: one written there, or a `const val` it reads. */
	private fun constantOf(inValue: IrExpression?): IrConst? = when (inValue) {
		is IrConst -> inValue
		is IrGetField -> inValue.symbol.owner.takeIf { it.isFinal }?.initializer?.expression as? IrConst
		is IrCall -> inValue.symbol.owner.correspondingPropertySymbol?.owner?.takeIf { it.isConst }?.backingField?.initializer?.expression as? IrConst
		else -> null
	}

	/** What the constant [inValue] is in KIR, as a value of type [inType]; null when it is no constant. */
	private fun simpleEnumConstant(inValue: IrExpression?, inType: KType): KExpr? = when {
		inValue !is IrGetEnumValue && constantOf(inValue) != null -> {
			val vConstant = constantOf(inValue)!!.value
			when {
				vConstant is String && inType == KStringType -> KStringLit(vConstant)
				vConstant != null && vConstant !is String && inType is KPrimType -> KConst(vConstant, inType)
				else -> null
			}
		}
		inValue is IrGetEnumValue -> {
			val vOther = simpleEnumOf(inValue.symbol.owner.parentAsClass)
			val vEntry = vOther?.entries?.firstOrNull { it.name == inValue.symbol.owner.name.asString() }
			if (vOther != null && vEntry != null && inType == KStructType(vOther.struct)) vOther.constant(vEntry) else null
		}
		else -> null
	}

	/**
	 * The function that gives, for a value of a `@SimpleEnum` class, what the property behind
	 * [inField] is for its entry: the entries compared one after the other, which the C compiler
	 * turns into a table.
	 */
	internal fun simpleEnumProperty(inField: IrField): KFunction? = mSimpleEnumProperties.getOrPut(inField) {
		val vEnum = simpleEnumOf(inField.parentAsClass) ?: return@getOrPut null
		val vParam = (inField.initializer?.expression as? IrGetValue)?.symbol?.owner
		val vIndex = vEnum.parameters.indexOf(vParam)
		val vType = kTypeOf(inField.type)
		if (vIndex < 0 || vType == null) return@getOrPut null
		val vSelf = KLocal("self", KStructType(vEnum.struct))
		val vFunction = KFunction(uniqueName("${vEnum.struct.cName}__${cSuffix(inField.name.asString())}"), vEnum.struct.unit, listOf(vSelf), vType)
		for (vEntry in vEnum.entries) {
			val vConstant = simpleEnumConstant(vEntry.arguments[vIndex], vType)
			if (vConstant == null) {
				refuse(vEnum.file, vEnum.irClass, "@SimpleEnum class '${vEnum.irClass.name}': what entry '${vEntry.name}' gives for '${inField.name}' is not a constant (a number, a string, an entry of a @SimpleEnum class)")
				continue
			}
			vFunction.body += KIf(vEnum.isEntry(KRead(vSelf), vEntry), listOf(KReturn(vConstant)), emptyList())
		}
		vFunction.body += KReturn(KZero(vType))
		vEnum.struct.unit.functions += vFunction
		vFunction
	}

	/**
	 * The class of the objects that stand for the values of a `@SimpleEnum` class where Kotlin
	 * needs an object: an ordinary `Enum`, with the name and the ordinal, and the number. There
	 * is one such object per entry for the whole run of the program, so two of the same entry
	 * are the same object, as on the JVM. `KStruct.boxFunction` gives the one of a value; it
	 * fills them in the first time it is called.
	 */
	private fun simpleEnumBox(inEnum: SimpleEnum): KClass {
		val vStruct = inEnum.struct
		val vBase = enumBase()
		val vBaseType = KClassType(vBase)
		val vUnit = KUnit(vStruct.unit.pkg, vStruct.unit.name + ".Box")
		val vClass = KClass(vStruct.cName + "_Box", vUnit, vBase, jvmNameOf(inEnum.irClass))
		vUnit.cls = vClass
		vStruct.box = vClass
		val vValueField = KField("value", KStructType(vStruct), vClass, isImmutable = true)
		vClass.fields += vValueField
		collectInterfaces(inEnum.irClass, vClass.interfaces)
		mClassInfo[vClass] = ClassInfo(inEnum.irClass, emptyMap(), inEnum.irClass.symbol.typeWith())
		mClassList += vClass
		mUnits += vUnit
		instantiate(vClass)
		inEnum.boxes = inEnum.entries.map { vEntry -> KStatic("${vClass.cName}__${cSuffix(vEntry.name)}", vClass, vUnit).also { vUnit.statics += it } }

		val vValue = KLocal("value", KStructType(vStruct))
		val vBoxing = KFunction(uniqueName("${vStruct.cName}__box"), vUnit, listOf(vValue), KClassType(vClass))
		if (inEnum.entries.isEmpty()) {
			vBoxing.body += KReturn(KNull(KClassType(vClass)))
		} else {
			// The name of the first one is written last: it says that all of them are filled in.
			val vFill = ArrayList<KStmt>()
			for ((vEntry, vBox) in inEnum.entries.zip(inEnum.boxes).reversed()) {
				vFill += KFieldSet(KStaticRead(vBox), vValueField, inEnum.constant(vEntry))
				vFill += KFieldSet(KRefCast(KStaticRead(vBox), vBaseType), enumOrdinalField, KConst(vEntry.ordinal, KPrimType(KPrim.INT)))
				vFill += KFieldSet(KRefCast(KStaticRead(vBox), vBaseType), enumNameField, KStringLit(vEntry.name))
			}
			val vFirstName = KFieldRead(KRefCast(KStaticRead(inEnum.boxes[0]), vBaseType), enumNameField)
			vBoxing.body += KIf(KRefEquals(vFirstName, KNull(KStringType)), vFill, emptyList())
			for ((vEntry, vBox) in inEnum.entries.zip(inEnum.boxes)) {
				vBoxing.body += KIf(inEnum.isEntry(KRead(vValue), vEntry), listOf(KReturn(KStaticRead(vBox))), emptyList())
			}
			vBoxing.body += KReturn(KStaticRead(inEnum.boxes[0]))
		}
		vUnit.functions += vBoxing
		vStruct.boxFunction = vBoxing
		return vClass
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
		declareEnsure(inEnum, inEnum.unit, vStartup)
		mPending += { BodyBuilder(this, vStartup, null, inFile, emptyMap()).buildEnumInitializer(vEntries, vStatics) }
	}

	// ==================
	// MARK: Initialization on first use
	// ==================

	/*
	Kotlin creates an `object`, and the entries of an enum class, when the program first touches
	them, and gives the top-level properties of a file their values when the program first uses
	something of that file. Every such use is therefore preceded by a call to an `ensure`
	function, which runs the initializer the first time. When importing is over, the
	initializers that only compute and store - which is most of them - are found; for those
	the calls are taken out again and the initializer runs at startup: nobody can tell when it
	ran. The calls that stay are made only while the initializer has not finished.
	*/

	/**
	 * @property function what a use calls first
	 * @property flag set when the initializer starts, [done] when it has finished
	 * @property ownUnit for a file: its unit, whose properties the initializer may read as it goes
	 */
	private class Ensure(val function: KFunction, val flag: KGlobal, val done: KGlobal, val unit: KUnit, val initializer: KFunction, val ownUnit: KUnit?)

	private val mEnsures = LinkedHashMap<KClass, Ensure>()
	private val mFileEnsures = LinkedHashMap<KUnit, Ensure>()

	private fun declareEnsure(inClass: KClass, inUnit: KUnit, inInitializer: KFunction) {
		mEnsures[inClass] = newEnsure(inClass.cName, inUnit, inInitializer, null)
	}

	private fun newEnsure(inName: String, inUnit: KUnit, inInitializer: KFunction, inOwnUnit: KUnit?): Ensure {
		val vBoolean = KPrimType(KPrim.BOOLEAN)
		val vFlag = KGlobal("${inName}_ready", vBoolean, inUnit)
		inUnit.globals += vFlag
		val vDone = KGlobal("${inName}_done", vBoolean, inUnit)
		inUnit.globals += vDone
		val vFunction = KFunction("${inName}_ensure", inUnit, emptyList(), KUnitType)
		// `ready` is set first: an initializer that comes back to its own object finds it as far as it got.
		// Another thread does not: it waits for the lock, which the initializing thread holds until it is done.
		val vInitialize = KIf(
			KUnary(KUnaryOp.NOT, KGlobalRead(vFlag), vBoolean),
			listOf(KGlobalSet(vFlag, KConst(true, vBoolean)), KEval(KCall(inInitializer, emptyList())), KGlobalSet(vDone, KConst(true, vBoolean))),
			emptyList(),
		)
		vFunction.body += KIf(
			KUnary(KUnaryOp.NOT, KGlobalRead(vDone), vBoolean),
			listOf(
				KEval(KIntrinsic("ktc2_initEnter", emptyList(), KUnitType, isPure = false)),
				vInitialize,
				KEval(KIntrinsic("ktc2_initExit", emptyList(), KUnitType, isPure = false)),
			),
			emptyList(),
		)
		inUnit.functions += vFunction
		return Ensure(vFunction, vFlag, vDone, inUnit, inInitializer, inOwnUnit)
	}

	/** The function to call before using the object or an entry of the enum class [inClass]; null when there is nothing to initialize. */
	internal fun ensureFor(inClass: IrClass): KFunction? = classFor(inClass)?.let { mEnsures[it]?.function }

	/**
	 * The function to call before [inFrom] uses a top-level property or function of the file
	 * behind [inUnit]; null when the file has nothing to initialize, or when [inFrom] is its
	 * initializer.
	 */
	internal fun fileEnsureFor(inUnit: KUnit, inFrom: KFunction): KFunction? =
		mFileEnsures[inUnit]?.takeIf { it.initializer !== inFrom && it.function !== inFrom }?.function

	/**
	 * True when running [inFunction] earlier than Kotlin would cannot be observed: it only
	 * computes and stores. It may read the top-level properties of [inOwnUnit], the file whose
	 * initializer it is; anything else that has an initializer of its own it must leave alone,
	 * so that the initializers run at startup do not depend on one another.
	 */
	private fun isUnobservable(inFunction: KFunction, inKnown: MutableMap<KFunction, Boolean>, inOwnUnit: KUnit? = null): Boolean {
		inKnown[inFunction]?.let { return it }
		// A function that leads back to itself is taken to be observable.
		inKnown[inFunction] = false
		if (inFunction.isExternal || inFunction.dispatch != null) return false
		val vIsEnsure = mEnsures.values.any { it.function === inFunction } || mFileEnsures.values.any { it.function === inFunction }
		val vHolds = !vIsEnsure && !inFunction.body.anyStatement { vStatement ->
			vStatement is KPrint || vStatement is KThrow || vStatement is KTryRegion || vStatement is KGlobalSet ||
				vStatement.ownExpressions().any { vRoot ->
					vRoot.anyNode { vNode ->
						(vNode is KGlobalRead && vNode.global.unit !== inOwnUnit) || vNode.isRuntimeCheck() ||
							// An object with a `deinit` that is given up here runs the program's code.
							(vNode is KAlloc && vNode.cls.deinit != null) ||
							(vNode is KCall && !isUnobservable(vNode.function, inKnown)) ||
							(vNode is KIntrinsic && !vNode.isPure && !vNode.name.startsWith("ktc2_box_"))
					}
				}
		}
		inKnown[inFunction] = vHolds
		return vHolds
	}

	/** [inBlock] with each `ensure` call of [inEager] taken out, and each one of [inLazy] made only while its initializer has not finished. */
	private fun settled(inBlock: List<KStmt>, inEager: Set<KFunction>, inLazy: Map<KFunction, KGlobal>): List<KStmt> = inBlock.mapNotNull { vStatement ->
		val vCalled = ((vStatement as? KEval)?.expr as? KCall)?.function
		when {
			vCalled != null && vCalled in inEager -> null
			vCalled != null && vCalled in inLazy ->
				KIf(KUnary(KUnaryOp.NOT, KGlobalRead(inLazy.getValue(vCalled)), KPrimType(KPrim.BOOLEAN)), listOf(vStatement), emptyList())
			vStatement is KIf -> KIf(vStatement.condition, settled(vStatement.thenBlock, inEager, inLazy), settled(vStatement.elseBlock, inEager, inLazy))
			vStatement is KLoop -> KLoop(vStatement.id, settled(vStatement.body, inEager, inLazy), settled(vStatement.latch, inEager, inLazy))
			vStatement is KTryRegion -> KTryRegion(vStatement.id, settled(vStatement.body, inEager, inLazy))
			else -> vStatement
		}
	}

	/** Decides, once everything is imported, what is initialized at startup and what on first use. */
	private fun settleInitialization() {
		val vEager = HashSet<KFunction>()
		val vLazy = HashMap<KFunction, KGlobal>()
		for (vEnsure in mEnsures.values + mFileEnsures.values) {
			// The initializer may come back to what it initializes: that call finds it under way and returns.
			val vKnown = HashMap<KFunction, Boolean>()
			vKnown[vEnsure.function] = true
			// The file initializer writes the properties of its file: that is its job, not something to observe.
			val vIsQuiet = if (vEnsure.ownUnit != null) isQuietFileInitializer(vEnsure, vKnown) else isUnobservable(vEnsure.initializer, vKnown)
			if (vIsQuiet) {
				vEager += vEnsure.function
				vEnsure.unit.functions -= vEnsure.function
				vEnsure.unit.globals -= vEnsure.flag
				vEnsure.unit.globals -= vEnsure.done
			} else {
				vLazy[vEnsure.function] = vEnsure.done
				if (vEnsure.unit.initializer === vEnsure.initializer) {
					// Initialized on first use: not at startup. The initializer stays, as a function the `ensure` calls.
					vEnsure.unit.initializer = null
					vEnsure.unit.functions += vEnsure.initializer
				}
			}
		}
		for (vUnit in mUnits) {
			for (vFunction in vUnit.functions + listOfNotNull(vUnit.initializer)) {
				if (vFunction.isExternal || vFunction.dispatch != null || vFunction in vLazy) continue
				val vBody = settled(vFunction.body, vEager, vLazy)
				vFunction.body.clear()
				vFunction.body += vBody
			}
		}
	}

	/** [isUnobservable] for the initializer of a file, whose statements are the stores into the file's own properties. */
	private fun isQuietFileInitializer(inEnsure: Ensure, inKnown: MutableMap<KFunction, Boolean>): Boolean =
		!inEnsure.initializer.body.anyStatement { vStatement ->
			vStatement is KPrint || vStatement is KThrow || vStatement is KTryRegion ||
				(vStatement is KGlobalSet && vStatement.global.unit !== inEnsure.ownUnit) ||
				vStatement.ownExpressions().any { vRoot ->
					vRoot.anyNode { vNode ->
						(vNode is KGlobalRead && vNode.global.unit !== inEnsure.ownUnit) || vNode.isRuntimeCheck() ||
							(vNode is KAlloc && vNode.cls.deinit != null) ||
							(vNode is KCall && !isUnobservable(vNode.function, inKnown)) ||
							(vNode is KIntrinsic && !vNode.isPure && !vNode.name.startsWith("ktc2_box_"))
					}
				}
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
		if (inField.isStatic && inField.parent is IrClass) inField.fileOrNull?.let { facadeFor(it) }
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
		if (isStaticProperty(vProperty)) vProperty.fileOrNull?.let { facadeFor(it) }
	}

	/**
	 * Declares a top-level property: a global for its backing field, direct access for default
	 * accessors (custom ones are ordinary functions).
	 *
	 * @return the global and its initializer when that initializer has to run at startup
	 */
	private fun declareProperty(inProperty: IrProperty, inUnit: KUnit, inFile: IrFile, inOwner: IrClass? = null): Pair<KGlobal, IrExpression>? {
		val vBacking = inProperty.backingField ?: return null
		val vType = kTypeOf(vBacking.type)
		if (vType == null || vType == KUnitType || vType == KNothingType) {
			unsupported(inFile, inProperty, "property type '${vBacking.type.render()}'")
			return null
		}
		val vScope = cPrefix(inUnit.pkg) + (inOwner?.let { cSuffix(nestedName(it)) + "_" } ?: "")
		val vGlobal = KGlobal(vScope + cSuffix(inProperty.name.asString()), vType, inUnit)
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
			// The receiver of an inner class's constructor is the enclosing object, not the new one.
			var vName = if (vParam.kind == IrParameterKind.Regular) cIdentifier(vParam.name.asString()) else if (inFunction is IrConstructor) "outer" else "self"
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
	/** The member of [inClass] that is [inFunction] or overrides it, if the class declares or inherits one. */
	internal fun implementationIn(inClass: IrClass, inFunction: IrSimpleFunction): IrSimpleFunction? =
		membersOf(inClass).firstOrNull { it == inFunction || overrides(it, inFunction) }

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
		// A function of a `companion { }` block belongs to no instance: it is imported as a top-level one.
		val vStaticOwner = (inFunction.parent as? IrClass)?.takeIf { isStaticMember(inFunction) }
		val vOwner = (inFunction.parent as? IrClass)?.takeIf { vStaticOwner == null }
		if (vOwner != null && isAnyClass(vOwner)) {
			return anyDefault(inFunction.name.asString()).also { mFunctions[vKey] = it }
		}
		if (vOwner != null && vOwner.kotlinFqName.asString() == "kotlin.Enum") {
			return enumFunction(inFunction.name.asString()).also { mFunctions[vKey] = it }
		}
		// A property of the constructor of a `@SimpleEnum` class is looked up from the number.
		if (vOwner != null && isSimpleEnum(vOwner) && inFunction is IrSimpleFunction && inFunction.origin == IrDeclarationOrigin.DEFAULT_PROPERTY_ACCESSOR) {
			val vField = inFunction.correspondingPropertySymbol?.owner?.backingField
			return (vField?.let { simpleEnumProperty(it) }).also { mFunctions[vKey] = it }
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
			inOwnerClass ?: classFor(vOwner, classParameters(vOwner).map { inSubst[it.symbol] ?: mBuiltIns.anyNType })
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
			vStaticOwner != null -> cPrefix(vUnit.pkg) + cSuffix(nestedName(vStaticOwner)) + "_"
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
		val vClassArguments = classParameters(vOwner).map { inSubst[it.symbol] ?: mBuiltIns.anyNType }
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
		// The runtime calls `deinit` of every object that has one: it is looked up like a call the program makes.
		val vDeinit = mDeinit
		if (vDeinit != null && mDeinitDispatcher == null && mInstantiated.any { vClass -> mClassInfo[vClass]?.let { isSubtype(it.irClass, vDeinit.parentAsClass) } == true }) {
			mDeinitDispatcher = dispatcherFor(vDeinit)
		}
		for ((vKey, vDispatcher) in mDispatchers.entries.toList()) {
			val vFunction = vDispatcher.function
			val vOwner = vFunction.parentAsClass
			val vIsInvoke = vFunction.name.asString() == "invoke"
			if (vIsInvoke || vFunction.modality == Modality.ABSTRACT) {
				for (vClosure in mClosures.toList()) {
					// A converted lambda implements the one abstract method of its interface.
					if (vClosure.isSam && vFunction.modality != Modality.ABSTRACT) continue
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
				// A struct returned through the dispatcher comes back as an object: in its box.
				(vTarget.returnType as? KStructType)?.let { instantiate(boxFor(it.struct)) }
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
	 * the dispatcher then boxes and unboxes at that boundary. The other way round as well - an
	 * implementation written over references (a `Comparator<Comparable<Any>>` used, after a
	 * cast, to compare Ints) serves a primitive dispatcher, which boxes for it.
	 */
	private fun accepts(inDispatcher: List<KType>, inActual: List<KType>): Boolean =
		inDispatcher.size == inActual.size && inDispatcher.indices.all {
			// `Nothing` as an argument (the empty list is a List<Nothing>) fits every instantiation.
			inDispatcher[it] == inActual[it] || inDispatcher[it] == KObjType || inActual[it] == KObjType || inActual[it] == KNothingType
		}

	/** True when a call to [inFunction] must go through a dispatcher. */
	internal fun isVirtual(inFunction: IrSimpleFunction): Boolean {
		val vOwner = inFunction.parent as? IrClass ?: return false
		if (isStruct(vOwner) || isPointerClass(vOwner)) return false
		return vOwner.isInterface || (inFunction.modality != Modality.FINAL && vOwner.modality != Modality.FINAL)
	}
}
