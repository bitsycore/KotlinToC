package com.bitsycore.ktc.codegen.expression

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.codegen.*
import com.bitsycore.ktc.types.KtcType

// ── Built-in / intrinsic call dispatch ───────────────────────────
// Handles println, print, arrayOf family, Array, StringBuffer.
// Returns null when inName is not a recognised built-in (caller continues dispatch).

/* Infer the capacity expression for the 2-arg StringBuffer constructor from the ptr argument. */
private fun CCodeGen.strBufCapExpr(inPtrArg: Expr, inRawPtr: String): String =
	when (inPtrArg) {
		is NullLit -> "0"
		is DotExpr if inPtrArg.name == "cPtr" ->
			"${genExpr(inPtrArg.obj)}.len"
		is CallExpr if inPtrArg.callee is DotExpr && inPtrArg.callee.name == "cPtr" ->
			"${genExpr(inPtrArg.callee.obj)}.len"
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

		// capture(...) is the thread-closure marker; outside a thread { } body it is a no-op (it is
		// consumed directly by the thread-closure lowering, so this is just a safety net).
		"capture" -> return ""

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
			codegenError("enumValues requires a type argument")
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
			codegenError("enumValueOf requires a type argument and a name")
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
		val vPtrCore = vPtrKtc.stripNullable
		val vPtrExpr = if (vPtrCore?.isArrayLike == true && vPtrCore.asArr?.sized == null) "$vPtrRaw.ptr" else vPtrRaw
		val vLenExpr = genExpr(inArgs[1].expr)
		val vCapExpr = if (inArgs.size == 3) genExpr(inArgs[2].expr)
			else strBufCapExpr(inArgs[0].expr, vPtrRaw)
		return "(ktc_StrBuf){$vPtrExpr, $vLenExpr, $vCapExpr}"
		}

	return null
	}
