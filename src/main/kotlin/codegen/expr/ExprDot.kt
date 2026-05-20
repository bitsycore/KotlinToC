package com.bitsycore.ktc.codegen.expr

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.codegen.*
import com.bitsycore.ktc.codegen.emit.collectAllIfaceProperties
import com.bitsycore.ktc.types.KtcType

internal fun CCodeGen.genDot(e: DotExpr): String {
    // C package passthrough: c.EXIT_SUCCESS → EXIT_SUCCESS, c.NULL → NULL
    if (e.obj is NameExpr && e.obj.name == "c" && lookupVar("c") == null) {
        return e.name
    }
    // Macro compile-time callsite info: expands to the Kotlin source file/line at the call site
    if (e.obj is NameExpr && e.obj.name == "Macro") {
        return when (e.name) {
            "FILE"   -> "ktc_core_str(\"$currentSourceFile\")"
            "LINE"   -> "$currentStmtLine"
            "C_FILE" -> "ktc_core_str(__FILE__)"
            "C_LINE" -> "__LINE__"
            else     -> codegenError("Macro has no member '${e.name}'")
        }
    }

    val recvType = inferExprType(e.obj)                                               // String? receiver type (string-based)
    val recvTypeKtc = inferExprTypeKtc(e.obj)                                         // KtcType? receiver type
    val recvTypeCoreKtc = (recvTypeKtc as? KtcType.Nullable)?.inner ?: recvTypeKtc   // KtcType? stripped of Nullable wrapper
    val recv = genExpr(e.obj)

    // Reject non-safe access on nullable receiver (enum/object/companion are never nullable)
    // Allow array types (plain or indirect) where size/index access is safe
    val isEnumOrObj = e.obj is NameExpr && (enums.containsKey(e.obj.name) || objects.containsKey(e.obj.name) || classCompanions.containsKey(e.obj.name))
    if (recvTypeKtc is KtcType.Nullable && !isEnumOrObj) {
        val innerKtc = recvTypeKtc.inner
        val isIndirectArray = innerKtc is KtcType.Ptr && innerKtc.inner is KtcType.Arr
        if (!isIndirectArray && innerKtc !is KtcType.Arr) {
            val recvSrc = (e.obj as? NameExpr)?.name ?: e.obj.toString()
            codegenError("Only safe (?.) access is allowed on a nullable receiver of type '$recvType': $recvSrc.${e.name}")
        }
    }

    // Enum entry: Color.RED → game_Color_RED
    if (e.obj is NameExpr && enums.containsKey(e.obj.name)) {
        val vDotEnumInfo = enums[e.obj.name]!!                                        // EnumInfo for C name
        return "${vDotEnumInfo.flatName}_${e.name}"
    }
    // Object / Companion field: ensure lazy init, then return flatName.field
    val vDotObjCName = resolveDotObjCName(e)
    if (vDotObjCName != null) {
        // Visibility check: can't access private props from outside the object
        val objInfo = resolveDotObjInfo(e)
        if (objInfo != null && objInfo.name != currentObject && objInfo.privateProps.contains(e.name)) {
            val displayName = objInfo.name.replace('$', '.')
            codegenError("Cannot access '${e.name}': it is private in object '$displayName'")
        }
        preStmts += "${vDotObjCName}_\$ensure_init();"
        val fieldName = if (objInfo != null && objInfo.privateProps.contains(e.name)) "PRIV_${e.name}" else e.name
        return "${vDotObjCName}.${fieldName}"
    }
    // Array .size → sized-struct param uses local$name$len; trampoline uses .size field; others use $len
    if (e.name == "size" && e.obj is NameExpr && e.obj.name in trampolinedParams) return arrayParamSizeExpr(e.obj.name)
    if (e.name == "size" && recvTypeCoreKtc != null && recvTypeCoreKtc.isArrayLike) return "${recv}\$len"
    if (e.name == "ptr" && recvTypeCoreKtc is KtcType.Str) return "$recv.ptr"
    if (e.name == "length" && recvTypeKtc is KtcType.Str) return "$recv.len"
    if (e.name == "runeLen" && recvTypeKtc is KtcType.Str) return "ktc_core_str_runeLen($recv)"
    // Enum .ordinal → the int value itself
    val vOrdinalEnumInfo = enumInfoFor(recvTypeCoreKtc)                               // non-null if receiver is an enum (for ordinal/name)
    if (e.name == "ordinal" && vOrdinalEnumInfo != null) return recv
    // Enum .name → lookup in names array
    if (e.name == "name" && vOrdinalEnumInfo != null) return "${vOrdinalEnumInfo.flatName}_names[($recv)]"

    // p->field (auto-deref through pointer)
    if (recvTypeCoreKtc is KtcType.Ptr) {
        val fieldName = if (currentClass != null && e.obj is ThisExpr) {
            val ci = classes[currentClass]!!
            if (e.name in ci.privateProps) "PRIV_${e.name}" else e.name
        } else e.name
        return "$recv->${fieldName}"
    }

    // Interface property access via vtable: list.size → list.vt->size(data_ptr)
    val vIfaceDotInfo = ifaceInfoFor(recvTypeCoreKtc)                                 // non-null if receiver is a known interface
    if (vIfaceDotInfo != null) {
        val allProps = collectAllIfaceProperties(vIfaceDotInfo)
        if (allProps.any { it.name == e.name }) {
            return "$recv.vt->${e.name}(${ifaceVtableSelf(vIfaceDotInfo.name, recv)})"
        }
    }

    // StringBuffer field access: sb.buffer → sb.ptr (the raw char pointer)
    if (recvType == "ktc_StrBuf" || recvType == "StringBuffer") {
        if (e.name == "buffer") return "$recv.ptr"
        if (e.name == "len") return "$recv.len"
    }

    val fieldName = if (currentClass != null && e.obj is ThisExpr) {
        val ci = classes[currentClass]!!
        if (e.name in ci.privateProps) "PRIV_${e.name}" else e.name
    } else e.name

    // Smart-cast from interface to class: redirect field access through union data
    if (e.obj is NameExpr) {
        val vOrigIface = isIfaceSmartCastVar(e.obj.name)
        if (vOrigIface != null) {
            val vNarrowedType = lookupVar(e.obj.name)!!
            return "${ifaceUnionAccess(vOrigIface, vNarrowedType, e.obj.name)}.$fieldName"
        }
    }
    // $self narrowed from interface in extension (this.field)
    if (e.obj is ThisExpr && currentExtRecvType != null && interfaces.containsKey(currentExtRecvType)) {
        val vNarrowedSelf = lookupVar("\$self")
        if (vNarrowedSelf != null && classes.containsKey(vNarrowedSelf)) {
            return "${ifaceUnionAccess(currentExtRecvType!!, vNarrowedSelf, "\$self")}.$fieldName"
        }
    }

    return "$recv.${fieldName}"
}

internal fun CCodeGen.genSafeDot(e: SafeDotExpr): String {
    val recvType = inferExprType(e.obj)
    val recvTypeKtc = inferExprTypeKtc(e.obj)
    val recvTypeCoreKtc = (recvTypeKtc as? KtcType.Nullable)?.inner ?: recvTypeKtc
    // Warn: ?. on a receiver that is already non-nullable (and not a pointer)
    if (recvTypeKtc != null && recvTypeKtc !is KtcType.Nullable && recvTypeCoreKtc !is KtcType.Ptr) {
        val vSrc = (e.obj as? NameExpr)?.name ?: "expression"
        codegenWarning("Safe call '?.' on non-nullable '$recvType' ($vSrc) is redundant; use '.' instead")
    }
    val recv = genExpr(e.obj)
    val recvName = (e.obj as? NameExpr)?.name
    val isThis = e.obj is ThisExpr
    val isValueNullRecv = recvTypeKtc is KtcType.Nullable && isValueNullableKtc(recvTypeKtc)

    // Determine the null guard expression
    val guard = if (isThis || recvName != null) nullGuardExpr(recvTypeKtc ?: KtcType.Prim(KtcType.PrimKind.Int), recv, recvName ?: recv, isThis) else "${recv}\$has"

    // Unwrapped receiver expression for field access (unwrap Optional if needed)
    val recvVal = if (isValueNullRecv) "$recv.value" else recv

    // Determine field access expression (same logic as genDot but without nullable check)
    val fieldAccess = when {
        recvTypeCoreKtc is KtcType.Ptr -> "$recvVal->${e.name}"
        e.name == "size" && recvTypeCoreKtc != null && recvTypeCoreKtc.isArrayLike -> "${recvVal}\$len"
        e.name == "length" && recvTypeCoreKtc is KtcType.Str -> "$recvVal.len"
        else -> "$recvVal.${e.name}"
    }

    // Infer field type for proper default and C type
    val fieldKtc = inferDotTypeKtc(DotExpr(e.obj, e.name))
    val fieldType = fieldKtc?.toInternalStr

    // Emit temp as Optional for value-nullable field results
    val t = tmp()
    val isFieldValueNull = fieldKtc is KtcType.Nullable && isValueNullableKtc(fieldKtc)
    if (isFieldValueNull) {
        val optType = optCTypeName(fieldType!!)
        preStmts += "$optType $t = $guard ? $fieldAccess : ${optNone(optType)};"
        markOptional(t)
        defineVar(t, fieldType)
    } else {
        val optType = if (fieldType != null) optCTypeName("${fieldType}?") else "ktc_Int\$Opt"
        preStmts += "$optType $t = $guard ? ${optSome(optType, fieldAccess)} : ${optNone(optType)};"
        markOptional(t)
        defineVar(t, "${fieldType ?: "Int"}?")
    }
    return t
}

// ── !! (not-null assertion) ─────────────────────────────────────────

internal fun CCodeGen.genNotNull(e: NotNullExpr): String {
    val inner = genExpr(e.expr)
    val innerType = inferExprType(e.expr)
    val innerKtc = inferExprTypeKtc(e.expr)
    val innerKtcCore = (innerKtc as? KtcType.Nullable)?.inner ?: innerKtc
    val loc = "$sourceFileName:$currentStmtLine"

    // Pointer-nullable: type ends with "*", "^", or "&"
    val baseType = innerType?.removeSuffix("?") ?: ""
    val isPtr = innerKtcCore is KtcType.Ptr || isAllocCall(e.expr)

    if (isPtr) {
        val ct = cTypeStr(baseType.ifEmpty { "void*" })
        // Simple name — no temp needed
        if (e.expr is NameExpr) {
            preStmts += "if (!$inner) { fprintf(stderr, \"NullPointerException: $loc\\n\"); exit(1); }"
            return inner
        }
        val t = tmp()
        preStmts += "$ct $t = $inner;"
        if (isArrayType(baseType) || isAllocArrayCall(e.expr)) {
            preStmts += "const ktc_Int ${t}\$len = ${inner}\$len;"
        }
        preStmts += "if (!$t) { fprintf(stderr, \"NullPointerException: $loc\\n\"); exit(1); }"
        return t
    }

    // Value-nullable variable: check Optional tag
    if (innerKtc is KtcType.Nullable && isValueNullableKtc(innerKtc) && e.expr is NameExpr) {
        val name = e.expr.name
        preStmts += "if ($name.tag == ktc_NONE) { fprintf(stderr, \"NullPointerException: $loc\\n\"); exit(1); }"
        // Return the unwrapped value
        return "$name.value"
    }

    // Check: !! on a type that inference knows is non-nullable — always a bug
    // Exclude smart-cast variables: they are stored as Optional but narrowed in scope (isOptional guard).
    val isSmartCastNarrowed = e.expr is NameExpr && isOptional(e.expr.name)
    if (innerKtc != null && innerKtc !is KtcType.Nullable && !isSmartCastNarrowed) {
        codegenError("Non-null assertion '!!' has no effect on non-nullable type '$innerType'")
    }

    // Fallback: no check (non-nullable expression)
    return inner
}

/** Find a common interface implemented by both types, or null. */