package com.bitsycore.ktc.codegen.statement

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.codegen.*
import com.bitsycore.ktc.codegen.expression.genExpr
import com.bitsycore.ktc.codegen.expression.isCPassthroughCall
import com.bitsycore.ktc.types.KtcType

// ── var / val ─────────────────────────────────────────────────────
// Core var declaration emitter. Array-init helpers live in VarHelpers.kt.

/* `val (a, b, ...) = expr` desugaring: evaluate [expr] once into a tmp, then bind each
   named slot to the corresponding positional ctor-param of the receiver class. */
internal fun CCodeGen.emitDestructuringDecl(s: DestructuringDeclStmt, ind: String) {
	val tmp  = "\$d${tmp().removePrefix("$")}"     // unique stable name (tmp() returns "$N")
	val type = inferExprType(s.init)
		?: codegenError("Cannot destructure: failed to infer type of init expression")
	val core = type.removeSuffix("?")
	// Resolve positional field names. Pair/Triple use first/second/third; user data classes
	// use their ctor params in declaration order.
	val fields: List<String> = when {
		core == "Pair" || core.startsWith("Pair_") ->
			listOf("first", "second").take(s.names.size)
		core == "Triple" || core.startsWith("Triple_") ->
			listOf("first", "second", "third").take(s.names.size)
		else -> {
			val ci = classes[core]
				?: codegenError("Cannot destructure: '$core' is not a known data class")
			val ctorProps = ci.ctorProps
			if (s.names.size > ctorProps.size) {
				codegenError("Destructuring '$core' requires at most ${ctorProps.size} names, got ${s.names.size}")
				}
			ctorProps.take(s.names.size).map { it.name }
			}
		}
	// Emit the tmp binding, then one VarDeclStmt per named slot.
	emitVarDecl(VarDeclStmt(tmp, null, s.init, mutable = false), ind)
	for ((idx, n) in s.names.withIndex()) {
		if (n == "_") continue          // skip discarded slots
		val accessor = DotExpr(NameExpr(tmp), fields[idx])
		emitVarDecl(VarDeclStmt(n, null, accessor, s.mutable), ind)
		}
	}


/* Emit nullable VarArr deep-copy for a general expression:
allocas a stack buffer, memcpys from the source temp, declares the var and its $has flag. */
private fun CCodeGen.emitNullableArrayCopyWithTmp(
    ind:         String,
    varName:     String,
    srcExpr:     String,
    vVarArrType: String,
    elemCType:   String,
    mutComment:  String
    ) {
    val vSrcTmp   = "${varName}_src"
    val vDataName = "${varName}_data"
    impl.appendLine("${ind}$vVarArrType $vSrcTmp = $srcExpr;")
    impl.appendLine("${ind}$elemCType* $vDataName = ($elemCType*)ktc_core_alloca(sizeof($elemCType) * $vSrcTmp.len);")
    impl.appendLine("${ind}memcpy($vDataName, $vSrcTmp.ptr, sizeof($elemCType) * $vSrcTmp.len);")
    impl.appendLine("$ind$mutComment$vVarArrType $varName = {$vDataName, $vSrcTmp.len};")
    impl.appendLine("${ind}bool ${varName}\$has = ($vSrcTmp.ptr != NULL);")
    }

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
    if (inInit.callee is DotExpr && inInit.callee.name == "copyOf") {
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
    // c.init() or c.SDL_FRect() → bare C declaration, no initializer
    if (s.init != null && isCPassthroughCall(s.init) && s.init is CallExpr && s.init.args.isEmpty()) {
        val vName = (s.init.callee as DotExpr).name
        if (vName == "init" || vName in cOpaqueTypes) {
            val vKtc = if (s.type != null) resolveTypeName(s.type) else KtcType.Prim(KtcType.PrimKind.Int)
            val ct = cTypeStr(vKtc.toInternalStr)
            val mut = if (s.mutable) "" else "const "
            defineVar(s.name, LocalVar(ktc = vKtc, mutable = s.mutable))
            impl.appendLine("$ind$mut$ct ${s.name};")
            return
        }
    }
    val vKtc = if (s.type != null) resolveTypeName(s.type) else parseResolvedTypeName(inferExprType(s.init) ?: "Int") // KtcType (for C type emission)
    val vKtcKtc = inferExprTypeKtc(s.init)
    val vKtcCore = vKtc.stripNullable
    val tRaw = vKtc.toInternalStr                                                                    // string type (for structural checks — retained during migration)
    val inferredNullable = s.type == null && vKtc is KtcType.Nullable
    // Strip ? suffix for nullable types; it gets added back at defineVar and optCTypeName
    val t = if (inferredNullable) tRaw.removeSuffix("?") else tRaw

    // Pointer↔value boundary: require explicit .ptr()/.value() when crossing.
    if (s.type != null && s.init != null) checkPtrValueBoundary(s.type, vKtc, vKtcKtc, s.init, "variable '${s.name}'")

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

    // Is this a pointer type? (Ref<T> adds * suffix)
    // Only user-class pointers (Vec2*), not typed-array pointers (IntArray which is Ptr<Arr<Int>>)
    val isPointer = vKtcCore is KtcType.Ptr && vKtcCore.inner !is KtcType.Arr

    val typeIsNullable = s.type?.isEffectivelyNullable() == true

    // Nullable pointer (Ref<T?>): can be NULL
    val isPtrNullable = isPointer &&
            (typeIsNullable || s.init is NullLit || inferredNullable)

    // Value nullable (T? without pointer): uses Optional struct
    val isValueNullable = when {
        isPointer -> false
        vKtcCore is KtcType.Func -> false
        vKtcCore.isArrayLike -> false
        vKtcCore is KtcType.Any -> false
        else -> typeIsNullable || s.init is NullLit || isNullableReturningCall(s.init) || inferredNullable
    }

    // Nullable array (Array<T>?): uses VarArr struct, null = .ptr == NULL
    val isNullableArray = isArrayType(t) && !isPointer &&
            (typeIsNullable || s.init is NullLit || inferredNullable)

    // Nullable Any: trampoline, null = data == NULL
    val isAnyNullable = vKtcCore is KtcType.Any &&
            (typeIsNullable || s.init is NullLit || inferredNullable)

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
        val arrayInit = tryArrayOfInit(s.name, s.init, vKtc, ind, s.mutable)
        if (arrayInit != null) {
            impl.appendLine(arrayInit)
            // Emit $has for nullable array variables so safe-calls work
            val isNullableArray = (s.type?.isEffectivelyNullable() == true || inferredNullable) && vKtcCore.isArrayLike && !isPtrNullable
            if (isNullableArray) {
                impl.appendLine("${ind}bool ${s.name}\$has = true;")
            }
            return
        }

        when {
            // ── Nullable pointer (Ref<T?>): can be NULL ──
            isPtrNullable -> {
                if (s.init is NullLit) {
                    impl.appendLine("$ind$mutComment$ct ${s.name} /* nullable */ = NULL;")
                } else {
                    val expr = genExpr(s.init)
                    flushPreStmts(ind)
                    impl.appendLine("$ind$mutComment$ct ${s.name} /* nullable */ = $expr;")
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
            // ── Nullable array (Array<T>?) — stored as ktc_VarArr_T + bool $has ──
            isNullableArray -> {
                val elemCType   = arrayElementCTypeKtc(vKtcCore)
                val vVarArrType = varArrTypeName(elemCType)
                if (s.init is NullLit) {
                    impl.appendLine("$ind$mutComment$vVarArrType ${s.name} = {NULL, 0};")
                    impl.appendLine("${ind}bool ${s.name}\$has = false;")
                } else if (s.init is DotExpr && s.init.name == "buffer") {
                    val dotInit     = s.init
                    val dotRecvType = inferExprType(dotInit.obj)
                    if (dotRecvType == "ktc_StrBuf" || dotRecvType == "StringBuffer") {
                        val recvExpr = genExpr(dotInit.obj)
                        val expr     = genExpr(s.init)
                        flushPreStmts(ind)
                        impl.appendLine("$ind$mutComment$vVarArrType ${s.name} = {$expr, $recvExpr.cap};")
                        impl.appendLine("${ind}bool ${s.name}\$has = ($expr != NULL);")
                    } else {
                        val expr = genExpr(s.init)
                        flushPreStmts(ind)
                        emitNullableArrayCopyWithTmp(ind, s.name, expr, vVarArrType, elemCType, mutComment)
                    }
                } else if (s.init is NameExpr) {
                    val vSrcName  = s.init.name
                    val vDataName = "${s.name}_data"
                    flushPreStmts(ind)
                    impl.appendLine("${ind}$elemCType* $vDataName = ($elemCType*)ktc_core_alloca(sizeof($elemCType) * $vSrcName.len);")
                    impl.appendLine("${ind}memcpy($vDataName, $vSrcName.ptr, sizeof($elemCType) * $vSrcName.len);")
                    impl.appendLine("$ind$mutComment$vVarArrType ${s.name} = {$vDataName, $vSrcName.len};")
                    impl.appendLine("${ind}bool ${s.name}\$has = ($vSrcName.ptr != NULL);")
                } else {
                    val expr = genExpr(s.init)
                    flushPreStmts(ind)
                    emitNullableArrayCopyWithTmp(ind, s.name, expr, vVarArrType, elemCType, mutComment)
                }
            }
            // ── Nullable Any (trampoline, null = data == NULL) ──
            isAnyNullable -> {
                if (s.init is NullLit) {
                    impl.appendLine("$ind$mutComment$ct ${s.name} = (ktc_Any){0};")
                } else {
                    val initType = inferExprType(s.init)?.removeSuffix("?") ?: "Int"
                    val typeId = getTypeId(initType)
                    val initCT = cTypeStr(initType)
                    val expr = genExpr(s.init)
                    flushPreStmts(ind)
                    val tVal = tmp()
                    impl.appendLine("$ind$initCT $tVal = $expr;")
                    impl.appendLine("$ind$mutComment$ct ${s.name} = (ktc_Any){$typeId, (void*)&$tVal};")
                }
            }
            // ── Non-nullable ──
            else -> {
                // Interface variable initialized from implementing class → auto-wrap
                if (interfaces.containsKey(t)) {
                    val initType = inferExprType(s.init)
                    if (initType != null && (classes.containsKey(initType) || objects.containsKey(initType)) && classInterfaces[initType]?.contains(t) == true) {
                        val isObj = objects.containsKey(initType)
                        if (isObj && (s.type == null || !s.type.isRefType())) {
                            currentStmtLine = s.line
                            codegenError("Object '${initType}' must be stored as Ref. Use: val ${s.name}: Ref<$t> = ${initType}")
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
                // Array-returning function call: function returns ktc_VarArr_T directly
                if (vKtcCore.isArrayLike && isArrayReturningCall(s.init)) {
                    val vElemC      = arrayElementCTypeKtc(vKtcCore)
                    val vVarArrType = varArrTypeName(vElemC)
                    val expr        = genExpr(s.init)
                    flushPreStmts(ind)
                    impl.appendLine("$ind$mutComment$qual$vVarArrType ${s.name} = $expr;")
                    return
                }
                val expr = genExprWithAllocTarget(s.init, s.type)
                flushPreStmts(ind)
                // Stack array: deep copy from source (value semantics); result is ktc_VarArr_T
                if (vKtcCore is KtcType.Arr && s.init !is NullLit) {
                    val elemCType   = arrayElementCTypeKtc(vKtcCore)
                    val vVarArrType = varArrTypeName(elemCType)
                    val vDataName   = "${s.name}_data"
                    if (s.init is NameExpr) {
                        val vSrcName = s.init.name
                        impl.appendLine("${ind}$elemCType* $vDataName = ($elemCType*)ktc_core_alloca(sizeof($elemCType) * $vSrcName.len);")
                        impl.appendLine("${ind}memcpy($vDataName, $vSrcName.ptr, sizeof($elemCType) * $vSrcName.len);")
                        impl.appendLine("${ind}$mutComment$qual$vVarArrType ${s.name} = {$vDataName, $vSrcName.len};")
                    } else {
                        val vTmpName = "${s.name}_tmp"
                        impl.appendLine("${ind}$vVarArrType $vTmpName = $expr;")
                        impl.appendLine("${ind}$elemCType* $vDataName = ($elemCType*)ktc_core_alloca(sizeof($elemCType) * $vTmpName.len);")
                        impl.appendLine("${ind}memcpy($vDataName, $vTmpName.ptr, sizeof($elemCType) * $vTmpName.len);")
                        impl.appendLine("${ind}$mutComment$qual$vVarArrType ${s.name} = {$vDataName, $vTmpName.len};")
                    }
                } else {
                    // Heap array (Ptr<Arr>): copy the ktc_VarArr_T struct (reference semantics)
                    val vHeapArrType = if (vKtcCore is KtcType.Ptr && vKtcCore.inner is KtcType.Arr) {
                        varArrTypeName(arrayElementCTypeKtc(vKtcCore))
                    } else null
                    // Auto-wrap init into ktc_Any trampoline when variable is typed Any
                    if (vKtc is KtcType.Any && s.init !is NullLit) {
                        val initType = inferExprType(s.init)?.removeSuffix("?") ?: "Int"
                        val typeId   = getTypeId(initType)
                        val initCT      = cTypeStr(initType)
                        val tVal        = tmp()
                        impl.appendLine("$ind$initCT $tVal = $expr;")
                        impl.appendLine("$ind$mutComment$qual$ct ${s.name} = (ktc_Any){$typeId, (void*)&$tVal};")
                    } else if (vHeapArrType != null) {
                        impl.appendLine("$ind$mutComment$qual$vHeapArrType ${s.name} = $expr;")
                    } else {
                        impl.appendLine("$ind$mutComment$qual$ct ${s.name} = $expr;")
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
                val vElemC      = arrayElementCTypeKtc(vKtcCore)
                val vVarArrType = varArrTypeName(vElemC)
                impl.appendLine("$ind$mutComment$vVarArrType ${s.name} = {NULL, 0};")
                impl.appendLine("${ind}bool ${s.name}\$has = false;")
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
