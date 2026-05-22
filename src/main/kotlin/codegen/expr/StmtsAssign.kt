package com.bitsycore.ktc.codegen.expr

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.codegen.*
import com.bitsycore.ktc.codegen.emit.collectAllIfaceMethods
import com.bitsycore.ktc.codegen.emit.ifaceDataName
import com.bitsycore.ktc.types.KtcType

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

    val target = genLValue(s.target)
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
        // Nullable array = null → {NULL, 0} struct + clear $has flag
        varKtc is KtcType.Nullable && varKtc.inner.isArrayLike && s.value is NullLit -> {
            val vElemC      = arrayElementCTypeKtc(varKtc.inner)
            val vVarArrType = varArrTypeName(vElemC)
            impl.appendLine("$ind$target = ($vVarArrType){NULL, 0};")
            if (varName != null) impl.appendLine("$ind${varName}\$has = false;")
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
            // Array struct assignment: .len already part of ktc_VarArr_T — no separate update needed
        }
    }
}

// ── return ───────────────────────────────────────────────────────

/* Store initExpr in a temp, run deferred blocks, then return the temp. */
private fun CCodeGen.emitDeferredReturn(ind: String, varType: String, initExpr: String) {
    val t = tmp()
    impl.appendLine("$ind$varType $t = $initExpr;")
    emitDeferredBlocks(ind)
    impl.appendLine("${ind}return $t;")
}

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
            !currentFnReturnsArray && !currentFnReturnsSizedArray) {
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
                val vElemCStr   = cTypeStr(currentFnSizedArrayElemType!!)
                val vStructType = sizedArrayCTypeName(vElemCStr, currentFnSizedArraySize)
                if (expr in arrayOfSizedStructVars) {
                    // Optimization: genArrayOfExpr already emitted a ktc_Array_T_N struct — return it directly.
                    if (deferStack.isNotEmpty()) {
                        emitDeferredReturn(ind, vStructType, expr)
                    } else {
                        impl.appendLine("${ind}return $expr;")
                    }
                } else {
                    // General path: copy raw array data into struct and return by value.
                    val vSrcKtc = inferExprTypeKtc(s.value)
                    // Trampolined @Size params: local$name is already a raw T* pointer — skip .ptr extraction
                    val vPtrSrc = if (s.value is NameExpr && s.value.name in trampolinedParams) expr
                        else arrayDataPtr(expr, vSrcKtc)
                    if (deferStack.isNotEmpty()) {
                        val vT = tmp()
                        impl.appendLine("${ind}$vStructType $vT;")
                        impl.appendLine("${ind}memcpy($vT.arr, $vPtrSrc, $currentFnSizedArraySize * sizeof($vElemCStr));")
                        emitDeferredBlocks(ind)
                        impl.appendLine("${ind}return $vT;")
                    } else {
                        impl.appendLine("${ind}{ $vStructType \$r; memcpy(\$r.arr, $vPtrSrc, $currentFnSizedArraySize * sizeof($vElemCStr)); return \$r; }")
                    }
                }
            } else if (currentFnReturnsSizedString) {
                // Direct struct return: copy ktc_String into ktc_String_N and return by value
                val vStructType = sizedStringCTypeName(currentFnSizedStringSize)
                if (deferStack.isNotEmpty()) {
                    val vT = tmp()
                    impl.appendLine("${ind}$vStructType $vT;")
                    impl.appendLine("${ind}memcpy($vT.buf, ($expr).ptr, ($expr).len * sizeof(ktc_Char)); $vT.len = ($expr).len;")
                    emitDeferredBlocks(ind)
                    impl.appendLine("${ind}return $vT;")
                } else {
                    impl.appendLine("${ind}{ $vStructType \$r; memcpy(\$r.buf, ($expr).ptr, ($expr).len * sizeof(ktc_Char)); \$r.len = ($expr).len; return \$r; }")
                }
            } else if (currentFnReturnsArray) {
                // Array return: return ktc_VarArr_T directly
                if (deferStack.isNotEmpty()) {
                    val vArrKtc  = currentFnReturnKtcType
                    val vArrElem = vArrKtc?.asArr?.elem ?: ((vArrKtc as? KtcType.Ptr)?.inner as? KtcType.Arr)?.elem
                    val vRetType = if (vArrElem != null) varArrTypeName(cTypeStr(vArrElem)) else cTypeStr(currentFnReturnType)
                    emitDeferredReturn(ind, vRetType, expr)
                } else {
                    impl.appendLine("${ind}return $expr;")
                }
            } else if (deferStack.isNotEmpty()) {
                // Evaluate return value into temp, run defers, then return
                val retType = currentFnReturnType.ifEmpty { inferExprType(s.value) ?: "Int" }
                emitDeferredReturn(ind, cTypeStr(retType), expr)
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
                    val fieldPath = if (impls.isEmpty()) ".$dataName" else ".data.$dataName"
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
            impl.appendLine("${ind}return;")
        }
    }
}
