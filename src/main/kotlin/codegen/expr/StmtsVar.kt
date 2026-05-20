package com.bitsycore.ktc.codegen.expr

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.ast.Annotation
import com.bitsycore.ktc.codegen.*
import com.bitsycore.ktc.codegen.emit.collectAllIfaceMethods
import com.bitsycore.ktc.codegen.emit.ifaceDataName
import com.bitsycore.ktc.types.KtcType

// ── var / val ────────────────────────────────────────────────────

/*
Returns the static element count of an array-init expression, or null if unknown at transpile time.
Handles: literal arrayOf / intArrayOf / etc., function calls with a @Size(N) return type,
and method calls to copyOf(N) where N is a literal (explicit truncation — no warning needed).
*/
internal fun CCodeGen.inferInitArraySize(inInit: Expr?): Int? {
    if (inInit == null) return null
    // Local variable whose array size was recorded when it was declared.
    if (inInit is NameExpr) return lookupArraySize(inInit.name)
    if (inInit !is CallExpr) return null
    // Method call: arr.copyOf(N) with literal N — explicit size, suppress unsized warning.
    if (inInit.callee is DotExpr && (inInit.callee as DotExpr).name == "copyOf") {
        val vArg = inInit.args.firstOrNull()?.expr
        if (vArg is IntLit)  return vArg.value.toInt()
        if (vArg is LongLit) return vArg.value.toInt()
        return null  // dynamic size — still unknown
    }
    val vCallee = (inInit.callee as? NameExpr)?.name ?: return null
    // Literal arrayOf / *arrayOf — size equals argument count.
    if (vCallee in setOf(
            "arrayOf", "intArrayOf", "longArrayOf", "floatArrayOf", "doubleArrayOf",
            "booleanArrayOf", "charArrayOf", "byteArrayOf", "shortArrayOf",
            "uintArrayOf", "ulongArrayOf", "ubyteArrayOf", "ushortArrayOf"
        )) return inInit.args.size
    // IntArray(N) / LongArray(N) / etc. with a literal size — constant-size allocation.
    if (vCallee in setOf(
            "IntArray", "LongArray", "FloatArray", "DoubleArray",
            "BooleanArray", "CharArray", "ByteArray", "ShortArray", "Array"
        )) {
        val vArg = inInit.args.firstOrNull()?.expr
        if (vArg is IntLit)  return vArg.value.toInt()
        if (vArg is LongLit) return vArg.value.toInt()
        return null  // dynamic size
    }
    // Named function call — look up @Size(N) on its return type.
    return funSigs[vCallee]?.returnType?.getSizeAnnotation()
}

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

    // Size compatibility check for @Size(N) array assignments.
    if (s.type != null && s.init != null && s.type.isSizedArray()) {
        val vTargetSize = s.type.getSizeAnnotation()
        if (vTargetSize != null) {
            val vInitSize = inferInitArraySize(s.init) // null = unknown at transpile time
            if (vInitSize != null && vInitSize > vTargetSize)
                error("Cannot assign @Size($vInitSize) array to @Size($vTargetSize) variable '${s.name}': source has more elements than the target (would truncate). Use .copyOf($vTargetSize) to truncate explicitly.")
            if (vInitSize == null) {
                System.err.println("WARNING [$currentSourceFile]: Assigning array of unknown compile-time size to @Size($vTargetSize) variable '${s.name}' — applying implicit .copyOf($vTargetSize) to guarantee bounds safety. Use .copyOf($vTargetSize) explicitly to suppress this warning.")
                // Implicitly apply .copyOf(N) so the stored slice is always exactly N elements.
                val vSyntheticInit = CallExpr(
                    callee = DotExpr(obj = s.init, name = "copyOf"),
                    args   = listOf(Arg(expr = IntLit(vTargetSize.toLong())))
                )
                emitVarDecl(s.copy(init = vSyntheticInit), ind)
                return
            }
        }
    }

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
    // Record inferred literal array size so downstream @Size(N) checks can resolve it.
    if (vKtcCore.isArrayLike) {
        val vInferredSize = inferInitArraySize(s.init) ?: s.type?.getSizeAnnotation()
        if (vInferredSize != null) defineArraySize(s.name, vInferredSize)
    }
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
    if (sig.returnType == null || sig.returnType.nullable) return false
    if (sig.returnType.isSizedArray()) return false  // sized arrays use struct-return ABI
    return resolveTypeName(sig.returnType).isArrayLike
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
            val obj = inner.callee.obj
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
    if (inProp.typeRef.hasSizeAnnotation()) return
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
