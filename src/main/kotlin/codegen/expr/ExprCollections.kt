package com.bitsycore.ktc.codegen.expr

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.codegen.*
import com.bitsycore.ktc.types.KtcType

internal fun CCodeGen.genArrayOfExpr(
    name: String,
    args: List<Arg>,
    inTypeArg: TypeRef? = null
): String { // inTypeArg — explicit type argument from the call site, e.g. arrayOf<Int?>(...)
    // arrayOf<T?>(v1, null, v2) → nullable element array; each element wrapped in Optional struct
    if (name == "arrayOf" && inTypeArg?.nullable == true) {
        val vElemName   = typeSubst[inTypeArg.name] ?: inTypeArg.name
        val vOptCType   = optCTypeName("${vElemName}?")
        val vVarArrType = varArrTypeName(vOptCType)
        val vVals = args.joinToString(", ") { vArg ->
            if (vArg.expr is NullLit) "($vOptCType){ktc_NONE}"
            else "($vOptCType){ktc_SOME, ${genExpr(vArg.expr)}}"
        }
        val vTmp     = tmp()
        val vTmpData = "${vTmp}_data"
        preStmts += "$vOptCType $vTmpData[] = {$vVals};"
        preStmts += "$vVarArrType $vTmp = {$vTmpData, ${args.size}};"
        return vTmp
    }
    val elemType = when (name) {
        "byteArrayOf" -> "ktc_Byte"; "shortArrayOf" -> "ktc_Short"
        "intArrayOf" -> "ktc_Int"; "longArrayOf" -> "ktc_Long"
        "floatArrayOf" -> "ktc_Float"; "doubleArrayOf" -> "ktc_Double"
        "booleanArrayOf" -> "ktc_Bool"; "charArrayOf" -> "ktc_Char"
        "ubyteArrayOf" -> "ktc_UByte"; "ushortArrayOf" -> "ktc_UShort"
        "uintArrayOf" -> "ktc_UInt"; "ulongArrayOf" -> "ktc_ULong"
        "arrayOf" -> {
            if (inTypeArg != null) cTypeStr(typeSubst[inTypeArg.name] ?: inTypeArg.name)
            else {
                val elemKt = if (args.isNotEmpty()) inferExprType(args[0].expr) ?: "Int" else "Int"; cTypeStr(elemKt)
            }
        }

        else -> "ktc_Int"
    }
    val vals = args.joinToString(", ") { genExpr(it.expr) }
    val n = args.size
    val t = tmp()
    /* Optimization: when this call is the direct return value of a @Size(N) function whose element
    type and count match, emit the ktc_Array_T_N struct inline — no raw array + memcpy needed. */
    if (currentFnReturnsSizedArray && n == currentFnSizedArraySize && elemType == currentFnSizedArrayElemType) {
        val vStructType = sizedArrayCTypeName(elemType, n)
        preStmts += "$vStructType $t = {{$vals}};"  // struct has only arr[N], no len field
        arrayOfSizedStructVars += t
        return t
    }
    val vVarArrType = varArrTypeName(elemType)
    val vTData      = "${t}_data"
    preStmts += "$elemType $vTData[] = {$vals};"
    preStmts += "$vVarArrType $t = {$vTData, $n};"
    return t
}

internal fun CCodeGen.genNewArray(elemCType: String, args: List<Arg>): String {
    val vSizeArg    = if (args.isNotEmpty()) args[0].expr else null
    val size        = if (vSizeArg != null) genExpr(vSizeArg) else "0"
    val vVarArrType = varArrTypeName(elemCType)
    val t           = tmp()
    val vTData      = "${t}_data"
    if (vSizeArg is IntLit || vSizeArg is LongLit) {
        preStmts += "$elemCType $vTData[$size] = {0};"
        } else {
        preStmts += "$elemCType* $vTData = ($elemCType*)ktc_core_alloca(sizeof($elemCType) * (size_t)($size));"
        }
    preStmts += "$vVarArrType $t = {$vTData, $size};"
    return t
}

/** Array<T>(size) { init } — stack-allocated with inline lambda init loop. */
internal fun CCodeGen.genNewArrayWithLambda(elemCType: String, args: List<Arg>): String {
    val size = genExpr(args[0].expr)
    val lambda = args[1].expr as LambdaExpr
    val itName = lambda.params.firstOrNull() ?: "it"
    val t = tmp()
    val vVarArrType = varArrTypeName(elemCType)
    val vTData      = "${t}_data"
    preStmts += "$elemCType* $vTData = ($elemCType*)ktc_core_alloca(sizeof($elemCType) * (size_t)($size));"
    preStmts += "for (ktc_Int $itName = 0; $itName < $size; $itName++) {"
    // Lambda body: must be a single expression producing the element value
    val bodyExpr = when {
        lambda.body.size == 1 && lambda.body[0] is ExprStmt -> (lambda.body[0] as ExprStmt).expr
        else -> {
            codegenError("Array init lambda must be a single expression")
        }
    }
    preStmts += "    $vTData[$itName] = ${genExpr(bodyExpr)};"
    preStmts += "}"
    preStmts += "$vVarArrType $t = {$vTData, $size};"
    return t
}

internal fun CCodeGen.genHeapArrayOfExpr(args: List<Arg>, inTypeArg: TypeRef? = null): String {
    val elemType = if (inTypeArg != null) cTypeStr(typeSubst[inTypeArg.name] ?: inTypeArg.name)
    else if (args.isNotEmpty()) {
        val inferred = inferExprType(args[0].expr) ?: "Int"
        val inferredKtc = inferExprTypeKtc(args[0].expr) ?: KtcType.Prim(KtcType.PrimKind.Int)
        cTypeStr(inferred)
    } else "ktc_Int"
    val n           = args.size
    val vVarArrType = varArrTypeName(elemType)
    val t           = tmp()
    val vTData      = "${t}_data"
    preStmts += "$elemType* $vTData = ($elemType*)${tMalloc("sizeof($elemType) * $n")};"
    val vals = args.mapIndexed { i, arg -> "$vTData[$i] = ${genExpr(arg.expr)};" }.joinToString(" ")
    if (vals.isNotEmpty()) preStmts += vals
    preStmts += "$vVarArrType $t = {$vTData, $n};"
    return t
}


// ── fill default arguments ───────────────────────────────────────

/* Returns the effective defaults map for a method, merging interface defaults for overrides.
In Kotlin, only the interface (origin) declares default values; overrides don't re-declare them.
inOwnerName: the class/object name that owns the method (null for top-level functions). */