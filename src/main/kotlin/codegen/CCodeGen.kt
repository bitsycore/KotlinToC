package com.bitsycore.ktc.codegen

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.codegen.emit.*
import com.bitsycore.ktc.codegen.expr.emitStmt
import com.bitsycore.ktc.codegen.expr.inferBlockType
import com.bitsycore.ktc.codegen.expr.inferInitType
import com.bitsycore.ktc.types.KtcType
import com.bitsycore.ktc.utils.wrapYellow

/**
 * Translates a parsed KtFile AST into C11 source code.
 *
 * ## Pipeline
 *
 * ```
 * Kotlin source → Lexer → Parser → AST → CCodeGen → .c/.h files
 * ```
 *
 * ## Architecture (split across 8 files, all in package `com.bitsycore`)
 *
 * | File                 | Lines | Role                                             |
 * |----------------------|-------|--------------------------------------------------|
 * | `CCodeGen.kt`        | 1043  | **Orchestrator**: state, `collectDecls()`, `generate()` |
 * | `CCodeGenStructures.kt` |  60 | Data classes (ClassInfo, BodyProp, etc.)          |
 * | `CCodeGenScan.kt`    |   789 | Pre-scanning: discover generic instantiations     |
 * | `CCodeGenEmit.kt`    |  1497 | Declaration emission: classes, functions, vtables |
 * | `CCodeGenStmts.kt`   |  1351 | Statement codegen: var/if/for/return/inline        |
 * | `CCodeGenExpr.kt`    |  2124 | Expression codegen: genExpr → genCall/genDot/...  |
 * | `CCodeGenInfer.kt`   |   460 | Type inference: inferExprType → inferCallType/... |
 * | `CCodeGenCTypes.kt`  |   574 | C type mapping: resolveTypeName, cTypeStr, printf |
 *
 * ## State conventions
 *
 * All functions across all files are extension functions on `CCodeGen`.
 * State members are `internal` (not `private`) so extension functions
 * in other files can access them.
 *
 * ## Pipeline phases (called by `generate()`)
 *
 * 1. **collectDecls()** — populate symbol tables
 * 2. **scanForClassArrayTypes()** — discover class types in Array<T>
 * 3. **scanForGenericInstantiations()** — find concrete generic usage
 * 4. **materializeGenericInstantiations()** — create concrete ClassInfo
 * 5. **scanForGenericFunCalls()** — discover generic function call sites
 * 6. **scanGenericFunBodiesForInstantiations()** — transitive discovery
 * 7. **scanGenericClassMethodBodiesForInstantiations()** — from materialized
 * 8. **computeGenericFunConcreteReturns()** — interface → concrete return
 * 9. **Emit declarations** — structs, functions, vtables, methods
 * 10. **Output assembly** — .h and .c strings
 *
 * ## Symbol naming
 *
 *   package game          →  game_ClassName, game_funcName
 *   package com.foo.bar   →  com_foo_bar_ClassName
 *   (no package)          →  bare names
 *
 * `fun main()` is never prefixed — always emits `int main(void)`.
 */

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
    // Data classes now in CCodeGenStructures.kt

    override val classes  = mutableMapOf<String, ClassInfo>()
    override val enums    = mutableMapOf<String, EnumInfo>()
    internal val enumValuesCalled  = mutableSetOf<String>()
    internal val enumValueOfCalled = mutableSetOf<String>()
    override val objects  = mutableMapOf<String, ObjInfo>()
    internal val funSigs  = mutableMapOf<String, FunSig>()
    internal val funNames = mutableMapOf<String, String>()  // top-level function name → C name
    internal val inlineFunDecls = mutableMapOf<String, MutableList<FunDecl>>()
    internal val inlineExtFunDecls = mutableMapOf<String, FunDecl>()  // inline generic extension funs, keyed by method name
    internal var activeLambdas: Map<String, ActiveLambda> = emptyMap()
    internal val lambdaParamSubst = mutableMapOf<String, String>()  // also stores "\$this" → receiver C expr during inline ext expansion
    // Deferred hdr declarations: className → list of hdr lines (for methods moved to implements section)
    /* Deferred header lines per class: (ifaceDisplayName, headerLine) pairs.
    ifaceDisplayName is the Kotlin type-ref string, e.g. "Iterator<Float?>", or "" for non-iface methods. */
    internal val deferredHdrLines = mutableMapOf<String, MutableList<Pair<String, String>>>()
    internal val lambdaParamTypes = mutableMapOf<String, String>()  // lambda param name → Kotlin type, used by inferExprType so .size etc. resolve correctly
    internal var inlineReturnVar: String? = null  // result var name (value pos), "" (stmt pos), null (not inside inline)
    internal var inlineEndLabel: String? = null   // goto label after the inline block to handle early return
    internal var currentInd: String = "    "  // current emit indentation, kept in sync by emitStmt
    internal var inlineCounter: Int = 0  // counter for unique inline temp variable names and end labels
    internal val topProps = mutableSetOf<String>()  // top-level property names (need pfx)
    internal val valTopProps = mutableSetOf<String>()  // top-level val properties (cannot be reassigned)
    internal val extensionFuns = mutableMapOf<String, MutableList<FunDecl>>()
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

    // ── Per-scope variable → type mapping ────────────────────────────
    /* Phase 4.3: scopes store KtcType; string interface kept via toInternalStr bridge. */
    internal val scopes = ArrayDeque<MutableMap<String, KtcType>>()  // variable name → KtcType
    internal val arraySizeScopes = ArrayDeque<MutableMap<String, Int>>()  // variable name → inferred literal array size
    internal fun pushScope() { scopes.addLast(mutableMapOf()); optValVarNames.addLast(mutableSetOf()); mutableVarScopes.addLast(mutableSetOf()); arraySizeScopes.addLast(mutableMapOf()) }
    internal fun popScope()  { scopes.removeLast(); optValVarNames.removeLast(); mutableVarScopes.removeLast(); arraySizeScopes.removeLast() }

    /* Record the compile-time-known element count for a local array variable. */
    internal fun defineArraySize(inName: String, inSize: Int) { if (arraySizeScopes.isNotEmpty()) arraySizeScopes.last()[inName] = inSize }

    /* Look up the compile-time size of a local array variable across all scopes, innermost first. */
    internal fun lookupArraySize(inName: String): Int? = arraySizeScopes.lastOrNull { it.containsKey(inName) }?.get(inName)

    /* Store a variable type using a KtcType (Phase 4.3+ primary API). */
    internal fun defineVarKtc(inName: String, inType: KtcType) { scopes.last()[inName] = inType }

    /* Store a variable type using a string (backward-compat bridge, converts via parseResolvedTypeName). */
    internal fun defineVar(inName: String, inType: String) { scopes.last()[inName] = parseResolvedTypeName(inType) }

    /* Look up a variable's KtcType (Phase 4.3+ primary API). */
    internal fun lookupVarKtc(inName: String): KtcType?
        {
        for (i in scopes.indices.reversed()) { scopes[i][inName]?.let { return it } }
        return preScanVarTypes?.get(inName)
        }

    /* Look up a variable's type as a string (backward-compat bridge, converts via toInternalStr). */
    internal fun lookupVar(inName: String): String? = lookupVarKtc(inName)?.toInternalStr

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
        val name = (dot.obj as? NameExpr)?.name ?: return null
        return objects[name]?.flatName
            ?: classCompanions[name]?.let { objects[it]?.flatName ?: typeFlatName(it) }
    }

    /** Shared null-guard expression for safe-call dispatch. */
    internal fun nullGuardExpr(recvKtc: KtcType, recvExpr: String, recvName: String, isThis: Boolean): String = when (recvKtc) {
        is KtcType.Nullable if recvKtc.inner is KtcType.Ptr -> "$recvName != NULL"
        is KtcType.Nullable if isValueNullableKtc(recvKtc) ->
            if (isThis) "KTC_IS_SOME(\$self)" else "KTC_IS_SOME($recvName)"

        is KtcType.Nullable -> if (isThis) "\$self\$has" else "${recvName}\$has"
        else -> "${recvExpr}\$has"
    }

    // Track mutable (var) variables — smart casts are only valid on val, val reassignment is an error.
    // Scoped: each pushScope() adds a new set, popScope() removes it.
    internal val mutableVarScopes = ArrayDeque<MutableSet<String>>()
    internal fun markMutable(name: String) { mutableVarScopes.lastOrNull()?.add(name) }
    internal fun isMutable(name: String): Boolean = mutableVarScopes.any { name in it }

    // Track variables stored as Optional structs (value-nullable T? → OptT in C).
    // When a variable is in this set but its current scope type is non-nullable (smart cast),
    // genName returns name.value to unwrap the Optional.
    // Scoped: each pushScope() adds a new set, popScope() removes it, so allocations never leak across scopes.
    internal val optValVarNames = ArrayDeque<MutableSet<String>>()
    internal fun markOptional(name: String) { optValVarNames.lastOrNull()?.add(name) }
    internal fun isOptional(name: String): Boolean = optValVarNames.any { name in it }

    // ── Current class context (when generating methods) ──────────────
    internal var currentClass: String? = null
    internal var currentObject: String? = null
    internal var selfIsPointer = true
    // Objects with dispose methods — called on main() exit
    internal val objectsWithDispose = mutableListOf<String>()  // cName of objects with dispose
    // @Tls-annotated objects and top-level properties → emit ktc_core_tls specifier
    internal val tlsObjects = mutableSetOf<String>()  // object names
    internal val tlsProps = mutableSetOf<String>()    // top-level property names

    // ── Trampolined array params (pass-by-value copy on stack) ────────
    // Names of array parameters whose data has been copied via alloca+memcpy.
    // genName redirects these to their local$name copy; .size uses the trampoline field.
    internal val trampolinedParams = mutableSetOf<String>()
    internal var currentExtRecvType: String? = null
    // Target type for HeapAlloc/HeapArrayZero/HeapArrayResize inference (context from LHS)
    internal var heapAllocTargetType: TypeRef? = null

    /* True if the variable was originally declared as Any trampoline (or Any?) and later smart-cast narrowed. */
    internal fun isAnySmartCastVar(inName: String): Boolean
        {
        val vCur = lookupVarKtc(inName) ?: return false     // current (narrowed) type
        if (vCur is KtcType.Any) return false                // not narrowed if still Any
        for (i in scopes.size - 2 downTo 0)
            {
            val vOuter = scopes[i][inName]                   // outer scope type as KtcType
            if (vOuter is KtcType.Any || (vOuter is KtcType.Nullable && vOuter.inner is KtcType.Any)) return true
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
            val vOuter = scopes[i][inName]?.toInternalStr ?: continue
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
        val vImpls = interfaceImplementors[inIfaceName]             // list of implementors for this interface
        val vDataName = "${typeFlatName(inNarrowedClass)}_data"     // e.g. "IsAsTest_Circle_data"
        return if (vImpls.isNullOrEmpty()) "$inRecv.$vDataName" else "$inRecv.data.$vDataName"
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
        val vImpls = interfaceImplementors[inIfaceName]
        val deref = if (isPtr) "->" else "."
        return when
            {
            vImpls.isNullOrEmpty() -> if (isPtr) "$inRecv->obj" else "$inRecv.obj"
            else -> "(void*)&$inRecv${deref}data"
            }
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
    internal var currentFnReturnsNullable = false
    internal var currentFnReturnsArray = false
    internal var currentFnReturnsSizedArray = false
    internal var currentFnSizedArraySize = 0
    internal var currentFnSizedArrayElemType = ""
    internal var currentFnReturnsSizedString = false        // true when function returns @Size(N) String
    internal var currentFnSizedStringSize = 0              // N for @Size(N) String return
    internal var currentFnReturnType: String = ""
    internal var currentFnReturnKtcType: KtcType? = null  // KtcType counterpart for pattern matching
    internal var currentFnOptReturnCTypeName: String = ""  // Optional C type for nullable returns
    internal var currentFnIsMain = false
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

    // ── Sized array param tracking ────────────────────────────────────
    /* Names of @Size(N) array params that arrived as ktc_Array_T_N structs and were
    unpacked to local$name pointers. Subset of trampolinedParams; checked when
    emitting .size to avoid accessing a non-existent .size field on the struct. */
    internal val sizedArrayTrampolinedParams = mutableSetOf<String>() // sized struct param names

    /* Tmp var names emitted as ktc_Array_T_N structs by genArrayOfExpr (rather than raw C arrays).
    Used by the return handler to emit `return tmpVar` directly without an extra memcpy. */
    internal val arrayOfSizedStructVars = mutableSetOf<String>()

    /** Snapshot of current function state for save/restore across emit functions. */
    internal data class FunState(
        var returnsNullable: Boolean,          // true when function returns a nullable type
        var returnsArray: Boolean,             // true when function returns a variable-length array
        var returnsSizedArray: Boolean,        // true when function returns @Size(N) array struct
        var sizedArraySize: Int,               // N for @Size(N) array return
        var sizedArrayElemType: String,        // element C type for @Size(N) array return
        var returnsSizedString: Boolean,       // true when function returns @Size(N) String struct
        var sizedStringSize: Int,              // N for @Size(N) String return
        var returnType: String,                // C return type string (legacy)
        var returnKtcType: KtcType?,           // KtcType of return (for pattern matching)
        var optReturnCTypeName: String,        // Optional C type for nullable returns
        var klass: String?,                    // current class context
        var selfPtr: Boolean,                  // true when $self is a pointer
        var extRecvType: String?,              // extension receiver type name
    )
    internal fun saveFunState() = FunState(
        currentFnReturnsNullable, currentFnReturnsArray, currentFnReturnsSizedArray,
        currentFnSizedArraySize, currentFnSizedArrayElemType,
        currentFnReturnsSizedString, currentFnSizedStringSize,
        currentFnReturnType, currentFnReturnKtcType, currentFnOptReturnCTypeName,
        currentClass, selfIsPointer, currentExtRecvType
    )
    internal fun restoreFunState(s: FunState) {
        currentFnReturnsNullable = s.returnsNullable
        currentFnReturnsArray = s.returnsArray
        currentFnReturnsSizedArray = s.returnsSizedArray
        currentFnSizedArraySize = s.sizedArraySize
        currentFnSizedArrayElemType = s.sizedArrayElemType
        currentFnReturnsSizedString = s.returnsSizedString
        currentFnSizedStringSize = s.sizedStringSize
        currentFnReturnType = s.returnType
        currentFnReturnKtcType = s.returnKtcType
        currentFnOptReturnCTypeName = s.optReturnCTypeName
        currentClass = s.klass
        selfIsPointer = s.selfPtr
        currentExtRecvType = s.extRecvType
    }

    internal var loopDepth: Int = 0  // nesting depth of active for/while/do-while loops

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
    internal val deferStack = mutableListOf<Block>()

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

    // ═══════════════════════════ Public entry ═════════════════════════

    fun collectAndScan() {
        collectDecls()
        scanForClassArrayTypes()
        scanForGenericInstantiations()
        materializeGenericInstantiations()
        scanForGenericFunCalls()
        scanGenericFunBodiesForInstantiations()
        materializeGenericInstantiations()
        scanGenericClassMethodBodiesForInstantiations()
        materializeGenericInstantiations()
        computeGenericFunConcreteReturns()
    }

    fun dumpSemantics(): String {
        val sb = StringBuilder()
        sb.appendLine("╔══════════════════════════════════════════════════╗")
        sb.appendLine("║  Semantic Analysis  —  ${file.sourceFile}".padEnd(49) + "║")
        sb.appendLine("╚══════════════════════════════════════════════════╝")
        sb.appendLine()

        // ── Package & imports ──
        if (file.pkg != null) sb.appendLine("package ${file.pkg}")
        if (file.imports.isNotEmpty()) {
            for (imp in file.imports) sb.appendLine("import $imp")
            sb.appendLine()
        }

        // ── Classes ──
        if (classes.isNotEmpty()) {
            sb.appendLine("── Classes ──")
            for ((name, ci) in classes) {
                val tp = if (ci.typeParams.isNotEmpty()) "<${ci.typeParams.joinToString(", ")}>" else ""
                val ifaces = classInterfaces[name]?.joinToString(", ") ?: ""
                val ifs = if (ifaces.isNotEmpty()) " : $ifaces" else ""
                val data = if (ci.isData) "(data) " else ""
                sb.appendLine("  ${data}class $name$tp$ifs")
                sb.appendLine("    type_id: ${typeIds[name]}")
                for ((pn, pt) in ci.props) {
                    val priv = if (pn in ci.privateProps) "private " else ""
                    val valMark = if (ci.isValProp(pn)) "val" else "var"
                    sb.appendLine("    $priv$valMark $pn: ${typeRefToStr(pt)}")
                }
				if (ci.ctorPlainParams.isNotEmpty())
					{
					for (vParam in ci.ctorPlainParams)
						{
						sb.appendLine("    param ${vParam.name}: ${typeRefToStr(vParam.typeRef)}")
						}
					}
                for (m in ci.methods) {
                    val priv = if (m.isPrivate) "private " else ""
                    val op = if (m.isOperator) "operator " else ""
                    val tp2 = if (m.typeParams.isNotEmpty()) "<${m.typeParams.joinToString(", ")}>" else ""
                    val ret = m.returnType?.let { ": ${typeRefToStr(it)}" } ?: ""
                    val params = m.params.joinToString(", ") { "${it.name}: ${typeRefToStr(it.type)}" }
                    sb.appendLine("    ${priv}${op}fun $tp2${m.name}($params)$ret")
                }
            }
            sb.appendLine()
        }

        // ── Interfaces ──
        if (interfaces.isNotEmpty()) {
            sb.appendLine("── Interfaces ──")
            for ((name, iface) in interfaces) {
                val tp = if (iface.typeParams.isNotEmpty()) "<${iface.typeParams.joinToString(", ")}>" else ""
                val sup = if (iface.superInterfaces.isNotEmpty())
                    " : ${iface.superInterfaces.joinToString(", ") { typeRefToStr(it) }}" else ""
                sb.appendLine("  interface $name$tp$sup")
                for (p in iface.propDecls) {
                    val mut = if (p.mutable) "var" else "val"
                    val pt = if (p.type != null) ": ${typeRefToStr(p.type)}" else ""
                    sb.appendLine("    $mut ${p.name}$pt")
                }
                for (m in iface.methods) {
                    val op = if (m.isOperator) "operator " else ""
                    val tp2 = if (m.typeParams.isNotEmpty()) "<${m.typeParams.joinToString(", ")}>" else ""
                    val ret = m.returnType?.let { ": ${typeRefToStr(it)}" } ?: ""
                    val params = m.params.joinToString(", ") { "${it.name}: ${typeRefToStr(it.type)}" }
                    sb.appendLine("    ${op}fun $tp2${m.name}($params)$ret")
                }
            }
            sb.appendLine()
        }

        // ── Enums ──
        if (enums.isNotEmpty()) {
            sb.appendLine("── Enums ──")
            for ((name, ei) in enums) {
                sb.appendLine("  enum $name { ${ei.entries.joinToString(", ")} }")
            }
            sb.appendLine()
        }

        // ── Objects ──
        if (objects.isNotEmpty()) {
            sb.appendLine("── Objects ──")
            for ((name, oi) in objects) {
                sb.appendLine("  object $name")
                for ((pn, pt) in oi.props) {
                    sb.appendLine("    val $pn: $pt")
                }
                for (m in oi.methods) {
                    val ret = m.returnType?.let { ": ${typeRefToStr(it)}" } ?: ""
                    val params = m.params.joinToString(", ") { "${it.name}: ${typeRefToStr(it.type)}" }
                    sb.appendLine("    fun ${m.name}($params)$ret")
                }
            }
            sb.appendLine()
        }

        // ── Functions ──
        if (funSigs.isNotEmpty()) {
            sb.appendLine("── Function Signatures ──")
            for ((name, sig) in funSigs) {
                val ret = sig.returnType?.let { ": ${typeRefToStr(it)}" } ?: ""
                val params = sig.params.joinToString(", ") { "${it.name}: ${typeRefToStr(it.type)}" }
                val src = declSourceFile[name]?.let { "  // $it" } ?: ""
                sb.appendLine("  fun $name($params)$ret$src")
            }
            sb.appendLine()
        }

        // ── Extension functions ──
        if (extensionFuns.isNotEmpty()) {
            sb.appendLine("── Extension Functions ──")
            for ((typeName, funs) in extensionFuns) {
                for (f in funs) {
                    val ret = f.returnType?.let { ": ${typeRefToStr(it)}" } ?: ""
                    val params = f.params.joinToString(", ") { "${it.name}: ${typeRefToStr(it.type)}" }
                    sb.appendLine("  fun $typeName.${f.name}($params)$ret")
                }
            }
            sb.appendLine()
        }

        // ── Generic class templates ──
        if (genericClassDecls.isNotEmpty()) {
            sb.appendLine("── Generic Class Templates (un-instantiated) ──")
            for ((name, decl) in genericClassDecls) {
                val tps = decl.typeParams.joinToString(", ")
                val src = declSourceFile[name]?.let { "  // $it" } ?: ""
                sb.appendLine("  $name<$tps>$src")
            }
            sb.appendLine()
        }

        // ── Generic instantiations ──
        if (genericInstantiations.isNotEmpty()) {
            sb.appendLine("── Generic Class Instantiations ──")
            for ((base, insts) in genericInstantiations) {
                for (typeArgs in insts) {
                    val bindings = genericTypeBindings[mangledGenericName(base, typeArgs)]
                    sb.appendLine("  $base<${typeArgs.joinToString(", ")}>")
                    if (bindings != null) {
                        sb.appendLine("    subst: ${bindings.entries.joinToString(", ") { "${it.key}→${it.value}" }}")
                    }
                }
            }
            sb.appendLine()
        }

        // ── Generic functions ──
        if (genericFunDecls.isNotEmpty()) {
            sb.appendLine("── Generic Function Templates ──")
            for (f in genericFunDecls) {
                val tps = "<${f.typeParams.joinToString(", ")}>"
                val params = f.params.joinToString(", ") { p ->
                    val va = if (p.isVararg) "vararg " else ""
                    val def = if (p.default != null) " = ..." else ""
                    "$va${p.name}: ${typeRefToStr(p.type)}$def"
                }
                val ret = f.returnType?.let { ": ${typeRefToStr(it)}" } ?: ""
                val recv = if (f.receiver != null) "${typeRefToStr(f.receiver)}." else ""
                val src = declSourceFile[f.name]?.let { "  // $it" } ?: ""
                sb.appendLine("  fun $tps $recv${f.name}($params)$ret$src")
            }
            sb.appendLine()
        }

        if (genericFunInstantiations.isNotEmpty()) {
            sb.appendLine("── Generic Function Instantiations ──")
            for ((mangled, typeArgsList) in genericFunInstantiations) {
                for (typeArgs in typeArgsList) {
                    sb.appendLine("  $mangled(${typeArgs.joinToString(", ")})")
                }
            }
            sb.appendLine()
        }

        // ── Generic function concrete returns ──
        if (genericFunConcreteReturn.isNotEmpty()) {
            sb.appendLine("── Generic Function Concrete Returns ──")
            for ((mangled, ret) in genericFunConcreteReturn) {
                sb.appendLine("  $mangled → $ret")
            }
            sb.appendLine()
        }

        // ── Interface implementors ──
        if (interfaceImplementors.isNotEmpty()) {
            sb.appendLine("── Interface Implementors ──")
            for ((iface, impls) in interfaceImplementors) {
                sb.appendLine("  $iface ← ${impls.joinToString(", ")}")
            }
            sb.appendLine()
        }

        // ── Companion objects ──
        if (classCompanions.isNotEmpty()) {
            sb.appendLine("── Companion Objects ──")
            for ((cls, companion) in classCompanions) {
                sb.appendLine("  $cls → $companion")
            }
            sb.appendLine()
        }

        // ── Class array types ──
        if (classArrayTypes.isNotEmpty()) {
            sb.appendLine("── Class Array Types ──")
            sb.appendLine("  ${classArrayTypes.joinToString(", ")}")
            sb.appendLine()
        }

        return sb.toString()
    }

    fun generate(): COutput {
        /* @file:DocumentationOnly files provide type information to other files but
        produce no C output themselves — the real implementations live in ktc_core. */
        if (file.documentationOnly) return COutput("", emptyMap())

        collectDecls()

        hdr.appendLine("#pragma once")
        if (memTrack) hdr.appendLine("#define KTC_MEM_TRACK")
        when (disposedMode) {
            "ASSERT" -> hdr.appendLine("#define KTC_DISPOSED_ASSERT")
            "LOG"    -> hdr.appendLine("#define KTC_DISPOSED_LOG")
        }
        when (doubleDisposeMode) {
            "ASSERT" -> hdr.appendLine("#define KTC_DOUBLE_DISPOSE_ASSERT")
            "LOG"    -> hdr.appendLine("#define KTC_DOUBLE_DISPOSE_LOG")
        }
        // All includes are file-relative — no -iquote flag needed.
        val vFromDir = file.pkg?.replace('.', '/') ?: "" // directory of this _package_.h, relative to outDir
        hdr.appendLine("#include \"${relIncludePath(vFromDir, "ktc/core/ktc_core.h")}\"")
        hdr.appendLine()

        // Imports → #include (skip ktc.std imports — auto-included below)
        for (imp in file.imports) {
            if (imp.startsWith("ktc.std") || imp.startsWith("ktc_std")) continue
            val parts = imp.removeSuffix(".*").split('.')
            val headerPath = parts.joinToString("/")
            hdr.appendLine("#include \"${relIncludePath(vFromDir, "$headerPath/_package_.h")}\"")
        }
        // Auto-include ktc/std/_package_.h for non-stdlib packages when stdlib is present
        val hasStdlib = allFiles.any { it.pkg == "ktc.std" }
        if (hasStdlib && file.pkg != "ktc.std") {
            hdr.appendLine("#include \"${relIncludePath(vFromDir, "ktc/std/_package_.h")}\"")
        }
        hdr.appendLine()

        // Placeholder for primitive/external/string KTC_DEFINE_ARRAY|STRING — replaced after emission.
        // Must appear before forward declarations. User-type arrays use @SIZED_TYPES_USER@ instead
        // (placed after class definitions, since KTC_DEFINE_ARRAY requires the element type to be complete).
        hdr.appendLine("/* @SIZED_TYPES@ */")
        hdr.appendLine()

        // Pre-scan for Array<T> type references to discover class array types early
        scanForClassArrayTypes()

        // Pre-scan for generic class instantiations and materialize concrete types
        scanForGenericInstantiations()
        materializeGenericInstantiations()

        // Scan for generic function call sites (must happen after materialization
        // so we know concrete class types for argument type inference)
        scanForGenericFunCalls()

        // Scan generic function bodies for class instantiations that only become
        // concrete after type substitution (e.g. ArrayList<T>() inside listOf<T>
        // becomes ArrayList<Int> when listOf is called with Int)
        scanGenericFunBodiesForInstantiations()
        materializeGenericInstantiations()

        // Scan materialized generic class method bodies for further generic instantiations
        // (e.g., HashMap<Int,String>.iterator() creates MapIterator<Int,String>)
        scanGenericClassMethodBodiesForInstantiations()
        materializeGenericInstantiations()

        // Pre-compute concrete return types for generic functions returning interfaces
        // (enables returning concrete class by value on stack instead of heap-allocating)
        computeGenericFunConcreteReturns()

        // Forward-declare all concrete interface types and monomorphized generic class types.
        // Everything is grouped here so all typedef struct decls appear before any definitions.
        data class FwdDecl(val vCName: String, val vSrc: String) // one forward declaration line
        val vFwdDecls = mutableListOf<FwdDecl>()
        for ((name, info) in interfaces) {
            if (info.typeParams.isNotEmpty()) continue  // skip templates
            val cName = typeFlatName(name)
            val vSrc = declSourceFile[name]?.let { " // $it" } ?: "" // source origin comment
            vFwdDecls.add(FwdDecl(cName, vSrc))
            vFwdDecls.add(FwdDecl("${cName}_vt", vSrc))
            val vComponents = mangledComponents[name]
            if (vComponents != null) {
                val (vGenBase, vTypeArgs) = vComponents
                vFwdDecls.add(FwdDecl(genericOptionalCName(vGenBase, vTypeArgs), vSrc))
            }
        }
        for ((baseName, instantiations) in genericInstantiations) {
            if (!genericClassDecls.containsKey(baseName)) continue
            for (typeArgs in instantiations) {
                val vMangledName = mangledGenericName(baseName, typeArgs) // mangled instantiation name
                val vCName = typeFlatName(vMangledName)
                val vSrc = declSourceFile[baseName]?.let { " // $it" } ?: "" // source origin comment
                vFwdDecls.add(FwdDecl(vCName, vSrc))
                vFwdDecls.add(FwdDecl(genericOptionalCName(baseName, typeArgs), vSrc))
            }
        }
        if (vFwdDecls.isNotEmpty()) {
            hdr.appendLine("/* $kHdrRule")
            hdr.appendLine(" * forward declarations")
            hdr.appendLine(" * $kHdrRule */")
            for (vFd in vFwdDecls) hdr.appendLine("typedef struct ${vFd.vCName} ${vFd.vCName};${vFd.vSrc}")
            hdr.appendLine()
        }

        // Emit struct/enum/object declarations (defines the element types needed by generic interfaces)
        // Skip generic templates — they are emitted per concrete instantiation.
        // Each top-level declaration is captured into its own per-decl impl buffer.
        // Companion objects and nested classes share the parent class's buffer (via captureForDecl).
        var firstClass = true
        for (d in file.decls) when (d) {
            is ClassDecl  -> if (d.typeParams.isEmpty() && !d.annotations.any { it.name == "DocumentationOnly" }) {
                if (!firstClass) hdr.appendLine()
                firstClass = false
                captureForDecl(d.name) {
                    emitClass(d)
                    // Companion objects — emitted in the same .c as the parent class
                    for (vMember in d.members.filterIsInstance<ObjectDecl>()) {
                        hdr.appendLine()
                        val vCompName = "${d.name}$${vMember.name}" // fully qualified companion name
                        emitObject(ObjectDecl(vCompName, vMember.members))
                        val vCompKind = if (vMember.name == "Companion") "companion object" else "object" // kind label
                        impl.appendLine(classBlockFooter(vCompKind, vCompName.replace('$', '.'), emptyList()))
                        impl.appendLine()
                        }
                    // Nested classes — emitted in the same .c as the outermost parent class
                    fun emitNested(inParent: ClassDecl, inParentFlatName: String) {
                        for (vNested in inParent.members.filterIsInstance<ClassDecl>()) {
                            if (vNested.typeParams.isEmpty()) {
                                val vFlatName = "$inParentFlatName$${vNested.name}" // mangled nested name
                                hdr.appendLine()
                                emitClass(ClassDecl(vFlatName, vNested.isData,
                                    vNested.ctorParams, vNested.members, vNested.initBlocks,
                                    vNested.superInterfaces, vNested.typeParams, vNested.secondaryCtors))
                                emitNested(vNested, vFlatName)
                                val vNestedKind = if (vNested.isData) "data class" else "class" // kind label
                                impl.appendLine(classBlockFooter(vNestedKind, vFlatName.replace('$', '.'), emptyList()))
                                impl.appendLine()
                                }
                            }
                        }
                    emitNested(d, d.name)
                    }
                }
            is EnumDecl   -> {
                if (!firstClass) hdr.appendLine()
                firstClass = false
                captureForDecl(d.name) { emitEnum(d) }
                }
            is ObjectDecl -> {
                if (!firstClass) hdr.appendLine()
                firstClass = false
                captureForDecl(d.name) { emitObject(d) }
                }
            else -> {}
            }

        // Pre-pass: register classInterfaces for objects and all monomorphized generic classes
        // so that interfaceImplementors is fully populated before any function body emission.
        for (d in file.decls) if (d is ObjectDecl && d.superInterfaces.isNotEmpty()) {
            classInterfaces[d.name] = d.superInterfaces.map { it.name }
        }
        for ((baseName, instantiations) in genericInstantiations) {
            val templateDecl = genericClassDecls[baseName] ?: continue
            if (templateDecl.superInterfaces.isEmpty()) continue
            for (typeArgs in instantiations) {
                val mangledName = mangledGenericName(baseName, typeArgs)
                val ci = classes[mangledName] ?: continue
                val subst = ci.typeParams.ifEmpty { templateDecl.typeParams }.zip(typeArgs).toMap()
                    .ifEmpty { genericTypeBindings[mangledName] ?: emptyMap() }
                val resolvedIfaces = templateDecl.superInterfaces.map { substituteTypeRef(it, subst) }
                typeSubst = subst
                classInterfaces[mangledName] = resolvedIfaces.map { resolveTypeNameStr(it) }
                typeSubst = emptyMap()
            }
        }
        // Build interfaceImplementors before emitting any generic class function bodies.
        for ((className, ifaces) in classInterfaces) {
            for (iface in ifaces) {
                interfaceImplementors.getOrPut(iface) { mutableListOf() }.add(className)
            }
        }

        for ((baseName, instantiations) in genericInstantiations) {
            val templateDecl = genericClassDecls[baseName] ?: continue
            for (typeArgs in instantiations) {
                if (!firstClass) hdr.appendLine()
                firstClass = false
                val mangledName = mangledGenericName(baseName, typeArgs)
                val templateCi = classes[baseName] ?: continue
                typeSubst = templateCi.typeParams.zip(typeArgs).toMap()
                val prevSourceFile = currentSourceFile
                declSourceFile[baseName]?.let { currentSourceFile = it }
                captureForDecl(mangledName) { emitGenericClass(templateDecl, mangledName) }
                currentSourceFile = prevSourceFile
                typeSubst = emptyMap()
                }
            }

        // Emit static vtable instances + wrapping functions for interface implementations.
        // Each vtable emission is captured into the same buffer as its owning class/object.
        // Non-generic classes:
        for (d in file.decls) if (d is ClassDecl && d.typeParams.isEmpty() && d.superInterfaces.isNotEmpty()) {
            captureForDecl(d.name) {
                emitInterfaceVtablesForClass(d.name, d.superInterfaces, implsOnly = true)
                }
            }
        // Objects implementing interfaces:
        for (d in file.decls) if (d is ObjectDecl && d.superInterfaces.isNotEmpty()) {
            captureForDecl(d.name) {
                emitInterfaceVtablesForClass(d.name, d.superInterfaces, implsOnly = true)
                }
            }
        // Monomorphized generic classes:
        for ((baseName, instantiations) in genericInstantiations) {
            val templateDecl = genericClassDecls[baseName] ?: continue
            if (templateDecl.superInterfaces.isEmpty()) continue
            for (typeArgs in instantiations) {
                val mangledName = mangledGenericName(baseName, typeArgs)
                val ci = classes[mangledName] ?: continue
                val subst = ci.typeParams.ifEmpty { templateDecl.typeParams }.zip(typeArgs).toMap()
                    .ifEmpty { genericTypeBindings[mangledName] ?: emptyMap() }
                val resolvedIfaces = templateDecl.superInterfaces.map { substituteTypeRef(it, subst) }
                typeSubst = subst
                captureForDecl(mangledName) {
                    emitInterfaceVtablesForClass(mangledName, resolvedIfaces, implsOnly = true)
                    }
                typeSubst = emptyMap()
                }
            }

        // Emit complete interface blocks (vtable + tagged union + $Opt) with banner comments.
        // Must be AFTER all class struct definitions (tagged union members need complete types)
        // and AFTER vtable implementations (interfaceImplementors must be fully populated).
        for ((name, info) in interfaces) {
            if (info.typeParams.isNotEmpty()) continue  // skip generic templates
            val isMonomorphized = mangledComponents.containsKey(name) ||
                genericIfaceDecls.keys.any { tmpl -> name.startsWith(tmpl + "_") }
            val isCrossPackage = info.pkg.isNotEmpty() && info.pkg != prefix
            if (!isMonomorphized && isCrossPackage) continue  // belongs to another package's header
            hdr.appendLine()  // always blank line before each interface block
            val prevSourceFile = currentSourceFile
            declSourceFile[name]?.let { currentSourceFile = it }
            emitInterfaceBlock(info)
            currentSourceFile = prevSourceFile
        }

        // Placeholder replaced after emission with KTC_DEFINE_ARRAY for current-pkg user types.
        // Must appear AFTER all class struct definitions (user types are incomplete before this point).
        hdr.appendLine()
        hdr.appendLine("/* @SIZED_TYPES_USER@ */")

        // Emit top-level functions, properties, generic funs, star ext funs, and enum values data.
        // Each is captured into its own per-source-file buffer (key "|sourceFile.kt").
        for (d in file.decls) when (d) {
            is FunDecl  -> {
                if (d.annotations.any { it.name == "DocumentationOnly" }) continue  // intrinsic stub — handled at call site
                if (d.typeParams.isNotEmpty()) continue
                if (d.receiver != null && d.receiver.typeArgs.any { it.name == "*" }) continue
                if (d.receiver != null && d.receiver.typeArgs.isNotEmpty()
                    && (genericIfaceDecls.containsKey(d.receiver.name) || genericClassDecls.containsKey(d.receiver.name))) continue
                if (d.receiver != null && (d.isInline || d.isInfix)) continue
                val vSrcKey = "|${declSourceFile[d.name] ?: sourceFileName}"  // per-source-file buffer key
                captureForDecl(vSrcKey) {
                    if (d.receiver != null) emitExtensionFun(d) else emitFun(d)
                    }
                }
            is PropDecl -> {
                val vSrcKey = "|${declSourceFile[d.name] ?: sourceFileName}"  // per-source-file buffer key
                captureForDecl(vSrcKey) { emitTopProp(d) }
                }
            else -> {}
            }
        for (f in genericFunDecls) {
            val vSrcKey = "|${declSourceFile[f.name] ?: sourceFileName}"      // per-source-file buffer key
            captureForDecl(vSrcKey) { emitGenericFunInstantiations(f) }
            }
        for (f in starExtFunDecls) {
            val vSrcKey = "|${declSourceFile[f.name] ?: sourceFileName}"      // per-source-file buffer key
            captureForDecl(vSrcKey) { emitStarExtFunInstantiations(f) }
            }
        emitEnumValuesData()                                                   // enum values data → each enum's own .c file

        // ── Assemble output ────────────────────────────────────────────
        val vSrcName = prefix.trimEnd('_').ifEmpty {
            sourceFileName.removeSuffix(".kt").ifEmpty { "main" }
            }
        val vPkg     = file.pkg ?: ""                                       // Kotlin package string
        val vSources = mutableMapOf<String, SourceFile>()                  // filename → SourceFile

        // Per-source-file top-level .c files (one per .kt source file, key starts with "|")
        for ((vKey, vTopImpl) in perDeclImpl) {
            if (!vKey.startsWith("|")) continue
            if (vTopImpl.isEmpty()) continue
            val vSrcFull    = vKey.removePrefix("|")                        // e.g. "GenericsTest.kt"
            val vSrcBase    = vSrcFull.removeSuffix(".kt")                  // e.g. "GenericsTest"
            val vCFileName  = "${vSrcBase}Kt.c"                             // e.g. "GenericsTestKt.c"
            val vTopImplFwd = perDeclImplFwd[vKey]
            val vSrc = buildString {
                appendLine(funBlockHeader(vPkg, vSrcFull))
                appendLine()
                appendLine("#include \"_package_.h\"")                       // same directory as this .c file
                appendLine()
                if (vTopImplFwd != null && vTopImplFwd.isNotEmpty()) {
                    append(vTopImplFwd)
                    appendLine()
                    }
                append(vTopImpl)
                }
            vSources[vCFileName] = SourceFile(vSrc, vPkg)
            }

        // Per-declaration .c files — one per class / object / enum
        for ((vDeclName, vDeclImpl) in perDeclImpl) {
            if (vDeclName.startsWith("|")) continue  // per-source-file keys already handled above
            if (vDeclImpl.isEmpty()) continue        // nothing was emitted
            val vDeclImplFwd = perDeclImplFwd[vDeclName]                    // may be null if no private fwds

            val vCi = classes[vDeclName]                                    // ClassInfo if it's a class
            val vOi = objects[vDeclName]                                    // ObjInfo  if it's an object
            val vEi = enums[vDeclName]                                      // EnumInfo if it's an enum

            /* Generic instantiation: find the template base name (e.g. "MapIterator" from
               "MapIterator_String_String"). Used for routing and display-name formatting. */
            val vGenBase     = mangledComponents[vDeclName]?.first          // null for non-generic decls
            val vGenTypeArgs = mangledComponents[vDeclName]?.second         // type args for display name

            val vKind: String                   // Kotlin keyword for the file header comment
            val vCName: String                  // C mangled identifier (also the filename base)
            val vKtName: String                 // Kotlin display name with generic args if applicable
            val vSrcFile: String                // originating Kotlin source file (template's for generics)
            val vInstFrom: String               // instantiator source file ("" for non-generic decls)
            val vRoutingPkg: String             // dot-separated package that determines output directory
            when {
                vCi != null -> {
                    vKind       = if (vCi.isData) "data class" else "class"
                    vCName      = vCi.flatName
                    val vBase   = vGenBase ?: vDeclName
                    vSrcFile    = declSourceFile[vBase] ?: sourceFileName
                    vInstFrom   = if (vGenBase != null) sourceFileName else ""
                    vKtName     = if (vGenBase != null && vGenTypeArgs != null)
                                      "$vGenBase<${vGenTypeArgs.joinToString(", ")}>"
                                  else vDeclName.replace('$', '.')
                    vRoutingPkg = declOrigPkg[vBase] ?: vPkg
                    }
                vOi != null -> {
                    val vParts  = vDeclName.split('$')
                    vKind       = if (vParts.size > 1 && vParts.last() == "Companion")
                                      "companion object" else "object"
                    vCName      = vOi.flatName
                    vSrcFile    = declSourceFile[vDeclName] ?: sourceFileName
                    vInstFrom   = ""
                    vKtName     = vDeclName.replace('$', '.')
                    vRoutingPkg = declOrigPkg[vDeclName] ?: vPkg
                    }
                vEi != null -> {
                    vKind       = "enum"
                    vCName      = vEi.flatName
                    vSrcFile    = declSourceFile[vDeclName] ?: sourceFileName
                    vInstFrom   = ""
                    vKtName     = vDeclName
                    vRoutingPkg = declOrigPkg[vDeclName] ?: vPkg
                    }
                else -> continue  // unknown declaration type (e.g. generic template) — skip
                }

            /* The .c file lands in vRoutingPkg's directory; its struct defs are in vPkg's _package_.h.
               For non-generic decls vRoutingPkg == vPkg → resolves to "_package_.h".
               For generic instantiations routed to the template's package the paths differ. */
            val vFileDir    = vRoutingPkg.replace('.', '/')                 // directory of the output .c file
            val vHdrAbsPath = if (vPkg.isNotEmpty()) "${vPkg.replace('.', '/')}/_package_.h"
                              else "$vSrcName/_package_.h"                   // _package_.h with struct defs
            val vSrc = buildString {
                appendLine("#include \"${relIncludePath(vFileDir, vHdrAbsPath)}\"")
                appendLine()
                appendLine(cSourceFileHeader(vKind, vKtName, vRoutingPkg, vCName, vSrcFile, vInstFrom))
                appendLine()
                if (vDeclImplFwd != null && vDeclImplFwd.isNotEmpty()) {
                    append(vDeclImplFwd)
                    appendLine()
                    }
                append(vDeclImpl)
                appendLine(classBlockFooter(vKind, vKtName, emptyList()))
                appendLine()
                }
            vSources["$vDeclName.c"] = SourceFile(vSrc, vRoutingPkg)
            }

        // Replace @SIZED_TYPES@ (before forward decls) with primitive/external/string array defs.
        // Replace @SIZED_TYPES_USER@ (after class defs) with current-pkg user type array defs.
        // Split so user-type KTC_DEFINE_ARRAY appears after the struct it references is defined.
        val vEarlyTypesSb = StringBuilder()   // primitives + external types + strings (before fwd decls)
        val vUserTypesSb  = StringBuilder()   // current-pkg user types (after class definitions)
        // User types from this package — no guard needed (exactly one canonical definition).
        val vSortedArrayDecls = sizedArrayDecls.sortedWith(compareBy({ it.first }, { it.second }))
        for ((vElemCType, vSize) in vSortedArrayDecls)
            vUserTypesSb.appendLine("KTC_DEFINE_ARRAY($vElemCType, $vSize);")
        // Primitive / external types — guard with #ifndef so multiple includes are safe.
        val vSortedGuarded = sizedArrayGuardedDecls
            .filter { it !in sizedArrayDecls }  // skip if already emitted without guard
            .sortedWith(compareBy({ it.first }, { it.second }))
        for ((vElemCType, vSize) in vSortedGuarded) {
            val vGuard = "KTC_ARRAY_DEF_${vElemCType}_$vSize"
            vEarlyTypesSb.appendLine("#ifndef $vGuard")
            vEarlyTypesSb.appendLine("#define $vGuard")
            vEarlyTypesSb.appendLine("KTC_DEFINE_ARRAY($vElemCType, $vSize);")
            vEarlyTypesSb.appendLine("#endif")
        }
        // String types — always guarded (String is a primitive-like built-in).
        for (vSize in sizedStringDecls.sorted()) {
            val vGuard = "KTC_STRING_DEF_$vSize"
            vEarlyTypesSb.appendLine("#ifndef $vGuard")
            vEarlyTypesSb.appendLine("#define $vGuard")
            vEarlyTypesSb.appendLine("KTC_DEFINE_STRING($vSize);")
            vEarlyTypesSb.appendLine("#endif")
        }
        /* Replace @SIZED_TYPES_USER@ first (it appears later in hdr, so replacing it first
           doesn't shift the index of the earlier @SIZED_TYPES@ placeholder). */
        fun replaceHdrPlaceholder(inPlaceholder: String, inContent: StringBuilder, inTitle: String) {
            val vIdx = hdr.indexOf(inPlaceholder)
            if (vIdx < 0) return
            val vSection = if (inContent.isNotEmpty())
                "/* $kHdrRule\n * $inTitle\n * $kHdrRule */\n$inContent"
            else ""
            val vEnd = vIdx + inPlaceholder.length +
                if (hdr.getOrNull(vIdx + inPlaceholder.length) == '\n') 1 else 0
            hdr.replace(vIdx, vEnd, vSection)
        }
        replaceHdrPlaceholder("/* @SIZED_TYPES_USER@ */", vUserTypesSb,  "sized array types (user-defined element types)")
        replaceHdrPlaceholder("/* @SIZED_TYPES@ */",      vEarlyTypesSb, "sized array / string types")

        return COutput(hdr.toString(), vSources)
        }

    // ═══════════════════════════ Collect declarations (pre-pass) ═════

    internal fun collectDecls() {
        objectsWithDispose.clear()
        tlsObjects.clear()
        tlsProps.clear()
        // Collect from all files for cross-reference resolution
        for (f in allFiles) {
            if (f.documentationOnly) continue
            val fpfx = f.pkg?.replace('.', '_')?.plus("_") ?: ""
            for (d in f.decls) {
                collectDecl(d)
                // Record the source file for generic declarations (for mem-track attribution)
                if (f.sourceFile.isNotEmpty()) {
                    when (d) {
                        is ClassDecl -> declSourceFile[d.name] = f.sourceFile
                        is FunDecl -> declSourceFile[d.name] = f.sourceFile
                        is InterfaceDecl -> declSourceFile[d.name] = f.sourceFile
                        is ObjectDecl -> declSourceFile[d.name] = f.sourceFile
                        is PropDecl -> declSourceFile[d.name] = f.sourceFile
                        else -> {}
                    }
                }
                // Record the package prefix for cross-file symbols
                when (d) {
                    is ClassDecl -> {
                        classes[d.name]?.pkg = fpfx  // set pkg on ClassInfo
                        declOrigPkg[d.name] = f.pkg ?: ""  // original package for routing
                        // Register companion object prefix
                        for (vMember in d.members.filterIsInstance<ObjectDecl>()) {
                            objects["${d.name}$${vMember.name}"]?.pkg = fpfx  // set pkg on ObjInfo
                            declOrigPkg["${d.name}$${vMember.name}"] = f.pkg ?: ""
                        }
                    }
                    is EnumDecl   -> { enums[d.name]?.pkg = fpfx;   declOrigPkg[d.name] = f.pkg ?: "" }
                    is InterfaceDecl -> interfaces[d.name]?.pkg = fpfx
                    is ObjectDecl -> { objects[d.name]?.pkg = fpfx; declOrigPkg[d.name] = f.pkg ?: "" }
                    is FunDecl -> {
                        if (d.receiver == null)
                            funNames[d.name] = "$fpfx${d.name}"               // cross-file C name
                        }
                    else -> {}
                }
            }
        }
        // Set pkg for nested classes whose pkg wasn't explicitly set (non-companion inner classes)
        for ((name, ci) in classes) {
            if ('$' in name && ci.pkg.isEmpty()) {
                val vParent = name.substringBefore('$')  // parent type name (may be class or object)
                ci.pkg = classes[vParent]?.pkg ?: objects[vParent]?.pkg ?: prefix
            }
        }
        // Current file's symbols use current prefix (overwrite any from allFiles)
        for (d in file.decls) {
            collectDecl(d, validate = true)
            when (d) {
                is ClassDecl -> {
                    classes[d.name]?.pkg = prefix  // override with current file prefix
                    // Register companion object prefix
                    for (vMember in d.members.filterIsInstance<ObjectDecl>()) {
                        objects["${d.name}$${vMember.name}"]?.pkg = prefix  // sync pkg
                    }
                }
                is EnumDecl -> enums[d.name]?.pkg = prefix
                is InterfaceDecl -> interfaces[d.name]?.pkg = prefix
                is ObjectDecl -> objects[d.name]?.pkg = prefix
                is FunDecl -> {
                    if (d.receiver == null)
                        funNames[d.name] = "$prefix${d.name}"                  // current-file C name (overrides allFiles)
                    }
                else -> {}
            }
        }
        // Sync pkg for nested classes/objects after the current-file pass
        for ((name, ci) in classes) {
            if ('$' in name) {
                val vParent = name.substringBefore('$')  // parent type name (may be class or object)
                ci.pkg = classes[vParent]?.pkg ?: objects[vParent]?.pkg ?: prefix
            }
        }
        for ((name, oi) in objects) {
            if ('$' in name) {
                val vParent = name.substringBefore('$')  // parent type name (may be class or object)
                oi.pkg = classes[vParent]?.pkg ?: objects[vParent]?.pkg ?: prefix
            }
        }
    }

    internal fun collectDecl(d: Decl, validate: Boolean = false) {
        when (d) {
            is ClassDecl -> {
                if (d.annotations.any { it.name == "DocumentationOnly" }) return  // intrinsic stub — skip registration
                for (p in d.ctorParams) {
                    if ((p.isVal || p.isVar) && p.type.isRawArray()) {
                        codegenError("Class property '${p.name}' cannot have raw array type '${p.type.name}'. Use @Ptr Array<T> or @Size(N) Array<T> instead")
                    }
                }
                for (p in d.members.filterIsInstance<PropDecl>()) {
                    val propType = p.type ?: inferInitType(p.init)
                    if (propType.isRawArray()) {
                        currentStmtLine = p.line
                        codegenError("Class property '${p.name}' cannot have raw array type '${propType.name}'. Use @Ptr Array<T> or @Size(N) Array<T> instead")
                    }
                }
				val vCtorProps = d.ctorParams.filter { it.isVal || it.isVar }.map { vP ->  // ctor val/var props
					PropertyDef(
						name = vP.name,
						typeRef = vP.type,
						isVal = vP.isVal,
						isPrivate = vP.isPrivate,
						isConstructorParam = true
						)
					}
				val vCtorPlainParams = d.ctorParams.filter { !it.isVal && !it.isVar }.map { vP ->  // plain ctor params
					PropertyDef(
						name = vP.name,
						typeRef = vP.type,
						isVal = false,
						isConstructorParam = true
						)
					}
				val vBodyProps = d.members.filterIsInstance<PropDecl>().map { vP ->  // body-declared props
					PropertyDef(
						name = vP.name,
						typeRef = vP.type ?: inferInitType(vP.init),
						isVal = !vP.mutable,
						isPrivate = vP.isPrivate,
						isPrivateSet = vP.isPrivateSet,
						initExpr = vP.init,
						line = vP.line
						)
					}
				val vAllProps = vCtorProps + vBodyProps  // combined property list
				val ci = ClassInfo(d.name, d.isData, vAllProps, vCtorPlainParams, initBlocks = d.initBlocks, typeParams = d.typeParams)
                if (d.typeParams.isNotEmpty()) allGenericTypeParamNames += d.typeParams
                for (m in d.members) if (m is FunDecl && m.receiver == null) {
                    if (m.returnType != null && m.returnType.isRawArray()) {
                        codegenError("Method '${m.name}' cannot return raw array type '${m.returnType.name}'. Use @Ptr Array<T> or @Size(N) Array<T> instead")
                    }
                    ci.methods += m
                }
                classes[d.name] = ci
                allClassDecls[d.name] = d
                getTypeId(d.name)
                if (d.typeParams.isNotEmpty()) genericClassDecls[d.name] = d
                if (d.superInterfaces.isNotEmpty()) classInterfaces[d.name] = d.superInterfaces.map { it.name }
                // Verify all interface methods are implemented with override (current file, non-stdlib)
                if (validate && file.pkg != "ktc.std") {
                    val classMethodNames = ci.methods.associateBy { it.name }
                    for (ifaceRef in d.superInterfaces) {
                        val ifaceName = resolveIfaceName(ifaceRef)
                        val iface = interfaces[ifaceName] ?: continue
                        for (m in collectAllIfaceMethods(iface)) {
                            val impl = classMethodNames[m.name]
                            when {
                                impl == null ->
                                    codegenError("Class '${d.name}' must implement '${m.name}' from interface '$ifaceName'")
                                !impl.isOverride ->
                                    codegenError("Method '${m.name}' in class '${d.name}' must be marked 'override'")
                            }
                        }
                    }
                    // dispose() and hashCode() are implicitly overrides — always require the keyword
                    for (m in ci.methods) {
                        if ((m.name == "dispose" || m.name == "hashCode") && !m.isOverride) {
                            codegenError("Method '${m.name}' in class '${d.name}' must be marked 'override'")
                        }
                    }
                    // Check for bogus override on methods that don't match any interface
                    // (dispose is implicitly an override of the no-op dispose every class gets)
                    val allIfaceMethodNames = d.superInterfaces.flatMap { ifaceRef ->
                        val ifaceName = resolveIfaceName(ifaceRef)
                        interfaces[ifaceName]?.let { collectAllIfaceMethods(it).map { m -> m.name } } ?: emptyList()
                    }.toSet() + "dispose" + "hashCode"
                    for (m in ci.methods) {
                        if (m.isOverride && m.name !in allIfaceMethodNames) {
                            codegenError("Method '${m.name}' is marked 'override' but does not override any interface method")
                        }
                    }
                }
                // Collect companion objects declared inside this class
                for (vMember in d.members.filterIsInstance<ObjectDecl>()) {
                    val vCompanionSynthName = "${d.name}$${vMember.name}" // e.g. "Foo$Companion"
                    classCompanions[d.name] = vCompanionSynthName
                    collectDecl(ObjectDecl(vCompanionSynthName, vMember.members, vMember.annotations))
                }
                // Collect nested classes/interfaces/enums (namespacing only — prefix with parent)
                val nestedClasses = d.members.filterIsInstance<ClassDecl>()
                for (nested in nestedClasses) {
                    val nestedName = "${d.name}$${nested.name}"  // e.g. "Outer$Inner"
                    collectDecl(ClassDecl(nestedName, nested.isData, nested.ctorParams, nested.members,
                        nested.initBlocks, nested.superInterfaces, nested.typeParams, nested.secondaryCtors))
                }
            }
            is EnumDecl  -> enums[d.name] = EnumInfo(d.name, d.entries)
            is InterfaceDecl -> {
                interfaces[d.name] = IfaceInfo(d.name, d.methods, d.properties, d.typeParams, d.superInterfaces)
                getTypeId(d.name)
                if (d.typeParams.isNotEmpty()) {
                    genericIfaceDecls[d.name] = d
                    allGenericTypeParamNames += d.typeParams
                }
            }
            is ObjectDecl -> {
                if (d.annotations.any { it.name == "Tls" }) tlsObjects.add(d.name)
                for (p in d.members.filterIsInstance<PropDecl>()) {
                    val propType = p.type ?: inferInitType(p.init)
                    if (propType.isRawArray()) {
                        currentStmtLine = p.line
                        codegenError("Object property '${p.name}' cannot have raw array type '${propType.name}'. Use @Ptr Array<T> or @Size(N) Array<T> instead")
                    }
                }
				val vObjProps = d.members.filterIsInstance<PropDecl>().map { vP ->  // object properties
					PropertyDef(
						name = vP.name,
						typeRef = vP.type ?: inferInitType(vP.init),
						isVal = !vP.mutable,
						isPrivate = vP.isPrivate,
						isPrivateSet = vP.isPrivateSet,
						initExpr = vP.init,
						line = vP.line
						)
					}
				val oi = ObjInfo(d.name, vObjProps)
                for (m in d.members) if (m is FunDecl) {
                    if (m.returnType != null && m.returnType.isRawArray()) {
                        codegenError("Method '${m.name}' cannot return raw array type '${m.returnType.name}'. Use @Ptr Array<T> or @Size(N) Array<T> instead")
                    }
                    oi.methods += m
                    // Register in funSigs for return type inference at call sites
                    if (funSigs[m.name] == null) {
                        funSigs[m.name] = FunSig(m.params, m.returnType)
                    }
                    // Inline object methods are expanded at call sites — register for expansion
                    if (m.isInline) inlineFunDecls.getOrPut(m.name) { mutableListOf() }.add(m)
                }
                objects[d.name] = oi
                // Register interface implementations for objects
                if (d.superInterfaces.isNotEmpty()) {
                    classInterfaces[d.name] = d.superInterfaces.map { it.name }
                }
                // dispose()/hashCode() are implicitly overrides — always require the keyword (current file, non-stdlib)
                if (validate && file.pkg != "ktc.std") {
                    for (m in d.members) if (m is FunDecl && (m.name == "dispose" || m.name == "hashCode") && !m.isOverride) {
                        codegenError("Method '${m.name}' in object '${d.name}' must be marked 'override'")
                    }
                }
                // Track objects with dispose for auto-call on main exit (current file only)
                // Use prefix directly: pkg is set AFTER collectDecl returns, so typeFlatName would miss it
                if (validate) {
                    for (m in d.members) if (m is FunDecl && m.name == "dispose") {
                        val cName = "$prefix${d.name}"  // current-file prefix (validate=true only for current file)
                        if (cName !in objectsWithDispose) objectsWithDispose.add(cName)
                    }
                }
                // Collect nested classes inside object (namespacing with parent prefix)
                for (nested in d.members.filterIsInstance<ClassDecl>()) {
                    val nestedName = "${d.name}$${nested.name}"  // e.g. "Sha256$Context"
                    collectDecl(ClassDecl(nestedName, nested.isData, nested.ctorParams, nested.members,
                        nested.initBlocks, nested.superInterfaces, nested.typeParams, nested.secondaryCtors))
                }
            }
            is FunDecl -> {
                if (d.annotations.any { it.name == "DocumentationOnly" }) return  // intrinsic stub — skip validation and signature collection
                if (d.returnType != null && d.returnType.isRawArray()) {
                    codegenError("Function '${d.name}' cannot return raw array type '${d.returnType.name}'. Use @Ptr Array<T> or @Size(N) Array<T> instead")
                }
                if (d.returnType != null && d.returnType.name == "Any" && d.returnType.annotations.none { it.name == "Ptr" }) {
                    codegenError("Function '${d.name}' cannot return value-type 'Any'. Use @Ptr Any instead")
                }
                val effectiveReturnType = d.returnType ?: d.body?.let { inferredTypeRef(inferBlockType(it)) }
                if (d.typeParams.isNotEmpty()) {
                    // Generic function template — store for monomorphization
                    // (dedup: overwrite funSig, but only add to list if not already present)
                    if (genericFunDecls.none { it === d }) genericFunDecls += d
                    funSigs[d.name] = FunSig(d.params, effectiveReturnType)
                    allGenericTypeParamNames += d.typeParams
                    if (d.isInline && d.receiver != null) inlineExtFunDecls[d.name] = d
                    if (d.isInline) inlineFunDecls.getOrPut(d.name) { mutableListOf() }.add(d)
                } else if (d.receiver != null && d.receiver.typeArgs.any { it.name == "*" }) {
                    // Star-projection extension function — store for expansion
                    if (starExtFunDecls.none { it === d }) starExtFunDecls += d
                } else if (d.receiver != null && d.receiver.typeArgs.isNotEmpty()
                    && (genericIfaceDecls.containsKey(d.receiver.name) || genericClassDecls.containsKey(d.receiver.name))) {
                    // Extension on generic type e.g. fun Map<K,V>.tryDispose() — expand per concrete type
                    if (starExtFunDecls.none { it === d }) starExtFunDecls += d
                } else if (d.receiver != null) {
                    val recvName = d.receiver.name
                    extensionFuns.getOrPut(recvName) { mutableListOf() }.add(d)
                    // Register as method on the class for inference
                    classes[recvName]?.methods?.add(d)
                    funSigs["${recvName}.${d.name}"] = FunSig(d.params, effectiveReturnType)
                    // Non-generic infix extension → register for binary-op inline dispatch
                    if (d.isInfix) inlineExtFunDecls[d.name] = d
                } else {
                    funSigs[d.name] = FunSig(d.params, effectiveReturnType)
                    if (d.isInline) inlineFunDecls.getOrPut(d.name) { mutableListOf() }.add(d)
                }
            }
            is PropDecl  -> { topProps.add(d.name); if (!d.mutable) valTopProps.add(d.name); if (d.annotations.any { it.name == "Tls" }) tlsProps.add(d.name) }
        }
    }

    /**
     * Returns the method name with overload type suffixes if multiple methods share the same name.
     * e.g. `digest(buff: @Ptr ByteArray)` → `digest`, `digest(buff: @Ptr ByteArray, offset: Int, length: Int)` → `digest_ByteArray_Int_Int`
     */
    internal fun methodName(f: FunDecl, siblings: List<FunDecl>): String {
        val base = f.name
        val overloads = siblings.filter { it.name == base }
        if (overloads.size <= 1) return base
        val types = f.params.map { resolveTypeName(it.type).toInternalStr.removeSuffix("*") }
        if (types.isEmpty()) return base   // no-arg keeps plain name
        return "${base}With${types.joinToString("_")}"
    }

    /**
     * Find the matching overloaded method from the given siblings that best matches the call args.
     * Phase 4.6: uses KtcType comparison instead of string equality for type matching.
     * Returns the matched FunDecl, or null.
     */
    internal fun findOverload(inName: String, inArgs: List<Arg>, inSiblings: List<FunDecl>): FunDecl?
        {
        val vCandidates = inSiblings.filter { it.name == inName }  // methods with matching name
        if (vCandidates.size <= 1) return vCandidates.firstOrNull()
        /* First: narrow by argument count (required params..total params). */
        val vByCount = vCandidates.filter { inArgs.size in it.params.count { vP -> vP.default == null }..it.params.size }
        if (vByCount.size == 1) return vByCount[0]
        if (vByCount.isEmpty()) return vCandidates.firstOrNull()
        /* Multiple same-count candidates: match by argument KtcType. */
        for (vCandidate in vByCount)
            {
            if (inArgs.size == vCandidate.params.size)
                {
                val vAllMatch = inArgs.zip(vCandidate.params).all { (vArg, vParam) ->
                    val vArgKtc  = inferExprTypeKtc(vArg.expr) ?: KtcType.Prim(KtcType.PrimKind.Int)
                    val vParamKtc = resolveTypeName(vParam.type)
                    /* Strip outer Nullable/Ptr for structural comparison. */
                    fun KtcType.core(): KtcType = when (this)
                        {
                        is KtcType.Nullable -> inner.core()
                        is KtcType.Ptr      -> inner.core()
                        else                -> this
                        }
                    val vArgCore   = vArgKtc.core()
                    val vParamCore = vParamKtc.core()
                    vArgCore == vParamCore ||
                        (vParamCore is KtcType.Any)
                    }
                if (vAllMatch) return vCandidate
                }
            }
        return vByCount.firstOrNull()
        }
    }
