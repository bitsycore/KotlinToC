package com.bitsycore.ktc.codegen

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.ast.Annotation
import com.bitsycore.ktc.codegen.mapping.arrayElementKtTypeKtc
import com.bitsycore.ktc.codegen.mapping.primitiveToArrayOptionalType
import com.bitsycore.ktc.codegen.mapping.primitiveToArrayType
import com.bitsycore.ktc.types.KtcType

/**
 * ── Type Inference ──────────────────────────────────────────────────────
 *
 * Recursively determines the Kotlin type string for any AST expression.
 * Used by both the scanning passes (to discover generic instantiations)
 * and the codegen passes (to pick the right C type, null check strategy, etc.).
 *
 * Type strings follow the internal convention described in AGENTS.md:
 *   - "Int", "String", "Vec2" for plain types
 *   - "Int?" for nullable values (uses Optional struct)
 *   - "Vec2*" for @Ptr-annotated pointer types
 *   - "IntArray" for Array<Int>, "Vec2Array" for Array<Vec2>
 *   - "Fun(P1,P2)->R" for function types
 *
 * ## Main entry points:
 *
 *   [inferExprType]          — infer type of any expression (main dispatcher)
 *   [inferCallType]           — infer return type of a function/constructor call
 *   [inferMethodReturnType]   — infer return type of obj.method()
 *   [inferDotType]            — infer type of obj.field
 *   [inferIndexType]          — infer type of obj.get(index)
 *
 *   (helpers) [inferBlockType], [inferIfExprType], [inferWhenExprType]
 *
 * ## State accessed (read-only):
 *   classes, interfaces, enums, objects, funSigs, extensionFuns, genericFunDecls,
 *   genericFunConcreteReturn, classInterfaces, classArrayTypes,
 *   pairTypeComponents, tripleTypeComponents, tupleTypeComponents,
 *   scopes (lookupVar), lambdaParamTypes, typeSubst, currentClass, currentExtRecvType,
 *   allGenericTypeParamNames
 *
 * ## State mutated:
 *   genericInstantiations (via recordGenericInstantiation on inferred generic class usage)
 *
 * ## Dependencies:
 *   Calls into CCodeGenCTypes.kt (resolveTypeName, resolveIfaceName, resolveMethodReturnType, ...)
 *   Called from everywhere (scan passes, emitter, statements, expressions)
 */

// ═══════════════════════════ Type inference ═══════════════════════

internal fun CCodeGen.inferExprType(e: Expr?): String? = when (e) {
    null        -> null
    is IntLit   -> "Int"
    is LongLit  -> "Long"
    is UIntLit  -> "UInt"
    is ULongLit -> "ULong"
    is DoubleLit -> "Double"
    is FloatLit -> "Float"
    is BoolLit  -> "Boolean"
    is CharLit  -> "Char"
    is StrLit, is StrTemplateExpr -> "String"
    is NullLit  -> null
    is ThisExpr -> lambdaParamTypes["\$this"] ?: lookupVar("\$self") ?: currentExtRecvType ?: currentClass
    is NameExpr -> lambdaParamTypes[e.name] ?: lookupVar(e.name) ?: run {
        if (enums.containsKey(e.name)) e.name
        else if (objects.containsKey(e.name)) e.name
        else {
            // Bare field access when $self is narrowed from interface in extension function
            val vNarrowedSelf = if (currentExtRecvType != null && interfaces.containsKey(currentExtRecvType))
                lookupVar("\$self") else null
            if (vNarrowedSelf != null && classes.containsKey(vNarrowedSelf)) {
                val vCi = classes[vNarrowedSelf]!!
                val vProp = vCi.props.find { it.first == e.name }
                if (vProp != null) resolveTypeName(vProp.second).toInternalStr else null
            } else {
                // Parent object field inside nested class
                val parentObj = currentClass?.substringBefore('$')
                if (parentObj != null && currentObject == null) {
                    val oi = objects[parentObj]
                    if (oi?.props?.any { it.first == e.name } == true)
                        resolveTypeName(oi.props.find { it.first == e.name }!!.second).toInternalStr
                    else null
                } else null
            }
        }
    }
    is BinExpr  -> {
        if (e.op in setOf("==", "!=", "<", ">", "<=", ">=", "&&", "||", "in", "!in")) "Boolean"
        else if (e.op == "..") "IntRange"
        else if (e.op == "to") {
            val a = inferExprType(e.left) ?: "Int" // left operand type
            val b = inferExprType(e.right) ?: "Int" // right operand type
            if (genericClassDecls.containsKey("Pair")) {
                /** Register and materialize immediately so genericTypeBindings is
                available when matchTypeParam is called during scan phase. */
                val vMangled = recordGenericInstantiation("Pair", listOf(a, b))
                materializeGenericInstantiations()
                vMangled
            } else "Pair_${a}_${b}"
        }
        else {
            /** User-defined infix operator: look up in inlineExtFunDecls, resolve return type with type substitution. */
            val vInfixDecl = inlineExtFunDecls[e.op] // infix function declaration, or null if not user-defined infix
            if (vInfixDecl != null && vInfixDecl.returnType != null) {
                val vRecvType = inferExprType(e.left)  // receiver type (e.g. "Int")
                val vArgType  = inferExprType(e.right) // argument type (e.g. "String")
                val vSavedSubst = typeSubst            // save outer substitution
                if (vInfixDecl.typeParams.isNotEmpty()) {
                    typeSubst = inferInlineFunSubst(vInfixDecl, vRecvType, listOf(vArgType))
                }
                val vResult = resolveTypeName(vInfixDecl.returnType).toInternalStr // concrete return type string
                typeSubst = vSavedSubst
                vResult
            } else {
                inferExprType(e.left)  // arithmetic inherits left type
            }
        }
    }
    is PrefixExpr -> if (e.op == "!") "Boolean" else inferExprType(e.expr)
    is PostfixExpr -> inferExprType(e.expr)
    is CallExpr -> inferCallType(e)
    is DotExpr  -> inferDotType(e)
    is SafeDotExpr -> inferDotTypeSafe(e)
    is IndexExpr -> inferIndexType(e)
    is IfExpr   -> inferIfExprType(e)
    is WhenExpr -> inferWhenExprType(e)
    is NotNullExpr -> inferExprType(e.expr)?.removeSuffix("?")
    is ElvisExpr -> (inferExprType(e.left) ?: inferExprType(e.right))?.removeSuffix("?")
    is IsCheckExpr -> "Boolean"
    is CastExpr -> if (e.safe) e.type.name + "?" else e.type.name
    is FunRefExpr -> {
        // Look up the function signature and build a Fun(...)->R type string
        val sig = funSigs[e.name]
        if (sig != null) {
            val params = sig.params.joinToString(",") { resolveTypeName(it.type).toInternalStr }
            val ret = if (sig.returnType != null) resolveTypeName(sig.returnType).toInternalStr else "Unit"
            "Fun($params)->$ret"
        } else null
    }
    is LambdaExpr -> null
}

internal fun CCodeGen.inferCallType(e: CallExpr): String? {
    // Nested class constructor: Outer.Inner(...) or A.B.C(...) → flat name
    if (e.callee is DotExpr) {
        fun flattenDotCallee(callee: Expr): String? {
            if (callee is NameExpr) return callee.name
            if (callee is DotExpr && callee.obj is NameExpr)
                return "${callee.obj.name}$${callee.name}"
            if (callee is DotExpr) {
                val left = flattenDotCallee(callee.obj)
                if (left != null) return "$left$${callee.name}"
            }
            return null
        }
        val flatCallee = flattenDotCallee(e.callee)
        if (flatCallee != null) {
            if (classes.containsKey(flatCallee)) return flatCallee
            if (genericClassDecls.containsKey(flatCallee)) {
                val resolvedArgs = e.typeArgs.map { t ->
                    val sub = substituteTypeParams(t)
                    if (sub.nullable) "${resolveTypeNameStr(sub)}?" else resolveTypeNameStr(sub)
                }
                return mangledGenericName(flatCallee, resolvedArgs)
            }
        }
        // allocWith → @Ptr ClassType
        if (e.callee.name == "allocWith" && e.callee.obj is NameExpr) {
            val objName = e.callee.obj.name
            val typeArgs = e.typeArgs
            val t = if (typeArgs.isNotEmpty()) TypeRef(objName, typeArgs = typeArgs, annotations = listOf(Annotation("Ptr")))
                    else TypeRef(objName, annotations = listOf(Annotation("Ptr")))
            return resolveTypeName(t).toInternalStr
        }
    }
    val name = (e.callee as? NameExpr)?.name
    if (name != null) {
        // StringBuffer constructor (intrinsic — only when no user-defined class named StringBuffer)
        if (name == "StringBuffer" && !classes.containsKey("StringBuffer") && !genericClassDecls.containsKey("StringBuffer")) {
            return "ktc_StrBuf"
        }
        // Generic class constructor: MyList<Int>(8) → "MyList_Int"
        // Apply typeSubst so type params resolve inside generic function bodies
        if (classes.containsKey(name) && classes[name]!!.isGeneric && e.typeArgs.isNotEmpty()) {
            val resolvedArgs = e.typeArgs.map { t ->
                val sub = substituteTypeParams(t)
                if (sub.nullable) "${resolveTypeNameStr(sub)}?" else resolveTypeNameStr(sub)
            }
            return mangledGenericName(name, resolvedArgs)
        }
        if (classes.containsKey(name) && classes[name]!!.isGeneric && e.args.isNotEmpty()
            && classes[name]!!.typeParams.size == e.args.size) {
            // Infer type args from constructor arguments (e.g. Wrapper("hello") → Wrapper_String)
            val inferredArgs = e.args.map { inferExprType(it.expr) ?: "Int" }
            recordGenericInstantiation(name, inferredArgs)
            return mangledGenericName(name, inferredArgs)
        }
        if (classes.containsKey(name)) return name
        if (name == "HeapAlloc" || name == "HeapArrayZero" || name == "HeapArrayResize" || name == "heapArrayOf") {
            // Resolve target type: explicit type arg, else pre-set target, else opaque void*
            val ta = e.typeArgs.getOrNull(0)
                ?: heapAllocTargetType
                ?: return "void*"

            // Array<T> → T* (typed array pointer)
            if (ta.name == "Array" && ta.typeArgs.isNotEmpty()) {
                val elemName = typeSubst[ta.typeArgs[0].name] ?: ta.typeArgs[0].name
                return "${elemName}*"
            }
            // RawArray<T> → T* (raw pointer, no $len companion)
            if (ta.name == "RawArray" && ta.typeArgs.isNotEmpty()) {
                val elemName = typeSubst[ta.typeArgs[0].name] ?: ta.typeArgs[0].name
                return "${elemName}*"
            }
            // MyList<Int> → MyList_Int* (generic class heap pointer)
            if (ta.typeArgs.isNotEmpty() && classes.containsKey(ta.name) && classes[ta.name]!!.isGeneric) {
                val resolvedArgs = ta.typeArgs.map { t ->
                    val sub = substituteTypeParams(t)
                    if (sub.nullable) "${resolveTypeNameStr(sub)}?" else resolveTypeNameStr(sub)
                }
                return "${mangledGenericName(ta.name, resolvedArgs)}*"
            }
            // Plain type T → T* (user class or resolved type param)
            val resolvedName = typeSubst[ta.name] ?: ta.name
            return "${resolvedName}*"
        }
        if (name == "byteArrayOf" || name == "ByteArray") return "ByteArray"
        if (name == "shortArrayOf" || name == "ShortArray") return "ShortArray"
        if (name == "intArrayOf" || name == "IntArray") return "IntArray"
        if (name == "longArrayOf" || name == "LongArray") return "LongArray"
        if (name == "floatArrayOf" || name == "FloatArray") return "FloatArray"
        if (name == "doubleArrayOf" || name == "DoubleArray") return "DoubleArray"
        if (name == "booleanArrayOf" || name == "BooleanArray") return "BooleanArray"
        if (name == "charArrayOf" || name == "CharArray") return "CharArray"
        if (name == "ubyteArrayOf" || name == "UByteArray") return "UByteArray"
        if (name == "ushortArrayOf" || name == "UShortArray") return "UShortArray"
        if (name == "uintArrayOf" || name == "UIntArray") return "UIntArray"
        if (name == "ulongArrayOf" || name == "ULongArray") return "ULongArray"
        if (name == "arrayOf") {
            // Prefer explicit type argument (arrayOf<T?> or arrayOf<T>)
            if (e.typeArgs.isNotEmpty()) {
                val vTypeArg = e.typeArgs[0]
                val vElemName = typeSubst[vTypeArg.name] ?: vTypeArg.name
                if (vTypeArg.nullable) {
                    return primitiveToArrayOptionalType(vElemName)
                }
                return primitiveToArrayType(vElemName)
            }
            // Fall back to inference from first argument
            val elemType = if (e.args.isNotEmpty()) inferExprType(e.args[0].expr) ?: "Int" else "Int"
            return primitiveToArrayType(elemType)
        }
        if (name == "arrayOfNulls") {
            if (e.typeArgs.isNotEmpty()) {
                val vTypeArg = e.typeArgs[0]
                val vElemName = typeSubst[vTypeArg.name] ?: vTypeArg.name
                return primitiveToArrayOptionalType(vElemName)
            }
            return "IntOptArray"
        }
        if (name == "enumValues") {
            if (e.typeArgs.isNotEmpty()) {
                val enumName = e.typeArgs[0].name
                val resolved = typeSubst[enumName] ?: enumName
                return "${resolved}Array"
            }
            return "IntArray"
        }
        if (name == "enumValueOf") {
            if (e.typeArgs.isNotEmpty()) {
                val enumName = e.typeArgs[0].name
                return typeSubst[enumName] ?: enumName
            }
            return "Int"
        }
        // Generic Array<T>(size) constructor
        if (name == "Array" && e.typeArgs.isNotEmpty()) {
            val elemName = resolveTypeName(e.typeArgs[0]).toInternalStr
            return "${elemName}Array"
        }
        // Generic function call: newArray<Int>(5) → resolve return type with type substitution
        // Also handles implicit type args inferred from arguments
        val genFun = genericFunDecls.find { it.name == name }
        if (genFun != null && genFun.returnType != null) {
            val typeArgNames = if (e.typeArgs.isNotEmpty()) {
                e.typeArgs.map { resolveTypeName(it).toInternalStr }
            } else {
                // Infer type args from argument types
                val inferredSubst = mutableMapOf<String, String>()
                for ((i, param) in genFun.params.withIndex()) {
                    if (i >= e.args.size) break
                    val argType = inferExprType(e.args[i].expr) ?: continue
                    matchTypeParam(param.type, argType, genFun.typeParams.toSet(), inferredSubst)
                }
                if (inferredSubst.size == genFun.typeParams.size) genFun.typeParams.map { inferredSubst[it]!! } else null
            }
            if (typeArgNames != null) {
                // Check if this instantiation has a known concrete return type
                val mangledName = "${name}_${typeArgNames.joinToString("_")}"
                val concreteRet = genericFunConcreteReturn[mangledName]
                if (concreteRet != null) return concreteRet
                val subst = genFun.typeParams.zip(typeArgNames).toMap()
                val saved = typeSubst
                typeSubst = subst
                val result = resolveTypeName(genFun.returnType)
                typeSubst = saved
                return if (genFun.returnType.nullable) KtcType.Nullable(result).toInternalStr else result.toInternalStr
            }
        }
        activeLambdas[name]?.returnType?.let { return it }
        funSigs[name]?.returnType?.let {
            val base = resolveTypeName(it)
            return if (it.nullable) KtcType.Nullable(base).toInternalStr else base.toInternalStr
        }
        // Bare call to interface method inside extension function on that interface
        if (currentExtRecvType != null && interfaces.containsKey(currentExtRecvType)) {
            val vIfaceInfo = interfaces[currentExtRecvType]!!
            val vIfaceMethod = vIfaceInfo.methods.find { it.name == name }
                ?: collectAllIfaceMethods(vIfaceInfo).find { it.name == name }
            if (vIfaceMethod?.returnType != null) {
                val baseKtc = resolveTypeName(vIfaceMethod.returnType)
                return if (vIfaceMethod.returnType.nullable) KtcType.Nullable(baseKtc).toInternalStr else baseKtc.toInternalStr
            }
        }
    }
    if (e.callee is DotExpr) return inferMethodReturnType(e.callee, e.args)
    if (e.callee is SafeDotExpr) {
        val retType = inferMethodReturnType(DotExpr(e.callee.obj, e.callee.name), e.args) ?: return null
        if (retType == "Unit") return retType
        val retKtc = parseResolvedTypeName(retType)
        return if (retKtc is KtcType.Nullable) retType else "${retType}?"
    }
    return null
}

/** Resolve a method return type, applying generic bindings if the class is a concrete generic instantiation. */
internal fun CCodeGen.resolveMethodReturnType(className: String, returnType: TypeRef?): String {
    if (returnType == null) return "Unit"
    val bindings = genericTypeBindings[className]
    val ktc = if (bindings != null) {
        val saved = typeSubst
        typeSubst = bindings
        val result = resolveTypeName(returnType)
        typeSubst = saved
        result
    } else {
        resolveTypeName(returnType)
    }
    return if (returnType.nullable) KtcType.Nullable(ktc).toInternalStr else ktc.toInternalStr
}

internal fun CCodeGen.inferMethodReturnType(dot: DotExpr, args: List<Arg>): String? {
    // C package: can't infer return type of C functions
    if (dot.obj is NameExpr && dot.obj.name == "c" && lookupVar(dot.obj.name) == null) return null
    // Companion object method return type
    val vDotObjName = (dot.obj as? NameExpr)?.name
    val vCompanionName = vDotObjName?.let { classCompanions[it] }
    if (vCompanionName != null) {
        val vMethod = objects[vCompanionName]?.methods?.find { it.name == dot.name }
        if (vMethod != null && vMethod.returnType != null) {
            val baseKtc = resolveTypeName(vMethod.returnType)
            return if (vMethod.returnType.nullable) KtcType.Nullable(baseKtc).toInternalStr else baseKtc.toInternalStr
        }
        return null
    }
    val recvType = inferExprType(dot.obj) ?: return null
    val recvKtcPtr = inferExprTypeKtc(dot.obj)
    val recvKtcCorePtr = (recvKtcPtr as? KtcType.Nullable)?.inner ?: recvKtcPtr
    val method = dot.name
    if (method == "toString") return "String"
    if (method == "trimIndent") return "String"
    if (method == "trimMargin") return "String"
    if (method == "runeAt") return "Rune"
    if (method == "toInt") return "Int"
    if (method == "toLong") return "Long"
    if (method == "toFloat") return "Float"
    if (method == "toDouble") return "Double"
    if (method == "toIntOrNull") return "Int?"
    if (method == "toLongOrNull") return "Long?"
    if (method == "toFloatOrNull") return "Float?"
    if (method == "toDoubleOrNull") return "Double?"
    if (method == "hashCode") return "Int"
    if (method == "inv") return recvType  // bitwise NOT returns same type
    // Array methods (including pointer-to-array)
    val recvKtc = parseResolvedTypeName(recvType)
    val isArrayPtr = recvKtc.isArrayLike
    if (isArrayPtr) {
        return when (method) {
            "size" -> "Int"
            "get" -> {
                val base = recvType.removeSuffix("?")
                // IntArray*? → strip Array → Int; Int*? → strip * → Int
                if (base.endsWith("Array*")) base.removeSuffix("Array*") else base.removeSuffix("*")
            }
            "ptr", "toHeap" -> {
                // For XxxArray* types, return element-type pointer (e.g. IntArray* → Int*)
                val base = recvType.removeSuffix("?")
                if (base.endsWith("Array*")) {
                    val elem = base.removeSuffix("Array*")
                    val internal = if (elem.endsWith("Opt")) "${elem.removeSuffix("Opt")}?" else elem
                    "${internal}*"
                } else if (recvType.endsWith("Array")) {
                    val elem = recvType.removeSuffix("Array")
                    val internal = if (elem.endsWith("Opt")) "${elem.removeSuffix("Opt")}?" else elem
                    "${internal}*"
                } else recvType
            }
            "set" -> "Unit"
            else -> null
        }
    }
    // String methods
    if (recvType.removeSuffix("?") == "String") {
        return when (method) {
            "substring" -> "String"
            "startsWith", "endsWith", "contains", "isEmpty", "isNotEmpty" -> "Boolean"
            "indexOf" -> "Int"
            else -> null
        }
    }
    // @Ptr/@Heap/@Value pointer methods (type return inference)
    val pointerBase = (recvKtcCorePtr as? KtcType.Ptr)?.inner?.let { it as? KtcType.User }?.baseName
    if (pointerBase != null) {
        val classMethod = classes[pointerBase]?.methods?.find { it.name == method }
        if (classMethod != null) {
            return resolveMethodReturnType(pointerBase, classMethod.returnType)
        }
        val extFun = extensionFuns[pointerBase]?.find { it.name == method }
        if (extFun != null) return if (extFun.returnType != null) resolveTypeName(extFun.returnType).toInternalStr else "Unit"
        return when (method) {
            "value" -> pointerBase
            "deref" -> pointerBase
            "set" -> "Unit"
            "copy" -> pointerBase
            "toHeap", "ptr" -> "${pointerBase}*"
            else -> null
        }
    }
    // Stack class methods
    val baseClass = recvType.removeSuffix("?")
    if (classes.containsKey(baseClass)) {
        if (method == "copy") return baseClass
        if (method == "toHeap" || method == "ptr") return "${baseClass}*"
    }
    // Interface method
    val iface = interfaces[recvType]
    if (iface != null) {
        val m = iface.methods.find { it.name == method }
        if (m != null && m.returnType != null) {
            return resolveMethodReturnType(recvType, m.returnType)
        }
    }
    // Object method
    if (objects.containsKey(baseClass)) {
        val m = objects[baseClass]!!.methods.find { it.name == method }
        if (m != null) return resolveMethodReturnType(baseClass, m.returnType)
    }
    // Class method
    val ci = classes[recvType]
    if (ci != null) {
        val m = ci.methods.find { it.name == method }
        if (m != null) return resolveMethodReturnType(recvType, m.returnType)
    }
    // Extension function on non-class type
    val extFun = extensionFuns[recvType]?.find { it.name == method }
    if (extFun != null) {
        /** Generic extension: infer concrete return type by substituting type params from receiver + args. */
        if (extFun.typeParams.isNotEmpty() && extFun.returnType != null) {
            val vArgTypes    = args.map { inferExprType(it.expr) } // concrete argument types
            val vSavedSubst  = typeSubst                           // save outer substitution
            typeSubst        = inferInlineFunSubst(extFun, recvType, vArgTypes)
            val vResult      = resolveTypeName(extFun.returnType).toInternalStr
            typeSubst        = vSavedSubst
            return vResult
        }
        return if (extFun.returnType != null) resolveTypeName(extFun.returnType).toInternalStr else "Unit"
    }
    // Enum static methods
    if (recvType in enums) {
        when (method) {
            "values" -> return "${recvType}Array"
            "valueOf" -> return recvType
        }
    }
    return null
}

internal fun CCodeGen.inferDotType(e: DotExpr): String? = inferDotTypeKtc(e)?.toInternalStr

internal fun CCodeGen.inferDotTypeKtc(e: DotExpr): KtcType? {
    // C package: can't infer type of C constants/macros
    if (e.obj is NameExpr && e.obj.name == "c" && lookupVar("c") == null) return null
    if (e.obj is NameExpr && enums.containsKey(e.obj.name)) return parseResolvedTypeName(e.obj.name)
    val vDotObjInfo = resolveDotObjInfo(e)
    if (vDotObjInfo != null) {
        val prop = vDotObjInfo.props.find { it.first == e.name }
        val base = if (prop != null) resolveTypeName(prop.second) else null
        return if (base != null && prop!!.second.nullable) KtcType.Nullable(base) else base
    }
    val recvType = inferExprType(e.obj) ?: return null
    val recvTypeKtc = inferExprTypeKtc(e.obj)
    val recvTypeCoreKtc = (recvTypeKtc as? KtcType.Nullable)?.inner ?: recvTypeKtc
    // StringBuffer field types
    if (recvType == "ktc_StrBuf" || recvType == "StringBuffer") {
        return when (e.name) {
            "buffer" -> parseResolvedTypeName("CharArray*?")  // nullable pointer to char array
            "len" -> KtcType.Prim(KtcType.PrimKind.Int)
            else -> null
        }
    }
    if (e.name == "size" && recvTypeCoreKtc != null && recvTypeCoreKtc.isArrayLike) return KtcType.Prim(KtcType.PrimKind.Int)
    if (e.name == "ptr") {
        if (recvTypeCoreKtc?.isArrayLike == true) {
            val arr = recvTypeCoreKtc.asArr
            if (arr != null) return KtcType.Ptr(arr.elem)
        }
        if (recvTypeCoreKtc is KtcType.Ptr && recvTypeCoreKtc.inner is KtcType.Arr) return parseResolvedTypeName(recvType)
        return if (recvTypeCoreKtc is KtcType.Ptr) parseResolvedTypeName(recvType) else parseResolvedTypeName("${recvType}*")
    }
    if (e.name == "toHeap" && recvTypeCoreKtc?.isArrayLike == true) {
        val arr = recvTypeCoreKtc.asArr
        if (arr != null) return KtcType.Ptr(arr.elem)
    }
    if (e.name == "length" && recvTypeCoreKtc is KtcType.Str) return KtcType.Prim(KtcType.PrimKind.Int)
    if (e.name == "runeLen" && recvTypeCoreKtc is KtcType.Str) return KtcType.Prim(KtcType.PrimKind.Int)
    // Enum value .name / .ordinal
    if (e.name == "name" && recvTypeCoreKtc is KtcType.User && recvTypeCoreKtc.kind == KtcType.UserKind.Enum) return KtcType.Str
    if (e.name == "ordinal" && recvTypeCoreKtc is KtcType.User && recvTypeCoreKtc.kind == KtcType.UserKind.Enum) return KtcType.Prim(KtcType.PrimKind.Int)
    // Heap/Ptr/Value pointer field access → look up in base class
    val indirectBase = (recvTypeCoreKtc as? KtcType.Ptr)?.inner?.let { it as? KtcType.User }?.baseName
    if (indirectBase != null) {
        val ci = classes[indirectBase] ?: return null
        val prop = ci.props.find { it.first == e.name }
        val baseIndirect = if (prop != null) resolveTypeName(prop.second) else null
        return if (baseIndirect != null && prop!!.second.nullable) KtcType.Nullable(baseIndirect) else baseIndirect
    }
    val ci = classes[recvType] ?: return null
    val prop = ci.props.find { it.first == e.name }
    val baseDirect = if (prop != null) resolveTypeName(prop.second) else null
    return if (baseDirect != null && prop!!.second.nullable) KtcType.Nullable(baseDirect) else baseDirect
}

internal fun CCodeGen.inferDotTypeSafe(e: SafeDotExpr): String? {
    val base = inferDotTypeKtc(DotExpr(e.obj, e.name)) ?: return null
    return if (base is KtcType.Nullable) base.toInternalStr else KtcType.Nullable(base).toInternalStr
}
internal fun CCodeGen.inferIndexType(e: IndexExpr): String? {
    val tRaw = inferExprType(e.obj) ?: return null
    val tKtc = inferExprTypeKtc(e.obj)
    val tKtcCore = (tKtc as? KtcType.Nullable)?.inner ?: tKtc
    // Strip nullability: indexing a nullable array is valid after a null guard
    val t = tRaw.removeSuffix("?")
    // String indexing: str[i] → Char
    if (t == "String") return "Char"
    // Class with operator get() method → return type of get
    if (classes.containsKey(t)) {
        val methodDecl = classes[t]?.methods?.find { it.name == "get" && it.isOperator }
        if (methodDecl?.returnType != null) {
            return resolveMethodReturnType(t, methodDecl.returnType)
        }
    }
    // wrapping a class with operator get()
    val indirectBase = (tKtcCore as? KtcType.Ptr)?.inner?.let { it as? KtcType.User }?.baseName
    if (indirectBase != null && classes.containsKey(indirectBase)) {
        val methodDecl = classes[indirectBase]?.methods?.find { it.name == "get" && it.isOperator }
        if (methodDecl?.returnType != null) {
            return resolveMethodReturnType(indirectBase, methodDecl.returnType)
        }
    }
    // Interface with operator get() in vtable
    if (interfaces.containsKey(t)) {
        val ifaceInfo = interfaces[t]
        val ifaceMethod = ifaceInfo?.methods?.find { it.name == "get" && it.isOperator }
            ?: collectAllIfaceMethods(ifaceInfo!!).find { it.name == "get" && it.isOperator }
        if (ifaceMethod?.returnType != null) {
            return resolveMethodReturnType(t, ifaceMethod.returnType)
        }
    }
    // Typed pointer: Ptr<UserClass> → base name. Exclude typed arrays (Ptr<Arr<T>>).
    if (tKtcCore is KtcType.Ptr && tKtcCore.inner !is KtcType.Arr) {
        return tKtcCore.inner.toInternalStr
    }
    return tKtcCore?.let { arrayElementKtTypeKtc(it) } ?: "Int"
}

// ══ Phase 4.4 — KtcType inference entry points ══════════════════════

/**
Infer the KtcType of an expression.
For NameExpr uses lookupVarKtc directly to avoid string round-trip.
All other branches delegate to inferExprType and convert via stringToKtc.
Callers should migrate to this function progressively (Phase 5 dispatch).
*/
internal fun CCodeGen.inferExprTypeKtc(inExpr: Expr?): KtcType?
	{
	if (inExpr == null) return null
	if (inExpr is NameExpr)
		{
		/** Use KtcType scope directly — avoids toInternalStr → stringToKtc round-trip. */
		val vKtc = lookupVarKtc(inExpr.name)
		if (vKtc != null) return vKtc
		/** Fall through to string-based for objects/enums/parent-object lookup. */
		}
	val vStr = inferExprType(inExpr) ?: return null  // string-based fallback
	return parseResolvedTypeName(vStr)
	}