package com.bitsycore.ktc.codegen.expression

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.codegen.*
import com.bitsycore.ktc.codegen.emit.collectAllIfaceProperties
import com.bitsycore.ktc.codegen.emit.registerClassFields
import com.bitsycore.ktc.types.KtcType

/* Returns the C field name for a dot access, prefixing private fields with PRIV_ when accessed via this. */
private fun CCodeGen.thisFieldName(name: String, obj: Expr): String =
	if (currentClass != null && obj is ThisExpr) {
		val ci = classes[currentClass]!!
		if (name in ci.privateProps) "PRIV_$name" else name
		} else name

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
    val recvTypeCoreKtc = recvTypeKtc.stripNullable   // KtcType? stripped of Nullable wrapper
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
    // Resolve nested object property chain (e.g. SDL3.Event.Quit → getter value)
    if (e.obj is DotExpr) {
        var vCur2: DotExpr = e.obj as DotExpr
        val vParts2 = mutableListOf(vCur2.name)
        while (vCur2.obj is DotExpr) { vCur2 = vCur2.obj as DotExpr; vParts2.add(vCur2.name) }
        if (vCur2.obj is NameExpr) {
            vParts2.add((vCur2.obj as NameExpr).name)
            vParts2.reverse()
            val vFullObj = vParts2.joinToString("$")
            val vOi = objects[vFullObj]
            if (vOi != null) {
                val vProp2 = vOi.properties.find { it.name == e.name && it.getter != null }
                if (vProp2 != null) return genExpr(vProp2.getter!!)
                }
            // Also check extension properties on the resolved object
            val vExtProps = extensionProps[vFullObj]
            if (vExtProps != null) {
                val vExtP = vExtProps.find { it.name == e.name && it.getter != null }
                if (vExtP != null) return genExpr(vExtP.getter!!)
                }
            }
        }

    // Object / Companion field: ensure lazy init, then return flatName.field
    val vDotObjCName = resolveDotObjCName(e)
    if (vDotObjCName != null) {
        // Nested object reference (e.g. SDL3.Event → sdl3_SDL3$Event): e.name IS the object, not a field.
        val vParentName = (e.obj as? NameExpr)?.name
        if (vParentName != null && objects.containsKey("$vParentName\$${e.name}")) {
            return vDotObjCName
        }
        // Visibility check: can't access private props from outside the object
        val objInfo = resolveDotObjInfo(e)
        if (objInfo != null && objInfo.name != currentObject && objInfo.privateProps.contains(e.name)) {
            val displayName = objInfo.name.replace('$', '.')
            codegenError("Cannot access '${e.name}': it is private in object '$displayName'")
        }
        // Skip ensure_init for objects with only computed getters (no mutable state)
        val vObjInfo2 = resolveDotObjInfo(e)
        val vNeedsInit = vObjInfo2?.properties?.any { it.getter == null } ?: true
        if (vNeedsInit) preStmts += "${vDotObjCName}_\$ensure_init();"
        val fieldName = if (objInfo != null && objInfo.privateProps.contains(e.name)) "PRIV_${e.name}" else e.name
        return "${vDotObjCName}.${fieldName}"
    }
    // Array .size → sized-struct param uses local$name$len; trampoline uses .size field; others use $len
    if (e.name == "size" && e.obj is NameExpr && e.obj.name in trampolinedParams) return arrayParamSizeExpr(e.obj.name)
    if (e.name == "size" && recvTypeCoreKtc != null && recvTypeCoreKtc.isArrayLike) return "${recv}.len"
    if (e.name == "ptr" && recvTypeCoreKtc is KtcType.Str) return "$recv.ptr"
    if (e.name == "ptr" && recvTypeCoreKtc != null && recvTypeCoreKtc.isArrayLike) return "$recv.ptr"
    if (e.name == "length" && recvTypeKtc is KtcType.Str) return "$recv.len"
    if (e.name == "runeLen" && recvTypeKtc is KtcType.Str) return "ktc_core_str_runeLen($recv)"
    // Enum .ordinal → the int value itself
    val vOrdinalEnumInfo = enumInfoFor(recvTypeCoreKtc)                               // non-null if receiver is an enum (for ordinal/name)
    if (e.name == "ordinal" && vOrdinalEnumInfo != null) return recv
    // Enum .name → lookup in names array
    if (e.name == "name" && vOrdinalEnumInfo != null) return "${vOrdinalEnumInfo.flatName}_names[($recv)]"

    // String.lastIndex → s.len - 1 (Kotlin stdlib extension property).
    if (e.name == "lastIndex" && recvTypeKtc is KtcType.Str) return "($recv.len - 1)"

    // p.refValue → dereference pointer (*p), optionally guarded by --check-null
    if (e.name == "refValue" && recvTypeCoreKtc is KtcType.Ptr) {
        val inner = (recvTypeCoreKtc as KtcType.Ptr).inner
        if (inner is KtcType.User && objects.containsKey(inner.baseName))
            codegenError("Cannot access .refValue on object '${inner.baseName}' — objects are always Ref")
        return wrapNullCheck(recv, "(*$recv)")
    }

    // p->field (auto-deref through pointer)
    if (recvTypeCoreKtc is KtcType.Ptr) {
        return "$recv->${thisFieldName(e.name, e.obj)}"
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

    val fieldName = thisFieldName(e.name, e.obj)

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

    // Computed property with custom getter: inline the getter expression
    val vDotClassInfo = classInfoFor(recvTypeCoreKtc)
    if (vDotClassInfo != null) {
        val vGetterProp = vDotClassInfo.properties.find { it.name == e.name && it.getter != null }
        if (vGetterProp != null) {
            // Emit getter in class context with $self bound to receiver
            val vPrevClass = currentClass
            val vPrevSelfPtr = selfIsPointer
            currentClass = vDotClassInfo.name
            selfIsPointer = recvTypeCoreKtc is KtcType.Ptr
            pushScope()
            registerClassFields(vDotClassInfo, if (selfIsPointer) "$recv->" else "$recv.")
            val vResult = genExpr(vGetterProp.getter!!)
            popScope()
            currentClass = vPrevClass
            selfIsPointer = vPrevSelfPtr
            return vResult
            }
        }

    return "$recv.${fieldName}"
}

internal fun CCodeGen.genSafeDot(e: SafeDotExpr): String {
    val recvType = inferExprType(e.obj)
    val recvTypeKtc = inferExprTypeKtc(e.obj)
    val recvTypeCoreKtc = recvTypeKtc.stripNullable
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
        e.name == "refValue" && recvTypeCoreKtc is KtcType.Ptr -> "(*$recvVal)"
        recvTypeCoreKtc is KtcType.Ptr -> "$recvVal->${e.name}"
        e.name == "size" && recvTypeCoreKtc != null && recvTypeCoreKtc.isArrayLike -> "${recvVal}.len"
        e.name == "ptr" && recvTypeCoreKtc != null && recvTypeCoreKtc.isArrayLike -> "${recvVal}.ptr"
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
        defineVar(t, fieldType)
        markOptional(t)
    } else {
        val optType = if (fieldType != null) optCTypeName("${fieldType}?") else "ktc_Int\$Opt"
        preStmts += "$optType $t = $guard ? ${optSome(optType, fieldAccess)} : ${optNone(optType)};"
        defineVar(t, "${fieldType ?: "Int"}?")
        markOptional(t)
    }
    return t
}

// ── !! (not-null assertion) ─────────────────────────────────────────

internal fun CCodeGen.genNotNull(e: NotNullExpr): String {
    val inner = genExpr(e.expr)
    val innerType = inferExprType(e.expr)
    val innerKtc = inferExprTypeKtc(e.expr)
    val innerKtcCore = innerKtc.stripNullable
    val loc = "$sourceFileName:$currentStmtLine"

    // Pointer-nullable: type ends with "*", "^", or "&"
    val baseType = innerType?.removeSuffix("?") ?: ""
    val isPtr = innerKtcCore is KtcType.Ptr

    if (isPtr) {
        // VarArr nullable (Ref<Array<T>?>): use .ptr field for null check
        val isVarArr = innerKtcCore is KtcType.Ptr && innerKtcCore.inner.let {
            it is KtcType.Arr && it.sized == null
            }
        if (isVarArr) {
            val vElemC    = arrayElementCTypeKtc(innerKtcCore)
            val vVarArrCt = varArrTypeName(vElemC)
            if (e.expr is NameExpr) {
                preStmts += "if (!$inner.ptr) { fprintf(stderr, \"NullPointerException: $loc\\n\"); exit(1); }"
                return inner
                }
            val t = tmp()
            preStmts += "$vVarArrCt $t = $inner;"
            preStmts += "if (!$t.ptr) { fprintf(stderr, \"NullPointerException: $loc\\n\"); exit(1); }"
            return t
            }
        val ct = cTypeStr(baseType.ifEmpty { "void*" })
        // Simple name — no temp needed
        if (e.expr is NameExpr) {
            preStmts += "if (!$inner) { fprintf(stderr, \"NullPointerException: $loc\\n\"); exit(1); }"
            return inner
        }
        val t = tmp()
        preStmts += "$ct $t = $inner;"
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