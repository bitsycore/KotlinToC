package com.bitsycore.ktc.codegen

import com.bitsycore.ktc.ast.*
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
class CCodeGen(internal val file: KtFile, internal val allFiles: List<KtFile> = listOf(), internal val sourceLines: List<String> = emptyList(), internal val memTrack: Boolean = false, internal val sourceFileName: String = "") {

    // ── Package prefix ───────────────────────────────────────────────
    internal val prefix: String = file.pkg?.replace('.', '_')?.plus("_") ?: ""

    /* Fallback C name: prefix + name. Used only for names not found in TypeDef maps or funNames. */
    internal fun pfx(inName: String): String {
        if (inName == "main") return inName
        if (inName.startsWith("ktc_")) return inName
        return "$prefix$inName"
        }

    /*
    Phase 6: TypeDef-based C name resolution.
    Replaces pfx(typeName) for class, object, enum, and interface identifiers.
    Looks up the TypeDef and returns its flatName (pkg + baseName).
    Falls back to pfx() for builtins and names not yet in TypeDef tables.
    */
    internal fun typeFlatName(inName: String): String {  // type or object name → C flat name
        if (inName == "main") return inName
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
        if (inName == "main") return inName
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

    internal val classes  = mutableMapOf<String, ClassInfo>()
    internal val enums    = mutableMapOf<String, EnumInfo>()
    internal val enumValuesCalled  = mutableSetOf<String>()
    internal val enumValueOfCalled = mutableSetOf<String>()
    internal val objects  = mutableMapOf<String, ObjInfo>()
    internal val funSigs  = mutableMapOf<String, FunSig>()
    internal val funNames = mutableMapOf<String, String>()  // top-level function name → C name
    internal val inlineFunDecls = mutableMapOf<String, MutableList<FunDecl>>()
    internal val inlineExtFunDecls = mutableMapOf<String, FunDecl>()  // inline generic extension funs, keyed by method name
    internal var activeLambdas: Map<String, ActiveLambda> = emptyMap()
    internal val lambdaParamSubst = mutableMapOf<String, String>()  // also stores "\$this" → receiver C expr during inline ext expansion
    // Deferred hdr declarations: className → list of hdr lines (for methods moved to implements section)
    internal val deferredHdrLines = mutableMapOf<String, MutableList<String>>()
    internal val lambdaParamTypes = mutableMapOf<String, String>()  // lambda param name → Kotlin type, used by inferExprType so .size etc. resolve correctly
    internal var inlineReturnVar: String? = null  // result var name (value pos), "" (stmt pos), null (not inside inline)
    internal var inlineEndLabel: String? = null   // goto label after the inline block to handle early return
    internal var currentInd: String = "    "  // current emit indentation, kept in sync by emitStmt
    internal var inlineCounter: Int = 0  // counter for unique inline temp variable names and end labels
    internal val topProps = mutableSetOf<String>()  // top-level property names (need pfx)
    internal val valTopProps = mutableSetOf<String>()  // top-level val properties (cannot be reassigned)
    internal val extensionFuns = mutableMapOf<String, MutableList<FunDecl>>()
    internal val interfaces = mutableMapOf<String, IfaceInfo>()
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
                it.receiver!!.name == bareType ||
                (genericClassDecls.containsKey(it.receiver!!.name) && bareType.startsWith("${it.receiver!!.name}\$")) ||
                (genericIfaceDecls.containsKey(it.receiver!!.name) && (
                    bareType == it.receiver!!.name ||
                    classInterfaces[bareType]?.contains(it.receiver!!.name) == true ||
                    bareType.startsWith("${it.receiver!!.name}\$")
                ))
            )
        }
        if (genericMatch) return true
        // Check interfaces implemented by this class
        val ifaces = classInterfaces[bareType] ?: emptyList()
        return ifaces.any { iface ->
            genericFunDecls.any { gf ->
                gf.name == method && gf.receiver?.nullable == true && (
                    gf.receiver!!.name == iface ||
                    (genericIfaceDecls.containsKey(gf.receiver!!.name) && iface.startsWith("${gf.receiver!!.name}\$"))
                )
            }
        }
    }

    // Map class name → list of interface names it implements
    internal val classInterfaces = mutableMapOf<String, List<String>>()
    // Reverse map: interface name → list of class names that implement it
    internal val interfaceImplementors = mutableMapOf<String, MutableList<String>>()

    // Track class/enum types used in Array<T> so we emit KT_ARRAY_DEF for them
    internal val classArrayTypes = mutableSetOf<String>()

    /* Pair/Triple types are now handled entirely by stdlib — no intrinsic maps needed. */


    // ── Generics (monomorphization) ──────────────────────────────────
    // Store original ClassDecl for generic classes so we can re-emit per instantiation
    internal val genericClassDecls = mutableMapOf<String, ClassDecl>()
    // Store original InterfaceDecl for generic interfaces so we can monomorphize them
    internal val genericIfaceDecls = mutableMapOf<String, InterfaceDecl>()
    // Track which source file a generic declaration came from (for mem-track attribution)
    internal val declSourceFile = mutableMapOf<String, String>()
    // Active type parameter substitution map during monomorphized emission (e.g. {T → Int})
    internal var typeSubst: Map<String, String> = emptyMap()
    // Track all discovered concrete instantiations: "MyList" → [["Int"], ["Float"]]
    internal val genericInstantiations = mutableMapOf<String, MutableSet<List<String>>>()
    // All known type parameter names from generic classes and functions (e.g. "T", "U")
    // Used to prevent registering type params as concrete instantiations
    internal val allGenericTypeParamNames = mutableSetOf<String>()

    // Reverse map: mangled class name → (baseName, typeArgs) for generic instances.
    // Used to compute KTC_OPTIONAL_GENERIC_NAME-style Optional wrapper names.
    internal val mangledComponents = mutableMapOf<String, Pair<String, List<String>>>()

    /** Mangle a generic class name with concrete type args: MyList + ["Int?"] → "MyList$1_Int$Opt" */
    internal fun mangledGenericName(baseName: String, typeArgs: List<String>): String {
        val sanitized = typeArgs.joinToString("_") { it.replace("?", "\$Opt") }
        val mangledName = "${baseName}\$${typeArgs.size}_$sanitized"
        mangledComponents[mangledName] = Pair(baseName, typeArgs)
        return mangledName
    }

    /** Record a concrete instantiation of a generic class and return the mangled name. */
    internal fun recordGenericInstantiation(baseName: String, typeArgs: List<String>): String {
        genericInstantiations.getOrPut(baseName) { mutableSetOf() }.add(typeArgs)
        return mangledGenericName(baseName, typeArgs)
    }

    /** C name for the Optional wrapper of a generic instance.
    e.g. ArrayList + ["Int"]  → "ktc_std_ArrayList$Opt$1_ktc_Int"
         ArrayList + ["Int?"] → "ktc_std_ArrayList$Opt$1_ktc_Int$Opt" */
    internal fun genericOptionalCName(baseName: String, typeArgs: List<String>): String {
        val baseCName = typeFlatName(baseName)
        val typeArgStr = typeArgs.joinToString("_") { optTypeArgComponent(it) }
        return "${baseCName}\$Opt\$${typeArgs.size}_${typeArgStr}"
    }

    // Maps mangled concrete name → type substitution (e.g. "MyList_Int" → {T: "Int"})
    internal val genericTypeBindings = mutableMapOf<String, Map<String, String>>()

    // ── Per-scope variable → type mapping ────────────────────────────
    /* Phase 4.3: scopes store KtcType; string interface kept via toInternalStr bridge. */
    internal val scopes = ArrayDeque<MutableMap<String, KtcType>>()  // variable name → KtcType
    internal fun pushScope() { scopes.addLast(mutableMapOf()); optValVarNames.addLast(mutableSetOf()); mutableVarScopes.addLast(mutableSetOf()) }
    internal fun popScope()  { scopes.removeLast(); optValVarNames.removeLast(); mutableVarScopes.removeLast() }

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
            if (isThis) "\$self.tag == ktc_SOME" else "$recvName.tag == ktc_SOME"

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
        return if (vImpls != null && vImpls.size == 1) "$inRecv.$vDataName" else "$inRecv.data.$vDataName"
        }

    /*
    Generates the (void*) argument to pass as $self in a vtable method call for an interface receiver.
    Vtable methods expect a pointer to the concrete struct data, NOT to the interface wrapper.
    For multi-implementor: pass &recv.data (start of union = start of first member = concrete struct start)
    For single-implementor: pass &recv.ConcreteClass_data
    For zero-implementor (fallback): pass recv.obj (old void* design)
    */
    internal fun ifaceVtableSelf(inIfaceName: String, inRecv: String, isPtr: Boolean = false): String
        {
        val vImpls = interfaceImplementors[inIfaceName]
        val deref = if (isPtr) "->" else "."
        return when
            {
            vImpls.isNullOrEmpty() -> if (isPtr) "$inRecv->obj" else "$inRecv.obj"
            vImpls.size == 1 -> "(void*)&$inRecv${deref}${typeFlatName(vImpls[0])}_data"
            else -> "(void*)&$inRecv${deref}data"
            }
        }

    /** True if type is a function pointer type: "Fun(P1,P2)->R" */
    internal fun isFuncType(t: String): Boolean = t.startsWith("Fun(")

    /** Parse a function type string "Fun(P1,P2)->R" or "Fun(R|P1,P2)->R" (receiver function) into (paramTypes, returnType) */
    internal fun parseFuncType(t: String): Pair<List<String>, String> {
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
    Generic instances: ArrayList<Int>? → ktc_std_ArrayList$Opt$1_ktc_Int. */
    internal fun optCTypeName(internalType: String): String {
        val base = internalType.removeSuffix("?")
        return when (base) {
            "Byte"    -> "ktc_Byte\$Opt"
            "Short"   -> "ktc_Short\$Opt"
            "Int"     -> "ktc_Int\$Opt"
            "Long"    -> "ktc_Long\$Opt"
            "Float"   -> "ktc_Float\$Opt"
            "Double"  -> "ktc_Double\$Opt"
            "Boolean" -> "ktc_Bool\$Opt"
            "Char"    -> "ktc_Char\$Opt"
            "UByte"   -> "ktc_UByte\$Opt"
            "UShort"  -> "ktc_UShort\$Opt"
            "UInt"    -> "ktc_UInt\$Opt"
            "ULong"   -> "ktc_ULong\$Opt"
            "String"  -> "ktc_String\$Opt"
            "Any"     -> "ktc_Any"   // Any uses data==NULL for null, not Optional
            else -> {
                val components = mangledComponents[base]
                if (components != null) {
                    val (genBase, typeArgs) = components
                    "${typeFlatName(genBase)}\$Opt\$${typeArgs.size}_${typeArgs.joinToString("_") { optTypeArgComponent(it) }}"
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
                // Nested generic (non-nullable): baseName$N_typeArgs
                val (genBase, typeArgs) = components
                val baseCName = typeFlatName(genBase)
                val innerStr = typeArgs.joinToString("_") { optTypeArgComponent(it) }
                "${baseCName}\$${typeArgs.size}_${innerStr}"
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

    /* Returns a C literal for "no value" for the given Optional C type. */
    internal fun optNone(optCType: String): String = "($optCType){ktc_NONE}"

    /* Returns a C literal for "has value" for the given Optional C type. */
    internal fun optSome(optCType: String, expr: String): String = "($optCType){ktc_SOME, $expr}"

    // ── Nullable return tracking ─────────────────────────────────────
    internal var currentFnReturnsNullable = false
    internal var currentFnReturnsArray = false
    internal var currentFnReturnsSizedArray = false
    internal var currentFnSizedArraySize = 0
    internal var currentFnSizedArrayElemType = ""
    internal var currentFnReturnType: String = ""
    internal var currentFnReturnKtcType: KtcType? = null  // KtcType counterpart for pattern matching
    internal var currentFnOptReturnCTypeName: String = ""  // Optional C type for nullable returns
    internal var currentFnIsMain = false
    internal fun currentFnReturnBaseType(): String = currentFnReturnType.removeSuffix("?")

    /** Snapshot of current function state for save/restore across emit functions. */
    internal data class FunState(
        var returnsNullable: Boolean,
        var returnsArray: Boolean,
        var returnsSizedArray: Boolean,
        var sizedArraySize: Int,
        var sizedArrayElemType: String,
        var returnType: String,
        var returnKtcType: KtcType?,
        var optReturnCTypeName: String,
        var klass: String?,
        var selfPtr: Boolean,
        var extRecvType: String?,
    )
    internal fun saveFunState() = FunState(
        currentFnReturnsNullable, currentFnReturnsArray, currentFnReturnsSizedArray,
        currentFnSizedArraySize, currentFnSizedArrayElemType,
        currentFnReturnType, currentFnReturnKtcType, currentFnOptReturnCTypeName,
        currentClass, selfIsPointer, currentExtRecvType
    )
    internal fun restoreFunState(s: FunState) {
        currentFnReturnsNullable = s.returnsNullable
        currentFnReturnsArray = s.returnsArray
        currentFnReturnsSizedArray = s.returnsSizedArray
        currentFnSizedArraySize = s.sizedArraySize
        currentFnSizedArrayElemType = s.sizedArrayElemType
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

    /** Throw an error with source context around the given line. */
    internal fun codegenError(msg: String): Nothing {
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
    internal fun codegenWarning(msg: String) {
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
    /** Kotlin source location string for current statement, e.g. `"File.kt", 42` */
    internal fun ktSrc(): String = "\"$currentSourceFile\", $currentStmtLine"
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

    // ── Output sections ──────────────────────────────────────────────
    internal val hdr   = StringBuilder()   // .h forward decls & typedefs
    internal val impl  = StringBuilder()   // .c implementations
    internal val implFwd = StringBuilder()  // .c private forward decls (prepended at end)

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
        if (file.documentationOnly) return COutput("", "")

        collectDecls()

        hdr.appendLine("#pragma once")
        if (memTrack) hdr.appendLine("#define KTC_MEM_TRACK")
        // ktc_* packages live in ktc/ subdir alongside ktc_core; user packages live one level up
        val vIsKtcPkg = (file.pkg?.replace('.', '_') ?: "").startsWith("ktc_")
        val vKtcPrefix = if (vIsKtcPkg) "" else "ktc/"
        hdr.appendLine("#include \"${vKtcPrefix}ktc_core.h\"")
        hdr.appendLine()

        // Imports → #include (skip ktc stdlib imports — handled below)
        for (imp in file.imports) {
            if (imp.startsWith("ktc_std")) continue
            val parts = imp.removeSuffix(".*").split('.')
            val headerName = parts.joinToString("_")
            hdr.appendLine("#include \"$headerName.h\"")
        }
        // Auto-include ktc_std.h for non-stdlib packages when stdlib is present
        val hasStdlib = allFiles.any { it.pkg == "ktc.std" }
        if (hasStdlib && file.pkg != "ktc_std") {
            hdr.appendLine("#include \"${vKtcPrefix}ktc_std.h\"")
        }
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

        // Emit interface vtable struct BEFORE classes (TYPE_ID + vtable function pointers)
        // The tagged-union struct is emitted later after all class vtables are processed.
        // Non-generic interfaces first (they only use primitive types in signatures)
        val emittedIfaceNames = mutableSetOf<String>()
        for (d in file.decls) if (d is InterfaceDecl && d.typeParams.isEmpty()) {
            emitInterface(d)
            emittedIfaceNames += d.name
        }

        // Forward-declare all interface structs so class _as_ declarations
        // can reference them before the tagged union is fully defined.
        // Skip generic templates (with type params) — only concrete/monomorphized types need forwarding.
        hdr.appendLine("// forward declarations")
        var emittedAny = false
        for ((name, info) in interfaces) {
            if (info.typeParams.isNotEmpty()) continue
            val cName = typeFlatName(name)
            hdr.appendLine("typedef struct $cName $cName;")
            emittedAny = true
        }
        if (emittedAny) hdr.appendLine()

        // Emit struct/enum/object declarations (defines the element types needed by generic interfaces)
        // Skip generic templates — they are emitted per concrete instantiation
        var firstClass = true
        for (d in file.decls) when (d) {
            is ClassDecl  -> if (d.typeParams.isEmpty()) {
                if (!firstClass) hdr.appendLine()
                firstClass = false
                emitClass(d)
                // Emit interface vtable header declarations right after the class struct
                if (d.superInterfaces.isNotEmpty()) {
                    emitInterfaceVtablesForClass(d.name, d.superInterfaces, declsOnly = true)
                }
                // Emit companion objects declared inside this class
                for (vMember in d.members.filterIsInstance<ObjectDecl>()) {
                    hdr.appendLine()
                    emitObject(ObjectDecl("${d.name}$${vMember.name}", vMember.members))
                }
                // Emit nested classes recursively
                fun emitNested(parentOriginal: ClassDecl, parentFlatName: String) {
                    for (nested in parentOriginal.members.filterIsInstance<ClassDecl>()) {
                        if (nested.typeParams.isEmpty()) {
                            val flatName = "$parentFlatName$${nested.name}"
                            hdr.appendLine()
                            emitClass(ClassDecl(flatName, nested.isData,
                                nested.ctorParams, nested.members, nested.initBlocks,
                                nested.superInterfaces, nested.typeParams, nested.secondaryCtors))
                            emitNested(nested, flatName)
                        }
                    }
                }
                emitNested(d, d.name)
            }
            is EnumDecl   -> emitEnum(d)
            is ObjectDecl -> {
                if (!firstClass) hdr.appendLine()
                firstClass = false
                emitObject(d)
                // Emit interface vtable header declarations for objects implementing interfaces
                if (d.superInterfaces.isNotEmpty()) {
                    emitInterfaceVtablesForClass(d.name, d.superInterfaces, declsOnly = true)
                }
            }
            else -> {}
        }

        // Emit forward declarations for all monomorphized generic class types
        // so method signatures can reference them before their full definitions.
        // Also forward-declare the KTC_OPTIONAL_GENERIC_NAME-style Optional wrapper.
        for ((baseName, instantiations) in genericInstantiations) {
            if (!genericClassDecls.containsKey(baseName)) continue
            for (typeArgs in instantiations) {
                val mangledName = mangledGenericName(baseName, typeArgs)
                val cName = typeFlatName(mangledName)
                hdr.appendLine("typedef struct $cName $cName;")
                // Forward-declare the Optional wrapper (Base$Optional_TypeArg style)
                val optName = genericOptionalCName(baseName, typeArgs)
                hdr.appendLine("typedef struct $optName $optName;")
            }
        }
        if (genericInstantiations.isNotEmpty()) hdr.appendLine()

        // Emit monomorphized generic class instantiations BEFORE generic interfaces,
        // because interface vtable structs may reference generic class types as return types
        // (e.g., MapIterator<Int,String> returned by Map<Int,String>.iterator())

        // Forward-declare all monomorphized generic interface vtable structs so that
        // class _as_ declarations can reference them before the full vtable definition.
        // Also forward-declare the KTC_OPTIONAL_GENERIC_NAME-style Optional wrapper.
        var emittedMonoFwd = false
        for ((name, _) in interfaces) {
            val isMonomorphized = genericIfaceDecls.keys.any { tmpl -> name.startsWith(tmpl + "\$") }
            if (isMonomorphized) {
                val cName = typeFlatName(name)
                hdr.appendLine("typedef struct ${cName}_vt ${cName}_vt;")
                val vIfaceComponents = mangledComponents[name]
                if (vIfaceComponents != null) {
                    val (vGenBase, vTypeArgs) = vIfaceComponents
                    val optName = genericOptionalCName(vGenBase, vTypeArgs)
                    hdr.appendLine("typedef struct $optName $optName;")
                }
                emittedMonoFwd = true
            }
        }
        if (emittedMonoFwd) hdr.appendLine()

        for ((baseName, instantiations) in genericInstantiations) {
            val templateDecl = genericClassDecls[baseName] ?: continue
            for (typeArgs in instantiations) {
                if (!firstClass) hdr.appendLine()
                firstClass = false
                val mangledName = mangledGenericName(baseName, typeArgs)
                val templateCi = classes[baseName] ?: continue
                // Set type substitution for this instantiation
                typeSubst = templateCi.typeParams.zip(typeArgs).toMap()
                // Switch source file attribution for mem-track
                val prevSourceFile = currentSourceFile
                declSourceFile[baseName]?.let { currentSourceFile = it }
                emitGenericClass(templateDecl, mangledName)
                // Emit interface vtable header declarations right after the class struct
                if (templateDecl.superInterfaces.isNotEmpty()) {
                    val resolvedIfaces = templateDecl.superInterfaces.map { substituteTypeRef(it, typeSubst) }
                    emitInterfaceVtablesForClass(mangledName, resolvedIfaces, declsOnly = true)
                }
                currentSourceFile = prevSourceFile
                typeSubst = emptyMap()
            }
        }

        // Emit monomorphized generic interfaces AFTER generic class structs so types are available
        // Only emit the VTABLE here; the tagged-union struct is emitted later after all class vtables.
        val emittedMonoIfaceVtables = mutableSetOf<String>()
        for ((name, info) in interfaces) {
            // Emit only monomorphized copies (not already emitted above as non-generic AST decl)
            if (name !in emittedIfaceNames && info.typeParams.isEmpty()
                && genericIfaceDecls.values.none { it.name == name }) {
                // Skip non-generic interfaces from other packages (they're in that package's header).
                // Monomorphized generics (e.g. MutableList_Int from MutableList<T>) are always
                // emitted here because they may reference user-defined types.
                val isMonomorphized = genericIfaceDecls.keys.any { tmpl -> name.startsWith(tmpl + "\$") }
                if (isMonomorphized) {
                    emitInterfaceVtable(info)
                    emittedMonoIfaceVtables += name
                } else {
                    val isCrossPackage = interfaces[name]?.pkg?.let { it.isNotEmpty() && it != prefix } == true
                    if (!isCrossPackage) {
                        emitInterfaceVtable(info)
                        emittedMonoIfaceVtables += name
                    }
                }
            }
        }

        // Build initial reverse map: interface → list of implementing classes
        // (from classInterfaces populated during collectDecl and materializeGenericClass).
        // Updated incrementally by emitTransitiveInterfaceVtables for transitive parent interfaces.
        for ((className, ifaces) in classInterfaces) {
            for (iface in ifaces) {
                interfaceImplementors.getOrPut(iface) { mutableListOf() }.add(className)
            }
        }

        // Emit static vtable instances + wrapping functions for interface implementations
        // Non-generic classes:
        for (d in file.decls) if (d is ClassDecl && d.typeParams.isEmpty() && d.superInterfaces.isNotEmpty()) {
            emitInterfaceVtablesForClass(d.name, d.superInterfaces, implsOnly = true)
        }
        // Objects implementing interfaces:
        for (d in file.decls) if (d is ObjectDecl && d.superInterfaces.isNotEmpty()) {
            classInterfaces[d.name] = d.superInterfaces.map { it.name }
            emitInterfaceVtablesForClass(d.name, d.superInterfaces, implsOnly = true)
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
                classInterfaces[mangledName] = resolvedIfaces.map { resolveTypeNameStr(it) }
                emitInterfaceVtablesForClass(mangledName, resolvedIfaces, implsOnly = true)
                typeSubst = emptyMap()
            }
        }

        // Emit tagged-union struct for ALL interfaces (non-generic + monomorphized).
        // Must be AFTER class struct definitions so union members are complete types
        // and AFTER all vtables so transitive implementors are known.
        val hasTaggedUnions = interfaces.keys.any { it in emittedIfaceNames || it in emittedMonoIfaceVtables }
        if (hasTaggedUnions) hdr.appendLine()
        for ((name, info) in interfaces) {
            if (name in emittedIfaceNames) {
                emitIfaceInfo(info)
            } else if (name in emittedMonoIfaceVtables) {
                emitIfaceInfo(info)
            }
        }

        // Emit top-level functions and properties
        for (d in file.decls) when (d) {
            is FunDecl  -> {
                // Skip generic function templates and star-projection extensions — handled below
                if (d.typeParams.isNotEmpty()) continue
                if (d.receiver != null && d.receiver.typeArgs.any { it.name == "*" }) continue
                // Skip extensions on generic types (expanded per class by emitStarExtFunInstantiations)
                if (d.receiver != null && d.receiver.typeArgs.isNotEmpty()
                    && (genericIfaceDecls.containsKey(d.receiver.name) || genericClassDecls.containsKey(d.receiver.name))) continue
                // Skip inline/infix extension functions — expanded at call sites only, not emitted as C functions
                if (d.receiver != null && (d.isInline || d.isInfix)) continue
                if (d.receiver != null) emitExtensionFun(d) else emitFun(d)
            }
            is PropDecl -> emitTopProp(d)
            else -> {}
        }

        // Emit monomorphized generic functions
        for (f in genericFunDecls) emitGenericFunInstantiations(f)

        // Emit star-projection extension functions (one per known instantiation)
        for (f in starExtFunDecls) emitStarExtFunInstantiations(f)

        // Emit enum values arrays and valueOf functions (only for enums referenced via enumValues/enumValueOf)
        emitEnumValuesData()

        val srcName = prefix.trimEnd('_').ifEmpty {
            sourceFileName.removeSuffix(".kt").ifEmpty { "main" }
        }
        val src = StringBuilder()
        src.appendLine("#include \"$srcName.h\"")
        src.appendLine()
        if (implFwd.isNotEmpty()) {
            src.append(implFwd)
            src.appendLine()
        }
        src.append(impl)

        return COutput(hdr.toString(), src.toString())
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
                        is ClassDecl -> if (d.typeParams.isNotEmpty()) declSourceFile[d.name] = f.sourceFile
                        is FunDecl -> if (d.typeParams.isNotEmpty()) declSourceFile[d.name] = f.sourceFile
                        else -> {}
                    }
                }
                // Record the package prefix for cross-file symbols
                when (d) {
                    is ClassDecl -> {
                        classes[d.name]?.pkg = fpfx  // set pkg on ClassInfo
                        // Register companion object prefix
                        for (vMember in d.members.filterIsInstance<ObjectDecl>()) {
                            objects["${d.name}$${vMember.name}"]?.pkg = fpfx  // set pkg on ObjInfo
                        }
                    }
                    is EnumDecl -> enums[d.name]?.pkg = fpfx
                    is InterfaceDecl -> interfaces[d.name]?.pkg = fpfx
                    is ObjectDecl -> objects[d.name]?.pkg = fpfx
                    is FunDecl -> {
                        if (d.receiver == null)
                            funNames[d.name] = if (d.name == "main") "main" else "$fpfx${d.name}"  // cross-file C name
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
                        funNames[d.name] = if (d.name == "main") "main" else "$prefix${d.name}"  // current-file C name (overrides allFiles)
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
                for (p in d.ctorParams) {
                    if ((p.isVal || p.isVar) && isRawArrayTypeRef(p.type)) {
                        codegenError("Class property '${p.name}' cannot have raw array type '${p.type.name}'. Use @Ptr Array<T> or @Size(N) Array<T> instead")
                    }
                }
                for (p in d.members.filterIsInstance<PropDecl>()) {
                    val propType = p.type ?: inferInitType(p.init)
                    if (isRawArrayTypeRef(propType)) {
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
                    if (m.returnType != null && isRawArrayTypeRef(m.returnType)) {
                        codegenError("Method '${m.name}' cannot return raw array type '${m.returnType.name}'. Use @Ptr Array<T> or @Size(N) Array<T> instead")
                    }
                    ci.methods += m
                }
                classes[d.name] = ci
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
                    if (isRawArrayTypeRef(propType)) {
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
                    if (m.returnType != null && isRawArrayTypeRef(m.returnType)) {
                        codegenError("Method '${m.name}' cannot return raw array type '${m.returnType.name}'. Use @Ptr Array<T> or @Size(N) Array<T> instead")
                    }
                    oi.methods += m
                    // Register in funSigs for return type inference at call sites
                    if (funSigs[m.name] == null) {
                        funSigs[m.name] = FunSig(m.params, m.returnType)
                    }
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
                if (d.returnType != null && isRawArrayTypeRef(d.returnType)) {
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

    /*
    Find the matching overloaded method from the given siblings that best matches the call args.
    Phase 4.6: uses KtcType comparison instead of string equality for type matching.
    Returns the matched FunDecl, or null.
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
