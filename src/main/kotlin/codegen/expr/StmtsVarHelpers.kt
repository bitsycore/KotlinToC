package com.bitsycore.ktc.codegen.expr

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.ast.Annotation
import com.bitsycore.ktc.codegen.*
import com.bitsycore.ktc.types.KtcType

// ── var / val helpers ─────────────────────────────────────────────
// Array-init detection, alloc introspection, and type inference
// for emitVarDecl (StmtsVar.kt).

internal fun CCodeGen.tryArrayOfInit(varName: String, init: Expr, ct: String, t: String, ind: String): String? {
	if (init !is CallExpr) return null
	// .ptr() / .copyWith() on array expression → propagate VarArr struct to the target variable
	if (init.callee is DotExpr) {
		val vDot = init.callee
		if (vDot.name == "ptr" || vDot.name == "copyWith") {
			val vRecvKtc = inferExprTypeKtc(vDot.obj)
			if (vRecvKtc?.isArrayLike == true) {
				val vElemC      = arrayElementCTypeKtc(vRecvKtc)
				val vVarArrType = varArrTypeName(vElemC)
				val vExpr       = genExpr(init)
				flushPreStmts(ind)
				return "$ind$vVarArrType $varName = $vExpr;"
				}
			}
		}
	val vCallee = (init.callee as? NameExpr)?.name ?: return null
	// arrayOfNulls<T>(size) — stack-allocate array of Optionals, all set to ktc_NONE
	if (vCallee == "arrayOfNulls") {
		val vTypeArg  = init.typeArgs.getOrNull(0)
		val vElemName = typeSubst[vTypeArg?.name ?: "Int"] ?: (vTypeArg?.name ?: "Int")
		val vOptCType = optCTypeName("${vElemName}?")
		val vSize     = if (init.args.isNotEmpty()) genExpr(init.args[0].expr) else "0"
		val vVarArrType = varArrTypeName(vOptCType)
		val vDataName   = "${varName}_data"
		return "${ind}$vOptCType* $vDataName = ($vOptCType*)ktc_core_alloca(sizeof($vOptCType) * (size_t)($vSize));\n" +
			"${ind}memset($vDataName, 0, sizeof($vOptCType) * (size_t)($vSize));\n" +
			"${ind}$vVarArrType $varName = {$vDataName, $vSize};"
		}
	// Array<T>(size), IntArray(size) etc. — fresh stack allocation
	if (vCallee in setOf(
			"IntArray", "LongArray", "FloatArray", "DoubleArray",
			"BooleanArray", "CharArray", "ByteArray", "ShortArray",
			"UByteArray", "UShortArray", "UIntArray", "ULongArray"
		) || (vCallee == "Array" && init.typeArgs.isNotEmpty())
	) {
		val vElemC = if (vCallee == "Array") {
			cTypeStr(resolveTypeName(init.typeArgs[0]))
			} else when (vCallee) {
			"IntArray"    -> "ktc_Int";    "LongArray"   -> "ktc_Long"
			"FloatArray"  -> "ktc_Float";  "DoubleArray" -> "ktc_Double"
			"BooleanArray"-> "ktc_Bool";   "CharArray"   -> "ktc_Char"
			"ByteArray"   -> "ktc_Byte";   "ShortArray"  -> "ktc_Short"
			"UByteArray"  -> "ktc_UByte";  "UShortArray" -> "ktc_UShort"
			"UIntArray"   -> "ktc_UInt";   "ULongArray"  -> "ktc_ULong"
			else          -> return null
			}
		val vSizeArg = init.args[0]
		val vSize    = genExpr(vSizeArg.expr)
		// Array<T>(size) { lambda } — inline init loop
		if (init.args.size >= 2 && init.args[1].expr is LambdaExpr) {
			val vLambda = init.args[1].expr as LambdaExpr
			val vItName = vLambda.params.firstOrNull() ?: "it"
			flushPreStmts(ind)
			val vSb         = StringBuilder()
			val vDataName   = "${varName}_data"
			val vVarArrType = varArrTypeName(vElemC)
			vSb.appendLine("${ind}$vElemC* $vDataName = ($vElemC*)ktc_core_alloca(sizeof($vElemC) * (size_t)($vSize));")
			vSb.appendLine("${ind}for (ktc_Int $vItName = 0; $vItName < $vSize; $vItName++) {")
			pushScope()
			defineVar(vItName, "Int")
			for ((vIdx, vStmt) in vLambda.body.withIndex()) {
				val vIsLast = vIdx == vLambda.body.lastIndex
				when {
					vIsLast && vStmt is ExprStmt ->
						vSb.appendLine("$ind    $vDataName[$vItName] = ${genExpr(vStmt.expr)};")
					vStmt is ExprStmt ->
						vSb.appendLine("$ind    (void)${genExpr(vStmt.expr)};")
					vStmt is VarDeclStmt -> {
						val vTypeKtc = vStmt.type?.let { resolveTypeName(it) }
							?: parseResolvedTypeName(inferExprType(vStmt.init) ?: "Int")
						defineVarKtc(vStmt.name, vTypeKtc)
						val vCT  = cTypeStr(vTypeKtc)
						val vMut = if (vStmt.mutable) "" else "const "
						val vInitExpr = vStmt.init?.let { genExpr(it) } ?: "0"
						vSb.appendLine("$ind    ${vMut}$vCT ${vStmt.name} = $vInitExpr;")
						}
					vStmt is AssignStmt -> {
						val vLhs = genLValue(vStmt.target, false)
						val vRhs = genExpr(vStmt.value)
						val vOp  = when (vStmt.op) {
							"+=" -> "+"; "-=" -> "-"; "*=" -> "*"; "/=" -> "/"; "%=" -> "%"
							else -> ""
							}
						if (vOp.isNotEmpty()) vSb.appendLine("$ind    $vLhs = ($vLhs $vOp $vRhs);")
						else                  vSb.appendLine("$ind    $vLhs = $vRhs;")
						}
					else -> codegenError("Unsupported statement in Array init lambda body")
					}
				}
			vSb.appendLine("${ind}}")
			popScope()
			vSb.appendLine("${ind}$vVarArrType $varName = {$vDataName, $vSize};")
			return vSb.toString()
			}
		flushPreStmts(ind)
		val vDataName   = "${varName}_data"
		val vVarArrType = varArrTypeName(vElemC)
		return if (vSizeArg.expr is IntLit || vSizeArg.expr is LongLit) {
			"${ind}$vElemC ${vDataName}[$vSize] = {0};\n" +
				"${ind}$vVarArrType $varName = {$vDataName, $vSize};"
			} else {
			"${ind}$vElemC* $vDataName = ($vElemC*)ktc_core_alloca(sizeof($vElemC) * (size_t)($vSize));\n" +
				"${ind}$vVarArrType $varName = {$vDataName, $vSize};"
			}
		}
	// arrayOf<T?>(...) or arrayOf(...) where declared type is an OptArray: wrap elements in Optional struct
	if (vCallee == "arrayOf") {
		val vTypeArg  = init.typeArgs.getOrNull(0)
		val vTKtc     = parseResolvedTypeName(t)
		val vTKtcCore = (vTKtc as? KtcType.Nullable)?.inner ?: vTKtc
		val vIsOptArray = vTKtcCore is KtcType.Ptr && vTKtcCore.inner is KtcType.Arr
			&& vTKtcCore.inner.elem is KtcType.Nullable
		val vIsNullableElem = vTypeArg?.nullable == true || vIsOptArray
		if (vIsNullableElem) {
			val vOptCType = if (vIsOptArray) arrayElementCTypeKtc(vTKtcCore)
				else optCTypeName("${typeSubst[vTypeArg!!.name] ?: vTypeArg.name}?")
			val vArgs = init.args.joinToString(", ") { vArg ->
				if (vArg.expr is NullLit) optNone(vOptCType)
				else optSome(vOptCType, genExpr(vArg.expr))
				}
			val vDataName   = "${varName}_data"
			val vVarArrType = varArrTypeName(vOptCType)
			return "${ind}$vOptCType ${vDataName}[] = {$vArgs};\n${ind}$vVarArrType $varName = {$vDataName, ${init.args.size}};"
			}
		}
	// heapArrayOf<T>(e1, e2, ...) → heap allocation, safe to return from functions
	if (vCallee == "heapArrayOf") {
		val vElemType = if (init.typeArgs.isNotEmpty()) cTypeStr(typeSubst[init.typeArgs[0].name] ?: init.typeArgs[0].name)
			else if (init.args.isNotEmpty()) cTypeStr(inferExprType(init.args[0].expr) ?: "Int")
			else "ktc_Int"
		val vN  = init.args.size
		val vSb         = StringBuilder()
		val vDataName   = "${varName}_data"
		val vVarArrType = varArrTypeName(vElemType)
		flushPreStmts(ind)
		val vInitName = "${varName}_init"
		val vArgExprs = init.args.joinToString(", ") { genExpr(it.expr) }
		vSb.appendLine("${ind}$vElemType ${vInitName}[] = {$vArgExprs};")
		vSb.appendLine("${ind}$vElemType* $vDataName = ($vElemType*)${tMalloc("sizeof($vInitName)")};")
		vSb.appendLine("${ind}memcpy($vDataName, $vInitName, sizeof($vInitName));")
		vSb.appendLine("${ind}$vVarArrType $varName = {$vDataName, $vN};")
		return vSb.toString().trimEnd()
		}
	val vElemType = when (vCallee) {
		"byteArrayOf"   -> "ktc_Byte";    "shortArrayOf"  -> "ktc_Short"
		"intArrayOf"    -> "ktc_Int";     "longArrayOf"   -> "ktc_Long"
		"floatArrayOf"  -> "ktc_Float";   "doubleArrayOf" -> "ktc_Double"
		"booleanArrayOf"-> "ktc_Bool";    "charArrayOf"   -> "ktc_Char"
		"ubyteArrayOf"  -> "ktc_UByte";   "ushortArrayOf" -> "ktc_UShort"
		"uintArrayOf"   -> "ktc_UInt";    "ulongArrayOf"  -> "ktc_ULong"
		"arrayOf" -> {
			if (init.typeArgs.isNotEmpty()) cTypeStr(typeSubst[init.typeArgs[0].name] ?: init.typeArgs[0].name)
			else {
				val vElemKt = if (init.args.isNotEmpty()) inferExprType(init.args[0].expr) ?: "Int" else "Int"
				cTypeStr(vElemKt)
				}
			}
		else -> return null
		}
	val vArgs       = init.args.joinToString(", ") { genExpr(it.expr) }
	val vN          = init.args.size
	val vDataName   = "${varName}_data"
	val vVarArrType = varArrTypeName(vElemType)
	return "${ind}$vElemType ${vDataName}[] = {$vArgs};\n${ind}$vVarArrType $varName = {$vDataName, $vN};"
	}

/* Check if an expression is a call to a function known to return nullable. */
internal fun CCodeGen.isNullableReturningCall(e: Expr?): Boolean {
	if (e !is CallExpr) return false
	val name = (e.callee as? NameExpr)?.name ?: return false
	return funSigs[name]?.returnType?.nullable == true
	}

/* Check if a call expression returns an array type (VarArr struct return). */
internal fun CCodeGen.isArrayReturningCall(e: Expr?): Boolean {
	if (e !is CallExpr) return false
	val name = (e.callee as? NameExpr)?.name ?: return false
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
	val sig = funSigs[name] ?: return false
	if (sig.returnType == null || sig.returnType.nullable) return false
	if (sig.returnType.isSizedArray()) return false
	return resolveTypeName(sig.returnType).isArrayLike
	}

/* Check if an expression is a malloc/calloc/realloc call (returns nullable pointer). */
internal fun isAllocCall(e: Expr?): Boolean {
	if (e !is CallExpr) return false
	val name = (e.callee as? NameExpr)?.name ?: return false
	return name in setOf("HeapAlloc", "HeapArrayZero", "HeapArrayResize", "heapArrayOf")
	}

/* Check if an expression is a malloc/calloc/realloc call with Array<T> type arg. */
internal fun CCodeGen.isAllocArrayCall(e: Expr?): Boolean {
	val inner = if (e is NotNullExpr) e.expr else e
	if (inner !is CallExpr) return false
	val name = (inner.callee as? NameExpr)?.name ?: return false
	if (name !in setOf("HeapAlloc", "HeapArrayZero", "HeapArrayResize", "heapArrayOf")) return false
	if (inner.typeArgs.isNotEmpty() && inner.typeArgs[0].name == "Array") return true
	if (name == "heapArrayOf") return true
	if (heapAllocTargetType != null && heapAllocTargetType!!.name == "Array" && heapAllocTargetType!!.typeArgs.isNotEmpty()) return true
	return false
	}

/*
Extract the allocation size argument from malloc<Array<T>>(size) or realloc<Array<T>>(ptr, size).
Unwraps NotNullExpr (!!). Returns the size Expr or null.
*/
internal fun extractAllocSize(e: Expr?): Expr? {
	val inner = if (e is NotNullExpr) e.expr else e
	if (inner !is CallExpr) return null
	if (inner.callee is DotExpr && inner.callee.name == "allocWith" && inner.args.size >= 2) {
		return inner.args[1].expr
		}
	if (inner.callee is DotExpr && inner.callee.name == "resizeWith" && inner.args.size >= 2) {
		return inner.args[1].expr
		}
	val name = (inner.callee as? NameExpr)?.name ?: return null
	return when (name) {
		"HeapAlloc"       -> inner.args.firstOrNull()?.expr
		"HeapArrayZero"   -> inner.args.firstOrNull()?.expr
		"HeapArrayResize" -> inner.args.getOrNull(1)?.expr
		"heapArrayOf"     -> IntLit(inner.args.size.toLong())
		else              -> null
		}
	}

/* Infer a TypeRef from an init expression, detecting @Ptr Array patterns from HeapAlloc. */
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
			if (name == "heapArrayOf") {
				return TypeRef("Array", nullable = true, typeArgs = listOf(ta), annotations = listOf(Annotation("Ptr")))
				}
			}
		if (name == "arrayOf" && inner.typeArgs.isNotEmpty()) {
			val ta      = inner.typeArgs[0]
			val size    = inner.args.size
			val sizeAnn = Annotation("Size", listOf(IntLit(size.toLong())))
			if (ta.name == "Array" && ta.typeArgs.isNotEmpty()) {
				return ta.typeArgs[0].copy(annotations = ta.typeArgs[0].annotations + sizeAnn)
				}
			return TypeRef(ta.name, typeArgs = ta.typeArgs, annotations = listOf(sizeAnn))
			}
		if (inner.callee is DotExpr && inner.callee.name == "allocWith") {
			val obj     = inner.callee.obj
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
	@Suppress("UNUSED_VARIABLE")
	val initKtc = inferExprTypeKtc(init)
	return TypeRef(inferExprType(init) ?: "Int")
	}

/* No-op: array fields are now ktc_VarArr_T structs that carry len internally. */
internal fun CCodeGen.emitBodyPropLenIfArray(inProp: PropertyDef) {
	}

/* Functions now return ktc_VarArr_T directly; delegate to genExpr. */
internal fun CCodeGen.genExprWithArrayLenOut(e: Expr, varName: String): String {
	return genExpr(e)
	}
