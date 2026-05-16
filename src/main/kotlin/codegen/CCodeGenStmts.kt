package com.bitsycore.ktc.codegen

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.ast.Annotation
import com.bitsycore.ktc.codegen.mapping.arrayElementCTypeKtc
import com.bitsycore.ktc.codegen.mapping.arrayElementKtTypeKtc
import com.bitsycore.ktc.types.KtcType

/**
 * ── Statement Codegen ───────────────────────────────────────────────────
 *
 * Translates Kotlin statements into C code. The main dispatcher is
 * [emitStmt] which routes each statement type to a specialized handler.
 *
 * ## Main entry points:
 *
 *   [emitStmt]        — statement dispatcher (var/assign/return/expr/for/while/...)
 *   [emitBlock]       — emit a block of statements (used by if/for/when bodies)
 *   [emitVarDecl]     — var/val declaration (array, pointer, nullable, Optional)
 *   [emitAssign]      — assignment (=, +=, safe dot assign, operator set)
 *   [emitReturn]      — return with defer execution, interface wrapping
 *   [emitIfStmt]      — if/else with smart-cast scoping
 *   [emitWhenStmt]    — when-as-statement → if/else-if chain
 *   [emitFor]         — range-based (in/until/downTo) and iterator-based for
 *   [emitInlineCall]  — inline function expansion at call site
 *   [emitLambdaCall]  — lambda expansion inside inline body
 *
 * ## State accessed:
 *   scopes (pushScope/popScope/defineVar/lookupVar), deferStack, trampolinedParams,
 *   currentFnReturnsNullable, currentFnReturnsArray, currentFnReturnsSizedArray,
 *   currentFnReturnType, currentFnIsMain, currentStmtLine, currentInd,
 *   selfIsPointer, currentClass, currentObject, mutableVarScopes, optValVarNames,
 *   hdr/impl/implFwd, preStmts
 *
 * ## Dependencies:
 *   Calls into CCodeGenExpr.kt  (genExpr, genLValue, genMethodCall, genSbAppend...)
 *   Calls into CCodeGenInfer.kt (inferExprType, inferMethodReturnType)
 *   Calls into CCodeGenCTypes.kt (cTypeStr, optCTypeName, optNone, optSome, ...)
 */

// ═══════════════════════════ Statements ═══════════════════════════

internal fun CCodeGen.emitStmt(s: Stmt, ind: String, insideMethod: Boolean = false) {
    if (s.line > 0) currentStmtLine = s.line
    currentInd = ind
    when (s) {
        is VarDeclStmt -> emitVarDecl(s, ind)
        is AssignStmt -> emitAssign(s, ind, insideMethod)
        is ReturnStmt -> emitReturn(s, ind)
        is ExprStmt -> emitExprStmt(s, ind, insideMethod)
        is ForStmt -> emitFor(s, ind, insideMethod)
        is WhileStmt -> {
            loopDepth++
            impl.appendLine("${ind}while (${genExpr(s.cond)}) {")
            emitBlock(s.body, ind, insideMethod)
            impl.appendLine("$ind}")
            loopDepth--
        }

        is DoWhileStmt -> {
            loopDepth++
            impl.appendLine("${ind}do {")
            emitBlock(s.body, ind, insideMethod)
            impl.appendLine("$ind} while (${genExpr(s.cond)});")
            loopDepth--
        }

        is BreakStmt -> {
            if (loopDepth == 0) codegenError("'break' outside of a loop")
            impl.appendLine("${ind}break;")
        }
        is ContinueStmt -> {
            if (loopDepth == 0) codegenError("'continue' outside of a loop")
            impl.appendLine("${ind}continue;")
        }
        is DeferStmt -> deferStack.add(s.body)
        is CommentStmt -> {
            impl.appendLine("$ind${s.text}")
        }
    }
    // Smart cast: if (x == null) return/break/continue → narrow x to non-null after
    applyGuardSmartCast(s)
}

/** After `if (x == null) ... return/break/continue` (no else), narrow x from T? to T. */
internal fun CCodeGen.applyGuardSmartCast(s: Stmt) {
    if (s !is ExprStmt) return
    val ifExpr = s.expr as? IfExpr ?: return
    if (ifExpr.els != null) return  // must have no else branch
    // Body must end with an early-exit statement
    val lastStmt = ifExpr.then.stmts.lastOrNull() ?: return
    if (lastStmt !is ReturnStmt && lastStmt !is BreakStmt && lastStmt !is ContinueStmt) return
    val casts = extractElseSmartCasts(ifExpr.cond)
    val outerInd = currentInd.removeSuffix("    ")
    for ((name, nonNullType) in casts) {
        impl.appendLine("${outerInd}// smart-cast: '$name' narrowed to '$nonNullType'")
        defineVar(name, nonNullType)
    }
}

internal fun CCodeGen.emitBlock(b: Block, ind: String, insideMethod: Boolean = false) {
    for ((idx, s) in b.stmts.withIndex()) {
        emitStmt(s, "$ind    ", insideMethod)
        // Check: unreachable code after unconditional exit
        if (s is ReturnStmt || s is BreakStmt || s is ContinueStmt) {
            val vRemaining = b.stmts.drop(idx + 1).filter { it !is CommentStmt }
            if (vRemaining.isNotEmpty()) {
                if (s.line > 0) currentStmtLine = s.line
                codegenError("Unreachable code after '${if (s is ReturnStmt) "return" else if (s is BreakStmt) "break" else "continue"}'")
            }
        }
    }
}

// ── var / val ────────────────────────────────────────────────────

internal fun CCodeGen.emitVarDecl(s: VarDeclStmt, ind: String) {
    val vKtc = if (s.type != null) resolveTypeName(s.type) else parseResolvedTypeName(inferExprType(s.init) ?: "Int") // KtcType (for C type emission)
    val vKtcKtc = inferExprTypeKtc(s.init)
    val vKtcCore = (vKtc as? KtcType.Nullable)?.inner ?: vKtc
    val tRaw = vKtc.toInternalStr                                                                    // string type (for structural checks — retained during migration)
    val inferredNullable = s.type == null && vKtc is KtcType.Nullable
    // Strip ? suffix for nullable types; it gets added back at defineVar and optCTypeName
    val t = if (inferredNullable) tRaw.removeSuffix("?") else tRaw
    // malloc/calloc/realloc return nullable pointers (may return NULL)
    val isAlloc = s.type == null && isAllocCall(s.init)

    // Is this a pointer type? (@Ptr annotation adds * suffix)
    // Only user-class pointers (Vec2*), not typed-array pointers (IntArray which is Ptr<Arr<Int>>)
    val isPointer = vKtcCore is KtcType.Ptr && vKtcCore.inner !is KtcType.Arr

    // Nullable pointer (@Ptr T?): can be NULL
    val isPtrNullable = isPointer &&
            (s.type?.nullable == true || s.init is NullLit || inferredNullable || isAlloc)

    // Value nullable (T? without pointer): uses Optional struct
    val isValueNullable = when {
        isPointer -> false
        vKtcCore is KtcType.Func -> false
        vKtcCore.isArrayLike -> false
        vKtcCore is KtcType.Any -> false
        else -> s.type?.nullable == true || s.init is NullLit || isNullableReturningCall(s.init) || inferredNullable
    }

    // Nullable array (Array<T>?): uses pointer + $len, null = NULL
    val isNullableArray = isArrayType(t) && !isPointer &&
            (s.type?.nullable == true || s.init is NullLit || inferredNullable)

    // Nullable Any: trampoline, null = data == NULL
    val isAnyNullable = vKtcCore is KtcType.Any &&
            (s.type?.nullable == true || s.init is NullLit || inferredNullable)

    // Register type in scope
    defineVar(
        s.name, when {
            isPtrNullable -> "${t}?"
            isValueNullable -> "${t}?"
            isNullableArray -> "${t}?"
            isAnyNullable -> "${t}?"
            else -> t
        }
    )
    if (s.mutable) markMutable(s.name)
    val mutComment = if (s.mutable) "/*VAR*/ " else "/*VAL*/ "

    // ── Function pointer type: special declaration syntax ──
    if (vKtc is KtcType.Func) {
        if (s.init != null) {
            val expr = genExpr(s.init)
            flushPreStmts(ind)
            impl.appendLine("$ind$mutComment${cFuncPtrDecl(t, s.name)} = $expr;")
        } else {
            impl.appendLine("$ind$mutComment${cFuncPtrDecl(t, s.name)} = NULL;")
        }
        return
    }

    val ct = cTypeStr(vKtc)  // C type string derived from KtcType
    // Don't const class types, typed pointers, nullable, arrays, or interface types
    val qual = if (!s.mutable && vKtcCore !is KtcType.User
        && !vKtcCore.isArrayLike
        && !isPointer && !isValueNullable && !isPtrNullable
    ) "const " else ""

    if (s.init != null) {
        val arrayInit = tryArrayOfInit(s.name, s.init, ct, t, ind)
        if (arrayInit != null) {
            impl.appendLine(arrayInit)
            // Emit $has for nullable array variables so safe-calls work
            val isNullableArray = (s.type?.nullable == true || inferredNullable) && vKtcCore.isArrayLike && !isPtrNullable
            if (isNullableArray) {
                impl.appendLine("${ind}bool ${s.name}\$has = true;")
            }
            return
        }

        when {
            // ── Nullable pointer (@Ptr T?): can be NULL ──
            isPtrNullable -> {
                if (s.init is NullLit) {
                    impl.appendLine("$ind$mutComment$ct ${s.name} /* nullable */ = NULL;")
                } else {
                    val expr = genExpr(s.init)
                    flushPreStmts(ind)
                    impl.appendLine("$ind$mutComment$ct ${s.name} /* nullable */ = $expr;")
                }
                // Emit $len companion for array pointer types
                if (isAllocArrayCall(s.init)) {
                    val allocSize = extractAllocSize(s.init)
                    if (allocSize != null) {
                        impl.appendLine("${ind}ktc_Int ${s.name}\$len = ${genExpr(allocSize)};")
                    }
                } else if (vKtcCore.isArrayLike && s.init is NameExpr) {
                    val lenVar = s.init.name + "\$len"
                    impl.appendLine("${ind}const ktc_Int ${s.name}\$len = $lenVar;")
                }
            }
            // ── Value nullable — use Optional struct ──
            isValueNullable -> {
                val optType = optCTypeName("${t}?")
                markOptional(s.name)
                if (s.init is NullLit) {
                    impl.appendLine("$ind$mutComment$optType ${s.name} = ${optNone(optType)};")
                } else {
                    val srcKtc = inferExprTypeKtc(s.init)
                    val alreadyOpt = srcKtc is KtcType.Nullable && isValueNullableKtc(srcKtc)
                    val expr = genExpr(s.init)
                    flushPreStmts(ind)
                    if (alreadyOpt) {
                        impl.appendLine("$ind$mutComment$optType ${s.name} = $expr;")
                    } else {
                        impl.appendLine("$ind$mutComment$optType ${s.name} = ${optSome(optType, expr)};")
                    }
                }
            }
            // ── Nullable array (Array<T>?) ──
            isNullableArray -> {
                val elemCType = arrayElementCTypeKtc(vKtcCore)
                if (s.init is NullLit) {
                    impl.appendLine("$ind$mutComment$elemCType* ${s.name} = NULL;")
                    impl.appendLine("${ind}const ktc_Int ${s.name}\$len = 0;")
                } else if (s.init is DotExpr && s.init.name == "buffer") {
                    val dotInit = s.init
                    val dotRecvType = inferExprType(dotInit.obj)
                    val dotRecvTypeKtc = inferExprTypeKtc(dotInit.obj)
                    if (dotRecvType == "ktc_StrBuf" || dotRecvType == "StringBuffer") {
                        val recvExpr = genExpr(dotInit.obj)
                        val expr = genExpr(s.init)
                        flushPreStmts(ind)
                        impl.appendLine("$ind$mutComment$elemCType* ${s.name} /* nullable */ = $expr;")
                        impl.appendLine("${ind}const ktc_Int ${s.name}\$len = $recvExpr.cap;")
                    } else {
                        val expr = genExpr(s.init)
                        flushPreStmts(ind)
                        val lenExpr = "${expr}\$len"
                        impl.appendLine("$ind$mutComment$elemCType* ${s.name} = ($elemCType*)ktc_core_alloca(sizeof($elemCType) * $lenExpr);")
                        impl.appendLine("${ind}memcpy(${s.name}, $expr, sizeof($elemCType) * $lenExpr);")
                        impl.appendLine("${ind}const ktc_Int ${s.name}\$len = $lenExpr;")
                    }
                } else {
                    val expr = genExpr(s.init)
                    flushPreStmts(ind)
                    val lenExpr = if (s.init is NameExpr) "${s.init.name}\$len" else "${expr}\$len"
                    impl.appendLine("$ind$mutComment$elemCType* ${s.name} = ($elemCType*)ktc_core_alloca(sizeof($elemCType) * $lenExpr);")
                    impl.appendLine("${ind}memcpy(${s.name}, $expr, sizeof($elemCType) * $lenExpr);")
                    impl.appendLine("${ind}const ktc_Int ${s.name}\$len = $lenExpr;")
                }
            }
            // ── Nullable Any (trampoline, null = data == NULL) ──
            isAnyNullable -> {
                if (s.init is NullLit) {
                    impl.appendLine("$ind$mutComment$ct ${s.name} = (ktc_Any){0};")
                } else {
                    val initType = inferExprType(s.init)?.removeSuffix("?") ?: "Int"
                    val initTypeKtc = inferExprTypeKtc(s.init)
                    val typeId = getTypeId(initType)
                    val initCT = cTypeStr(initType)
                    val expr = genExpr(s.init)
                    flushPreStmts(ind)
                    val tVal = tmp()
                    impl.appendLine("$ind$initCT $tVal = $expr;")
                    impl.appendLine("$ind$mutComment$ct ${s.name} = (ktc_Any){{$typeId}, (void*)&$tVal};")
                }
            }
            // ── Non-nullable ──
            else -> {
                // Interface variable initialized from implementing class → auto-wrap
                if (interfaces.containsKey(t)) {
                    val initType = inferExprType(s.init)
                    val initTypeKtc = inferExprTypeKtc(s.init)
                    if (initType != null && (classes.containsKey(initType) || objects.containsKey(initType)) && classInterfaces[initType]?.contains(t) == true) {
                        val isObj = objects.containsKey(initType)
                        if (isObj && (s.type == null || s.type.annotations.none { it.name == "Ptr" })) {
                            currentStmtLine = s.line
                            codegenError("Object '${initType}' must be stored as @Ptr. Use: val ${s.name}: @Ptr $t = ${initType}")
                        }
                        val expr = genExpr(s.init)
                        flushPreStmts(ind)
                        if (isObj) {
                            impl.appendLine("$ind$ct ${s.name} = ${typeFlatName(initType)}_as_$t(&$expr);")
                        } else {
                            val backing = tmp()
                            impl.appendLine("$ind${typeFlatName(initType)} $backing = $expr;")
                            impl.appendLine("$ind$ct ${s.name} = ${typeFlatName(initType)}_as_$t(&$backing);")
                        }
                        return
                    }
                }
                // Sized-array-returning function call: declare local array, pass as out-param
                if (isSizedArrayReturningCall(s.init)) {
                    val call = s.init as CallExpr
                    val size = getSizedArrayReturnSize(call)!!
                    val elemCType = getSizedArrayReturnElemType(call)!!
                    impl.appendLine("${ind}${elemCType} ${s.name}[$size];")
                    impl.appendLine("${ind}const ktc_Int ${s.name}\$len = $size;")
                    genExprWithSizedArrayOut(s.init, s.name)
                    flushPreStmts(ind)
                    return
                }
                // Array-returning function call: declare $len first, pass &$len as out-param
                if (vKtcCore.isArrayLike && isArrayReturningCall(s.init)) {
                    impl.appendLine("${ind}ktc_Int ${s.name}\$len;")
                    val expr = genExprWithArrayLenOut(s.init, s.name)
                    flushPreStmts(ind)
                    impl.appendLine("$ind$qual$ct ${s.name} = $expr;")
                    return
                }
                if (s.type != null) heapAllocTargetType = s.type
                val expr = genExpr(s.init)
                heapAllocTargetType = null
                flushPreStmts(ind)
                // Array type: deep copy from source (value semantics, not alias)
                if (vKtcCore is KtcType.Arr && s.init !is NullLit) {
                    val elemCType = arrayElementCTypeKtc(vKtcCore)
                    val lenExpr = if (s.init is NameExpr) "${s.init.name}\$len" else "${expr}\$len"
                    impl.appendLine("${ind}$elemCType* ${s.name} = ($elemCType*)ktc_core_alloca(sizeof($elemCType) * $lenExpr);")
                    impl.appendLine("${ind}memcpy(${s.name}, $expr, sizeof($elemCType) * $lenExpr);")
                    impl.appendLine("${ind}const ktc_Int ${s.name}\$len = $lenExpr;")
                } else {
                    // Auto-wrap init into ktc_Any trampoline when variable is typed Any
                    if (vKtc is KtcType.Any && s.init !is NullLit) {
                        val initType = inferExprType(s.init)?.removeSuffix("?") ?: "Int"
                        val initTypeKtc = inferExprTypeKtc(s.init)
                        val typeId = getTypeId(initType)
                        val initCT = cTypeStr(initType)
                        val tVal = tmp()
                        impl.appendLine("$ind$initCT $tVal = $expr;")
                        impl.appendLine("$ind$mutComment$qual$ct ${s.name} = (ktc_Any){{$typeId}, (void*)&$tVal};")
                    } else {
                        impl.appendLine("$ind$mutComment$qual$ct ${s.name} = $expr;")
                    }
                    if (vKtcCore.isArrayLike) {
                        val lenInit = if (s.init is NullLit) "0" else "${expr}\$len"
                        impl.appendLine("${ind}const ktc_Int ${s.name}\$len = $lenInit;")
                    } else if (vKtcCore is KtcType.Ptr && s.init is NameExpr) {
                        val srcName = s.init.name
                        // Skip $len copy if source is a @Ptr RawArray<T> field (which has no $len companion)
                        val isRawArrayField = currentClass != null &&
                                classes[currentClass]?.props?.any { it.first == srcName && it.second.name == "RawArray" } == true
                        if (!isRawArrayField) {
                            impl.appendLine("${ind}ktc_Int ${s.name}\$len = ${srcName}\$len;")
                        }
                    }
                }
            }
        }
    } else {
        when {
            isPtrNullable -> {
                impl.appendLine("$ind$mutComment$ct ${s.name} = NULL;")
            }

            isNullableArray -> {
                impl.appendLine("$ind$mutComment${arrayElementCTypeKtc(vKtcCore)}* ${s.name} = NULL;")
                impl.appendLine("${ind}const ktc_Int ${s.name}\$len = 0;")
            }

            else -> {
                if (isValueNullable) {
                    val optType = optCTypeName("${t}?")
                    markOptional(s.name)
                    impl.appendLine("$ind$mutComment$optType ${s.name} = ${optNone(optType)};")
                } else {
                    impl.appendLine("$ind$mutComment$ct ${s.name} = ${defaultVal(vKtc)};")
                }
            }
        }
    }
}


internal fun CCodeGen.tryArrayOfInit(varName: String, init: Expr, ct: String, t: String, ind: String): String? {
    if (init !is CallExpr) return null
    // .ptr() on array expression → propagate $len to the target variable
    if (init.callee is DotExpr) {
        val dot = init.callee
        if (dot.name == "ptr" || dot.name == "toHeap") {
            val recvKtc = inferExprTypeKtc(dot.obj)
            if (recvKtc?.isArrayLike == true) {
                val expr = genExpr(init)
                flushPreStmts(ind)
                return "$ind$ct $varName = $expr;\n${ind}ktc_Int ${varName}\$len = ${expr}\$len;"
            }
        }
    }
    val callee = (init.callee as? NameExpr)?.name ?: return null
    // arrayOfNulls<T>(size) — stack-allocate array of Optionals, all set to ktc_NONE
    if (callee == "arrayOfNulls") {
        val typeArg = init.typeArgs.getOrNull(0)
        val elemName = typeSubst[typeArg?.name ?: "Int"] ?: (typeArg?.name ?: "Int")
        val optCType = optCTypeName("${elemName}?")
        val size = if (init.args.isNotEmpty()) genExpr(init.args[0].expr) else "0"
        return "${ind}$optCType* $varName = ($optCType*)ktc_core_alloca(sizeof($optCType) * (size_t)($size));\n" +
                "${ind}memset($varName, 0, sizeof($optCType) * (size_t)($size));\n" +
                "${ind}const ktc_Int ${varName}\$len = $size;"
    }
    // Array<T>(size), IntArray(size) etc. — fresh stack allocation, emit directly into varName
    if (callee in setOf(
            "IntArray", "LongArray", "FloatArray", "DoubleArray",
            "BooleanArray", "CharArray", "ByteArray", "ShortArray",
            "UByteArray", "UShortArray", "UIntArray", "ULongArray"
        ) ||
        (callee == "Array" && init.typeArgs.isNotEmpty())
    ) {
        val elemC = if (callee == "Array") {
            cTypeStr(resolveTypeName(init.typeArgs[0]))  // KtcType for element type emission
        } else when (callee) {
            "IntArray" -> "ktc_Int"; "LongArray" -> "ktc_Long"
            "FloatArray" -> "ktc_Float"; "DoubleArray" -> "ktc_Double"
            "BooleanArray" -> "ktc_Bool"; "CharArray" -> "ktc_Char"
            "ByteArray" -> "ktc_Byte"; "ShortArray" -> "ktc_Short"
            "UByteArray" -> "ktc_UByte"; "UShortArray" -> "ktc_UShort"
            "UIntArray" -> "ktc_UInt"; "ULongArray" -> "ktc_ULong"
            else -> return null
        }
        val sizeArg = init.args[0]
        val size = genExpr(sizeArg.expr)
        // Array<T>(size) { lambda } — inline init loop
        if (init.args.size >= 2 && init.args[1].expr is LambdaExpr) {
            val lambda = init.args[1].expr as LambdaExpr
            val itName = lambda.params.firstOrNull() ?: "it"
            flushPreStmts(ind)
            val sb = StringBuilder()
            sb.appendLine("${ind}$elemC* $varName = ($elemC*)ktc_core_alloca(sizeof($elemC) * (size_t)($size));")
            sb.appendLine("${ind}const ktc_Int ${varName}\$len = $size;")
            sb.appendLine("${ind}for (ktc_Int $itName = 0; $itName < $size; $itName++) {")
            pushScope()
            defineVar(itName, "Int")
            // Lambda body: emit all statements, last one produces the element value
            for ((i, stmt) in lambda.body.withIndex()) {
                val isLast = i == lambda.body.lastIndex
                when {
                    isLast && stmt is ExprStmt -> {
                        sb.appendLine("$ind    $varName[$itName] = ${genExpr(stmt.expr)};")
                    }

                    stmt is ExprStmt -> {
                        // Non-last expression statement: evaluate for side effects and discard
                        sb.appendLine("$ind    (void)${genExpr(stmt.expr)};")
                    }

                    stmt is VarDeclStmt -> {
                        // Local val/var inside the loop body
                        val vTypeKtc =
                            stmt.type?.let { resolveTypeName(it) } ?: parseResolvedTypeName(inferExprType(stmt.init) ?: "Int") // KtcType for emission
                        val vTypeKtcKtc = inferExprTypeKtc(stmt.init)
                        defineVarKtc(stmt.name, vTypeKtc)
                        val vCT = cTypeStr(vTypeKtc)  // C type from KtcType
                        val mut = if (stmt.mutable) "" else "const "
                        val initExpr = stmt.init?.let { genExpr(it) } ?: "0"
                        sb.appendLine("$ind    ${mut}$vCT ${stmt.name} = $initExpr;")
                    }

                    stmt is AssignStmt -> {
                        val lhs = genLValue(stmt.target, false)
                        val rhs = genExpr(stmt.value)
                        val op = when (stmt.op) {
                            "+=" -> "+"; "-=" -> "-"; "*=" -> "*"; "/=" -> "/"; "%=" -> "%"
                            else -> ""
                        }
                        if (op.isNotEmpty()) {
                            sb.appendLine("$ind    $lhs = ($lhs $op $rhs);")
                        } else {
                            sb.appendLine("$ind    $lhs = $rhs;")
                        }
                    }

                    else -> codegenError("Unsupported statement in Array init lambda body")
                }
            }
            sb.appendLine("${ind}}")
            popScope()
            return sb.toString()
        }
        flushPreStmts(ind)
        return "${ind}$elemC* $varName = ($elemC*)ktc_core_alloca(sizeof($elemC) * (size_t)($size));\n" +
                "${ind}const ktc_Int ${varName}\$len = $size;"
    }
    // arrayOf<T?>(…) or arrayOf(…) where declared type is an OptArray: wrap each element in Optional struct
    if (callee == "arrayOf") {
        val vTypeArg = init.typeArgs.getOrNull(0)
        val tKtc = parseResolvedTypeName(t)
        val tKtcCore = (tKtc as? KtcType.Nullable)?.inner ?: tKtc
        val isOptArray = tKtcCore is KtcType.Ptr && tKtcCore.inner is KtcType.Arr
            && tKtcCore.inner.elem is KtcType.Nullable
        val vIsNullableElem = vTypeArg?.nullable == true || isOptArray
        if (vIsNullableElem) {
            val vOptCType = if (isOptArray) arrayElementCTypeKtc(tKtcCore)
            else optCTypeName("${typeSubst[vTypeArg!!.name] ?: vTypeArg.name}?")
            val vArgs = init.args.joinToString(", ") { vArg ->
                if (vArg.expr is NullLit) optNone(vOptCType)
                else optSome(vOptCType, genExpr(vArg.expr))
            }
            return "${ind}$vOptCType ${varName}[] = {$vArgs};\n${ind}const ktc_Int ${varName}\$len = ${init.args.size};"
        }
    }
    // heapArrayOf<T>(e1, e2, ...) → heap allocation, returns pointer, safe to return from functions
    if (callee == "heapArrayOf") {
        val elemType = if (init.typeArgs.isNotEmpty()) cTypeStr(typeSubst[init.typeArgs[0].name] ?: init.typeArgs[0].name)
        else if (init.args.isNotEmpty()) {
            val inferred = inferExprType(init.args[0].expr) ?: "Int"
            val inferredKtc = inferExprTypeKtc(init.args[0].expr)
            cTypeStr(inferred)
        } else "ktc_Int"
        val n = init.args.size
        val sb = StringBuilder()
        flushPreStmts(ind)
        sb.appendLine("${ind}$elemType* $varName = ($elemType*)${tMalloc("sizeof($elemType) * $n")};")
        init.args.forEachIndexed { i, arg ->
            sb.appendLine("${ind}$varName[$i] = ${genExpr(arg.expr)};")
        }
        sb.appendLine("${ind}const ktc_Int ${varName}\$len = $n;")
        return sb.toString().trimEnd()
    }
    val elemType = when (callee) {
        "byteArrayOf" -> "ktc_Byte"
        "shortArrayOf" -> "ktc_Short"
        "intArrayOf" -> "ktc_Int"
        "longArrayOf" -> "ktc_Long"
        "floatArrayOf" -> "ktc_Float"
        "doubleArrayOf" -> "ktc_Double"
        "booleanArrayOf" -> "ktc_Bool"
        "charArrayOf" -> "ktc_Char"
        "ubyteArrayOf" -> "ktc_UByte"
        "ushortArrayOf" -> "ktc_UShort"
        "uintArrayOf" -> "ktc_UInt"
        "ulongArrayOf" -> "ktc_ULong"
        "arrayOf" -> {
            if (init.typeArgs.isNotEmpty()) cTypeStr(typeSubst[init.typeArgs[0].name] ?: init.typeArgs[0].name)
            else {
                val elemKt = if (init.args.isNotEmpty()) inferExprType(init.args[0].expr) ?: "Int" else "Int"
                val elemKtKtc = if (init.args.isNotEmpty()) inferExprTypeKtc(init.args[0].expr) else null
                cTypeStr(elemKt)
            }
        }

        else -> return null
    }
    val args = init.args.joinToString(", ") { genExpr(it.expr) }
    val n = init.args.size
    return "${ind}$elemType ${varName}[] = {$args};\n${ind}const ktc_Int ${varName}\$len = $n;"
}

/** Check if an expression is a call to a function known to return nullable. */
internal fun CCodeGen.isNullableReturningCall(e: Expr?): Boolean {
    if (e !is CallExpr) return false
    val name = (e.callee as? NameExpr)?.name ?: return false
    return funSigs[name]?.returnType?.nullable == true
}

/** Check if a call expression returns an array type (function has $len_out parameter). */
internal fun CCodeGen.isArrayReturningCall(e: Expr?): Boolean {
    if (e !is CallExpr) return false
    val name = (e.callee as? NameExpr)?.name ?: return false
    // Check generic functions
    val genFun = genericFunDecls.find { it.name == name }
    if (genFun != null && genFun.returnType != null) {
        val typeArgNames = if (e.typeArgs.isNotEmpty()) e.typeArgs.map { resolveTypeName(it).toInternalStr }
        else return false
        val subst = genFun.typeParams.zip(typeArgNames).toMap()
        val saved = typeSubst; typeSubst = subst
        val retType = resolveTypeName(genFun.returnType).toInternalStr
        typeSubst = saved
        return isArrayType(retType)
    }
    // Check regular functions
    val sig = funSigs[name] ?: return false
    return sig.returnType != null && !sig.returnType.nullable && resolveTypeName(sig.returnType).isArrayLike
}

/** Check if an expression is a malloc/calloc/realloc call (returns nullable pointer). */
internal fun isAllocCall(e: Expr?): Boolean {
    if (e !is CallExpr) return false
    val name = (e.callee as? NameExpr)?.name ?: return false
    return name in setOf("HeapAlloc", "HeapArrayZero", "HeapArrayResize", "heapArrayOf")
}

/** Check if an expression is a malloc/calloc/realloc call with Array<T> type arg. */
internal fun CCodeGen.isAllocArrayCall(e: Expr?): Boolean {
    val inner = if (e is NotNullExpr) e.expr else e
    if (inner !is CallExpr) return false
    val name = (inner.callee as? NameExpr)?.name ?: return false
    if (name !in setOf("HeapAlloc", "HeapArrayZero", "HeapArrayResize", "heapArrayOf")) return false
    if (inner.typeArgs.isNotEmpty() && inner.typeArgs[0].name == "Array") return true
    // heapArrayOf<T>(...) produces a heap array pointer
    if (name == "heapArrayOf") return true
    if (heapAllocTargetType != null && heapAllocTargetType!!.name == "Array" && heapAllocTargetType!!.typeArgs.isNotEmpty()) return true
    return false
}

/** Extract the allocation size argument from malloc<Array<T>>(size) or realloc<Array<T>>(ptr, size).
 *  Unwraps NotNullExpr (!!). Returns the size Expr or null. */
internal fun extractAllocSize(e: Expr?): Expr? {
    val inner = if (e is NotNullExpr) e.expr else e
    if (inner !is CallExpr) return null
    // allocWith: Array/RawArray.allocWith(allocator, size) → size is 2nd arg (index 1)
    if (inner.callee is DotExpr && inner.callee.name == "allocWith" && inner.args.size >= 2) {
        return inner.args[1].expr
    }
    // resizeWith: Array.resizeWith(allocator, newSize) → size is 2nd arg (index 1)
    if (inner.callee is DotExpr && inner.callee.name == "resizeWith" && inner.args.size >= 2) {
        return inner.args[1].expr
    }
    val name = (inner.callee as? NameExpr)?.name ?: return null
    return when (name) {
        "HeapAlloc" -> inner.args.firstOrNull()?.expr
        "HeapArrayZero" -> inner.args.firstOrNull()?.expr
        "HeapArrayResize" -> inner.args.getOrNull(1)?.expr
        "heapArrayOf" -> IntLit(inner.args.size.toLong())
        else -> null
    }
}

/** Infer a TypeRef from an init expression, detecting @Ptr Array patterns from HeapAlloc. */
internal fun CCodeGen.inferInitType(init: Expr?): TypeRef {
    val inner = if (init is NotNullExpr) init.expr else init
    if (inner is CallExpr) {
        val name = (inner.callee as? NameExpr)?.name
        if (name in setOf("HeapAlloc", "HeapArrayZero", "HeapArrayResize", "heapArrayOf") && inner.typeArgs.isNotEmpty()) {
            val ta = inner.typeArgs[0]
            if (ta.name == "Array" && ta.typeArgs.isNotEmpty()) {
                return ta.copy(annotations = ta.annotations + Annotation("Ptr"))
            }
            if (ta.name == "RawArray" && ta.typeArgs.isNotEmpty()) {
                return ta.typeArgs[0].copy(annotations = ta.typeArgs[0].annotations + Annotation("Ptr"))
            }
            // heapArrayOf<Int>(1,2,3) → @Ptr Array<Int>? (heap pointer to array)
            if (name == "heapArrayOf") {
                return TypeRef("Array", nullable = true, typeArgs = listOf(ta), annotations = listOf(Annotation("Ptr")))
            }
        }
        if (name == "arrayOf" && inner.typeArgs.isNotEmpty()) {
            val ta = inner.typeArgs[0]
            val size = inner.args.size
            val sizeAnn = Annotation("Size", listOf(IntLit(size.toLong())))
            if (ta.name == "Array" && ta.typeArgs.isNotEmpty()) {
                return ta.typeArgs[0].copy(annotations = ta.typeArgs[0].annotations + sizeAnn)
            }
            return TypeRef(ta.name, typeArgs = ta.typeArgs, annotations = listOf(sizeAnn))
        }
        // allocWith returns @Ptr always
        if (inner.callee is DotExpr && inner.callee.name == "allocWith") {
            val obj = (inner.callee as DotExpr).obj
            val objName = (obj as? NameExpr)?.name ?: ""
            if (objName == "Array" || objName == "RawArray") {
                val elem = inner.typeArgs.getOrNull(0) ?: TypeRef("Int")
                if (objName == "RawArray") return elem.copy(annotations = listOf(Annotation("Ptr")))
                return TypeRef(objName, typeArgs = listOf(elem), annotations = listOf(Annotation("Ptr")))
            }
            if (classes.containsKey(objName) || genericClassDecls.containsKey(objName)) {
                val typeArgs = if (inner.typeArgs.isNotEmpty()) inner.typeArgs else emptyList()
                return TypeRef(objName, typeArgs = typeArgs, annotations = listOf(Annotation("Ptr")))
            }
        }
    }
    val initKtc = inferExprTypeKtc(init)
    return TypeRef(inferExprType(init) ?: "Int")
}

/* If a body prop is an array type, emit $self.name$len = allocSize after assignment. */
internal fun CCodeGen.emitBodyPropLenIfArray(inProp: PropertyDef) {
    val vKtcProp = resolveTypeName(inProp.typeRef)  // resolved KtcType
    if (!vKtcProp.isArrayLike) return
    if (hasSizeAnnotation(inProp.typeRef)) return
    val vFieldName = if (inProp.isPrivate) "PRIV_${inProp.name}" else inProp.name  // C field name
    val vAllocSize = extractAllocSize(inProp.initExpr)  // extracted allocation size expr
    if (vAllocSize != null) {
        impl.appendLine("    \$self.$vFieldName\$len = ${genExpr(vAllocSize)};")
    } else if (inProp.initExpr is NameExpr) {
        val vInitName = inProp.initExpr.name  // source variable name
        impl.appendLine("    \$self.$vFieldName\$len = ${vInitName}\$len;")
    }
}

/** Generate a call expression that returns an array, appending &name$len as extra arg
 *  to receive the array length through the $len_out out-parameter. */
internal fun CCodeGen.genExprWithArrayLenOut(e: Expr, varName: String): String {
    if (e !is CallExpr) return genExpr(e)
    val name = (e.callee as? NameExpr)?.name ?: return genExpr(e)
    // For generic function calls, use the mangled name and fill defaults
    val genFun = genericFunDecls.find { it.name == name }
    if (genFun != null && e.typeArgs.isNotEmpty()) {
        val typeArgNames = e.typeArgs.map { resolveTypeName(it).toInternalStr }
        val mangledName = "${name}_${typeArgNames.joinToString("_")}"
        val prevSubst = typeSubst
        typeSubst = genFun.typeParams.zip(typeArgNames).toMap()
        val filledArgs = fillDefaults(e.args, genFun.params, genFun.params.associate { it.name to it.default })
        val expandedArgs = expandCallArgs(filledArgs, genFun.params)
        typeSubst = prevSubst
        val extraArg = "&${varName}\$len"
        val allArgs = if (expandedArgs.isEmpty()) extraArg else "$expandedArgs, $extraArg"
        return "${funCName(mangledName)}($allArgs)"
    }
    // Regular function
    val cName = if (currentObject != null) "${typeFlatName(currentObject!!)}_$name" else funCName(name)
    val sig = funSigs[name]
    val filledArgs = if (sig != null) fillDefaults(e.args, sig.params, sig.params.associate { it.name to it.default }) else e.args
    val args = expandCallArgs(filledArgs, sig?.params)
    val extraArg = "&${varName}\$len"
    val allArgs = if (args.isEmpty()) extraArg else "$args, $extraArg"
    return "$cName($allArgs)"
}

/** Generate a call expression that returns a sized array (@Size(N) Array<T>),
 *  appending the varName as $out arg. The call is added as a preStmt since it returns void. */
internal fun CCodeGen.genExprWithSizedArrayOut(e: Expr, varName: String) {
    if (e !is CallExpr) return
    val name = (e.callee as? NameExpr)?.name ?: return
    val genFun = genericFunDecls.find { it.name == name }
    if (genFun != null && e.typeArgs.isNotEmpty()) {
        val typeArgNames = e.typeArgs.map { resolveTypeName(it).toInternalStr }
        val mangledName = "${name}_${typeArgNames.joinToString("_")}"
        val prevSubst = typeSubst
        typeSubst = genFun.typeParams.zip(typeArgNames).toMap()
        val filledArgs = fillDefaults(e.args, genFun.params, genFun.params.associate { it.name to it.default })
        val expandedArgs = expandCallArgs(filledArgs, genFun.params)
        typeSubst = prevSubst
        val allArgs = if (expandedArgs.isEmpty()) varName else "$expandedArgs, $varName"
        preStmts += "${funCName(mangledName)}($allArgs);"
        return
    }
    val cName = if (currentObject != null) "${typeFlatName(currentObject!!)}_$name" else funCName(name)
    val sig = funSigs[name]
    val filledArgs = if (sig != null) fillDefaults(e.args, sig.params, sig.params.associate { it.name to it.default }) else e.args
    val args = expandCallArgs(filledArgs, sig?.params)
    val allArgs = if (args.isEmpty()) varName else "$args, $varName"
    preStmts += "$cName($allArgs);"
}

// ── assignment ───────────────────────────────────────────────────

internal fun CCodeGen.emitAssign(s: AssignStmt, ind: String, method: Boolean) {

    // safe dot assignment: this?.x = value → if ($self$has) { (*$self).x = value; }
    if (s.target is SafeDotExpr) {
        val recvTypeKtc = inferExprTypeKtc(s.target.obj)
        val recvTypeCoreKtc = (recvTypeKtc as? KtcType.Nullable)?.inner ?: recvTypeKtc
        val recv = genExpr(s.target.obj)
        val recvName = (s.target.obj as? NameExpr)?.name ?: recv
        val isThis = s.target.obj is ThisExpr
        val isValueNullRecv = recvTypeKtc is KtcType.Nullable && isValueNullableKtc(recvTypeKtc)
        val guard = if (recvTypeKtc != null) nullGuardExpr(recvTypeKtc, recv, recvName, isThis) else "${recv}\$has"
        val recvVal = if (isValueNullRecv) "KTC_UNWRAP($recv)" else recv
        val fieldExpr = if (recvTypeCoreKtc is KtcType.Ptr) "$recvVal->${s.target.name}"
        else "$recvVal.${s.target.name}"
        val value = genExpr(s.value)
        flushPreStmts(ind)
        impl.appendLine("${ind}if ($guard) { $fieldExpr ${s.op} $value; }")
        return
    }

    // operator set: a[i] = v → ClassName_set(&a, i, v)
    if (s.target is IndexExpr && s.op == "=") {
        val objTypeKtc = inferExprTypeKtc(s.target.obj)                              // KtcType? object type
        val objTypeCoreKtc = (objTypeKtc as? KtcType.Nullable)?.inner ?: objTypeKtc  // KtcType? stripped Nullable
        val vSetClassInfo = classInfoFor(objTypeCoreKtc)                              // non-null if object is a class
        val vSetIfaceInfo = ifaceInfoFor(objTypeCoreKtc)                              // non-null if object is an interface
        if (vSetClassInfo != null) {
            val setMethod = vSetClassInfo.methods.find { it.name == "set" && it.isOperator }
            if (setMethod != null) {
                val recv = genExpr(s.target.obj)
                val idx = genExpr(s.target.index)
                val value = genExpr(s.value)
                flushPreStmts(ind)
                impl.appendLine("$ind${vSetClassInfo.flatName}_set(&$recv, $idx, $value);")
                return
            }
        }
        if (objTypeCoreKtc is KtcType.Ptr) {
            val baseName = objTypeCoreKtc.inner
            val baseClass = (baseName as? KtcType.User)?.baseName ?: baseName.toInternalStr
            val setMethod = classes[baseClass]?.methods?.find { it.name == "set" && it.isOperator }
            if (setMethod != null) {
                val recv = genExpr(s.target.obj)
                val idx = genExpr(s.target.index)
                val value = genExpr(s.value)
                flushPreStmts(ind)
                impl.appendLine("$ind${typeFlatName(baseClass)}_set($recv, $idx, $value);")
                return
            }
        }
        if (vSetIfaceInfo != null) {
            val setMethod = vSetIfaceInfo.methods.find { it.name == "set" && it.isOperator }
                ?: collectAllIfaceMethods(vSetIfaceInfo).find { it.name == "set" && it.isOperator }
            if (setMethod != null) {
                val recv = genExpr(s.target.obj)
                val idx = genExpr(s.target.index)
                val value = genExpr(s.value)
                flushPreStmts(ind)
                impl.appendLine("$ind$recv.vt->set(${ifaceVtableSelf(vSetIfaceInfo.name, recv)}, $idx, $value);")
                return
            }
        }
    }

    // Object / Companion property write: ensure lazy init before assignment
    if (s.target is DotExpr) {
        val vObjWriteCName = resolveDotObjCName(s.target)
        if (vObjWriteCName != null) {
            impl.appendLine("$ind${vObjWriteCName}_\$ensure_init();")
        }
    }

    val target = genLValue(s.target, method)
    val varName = (s.target as? NameExpr)?.name
    val varType = if (varName != null) lookupVar(varName) else null
    val isAnyValNullVar = false
    val varKtc = if (varType != null) parseResolvedTypeName(varType) else null
    val isAnyPtrNullVar = varKtc is KtcType.Nullable && varKtc.inner is KtcType.Ptr

    // Val reassignment check
    if (varName != null) {
        // Local variable
        if (lookupVar(varName) != null && !isMutable(varName)) {
            codegenError("Val cannot be reassigned: '$varName'")
        }
        // Top-level property
        if (varName in valTopProps) {
            codegenError("Val cannot be reassigned: '$varName'")
        }
    }
    // Class property via obj.field
    if (s.target is DotExpr) {
        val dotTarget = s.target
        val recvTypeKtc = inferExprTypeKtc(dotTarget.obj)
        val recvTypeCoreKtc = (recvTypeKtc as? KtcType.Nullable)?.inner ?: recvTypeKtc
        val className = (recvTypeCoreKtc as? KtcType.User)?.baseName
            ?: ((recvTypeCoreKtc as? KtcType.Ptr)?.inner as? KtcType.User)?.baseName
        if (className != null) {
            val ci = classes[className]
            if (ci != null) {
                val propName = dotTarget.name
                if (propName in ci.privateSetProps) {
                    codegenError("Var with private set cannot be reassigned outside its class: '$propName'")
                }
                if (ci.isValProp(propName)) {
                    codegenError("Val cannot be reassigned: '$propName'")
                }
            }
        }
    }

    when {
        // value-nullable (*<T?> / ^<T?> / &<T?>) = null → clear value, keep pointer
        isAnyValNullVar && s.value is NullLit -> {
            impl.appendLine("$ind${target}\$has = false;")
        }
        // value-nullable = value → set value
        isAnyValNullVar -> {
            val value = genExpr(s.value)
            flushPreStmts(ind)
            impl.appendLine("$ind*$target = $value;")
            impl.appendLine("$ind${target}\$has = true;")
        }
        // pointer-nullable (*<T>? / ^<T>? / &<T>?) = null → NULL pointer
        isAnyPtrNullVar && s.value is NullLit -> {
            impl.appendLine("$ind$target = NULL;")
        }
        // pointer-nullable = value → assign pointer
        isAnyPtrNullVar -> {
            val value = genExpr(s.value)
            flushPreStmts(ind)
            impl.appendLine("$ind$target ${s.op} $value;")
        }
        // Value nullable = null → Optional{NONE}
        varKtc is KtcType.Nullable && s.value is NullLit && isValueNullableKtc(varKtc) -> {
            val optType = optCTypeName(varType!!)
            impl.appendLine("$ind$target = ${optNone(optType)};")
        }
        // General case
        else -> {
            val value = genExpr(s.value)
            flushPreStmts(ind)
            val valueKtc = inferExprTypeKtc(s.value)
            if (varKtc is KtcType.Nullable && isValueNullableKtc(varKtc)
                && varName != null && isOptional(varName)
            ) {
                val optType = optCTypeName(varType!!)
                val alreadyOpt = valueKtc is KtcType.Nullable && isValueNullableKtc(valueKtc)
                if (alreadyOpt) {
                    impl.appendLine("$ind$target = $value;")
                } else {
                    impl.appendLine("$ind$target = ${optSome(optType, value)};")
                }
            } else {
                impl.appendLine("$ind$target ${s.op} $value;")
            }
            // Update array $len when assigning from malloc/realloc
            if (varKtc != null && varKtc.isArrayLike && s.op == "=") {
                val allocSize = extractAllocSize(s.value)
                if (allocSize != null) {
                    impl.appendLine("$ind${target}\$len = ${genExpr(allocSize)};")
                }
            }
        }
    }
}

// ── return ───────────────────────────────────────────────────────

internal fun CCodeGen.emitReturn(s: ReturnStmt, ind: String) {
    val endLabel = inlineEndLabel
    if (endLabel == null) {
        // Check: return with a value from a Unit (void) function
        val vRetBase = currentFnReturnBaseType()
        if (s.value != null && s.value !is NullLit && vRetBase == "Unit") {
            codegenError("Cannot return a value from a Unit function")
        }
        // Check: bare 'return' (no value) from a non-Unit non-Any function
        if (s.value == null && vRetBase != "" && vRetBase != "Unit" && vRetBase != "Any" &&
            !currentFnReturnsArray && !currentFnReturnsSizedArray && !currentFnIsMain) {
            codegenError("A 'return' expression must return a value of type '$vRetBase'")
        }
    }
    if (endLabel != null) {
        // Inside an inline body expansion: assign result (if any), then jump to end label
        val retVar = inlineReturnVar ?: ""
        if (s.value != null) {
            if (retVar.isNotEmpty()) {
                val expr = genExpr(s.value)
                flushPreStmts(ind)
                impl.appendLine("$ind$retVar = $expr;")
            } else {
                // Statement position: execute for side effects only
                val expr = genExpr(s.value)
                flushPreStmts(ind)
                if (expr.isNotEmpty()) impl.appendLine("$ind(void)($expr);")
            }
        }
        impl.appendLine("${ind}goto $endLabel;")
        return
    }
    if (currentFnReturnsNullable) {
        // Any? nullable return: uses ktc_Any with data==NULL for null (not Optional)
        if ((currentFnReturnKtcType as? KtcType.Nullable)?.inner is KtcType.Any || currentFnReturnKtcType is KtcType.Any) {
            if (s.value == null || s.value is NullLit) {
                emitDeferredBlocks(ind)
                impl.appendLine("${ind}return (ktc_Any){0};")
            } else {
                val srcType = inferExprType(s.value)?.removeSuffix("?") ?: "Int"
                val srcTypeKtc = inferExprTypeKtc(s.value)
                val typeId = getTypeId(srcType)
                val ct = cTypeStr(srcType)
                val expr = genExpr(s.value)
                flushPreStmts(ind)
                val tVal = tmp()
                impl.appendLine("$ind$ct $tVal = $expr;")
                emitDeferredBlocks(ind)
                impl.appendLine("${ind}return (ktc_Any){{$typeId}, (void*)&$tVal};")
            }
            return
        }
        val optType = currentFnOptReturnCTypeName
        if (s.value == null || s.value is NullLit) {
            emitDeferredBlocks(ind)
            impl.appendLine("${ind}return ${optNone(optType)};")
        } else {
            val srcKtc = inferExprTypeKtc(s.value)
            val alreadyOpt = srcKtc is KtcType.Nullable && isValueNullableKtc(srcKtc)
            val expr = genExpr(s.value)
            flushPreStmts(ind)
            if (deferStack.isNotEmpty()) {
                val t = tmp()
                if (alreadyOpt) {
                    impl.appendLine("$ind$optType $t = $expr;")
                    emitDeferredBlocks(ind)
                    impl.appendLine("${ind}return $t;")
                } else {
                    impl.appendLine("$ind${cTypeStr(currentFnReturnBaseType())} $t = $expr;")
                    emitDeferredBlocks(ind)
                    impl.appendLine("${ind}return ${optSome(optType, t)};")
                }
            } else {
                if (alreadyOpt) {
                    impl.appendLine("${ind}return $expr;")
                } else {
                    impl.appendLine("${ind}return ${optSome(optType, expr)};")
                }
            }
        }
    } else {
        if (s.value != null) {
            val expr = genExpr(s.value)
            flushPreStmts(ind)
            if (currentFnReturnsSizedArray) {
                impl.appendLine("${ind}memcpy(\$out, $expr, $currentFnSizedArraySize * sizeof(${currentFnSizedArrayElemType}));")
                if (deferStack.isNotEmpty()) {
                    emitDeferredBlocks(ind)
                }
                impl.appendLine("${ind}return;")
            } else if (currentFnReturnsArray) {
                // Array return: pass length through out-parameter
                impl.appendLine("$ind*\$len_out = ${expr}\$len;")
                if (deferStack.isNotEmpty()) {
                    val t = tmp()
                    impl.appendLine("$ind${cTypeStr(currentFnReturnType)} $t = $expr;")
                    emitDeferredBlocks(ind)
                    impl.appendLine("${ind}return $t;")
                } else {
                    impl.appendLine("${ind}return $expr;")
                }
            } else if (deferStack.isNotEmpty()) {
                // Evaluate return value into temp, run defers, then return
                val retType = currentFnReturnType.ifEmpty { inferExprType(s.value) ?: "Int" }
                val retTypeKtc = inferExprTypeKtc(s.value)
                val t = tmp()
                impl.appendLine("$ind${cTypeStr(retType)} $t = $expr;")
                emitDeferredBlocks(ind)
                impl.appendLine("${ind}return $t;")
            } else {
                // Auto-wrap class → interface if return type is an interface
                val exprType = inferExprType(s.value)
                val exprTypeKtc = inferExprTypeKtc(s.value)
                val retIface = currentFnReturnType
                if (retIface.isNotEmpty() && interfaces.containsKey(retIface)
                    && exprType != null && (classes.containsKey(exprType) || objects.containsKey(exprType))
                    && classInterfaces[exprType]?.contains(retIface) == true
                ) {
                    val cExprType = typeFlatName(exprType)
                    val cIface = typeFlatName(retIface)
                    val impls = interfaceImplementors[retIface] ?: emptyList()
                    val dataName = ifaceDataName(exprType)
                    val fieldPath = when {
                        impls.size <= 1 -> ".$dataName"
                        else -> ".data.$dataName"
                    }
                    val t = tmp()
                    impl.appendLine("$ind${cIface} $t;")
                    impl.appendLine("$ind$t$fieldPath = $expr;")
                    impl.appendLine("$ind$t.vt = &${cExprType}_${retIface}_vt;")
                    impl.appendLine("${ind}return $t;")
                } else {
                    // Auto-wrap Any return → ktc_Any trampoline
                    if (currentFnReturnKtcType is KtcType.Any) {
                        val srcTy = inferExprType(s.value)?.removeSuffix("?") ?: "Int"
                        val srcTyKtc = inferExprTypeKtc(s.value)
                        val typeId = getTypeId(srcTy)
                        val ct = cTypeStr(srcTy)
                        val tVal = tmp()
                        impl.appendLine("$ind$ct $tVal = $expr;")
                        impl.appendLine("${ind}return (ktc_Any){{$typeId}, (void*)&$tVal};")
                    } else {
                        impl.appendLine("${ind}return $expr;")
                    }
                }
            }
        } else {
            emitDeferredBlocks(ind)
            impl.appendLine(if (currentFnIsMain) "${ind}return 0;" else "${ind}return;")
        }
    }
}

// ── expression statement (may be println, method call, etc.) ─────

internal fun CCodeGen.emitExprStmt(s: ExprStmt, ind: String, method: Boolean) {
    val e = s.expr
    // if / when used as statements
    if (e is IfExpr) {
        emitIfStmt(e, ind, method); return
    }
    if (e is WhenExpr) {
        emitWhenStmt(e, ind, method); return
    }
    // Inline function call — expand body at call site
    if (e is CallExpr && e.callee is NameExpr) {
        val name = e.callee.name
        val inlineCandidates = inlineFunDecls[name]
        val inlineDecl = when {
            inlineCandidates == null -> null
            inlineCandidates.size == 1 -> inlineCandidates[0]
            else -> {
                val exact = inlineCandidates.find { it.params.size == e.args.size }
                exact ?: inlineCandidates.minByOrNull { kotlin.math.abs(it.params.size - e.args.size) }
            }
        }
        if (inlineDecl != null) {
            // Set up typeSubst for generic inline functions
            val vSavedSubst = typeSubst
            if (inlineDecl.typeParams.isNotEmpty()) {
                val vSubst = mutableMapOf<String, String>()
                for ((i, param) in inlineDecl.params.withIndex()) {
                    if (i >= e.args.size) break
                    val argType = inferExprType(e.args[i].expr)?.removeSuffix("?") ?: continue
                    val argTypeKtc = inferExprTypeKtc(e.args[i].expr)
                    matchTypeParam(param.type, argType, inlineDecl.typeParams.toSet(), vSubst)
                }
                if (vSubst.isNotEmpty()) typeSubst = vSubst
            }
            emitInlineCall(inlineDecl, e.args, ind, method)
            typeSubst = vSavedSubst
            return
        }
        // Active lambda call (inside an inline body expansion)
        val lambda = activeLambdas[name]
        if (lambda != null) {
            emitLambdaCall(lambda, e.args, ind); return
        }
    }
    // Inline extension function call — expand body at call site (callee is DotExpr or SafeDotExpr)
    if (e is CallExpr && (e.callee is DotExpr || e.callee is SafeDotExpr)) {
        val isSafe = e.callee is SafeDotExpr
        val methodName = if (isSafe) e.callee.name else (e.callee as DotExpr).name
        val inlineExt = inlineExtFunDecls[methodName]
        if (inlineExt != null) {
            val recvObj = if (isSafe) e.callee.obj else (e.callee as DotExpr).obj
            val recvExpr = genExpr(recvObj)
            val recvKtType = inferExprType(recvObj)?.removeSuffix("?")
            val recvKtTypeKtc = inferExprTypeKtc(recvObj)
            // Set up typeSubst for generic inline extension functions
            val vSavedSubst = typeSubst
            if (inlineExt.typeParams.isNotEmpty()) {
                val vArgTypes = e.args.map { inferExprType(it.expr) } // concrete arg types
                val vArgTypesKtc = e.args.map { inferExprTypeKtc(it.expr) }
                typeSubst = inferInlineFunSubst(inlineExt, recvKtType, vArgTypes)
            }
            if (isSafe) {
                // Safe call: guard the inline block with a null check
                val recvName = (recvObj as? NameExpr)?.name
                val recvType = if (recvName != null) lookupVar(recvName) else null
                val recvTypeKtc = if (recvType != null) parseResolvedTypeName(recvType) else null
                val guard = if (recvTypeKtc is KtcType.Nullable && isValueNullableKtc(recvTypeKtc))
                    "$recvExpr.tag == ktc_SOME"
                else
                    "$recvExpr != NULL"
                impl.appendLine("${ind}if ($guard) {")
                emitInlineCall(inlineExt, e.args, "$ind    ", method, receiverExpr = recvExpr, receiverType = recvKtType)
                impl.appendLine("$ind}")
            } else {
                emitInlineCall(inlineExt, e.args, ind, method, receiverExpr = recvExpr, receiverType = recvKtType)
            }
            typeSubst = vSavedSubst
            return
        }
    }
    // println / print as statements — avoid GCC statement-expressions
    if (e is CallExpr && e.callee is NameExpr) {
        val name = e.callee.name
        if (name == "println") {
            emitPrintlnStmt(e.args, ind); return
        }
        if (name == "print") {
            emitPrintStmt(e.args, ind); return
        }
    }
    // Heap/Ptr/Value .set(val) as statement — only when class has no own set() method
    if (e is CallExpr && e.callee is DotExpr && e.callee.name == "set") {
        val recvTypeKtc = inferExprTypeKtc(e.callee.obj)
        val recvTypeCoreKtc = (recvTypeKtc as? KtcType.Nullable)?.inner ?: recvTypeKtc
        val baseClass = (recvTypeCoreKtc as? KtcType.Ptr)?.inner?.let { it as? KtcType.User }?.baseName
        if (baseClass != null && classes[baseClass]?.methods?.any { it.name == "set" } != true) {
            val recv = genExpr(e.callee.obj)
            val argStr = e.args.joinToString(", ") { genExpr(it.expr) }
            flushPreStmts(ind)
            impl.appendLine("$ind*$recv = $argStr;")
            return
        }
    }
    // Safe method call as statement: a?.method() → if (guard) { method(a); }
    if (e is CallExpr && e.callee is SafeDotExpr) {
        val safe = e.callee
        val recvName = (safe.obj as? NameExpr)?.name
        val recvType = if (recvName != null) lookupVar(recvName) else null
        val recvTypeKtc = if (recvType != null) parseResolvedTypeName(recvType) else null
        if (recvType != null) {

            // Pointer-nullable (Heap<T>?, Ptr<T>?, Value<T>?, raw T*?) → NULL check
            val guard = when (recvTypeKtc) {
                is KtcType.Nullable if recvTypeKtc.inner is KtcType.Ptr ->
                    "$recvName != NULL"
                // Value-nullable Optional
                is KtcType.Nullable if isValueNullableKtc(recvTypeKtc) ->
                    "$recvName.tag == ktc_SOME"
                // Heap<T?>/Ptr<T?>/Value<T?> or other nullable
                is KtcType.Nullable -> "${recvName}\$has"
                else -> null
            }

            if (guard != null) {
                val dotExpr = DotExpr(safe.obj, safe.name)
                val callExpr = genMethodCall(dotExpr, e.args)
                flushPreStmts(ind)
                impl.appendLine("${ind}if ($guard) { $callExpr; }")
                return
            }
        }
    }
    val expr = genExpr(e)
    flushPreStmts(ind)
    impl.appendLine("$ind$expr;")
}

/* Expand an inline function call as a C block at the call site.
Non-lambda args become const-initialized locals; lambda args are
registered in activeLambdas so their call sites expand inline too. */
/* Expand an inline function body at the call site.
receiverExpr: C expression for `this` inside an extension fun body.
resultVar: when non-null, `return` inside the body assigns here (value position).
           when null, the body is expanded for side effects (statement position).
A unique goto label is emitted after the block so that `return` inside the body
jumps to the end without exiting the enclosing C function. */
internal fun CCodeGen.emitInlineCall(
    decl: FunDecl,
    callArgs: List<Arg>,
    ind: String,
    method: Boolean,
    receiverExpr: String? = null,
    receiverType: String? = null,
    resultVar: String? = null
) {
    val body = decl.body ?: return
    val labelName = "\$end_ir_${inlineCounter++}"
    // Build comment with template types (clear typeSubst so type params appear unsubstituted)
    val vSavedSubstForComment = typeSubst
    typeSubst = emptyMap()
    val sig = buildString {
        if (receiverExpr != null) append("$receiverExpr.")
        append(decl.name)
        append("(")
        callArgs.forEachIndexed { idx, a ->
            if (idx > 0) append(", ")
            val p = decl.params.getOrNull(idx)
            val pName = p?.name ?: "arg$idx"
            if (a.expr is LambdaExpr) {
                val pType = p?.type?.let { resolveTypeName(it).toInternalStr } ?: "?"
                append("$pName = $pType")
            } else {
                val exprStr = when (a.expr) {
                    is NameExpr -> a.expr.name
                    is ThisExpr -> "this"
                    is IntLit -> a.expr.value.toString()
                    is StrLit -> "\"${a.expr.value}\""
                    is BoolLit -> a.expr.value.toString()
                    else -> "..."
                }
                append("$pName = $exprStr")
            }
        }
        append(")")
        decl.returnType?.let { append(": ${resolveTypeName(it).toInternalStr}") }
    }
    typeSubst = vSavedSubstForComment
    impl.appendLine("$ind/* inline $sig */")
    impl.appendLine("$ind{")
    pushScope()
    val savedLambdas = activeLambdas
    val newLambdas = activeLambdas.toMutableMap()
    val savedRetVar = inlineReturnVar
    val savedEndLabel = inlineEndLabel
    inlineReturnVar = resultVar ?: ""
    inlineEndLabel = labelName

    // Set up `this` substitution for extension function receivers
    val savedThis = lambdaParamSubst["\$this"]
    val savedThisType = lambdaParamTypes["\$this"]
    if (receiverExpr != null) lambdaParamSubst["\$this"] = receiverExpr
    if (receiverType != null) lambdaParamTypes["\$this"] = receiverType

    // Bind each parameter: lambda params go into activeLambdas, value params become locals
    callArgs.forEachIndexed { i, arg ->
        val param = decl.params.getOrNull(i) ?: return@forEachIndexed
        val expr = arg.expr
        if (expr is LambdaExpr) {
            val funcParams = param.type.funcParams ?: emptyList()
            val paramTypes = funcParams.map { resolveTypeName(it).toInternalStr }
            val retType = param.type.funcReturn?.let { resolveTypeName(it).toInternalStr }
            newLambdas[param.name] = ActiveLambda(expr, paramTypes, retType)
        } else {
            val resolvedKtc = resolveTypeName(param.type)
            val isValueNullable = param.type.nullable && !param.type.annotations.any { it.name == "Ptr" }
            val (cTypeName, scopeKtc) = if (isValueNullable) {
                markOptional(param.name)
                val innerKtc = resolveTypeName(param.type.copy(nullable = false))
                optCTypeName(innerKtc.toInternalStr) to KtcType.Nullable(innerKtc)
            } else {
                cTypeStr(resolvedKtc) to resolvedKtc
            }
            val cVal = genExpr(expr)
            flushPreStmts(ind)
            impl.appendLine("$ind    $cTypeName ${param.name} = $cVal;")
            defineVarKtc(param.name, scopeKtc)
        }
    }
    activeLambdas = newLambdas

    emitBlock(body, ind, method)

    impl.appendLine("$ind$labelName:;")
    activeLambdas = savedLambdas
    inlineReturnVar = savedRetVar
    inlineEndLabel = savedEndLabel
    if (receiverExpr != null) {
        if (savedThis != null) lambdaParamSubst["\$this"] = savedThis else lambdaParamSubst.remove("\$this")
    }
    if (receiverType != null) {
        if (savedThisType != null) lambdaParamTypes["\$this"] = savedThisType else lambdaParamTypes.remove("\$this")
    }
    popScope()
    // Smart cast propagation: if a nullable param was null-checked and bound to
    // a simple NameExpr, narrow the argument in the caller scope (e.g. checkNotNull(x)).
    val propCasts = mutableListOf<Pair<String, String>>()
    for ((i, arg) in callArgs.withIndex()) {
        val param = decl.params.getOrNull(i) ?: continue
        if (arg.expr !is NameExpr) continue
        val argName = arg.expr.name
        if (isMutable(argName)) continue  // var cannot be smart-cast
        val paramKtc = resolveTypeName(param.type.copy(nullable = false))
        val retKtc = decl.returnType?.let { resolveTypeName(it) }
        if (param.type.nullable && retKtc != null
            && retKtc.toInternalStr == paramKtc.toInternalStr) {
            defineVar(argName, retKtc.toInternalStr)
            propCasts.add(argName to retKtc.toInternalStr)
        }
    }
    impl.appendLine("$ind}")
    for ((name, narrowedType) in propCasts) {
        impl.appendLine("$ind// smart-cast: '$name' narrowed to '$narrowedType'")
    }
}

/* Expand a lambda call inside an inline body (statement position).
Lambda params are substituted via lambdaParamSubst rather than declared as C variables,
avoiding name-collision issues when lambda params shadow enclosing inline params. */
internal fun CCodeGen.emitLambdaCall(active: ActiveLambda, callArgs: List<Arg>, ind: String) {
    val savedSubst = lambdaParamSubst.toMap()
    val savedTypes = lambdaParamTypes.toMap()
    active.expr.params.forEachIndexed { i, pName ->
        val arg = callArgs.getOrNull(i)
        if (arg != null) {
            lambdaParamSubst[pName] = genExpr(arg.expr)
            // For ThisExpr args inside inline bodies, inferExprType returns null (no C $self scope);
            // fall back to lambdaParamTypes["\$this"] which was set by emitInlineCall's receiverType
            val t = (if (arg.expr is ThisExpr) lambdaParamTypes["\$this"] else null)
                ?: inferExprType(arg.expr)
                ?: active.paramTypes.getOrElse(i) { "" }
            if (t.isNotEmpty()) lambdaParamTypes[pName] = t
        }
    }
    for (stmt in active.expr.body) emitStmt(stmt, ind)
    lambdaParamSubst.clear(); lambdaParamSubst.putAll(savedSubst)
    lambdaParamTypes.clear(); lambdaParamTypes.putAll(savedTypes)
}

/** Emit println as C statements. */
internal fun CCodeGen.emitPrintlnStmt(args: List<Arg>, ind: String) {
    if (args.isEmpty()) {
        impl.appendLine("${ind}printf(\"\\n\");"); return
    }
    emitPrintStmtInner(args, ind, newline = true)
}

internal fun CCodeGen.emitPrintStmt(args: List<Arg>, ind: String) {
    if (args.isEmpty()) return
    emitPrintStmtInner(args, ind, newline = false)
}

internal fun CCodeGen.emitPrintStmtInner(args: List<Arg>, ind: String, newline: Boolean) {
    val arg = args[0].expr
    val nl = if (newline) "\\n" else ""

    // String template
    if (arg is StrTemplateExpr) {
        if (templateNeedsStrBuf(arg)) {
            emitPrintTemplateViaStrBuf(arg, ind, newline)
        } else {
            val printfStr = genPrintfFromTemplate(arg, nl)
            flushPreStmts(ind)
            impl.appendLine("$ind$printfStr;")
        }
        return
    }

    val tKtc = inferExprTypeKtc(arg) ?: KtcType.Prim(KtcType.PrimKind.Int)
    val tKtcCore = (tKtc as? KtcType.Nullable)?.inner ?: tKtc
    var expr = genExpr(arg)
    flushPreStmts(ind)

    // Nullable → if (tag == ktc_SOME) print(value) else print("null")
    if (tKtc is KtcType.Nullable) {
        val isValNull = isValueNullableKtc(tKtc)
        val isPtrNull = !isValNull && tKtc.inner is KtcType.Ptr
        // Materialize only when complex to avoid repeated evaluation
        if (!isSimpleCExpr(expr)) {
            val vTmp = tmp()
            impl.appendLine("${ind}${tKtc.toCType()} $vTmp = ($expr);")
            expr = vTmp
        }
        val hasExpr = when {
            isValNull -> "$expr.tag == ktc_SOME"
            isPtrNull -> "$expr != NULL"
            else -> "${expr}\$has"
        }
        val valExpr = if (isValNull) "$expr.value" else expr
        // data class → use StrBuf toString with null guard
        val dataClass = (tKtcCore as? KtcType.User)?.takeIf { it.kind == KtcType.UserKind.DataClass }?.baseName
            ?: ((tKtcCore as? KtcType.Ptr)?.inner as? KtcType.User)?.takeIf { it.kind == KtcType.UserKind.DataClass }?.baseName
        if (dataClass != null) {
            val buf = tmp()
            val isIndirect = tKtcCore is KtcType.Ptr
            val recv = if (isIndirect) valExpr else "&($valExpr)"
            val maxLen = toStringMaxLen(dataClass)
            if (maxLen != null && maxLen <= 512) {
                impl.appendLine("${ind}char ${buf}[$maxLen];")
                impl.appendLine("${ind}ktc_StrBuf ${buf}_sb = {${buf}, 0, $maxLen};")
                impl.appendLine("${ind}if ($hasExpr) { ${typeFlatName(dataClass)}_toString($recv, &${buf}_sb); }")
                impl.appendLine("${ind}else { ktc_core_sb_append_str(&${buf}_sb, ktc_core_str(\"null\")); }")
                impl.appendLine("${ind}printf(\"%.*s$nl\", (ktc_Int)${buf}_sb.len, ${buf}_sb.ptr);")
            } else {
                impl.appendLine("${ind}ktc_StrBuf ${buf}_sb = {NULL, 0, 0};")
                impl.appendLine("${ind}if ($hasExpr) { ${typeFlatName(dataClass)}_toString($recv, &${buf}_sb); }")
                impl.appendLine("${ind}char* $buf = (char*)ktc_core_alloca(${buf}_sb.len + 1);")
                impl.appendLine("${ind}${buf}_sb = (ktc_StrBuf){${buf}, 0, ${buf}_sb.len + 1};")
                impl.appendLine("${ind}if ($hasExpr) { ${typeFlatName(dataClass)}_toString($recv, &${buf}_sb); }")
                impl.appendLine("${ind}else { ktc_core_sb_append_str(&${buf}_sb, ktc_core_str(\"null\")); }")
                impl.appendLine("${ind}printf(\"%.*s$nl\", (ktc_Int)${buf}_sb.len, ${buf}_sb.ptr);")
            }
        } else {
            val fmt = printfFmt(tKtcCore) + nl
            val a = printfArg(valExpr, tKtcCore)
            impl.appendLine("${ind}if ($hasExpr) { printf(\"$fmt\", $a); }")
            impl.appendLine("${ind}else { printf(\"null$nl\"); }")
        }
        return
    }

    // data class → toString into StrBuf, then printf (fixed buffer if bounded)
    if (tKtcCore is KtcType.User && tKtcCore.kind == KtcType.UserKind.DataClass) {
        val buf = tmp()
        val vTmp = tmp()
        val baseName = tKtcCore.baseName
        val maxLen = toStringMaxLen(baseName)
        if (maxLen != null && maxLen <= 512) {
            impl.appendLine("${ind}${tKtcCore.toCType()} $vTmp = ($expr);")
            impl.appendLine("${ind}char ${buf}[$maxLen];")
            impl.appendLine("${ind}ktc_StrBuf ${buf}_sb = {${buf}, 0, $maxLen};")
            impl.appendLine("${ind}${typeFlatName(baseName)}_toString(&$vTmp, &${buf}_sb);")
            impl.appendLine("${ind}printf(\"%.*s$nl\", (ktc_Int)${buf}_sb.len, ${buf}_sb.ptr);")
        } else {
            impl.appendLine("${ind}${tKtcCore.toCType()} $vTmp = ($expr);")
            impl.appendLine("${ind}ktc_StrBuf ${buf}_sb = {NULL, 0, 0};")
            impl.appendLine("${ind}${typeFlatName(baseName)}_toString(&$vTmp, &${buf}_sb);")
            impl.appendLine("${ind}char* $buf = (char*)ktc_core_alloca(${buf}_sb.len + 1);")
            impl.appendLine("${ind}${buf}_sb = (ktc_StrBuf){${buf}, 0, ${buf}_sb.len + 1};")
            impl.appendLine("${ind}${typeFlatName(baseName)}_toString(&$vTmp, &${buf}_sb);")
            impl.appendLine("${ind}printf(\"%.*s$nl\", (ktc_Int)${buf}_sb.len, ${buf}_sb.ptr);")
        }
        return
    }

    // Heap/Ptr/Value pointer to data class → pass pointer directly
    val indirectBase = (tKtcCore as? KtcType.Ptr)?.inner?.let { it as? KtcType.User }?.baseName
    if (indirectBase != null && classes[indirectBase]?.isData == true) {
        val buf = tmp()
        val maxLen = toStringMaxLen(indirectBase)
        if (maxLen != null && maxLen <= 512) {
            impl.appendLine("${ind}char ${buf}[$maxLen];")
            impl.appendLine("${ind}ktc_StrBuf ${buf}_sb = {${buf}, 0, $maxLen};")
            impl.appendLine("${ind}${typeFlatName(indirectBase)}_toString($expr, &${buf}_sb);")
            impl.appendLine("${ind}printf(\"%.*s$nl\", (ktc_Int)${buf}_sb.len, ${buf}_sb.ptr);")
        } else {
            impl.appendLine("${ind}ktc_StrBuf ${buf}_sb = {NULL, 0, 0};")
            impl.appendLine("${ind}${typeFlatName(indirectBase)}_toString($expr, &${buf}_sb);")
            impl.appendLine("${ind}char* $buf = (char*)ktc_core_alloca(${buf}_sb.len + 1);")
            impl.appendLine("${ind}${buf}_sb = (ktc_StrBuf){${buf}, 0, ${buf}_sb.len + 1};")
            impl.appendLine("${ind}${typeFlatName(indirectBase)}_toString($expr, &${buf}_sb);")
            impl.appendLine("${ind}printf(\"%.*s$nl\", (ktc_Int)${buf}_sb.len, ${buf}_sb.ptr);")
        }
        return
    }

    // Non-data class/object/interface → use toString()
    if (tKtcCore is KtcType.User) {
        val str = genToStringKtc(expr, tKtcCore)
        flushPreStmts(ind)
        val tmpStr = tmp()
        impl.appendLine("${ind}ktc_String $tmpStr = $str;")
        impl.appendLine("${ind}printf(\"%.*s$nl\", (ktc_Int)${tmpStr}.len, ${tmpStr}.ptr);")
        return
    }

    // String: printf(".*s") needs .len + .ptr — materialize if complex
    if (tKtcCore is KtcType.Str) {
        val safeExpr = if (!isSimpleCExpr(expr)) { val vTmp = tmp(); impl.appendLine("${ind}ktc_String $vTmp = ($expr);"); vTmp } else expr
        impl.appendLine("${ind}printf(\"%.*s$nl\", (ktc_Int)($safeExpr).len, ($safeExpr).ptr);")
        return
    }

    val fmt = printfFmt(tKtcCore) + nl
    val a = printfArg(expr, tKtcCore)
    impl.appendLine("${ind}printf(\"$fmt\", $a);")
}

/** Check if a template contains data class or nullable expressions (need StrBuf). */
internal fun CCodeGen.templateNeedsStrBuf(tmpl: StrTemplateExpr): Boolean {
    return tmpl.parts.any { part ->
        part is ExprPart && run {
            val tKtc = inferExprTypeKtc(part.expr)
            tKtc is KtcType.User || tKtc is KtcType.Nullable || tKtc is KtcType.Ptr
        }
    }
}

/** Emit a println/print of a complex string template via ktc_StrBuf. */
internal fun CCodeGen.emitPrintTemplateViaStrBuf(tmpl: StrTemplateExpr, ind: String, newline: Boolean) {
    val buf = tmp()

    data class PartData(val lit: String? = null, val sbAppend: String? = null)

    val parts = mutableListOf<PartData>()
    for (part in tmpl.parts) {
        when (part) {
            is LitPart -> {
                val last = parts.lastOrNull()
                if (last?.lit != null) parts[parts.lastIndex] = PartData(lit = last.lit + part.text)
                else parts += PartData(lit = part.text)
            }

            is ExprPart -> {
                val tKtc = inferExprTypeKtc(part.expr) ?: KtcType.Prim(KtcType.PrimKind.Int)
                val expr = genExpr(part.expr)
                parts += PartData(sbAppend = genSbAppendKtc("&${buf}_sb", expr, tKtc))
            }
        }
    }
    val nl = if (newline) "\\n" else ""
    val maxLen = templateMaxLen(tmpl)
    if (maxLen != null && maxLen <= 512) {
        impl.appendLine("${ind}char ${buf}[$maxLen];")
        impl.appendLine("${ind}ktc_StrBuf ${buf}_sb = {${buf}, 0, $maxLen};")
        for (p in parts) {
            when {
                p.lit != null -> impl.appendLine("${ind}ktc_core_sb_append_str(&${buf}_sb, ktc_core_str(\"${escapeStr(p.lit)}\"));")
                p.sbAppend != null -> {
                    flushPreStmts(ind)
                    impl.appendLine("$ind${p.sbAppend}")
                }
            }
        }
        impl.appendLine("${ind}printf(\"%.*s$nl\", (ktc_Int)${buf}_sb.len, ${buf}_sb.ptr);")
        return
    }
    // First pass: count
    impl.appendLine("${ind}ktc_StrBuf ${buf}_sb = {NULL, 0, 0};")
    for (p in parts) {
        when {
            p.lit != null -> impl.appendLine("${ind}ktc_core_sb_append_str(&${buf}_sb, ktc_core_str(\"${escapeStr(p.lit)}\"));")
            p.sbAppend != null -> {
                flushPreStmts(ind)
                impl.appendLine("$ind${p.sbAppend}")
            }
        }
    }
    // Allocate + second pass
    impl.appendLine("${ind}char* $buf = (char*)ktc_core_alloca(${buf}_sb.len + 1);")
    impl.appendLine("${ind}${buf}_sb = (ktc_StrBuf){${buf}, 0, ${buf}_sb.len + 1};")
    for (p in parts) {
        when {
            p.lit != null -> impl.appendLine("${ind}ktc_core_sb_append_str(&${buf}_sb, ktc_core_str(\"${escapeStr(p.lit)}\"));")
            p.sbAppend != null -> impl.appendLine("$ind${p.sbAppend}")
        }
    }
    impl.appendLine("${ind}printf(\"%.*s$nl\", (ktc_Int)${buf}_sb.len, ${buf}_sb.ptr);")
}

// ── if (as statement) ────────────────────────────────────────────

/**
 * Detect smart-cast candidates from a condition expression.
 * Returns a list of (varName, narrowedType) pairs for variables whose type is narrowed.
 * Handles value nullable ("T?") and pointer nullable ("T*?") via != null checks,
 * and type narrowing via `is T` / `this is T` checks.
 */
internal fun CCodeGen.extractSmartCasts(cond: Expr): List<Pair<String, String>> {
    val casts = mutableListOf<Pair<String, String>>()
    fun trySmartCast(name: String) {
        if (isMutable(name)) return  // var cannot be smart-cast
        val typeKtc = lookupVarKtc(name)
        if (typeKtc is KtcType.Nullable) {
            casts.add(name to typeKtc.inner.toInternalStr)
        }
    }

    fun tryThisSmartCast() {
        val type = currentExtRecvType
        if (type != null) {
            val typeKtc = parseResolvedTypeName(type)
            if (typeKtc is KtcType.Nullable)
                casts.add("\$self" to typeKtc.inner.toInternalStr)
        }
    }

    fun tryCastTo(name: String, targetType: String) {
        if (isMutable(name)) return
        val currentKtc = lookupVarKtc(name)
        // Don't narrow pointer types (Any* etc.) — they need original type for ->data dereference.
        // But DO narrow trampoline types (Any) — genName handles .data dereference after narrowing.
        if (currentKtc != null && currentKtc.toInternalStr != targetType
            && currentKtc !is KtcType.Ptr
        ) {
            casts.add(name to targetType)
        }
    }

    fun tryThisCastTo(targetType: String) {
        val currentType = currentExtRecvType ?: return
        if (currentType != targetType) {
            casts.add("\$self" to targetType)
        }
    }

    // x != null
    when (cond) {
        is BinExpr if cond.op == "!=" && cond.right is NullLit && cond.left is NameExpr ->
            trySmartCast(cond.left.name)
        // null != x
        is BinExpr if cond.op == "!=" && cond.left is NullLit && cond.right is NameExpr ->
            trySmartCast(cond.right.name)
        // this != null
        is BinExpr if cond.op == "!=" && cond.right is NullLit && cond.left is ThisExpr ->
            tryThisSmartCast()
        // null != this
        is BinExpr if cond.op == "!=" && cond.left is NullLit && cond.right is ThisExpr ->
            tryThisSmartCast()
        // x is Type
        is IsCheckExpr if !cond.negated && cond.expr is NameExpr ->
            tryCastTo(cond.expr.name, resolveTypeName(cond.type).toInternalStr)
        // this is Type
        is IsCheckExpr if !cond.negated && cond.expr is ThisExpr ->
            tryThisCastTo(resolveTypeName(cond.type).toInternalStr)
        // a && b → smart-cast both sides
        is BinExpr if cond.op == "&&" -> {
            casts.addAll(extractSmartCasts(cond.left))
            casts.addAll(extractSmartCasts(cond.right))
        }

        else -> {}
    }

    return casts
}

/** Detect smart-casts for the else branch (condition that proves null in the then branch, or !is in then branch). */
internal fun CCodeGen.extractElseSmartCasts(cond: Expr): List<Pair<String, String>> {
    val casts = mutableListOf<Pair<String, String>>()
    fun trySmartCast(name: String) {
        if (isMutable(name)) return
        val typeKtc = lookupVarKtc(name)
        if (typeKtc is KtcType.Nullable) {
            casts.add(name to typeKtc.inner.toInternalStr)
        }
    }

    fun tryCastTo(name: String, targetType: String) {
        if (isMutable(name)) return
        val currentKtc = lookupVarKtc(name)
        // Don't narrow pointer types
        if (currentKtc != null && currentKtc.toInternalStr != targetType
            && currentKtc !is KtcType.Ptr
        ) {
            casts.add(name to targetType)
        }
    }

    fun tryThisCastTo(targetType: String) {
        val currentType = currentExtRecvType ?: return
        if (currentType != targetType) {
            casts.add("\$self" to targetType)
        }
    }

    fun tryThisSmartCastElse() {
        val type = currentExtRecvType
        if (type != null) {
            val typeKtc = parseResolvedTypeName(type)
            if (typeKtc is KtcType.Nullable)
                casts.add("\$self" to typeKtc.inner.toInternalStr)
        }
    }

    // x == null → in else branch, x is non-null
    when (cond) {
        is BinExpr if cond.op == "==" && cond.right is NullLit && cond.left is NameExpr ->
            trySmartCast(cond.left.name)

        is BinExpr if cond.op == "==" && cond.left is NullLit && cond.right is NameExpr ->
            trySmartCast(cond.right.name)

        // this == null → in else branch, $self is non-null
        is BinExpr if cond.op == "==" && cond.right is NullLit && cond.left is ThisExpr ->
            tryThisSmartCastElse()

        // null == this → same
        is BinExpr if cond.op == "==" && cond.left is NullLit && cond.right is ThisExpr ->
            tryThisSmartCastElse()

        // x !is Type → in else branch, x IS Type
        is IsCheckExpr if cond.negated && cond.expr is NameExpr ->
            tryCastTo(cond.expr.name, resolveTypeName(cond.type).toInternalStr)

        // this !is Type → in else branch, $self IS Type
        is IsCheckExpr if cond.negated && cond.expr is ThisExpr ->
            tryThisCastTo(resolveTypeName(cond.type).toInternalStr)

        else -> {}
    }

    return casts
}

internal fun CCodeGen.emitIfStmt(e: IfExpr, ind: String, method: Boolean) {
    // Warn: constant boolean condition
    if (e.cond is BoolLit) {
        val vVal = e.cond.value
        codegenWarning("Condition is always ${if (vVal) "true" else "false"}")
    }
    impl.appendLine("${ind}if (${genExpr(e.cond)}) {")
    // Smart cast: narrow types in then-branch
    val thenCasts = extractSmartCasts(e.cond)
    if (thenCasts.isNotEmpty()) {
        for ((name, narrowedType) in thenCasts) {
            impl.appendLine("$ind    // smart-cast: '$name' narrowed to '$narrowedType'")
        }
        pushScope()
        for ((name, nonNullType) in thenCasts) defineVar(name, nonNullType)
    }
    emitBlock(e.then, ind, method)
    if (thenCasts.isNotEmpty()) popScope()

    if (e.els != null) {
        // check for else-if chain
        val single = e.els.stmts.singleOrNull()
        if (single is ExprStmt && single.expr is IfExpr) {
            impl.appendLine("$ind} else ")
            // Apply else-branch smart-casts before recursing (e.g. x == null → x non-null)
            val elseCasts = extractElseSmartCasts(e.cond)
            if (elseCasts.isNotEmpty()) {
                for ((name, narrowedType) in elseCasts) {
                    impl.appendLine("$ind    // smart-cast: '$name' narrowed to '$narrowedType'")
                }
                pushScope()
                for ((name, type) in elseCasts) defineVar(name, type)
            }
            emitIfStmt(single.expr, ind, method)
            if (elseCasts.isNotEmpty()) popScope()
            return
        }
        impl.appendLine("$ind} else {")
        // Smart cast: narrow nullable types in else-branch (x == null → else has x non-null)
        val elseCasts = extractElseSmartCasts(e.cond)
        if (elseCasts.isNotEmpty()) {
            for ((name, narrowedType) in elseCasts) {
                impl.appendLine("$ind    // smart-cast: '$name' narrowed to '$narrowedType'")
            }
            pushScope()
            for ((name, nonNullType) in elseCasts) defineVar(name, nonNullType)
        }
        emitBlock(e.els, ind, method)
        if (elseCasts.isNotEmpty()) popScope()
    }
    impl.appendLine("$ind}")
}

// ── when (as statement) ──────────────────────────────────────────

internal fun CCodeGen.emitWhenStmt(e: WhenExpr, ind: String, method: Boolean) {
    // ThisExpr subject maps to $self; NameExpr subject maps to its variable name
    val subjName = when (e.subject) {
        is NameExpr -> e.subject.name
        is ThisExpr -> "\$self"
        else -> null
    }
    for ((bi, br) in e.branches.withIndex()) {
        if (br.conds == null) {
            // else branch
            impl.appendLine("${ind}else {")
        } else {
            val condStr = br.conds.joinToString(" || ") { genWhenCond(it, e.subject) }
            val keyword = if (bi == 0) "if" else "else if"
            impl.appendLine("$ind$keyword ($condStr) {")
        }
        // Smart cast: narrow subject type for `is` branches
        val vNarrowedKtc = if (br.conds != null && subjName != null && !isMutable(subjName)) {
            val isCond = br.conds.find { it is IsCond && !it.negated } as? IsCond
            if (isCond != null) resolveTypeName(isCond.type) else null
        } else null
        val narrowedType = vNarrowedKtc?.toInternalStr  // string form for comment
        if (vNarrowedKtc != null) {
            impl.appendLine("$ind    // smart-cast: '$subjName' narrowed to '$narrowedType'")
            pushScope()
            defineVarKtc(subjName!!, vNarrowedKtc)
        }
        emitBlock(br.body, ind, method)
        if (narrowedType != null) popScope()
        impl.appendLine("$ind}")
    }
}

internal fun CCodeGen.genWhenCond(c: WhenCond, subject: Expr?): String {
    val subj = if (subject != null) genExpr(subject) else ""
    return when (c) {
        is ExprCond -> if (subject != null) "$subj == ${genExpr(c.expr)}" else genExpr(c.expr)
        is InCond -> {
            val range = c.expr
            val neg = if (c.negated) "!" else ""
            if (range is BinExpr && range.op == "..") {
                "${neg}($subj >= ${genExpr(range.left)} && $subj <= ${genExpr(range.right)})"
            } else "${neg}(/* in ${genExpr(range)} */)"   // fallback
        }

        is IsCond -> {
            val targetKtc = resolveTypeName(c.type)
            val target = targetKtc.toInternalStr
            val exprKtc = if (subject != null) inferExprTypeKtc(subject) else null
            val exprKtcCore = (exprKtc as? KtcType.Nullable)?.inner ?: exprKtc
            val memOp = if (exprKtcCore is KtcType.Ptr) "->" else "."
            val check = if (classes.containsKey(target)) {
                "$subj${memOp}__base.typeId == ${typeFlatName(target)}_TYPE_ID"
            } else if (interfaces.containsKey(target)) {
                val impls = classInterfaces.filter { (_, ifaces) -> target in ifaces }.keys
                if (impls.isEmpty()) "false"
                else impls.joinToString(" || ") { "$subj${memOp}__base.typeId == ${typeFlatName(it)}_TYPE_ID" }
            } else if (targetKtc.isArrayLike) {
                if (exprKtcCore != null && exprKtcCore.isArrayLike) {
                    if (exprKtcCore.toInternalStr == target) "true" else "false"
                } else {
                    val arrayId = getTypeId(target)
                    "($subj${memOp}__array_type_id == $arrayId)"
                }
            } else if (targetKtc !is KtcType.User || targetKtc.kind != KtcType.UserKind.Class) {
                val isSourceNullable = exprKtc is KtcType.Nullable
                if (exprKtcCore != null && exprKtcCore !is KtcType.Ptr) {
                    if (exprKtcCore.toInternalStr == target) {
                        if (isSourceNullable && isValueNullableKtc(exprKtc)) "($subj.tag == ktc_SOME)"
                        else if (isSourceNullable) "($subj != NULL)"
                        else "true"
                    } else "false"
                } else {
                    val typeId = getTypeId(target)
                    "($subj${memOp}__base.typeId == $typeId)"
                }
            } else {
                "/* is ${c.type.name} */ true"
            }
            if (c.negated) "!($check)" else "($check)"
        }
    }
}

// ── for ──────────────────────────────────────────────────────────

internal fun CCodeGen.emitFor(s: ForStmt, ind: String, method: Boolean) {
    loopDepth++
    val iter = s.iter
    // Unwrap "step" wrapper: (rangeExpr step N)
    val step: String?
    val rangeExpr: Expr
    if (iter is BinExpr && iter.op == "step") {
        step = genExpr(iter.right)
        rangeExpr = iter.left
    } else {
        step = null
        rangeExpr = iter
    }
    // for (i in a..b)   inclusive range
    when (rangeExpr) {
        is BinExpr if rangeExpr.op == ".." -> {
            val inc = if (step != null) "${s.varName} += $step" else "${s.varName}++"
            impl.appendLine("${ind}for (ktc_Int ${s.varName} = ${genExpr(rangeExpr.left)}; ${s.varName} <= ${genExpr(rangeExpr.right)}; $inc) {")
            pushScope(); defineVar(s.varName, "Int")
            emitBlock(s.body, ind, method)
            popScope()
            impl.appendLine("$ind}")
        }
        // for (i in a until b)  or  for (i in a..<b)
        is BinExpr if (rangeExpr.op == "until" || rangeExpr.op == "..<") -> {
            val inc = if (step != null) "${s.varName} += $step" else "${s.varName}++"
            impl.appendLine("${ind}for (ktc_Int ${s.varName} = ${genExpr(rangeExpr.left)}; ${s.varName} < ${genExpr(rangeExpr.right)}; $inc) {")
            pushScope(); defineVar(s.varName, "Int")
            emitBlock(s.body, ind, method)
            popScope()
            impl.appendLine("$ind}")
        }
        // for (i in a downTo b)
        is BinExpr if rangeExpr.op == "downTo" -> {
            val dec = if (step != null) "${s.varName} -= $step" else "${s.varName}--"
            impl.appendLine("${ind}for (ktc_Int ${s.varName} = ${genExpr(rangeExpr.left)}; ${s.varName} >= ${genExpr(rangeExpr.right)}; $dec) {")
            pushScope(); defineVar(s.varName, "Int")
            emitBlock(s.body, ind, method)
            popScope()
            impl.appendLine("$ind}")
        }
        // for (item in array/collection)  — iterate over elements
        else -> {
            val arrType = inferExprType(rangeExpr)
            val arrTypeKtc = inferExprTypeKtc(rangeExpr)
            val iterInfo = findOperatorIterator(arrType)
            if (iterInfo != null) {
                // Iterator-based: val $it = obj.iterator(); while($it.hasNext()) { val item = $it.next(); ... }
                val (iterClass, iterCType, elemKtType, isPointer) = iterInfo
                val arrExpr = genExpr(rangeExpr)
                flushPreStmts(ind)
                val iterVar = tmp()
                val selfArg = if (isPointer) arrExpr else "&$arrExpr"
                // For interface types, dispatch through vtable
                val isPtrIface = isPointer && arrType != null && run {
                    val ktc = parseResolvedTypeName(arrType)
                    val inner = (ktc as? KtcType.Ptr)?.inner as? KtcType.User
                    inner != null && inner.kind == KtcType.UserKind.Interface
                }
                if (arrType != null && interfaces.containsKey(arrType)) {
                    impl.appendLine("$ind$iterCType $iterVar = $arrExpr.vt->iterator(${ifaceVtableSelf(arrType, arrExpr)});")
                } else if (isPtrIface) {
                    val arrKtc = parseResolvedTypeName(arrType!!)
                    val ifaceName = ((arrKtc as KtcType.Ptr).inner as KtcType.User).baseName
                    val cIface = typeFlatName(ifaceName)
                    impl.appendLine("$ind$iterCType $iterVar = ((${cIface}_vt*)$arrExpr.vt)->iterator($arrExpr.obj);")
                } else {
                    val baseClass = if (isPointer) {
                        val arrKtc = parseResolvedTypeName(arrType!!)
                        ((arrKtc as? KtcType.Ptr)?.inner as? KtcType.User)?.baseName ?: arrType
                    } else arrType!!
                    impl.appendLine("$ind$iterCType $iterVar = ${typeFlatName(baseClass)}_iterator($selfArg);")
                }
                val isIfaceIter = interfaces.containsKey(iterClass)
                if (isIfaceIter) {
                    impl.appendLine("${ind}while (${iterVar}.vt->hasNext(${ifaceVtableSelf(iterClass, iterVar)})) {")
                    val elemCType = cTypeStr(elemKtType)
                    impl.appendLine("$ind    $elemCType ${s.varName} = ${iterVar}.vt->next(${ifaceVtableSelf(iterClass, iterVar)});")
                } else {
                    impl.appendLine("${ind}while (${typeFlatName(iterClass)}_hasNext(&$iterVar)) {")
                    val elemCType = cTypeStr(elemKtType)
                    impl.appendLine("$ind    $elemCType ${s.varName} = ${typeFlatName(iterClass)}_next(&$iterVar);")
                }
                pushScope(); defineVar(s.varName, elemKtType)
                emitBlock(s.body, ind, method)
                popScope()
                impl.appendLine("$ind}")
            } else {
                // Array: use $len / trampoline size and direct indexing
                val arrExpr = genExpr(rangeExpr)
                val idx = tmp()
                val elemType = if (arrTypeKtc != null) arrayElementCTypeKtc(arrTypeKtc) else "ktc_Int"
                val arrOrigName = (rangeExpr as? NameExpr)?.name
                val sizeExpr = if (arrOrigName != null && arrOrigName in trampolinedParams)
                    "$arrOrigName.size" else "${arrExpr}\$len"
                impl.appendLine("${ind}for (ktc_Int $idx = 0; $idx < $sizeExpr; $idx++) {")
                impl.appendLine("$ind    $elemType ${s.varName} = ${arrExpr}[$idx];")
                pushScope(); defineVar(s.varName, if (arrTypeKtc != null) arrayElementKtTypeKtc(arrTypeKtc) else "Int")
                emitBlock(s.body, ind, method)
                popScope()
                impl.appendLine("$ind}")
            }
        }
    }
    loopDepth--
}

/**
 * Check if a type has an `operator fun iterator()` method.
 * Returns (iteratorClassName, iteratorCType, elementKtType, isPointer) or null.
 */
internal fun CCodeGen.findOperatorIterator(type: String?): IteratorInfo? {
    if (type == null) return null
    // Direct class
    if (classes.containsKey(type)) {
        val vIterCI = classes[type]!!                                                  // ClassInfo for the iterable type
        val iterMethod = vIterCI.methods.find { it.name == "iterator" && it.isOperator }
        if (iterMethod?.returnType != null) {
            val iterType = resolveMethodReturnType(type, iterMethod.returnType)
            if (classes.containsKey(iterType)) {
                val vIterTypeCI = classes[iterType]!!                                  // ClassInfo for the iterator type
                val nextMethod = vIterTypeCI.methods.find { it.name == "next" }
                if (nextMethod?.returnType != null) {
                    val elemType = resolveMethodReturnType(iterType, nextMethod.returnType)
                    return IteratorInfo(iterType, vIterTypeCI.flatName, elemType, false)
                }
            } else if (interfaces.containsKey(iterType)) {
                // Iterator returns an interface — use interface type with vtable dispatch
                val vIterTypeII = interfaces[iterType]!!                               // IfaceInfo for the iterator interface
                val allMethods = collectAllIfaceMethods(vIterTypeII)
                val nextMethod = allMethods.find { it.name == "next" && it.isOperator }
                if (nextMethod?.returnType != null) {
                    val elemType = resolveMethodReturnType(iterType, nextMethod.returnType)
                    return IteratorInfo(iterType, vIterTypeII.flatName, elemType, false)
                }
            }
        }
    }
    // Heap/Ptr/Value class
    val indirectBase = (parseResolvedTypeName(type) as? KtcType.Ptr)?.inner?.let { it as? KtcType.User }?.baseName
    if (indirectBase != null && classes.containsKey(indirectBase)) {
        val vIndirectCI = classes[indirectBase]!!                                      // ClassInfo for the heap class
        val iterMethod = vIndirectCI.methods.find { it.name == "iterator" && it.isOperator }
        if (iterMethod?.returnType != null) {
            val iterType = resolveMethodReturnType(indirectBase, iterMethod.returnType)
            if (classes.containsKey(iterType)) {
                val vIndirectIterCI = classes[iterType]!!                              // ClassInfo for the iterator type
                val nextMethod = vIndirectIterCI.methods.find { it.name == "next" }
                if (nextMethod?.returnType != null) {
                    val elemType = resolveMethodReturnType(iterType, nextMethod.returnType)
                    return IteratorInfo(iterType, vIndirectIterCI.flatName, elemType, true)
                }
            }
        }
    }
    // Ptr to interface (e.g. @Ptr List<T> → List_Int*)
    val isMonoIface = indirectBase != null && genericIfaceDecls.keys.any { indirectBase.startsWith(it + "_") }
    if ((indirectBase != null && interfaces.containsKey(indirectBase)) || isMonoIface) {
        val ifaceName = if (isMonoIface) indirectBase!! else indirectBase!!
        val vIndirectIfaceII = interfaces[ifaceName]
        val allIfaceMethods = if (vIndirectIfaceII != null) collectAllIfaceMethods(vIndirectIfaceII)
            else {
                // For monomorphized ifaces, look up methods from the template + concrete vtable info
                val tmplName = genericIfaceDecls.keys.first { ifaceName.startsWith(it + "_") }
                val tmplIID = interfaces[tmplName]
                if (tmplIID != null) collectAllIfaceMethods(tmplIID) else emptyList()
            }
        val iterMethod = allIfaceMethods.find { it.name == "iterator" && it.isOperator }
        if (iterMethod?.returnType != null) {
            val iterType = resolveMethodReturnType(indirectBase, iterMethod.returnType)
            if (classes.containsKey(iterType)) {
                val vIndirectIterCI = classes[iterType]!!
                val nextMethod = vIndirectIterCI.methods.find { it.name == "next" }
                if (nextMethod?.returnType != null) {
                    val elemType = resolveMethodReturnType(iterType, nextMethod.returnType)
                    return IteratorInfo(iterType, vIndirectIterCI.flatName, elemType, true)
                }
            } else if (interfaces.containsKey(iterType)) {
                val vIndirectIterII = interfaces[iterType]!!
                val allIterMethods = collectAllIfaceMethods(vIndirectIterII)
                val nextMethod = allIterMethods.find { it.name == "next" && it.isOperator }
                if (nextMethod?.returnType != null) {
                    val elemType = resolveMethodReturnType(iterType, nextMethod.returnType)
                    return IteratorInfo(iterType, vIndirectIterII.flatName, elemType, true)
                }
            }
        }
    }
    // Interface
    if (interfaces.containsKey(type)) {
        val vIfaceII = interfaces[type]!!                                               // IfaceInfo for the iterable interface
        val allMethods = collectAllIfaceMethods(vIfaceII)
        val iterMethod = allMethods.find { it.name == "iterator" && it.isOperator }
        if (iterMethod?.returnType != null) {
            val iterType = resolveMethodReturnType(type, iterMethod.returnType)
            if (classes.containsKey(iterType)) {
                val vIfaceIterCI = classes[iterType]!!                                 // ClassInfo for the iterator type
                val nextMethod = vIfaceIterCI.methods.find { it.name == "next" }
                if (nextMethod?.returnType != null) {
                    val elemType = resolveMethodReturnType(iterType, nextMethod.returnType)
                    return IteratorInfo(iterType, vIfaceIterCI.flatName, elemType, false)
                }
            }
        }
    }
    return null
}
