package com.bitsycore.ktc.codegen

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.types.KtcType

/**
 * ── Expression Codegen ──────────────────────────────────────────────────
 *
 * Translates Kotlin expressions into C expressions. This is the largest
 * and most complex section of the transpiler. The central function is
 * [genExpr] which dispatches on the expression AST node type.
 *
 * ## Main entry points:
 *
 *   [genExpr]         — central expression dispatcher (see below for full list)
 *   [genName]         — resolve variable/field/this names with smart-cast unwrap
 *   [genBin]           — binary ops with special handling for String, Pair, null, in/!in
 *   [genCall]          — function/constructor call, built-ins (HeapAlloc, arrayOf...)
 *   [genMethodCall]    — method dispatch: built-in, class method, interface vtable, extension
 *   [genDot]           — field access, enum values, object properties, pointer deref
 *   [genSafeDot]       — nullable-safe field access (?.)
 *   [genNotNull]       — not-null assertion (!!)
 *   [genIfExpr]        — if-expression → C ternary or pre-stmt temp
 *   [genWhenExpr]      — when-expression → nested ternary or pre-stmt temp
 *   [genStrTemplate]   — string template → StrBuf-based concatenation
 *   [genToString]      — toString() dispatch (primitive, data class, default hash)
 *   [genSbAppend]      — StrBuf append for all types
 *   [genArrayOfExpr]   — array literal (arrayOf/byteArrayOf/etc.)
 *   [genNewArray]      — Array<T>(size) constructor → alloca
 *   [genLValue]         — l-value for assignment target
 *
 * ## genExpr dispatch table:
 *   IntLit, LongLit, DoubleLit, FloatLit, BoolLit, CharLit, StrLit, NullLit,
 *   ThisExpr, NameExpr, BinExpr, PrefixExpr, PostfixExpr, CallExpr, DotExpr,
 *   SafeDotExpr, IndexExpr, IfExpr, WhenExpr, NotNullExpr, ElvisExpr,
 *   StrTemplateExpr, IsCheckExpr, CastExpr, FunRefExpr, LambdaExpr
 *
 * ## State accessed:
 *   classes, interfaces, enums, objects, funSigs, extensionFuns, classInterfaces,
 *   scopes (lookupVar), lambdaParamSubst, currentClass, currentObject, selfIsPointer,
 *   currentStmtLine, currentInd, typeSubst, inlineFunDecls, inlineExtFunDecls,
 *   activeLambdas, pairTypes, pairTypeComponents, tripleTypeComponents,
 *   classArrayTypes, classCompanions, preStmts, hdr, impl
 *
 * ## Dependencies:
 *   Calls into CCodeGenStmts.kt (emitStmt, emitInlineCall, emitBlock)
 *   Calls into CCodeGenInfer.kt (inferExprType, inferMethodReturnType, ...)
 *   Calls into CCodeGenCTypes.kt (cType, cTypeStr, resolveTypeName, optNone, optSome, ...)
 */

// ═══════════════════════════ Expression codegen ═══════════════════

/** Generate an expression for use as a C function argument.
 *  String literals are emitted as raw C strings (not ktc_core_str wrapped). */
internal fun CCodeGen.genCArg(e: Expr): String = when (e) {
    is StrLit -> "\"${escapeStr(e.value)}\""
    else -> genExpr(e)
}

fun CCodeGen.genExpr(e: Expr): String = when (e) {
    is IntLit -> if (e.hex) "0x${e.value.toString(16)}" else "${e.value}"
    is LongLit -> if (e.hex) "0x${e.value.toString(16)}LL" else "${e.value}LL"
    is UIntLit -> if (e.hex) "0x${e.value.toString(16)}U" else "${e.value}U"
    is ULongLit -> if (e.hex) "0x${e.value.toString(16)}ULL" else "${e.value}ULL"
    is DoubleLit -> "${e.value}"
    is FloatLit -> "${e.value}f"
    is BoolLit -> if (e.value) "true" else "false"
    is CharLit -> "'${escapeC(e.value)}'"
    is StrLit -> "ktc_core_str(\"${escapeStr(e.value)}\")"
    is NullLit -> "NULL"
    is ThisExpr -> {
        val inlineThis = lambdaParamSubst["\$this"]
        if (inlineThis != null) return inlineThis
        val selfKtc = lookupVarKtc("\$self")
        if (selfKtc != null && isOptional("\$self") && selfKtc !is KtcType.Nullable) {
            "KTC_UNWRAP(\$self)"
        } else if (selfIsPointer) "(*\$self)" else "\$self"
    }

    is NameExpr -> genName(e)
    is BinExpr -> genBin(e)
    is PrefixExpr -> "(${e.op}${genExpr(e.expr)})"
    is PostfixExpr -> "(${genExpr(e.expr)}${e.op})"
    is CallExpr -> genCall(e)
    is DotExpr -> genDot(e)
    is SafeDotExpr -> genSafeDot(e)
    is IndexExpr -> {
        val objType = inferExprType(e.obj)                                            // String? object type
        val objTypeKtc = inferExprTypeKtc(e.obj)                                     // KtcType? object type
        val objTypeCoreKtc = (objTypeKtc as? KtcType.Nullable)?.inner ?: objTypeKtc  // KtcType? stripped Nullable
        val vIdxClassInfo = classInfoFor(objTypeCoreKtc)                              // non-null if object is a class
        val vIdxIfaceInfo = ifaceInfoFor(objTypeCoreKtc)                              // non-null if object is an interface
        if (objType == "String") {
            // String indexing: str[i] → str.ptr[i] (returns char)
            "${genExpr(e.obj)}.ptr[${genExpr(e.index)}]"
        } else if (vIdxClassInfo != null) {
            // Class with operator get() method → operator[] dispatch
            val methodDecl = vIdxClassInfo.methods.find { it.name == "get" && it.isOperator }
            if (methodDecl != null) {
                val recv = genExpr(e.obj)
                val idx = genExpr(e.index)
                if (methodDecl.returnType?.nullable == true) {
                    genNullableMethodCall(vIdxClassInfo.baseName, "${vIdxClassInfo.flatName}_get", "&$recv, $idx", methodDecl)
                } else {
                    "${vIdxClassInfo.flatName}_get(&$recv, $idx)"
                }
            } else {
                "${genExpr(e.obj)}.ptr[${genExpr(e.index)}]"
            }
        } else if (objTypeCoreKtc is KtcType.Ptr) {
            // Ptr<T>/Value<T> with operator get() → pointer-based dispatch
            val baseClass = objTypeCoreKtc.inner
            val baseName = (baseClass as? KtcType.User)?.baseName ?: baseClass.toInternalStr
            val methodDecl = classes[baseName]?.methods?.find { it.name == "get" && it.isOperator }
            if (methodDecl != null) {
                val recv = genExpr(e.obj)
                val idx = genExpr(e.index)
                if (methodDecl.returnType?.nullable == true) {
                    genNullableMethodCall(baseName, "${typeFlatName(baseName)}_get", "$recv, $idx", methodDecl)
                } else {
                    "${typeFlatName(baseName)}_get($recv, $idx)"
                }
            } else {
                "${genExpr(e.obj)}[${genExpr(e.index)}]"
            }
        } else if (vIdxIfaceInfo != null) {
            // Interface with operator get() in vtable → operator[] dispatch
            val ifaceMethod = vIdxIfaceInfo.methods.find { it.name == "get" && it.isOperator }
                ?: collectAllIfaceMethods(vIdxIfaceInfo).find { it.name == "get" && it.isOperator }
            if (ifaceMethod != null) {
                val recv = genExpr(e.obj)
                val idx = genExpr(e.index)
                val vIdxSelfArg = ifaceVtableSelf(vIdxIfaceInfo.name, recv)
                if (ifaceMethod.returnType?.nullable == true) {
                    val retBase = resolveMethodReturnType(vIdxIfaceInfo.baseName, ifaceMethod.returnType).removeSuffix("?")
                    val optType = optCTypeName("${retBase}?")
                    val t = tmp()
                    preStmts += "$optType $t = $recv.vt->get($vIdxSelfArg, $idx);"
                    markOptional(t)
                    defineVar(t, "${retBase}?")
                    t
                } else {
                    "$recv.vt->get($vIdxSelfArg, $idx)"
                }
            } else {
                "${genExpr(e.obj)}.ptr[${genExpr(e.index)}]"
            }
        } else if (objTypeCoreKtc != null && objTypeCoreKtc.isArrayLike) {
            // Typed pointer or array: direct indexing
            "${genExpr(e.obj)}[${genExpr(e.index)}]"
        } else {
            "${genExpr(e.obj)}.ptr[${genExpr(e.index)}]"
        }
    }

    is IfExpr -> genIfExpr(e)
    is WhenExpr -> genWhenExpr(e)
    is NotNullExpr -> genNotNull(e)
    is ElvisExpr -> {
        val lt = inferExprType(e.left)
        val l = genExpr(e.left)
        val rt = inferExprType(e.right)
        val rtKtc = inferExprTypeKtc(e.right)
        // If right side returns Nothing or Unit/void (e.g., error("msg")), emit non-null assertion
        if (rt != null && (rt == "Nothing" || rt == "Unit" || rt.removeSuffix("?") == "Nothing")) {
            val baseType = lt?.removeSuffix("?") ?: "void*"
            val ct = cTypeStr(baseType)
            val t = tmp()
            preStmts += "$ct $t = $l;"
            val r = genExpr(e.right)
            preStmts += "if (!$t) { $r; }"
            return t
        }
        val r = genExpr(e.right)
        val ltKtc = inferExprTypeKtc(e.left)
        if (ltKtc != null && isValueNullableKtc(ltKtc)) {
            "(KTC_IS_SOME($l) ? KTC_UNWRAP($l) : $r)"
        } else if (ltKtc is KtcType.Nullable && ltKtc.inner is KtcType.Ptr) {
            "($l != NULL ? $l : $r)"
        } else {
            "($l != NULL ? $l : $r)"
        }
    }

    is StrTemplateExpr -> genStrTemplate(e)
    is IsCheckExpr -> {
        val targetKtc = resolveTypeName(e.type)                                   // KtcType for is-check target
        val target = targetKtc.toInternalStr                                             // String for fallback/array checks
        val inner = genExpr(e.expr)
        val exprKtc = inferExprTypeKtc(e.expr)
        val exprKtcCore = (exprKtc as? KtcType.Nullable)?.inner ?: exprKtc
        // ktc_IfacePtr is a value struct even though the KTC type is Ptr<Interface>
        val isIfacePtr = exprKtcCore is KtcType.Ptr && exprKtcCore.inner is KtcType.User && exprKtcCore.inner.kind == KtcType.UserKind.Interface
        val memOp = if (exprKtcCore is KtcType.Ptr && !isIfacePtr) "->" else "."
        val vIsClassInfo = classInfoFor(targetKtc)                                    // non-null if target is a user class
        val vIsIfaceInfo = ifaceInfoFor(targetKtc)                                    // non-null if target is an interface
        val check = if (vIsClassInfo != null) {
            "KTC_GET_TYPEID(${inner}${memOp}__base.typeId) == ${vIsClassInfo.flatName}_TYPE_ID"
        } else if (vIsIfaceInfo != null) {
            val impls = classInterfaces.filter { (_, ifaces) -> target in ifaces }.keys
            if (impls.isEmpty()) "false"
            else impls.joinToString(" || ") { "KTC_GET_TYPEID(${inner}${memOp}__base.typeId) == ${typeFlatName(it)}_TYPE_ID" }
        } else if (targetKtc.isArrayLike) {
            if (exprKtcCore != null && exprKtcCore.isArrayLike) {
                if (exprKtcCore.toInternalStr == target) "true" else "false"
            } else {
                val arrayId = getTypeId(target)
                "(${inner}${memOp}__array_type_id == $arrayId)"
            }
        } else if (targetKtc !is KtcType.User || targetKtc.kind != KtcType.UserKind.Class) {
            val isSourceNullable = exprKtc is KtcType.Nullable
            val isSourceAny = exprKtcCore is KtcType.Any
            if (exprKtcCore != null && !isSourceAny && exprKtcCore !is KtcType.Ptr) {
                if (exprKtcCore.toInternalStr == target) {
                    // Nullable source: check non-null (tag check for value Optional, != NULL for pointers)
                    if (isSourceNullable && isValueNullableKtc(exprKtc)) "(${inner}.tag == ktc_SOME)"
                    else if (isSourceNullable) "(${inner} != NULL)"
                    else "true"
                } else "false"
            } else {
                val typeId = getTypeId(target)
                    "(KTC_GET_TYPEID(${inner}${memOp}__base.typeId) == $typeId)"
            }
        } else {
            "/* is-check: unknown type '${target}' */ true"
        }
        if (e.negated) "!($check)" else "($check)"
    }

    is CastExpr -> {
        val targetKtc = resolveTypeName(e.type)                                   // KtcType for cast target
        val target = targetKtc.toInternalStr                                             // String for fallback/cTypeStr calls
        val inner = genExpr(e.expr)
        val srcType = inferExprType(e.expr)?.removeSuffix("?")
        val srcKtc = inferExprTypeKtc(e.expr)
        val srcKtcCore = (srcKtc as? KtcType.Nullable)?.inner ?: srcKtc
        val isPtr = srcKtcCore is KtcType.Ptr
        val vCastClassInfo = classInfoFor(targetKtc)                                  // non-null if target is a user class
        val vCastIfaceInfo = ifaceInfoFor(targetKtc)                                  // non-null if target is an interface
        if (e.safe) {
            val optCType = optCTypeName("$target?")
            val memOp = if (isPtr) "->" else "."
            val check = if (vCastClassInfo != null) {
                "KTC_GET_TYPEID(${inner}${memOp}__base.typeId) == ${vCastClassInfo.flatName}_TYPE_ID"
            } else if (vCastIfaceInfo != null) {
                val impls = classInterfaces.filter { (_, ifaces) -> target in ifaces }.keys
                if (impls.isEmpty()) "false"
                else impls.joinToString(" || ") { "KTC_GET_TYPEID(${inner}${memOp}__base.typeId) == ${typeFlatName(it)}_TYPE_ID" }
            } else if (targetKtc !is KtcType.User || targetKtc.kind != KtcType.UserKind.Class) {
                val typeId = getTypeId(target)
                "KTC_GET_TYPEID(${inner}${memOp}__base.typeId) == $typeId"
            } else {
                "true"
            }
            val castVal = if (vCastIfaceInfo != null) {
                val srcFlatName2 = if (srcType != null && (classes.containsKey(srcType) || interfaces.containsKey(srcType))) typeFlatName(srcType) else vCastIfaceInfo.flatName
                val addrExpr2 = if ('(' in inner) { val vT = tmp(); preStmts += "${cTypeStr(srcType ?: "")} $vT = ($inner);"; "&$vT" } else "&($inner)"
                "${srcFlatName2}_as_${vCastIfaceInfo.baseName}($addrExpr2)"
            } else if (srcType != null && interfaces.containsKey(srcType) && classes.containsKey(target)) {
                // Source is an interface, target is a concrete class — extract from tagged union
                ifaceUnionAccess(srcType, target, inner)
            } else if (srcKtc is KtcType.Any || (srcKtcCore is KtcType.Ptr && srcKtcCore.inner is KtcType.Any)) {
                "(*(${cTypeStr(target)}*)(${inner}${memOp}data))"
            } else {
                "(${cTypeStr(target)})($inner)"
            }
            "($check) ? ${optSome(optCType, castVal)} : ${optNone(optCType)}"
        } else if (vCastIfaceInfo != null) {
            val srcFlatName = if (srcType != null && (classes.containsKey(srcType) || interfaces.containsKey(srcType))) typeFlatName(srcType) else vCastIfaceInfo.flatName
            // Rvalue (e.g. constructor call) needs a temp to take its address
            val addrExpr = if ('(' in inner) {
                val vTmp = tmp(); preStmts += "${cTypeStr(srcType ?: "")} $vTmp = ($inner);"; "&$vTmp"
            } else "&($inner)"
            "${srcFlatName}_as_${vCastIfaceInfo.baseName}($addrExpr)"
        } else if (srcType != null && interfaces.containsKey(srcType) && classes.containsKey(target)) {
            // Source is an interface, target is a concrete class — extract from tagged union
            ifaceUnionAccess(srcType, target, inner)
        } else if (srcKtc is KtcType.Any || (srcKtcCore is KtcType.Ptr && srcKtcCore.inner is KtcType.Any)) {
            val memOp = if (isPtr) "->" else "."
            "(*(${cTypeStr(target)}*)(${inner}${memOp}data))"
        } else {
            "(${cType(e.type)})($inner)"
        }
    }

    is FunRefExpr -> funCName(e.name)    // ::functionName → C function pointer
    is LambdaExpr -> error("Lambda can only be passed to an inline function, not used as a standalone expression")
}

// ── names (may resolve to enum, object field, self->field) ───────

internal fun CCodeGen.genName(e: NameExpr): String {
    val subst = lambdaParamSubst[e.name]
    if (subst != null) return subst
    val curType = lookupVar(e.name)
    val curKtc = lookupVarKtc(e.name)
    // Check if it's a known variable in scope
    if (curType != null) {
        if (currentClass != null && classes[currentClass]?.props?.any { it.first == e.name } == true) {
            val ci = classes[currentClass]!!
            val fieldName = if (e.name in ci.privateProps) "PRIV_${e.name}" else e.name
            val fieldRef = if (selfIsPointer) "\$self->${fieldName}" else "\$self.${fieldName}"
            // If field is stored as Optional but accessed after smart-cast (non-nullable context), unwrap
            val fieldType = ci.props.find { it.first == e.name }?.second
            if (fieldType?.nullable == true && curKtc !is KtcType.Nullable) {
                return "KTC_UNWRAP($fieldRef)"
            }
            return fieldRef
        }
        val vCurObj = currentObject
        if (vCurObj != null && objects[vCurObj]?.props?.any { it.first == e.name } == true) {
            val objInfo = objects[vCurObj]!!
            val fn = if (e.name in objInfo.privateProps) "PRIV_${e.name}" else e.name
            return "${typeFlatName(vCurObj)}.$fn"
        }
        // Inside a class nested within an object: resolve to parent object's field
        val parentObj = currentClass?.substringBefore('$')
        if (parentObj != null && currentObject == null
            && objects[parentObj]?.props?.any { it.first == e.name } == true
        ) {
            val objInfo = objects[parentObj]!!
            val fn = if (e.name in objInfo.privateProps) "PRIV_${e.name}" else e.name
            return "${typeFlatName(parentObj)}.$fn"
        }
        // Trampolined array param: redirect to local stack copy
        if (e.name in trampolinedParams) return "local$${e.name}"
        // Any trampoline smart-cast: narrowed from Any, dereference .data
        if (curKtc !is KtcType.Any && isAnySmartCastVar(e.name)) {
            val ct = cTypeStr(curType)
            return "(*(($ct*)(${e.name}.data)))"
        }
        // Optional var smart-casted to non-nullable: unwrap
        if (isOptional(e.name) && curKtc !is KtcType.Nullable) {
            return "KTC_UNWRAP(${e.name})"
        }
        return e.name
    }
    // Top-level property: apply package prefix
    if (e.name in topProps) return typeFlatName(e.name)
    // Object singleton: resolve to global instance name
    if (e.name in objects) return typeFlatName(e.name)
    if (e.name in enums) return typeFlatName(e.name)
    // Inside a class nested within an object: resolve to parent object's field/function
    val parentObj2 = currentClass?.substringBefore('$')
    if (parentObj2 != null && currentObject == null) {
        val objInfo = objects[parentObj2]
        if (objInfo?.props?.any { it.first == e.name } == true) {
            val fn = if (e.name in objInfo.privateProps) "PRIV_${e.name}" else e.name
            return "${typeFlatName(parentObj2)}.$fn"
        }
    }
    // Bare field access when $self has been narrowed from interface in extension function
    if (currentExtRecvType != null && interfaces.containsKey(currentExtRecvType)) {
        val vNarrowedSelf = lookupVar("\$self")
        if (vNarrowedSelf != null && classes.containsKey(vNarrowedSelf)) {
            val vCi = classes[vNarrowedSelf]!!
            if (vCi.props.any { it.first == e.name }) {
                return "${ifaceUnionAccess(currentExtRecvType!!, vNarrowedSelf, "\$self")}.${e.name}"
            }
        }
    }
    return e.name
}


// ── l-value generation (for assignments) ─────────────────────────

internal fun CCodeGen.genLValue(e: Expr, method: Boolean): String {
    return when (e) {
        is NameExpr -> {
            if (method && currentClass != null && classes[currentClass]?.props?.any { it.first == e.name } == true) {
                val ci = classes[currentClass]!!
                val fieldName = if (e.name in ci.privateProps) "PRIV_${e.name}" else e.name
                if (selfIsPointer) "\$self->${fieldName}" else "\$self.${fieldName}"
            } else if (currentObject.let { vCO -> vCO != null && objects[vCO]?.props?.any { it.first == e.name } == true })
                "${typeFlatName(currentObject!!)}.${e.name}"
            else e.name
        }

        is DotExpr -> {
            if (e.obj is NameExpr && objects.containsKey(e.obj.name))
                "${typeFlatName(e.obj.name)}.${e.name}"
            else if (e.obj is NameExpr && classCompanions.containsKey(e.obj.name)) {
                val vCompanionName = classCompanions[e.obj.name]!!
                "${typeFlatName(vCompanionName)}.${e.name}"
            } else {
                val recvKtc = inferExprTypeKtc(e.obj)
                val recvKtcCore = (recvKtc as? KtcType.Nullable)?.inner ?: recvKtc
                val op = if (recvKtcCore is KtcType.Ptr) "->" else "."
                "${genExpr(e.obj)}$op${e.name}"
            }
        }

        is IndexExpr -> {
            val objKtc = inferExprTypeKtc(e.obj)
            val objKtcCore = (objKtc as? KtcType.Nullable)?.inner ?: objKtc
            if (objKtcCore != null && (objKtcCore is KtcType.Ptr || objKtcCore.isArrayLike)) {
                "${genExpr(e.obj)}[${genExpr(e.index)}]"
            } else {
                "${genExpr(e.obj)}.ptr[${genExpr(e.index)}]"
            }
        }

        else -> genExpr(e)
    }
}

// ── String helpers for transpile-time folding ──────────────────────

internal fun trimIndentImpl(raw: String): String {
    val lines = raw.split("\n").toMutableList()
    // Remove leading blank line if present
    if (lines.isNotEmpty() && lines[0].isBlank()) lines.removeAt(0)
    // Remove trailing blank line if present
    if (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.lastIndex)
    // Find minimum indent (only consider non-blank lines)
    val minIndent = lines.filter { it.isNotBlank() }.minOfOrNull { it.indentCount() } ?: 0
    // Remove min indent from each line
    return lines.joinToString("\n") { if (it.length >= minIndent) it.substring(minIndent) else it }
}

internal fun trimMarginImpl(raw: String, marginPrefix: String): String {
    val lines = raw.split("\n").toMutableList()
    // Remove leading blank line if present
    if (lines.isNotEmpty() && lines[0].isBlank()) lines.removeAt(0)
    // Remove trailing blank line if present
    if (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.lastIndex)
    return lines.joinToString("\n") { line ->
        val idx = line.indexOf(marginPrefix)
        if (idx >= 0 && line.substring(0, idx).all { it == ' ' || it == '\t' }) {
            line.substring(idx + marginPrefix.length)
        } else {
            line
        }
    }
}

private fun String.indentCount(): Int {
    var count = 0
    for (c in this) {
        if (c == ' ') count++ else if (c == '\t') count++ else break
    }
    return count
}
