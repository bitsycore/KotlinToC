package com.bitsycore.ktc.codegen.expression

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.codegen.*
import com.bitsycore.ktc.codegen.emit.collectAllIfaceMethods
import com.bitsycore.ktc.codegen.statement.emitInlineCall
import com.bitsycore.ktc.codegen.statement.tryGenInlineExpr
import com.bitsycore.ktc.types.KtcType

/* Binary operators that compare two operands and would be tautological when
   applied to the exact same variable. Float kinds are excluded at the call site
   because NaN != NaN is intentional. */
private val kTautologyOps = setOf("==", "!=", "<=", ">=", "<", ">")
private val kFloatKinds   = setOf(KtcType.PrimKind.Float, KtcType.PrimKind.Double)

internal fun CCodeGen.genBin(e: BinExpr): String {
    // Tautological comparison of a name against itself (e.g. `x == x`, `i >= i`).
    // Always true (or always false for !=). Skip for float NaN-sensitive ops on
    // unknown types — we restrict to primitive integer / char / boolean receivers.
    if (e.op in kTautologyOps && e.left is NameExpr && e.right is NameExpr && e.left.name == e.right.name) {
        val vKtc = inferExprTypeKtc(e.left)
        if (vKtc is KtcType.Prim && vKtc.kind !in kFloatKinds)
            codegenWarning("self-compare", "Comparing '${e.left.name}' to itself with '${e.op}' is ${if (e.op == "!=") "always false" else "always true"}")
    }
    val lt = inferExprType(e.left)
    // User-defined infix inline extension function dispatch (covers `to`,
    // `toStd`-style functions, and any user-declared infix extension).
    val vInfixDecl = inlineExtFunDecls[e.op]?.firstOrNull()
    if (vInfixDecl != null) {
        val vRecvType = inferExprType(e.left) // receiver type string
        val vArgType  = inferExprType(e.right) // single argument type string
        val vSubst   = if (vInfixDecl.typeParams.isNotEmpty()) inferInlineFunSubst(vInfixDecl, vRecvType, listOf(vArgType)) else null
        val vRetType = vInfixDecl.returnType // declared return TypeRef
        return withTypeSubst(vSubst) {
            if (vRetType != null) {
                val vRecvExpr = genExpr(e.left)
                tryGenInlineExpr(
                    vInfixDecl, listOf(Arg(expr = e.right)),
                    receiverExpr = vRecvExpr, receiverType = vRecvType?.removeSuffix("?")
                ) ?: run {
                    val vResultName = "\$ir${inlineCounter++}"
                    impl.appendLine("$currentInd${cType(vRetType)} $vResultName;")
                    emitInlineCall(
                        vInfixDecl, listOf(Arg(expr = e.right)), currentInd, false,
                        receiverExpr = vRecvExpr, receiverType = vRecvType?.removeSuffix("?"), resultVar = vResultName
                    )
                    vResultName
                }
            } else {
                val vRecvExpr = genExpr(e.left)
                emitInlineCall(
                    vInfixDecl, listOf(Arg(expr = e.right)), currentInd, false,
                    receiverExpr = vRecvExpr, receiverType = vRecvType?.removeSuffix("?")
                )
                ""
            }
        }
    }
    // null comparison
    if ((e.op == "==" || e.op == "!=") && (e.left is NullLit || e.right is NullLit)) {
        val nonNull = if (e.left is NullLit) e.right else e.left
        // Warn: comparing a non-nullable type to null — always true/false.
        // Also short-circuit to a literal so we don't fall through into a
        // class-equality path that would try to call `T.equals(self, NULL)`
        // (which doesn't typecheck for a value-type T).
        val nonNullType = inferExprType(nonNull)
        val nonNullKtc = inferExprTypeKtc(nonNull)
        if (nonNullKtc != null && nonNullKtc !is KtcType.Nullable) {
            val always = if (e.op == "==") "false" else "true"
            codegenWarning("null-check", "Null check on non-nullable '$nonNullType' is always $always")
            // Side-effects in the receiver must still be evaluated.
            val vSide = genExpr(nonNull)
            return "((void)($vSide), $always)"
        }
        // this == null / this != null inside nullable-receiver extension
        if (nonNull is ThisExpr) {
            val thisKtc = inferExprTypeKtc(nonNull)
            if (thisKtc is KtcType.Nullable) {
                // Nullable VarArr (Ref<Array<T>?>) → check .ptr for null
                if (thisKtc.inner.isArrayLike && thisKtc.inner.asArr?.sized == null) {
                    return if (e.op == "==") "\$self.ptr == NULL" else "\$self.ptr != NULL"
                }
                // Ref<T?> → compare pointer to NULL
                if (thisKtc.inner is KtcType.Ptr && thisKtc.inner.inner !is KtcType.Arr) {
                    if (thisKtc.inner.inner is KtcType.User && thisKtc.inner.inner.kind == KtcType.UserKind.Interface)
                        return if (e.op == "==") "!\$self.vt" else "\$self.vt"
                    return if (e.op == "==") "\$self == NULL" else "\$self != NULL"
                }
                if (isValueNullableKtc(thisKtc)) {
                    return if (e.op == "==") "\$self.tag == ktc_NONE" else "\$self.tag == ktc_SOME"
                }
                return if (e.op == "==") "!\$self\$has" else "\$self\$has"
            }
        }
        val varName = (nonNull as? NameExpr)?.name
        val varKtc = if (varName != null) lookupVarKtc(varName) else null
        if (varKtc != null && varName != null) {
            val cName = lookupCName(varName)
            // Ref<T?> → compare pointer to NULL (exclude typed array pointers like IntArray)
            if (varKtc is KtcType.Nullable && varKtc.inner is KtcType.Ptr && varKtc.inner.inner !is KtcType.Arr) {
                // Ref<InterfaceType> → check vt for null (ktc_IfacePtr is a struct)
                if (varKtc.inner.inner is KtcType.User
                    && varKtc.inner.inner.kind == KtcType.UserKind.Interface)
                    return if (e.op == "==") "!${cName}.vt" else "${cName}.vt"
                return if (e.op == "==") "$cName == NULL" else "$cName != NULL"
            }
            // Any? nullable → compare data pointer to NULL
            if (varKtc is KtcType.Nullable && varKtc.inner is KtcType.Any) {
                return if (e.op == "==") "$cName.data == NULL" else "$cName.data != NULL"
            }
            // Value nullable → use Optional tag
            if (isValueNullableKtc(varKtc)) {
                return if (e.op == "==") "$cName.tag == ktc_NONE" else "$cName.tag == ktc_SOME"
            }
            // Trampolined array param: null is data == NULL — use local copy for consistency
            if (varName in trampolinedParams) {
                return if (e.op == "==") "local$$varName == NULL" else "local$$varName != NULL"
            }
            // Nullable VarArr (Ref<Array<T>?>) → check .ptr for null
            if (varKtc is KtcType.Nullable && varKtc.inner.isArrayLike && varKtc.inner.asArr?.sized == null) {
                return if (e.op == "==") "$cName.ptr == NULL" else "$cName.ptr != NULL"
            }
            // Fallback for other nullable
            if (varKtc is KtcType.Nullable) {
                return if (e.op == "==") "$cName == NULL" else "$cName != NULL"
            }
        }
        // DotExpr on nullable field (e.g. np.x == null)
        val nonNullKtc2 = nonNullKtc ?: inferExprTypeKtc(nonNull)
        if (nonNull is DotExpr && nonNullKtc2 is KtcType.Nullable && isValueNullableKtc(nonNullKtc2)) {
            val dotExpr = genExpr(nonNull)
            return if (e.op == "==") "$dotExpr.tag == ktc_NONE" else "$dotExpr.tag == ktc_SOME"
        }
    }
    // Nullable T? vs non-null value: generate Optional-aware comparison
    // === → pointer comparison in C (referential equality)
    if (e.op == "===") {
        fun genPtr(e2: Expr): String {
            if (e2 is ThisExpr) return "(void*)\$self"
            val s = genExpr(e2)
            // Trampoline Any: extract .data pointer
            val t = inferExprTypeKtc(e2)
            if (t is KtcType.Any || (t is KtcType.Nullable && t.inner is KtcType.Any)) return "$s.data"
            return s
            }
        return "(${genPtr(e.left)} == ${genPtr(e.right)})"
        }
    if (e.op in setOf("==", "!=", "<", ">", "<=", ">=")) {
        val ltKtc = inferExprTypeKtc(e.left)
        val rtKtc = inferExprTypeKtc(e.right)
        // Both are nullable value types — compare tags and values
        if (ltKtc is KtcType.Nullable && isValueNullableKtc(ltKtc) &&
            rtKtc is KtcType.Nullable && isValueNullableKtc(rtKtc)) {
            val leftExpr = genExpr(e.left)
            val rightExpr = genExpr(e.right)
            val vIsStr = ltKtc.inner is KtcType.Str
            return when (e.op) {
                "==" -> if (vIsStr) "($leftExpr.tag == $rightExpr.tag && ($leftExpr.tag == ktc_NONE || ktc_core_string_eq($leftExpr.value, $rightExpr.value)))"
                        else "($leftExpr.tag == $rightExpr.tag && ($leftExpr.tag == ktc_NONE || $leftExpr.value == $rightExpr.value))"
                "!=" -> if (vIsStr) "($leftExpr.tag != $rightExpr.tag || ($leftExpr.tag == ktc_SOME && !ktc_core_string_eq($leftExpr.value, $rightExpr.value)))"
                        else "($leftExpr.tag != $rightExpr.tag || ($leftExpr.tag == ktc_SOME && $leftExpr.value != $rightExpr.value))"
                else -> "($leftExpr.tag == ktc_SOME && $rightExpr.tag == ktc_SOME && $leftExpr.value ${e.op} $rightExpr.value)"
            }
        }
        // Left is nullable value type, right is non-null → wrap in tag check
        if (ltKtc is KtcType.Nullable && isValueNullableKtc(ltKtc) &&
            rtKtc != null && rtKtc !is KtcType.Nullable && e.right !is NullLit) {
            val leftExpr = genExpr(e.left)
            val rightExpr = genExpr(e.right)
            val vIsStr = ltKtc.inner is KtcType.Str
            return when (e.op) {
                "==" -> if (vIsStr) "($leftExpr.tag == ktc_SOME && ktc_core_string_eq($leftExpr.value, $rightExpr))"
                        else "($leftExpr.tag == ktc_SOME && $leftExpr.value == $rightExpr)"
                "!=" -> if (vIsStr) "($leftExpr.tag != ktc_SOME || !ktc_core_string_eq($leftExpr.value, $rightExpr))"
                        else "($leftExpr.tag != ktc_SOME || $leftExpr.value != $rightExpr)"
                else -> "($leftExpr.tag == ktc_SOME && $leftExpr.value ${e.op} $rightExpr)"
            }
        }
        // Right is nullable value type, left is non-null
        if (rtKtc is KtcType.Nullable && isValueNullableKtc(rtKtc) &&
            ltKtc != null && ltKtc !is KtcType.Nullable && e.left !is NullLit) {
            val leftExpr = genExpr(e.left)
            val rightExpr = genExpr(e.right)
            val vIsStr = rtKtc.inner is KtcType.Str
            return when (e.op) {
                "==" -> if (vIsStr) "($rightExpr.tag == ktc_SOME && ktc_core_string_eq($leftExpr, $rightExpr.value))"
                        else "($rightExpr.tag == ktc_SOME && $leftExpr == $rightExpr.value)"
                "!=" -> if (vIsStr) "($rightExpr.tag != ktc_SOME || !ktc_core_string_eq($leftExpr, $rightExpr.value))"
                        else "($rightExpr.tag != ktc_SOME || $leftExpr != $rightExpr.value)"
                else -> "($rightExpr.tag == ktc_SOME && $leftExpr ${e.op} $rightExpr.value)"
            }
        }
    }
    // Class == / != → ClassName_equals (all classes, not just data)
    // Also handles Ref<T> types by resolving to base class and dereferencing
    val ltKtc2 = inferExprTypeKtc(e.left)
    // Full-enum == / != — compare by ordinal (simple enums fall through to plain int compare).
    val ltEnumInfo = enumInfoFor(ltKtc2)
    if ((e.op == "==" || e.op == "!=") && ltEnumInfo != null && !ltEnumInfo.isSimple) {
        val leftExpr  = genExpr(e.left)
        val rightExpr = genExpr(e.right)
        return "($leftExpr.ordinal ${e.op} $rightExpr.ordinal)"
    }
    var isPtr = false
    val classKeyFromKtc: String? = when (ltKtc2) {
        is KtcType.Ptr -> { isPtr = true; (ltKtc2.inner as? KtcType.User)?.baseName }
        is KtcType.Nullable if ltKtc2.inner is KtcType.Ptr -> {
            isPtr = true
            (ltKtc2.inner.inner as? KtcType.User)?.baseName
        }
        is KtcType.User -> ltKtc2.baseName.takeIf { classes.containsKey(it) }
        else -> null
    }
    val classKey = classKeyFromKtc ?: lt?.takeIf { classes.containsKey(it) }
    if ((e.op == "==" || e.op == "!=") && classKey != null) {
        val leftExpr = genExpr(e.left)
        val rightExpr = genExpr(e.right)
        // Dereference if the operands are pointers (e.g. Ref<Vec2>)
        val l = if (isPtr) "(*$leftExpr)" else leftExpr
        val r = if (isPtr) "(*$rightExpr)" else rightExpr
        val eq = "${typeFlatName(classKey)}_equals($l, $r)"
        return if (e.op == "==") eq else "!$eq"
    }
    // @SimpleUnion == / != → inline dispatch through type ID + concrete equals
    val simpleUnionKey = lt?.takeIf { it in simpleUnionInterfaces }
    if ((e.op == "==" || e.op == "!=") && simpleUnionKey != null) {
        val leftExpr = genExpr(e.left)
        val rightExpr = genExpr(e.right)
        val eq = genSimpleUnionDispatch(simpleUnionKey, leftExpr, "equals", rightExpr)
        return if (e.op == "==") eq else "!$eq"
    }
    // String == → ktc_core_string_eq
    val ltKtc3 = inferExprTypeKtc(e.left)
    if (e.op == "==" && ltKtc3 is KtcType.Str) {
        return "ktc_core_string_eq(${genExpr(e.left)}, ${genExpr(e.right)})"
    }
    if (e.op == "!=" && ltKtc3 is KtcType.Str) {
        return "!ktc_core_string_eq(${genExpr(e.left)}, ${genExpr(e.right)})"
    }
    // String <, >, <=, >= → ktc_core_string_cmp
    if (ltKtc3 is KtcType.Str && e.op in listOf("<", ">", "<=", ">=")) {
        return "(ktc_core_string_cmp(${genExpr(e.left)}, ${genExpr(e.right)}) ${e.op} 0)"
    }
    // String + → ktc_core_string_cat
    if (e.op == "+" && (lt == "String" || inferExprType(e.right) == "String")) {
        return genStringConcat(e)
    }
    // in / !in → operator contains() dispatch on class or interface
    if (e.op == "in" || e.op == "!in") {
        val rt = inferExprType(e.right)                                               // String? right-side type
        val rtKtc = inferExprTypeKtc(e.right)                                         // KtcType? right-side type
        val rtCoreKtc = rtKtc.stripNullable                 // KtcType? stripped Nullable
        val negated = e.op == "!in"
        val vContClassInfo = classInfoFor(rtCoreKtc)                                  // non-null if right side is a class
        val vContIfaceInfo = ifaceInfoFor(rtCoreKtc)                                  // non-null if right side is an interface
        if (vContClassInfo != null) {
            val containsMethod = vContClassInfo.methods.find { (it.name == "contains" || it.name == "containsKey") && it.isOperator }
            if (containsMethod != null) {
                val recv = genExpr(e.right)
                val elem = genExpr(e.left)
                val call = "${vContClassInfo.flatName}_${containsMethod.name}(&$recv, $elem)"
                return if (negated) "!$call" else call
            }
        }
        if (rtKtc is KtcType.Ptr || (rtKtc is KtcType.Nullable && rtKtc.inner is KtcType.Ptr)) {
            val ptrKtc = rtKtc.stripNullable
            val baseName = (ptrKtc as KtcType.Ptr).inner
            val baseClass = (baseName as? KtcType.User)?.baseName ?: baseName.toInternalStr
            val containsMethod = classes[baseClass]?.methods?.find { (it.name == "contains" || it.name == "containsKey") && it.isOperator }
            if (containsMethod != null) {
                val recv = genExpr(e.right)
                val elem = genExpr(e.left)
                val call = "${typeFlatName(baseClass)}_${containsMethod.name}($recv, $elem)"
                return if (negated) "!$call" else call
            }
        }
        if (vContIfaceInfo != null) {
            val allMethods = collectAllIfaceMethods(vContIfaceInfo)
            val containsMethod = allMethods.find { (it.name == "contains" || it.name == "containsKey") && it.isOperator }
            if (containsMethod != null) {
                val recv = genExpr(e.right)
                val elem = genExpr(e.left)
                val call = "$recv.vt->${containsMethod.name}(${ifaceVtableSelf(vContIfaceInfo.name, recv)}, $elem)"
                return if (negated) "!$call" else call
            }
        }
        // Fallback: range-based `in` for IntRange, etc.
        if (rt != null && (rt == "IntRange" || rt.endsWith("Range"))) {
            val lo = genExpr((e.right as? BinExpr)?.left ?: e.right)
            val hi = genExpr((e.right as? BinExpr)?.right ?: e.right)
            val v = genExpr(e.left)
            return if (negated) "($v < $lo || $v > $hi)" else "($v >= $lo && $v <= $hi)"
        }
    }
    // Bitwise infix operators → C operators
    if (e.op == "and") return "(${genExpr(e.left)} & ${genExpr(e.right)})"
    if (e.op == "or") return "(${genExpr(e.left)} | ${genExpr(e.right)})"
    if (e.op == "xor") return "(${genExpr(e.left)} ^ ${genExpr(e.right)})"
    if (e.op == "shl") return "(${genExpr(e.left)} << ${genExpr(e.right)})"
    if (e.op == "shr") return "(${genExpr(e.left)} >> ${genExpr(e.right)})"
    if (e.op == "ushr") {
        val leftType = inferExprType(e.left)
        val l = genExpr(e.left)
        val r = genExpr(e.right)
        // For unsigned types, >> is already unsigned; for signed, cast to unsigned first
        if (leftType == "UInt" || leftType == "UByte" || leftType == "UShort" || leftType == "ULong") {
            return "($l >> $r)"
        }
        if (leftType == "Long") return "(((ktc_ULong)$l) >> $r)"
        // Default: cast to unsigned equivalent
        return "(((ktc_UInt)$l) >> $r)"
    }
    // Check: division or modulo by constant zero
    if ((e.op == "/" || e.op == "%") && e.right is IntLit && e.right.value == 0L) {
        codegenError("Division by zero: constant 0 used as divisor in '${e.op}' expression")
    }
    // Constant-fold when both sides are integer literals
    if (e.left is IntLit && e.right is IntLit && e.op in setOf("+", "-", "*", "/", "%")) {
        val vL = e.left.value
        val vR = e.right.value
        val vResult = when (e.op) {
            "+"  -> vL + vR
            "-"  -> vL - vR
            "*"  -> vL * vR
            "/"  -> vL / vR
            "%"  -> vL % vR
            else -> null
        }
        if (vResult != null) {
            val vSuffix = if (lt == "Long") "LL" else ""
            return if (vResult < 0) "($vResult$vSuffix)" else "$vResult$vSuffix"
        }
    }
    return "(${genExpr(e.left)} ${e.op} ${genExpr(e.right)})"
}

/*
Infers a typeSubst map for a generic inline extension function from the receiver
and argument types at a call site. Handles only direct type-parameter references:
when the receiver type IS a type param (e.g. receiver = "A") or when a param type
IS a type param (e.g. param type = "B"). Nested generics require Phase-1 scanner work.
*/
internal fun inferInlineFunSubst(
    inDecl: FunDecl,
    inReceiverType: String?,
    inArgTypes: List<String?>
): Map<String, String> {
    val vSubst = mutableMapOf<String, String>() // type-param name → concrete type
    val vTypeParams = inDecl.typeParams.toSet() // set of type-param names for O(1) lookup
    if (inReceiverType != null && inDecl.receiver != null) {
        val vRecvParamName = inDecl.receiver.name // type param name used as receiver (e.g. "A")
        if (vRecvParamName in vTypeParams) {
            vSubst[vRecvParamName] = inReceiverType.removeSuffix("?")
        } else if (inDecl.receiver.typeArgs.isNotEmpty()) {
            // Generic receiver: Result<T> with concrete Result_Int → extract T→Int
            val vPrefix = "${vRecvParamName}_"
            val vConcrete = inReceiverType.removeSuffix("?")
            if (vConcrete.startsWith(vPrefix)) {
                val vArgStr = vConcrete.removePrefix(vPrefix)
                val vParts = vArgStr.split("_")
                inDecl.receiver.typeArgs.forEachIndexed { i, tArg ->
                    if (tArg.name in vTypeParams && i < vParts.size) vSubst[tArg.name] = vParts[i]
                }
            }
        }
    }
    inDecl.params.forEachIndexed { vIdx, vParam ->
        val vArgType = inArgTypes.getOrNull(vIdx)?.removeSuffix("?") ?: return@forEachIndexed
        val vParamTypeName = vParam.type.name // type param name used as param type (e.g. "B")
        if (vParamTypeName in vTypeParams) vSubst[vParamTypeName] = vArgType
    }
    return vSubst
}

internal fun CCodeGen.genStringConcat(e: BinExpr): String {
    val buf = tmp()
    // alloca, not a stack array: the buffer must live in the enclosing function's
    // frame so a String built here survives any surrounding inline `{ }` block and
    // can be safely returned from inline functions back to the caller.
    preStmts += "ktc_Char* $buf = (ktc_Char*)ktc_core_alloca(512);"
    return "ktc_core_string_cat($buf, 512, ${genExpr(e.left)}, ${genExpr(e.right)})"
}