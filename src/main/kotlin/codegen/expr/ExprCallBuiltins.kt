package com.bitsycore.ktc.codegen.expr

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.codegen.*
import com.bitsycore.ktc.types.KtcType

// ── Built-in / intrinsic call dispatch ───────────────────────────
// Handles println, print, HeapAlloc family, arrayOf family, Array, StringBuffer.
// Returns null when inName is not a recognised built-in (caller continues dispatch).

internal fun CCodeGen.genBuiltinCallOrNull(
	inName:  String,
	inArgs:  List<Arg>,
	inCall:  CallExpr
	): String? {
	when (inName) {
		"println" -> return genPrintln(inArgs)
		"print"   -> return genPrint(inArgs)

		"HeapAlloc" -> {
			if (inCall.typeArgs.isNotEmpty()) {
				val vTa = inCall.typeArgs[0]
				if (vTa.name == "RawArray" && vTa.typeArgs.isNotEmpty() ||
					vTa.name == "Array"    && vTa.typeArgs.isNotEmpty()
				) {
					val vElemName = typeSubst[vTa.typeArgs[0].name] ?: vTa.typeArgs[0].name
					val vElemC    = cTypeStr(vElemName)
					val vSizeExpr = genExpr(inArgs[0].expr)
					val vT        = tmp()
					preStmts += "$vElemC* ${vT}_ptr = ($vElemC*)${tMalloc("sizeof($vElemC) * (size_t)($vSizeExpr)")};"
					if (vTa.name == "Array") {
						preStmts += "${varArrTypeName(vElemC)} $vT = {${vT}_ptr, $vSizeExpr};"
						} else { preStmts += "$vElemC* $vT = ${vT}_ptr;" }
					return vT
					}
				var vTypeName = typeSubst[vTa.name] ?: vTa.name
				if (vTa.typeArgs.isNotEmpty() && classes.containsKey(vTypeName) && classes[vTypeName]!!.isGeneric) {
					vTypeName = mangledGenericName(vTypeName, vTa.typeArgs.map { it.name })
					}
				if (classes.containsKey(vTypeName)) {
					val vCName   = typeFlatName(vTypeName)
					val vArgStr  = inArgs.joinToString(", ") { genExpr(it.expr) }
					val vT       = tmp()
					preStmts += "$vCName* $vT = ($vCName*)${tMalloc("sizeof($vCName)")};"
					preStmts += "if ($vT) *$vT = ${vCName}_primaryConstructor($vArgStr);"
					return vT
					}
				val vElemC = cTypeStr(vTypeName)
				if (inArgs.isEmpty()) return "($vElemC*)${tMalloc("sizeof($vElemC)")}"
				return "($vElemC*)${tMalloc("sizeof($vElemC) * (size_t)(${genExpr(inArgs[0].expr)})")}"
				}
			if (heapAllocTargetType != null) {
				val vTt = heapAllocTargetType!!
				if (vTt.name == "RawArray" && vTt.typeArgs.isNotEmpty() ||
					vTt.name == "Array"    && vTt.typeArgs.isNotEmpty()
				) {
					val vElemName = typeSubst[vTt.typeArgs[0].name] ?: vTt.typeArgs[0].name
					val vElemC    = cTypeStr(vElemName)
					val vSizeExpr = genExpr(inArgs[0].expr)
					val vT        = tmp()
					preStmts += "$vElemC* ${vT}_ptr = ($vElemC*)${tMalloc("sizeof($vElemC) * (size_t)($vSizeExpr)")};"
					if (vTt.name == "Array") {
						preStmts += "${varArrTypeName(vElemC)} $vT = {${vT}_ptr, $vSizeExpr};"
						} else { preStmts += "$vElemC* $vT = ${vT}_ptr;" }
					return vT
					}
				var vTypeName = typeSubst[vTt.name] ?: vTt.name
				if (vTt.typeArgs.isNotEmpty() && classes.containsKey(vTypeName) && classes[vTypeName]!!.isGeneric) {
					vTypeName = mangledGenericName(vTypeName, vTt.typeArgs.map { it.name })
					}
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
				if (vIsArray) {
					preStmts += "${varArrTypeName(vElemC)} $vT = {${vT}_ptr, $vSizeExpr};"
					} else { preStmts += "$vElemC* $vT = ${vT}_ptr;" }
				return vT
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
				if (vIsArray) {
					preStmts += "${varArrTypeName(vElemC)} $vT = {${vT}_ptr, $vSizeExpr};"
					} else { preStmts += "$vElemC* $vT = ${vT}_ptr;" }
				return vT
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
		"heapArrayOf" -> return genHeapArrayOfExpr(inArgs, inCall.typeArgs.getOrNull(0))

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
				enumValuesCalled.add(vResolved)
				return "${typeFlatName(vResolved)}_values"
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

	// StringBuffer(ptr, len, cap)
	if (inName == "StringBuffer" && inArgs.size == 3
		&& !classes.containsKey("StringBuffer") && !genericClassDecls.containsKey("StringBuffer")
	) {
		val vPtrExpr = genExpr(inArgs[0].expr)
		val vLenExpr = genExpr(inArgs[1].expr)
		val vCapExpr = genExpr(inArgs[2].expr)
		return "(ktc_StrBuf){$vPtrExpr, $vLenExpr, $vCapExpr}"
		}
	// StringBuffer(ptr, len)
	if (inName == "StringBuffer" && inArgs.size == 2
		&& !classes.containsKey("StringBuffer") && !genericClassDecls.containsKey("StringBuffer")
	) {
		val vPtrExpr = genExpr(inArgs[0].expr)
		val vLenExpr = genExpr(inArgs[1].expr)
		val vCapExpr = when (inArgs[0].expr) {
			is NullLit -> "0"
			is DotExpr if (inArgs[0].expr as DotExpr).name == "ptr" -> {
				val vArrExpr = genExpr((inArgs[0].expr as DotExpr).obj)
				"$vArrExpr\$len"
				}
			is CallExpr if (inArgs[0].expr as CallExpr).callee is DotExpr
					&& ((inArgs[0].expr as CallExpr).callee as DotExpr).name == "ptr" -> {
				val vDot     = (inArgs[0].expr as CallExpr).callee as DotExpr
				val vArrExpr = genExpr(vDot.obj)
				"$vArrExpr\$len"
				}
			else -> {
				val vPtrKtc = inferExprTypeKtc(inArgs[0].expr)
				if (vPtrKtc is KtcType.Ptr && vPtrKtc.inner is KtcType.Arr) "${vPtrExpr}\$len"
				else "0x7FFFFFFF"
				}
			}
		return "(ktc_StrBuf){$vPtrExpr, $vLenExpr, $vCapExpr}"
		}

	return null
	}
