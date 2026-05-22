package com.bitsycore.ktc.codegen.expression

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.codegen.*
import com.bitsycore.ktc.types.KtcType

// ── Built-in / intrinsic call dispatch ───────────────────────────
// Handles println, print, HeapAlloc family, arrayOf family, Array, StringBuffer.
// Returns null when inName is not a recognised built-in (caller continues dispatch).

/* Emit the final two preStmts that either build a VarArr or a raw pointer local,
then return the tmp name. Shared by HeapAlloc, HeapArrayZero and HeapArrayResize. */
private fun CCodeGen.finishHeapArrayVar(inT: String, inElemC: String, inSizeExpr: String, inIsArray: Boolean): String {
	if (inIsArray) preStmts += "${varArrTypeName(inElemC)} $inT = {${inT}_ptr, $inSizeExpr};"
	else           preStmts += "$inElemC* $inT = ${inT}_ptr;"
	return inT
	}

/* Emit heap-allocation code for a single TypeRef (array or class or raw pointer).
Shared by the explicit-type-arg branch and the target-type-inference branch of HeapAlloc. */
private fun CCodeGen.genHeapAllocForTypeRef(inTa: TypeRef, inArgs: List<Arg>): String {
	if ((inTa.name == "RawArray" || inTa.name == "Array") && inTa.typeArgs.isNotEmpty()) {
		val vElemName = typeSubst[inTa.typeArgs[0].name] ?: inTa.typeArgs[0].name
		val vElemC    = cTypeStr(vElemName)
		val vSizeExpr = genExpr(inArgs[0].expr)
		val vT        = tmp()
		preStmts += "$vElemC* ${vT}_ptr = ($vElemC*)${tMalloc("sizeof($vElemC) * (size_t)($vSizeExpr)")};"
		return finishHeapArrayVar(vT, vElemC, vSizeExpr, inTa.name == "Array")
		}
	var vTypeName = typeSubst[inTa.name] ?: inTa.name
	if (inTa.typeArgs.isNotEmpty() && classes.containsKey(vTypeName) && classes[vTypeName]!!.isGeneric)
		vTypeName = mangledGenericName(vTypeName, inTa.typeArgs.map { it.name })
	if (classes.containsKey(vTypeName)) {
		val vCName  = typeFlatName(vTypeName)
		val vArgStr = inArgs.joinToString(", ") { genExpr(it.expr) }
		val vT      = tmp()
		preStmts += "$vCName* $vT = ($vCName*)${tMalloc("sizeof($vCName)")};"
		preStmts += "if ($vT) *$vT = ${vCName}_primaryConstructor($vArgStr);"
		return vT
		}
	val vElemC = cTypeStr(vTypeName)
	if (inArgs.isEmpty()) return "($vElemC*)${tMalloc("sizeof($vElemC)")}"
	return "($vElemC*)${tMalloc("sizeof($vElemC) * (size_t)(${genExpr(inArgs[0].expr)})")}"
	}

/* Infer the capacity expression for the 2-arg StringBuffer constructor from the ptr argument. */
private fun CCodeGen.strBufCapExpr(inPtrArg: Expr, inRawPtr: String): String =
	when (inPtrArg) {
		is NullLit -> "0"
		is DotExpr if inPtrArg.name == "ptr" ->
			"${genExpr(inPtrArg.obj)}.len"
		is CallExpr if inPtrArg.callee is DotExpr && (inPtrArg.callee as DotExpr).name == "ptr" ->
			"${genExpr((inPtrArg.callee as DotExpr).obj)}.len"
		else -> {
			val ktc = inferExprTypeKtc(inPtrArg)
			// Use inRawPtr.len (the VarArr) not the extracted .ptr value
			if (ktc is KtcType.Ptr && ktc.inner is KtcType.Arr) "$inRawPtr.len" else "0x7FFFFFFF"
			}
		}

internal fun CCodeGen.genBuiltinCallOrNull(
	inName:  String,
	inArgs:  List<Arg>,
	inCall:  CallExpr
	): String? {
	when (inName) {
		"println" -> return genPrintln(inArgs)
		"print"   -> return genPrint(inArgs)

		"HeapAlloc" -> {
			if (inCall.typeArgs.isNotEmpty()) return genHeapAllocForTypeRef(inCall.typeArgs[0], inArgs)
			if (heapAllocTargetType != null)  return genHeapAllocForTypeRef(heapAllocTargetType!!, inArgs)
			return tMalloc("(size_t)(${genExpr(inArgs[0].expr)})")
			}

		"HeapArrayZero" -> {
			fun genBranch(inTa: TypeRef): String {
				val vIsArray    = inTa.name == "Array"    && inTa.typeArgs.isNotEmpty()
				val vIsRawArray = inTa.name == "RawArray" && inTa.typeArgs.isNotEmpty()
				val vElemName   = if (vIsArray || vIsRawArray) {
					typeSubst[inTa.typeArgs[0].name] ?: inTa.typeArgs[0].name
					} else typeSubst[inTa.name] ?: inTa.name
				val vElemC    = cTypeStr(vElemName)
				val vSizeExpr = genExpr(inArgs[0].expr)
				val vT        = tmp()
				preStmts += "$vElemC* ${vT}_ptr = ($vElemC*)${tCalloc("(size_t)($vSizeExpr)", "sizeof($vElemC)")};"
				return finishHeapArrayVar(vT, vElemC, vSizeExpr, vIsArray)
				}
			if (inCall.typeArgs.isNotEmpty()) return genBranch(inCall.typeArgs[0])
			if (heapAllocTargetType != null)  return genBranch(heapAllocTargetType!!)
			return tCalloc("(size_t)(${genExpr(inArgs[0].expr)})", "(size_t)(${genExpr(inArgs[1].expr)})")
			}

		"HeapArrayResize" -> {
			fun genBranch(inTa: TypeRef): String {
				val vIsArray  = inTa.name == "Array" && inTa.typeArgs.isNotEmpty()
				val vElemName = if (vIsArray) typeSubst[inTa.typeArgs[0].name] ?: inTa.typeArgs[0].name
					else typeSubst[inTa.name] ?: inTa.name
				val vElemC    = cTypeStr(vElemName)
				val vPtrExpr  = genExpr(inArgs[0].expr)
				val vSizeExpr = genExpr(inArgs[1].expr)
				val vT        = tmp()
				val vRawPtrExpr = if (vIsArray) "($vPtrExpr).ptr" else vPtrExpr
				preStmts += "$vElemC* ${vT}_ptr = ($vElemC*)${tRealloc(vRawPtrExpr, "sizeof($vElemC) * (size_t)($vSizeExpr)")};"
				return finishHeapArrayVar(vT, vElemC, vSizeExpr, vIsArray)
				}
			if (inCall.typeArgs.isNotEmpty()) return genBranch(inCall.typeArgs[0])
			if (heapAllocTargetType != null)  return genBranch(heapAllocTargetType!!)
			return tRealloc(genExpr(inArgs[0].expr), "(size_t)(${genExpr(inArgs[1].expr)})")
			}

		"HeapFree" -> {
			val vFreeArgKtc  = inferExprTypeKtc(inArgs[0].expr)
			val vFreeArgCore = (vFreeArgKtc as? KtcType.Nullable)?.inner ?: vFreeArgKtc
			val vFreeExpr    = genExpr(inArgs[0].expr)
			return if (vFreeArgCore?.isArrayLike == true) tFree("($vFreeExpr).ptr") else tFree(vFreeExpr)
			}

		"byteArrayOf", "shortArrayOf", "intArrayOf", "longArrayOf",
		"floatArrayOf", "doubleArrayOf", "booleanArrayOf", "charArrayOf",
		"ubyteArrayOf", "ushortArrayOf", "uintArrayOf", "ulongArrayOf" ->
			return genArrayOfExpr(inName, inArgs)

		"arrayOf"     -> return genArrayOfExpr(inName, inArgs, inCall.typeArgs.getOrNull(0))

		"arrayOfNulls" -> {
			val vTypeArg  = inCall.typeArgs.getOrNull(0)
			val vElemName = typeSubst[vTypeArg?.name ?: "Int"] ?: (vTypeArg?.name ?: "Int")
			val vOptCType = optCTypeName("${vElemName}?")
			return genNewArray(vOptCType, inArgs)
			}

		"enumValues" -> {
			if (inCall.typeArgs.isNotEmpty()) {
				val vEnumName = inCall.typeArgs[0].name
				val vResolved = typeSubst[vEnumName] ?: vEnumName
				val vFlat     = typeFlatName(vResolved)
				enumValuesCalled.add(vResolved)
				return "{${vFlat}_values, ${vFlat}_values\$len}"
				}
			error("enumValues requires a type argument")
			}

		"enumValueOf" -> {
			if (inCall.typeArgs.isNotEmpty() && inArgs.isNotEmpty()) {
				val vEnumName = inCall.typeArgs[0].name
				val vResolved = typeSubst[vEnumName] ?: vEnumName
				enumValuesCalled.add(vResolved)
				enumValueOfCalled.add(vResolved)
				val vNameExpr = genExpr(inArgs[0].expr)
				return "${typeFlatName(vResolved)}_valueOf($vNameExpr)"
				}
			error("enumValueOf requires a type argument and a name")
			}

		"ByteArray"    -> return genNewArray("ktc_Byte",   inArgs)
		"ShortArray"   -> return genNewArray("ktc_Short",  inArgs)
		"IntArray"     -> return genNewArray("ktc_Int",    inArgs)
		"LongArray"    -> return genNewArray("ktc_Long",   inArgs)
		"FloatArray"   -> return genNewArray("ktc_Float",  inArgs)
		"DoubleArray"  -> return genNewArray("ktc_Double", inArgs)
		"BooleanArray" -> return genNewArray("ktc_Bool",   inArgs)
		"CharArray"    -> return genNewArray("ktc_Char",   inArgs)
		"UByteArray"   -> return genNewArray("ktc_UByte",  inArgs)
		"UShortArray"  -> return genNewArray("ktc_UShort", inArgs)
		"UIntArray"    -> return genNewArray("ktc_UInt",   inArgs)
		"ULongArray"   -> return genNewArray("ktc_ULong",  inArgs)

		"Array" -> {
			if (inCall.typeArgs.isNotEmpty()) {
				val vElemC = cTypeStr(resolveTypeName(inCall.typeArgs[0]))
				if (inArgs.size >= 2 && inArgs[1].expr is LambdaExpr) return genNewArrayWithLambda(vElemC, inArgs)
				return genNewArray(vElemC, inArgs)
				}
			}
		}

	// StringBuffer(ptr, len[, cap])
	if (inName == "StringBuffer" && inArgs.size in 2..3
		&& !classes.containsKey("StringBuffer") && !genericClassDecls.containsKey("StringBuffer")
	) {
		val vPtrRaw  = genExpr(inArgs[0].expr)
		val vPtrKtc  = inferExprTypeKtc(inArgs[0].expr)
		val vPtrCore = (vPtrKtc as? KtcType.Nullable)?.inner ?: vPtrKtc
		val vPtrExpr = if (vPtrCore?.isArrayLike == true && vPtrCore.asArr?.sized == null) "$vPtrRaw.ptr" else vPtrRaw
		val vLenExpr = genExpr(inArgs[1].expr)
		val vCapExpr = if (inArgs.size == 3) genExpr(inArgs[2].expr)
			else strBufCapExpr(inArgs[0].expr, vPtrRaw)
		return "(ktc_StrBuf){$vPtrExpr, $vLenExpr, $vCapExpr}"
		}

	return null
	}
