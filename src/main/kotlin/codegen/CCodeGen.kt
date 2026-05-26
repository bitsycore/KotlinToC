package com.bitsycore.ktc.codegen

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.codegen.statement.emitStmt
import com.bitsycore.ktc.types.KtcType
import com.bitsycore.ktc.utils.wrapYellow

/* Compute a file-relative #include path.
inFromDir: the directory of the including file, relative to outDir (e.g. "ktc/std", "com/example", "")
inToPath:  the target path relative to outDir (e.g. "ktc/core/ktc_core.h", "ktc/std/_package_.h") */
internal fun relIncludePath(inFromDir: String, inToPath: String): String {
	val vFromParts = if (inFromDir.isEmpty()) emptyList() else inFromDir.split('/') // parts of the source directory
	val vToParts   = inToPath.split('/')                                             // parts of the target path
	var vCommon    = 0                                                               // length of shared prefix
	while (vCommon < vFromParts.size && vCommon < vToParts.size - 1 && vFromParts[vCommon] == vToParts[vCommon])
		vCommon++
	val vUps  = vFromParts.size - vCommon                // how many ".." hops needed
	val vDown = vToParts.drop(vCommon).joinToString("/") // path from the common ancestor to the target
	return if (vUps == 0) vDown else "../".repeat(vUps) + vDown
}

internal class CCodeGen(val file: KtFile, val allFiles: List<KtFile> = listOf(), val sourceLines: List<String> = emptyList(), val memTrack: Boolean = false, val disposedMode: String = "NO", val doubleDisposeMode: String = "NO", val sourceFileName: String = "") : SymbolReader {

    // ── Package prefix ───────────────────────────────────────────────
    override val prefix: String = file.pkg?.replace('.', '_')?.plus("_") ?: ""

    /* Fallback C name: prefix + name. Used only for names not found in TypeDef maps or funNames. */
    internal fun pfx(inName: String): String {
        if (inName.startsWith("ktc_")) return inName
        return "$prefix$inName"
        }

    /*
    Phase 6: TypeDef-based C name resolution.
    Replaces pfx(typeName) for class, object, enum, and interface identifiers.
    Looks up the TypeDef and returns its flatName (pkg + baseName).
    Falls back to pfx() for builtins and names not yet in TypeDef tables.
    */
    override fun typeFlatName(inName: String): String {  // type or object name → C flat name
        if (inName.startsWith("ktc_")) return inName
        classes[inName]?.let { return it.flatName }
        objects[inName]?.let { return it.flatName }
        enums[inName]?.let { return it.flatName }
        interfaces[inName]?.let { return it.flatName }
        // Resolve dotted nested names (e.g. "SDL3.Mouse" → "SDL3$Mouse") not yet in map
        if (inName.contains('.')) {
            val vFlat = inName.replace('.', '$')
            classes[vFlat]?.let { return it.flatName }
            objects[vFlat]?.let { return it.flatName }
            }
        return pfx(inName)
        }

    /*
    Phase 6: Top-level function C name resolution.
    Replaces pfx(fnName) for standalone function identifiers.
    Uses the funNames map populated in collectDecls.
    Falls back to pfx() for generic function instantiations (mangled names).
    */
    internal fun funCName(inName: String): String {  // function name → C function name
        if (inName.startsWith("ktc_")) return inName
        funNames[inName]?.let { return it }
        return pfx(inName)
        }

    /* Find a top-level (no receiver) FunDecl in a named package and return (decl, C-function-name) or null.
    Used for cross-package qualified calls like sdl3.initialize() → sdl3_initialize(). */
    internal fun findCrossPackageFun(inPkgName: String, inFnName: String): Pair<FunDecl, String>? {
        if (lookupVar(inPkgName) != null) return null  // shadowed by a local variable
        if (classes.containsKey(inPkgName) || objects.containsKey(inPkgName)) return null  // is a type, not a package
        for (vFile in allFiles) {
            if ((vFile.pkg ?: "") != inPkgName) continue
            val vDecl = vFile.decls.filterIsInstance<FunDecl>()
                .firstOrNull { it.receiver == null && it.name == inFnName }
            if (vDecl != null) {
                val vCFnName = "${inPkgName.replace('.', '_')}_$inFnName"
                return Pair(vDecl, vCFnName)
                }
            }
        return null
        }

    /* Resolve a type name that may be a nested class inside an object.
    e.g. resolveNestedObjName("Window", "SDL3") → "SDL3$Window" if that class exists. */
    internal fun resolveNestedObjName(inName: String, inObjName: String): String {
        if (classes.containsKey(inName)) return inName
        val vNested = "${inObjName}\$$inName"
        if (classes.containsKey(vNested)) return vNested
        return inName
        }

    internal fun inferredTypeRef(typeName: String?): TypeRef? {
        if (typeName == null) return null
        return TypeRef(typeName)
    }

    /* Convert internal type name to Kotlin display name: "Wrapper_String" → "Wrapper<String>" */
    internal fun ktDisplayName(internal: String): String {
        // Generic class instantiation: find base name and split by _
        for (baseName in genericClassDecls.keys) {
            if (internal.startsWith("${baseName}_")) {
                val typeArgs = internal.removePrefix("${baseName}_").split("_")
                return "$baseName<${typeArgs.joinToString(", ")}>"
            }
        }
        return internal
    }

    // ── Symbol tables (populated by collectDecls) ────────────────────
    // Data classes in Structures.kt

    override val classes  = mutableMapOf<String, ClassInfo>()
    override val enums    = mutableMapOf<String, EnumInfo>()
    internal val enumValuesCalled  = mutableSetOf<String>()
    internal val enumValueOfCalled = mutableSetOf<String>()
    override val objects  = mutableMapOf<String, ObjInfo>()
    internal val funSigs  = mutableMapOf<String, FunSig>()
    internal val funNames = mutableMapOf<String, String>()  // top-level function name → C name
    internal val inlineFunDecls = mutableMapOf<String, MutableList<FunDecl>>()
    internal val inlineExtFunDecls = mutableMapOf<String, MutableList<FunDecl>>()  // inline extension funs, keyed by method name; multiple overloads by receiver type
    internal var activeLambdas: Map<String, ActiveLambda> = emptyMap()
    internal val lambdaParamSubst = mutableMapOf<String, String>()  // also stores "\$this" → receiver C expr during inline ext expansion
    // Deferred hdr declarations: className → list of hdr lines (for methods moved to implements section)
    /* Deferred header lines per class: (ifaceDisplayName, headerLine) pairs.
    ifaceDisplayName is the Kotlin type-ref string, e.g. "Iterator<Float?>", or "" for non-iface methods. */
    internal val deferredHdrLines = mutableMapOf<String, MutableList<Pair<String, String>>>()
    internal val lambdaParamTypes = mutableMapOf<String, String>()  // lambda param name → Kotlin type, used by inferExprType so .size etc. resolve correctly
    internal var inlineReturnVar: String? = null  // result var name (value pos), "" (stmt pos), null (not inside inline)
    internal var inlineEndLabel: String? = null   // goto label after the inline block to handle early return
    internal var inlineLabelUsed: Boolean = false // true if at least one goto to inlineEndLabel was emitted
    internal var currentInd: String = "    "  // current emit indentation, kept in sync by emitStmt
    internal var inlineCounter: Int = 0  // counter for unique inline temp variable names and end labels
    internal val cOpaqueTypes = mutableSetOf<String>()  // c.* names used as types → compound literal ctor
    internal val topProps = mutableSetOf<String>()  // top-level property names (need pfx)
    internal val valTopProps = mutableSetOf<String>()  // top-level val properties (cannot be reassigned)
    internal val extensionFuns = mutableMapOf<String, MutableList<FunDecl>>()
    internal val extensionProps = mutableMapOf<String, MutableList<PropDecl>>()  // extension properties: recvName → props
    override val interfaces = mutableMapOf<String, IfaceInfo>()
    // Type ID registry: each class/interface gets an incrementing integer ID for is/as checks
    internal val typeIds = mutableMapOf<String, Int>()
    internal var nextTypeId = 14  // 0-13 reserved for builtin types (ktc_core.h)

    init {
        for ((i, t) in listOf(
            "Byte", "Short", "Int", "Long", "Float", "Double",
            "Boolean", "Char", "UByte", "UShort", "UInt", "ULong",
            "String", "Any"
        ).withIndex()) {
            typeIds[t] = i
        }
    }

    internal fun getTypeId(name: String): Int = typeIds.getOrPut(name) { nextTypeId++ }

    /** Returns the C expression for reading the runtime typeId from [inner] given its KtcType.
     *  Returns null when the type is statically known (concrete class) — caller emits true/false. */
    internal fun typeIdExpr(exprKtcCore: KtcType?, inner: String, memOp: String): String? = when {
        exprKtcCore is KtcType.Any -> "${inner}.typeId"
        exprKtcCore is KtcType.User && exprKtcCore.kind == KtcType.UserKind.Interface -> "${inner}.__typeId"
        exprKtcCore is KtcType.Ptr && (exprKtcCore.inner as? KtcType.User)?.kind == KtcType.UserKind.Interface -> "${inner}.typeId"
        exprKtcCore is KtcType.Ptr && exprKtcCore.inner is KtcType.Any -> "${inner}${memOp}typeId"
        else -> null  // concrete class or unknown — type is statically known
    }

    // Maps class name → synthetic companion object name (e.g. "Foo" → "Foo$Companion")
    internal val classCompanions = mutableMapOf<String, String>()

    // Generic functions: fun <T> name(...) — stored as templates
    internal val genericFunDecls = mutableListOf<FunDecl>()
    // Star-projection extension functions: fun Foo<*>.name() — stored for expansion
    internal val starExtFunDecls = mutableListOf<FunDecl>()
    // Concrete instantiations of generic functions: mangledName → (FunDecl, typeSubst)
    internal val genericFunInstantiations = mutableMapOf<String, MutableSet<List<String>>>()
    // Maps mangled generic function name → concrete class return type when the declared return
    // type is an interface but the body returns a concrete class (enables stack return)
    internal val genericFunConcreteReturn = mutableMapOf<String, String>()
    /** Check if a method on baseType has a nullable receiver declaration. */
    internal fun hasNullableReceiverExt(baseType: String, method: String): Boolean {
        val bareType = baseType.removeSuffix("*")
        // Check non-generic extension functions
        if (extensionFuns[bareType]?.any { it.name == method && it.receiver?.nullable == true } == true) return true
        // Check generic extension functions matching the base type or its interfaces
        val genericMatch = genericFunDecls.any {
            it.name == method && it.receiver?.nullable == true && (
                it.receiver.name == bareType ||
                (genericClassDecls.containsKey(it.receiver.name) && bareType.startsWith("${it.receiver.name}_")) ||
                (genericIfaceDecls.containsKey(it.receiver.name) && (
                    bareType == it.receiver.name ||
                    classInterfaces[bareType]?.contains(it.receiver.name) == true ||
                    bareType.startsWith("${it.receiver.name}_")
                ))
            )
        }
        if (genericMatch) return true
        // Check interfaces implemented by this class
        val ifaces = classInterfaces[bareType] ?: emptyList()
        return ifaces.any { iface ->
            genericFunDecls.any { gf ->
                gf.name == method && gf.receiver?.nullable == true && (
                    gf.receiver.name == iface ||
                    (genericIfaceDecls.containsKey(gf.receiver.name) && iface.startsWith("${gf.receiver.name}_"))
                )
            }
        }
    }

    // Map class name → list of interface names it implements
    override val classInterfaces = mutableMapOf<String, List<String>>()
    // Reverse map: interface name → list of class names that implement it
    override val interfaceImplementors = mutableMapOf<String, MutableList<String>>()

    // Track class/enum types used in Array<T> so we emit KT_ARRAY_DEF for them
    override val classArrayTypes = mutableSetOf<String>()

    /* Pair/Triple types are now handled entirely by stdlib — no intrinsic maps needed. */


    // ── Generics (monomorphization) ──────────────────────────────────
    // Store original ClassDecl for every class (generic and concrete) — used for secondary ctor lookup across files.
    internal val allClassDecls = mutableMapOf<String, ClassDecl>()
    // Store original ClassDecl for generic classes so we can re-emit per instantiation
    override val genericClassDecls = mutableMapOf<String, ClassDecl>()
    // Store original InterfaceDecl for generic interfaces so we can monomorphize them
    override val genericIfaceDecls = mutableMapOf<String, InterfaceDecl>()
    // Track which source file a generic declaration came from (for mem-track attribution)
    internal val declSourceFile = mutableMapOf<String, String>()
    // Track original dot-separated package for each declaration (for per-file routing)
    internal val declOrigPkg = mutableMapOf<String, String>()
    // Active type parameter substitution map during monomorphized emission (e.g. {T → Int})
    override var typeSubst: Map<String, String> = emptyMap()
    // Track all discovered concrete instantiations: "MyList" → [["Int"], ["Float"]]
    internal val genericInstantiations = mutableMapOf<String, MutableSet<List<String>>>()
    // All known type parameter names from generic classes and functions (e.g. "T", "U")
    // Used to prevent registering type params as concrete instantiations
    override val allGenericTypeParamNames = mutableSetOf<String>()

    // Reverse map: mangled class name → (baseName, typeArgs) for generic instances.
    override val mangledComponents = mutableMapOf<String, Pair<String, List<String>>>()

    /* Mangle a generic class name with concrete type args: MyList + ["Int?"] → "MyList_Int$Opt" */
    override fun mangledGenericName(inBaseName: String, inTypeArgs: List<String>): String {
        val vSanitized  = inTypeArgs.joinToString("_") { it.replace("?", "\$Opt") }  // sanitized type arg string
        val vMangledName = "${inBaseName}_$vSanitized"                                 // full mangled name
        mangledComponents[vMangledName] = Pair(inBaseName, inTypeArgs)
        return vMangledName
    }

    /* Record a concrete instantiation of a generic class and return the mangled name. */
    override fun recordGenericInstantiation(inBaseName: String, inTypeArgs: List<String>): String {
        genericInstantiations.getOrPut(inBaseName) { mutableSetOf() }.add(inTypeArgs)
        return mangledGenericName(inBaseName, inTypeArgs)
    }

    /* C name for the Optional wrapper of a generic instance.
    e.g. ArrayList + ["Int"]  → "ktc_std_ArrayList$Opt_Int"
         ArrayList + ["Int?"] → "ktc_std_ArrayList$Opt_Int$Opt" */
    internal fun genericOptionalCName(baseName: String, typeArgs: List<String>): String {
        val baseCName = typeFlatName(baseName)
        val typeArgStr = typeArgs.joinToString("_") { it.replace("?", "\$Opt") }
        return "${baseCName}\$Opt_${typeArgStr}"
    }

    // Maps mangled concrete name → type substitution (e.g. "MyList_Int" → {T: "Int"})
    internal val genericTypeBindings = mutableMapOf<String, Map<String, String>>()

    // ── Per-scope variable → LocalVar mapping ────────────────────────
    /* Each scope frame maps variable name → LocalVar (type + mutability + optional + arraySize). */
    internal val scopes = ArrayDeque<MutableMap<String, LocalVar>>()  // variable name → LocalVar
    internal fun pushScope() { scopes.addLast(mutableMapOf()) }
    internal fun popScope()  { scopes.removeLast() }

    /* Define a variable with a full LocalVar descriptor. Warns when a local var shadows a field. */
    internal fun defineVar(inName: String, inVar: LocalVar) {
        if (inVar.cName == null) {
            for (i in 0 until scopes.size - 1) {
                val vShadowed = scopes[i][inName]
                if (vShadowed?.cName != null) {
                    codegenWarning("local '$inName' shadows field '${vShadowed.cName}'")
                    break
                    }
                }
            }
        scopes.last()[inName] = inVar
        }

    /* Define a variable by KtcType only (other fields default). */
    internal fun defineVarKtc(inName: String, inType: KtcType) { scopes.last()[inName] = LocalVar(inType) }

    /* Define a variable by string type (backward-compat bridge). */
    internal fun defineVar(inName: String, inType: String) { scopes.last()[inName] = LocalVar(parseResolvedTypeName(inType)) }

    /* Narrow a variable's KtcType for a guard smart-cast, preserving mutable/optional/arraySize. */
    internal fun narrowVarType(inName: String, inType: String) {
        val vNewKtc = parseResolvedTypeName(inType)
        // Emit a C cast only for guard-pattern narrows on @Ptr Any? (equals case).
        // The old type is Nullable(Ptr(Any)) → points to the actual object, safe to cast.
        // Skip for plain Ptr(Any) → points to a ktc_Any trampoline, must use .data.
        var vCName: String? = null
        if (vNewKtc is KtcType.Ptr && vNewKtc.inner is KtcType.User) {
            for (i in scopes.indices.reversed()) {
                val vOldKtc = scopes[i][inName]?.ktc
                if (vOldKtc != null) {
                    val vIsGuardPattern = vOldKtc is KtcType.Nullable && vOldKtc.inner is KtcType.Ptr && vOldKtc.inner.inner is KtcType.Any
                    if (vIsGuardPattern && vOldKtc.toCType() != vNewKtc.toCType()) vCName = "((${vNewKtc.toCType()})${inName})"
                    break
                    }
                }
            }
        for (i in scopes.indices.reversed()) {
            scopes[i][inName]?.let { scopes[i][inName] = it.copy(ktc = vNewKtc, cName = vCName ?: it.cName); return }
            }
        scopes.lastOrNull()?.set(inName, LocalVar(vNewKtc, cName = vCName))
        }

    /* Look up the LocalVar for a variable, innermost scope first. */
    internal fun lookupLocalVar(inName: String): LocalVar? {
        for (i in scopes.indices.reversed()) { scopes[i][inName]?.let { return it } }
        return preScanVarTypes?.get(inName)?.let { LocalVar(it) }
        }

    /* Return the C access expression for a variable. Stops at the first scope that defines the name:
    returns its cName if set, otherwise the bare name (even if an outer scope has a cName). */
    internal fun lookupCName(inName: String): String {
        for (i in scopes.indices.reversed()) {
            scopes[i][inName]?.let { return it.cName ?: inName }
            }
        return inName
        }

    /* Look up a variable's KtcType (primary API). */
    internal fun lookupVarKtc(inName: String): KtcType? = lookupLocalVar(inName)?.ktc

    /* Look up a variable's type as a string (backward-compat bridge). */
    internal fun lookupVar(inName: String): String? = lookupVarKtc(inName)?.toInternalStr

    /* Record the compile-time-known element count for a local array variable. */
    internal fun defineArraySize(inName: String, inSize: Int) {
        val vScope = scopes.lastOrNull() ?: return
        vScope[inName]?.let { vScope[inName] = it.copy(arraySize = inSize) }
        }

    /* Look up the compile-time size — searches all scopes, innermost first, skipping null. */
    internal fun lookupArraySize(inName: String): Int? {
        for (i in scopes.indices.reversed()) { scopes[i][inName]?.arraySize?.let { return it } }
        return null
        }

    /* Phase 4.3: preScanVarTypes stores KtcType for pre-scan inference pass. */
    internal var preScanVarTypes: MutableMap<String, KtcType>? = null  // pre-scan variable type map

    /*
    Phase 5.1: KtcType-based TypeDef lookup helpers.
    Replace the classes[str] / interfaces[str] / objects[str] / enums[str] dispatch pattern
    with typed lookups directly from the KtcType.User.decl reference.
    Returns null for non-User types or when the underlying decl is a different TypeDef kind.
    */
    internal fun classInfoFor(inType: KtcType?): ClassInfo? =    // ClassInfo if type is a user-defined class
        (inType as? KtcType.User)?.decl as? ClassInfo

    internal fun ifaceInfoFor(inType: KtcType?): IfaceInfo? =    // IfaceInfo if type is a user-defined interface
        when (inType) {
            is KtcType.User -> inType.decl as? IfaceInfo ?: interfaces[inType.baseName]
            is KtcType.Ptr -> ifaceInfoFor(inType.inner)  // unwrap @Ptr
            else -> null
        }
    internal fun enumInfoFor(inType: KtcType?): EnumInfo? =      // EnumInfo if type is an enum class
        (inType as? KtcType.User)?.decl as? EnumInfo

    // True when an interface uses pointer-based layout (cross-package implementors exist)
    internal fun ifaceUsesPointerLayout(ifaceName: String): Boolean {
        val impls = interfaceImplementors[ifaceName] ?: return true
        if (impls.isEmpty()) return true
        val ifacePkg = interfaces[ifaceName]?.pkg ?: return true
        return impls.any {
            val implPkg = classes[it]?.pkg ?: objects[it]?.pkg ?: ""
            implPkg != ifacePkg
        }
    }

    /** Returns the ObjInfo for a DotExpr, resolving through companion objects if needed. */
    internal fun resolveDotObjInfo(dot: DotExpr): ObjInfo? {
        val name = (dot.obj as? NameExpr)?.name ?: return null
        return when {
            objects.containsKey(name) -> objects[name]
            classCompanions.containsKey(name) -> objects[classCompanions[name]!!]
            else -> null
        }
    }
    /** Returns the C flat name for a DotExpr receiver (object or companion). Null if not an object/companion. */
    internal fun resolveDotObjCName(dot: DotExpr): String? {
        val name = (dot.obj as? NameExpr)?.name
        if (name != null) {
            // Check for nested object: Parent.Child → flat name Parent$Child
            val vNestedName = "$name\$${dot.name}"
            if (vNestedName in objects) return objects[vNestedName]!!.flatName
            return objects[name]?.flatName
                ?: classCompanions[name]?.let { objects[it]?.flatName ?: typeFlatName(it) }
            }
        // Recurse into chained dots: only for nested objects (A.B where B is an object)
        if (dot.obj is DotExpr) {
            val vParent = resolveDotObjCName(dot.obj) ?: return null
            val vNestedName = "${vParent.replace('.', '$')}\$${dot.name}"
            if (objects.containsKey(vNestedName)) return objects[vNestedName]!!.flatName
            // Not a nested object — let genDot handle it as a regular property access
            return null
            }
        return null
    }

    /** Shared null-guard expression for safe-call dispatch. */
    internal fun nullGuardExpr(recvKtc: KtcType, recvExpr: String, recvName: String, isThis: Boolean): String = when (recvKtc) {
        is KtcType.Nullable if recvKtc.inner.isArrayLike && recvKtc.inner.asArr?.sized == null -> "$recvName.ptr != NULL"
        is KtcType.Nullable if recvKtc.inner is KtcType.Ptr -> "$recvName != NULL"
        is KtcType.Nullable if isValueNullableKtc(recvKtc) ->
            if (isThis) "KTC_IS_SOME(\$self)" else "KTC_IS_SOME($recvName)"

        is KtcType.Nullable -> if (isThis) "\$self\$has" else "${recvName}\$has"
        else -> "${recvExpr}\$has"
    }

    // mutable / optional flags live inside LocalVar — see markMutable / markOptional below.

    /* Mark a variable as mutable (var). Updates LocalVar in the innermost scope that defines it. */
    internal fun markMutable(name: String) {
        val vScope = scopes.lastOrNull() ?: return
        vScope[name]?.let { vScope[name] = it.copy(mutable = true) }
        }

    /* True if any scope has the variable marked mutable — smart-cast inner scopes don't reset it. */
    internal fun isMutable(name: String): Boolean = scopes.any { it[name]?.mutable == true }

    /* Mark a variable as stored in an Optional struct (value-nullable). */
    internal fun markOptional(name: String) {
        val vScope = scopes.lastOrNull() ?: return
        vScope[name]?.let { vScope[name] = it.copy(optional = true) }
        }

    /* True if any scope has the variable marked optional — smart-cast inner scopes don't reset it. */
    internal fun isOptional(name: String): Boolean = scopes.any { it[name]?.optional == true }

    // ── Current class context (when generating methods) ──────────────
    internal var fnCtx = FunctionContext()
    internal var currentClass: String?  get() = fnCtx.klass;   set(v) { fnCtx.klass = v }
    internal var currentObject: String? get() = fnCtx.currentObject; set(v) { fnCtx.currentObject = v }
    internal var selfIsPointer: Boolean get() = fnCtx.selfPtr; set(v) { fnCtx.selfPtr = v }
    // Objects with dispose methods — called on main() exit
    internal val objectsWithDispose = mutableListOf<String>()  // cName of objects with dispose
    // @Tls-annotated objects and top-level properties → emit ktc_core_tls specifier
    internal val tlsObjects       = mutableSetOf<String>()  // object names
    internal val namespaceObjects = mutableSetOf<String>()  // @Namespace object names
    internal val tlsProps = mutableSetOf<String>()    // top-level property names

    // ── Trampolined array params (pass-by-value copy on stack) ────────
    // Names of array parameters whose data has been copied via alloca+memcpy.
    // genName redirects these to their local$name copy; .size uses the trampoline field.
    internal val trampolinedParams: MutableSet<String>
        get() = fnCtx.trampolinedParams
    internal var currentExtRecvType: String? get() = fnCtx.extRecvType; set(v) { fnCtx.extRecvType = v }
    // Target type for allocWith/resizeWith inference (context from LHS)
    internal var allocTargetType: TypeRef? = null

    /* True if the variable was originally declared as Any trampoline (or Any?) and later smart-cast narrowed. */
    internal fun isAnySmartCastVar(inName: String): Boolean
        {
        val vCur = lookupVarKtc(inName) ?: return false     // current (narrowed) type
        if (vCur is KtcType.Any) return false                // not narrowed if still Any
        for (i in scopes.size - 2 downTo 0)
            {
            val vOuter = scopes[i][inName]?.ktc              // outer scope type as KtcType
            if (vOuter is KtcType.Any || (vOuter is KtcType.Nullable && vOuter.inner is KtcType.Any)
            || (vOuter is KtcType.Ptr && vOuter.inner is KtcType.Any)) return true
            if (vOuter != null) return false
            }
        return false
        }

    /*
    Returns the original interface type if this variable (or $self) was smart-cast
    from an interface to a concrete class. Used to redirect field accesses through
    the tagged union: recv.data.ConcreteClass_data.field.
    */
    internal fun isIfaceSmartCastVar(inName: String): String?
        {
        val vCur = lookupVar(inName) ?: return null     // current narrowed type
        if (interfaces.containsKey(vCur)) return null   // still typed as interface, not narrowed
        // Walk scope stack outward to find the original interface type
        for (i in scopes.size - 2 downTo 0)
            {
            val vOuter = scopes[i][inName]?.ktc?.toInternalStr ?: continue
            return if (interfaces.containsKey(vOuter)) vOuter else null
            }
        // $self in extension function: outer scope never defines $self, use currentExtRecvType
        if (inName == "\$self" && currentExtRecvType != null && interfaces.containsKey(currentExtRecvType))
            return currentExtRecvType
        return null
        }

    /* Generates the C expression to access the union data field for a narrowed interface variable. */
    internal fun ifaceUnionAccess(inIfaceName: String, inNarrowedClass: String, inRecv: String): String
        {
        val vDataName = "${typeFlatName(inNarrowedClass)}_data"     // e.g. "IsAsTest_Circle_data"
        return if (ifaceUsesPointerLayout(inIfaceName)) "$inRecv.$vDataName" else "$inRecv.data.$vDataName"
        }

    /*
    Generates the (void*) argument to pass as $self in a vtable method call for an interface receiver.
    Vtable methods expect a pointer to the concrete struct data, NOT to the interface wrapper.
    For multi-implementor: pass &recv.data (start of union = start of first member = concrete struct start)
    For 1+ implementors: pass &recv.data (union start = concrete struct start)
    For zero-implementor (fallback): pass recv.obj (old void* design)
    */
    internal fun ifaceVtableSelf(inIfaceName: String, inRecv: String, isPtr: Boolean = false): String
        {
        val deref = if (isPtr) "->" else "."
        return if (ifaceUsesPointerLayout(inIfaceName))
            "$inRecv${deref}obj"
        else
            "(void*)&$inRecv${deref}data"
        }

    /* True if type is a function pointer type: "Fun(P1,P2)->R" */
    override fun isFuncType(inT: String): Boolean = inT.startsWith("Fun(")

    /* Parse a function type string "Fun(P1,P2)->R" or "Fun(R|P1,P2)->R" (receiver function) into (paramTypes, returnType) */
    override fun parseFuncType(inT: String): Pair<List<String>, String> {
        val t = inT  // local alias so body can use `t` unchanged
        // Format: Fun(P1,P2,...)->R or Fun(R|P1,P2)->R
        val inner = t.removePrefix("Fun(")
        val parenEnd = inner.indexOf(")->")
        val paramStr = inner.substring(0, parenEnd)
        val retType = inner.substring(parenEnd + 3)
        val params = if (paramStr.isEmpty()) emptyList() else paramStr.split(",").map { it.removeSuffix("|") }
        return params to retType
    }

    /** Emit a C function pointer declaration: "retType (*name)(paramTypes)" */
    internal fun cFuncPtrDecl(t: String, name: String): String {
        val (params, ret) = parseFuncType(t)
        val cRet = cTypeStr(ret)
        val cParams = if (params.isEmpty()) "void" else params.joinToString(", ") { cTypeStr(it) }
        return "$cRet (*$name)($cParams)"
    }

	/* Emit a C function pointer declaration from a KtcType.Func. */
	internal fun cFuncPtrDecl(inKtc: KtcType.Func, inName: String): String {
		val vCRet = cTypeStr(inKtc.ret)                                                     // C return type string
		val vReceiverList = inKtc.receiver?.let { listOf(cTypeStr(it)) } ?: emptyList()     // receiver as first C param
		val vAllParams = vReceiverList + inKtc.params.map { cTypeStr(it) }                  // all C params including receiver
		val vCParams = if (vAllParams.isEmpty()) "void" else vAllParams.joinToString(", ")  // C parameter type list
		return "$vCRet (*$inName)($vCParams)"
		}

    // ── Optional type helpers ────────────────────────────────────────

    /** True if KtcType is a value-nullable (non-pointer, non-array Optional). */
    internal fun isValueNullableKtc(ktc: KtcType): Boolean = when {
        ktc !is KtcType.Nullable -> false
        ktc.inner is KtcType.Ptr -> false
        ktc.inner is KtcType.Arr -> false
        ktc.inner is KtcType.Any -> false
        else -> true
    }

    /* Maps an internal type string to its C Optional struct type name.
    Primitives: Int? → ktc_Int$Opt.
    Generic instances: ArrayList<Int>? → ktc_std_ArrayList$Opt_ktc_Int. */
    override fun optCTypeName(internalType: String): String {
        return when (val base = internalType.removeSuffix("?")) {
            "Byte"    -> $$"ktc_Byte$Opt"
            "Short"   -> $$"ktc_Short$Opt"
            "Int"     -> $$"ktc_Int$Opt"
            "Long"    -> $$"ktc_Long$Opt"
            "Float"   -> $$"ktc_Float$Opt"
            "Double"  -> $$"ktc_Double$Opt"
            "Boolean" -> $$"ktc_Bool$Opt"
            "Char"    -> $$"ktc_Char$Opt"
            "UByte"   -> $$"ktc_UByte$Opt"
            "UShort"  -> $$"ktc_UShort$Opt"
            "UInt"    -> $$"ktc_UInt$Opt"
            "ULong"   -> $$"ktc_ULong$Opt"
            "String"  -> $$"ktc_String$Opt"
            "Any"     -> "ktc_Any"   // Any uses data==NULL for null, not Optional
            else -> {
                val components = mangledComponents[base]
                if (components != null) {
                    val (genBase, typeArgs) = components
                    "${typeFlatName(genBase)}\$Opt_${typeArgs.joinToString("_") { it.replace("?", "\$Opt") }}"
                } else {
                    "${typeFlatName(base)}\$Opt"
                }
            }
        }
    }

    /* Compute the C name component for a type arg inside KTC_OPTIONAL_GENERIC_NAME.
    Non-nullable → C type name. Nullable primitive/simple → ktc_T$Optional. Nullable generic → recursive. */
    internal fun optTypeArgComponent(internalTypeArg: String): String {
        return if (internalTypeArg.endsWith("?")) {
            // Nullable type arg: recurse to get the Optional name for this type
            optCTypeName(internalTypeArg)
        } else {
            // Non-nullable type arg: use the C type name (with prefix)
            val components = mangledComponents[internalTypeArg]
            if (components != null) {
                // Nested generic (non-nullable): baseName_typeArgs
                val (genBase, typeArgs) = components
                val baseCName = typeFlatName(genBase)
                val innerStr = typeArgs.joinToString("_") { optTypeArgComponent(it) }
                "${baseCName}_${innerStr}"
            } else {
                // Non-generic non-nullable: map to C type
                when (internalTypeArg) {
                    "Byte"    -> "ktc_Byte";  "Short"   -> "ktc_Short";  "Int"     -> "ktc_Int"
                    "Long"    -> "ktc_Long";  "Float"   -> "ktc_Float";  "Double"  -> "ktc_Double"
                    "Boolean" -> "ktc_Bool";  "Char"    -> "ktc_Char";   "UByte"   -> "ktc_UByte"
                    "UShort"  -> "ktc_UShort"; "UInt"   -> "ktc_UInt";   "ULong"   -> "ktc_ULong"
                    "String"  -> "ktc_String"; "Any"    -> "ktc_Any"
                    else -> typeFlatName(internalTypeArg)
                }
            }
        }
    }

    /* Extracts the inner type from an Optional C type name.
    "ktc_Int$Opt" → "ktc_Int",  "Base$Opt$N_args" → "Base$N_args",  "ktc_Array$Opt$_T_N" → "ktc_Array_T_N" */
    internal fun optInnerType(optCType: String): String {
        val vIdx = optCType.indexOf("\$Opt\$")
        if (vIdx >= 0) {
            // Generic or array opt: check what follows "$Opt$"
            val vAfter = optCType.getOrNull(vIdx + 5)
            return if (vAfter?.isDigit() == true)
                optCType.removeRange(vIdx + 1, vIdx + 5)  // "$Opt$N..." → "$N..." (keep $)
            else
                optCType.removeRange(vIdx, vIdx + 5)       // "$Opt$_..." → "_..." (remove $Opt$)
        }
        return optCType.removeSuffix("\$Opt")              // "T$Opt" → "T"
    }

    /* Returns a C expression for "no value".
    Uses KTC_NONE macro for simple types; raw struct literal for generic opt types. */
    internal fun optNone(optCType: String): String {
        val vInner = optInnerType(optCType)
        return if ("\$" in vInner) "($optCType){ktc_NONE}"
        else "KTC_NONE($vInner)"
    }

    /* Returns a C expression for "has value".
    Uses KTC_SOME macro for simple types; raw struct literal for generic opt types. */
    internal fun optSome(optCType: String, expr: String): String {
        val vInner = optInnerType(optCType)
        return if ("\$" in vInner) "($optCType){ktc_SOME, $expr}"
        else "KTC_SOME($vInner, $expr)"
    }

    // ── Nullable return tracking ─────────────────────────────────────
    internal var currentFnReturnsNullable: Boolean   get() = fnCtx.returnsNullable;    set(v) { fnCtx.returnsNullable = v }
    internal var currentFnReturnsArray: Boolean      get() = fnCtx.returnsArray;       set(v) { fnCtx.returnsArray = v }
    internal var currentFnReturnsSizedArray: Boolean get() = fnCtx.returnsSizedArray;  set(v) { fnCtx.returnsSizedArray = v }
    internal var currentFnSizedArraySize: Int        get() = fnCtx.sizedArraySize;     set(v) { fnCtx.sizedArraySize = v }
    internal var currentFnSizedArrayElemType: KtcType? get() = fnCtx.sizedArrayElemType; set(v) { fnCtx.sizedArrayElemType = v }
    internal var currentFnReturnsSizedString: Boolean get() = fnCtx.returnsSizedString; set(v) { fnCtx.returnsSizedString = v }
    internal var currentFnSizedStringSize: Int       get() = fnCtx.sizedStringSize;    set(v) { fnCtx.sizedStringSize = v }
    internal var currentFnReturnType: String         get() = fnCtx.returnType;         set(v) { fnCtx.returnType = v }
    internal var currentFnReturnKtcType: KtcType?   get() = fnCtx.returnKtcType;      set(v) { fnCtx.returnKtcType = v }
    internal var currentFnOptReturnCTypeName: String get() = fnCtx.optReturnCTypeName; set(v) { fnCtx.optReturnCTypeName = v }
    internal var currentFnIsMain: Boolean            get() = fnCtx.isMain;             set(v) { fnCtx.isMain = v }
    internal fun currentFnReturnBaseType(): String = currentFnReturnType.removeSuffix("?")

    // ── Sized array/string struct type registry ───────────────────────
    /* (elemCType, size) pairs for KTC_DEFINE_ARRAY(T, N) emitted WITHOUT guard.
    Used only for user types defined in the current package (one canonical location). */
    internal val sizedArrayDecls = mutableSetOf<Pair<String, Int>>()         // unguarded: current-pkg user types
    /* Same pairs but emitted WITH #ifndef guard.
    Used for primitive element types and types from other packages (safe in multiple headers). */
    internal val sizedArrayGuardedDecls = mutableSetOf<Pair<String, Int>>()  // guarded: primitives / external types
    /* Sizes N for KTC_DEFINE_STRING(N) emission (always guarded – String is a primitive). */
    internal val sizedStringDecls = mutableSetOf<Int>()                      // string size N

    // ── VarArr (typed variable-size array) type registry ────────────
    /* Element C type strings for KTC_DECL_VAR_ARR(T, Name) emitted WITHOUT guard.
    Used only for user types defined in the current package. */
    internal val varArrDecls = mutableSetOf<String>()                        // unguarded: current-pkg user types
    /* Same but emitted WITH #ifndef guard for primitives and external types. */
    internal val varArrGuardedDecls = mutableSetOf<String>()                 // guarded: primitives / external types

    // ── Sized array param tracking ────────────────────────────────────
    /* Names of @Size(N) array params that arrived as ktc_Array_T_N structs and were
    unpacked to local$name pointers. Subset of trampolinedParams; checked when
    emitting .size to avoid accessing a non-existent .size field on the struct. */
    internal val sizedArrayTrampolinedParams: MutableSet<String>
        get() = fnCtx.sizedArrayTrampolinedParams

    /* Tmp var names emitted as ktc_Array_T_N structs by genArrayOfExpr (rather than raw C arrays).
    Used by the return handler to emit `return tmpVar` directly without an extra memcpy. */
    internal val arrayOfSizedStructVars = mutableSetOf<String>()

    /* Snapshot type for save/restore of function context across nested emit functions. */
    internal typealias FunState = FunctionContext
    internal fun saveFunState(): FunState = fnCtx.deepCopy()
    internal fun restoreFunState(inState: FunState) { fnCtx = inState }

    internal var loopDepth: Int get() = fnCtx.loopDepth; set(v) { fnCtx.loopDepth = v }

    // ── Source location tracking for error messages ──────────────────
    internal var currentStmtLine: Int = 0
    /** Mutable source file name for mem-track attribution.
     *  Overridden when emitting generic instantiations from other packages (e.g. stdlib). */
    internal var currentSourceFile: String = sourceFileName

    /** Last source file for which a functions banner was emitted; null = none yet. */
    internal var lastEmittedFunFile: String? = null

    /* Throw an error with source context around the given line. */
    override fun codegenError(inMsg: String): Nothing {
        val msg = inMsg  // local alias so body can use `msg` unchanged
        val line = currentStmtLine
        if (line > 0 && sourceLines.isNotEmpty()) {
            val sb = StringBuilder()
            sb.appendLine(msg)
            val from = maxOf(0, line - 3)
            val to = minOf(sourceLines.size, line + 2)
            for (i in from until to) {
                val lineNum = i + 1   // 1-indexed
                val marker = if (lineNum == line) ">>>" else "   "
                sb.appendLine("$marker %4d | %s".format(lineNum, sourceLines[i]))
            }
            error(sb.toString().trimEnd())
        } else {
            error(msg)
        }
    }

    /* Print a non-fatal warning with the same source-context display as codegenError. */
    override fun codegenWarning(inMsg: String) {
        val msg = inMsg  // local alias so body can use `msg` unchanged
        val line = currentStmtLine
        val sb = StringBuilder()

        sb.append("warning".wrapYellow())
        sb.append(": $msg")

        if (line > 0 && sourceLines.isNotEmpty()) {
            sb.appendLine()

            val from = maxOf(0, line - 3)
            val to = minOf(sourceLines.size, line + 2)

            for (i in from until to) {
                val lineNum = i + 1
                val marker = if (lineNum == line) ">>>".wrapYellow() else "   "

                sb.appendLine(
                    "$marker %4d | %s".format(lineNum, sourceLines[i])
                )
            }
        }

        System.err.print(sb.toString().trimEnd() + "\n")
        diagnosticWarningCount++
    }

    internal var diagnosticWarningCount: Int = 0  // total warnings emitted this file

    // ── Defer stack (LIFO: last deferred = first to execute) ─────────
    internal val deferStack: MutableList<Block>
        get() = fnCtx.deferStack

    /** Emit all deferred blocks in LIFO order (does NOT clear the stack). */
    internal fun emitDeferredBlocks(ind: String, insideMethod: Boolean = false) {
        for (i in deferStack.indices.reversed()) {
            for (s in deferStack[i].stmts) emitStmt(s, ind, insideMethod)
        }
    }

    // ── Temp counter for stack buffers ───────────────────────────────
    internal var tmpCounter = 0
    internal fun tmp(): String = "$${tmpCounter++}"

    // ── Memory tracking helpers (Kotlin source attribution) ──────────
    /* Raw const char* location for direct C calls (ktc_core_malloc/free/realloc) */
    internal fun ktSrc(): String = "\"$currentSourceFile\", $currentStmtLine"
    /* ktc_String location for Allocator vtable calls (file param is String) */
    internal fun ktSrcStr(): String = "ktc_core_str(\"$currentSourceFile\"), $currentStmtLine"
    internal fun tMalloc(sizeExpr: String) = if (memTrack) "ktc_core_malloc($sizeExpr, ${ktSrc()})" else "malloc($sizeExpr)"
    internal fun tCalloc(nExpr: String, sizeExpr: String) = if (memTrack) "ktc_core_calloc($nExpr, $sizeExpr, ${ktSrc()})" else "calloc($nExpr, $sizeExpr)"
    internal fun tRealloc(ptrExpr: String, sizeExpr: String) = if (memTrack) "ktc_core_realloc($ptrExpr, $sizeExpr, ${ktSrc()})" else "realloc($ptrExpr, $sizeExpr)"
    internal fun tFree(ptrExpr: String) = if (memTrack) "ktc_core_free($ptrExpr, ${ktSrc()})" else "free($ptrExpr)"

    // ── Pre-statements (hoisted before the current statement) ────────
    internal val preStmts = mutableListOf<String>()
    internal fun flushPreStmts(ind: String) {
        for (s in preStmts) impl.appendLine("$ind$s")
        preStmts.clear()
    }

    // ── Output sections (backed by CodeBuilder) ─────────────────────
    internal val cb = CodeBuilder()                                      // all output buffer state

    // Delegate properties — keep all call sites in emit/expr files unchanged.
    // Per-declaration impl accumulators: keyed by declaration simple name (e.g. "Foo");
    // inner classes and companions share the parent's buffers via rootDeclKey().
    internal val hdr     get() = cb.hdr                                  // .h forward decls & typedefs
    internal var impl: StringBuilder                                      // active .c impl target (swapped per-decl)
        get()      = cb.impl
        set(v)     { cb.impl = v }
    internal var implFwd: StringBuilder                                   // active .c private-fwd target (swapped per-decl)
        get()      = cb.implFwd
        set(v)     { cb.implFwd = v }
    internal val perDeclImpl    get() = cb.perDeclImpl                   // per-decl impl content
    internal val perDeclImplFwd get() = cb.perDeclImplFwd               // per-decl implFwd content
    internal val deferredAsCalls         get() = cb.deferredAsCalls      // as_* cast functions, keyed by root decl name
    internal val deferredObjIfaceMethods get() = cb.deferredObjIfaceMethods  // (objectName, ifaceName) → impl content

    /*
    Returns the root key for a declaration name.
    Inner classes / companions ("Foo$Bar") map to their parent ("Foo")
    so they share the parent's .c file.
    Generic instantiation names like "Pair_Int$Opt_String$Opt" also contain '$'
    (the $Opt nullable suffix) but are NOT inner classes; they get their own file.
    We distinguish by checking whether the substring before '$' is a known class/object.
    */
    internal fun rootDeclKey(inName: String): String {
        val vDollar = inName.indexOf('$')
        if (vDollar < 0) return inName                      // no '$' — top-level name
        val vParent = inName.substring(0, vDollar)
        return if (classes.containsKey(vParent) || objects.containsKey(vParent))
            vParent     // confirmed inner class / companion → share parent file
        else
            inName      // '$' from type-arg mangling ($Opt etc.) → own file
        }

    /*
    Routes impl and implFwd to the per-decl buffers for inKey, runs inBlock,
    then restores the previous impl/implFwd.
    Nested calls with the same root key append to the same buffer (getOrPut semantics).
    */
    internal fun captureForDecl(
        inKey: String,     // declaration name; "" for top-level functions
        inBlock: () -> Unit
        ) {
        val vKey = rootDeclKey(inKey)                                     // route inner classes to parent
        cb.captureForDecl(vKey, inBlock)
        }

    // ═══════════════════ array ptr / len helpers ═══════════════════

    /* Return the C expression for the data pointer of an array expression.
       VarArr structs (isArrayLike && !sized): use .ptr member.
       Everything else (sized arrays, raw pointers, non-arrays): expression itself. */
    internal fun arrayDataPtr(expr: String, srcKtc: KtcType?): String =
        if (srcKtc != null && srcKtc.isArrayLike && srcKtc.asArr?.sized == null) "($expr).ptr" else expr

    /* Return the C expression for the length of an array expression.
       Sized arrays: the constant size from the annotation.
       VarArr structs: use .len member.
       Non-arrays: "0" (should not be called). */
    internal fun arrayDataLen(expr: String, srcKtc: KtcType?): String {
        val sized = srcKtc?.asArr?.sized
        if (sized != null) return "$sized"
        return if (srcKtc != null && srcKtc.isArrayLike) "($expr).len" else "0"
    }

    /** Resolve the best inline extension function overload for a call.
     *  Disambiguates first by receiver type, then by argument count. */
    internal fun findInlineExtFun(name: String, receiverType: String?, argCount: Int = -1): FunDecl? {
        val vCandidates = inlineExtFunDecls[name] ?: return null
        if (vCandidates.size == 1) return vCandidates[0]
        val vFlat = receiverType?.replace('.', '$')
        val vByReceiver = if (vFlat != null) {
            vCandidates.filter { decl ->
                val vRecv = decl.receiver ?: return@filter true
                val vRecvFlat = vRecv.name.replace('.', '$')
                vRecvFlat == vFlat || vRecv.name == receiverType
            }
        } else vCandidates
        val vPool = vByReceiver.ifEmpty { vCandidates }
        if (vPool.size == 1) return vPool[0]
        if (argCount >= 0) {
            val vExact = vPool.find { it.params.size == argCount }
            if (vExact != null) return vExact
        }
        return vPool[0]
        }

    // ═══════════════════════════ Public entry ═════════════════════════
    // collectAndScan() and generate() are extension functions in CCodeGenGenerate.kt.
    // Main.kt calls: gen.collectAndScan() and CCodeGen(...).generate()

    }
