package com.bitsycore.ktc.codegen.expression

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.codegen.*
import com.bitsycore.ktc.codegen.emit.collectAllIfaceMethods
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
 *   [com.bitsycore.ktc.codegen.expression.genMethodCall]    — method dispatch: built-in, class method, interface vtable, extension
 *   [genDot]           — field access, enum values, object properties, pointer deref
 *   [genSafeDot]       — nullable-safe field access (?.)
 *   [genNotNull]       — not-null assertion (!!)
 *   [genIfExpr]        — if-expression → C ternary or pre-stmt temp
 *   [genWhenExpr]      — when-expression → nested ternary or pre-stmt temp
 *   [genStrTemplate]   — string template → StrBuf-based concatenation
 *   [com.bitsycore.ktc.codegen.expression.genToString]      — toString() dispatch (primitive, data class, default hash)
 *   [com.bitsycore.ktc.codegen.expression.genSbAppend]      — StrBuf append for all types
 *   [com.bitsycore.ktc.codegen.expression.genArrayOfExpr]   — array literal (arrayOf/byteArrayOf/etc.)
 *   [com.bitsycore.ktc.codegen.expression.genNewArray]      — Array<T>(size) constructor → alloca
 *   [com.bitsycore.ktc.codegen.expression.genLValue]         — l-value for assignment target
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
 *   Calls into codegen/expr/Stmts*.kt (emitStmt, emitInlineCall, emitBlock)
 *   Calls into TypeInfer.kt / TypeInferCall.kt (inferExprType, inferMethodReturnType, ...)
 *   Calls into CTypes.kt (cType, cTypeStr, resolveTypeName, optNone, optSome, ...)
 */

// ═══════════════════════════ Expression codegen ═══════════════════

/** Generate an expression for use as a C function argument.
 *  String literals are emitted as raw C strings (not ktc_core_str wrapped). */
internal fun CCodeGen.genCArg(e: Expr): String = when (e) {
    is StrLit -> "\"${escapeStr(e.value)}\""
    else -> genExpr(e)
}

internal fun CCodeGen.genExpr(e: Expr): String = when (e) {
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
        } else if (objTypeCoreKtc is KtcType.Ptr && objTypeCoreKtc.inner !is KtcType.Arr) {
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
                    tmpOptional(retBase, "$recv.vt->get($vIdxSelfArg, $idx)")
                } else {
                    "$recv.vt->get($vIdxSelfArg, $idx)"
                }
            } else {
                "${genExpr(e.obj)}.ptr[${genExpr(e.index)}]"
            }
        } else if (objTypeCoreKtc != null && objTypeCoreKtc.isArrayLike) {
            val vObjName       = (e.obj as? NameExpr)?.name                             // name of array expr (if any)
            val vIsTrampolined = vObjName != null && vObjName in trampolinedParams      // @Size trampolined param
            val vIsSizedArr    = objTypeCoreKtc?.asArr?.sized != null              // fixed-size C array
            if (vIsTrampolined || vIsSizedArr) "${genExpr(e.obj)}[${genExpr(e.index)}]"
            else "${genExpr(e.obj)}.ptr[${genExpr(e.index)}]"
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
