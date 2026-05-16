package com.bitsycore.ktc.codegen

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.codegen.mapping.arrayElementCTypeKtc
import com.bitsycore.ktc.codegen.mapping.primitiveArraySet
import com.bitsycore.ktc.codegen.mapping.primitiveToArrayOptionalType
import com.bitsycore.ktc.codegen.mapping.primitiveToArrayType
import com.bitsycore.ktc.types.BuiltinTypeDef
import com.bitsycore.ktc.types.KtcType
import com.bitsycore.ktc.types.TypeDef

/**
 * ── C Type Mapping & Printf Helpers ─────────────────────────────────────
 *
 * Converts the internal Kotlin type representation to C type strings,
 * parameter lists, and printf format/argument pairs. This module is the
 * "leaf" dependency — used by almost every other module but calls no other
 * module itself (besides the core CCodeGen state).
 *
 * Type resolution follows [resolveTypeName] → [resolveTypeNameStr] → [resolveTypeNameInnerStr]:
 *   - Primitives: Byte → ktc_Byte, Int → ktc_Int, ...
 *   - Arrays: ByteArray → ktc_Byte*, Array<Vec2> → Vec2Array → game_Vec2*
 *   - Nullable: Int? → ktc_Int$Optional (via [com.bitsycore.ktc.codegen.CCodeGen.optCTypeName])
 *   - Pointers: @Ptr T → T*
 *   - Function types: (P1,P2)->R → Fun(P1,P2)->R (stored as string key)
 *   - Generics: MutableList<Int> → MutableList_Int (via mangledGenericName)
 *   - Pair/Triple: stdlib generic classes, resolved via generic instantiation (e.g. Pair$2_Int_String)
 *
 * ## Main entry points:
 *
 *   [resolveTypeName]        — resolve a TypeRef to KtcType (primary entry point)
 *   [resolveTypeNameStr]     — resolve a TypeRef to internal type string (legacy bridge)
 *   [resolveTypeNameInnerStr] — actual string resolution logic (after @Ptr strip)
 *   [resolveIfaceName]       — resolve interface TypeRef to concrete name
 *   [cTypeStr]               — internal type → C type name
 *   [cType]                  — TypeRef → C type name (shortcut)
 *   [expandParams]           — function params → C param list string
 *   [expandCtorParams]       — ctor params → C param list string
 *   [expandCallArgs]         — call args → C arg list string
 *
 *   (helpers) [isArrayType], [isRawArrayTypeRef], [isSizedArrayTypeRef],
 *             [com.bitsycore.ktc.codegen.CCodeGen.optCTypeName], [com.bitsycore.ktc.codegen.CCodeGen.optNone], [com.bitsycore.ktc.codegen.CCodeGen.optSome],
 *             [com.bitsycore.ktc.codegen.CCodeGen.isFuncType], [com.bitsycore.ktc.codegen.CCodeGen.parseFuncType], [com.bitsycore.ktc.codegen.CCodeGen.cFuncPtrDecl],
 *             [defaultVal], [hasSizeAnnotation], [getSizeAnnotation]
 *
 *   (printf) [printfFmt], [printfArg], [escapeC], [escapeStr]
 *
 * ## State accessed (read-only):
 *   classes, interfaces, enums, classArrayTypes, pairTypeComponents,
 *   tripleTypeComponents, tupleTypeComponents, genericClassDecls, genericIfaceDecls,
 *   allGenericTypeParamNames, typeSubst, prefix
 *
 * ## Dependencies:
 *   None (leaf module). Called from all other modules.
 */

// ═══════════════════════════ C type mapping ═══════════════════════

/** Expand ctor props: array → (T* name, ktc_Int name$len), nullable → OptT name. */
internal fun CCodeGen.expandCtorParams(inProps: List<PropertyDef>): String {
    val vParts = mutableListOf<String>() // accumulated C parameter declarations
    for (vProp in inProps) {
        val vName = vProp.name                     // parameter name
        val vType = vProp.typeRef                  // parameter type
        val vKtc = resolveTypeName(vType)          // KtcType (with full func param info)
        if (vKtc is KtcType.Func) {
            vParts += cFuncPtrDecl(vKtc, vName)
        } else if (vKtc.isArrayLike) {
            if (hasSizeAnnotation(vType)) {
                vParts += "${cTypeStr(vKtc)} $vName"
            } else {
                vParts += "${cTypeStr(vKtc)} $vName"
                vParts += "ktc_Int ${vName}\$len"
            }
        } else if (vType.nullable) {
            vParts += "${optCTypeName(vKtc.toInternalStr)} $vName"
        } else {
            vParts += "${cTypeStr(vKtc)} $vName"
        }
    }
    return vParts.joinToString(", ")
}

internal fun CCodeGen.cType(t: TypeRef): String = cTypeStr(resolveTypeName(t))

/** Emit alloca+memcpy copies for all variable array params and record them as trampolined. */
internal fun CCodeGen.emitArrayParamCopies(inParams: List<Param>, inInd: String) {
    var vAny = false // whether any trampoline was emitted
    for (vP in inParams) {
        if (vP.isVararg) continue
        // Use isRawArrayTypeRef to identify trampoline-passed arrays (not @Ptr, not @Size)
        if (!isRawArrayTypeRef(vP.type)) continue
        // Both nullable and non-nullable array params use ktc_ArrayTrampoline.
        // Non-nullable: copy unconditionally. Nullable: copy only when data != NULL.
        if (!vAny) {
            impl.appendLine("${inInd}// ── trampoline array start ──")
            vAny = true
        }
        val vElem = resolveTypeName(vP.type).asArr!!.elem            // element KtcType
        val vElemCType = if (vElem is KtcType.Nullable)                    // Optional for nullable element types
            optCTypeName(vElem.inner.toInternalStr)
        else
            cTypeStr(vElem)                                                // plain C type for non-nullable
        if (vP.type.nullable) {
            impl.appendLine("${inInd}$vElemCType* local$${vP.name} = NULL;")
            impl.appendLine("${inInd}if (${vP.name}.data != NULL) {")
            impl.appendLine("$inInd    local$${vP.name} = ($vElemCType*)ktc_core_alloca(sizeof($vElemCType) * ${vP.name}.size);")
            impl.appendLine("$inInd    memcpy(local$${vP.name}, ${vP.name}.data, sizeof($vElemCType) * ${vP.name}.size);")
            impl.appendLine("${inInd}}")
        } else {
            impl.appendLine("${inInd}$vElemCType* local$${vP.name} = ($vElemCType*)ktc_core_alloca(sizeof($vElemCType) * ${vP.name}.size);")
            impl.appendLine("${inInd}memcpy(local$${vP.name}, ${vP.name}.data, sizeof($vElemCType) * ${vP.name}.size);")
        }
        trampolinedParams += vP.name
    }
    if (vAny) impl.appendLine("${inInd}// ── trampoline array end ──")
}

// KtcType overload
internal fun ptrNullComment(kt: KtcType): String = when (kt) {
    is KtcType.Nullable if kt.inner is KtcType.Ptr -> " /** nullable */"
    is KtcType.Ptr -> " /** notnull */"
    is KtcType.Arr -> " /** notnull */"
    else -> ""
}

/** Expand a parameter list: variable array params → ktc_ArrayTrampoline, @Size arrays → T*, nullable params → OptT name. */
internal fun CCodeGen.expandParams(inParams: List<Param>): String {
    val vParts = mutableListOf<String>() // accumulated C parameter declarations
    for (vP in inParams) {
        val vKtc = resolveTypeName(vP.type)  // KtcType (full func param info preserved)
        if (vP.isVararg) {
            vParts += "${cTypeStr(vKtc)}* ${vP.name}"
            vParts += "ktc_Int ${vP.name}\$len"
        } else if (vKtc is KtcType.Func) {
            vParts += cFuncPtrDecl(vKtc, vP.name)
        } else if (vKtc is KtcType.Ptr && vP.type.annotations.any { it.name == "Ptr" }) {
            // Explicitly @Ptr-annotated: raw pointer; nullability lives in vP.type.nullable
            // (typed arrays — IntArray, IntOptArray — also resolve to Ptr but are handled by isArrayLike below)
            val vNullComment = if (vP.type.nullable) " /** nullable */" else " /** notnull */" // null comment
            vParts += "${cTypeStr(vKtc)} ${vP.name}$vNullComment"
            // @Ptr arrays add $len companion (unless sized — size known at compile time)
            val vInnerArr = vKtc.inner.asArr                                                  // Arr node if Ptr(Arr)
            if (vInnerArr != null && vInnerArr.sized == null) vParts += "ktc_Int ${vP.name}\$len"
        } else if (vKtc.isArrayLike) {
            if (hasSizeAnnotation(vP.type)) {
                // @Size(N) fixed array — passed as raw pointer (size known at compile time)
                vParts += "${cTypeStr(vKtc)} ${vP.name}"
            } else {
                // Both nullable and non-nullable arrays use ktc_ArrayTrampoline for value semantics.
                // Nullable: data == NULL means the array argument was null.
                val vNullComment = if (vP.type.nullable) " /** nullable */" else "" // null comment
                val vArrElem = vKtc.asArr!!.elem                                // element KtcType
                val vElemCType = if (vArrElem is KtcType.Nullable)                // Optional for nullable elem
                    optCTypeName(vArrElem.inner.toInternalStr)
                else
                    cTypeStr(vArrElem)                                               // plain C type
                vParts += "ktc_ArrayTrampoline ${vP.name}$vNullComment /** ${vElemCType}[] */"
            }
        } else if (vP.type.nullable) {
            val vNullComment =
                if (vKtc is KtcType.Any) " /** nullable */" else "" // null comment
            vParts += "${optCTypeName(vKtc.toInternalStr)} ${vP.name}$vNullComment"
        } else {
            vParts += "${cTypeStr(vKtc)} ${vP.name}"
        }
    }
    return vParts.joinToString(", ")
}

internal fun CCodeGen.cTypeStr(t: String): String {
    if (t == "ktc_IfacePtr" || t.startsWith("ktc_IfacePtr:")) return "ktc_IfacePtr"
    val ktc = parseResolvedTypeName(t)
    return cTypeStr(ktc)
}

/** Resolve a TypeRef to its internal string type name (legacy bridge). */
internal fun CCodeGen.resolveTypeNameStr(t: TypeRef?): String {
    if (t == null) return "Int"
    val vSubstituted = substituteTypeParams(t)   // type-param substituted copy
    // RawArray<T> → T* (raw pointer, no $len companion)
    if (vSubstituted.name == "RawArray" && vSubstituted.typeArgs.isNotEmpty())
        return resolveTypeNameStr(vSubstituted.typeArgs[0]) + "*"
    val vBase = resolveTypeNameInnerStr(vSubstituted) // raw resolved name
    val vIsPtr = vSubstituted.annotations.any { it.name == "Ptr" } // has @Ptr annotation
    if (vIsPtr) {
        // @Ptr InterfaceType → ktc_IfacePtr (trampoline value, carries vt+obj)
        // For generic interfaces, include concrete type args in the resolved name
        // so scope type resolution retains the concrete instantiation (e.g. "List_Int*")
        if (interfaces.containsKey(vSubstituted.name)) {
            if (vSubstituted.typeArgs.isNotEmpty()) return "ktc_IfacePtr:${vBase}"
            return "ktc_IfacePtr"
        }
        return "${vBase}*"
    }
    return vBase
}

internal fun CCodeGen.typeRefToStr(t: TypeRef?): String {
    if (t == null) return "Unit"
    val ann = if (t.annotations.any { it.name == "Ptr" }) "@Ptr " else ""
    val args = if (t.typeArgs.isNotEmpty()) "<${t.typeArgs.joinToString(", ") { typeRefToStr(it) }}>" else ""
    val nullable = if (t.nullable) "?" else ""
    return "$ann${t.name}$args$nullable"
}

/** Recursively substitute type parameters throughout a TypeRef tree. */
internal fun CCodeGen.substituteTypeParams(t: TypeRef): TypeRef {
    if (typeSubst.isEmpty()) return t
    val rawNewName = typeSubst[t.name] ?: t.name
    val hasNullableSuffix = rawNewName.endsWith("?")
    val newName = if (hasNullableSuffix) rawNewName.dropLast(1) else rawNewName
    val newTypeArgs = t.typeArgs.map { substituteTypeParams(it) }
    val newFuncParams = t.funcParams?.map { substituteTypeParams(it) }
    val newFuncReturn = t.funcReturn?.let { substituteTypeParams(it) }
    val newFuncReceiver = t.funcReceiver?.let { substituteTypeParams(it) }
    val newNullable = t.nullable || hasNullableSuffix
    return if (newName != t.name || newNullable != t.nullable || newTypeArgs != t.typeArgs || newFuncParams != t.funcParams || newFuncReturn != t.funcReturn || newFuncReceiver != t.funcReceiver) {
        TypeRef(newName, newNullable, newTypeArgs, newFuncParams, newFuncReturn, newFuncReceiver, t.annotations)
    } else t
}

/** Internal string-based type resolution after @Ptr stripping (legacy bridge). */
internal fun CCodeGen.resolveTypeNameInnerStr(t: TypeRef): String {
    // Function type: (P1, P2) -> R → "Fun(P1,P2)->R"
    // Receiver function type: T.(P1) -> R → "Fun(T|P1)->R"
    if (t.funcParams != null) {
        val vReceiver = t.funcReceiver?.let { resolveTypeNameStr(it) + "|" } ?: "" // optional receiver prefix
        val vParams = t.funcParams.joinToString(",") { resolveTypeNameStr(it) }     // comma-joined param types
        val vRet = resolveTypeNameStr(t.funcReturn)                                  // return type string
        return "Fun($vReceiver$vParams)->$vRet"
    }
    // Nested class: Outer.Inner → Outer$Inner
    if (t.name.contains('.') && !t.name.startsWith("ktc_")) {
        val vFlatName = t.name.replace('.', '$') // dot-to-dollar flattened name
        if (classes.containsKey(vFlatName) || genericClassDecls.containsKey(vFlatName) || interfaces.containsKey(
                vFlatName
            )
        ) {
            val vSynthetic = TypeRef(
                vFlatName,
                t.nullable,
                t.typeArgs,
                t.funcParams,
                t.funcReturn,
                t.funcReceiver,
                t.annotations
            ) // synthetic flattened TypeRef
            return resolveTypeNameInnerStr(vSynthetic)
        }
    }
    // User-defined generic class takes priority over built-in aliases
    if (t.typeArgs.isNotEmpty() && classes.containsKey(t.name) && classes[t.name]!!.isGeneric) {
        val vTypeArgNames = t.typeArgs.map { typeRef ->
            if (typeRef.nullable) "${resolveTypeNameStr(typeRef)}?" else resolveTypeNameStr(typeRef)
        }
        // Don't register as concrete instantiation if any type arg is still a type parameter
        if (vTypeArgNames.any { it in allGenericTypeParamNames })
            return mangledGenericName(t.name, vTypeArgNames)
        return recordGenericInstantiation(t.name, vTypeArgNames)
    }
    // User-defined generic interface takes priority over built-in aliases
    if (t.typeArgs.isNotEmpty() && genericIfaceDecls.containsKey(t.name)) {
        val vTypeArgNames = t.typeArgs.map { resolveTypeNameStr(it) } // resolved type argument names
        return mangledGenericName(t.name, vTypeArgNames)
    }
    // Intrinsic AnyPtr → void* (accepts any @Ptr type, used for freeMem etc.)
    if (t.name == "AnyPtr" && t.typeArgs.isEmpty())
        return if (t.annotations.any { it.name == "Ptr" }) "void*" else "void*"
    // Intrinsic StringBuffer → ktc_StrBuf (only when no user-defined class named StringBuffer)
    if (t.name == "StringBuffer" && t.typeArgs.isEmpty()
        && !classes.containsKey("StringBuffer") && !genericClassDecls.containsKey("StringBuffer")
    )
        return "ktc_StrBuf"
    if (t.name == "Array" && t.typeArgs.isNotEmpty()) {
        val vElemRef = t.typeArgs[0]    // element TypeRef
        val vElem = vElemRef.name    // element Kotlin type name
        val vNullableElem = vElemRef.nullable // true for Array<T?>
        return if (vNullableElem) primitiveToArrayOptionalType(vElem)
        else primitiveToArrayType(vElem)
    }
    if (t.name == "RawArray" && t.typeArgs.isNotEmpty())
        return resolveTypeNameStr(t.typeArgs[0]) + "*"
    // Reject unknown type constructors with args (e.g. Heap<T>, Value<T>)
    if (t.typeArgs.isNotEmpty()
        && !classes.containsKey(t.name)
        && !interfaces.containsKey(t.name)
        && !genericClassDecls.containsKey(t.name)
        && !genericIfaceDecls.containsKey(t.name)
        && t.name !in setOf("Array", "RawArray", "AnyPtr")
    )
        codegenError("Unknown type '${t.name}<...>'. Use @Ptr for pointer types.")
    // Resolve nested class within current object/class scope (e.g., Context → Sha256$Context)
    if (!classes.containsKey(t.name) && !enums.containsKey(t.name) && !interfaces.containsKey(t.name)
        && !genericClassDecls.containsKey(t.name)
    ) {
        val vParent = currentObject ?: currentClass // enclosing class or object
        if (vParent != null) {
            val vNestedName = "$vParent$${t.name}" // candidate nested name
            if (classes.containsKey(vNestedName) || genericClassDecls.containsKey(vNestedName))
                return vNestedName
        }
        // Scan all objects for nested class (used when calling obj.method() from outside)
        for (vObj in objects.keys) {
            val vNestedName = "$vObj$${t.name}" // candidate nested name
            if (classes.containsKey(vNestedName) || genericClassDecls.containsKey(vNestedName))
                return vNestedName
        }
    }
    return t.name
}

internal fun CCodeGen.defaultVal(t: KtcType): String = when (t) {
    is KtcType.Prim -> when (t.kind) {
        KtcType.PrimKind.Int, KtcType.PrimKind.Long -> "0"
        KtcType.PrimKind.Float -> "0.0f"
        KtcType.PrimKind.Double -> "0.0"
        KtcType.PrimKind.Boolean -> "false"
        KtcType.PrimKind.Char -> "'\\0'"
        else -> "0"
    }

    is KtcType.Str -> "ktc_core_str(\"\")"
    is KtcType.Ptr -> "NULL"
    else -> {
        val ct = cTypeStr(t.toInternalStr.removeSuffix("?"))
        "($ct){0}"
    }
}

/** True if the internal type name represents an array (IntArray, LongArray, Vec2Array, etc.). Strips pointer/nullable suffixes. */
internal fun isArrayType(t: String): Boolean {
    val base = t.removeSuffix("?").removeSuffix("*")
    return base.endsWith("Array")
}

/** True if the TypeRef is a raw Array<T> or primitive array type (not wrapped in Heap, Ptr, or Value). */
internal fun isRawArrayTypeRef(t: TypeRef): Boolean {
    if (hasSizeAnnotation(t)) return false
    if (t.annotations.any { it.name == "Ptr" }) return false
    if (t.name == "Array") return true
    if (t.name in primitiveArraySet) return true
    return false
}

internal fun hasSizeAnnotation(t: TypeRef): Boolean = t.annotations.any { it.name == "Size" }

internal fun getSizeAnnotation(t: TypeRef): Int? {
    val ann = t.annotations.find { it.name == "Size" }
    if (ann != null && ann.args.isNotEmpty()) {
        val arg = ann.args[0]
        if (arg is IntLit) return arg.value.toInt()
        if (arg is LongLit) return arg.value.toInt()
    }
    return null
}

/** True if the TypeRef is an array type WITH @Size(N) annotation (allowed fixed-size array). */
internal fun isSizedArrayTypeRef(t: TypeRef): Boolean {
    if (!hasSizeAnnotation(t)) return false
    if (t.name == "Array") return true
    if (t.name in primitiveArraySet) return true
    return false
}

/** Check if a call expression returns a sized array (@Size(N) Array<T>). */
internal fun CCodeGen.isSizedArrayReturningCall(e: Expr?): Boolean {
    if (e !is CallExpr) return false
    val name = (e.callee as? NameExpr)?.name ?: return false
    val genFun = genericFunDecls.find { it.name == name }
    if (genFun != null && genFun.returnType != null && isSizedArrayTypeRef(genFun.returnType))
        return true
    val sig = funSigs[name] ?: return false
    return sig.returnType != null && isSizedArrayTypeRef(sig.returnType)
}

/** Get the @Size value from a call to a sized-array-returning function. */
internal fun CCodeGen.getSizedArrayReturnSize(e: CallExpr): Int? {
    val name = (e.callee as? NameExpr)?.name ?: return null
    val genFun = genericFunDecls.find { it.name == name }
    if (genFun != null && genFun.returnType != null) return getSizeAnnotation(genFun.returnType)
    val sig = funSigs[name] ?: return null
    if (sig.returnType != null) return getSizeAnnotation(sig.returnType)
    return null
}

/** Get the element C type from a call to a sized-array-returning function. */
internal fun CCodeGen.getSizedArrayReturnElemType(e: CallExpr): String? {
    val name = (e.callee as? NameExpr)?.name ?: return null
    val genFun = genericFunDecls.find { it.name == name }
    if (genFun != null && genFun.returnType != null) return arrayElementCTypeKtc(resolveTypeName(genFun.returnType))
    val sig = funSigs[name] ?: return null
    if (sig.returnType != null) return arrayElementCTypeKtc(resolveTypeName(sig.returnType))
    return null
}

// ═══════════════════════════ printf helpers ═══════════════════════

internal fun CCodeGen.printfFmt(ktc: KtcType): String = when (ktc) {
    is KtcType.Prim -> when (ktc.kind) {
        KtcType.PrimKind.Byte -> "%\" PRId8 \""
        KtcType.PrimKind.Short -> "%\" PRId16 \""
        KtcType.PrimKind.Int -> "%\" PRId32 \""
        KtcType.PrimKind.Long -> "%\" PRId64 \""
        KtcType.PrimKind.Float -> "%f"
        KtcType.PrimKind.Double -> "%f"
        KtcType.PrimKind.Boolean -> "%s"
        KtcType.PrimKind.Char -> "%c"
        KtcType.PrimKind.Rune -> "%\" PRId32 \""
        KtcType.PrimKind.UByte -> "%\" PRIu8 \""
        KtcType.PrimKind.UShort -> "%\" PRIu16 \""
        KtcType.PrimKind.UInt -> "%\" PRIu32 \""
        KtcType.PrimKind.ULong -> "%\" PRIu64 \""
    }
    is KtcType.Str -> "%.*s"
    is KtcType.Ptr -> "%p"
    is KtcType.Nullable -> if (ktc.inner is KtcType.Ptr) "%p" else printfFmt(ktc.inner)
    is KtcType.User -> when (ktc.kind) {
        KtcType.UserKind.Enum -> "%.*s"
        else -> "%.*s"
    }
    else -> "%.*s"
}

internal fun CCodeGen.printfArg(expr: String, ktc: KtcType): String = when (ktc) {
    is KtcType.Prim if ktc.kind == KtcType.PrimKind.Boolean -> "($expr) ? \"true\" : \"false\""
    is KtcType.Str -> "(ktc_Int)($expr).len, ($expr).ptr"
    is KtcType.User if ktc.kind == KtcType.UserKind.Enum -> {
        val cName = typeFlatName(ktc.baseName)
        "(ktc_Int)${cName}_names[($expr)].len, ${cName}_names[($expr)].ptr"
    }

    else -> expr
}

internal fun escapeC(c: Char): String = when (c) {
    '\'' -> "\\'"
    '\\' -> "\\\\"
    '\n' -> "\\n"
    '\t' -> "\\t"
    '\r' -> "\\r"
    '\u0000' -> "\\0"
    else -> c.toString()
}

internal fun escapeStr(s: String): String = s
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
    .replace("\t", "\\t")
    .replace("\r", "\\r")

// ── Typed bridge (KtcType) ──────────────────────────────────────────

/**
Primary entry point: resolve a TypeRef to a KtcType.
Handles function types directly; bridges through resolveTypeNameStr for all others.
 */
internal fun CCodeGen.resolveTypeName(inT: TypeRef?): KtcType {
    if (inT == null) return KtcType.Prim(KtcType.PrimKind.Int) // default to Int for null TypeRef
    val vSubstituted = substituteTypeParams(inT)                // apply type param substitutions
    // Function types: build KtcType.Func directly to preserve param info and receiver
    if (vSubstituted.funcParams != null) {
        val vReceiver = vSubstituted.funcReceiver?.let { resolveTypeName(it) }          // extension receiver, or null
        val vParams = vSubstituted.funcParams.map { resolveTypeName(it) }               // non-receiver params only
        val vRet = resolveTypeName(vSubstituted.funcReturn)                             // return type
        return KtcType.Func(vParams, vRet, receiver = vReceiver)
    }
    val vResolved = resolveTypeNameStr(inT)                     // string-based resolution (bridge)
    val base = parseResolvedTypeName(vResolved, inT)
    // Preserve nullability from type substitution (e.g., T→Int? produces nullable=true on vSubstituted)
    val isSubstNullable = vSubstituted.nullable && !inT.nullable
    return if (isSubstNullable && base !is KtcType.Ptr && base !is KtcType.Nullable) KtcType.Nullable(base) else base
}

/** Create KtcType.User by looking up the TypeDef from symbol tables, or creating a BuiltinTypeDef. */
internal fun CCodeGen.userType(inName: String, inKind: KtcType.UserKind = KtcType.UserKind.Class): KtcType.User {
    val vTypeDef: TypeDef = when {
        classes.containsKey(inName) -> classes[inName]!!
        objects.containsKey(inName) -> objects[inName]!!
        interfaces.containsKey(inName) -> interfaces[inName]!!
        enums.containsKey(inName) -> enums[inName]!!
        else -> {
            // Builtin or unknown: derive pkg from typeFlatName
            val vFullPfx = typeFlatName(inName)
            val vPkg = if (vFullPfx.endsWith(inName)) vFullPfx.removeSuffix(inName) else ""
            BuiltinTypeDef(baseName = inName, pkg = vPkg, kind = inKind)
        }
    }
    return KtcType.User(vTypeDef)
}

internal fun CCodeGen.parseResolvedTypeName(resolved: String, t: TypeRef? = null): KtcType {
    // @Ptr InterfaceType → preserve interface info for dispatch
    if (resolved.startsWith("ktc_IfacePtr")) {
        val concreteName = if (resolved.startsWith("ktc_IfacePtr:") && resolved.length > "ktc_IfacePtr:".length)
            resolved.substring("ktc_IfacePtr:".length)
        else if (t != null && interfaces.containsKey(t.name) && t.typeArgs.isNotEmpty()) {
            val argNames = t.typeArgs.map { resolveTypeNameInnerStr(it) }
            mangledGenericName(t.name, argNames)
        } else t?.name ?: "Any"
        return KtcType.Ptr(userType(concreteName, KtcType.UserKind.Interface))
    }
    // Pointer suffix — for array types that are already pointers, don't double-wrap
    if (resolved.endsWith("*?")) {
        val base = resolved.dropLast(2)
        if (base.endsWith("Array")) return KtcType.Nullable(
            parseResolvedTypeName(
                base,
                t
            )
        )  // Array*? → nullable array ptr
        return KtcType.Nullable(KtcType.Ptr(parseResolvedTypeName(base, t)))
    }
    if (resolved.endsWith("*")) {
        val base = resolved.dropLast(1)
        if (base.endsWith("Array")) return parseResolvedTypeName(base, t)  // Array* → already a pointer
        if (base.startsWith("ktc_IfacePtr")) return parseResolvedTypeName(base, t)  // ktc_IfacePtr:X* → already Ptr
        return KtcType.Ptr(parseResolvedTypeName(base, t))
    }
    // Nullable suffix
    if (resolved.endsWith("?")) return KtcType.Nullable(parseResolvedTypeName(resolved.dropLast(1)))

    // Function type
    if (resolved.startsWith("Fun(")) return KtcType.Func(emptyList(), KtcType.Void)

    // Primitives
    for (kind in KtcType.PrimKind.entries) {
        if (resolved == kind.name) return KtcType.Prim(kind)
    }

    // String, void
    if (resolved == "String") return KtcType.Str
    if (resolved == "void" || resolved == "Nothing" || resolved == "Unit") return KtcType.Void
    if (resolved == "Any") return KtcType.Any

    // StringBuffer
    if (resolved == "ktc_StrBuf") return userType("ktc_StrBuf")

    // Optional arrays must be checked before plain arrays (IntOptArray ends with "Array" too)
    // Optional arrays: IntOptArray → Ptr(Arr(Nullable(elem)))
    if (resolved.endsWith("OptArray")) {
        val elemName = resolved.removeSuffix("OptArray")
        val elem = parseResolvedTypeName(elemName)
        return KtcType.Ptr(KtcType.Arr(KtcType.Nullable(elem)))
    }

    // Array types: IntArray, ByteArray, Vec2Array, etc.
    if (resolved.endsWith("Array") && resolved.length > 5) {
        val elemName = resolved.removeSuffix("Array")
        val elem = when (elemName) {
            in KtcType.PrimKind.entries.map { it.name } -> KtcType.Prim(KtcType.PrimKind.valueOf(elemName))
            "String" -> KtcType.Str
            else -> userType(elemName)
        }
        val sized = t?.let { getSizeAnnotation(it) }
        val isTypedArray = elemName in KtcType.PrimKind.entries.map { it.name } || elemName == "String"
                || classArrayTypes.contains(elemName) || enums.containsKey(elemName)
        val arr = KtcType.Arr(elem, sized = sized)
        return if (isTypedArray) KtcType.Ptr(arr) else arr
    }

    // User-defined classes, interfaces
    if (classes.containsKey(resolved) || objects.containsKey(resolved) || interfaces.containsKey(resolved)) {
        val kind = when {
            classes[resolved]?.isData == true -> KtcType.UserKind.DataClass
            objects.containsKey(resolved) -> KtcType.UserKind.Object
            enums.containsKey(resolved) -> KtcType.UserKind.Enum
            interfaces.containsKey(resolved) -> KtcType.UserKind.Interface
            else -> KtcType.UserKind.Class
        }
        return userType(resolved, kind)
    }

    // Fallback: unknown type as User
    return userType(resolved)
}

/** C type string from KtcType, uses pfx for user types. */
internal fun CCodeGen.cTypeStr(ktc: KtcType): String = when (ktc) {
    is KtcType.Prim -> ktc.toCType()
    is KtcType.Str -> ktc.toCType()
    is KtcType.Void -> ktc.toCType()
    is KtcType.Any -> ktc.toCType()
    is KtcType.User -> {
        val bn = ktc.baseName // base name (no package prefix)
        when (bn) {
            "Any" -> "ktc_Any"
            "ktc_StrBuf" -> "ktc_StrBuf"
            "AnyPtr" -> "void*"
            else -> ktc.toCType()
        }
    }

    is KtcType.Ptr -> {
        // Ptr(Arr(elem)) → elem*, not trampoline* (handles OptArray via nullable elem)
        if (ktc.inner is KtcType.Arr) {
            val vArrElem = ktc.inner.elem
            val vElemStr = if (vArrElem is KtcType.Nullable)
                optCTypeName(vArrElem.inner.toInternalStr)
            else cTypeStr(vArrElem)
            "$vElemStr*"
        } else if (ktc.inner is KtcType.User && ktc.inner.kind == KtcType.UserKind.Interface) {
            "ktc_IfacePtr"  // @Ptr InterfaceType → untyped trampoline
        } else "${cTypeStr(ktc.inner)}*"
    }

    is KtcType.Arr -> {
        val elem = cTypeStr(ktc.elem)
        when {
            ktc.sized != null -> elem  // sized array: T[]
            else -> "ktc_ArrayTrampoline"
        }
    }

    is KtcType.Nullable -> {
        val inner = ktc.inner
        if (inner is KtcType.Ptr) cTypeStr(inner)  // T* for nullable pointers (NULL = null)
        else optCTypeName(inner.toInternalStr)  // T$Optional for nullable value types
    }

    is KtcType.Func -> "void*"
}
