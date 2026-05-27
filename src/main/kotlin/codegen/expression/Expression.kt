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
 *   [genCall]          — function/constructor call, built-ins (allocWith, arrayOf...)
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
    is ObjectExpr -> typeFlatName(e.syntheticName)
    is BinExpr -> genBin(e)
    is PrefixExpr -> "(${e.op}${genExpr(e.expr)})"
    is PostfixExpr -> "(${genExpr(e.expr)}${e.op})"
    is CallExpr -> genCall(e)
    is DotExpr -> genDot(e)
    is SafeDotExpr -> genSafeDot(e)
    is IndexExpr -> {
        val objType = inferExprType(e.obj)                                            // String? object type
        val objTypeKtc = inferExprTypeKtc(e.obj)                                     // KtcType? object type
        val objTypeCoreKtc = objTypeKtc.stripNullable  // KtcType? stripped Nullable
        val vIdxClassInfo = classInfoFor(objTypeCoreKtc)                              // non-null if object is a class
        val vIdxIfaceInfo = ifaceInfoFor(objTypeCoreKtc)                              // non-null if object is an interface
        // Static bounds check: if index is a literal AND the array has a
        // statically known size, validate at transpile time. Triggers for
        // @Size(N) arrays and string literals where len is known.
        staticBoundsCheck(e.obj, e.index, objTypeCoreKtc)
        if (objType == "String") {
            // String indexing: str[i] → str.ptr[i] (returns char)
            val vS = genExpr(e.obj)
            "$vS.ptr[${wrapBoundsIdx(genExpr(e.index), "$vS.len", e.index, e.obj)}]"
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
                val vR = genExpr(e.obj)
                "$vR.ptr[${wrapBoundsIdx(genExpr(e.index), "$vR.len")}]"
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
                // Bare T* pointer indexing — no .len carried; bounds-check is impossible.
                // User opted into raw pointer arithmetic; warn statically only.
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
                val vR = genExpr(e.obj)
                "$vR.ptr[${wrapBoundsIdx(genExpr(e.index), "$vR.len")}]"
            }
        } else if (objTypeCoreKtc != null && objTypeCoreKtc.isArrayLike) {
            val vObjName       = (e.obj as? NameExpr)?.name                             // name of array expr (if any)
            val vIsTrampolined = vObjName != null && vObjName in trampolinedParams      // @Size trampolined param
            val vSizedN        = objTypeCoreKtc.asArr?.sized                            // fixed-size N (or null)
            if (vIsTrampolined || vSizedN != null) {
                val vA = genExpr(e.obj)
                // Length resolution priority:
                //   1. Trampolined sized param → local$name$len constant (the unpacked size).
                //   2. Type carries @Size(N) → use the literal N.
                //   3. Fallback to sizeof — only safe for true stack arrays (not pointers).
                val vLen = when {
                    vIsTrampolined && vObjName != null -> arrayParamSizeExpr(vObjName)
                    vSizedN != null                    -> vSizedN.toString()
                    else                                -> "(sizeof($vA)/sizeof(($vA)[0]))"
                }
                "$vA[${wrapBoundsIdx(genExpr(e.index), vLen, e.index, e.obj)}]"
            } else {
                val vA = genExpr(e.obj)
                "$vA.ptr[${wrapBoundsIdx(genExpr(e.index), "$vA.len", e.index, e.obj)}]"
            }
        } else {
            val vR = genExpr(e.obj)
            "$vR.ptr[${wrapBoundsIdx(genExpr(e.index), "$vR.len")}]"
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
        val exprKtcCore = exprKtc.stripNullable
        // ktc_IfacePtr is a value struct even though the KTC type is Ptr<Interface>
        val isIfacePtr = exprKtcCore is KtcType.Ptr && exprKtcCore.inner is KtcType.User && exprKtcCore.inner.kind == KtcType.UserKind.Interface
        val memOp = if (exprKtcCore is KtcType.Ptr && !isIfacePtr) "->" else "."
        val vIsClassInfo = classInfoFor(targetKtc)                                    // non-null if target is a user class
        val vIsIfaceInfo = ifaceInfoFor(targetKtc)                                    // non-null if target is an interface
        val vTypeIdRef   = typeIdExpr(exprKtcCore, inner, memOp)                      // null → concrete class (static type)
        val check = if (vIsClassInfo != null) {
            if (vTypeIdRef != null) "KTC_GET_TYPEID($vTypeIdRef) == ${vIsClassInfo.flatName}_TYPE_ID"
            else "${typeFlatName((exprKtcCore as? KtcType.User)?.baseName ?: "")}_TYPE_ID == ${vIsClassInfo.flatName}_TYPE_ID"
        } else if (vIsIfaceInfo != null) {
            val impls = classInterfaces.filter { (_, ifaces) -> target in ifaces }.keys
            if (impls.isEmpty()) "false"
            else if (vTypeIdRef != null) impls.joinToString(" || ") { "KTC_GET_TYPEID($vTypeIdRef) == ${typeFlatName(it)}_TYPE_ID" }
            else "${typeFlatName((exprKtcCore as? KtcType.User)?.baseName ?: "")}_TYPE_ID == ${typeFlatName(impls.first())}_TYPE_ID"
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
                if (vTypeIdRef != null) "(KTC_GET_TYPEID($vTypeIdRef) == $typeId)"
                else "(${typeFlatName((exprKtcCore as? KtcType.User)?.baseName ?: "")}_TYPE_ID == $typeId)"
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
        val srcKtcCore = srcKtc.stripNullable
        val isPtr = srcKtcCore is KtcType.Ptr
        val vCastClassInfo = classInfoFor(targetKtc)                                  // non-null if target is a user class
        val vCastIfaceInfo = ifaceInfoFor(targetKtc)                                  // non-null if target is an interface
        if (e.safe) {
            val optCType = optCTypeName("$target?")
            val memOp = if (isPtr) "->" else "."
            val vTypeIdRef = typeIdExpr(srcKtcCore, inner, memOp)
            val check = if (vCastClassInfo != null) {
                if (vTypeIdRef != null) "KTC_GET_TYPEID($vTypeIdRef) == ${vCastClassInfo.flatName}_TYPE_ID"
                else "${typeFlatName((srcKtcCore as? KtcType.User)?.baseName ?: "")}_TYPE_ID == ${vCastClassInfo.flatName}_TYPE_ID"
            } else if (vCastIfaceInfo != null) {
                val impls = classInterfaces.filter { (_, ifaces) -> target in ifaces }.keys
                if (impls.isEmpty()) "false"
                else if (vTypeIdRef != null) impls.joinToString(" || ") { "KTC_GET_TYPEID($vTypeIdRef) == ${typeFlatName(it)}_TYPE_ID" }
                else impls.joinToString(" || ") { "${typeFlatName((srcKtcCore as? KtcType.User)?.baseName ?: "")}_TYPE_ID == ${typeFlatName(it)}_TYPE_ID" }
            } else if (targetKtc !is KtcType.User || targetKtc.kind != KtcType.UserKind.Class) {
                val typeId = getTypeId(target)
                if (vTypeIdRef != null) "KTC_GET_TYPEID($vTypeIdRef) == $typeId"
                else "${typeFlatName((srcKtcCore as? KtcType.User)?.baseName ?: "")}_TYPE_ID == $typeId"
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
        } else if (srcKtcCore is KtcType.Ptr && srcKtcCore.inner.toInternalStr == target) {
            // Already a pointer to target: just dereference
            "(*($inner))"
        } else {
            "(${cType(e.type)})($inner)"
        }
    }

    is FunRefExpr -> funCName(e.name)    // ::functionName → C function pointer
    is ClassRefExpr -> codegenError("T::class is only supported as T::class.simpleName or T::class.qualifiedName")
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

// ══════════════════════════════════════════════════════════════════
// MARK: Array bounds checking (static warnings + runtime check)
// ══════════════════════════════════════════════════════════════════

/* Wraps a runtime index expression with ktc_core_bounds_check when the
checkBounds flag is on, returning a C expression that panics-then-exits on
out-of-range access and returns the index unchanged otherwise. inLen is a C
expression yielding the array's length (e.g. "arr.len" or a literal "8").
When the flag is off, returns the raw index unchanged for a zero-cost
release build.
inIdxAst/inObjAst: optional AST nodes — when both are present and the index
is a non-negative literal within the statically known array size, the
runtime check is elided (the access is provably safe). */
internal fun CCodeGen.wrapBoundsIdx(inIdxExpr: String, inLen: String, inIdxAst: Expr? = null, inObjAst: Expr? = null): String {
	if (!checkBounds) return inIdxExpr
	if (inIdxAst != null && inObjAst != null && isStaticallySafe(inIdxAst, inObjAst)) return inIdxExpr
	val vFile = currentSourceFile
	val vFileC = "\"${vFile.replace("\\", "\\\\")}\""
	val vFileLen = vFile.length
	return "ktc_core_bounds_check($vFileC, $vFileLen, $currentStmtLine, ($inIdxExpr), ($inLen))"
}

// Returns true when a literal index is provably within bounds of a statically-sized container.
private fun CCodeGen.isStaticallySafe(inIdxAst: Expr, inObjAst: Expr): Boolean {
	val vIdx = (inIdxAst as? IntLit)?.value ?: return false
	if (vIdx < 0) return false
	val vObjKtc = inferExprTypeKtc(inObjAst)?.stripNullable
	val vLen: Long = when {
		vObjKtc is KtcType.Arr && vObjKtc.sized != null -> vObjKtc.sized!!.toLong()
		inObjAst is StrLit -> inObjAst.value.length.toLong()
		else -> return false
	}
	return vIdx < vLen
}

// ══════════════════════════════════════════════════════════════════
// MARK: Null-deref check (--check-null opt-in)
// ══════════════════════════════════════════════════════════════════

/* Read-side wrapper: emits the null check as a preStmt so the returned
expression stays a plain lvalue (`*p`) — important when the caller writes
patterns like `&p.refValue` which need an lvalue operand. Returns the raw
deref expression unchanged on --no-check-null.
inRecvAst: optional AST node for the pointer expression — when present,
the check is elided for provably non-null origins (e.g. .asRef() of a local). */
internal fun CCodeGen.wrapNullCheck(inPtrExpr: String, inDerefExpr: String, inRecvAst: Expr? = null): String {
	if (!checkNull) return inDerefExpr
	if (inRecvAst != null && isProvablyNonNull(inRecvAst)) return inDerefExpr
	preStmts += nullCheckStmt(inPtrExpr)
	return inDerefExpr
}

internal fun CCodeGen.isProvablyNonNull(inExpr: Expr): Boolean {
	if (inExpr is CallExpr && inExpr.callee is DotExpr && (inExpr.callee as DotExpr).name == "asRef")
		return true
	// After smart-cast in `if (p != null)`, Nullable(Ptr(T)) narrows to Ptr(T).
	// Only elide when an outer scope had a nullable variant — not for all Ptr params.
	if (inExpr is NameExpr) {
		val ktc = lookupVarKtc(inExpr.name)
		if (ktc is KtcType.Ptr) {
			for (i in scopes.size - 2 downTo 0) {
				val outer = scopes[i][inExpr.name]?.ktc ?: continue
				if (outer is KtcType.Nullable) return true
				return false
			}
		}
	}
	return false
}

/* Statement-form null check, used before `*p = x` assignment lowering or
as a preStmt before a read-side deref. Returns a single C statement
(terminated with `;`). No-op string when --check-null is off. */
internal fun CCodeGen.nullCheckStmt(inPtrExpr: String): String {
	if (!checkNull) return ""
	val vFile = currentSourceFile
	val vFileC = "\"${vFile.replace("\\", "\\\\")}\""
	val vFileLen = vFile.length
	return "ktc_core_null_check((const void*)($inPtrExpr), $vFileC, $vFileLen, $currentStmtLine);"
}

/* Compile-time bounds check: if inIdx is a literal IntLit AND the array's
length is statically known (sized arrays, string literals), validates the
range and emits a warning (negative or >= length). Stays silent otherwise.
Doesn't block compilation — runtime check will fire for the same access
when checkBounds is on. */
internal fun CCodeGen.staticBoundsCheck(inObj: Expr, inIdx: Expr, inObjTypeCore: KtcType?) {
	val vIdxVal = when (inIdx) {
		is IntLit -> inIdx.value
		else -> return
	}
	// Determine statically-known length.
	val vKnownLen: Long? = when {
		inObjTypeCore is KtcType.Arr && inObjTypeCore.sized != null -> inObjTypeCore.sized!!.toLong()
		inObj is StrLit -> inObj.value.length.toLong()
		// Note: inferring length from a NameExpr would need flow analysis
		// against the declared `IntArray(N)` initializer — left for the
		// runtime check to catch on default-on bounds checking.
		else -> null
	}
	if (vKnownLen == null) return
	if (vIdxVal < 0) {
		System.err.println("WARNING [$currentSourceFile:$currentStmtLine]: " +
			"array index $vIdxVal is negative — always out of bounds.")
	} else if (vIdxVal >= vKnownLen) {
		System.err.println("WARNING [$currentSourceFile:$currentStmtLine]: " +
			"array index $vIdxVal is out of bounds for length $vKnownLen.")
	}
}
