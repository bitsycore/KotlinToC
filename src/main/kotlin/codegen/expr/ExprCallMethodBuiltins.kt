package com.bitsycore.ktc.codegen.expr

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.codegen.*
import com.bitsycore.ktc.types.KtcType

// ── Built-in and array method dispatch ───────────────────────────
// Returns null when method is not a recognised built-in (caller continues dispatch).

/* Handles built-in String/primitive methods and array methods.
Returns the expression string, or null if not a recognised built-in. */
internal fun CCodeGen.genBuiltinMethodCallOrNull(
	inDot:         DotExpr,
	inArgs:        List<Arg>,
	inRecv:        String,
	inRecvType:    String?,
	inRecvTypeKtc: KtcType?
	): String? {
	val vMethod = inDot.name  // method name

	// ── Built-in methods ──────────────────────────────────────────────
	when (vMethod) {
		"trimIndent" -> {
			if (inDot.obj is StrLit) {
				val vStr     = inDot.obj.value
				val vTrimmed = trimIndentImpl(vStr)
				return "ktc_core_str(\"${escapeStr(vTrimmed)}\")"
				}
			codegenError("trimIndent() only supported on string literals")
			}

		"trimMargin" -> {
			if (inDot.obj is StrLit) {
				val vStr          = inDot.obj.value
				val vMarginPrefix = if (inArgs.isNotEmpty()) { // margin char, defaults to "|"
					(inArgs[0].expr as? StrLit)?.value ?: "|"
					} else "|"
				val vTrimmed = trimMarginImpl(vStr, vMarginPrefix)
				return "ktc_core_str(\"${escapeStr(vTrimmed)}\")"
				}
			codegenError("trimMargin() only supported on string literals")
			}

		"runeAt" -> {
			if (inRecvTypeKtc is KtcType.Str && inArgs.size == 1) {
				return "ktc_core_str_runeAt($inRecv, ${genExpr(inArgs[0].expr)})"
				}
			codegenError("runeAt() only supported on String with a byteIndex argument")
			}

		"toString" -> {
			if (inArgs.size == 1) {
				val vArgType = inferExprType(inArgs[0].expr) // output buffer type check
				if (vArgType == "ktc_StrBuf" || vArgType == "StringBuffer") {
					return genToStringInto(inRecv, inRecvType ?: "Int", genExpr(inArgs[0].expr))
					}
				}
			return genToString(inRecv, inRecvType ?: "Int")
			}

		"toInt"    -> { if (inRecvTypeKtc is KtcType.Str) return "ktc_core_str_toInt($inRecv)";  return "((ktc_Int)($inRecv))" }
		"toLong"   -> { if (inRecvTypeKtc is KtcType.Str) return "ktc_core_str_toLong($inRecv)"; return "((ktc_Long)($inRecv))" }
		"toFloat"  -> { if (inRecvTypeKtc is KtcType.Str) return "((ktc_Float)ktc_core_str_toDouble($inRecv))"; return "((ktc_Float)($inRecv))" }
		"toDouble" -> { if (inRecvTypeKtc is KtcType.Str) return "ktc_core_str_toDouble($inRecv)"; return "((ktc_Double)($inRecv))" }

		"toByte"   -> return "((ktc_Byte)($inRecv))"
		"toShort"  -> return "((ktc_Short)($inRecv))"
		"toUByte"  -> return "((ktc_UByte)($inRecv))"
		"toUShort" -> return "((ktc_UShort)($inRecv))"
		"toUInt"   -> return "((ktc_UInt)($inRecv))"
		"toULong"  -> return "((ktc_ULong)($inRecv))"
		"toChar"   -> return "((ktc_Char)($inRecv))"
		"inv"      -> return "(~($inRecv))"

		// Nullable string-to-number conversions — result is an Optional struct in vT
		"toIntOrNull" -> if (inRecvTypeKtc is KtcType.Str) {
			val vT = tmp()
			preStmts += "ktc_Int ${vT}_val;"
			preStmts += "ktc_Int\$Opt $vT;"
			preStmts += "$vT.tag = ktc_core_str_toIntOrNull($inRecv, &${vT}_val) ? ktc_SOME : ktc_NONE;"
			preStmts += "$vT.value = ${vT}_val;"
			markOptional(vT)
			return vT
			}

		"toLongOrNull" -> if (inRecvTypeKtc is KtcType.Str) {
			val vT = tmp()
			preStmts += "ktc_Long ${vT}_val;"
			preStmts += "ktc_Long\$Opt $vT;"
			preStmts += "$vT.tag = ktc_core_str_toLongOrNull($inRecv, &${vT}_val) ? ktc_SOME : ktc_NONE;"
			preStmts += "$vT.value = ${vT}_val;"
			markOptional(vT)
			return vT
			}

		"toDoubleOrNull" -> if (inRecvTypeKtc is KtcType.Str) {
			val vT = tmp()
			preStmts += "ktc_Double ${vT}_val;"
			preStmts += "ktc_Double\$Opt $vT;"
			preStmts += "$vT.tag = ktc_core_str_toDoubleOrNull($inRecv, &${vT}_val) ? ktc_SOME : ktc_NONE;"
			preStmts += "$vT.value = ${vT}_val;"
			markOptional(vT)
			return vT
			}

		"toFloatOrNull" -> if (inRecvTypeKtc is KtcType.Str) {
			val vT = tmp()
			preStmts += "ktc_Double ${vT}_d;"
			preStmts += "ktc_Float\$Opt $vT;"
			preStmts += "$vT.tag = ktc_core_str_toDoubleOrNull($inRecv, &${vT}_d) ? ktc_SOME : ktc_NONE;"
			preStmts += "$vT.value = (ktc_Float)${vT}_d;"
			markOptional(vT)
			return vT
			}

		"substring" -> if (inRecvTypeKtc is KtcType.Str) {
			val vFrom = genExpr(inArgs[0].expr)
			val vTo   = if (inArgs.size >= 2) genExpr(inArgs[1].expr) else "$inRecv.len"
			return "ktc_core_string_substring($inRecv, $vFrom, $vTo)"
			}

		"startsWith" -> if (inRecvTypeKtc is KtcType.Str) {
			val vPrefix = genExpr(inArgs[0].expr)
			return "($inRecv.len >= $vPrefix.len && memcmp($inRecv.ptr, $vPrefix.ptr, (size_t)$vPrefix.len) == 0)"
			}

		"endsWith" -> if (inRecvTypeKtc is KtcType.Str) {
			val vSuffix = genExpr(inArgs[0].expr)
			return "($inRecv.len >= $vSuffix.len && memcmp($inRecv.ptr + $inRecv.len - $vSuffix.len, $vSuffix.ptr, (size_t)$vSuffix.len) == 0)"
			}

		"contains" -> if (inRecvTypeKtc is KtcType.Str) {
			val vSub = genExpr(inArgs[0].expr)
			val vT   = tmp()
			preStmts += "ktc_Bool $vT = false;"
			preStmts += "for (ktc_Int ${vT}_i = 0; ${vT}_i <= $inRecv.len - $vSub.len; ${vT}_i++) { if (memcmp($inRecv.ptr + ${vT}_i, $vSub.ptr, (size_t)$vSub.len) == 0) { $vT = true; break; } }"
			return vT
			}

		"indexOf" -> if (inRecvTypeKtc is KtcType.Str) {
			val vSub = genExpr(inArgs[0].expr)
			val vT   = tmp()
			preStmts += "ktc_Int $vT = -1;"
			preStmts += "for (ktc_Int ${vT}_i = 0; ${vT}_i <= $inRecv.len - $vSub.len; ${vT}_i++) { if (memcmp($inRecv.ptr + ${vT}_i, $vSub.ptr, (size_t)$vSub.len) == 0) { $vT = ${vT}_i; break; } }"
			return vT
			}

		"isEmpty"    -> if (inRecvTypeKtc is KtcType.Str) return "($inRecv.len == 0)"
		"isNotEmpty" -> if (inRecvTypeKtc is KtcType.Str) return "($inRecv.len > 0)"

		"hashCode" -> {
			if (inRecvTypeKtc != null) {
				return when (inRecvTypeKtc) {
					is KtcType.Prim -> when (inRecvTypeKtc.kind) {
						KtcType.PrimKind.Byte    -> "ktc_core_hash_i8($inRecv)"
						KtcType.PrimKind.Short   -> "ktc_core_hash_i16($inRecv)"
						KtcType.PrimKind.Int     -> "ktc_core_hash_i32($inRecv)"
						KtcType.PrimKind.Long    -> "ktc_core_hash_i64($inRecv)"
						KtcType.PrimKind.Float   -> "ktc_core_hash_f32($inRecv)"
						KtcType.PrimKind.Double  -> "ktc_core_hash_f64($inRecv)"
						KtcType.PrimKind.Boolean -> "ktc_core_hash_bool($inRecv)"
						KtcType.PrimKind.Char    -> "ktc_core_hash_char($inRecv)"
						KtcType.PrimKind.UByte   -> "ktc_core_hash_u8($inRecv)"
						KtcType.PrimKind.UShort  -> "ktc_core_hash_u16($inRecv)"
						KtcType.PrimKind.UInt    -> "ktc_core_hash_u32($inRecv)"
						KtcType.PrimKind.ULong   -> "ktc_core_hash_u64($inRecv)"
						KtcType.PrimKind.Rune    -> "ktc_core_hash_i32($inRecv)"
						}
					is KtcType.Str -> "ktc_core_hash_str($inRecv)"
					else -> {
						val vPointerBase = (inRecvTypeKtc as? KtcType.Ptr)?.inner?.let { it as? KtcType.User }?.baseName
						if (vPointerBase != null) {
							"${typeFlatName(vPointerBase)}_hashCode($inRecv)"
							} else {
							"${typeFlatName(inRecvType!!)}_hashCode(&($inRecv))"
							}
						}
					}
				}
			return "${typeFlatName(inRecvType!!)}_hashCode(&($inRecv))"
			}
		}

	// ── Array methods ─────────────────────────────────────────────────

	if (vMethod == "size" && inRecvTypeKtc != null && inRecvTypeKtc.isArrayLike) {
		val vDotName = (inDot.obj as? NameExpr)?.name
		return if (vDotName != null && vDotName in trampolinedParams) arrayParamSizeExpr(vDotName) else "${inRecv}\$len"
		}
	if (vMethod == "ptr" && inRecvTypeKtc != null && inRecvTypeKtc.isArrayLike) {
		return inRecv
		}
	if (vMethod == "toHeap" && inRecvTypeKtc != null && inRecvTypeKtc.isArrayLike) {
		val vElemC   = arrayElementCTypeKtc(inRecvTypeKtc)
		val vLenExpr = when {
			inDot.obj is NameExpr && inDot.obj.name in trampolinedParams -> arrayParamSizeExpr(inDot.obj.name)
			else -> "${inRecv}\$len"
			}
		val vT = tmp()
		preStmts += "$vElemC* $vT = ($vElemC*)${tMalloc("sizeof($vElemC) * (size_t)($vLenExpr)")};"
		preStmts += "if ($vT) memcpy($vT, $inRecv, sizeof($vElemC) * (size_t)($vLenExpr));"
		preStmts += "ktc_Int ${vT}\$len = $vLenExpr;"
		return vT
		}
	if (vMethod == "copyOf" && inRecvTypeKtc != null && inRecvTypeKtc.isArrayLike && inArgs.size == 1) {
		val vElemC   = arrayElementCTypeKtc(inRecvTypeKtc)
		val vNewSize = genExpr(inArgs[0].expr)
		val vSrcLen  = when {
			inDot.obj is NameExpr && inDot.obj.name in trampolinedParams -> arrayParamSizeExpr(inDot.obj.name)
			else -> "${inRecv}\$len"
			}
		val vT       = tmp()
		val vCopyLen = tmp()
		preStmts += "$vElemC* $vT = ($vElemC*)ktc_core_alloca(sizeof($vElemC) * (size_t)($vNewSize));"
		preStmts += "const ktc_Int $vCopyLen = ($vSrcLen < ($vNewSize)) ? $vSrcLen : ($vNewSize);"
		preStmts += "memcpy($vT, $inRecv, (size_t)$vCopyLen * sizeof($vElemC));"
		preStmts += "if ($vCopyLen < ($vNewSize)) memset($vT + $vCopyLen, 0, (size_t)(($vNewSize) - $vCopyLen) * sizeof($vElemC));"
		preStmts += "const ktc_Int ${vT}\$len = $vNewSize;"
		return vT
		}
	if (vMethod == "resizeWith" && inRecvTypeKtc != null && inRecvTypeKtc.isArrayLike && inArgs.size >= 2) {
		val vElemC        = arrayElementCTypeKtc(inRecvTypeKtc)
		val vAllocExpr    = genExpr(inArgs[0].expr)
		val vNewSizeExpr  = genExpr(inArgs[1].expr)
		val vT            = tmp()
		val vAllocKtc     = inferExprTypeKtc(inArgs[0].expr)
		val vAllocCore    = (vAllocKtc as? KtcType.Nullable)?.inner ?: vAllocKtc
		val vIsTrampoline = vAllocCore is KtcType.Ptr && vAllocCore.inner is KtcType.User && vAllocCore.inner.kind == KtcType.UserKind.Interface
		val vIfExpr: String
		if (vIsTrampoline) {
			vIfExpr = vAllocExpr
			} else {
			val vAllocObjName = (inArgs[0].expr as? NameExpr)?.name
			if (vAllocObjName != null && objects.containsKey(vAllocObjName)) {
				val vCConcrete = typeFlatName(vAllocObjName); val vTypeId = getTypeId(vAllocObjName)
				preStmts += "ktc_IfacePtr $vT = {{$vTypeId}, (const void*)&${vCConcrete}_Allocator_vt, (void*)&$vAllocExpr};"
				vIfExpr = vT
				} else { vIfExpr = vAllocExpr }
			}
		preStmts += "$vElemC* ${vT}_ptr = ($vElemC*)((ktc_std_Allocator_vt*)$vIfExpr.vt)->reallocMem($vIfExpr.obj, $inRecv, sizeof($vElemC) * (size_t)($vNewSizeExpr), ${ktSrcStr()});"
		val vIsRawArray = inRecvTypeKtc.asArr == null && inRecvTypeKtc is KtcType.Ptr
		if (!vIsRawArray) preStmts += "ktc_Int ${vT}_ptr\$len = $vNewSizeExpr;"
		return "${vT}_ptr"
		}
	if ((vMethod == "get" || vMethod == "set") && inRecvTypeKtc != null && inRecvTypeKtc.isArrayLike) {
		val vIdx = inArgs.getOrNull(0)?.let { genExpr(it.expr) } ?: "0"
		if (vMethod == "get") return "${inRecv}[$vIdx]"
		val vValExpr = inArgs.getOrNull(1)?.let { genExpr(it.expr) } ?: "0"
		return "(${inRecv}[$vIdx] = $vValExpr)"
		}
	if (vMethod == "deref" && inRecvTypeKtc != null && inRecvTypeKtc.isArrayLike && inRecvTypeKtc is KtcType.Ptr) {
		return inRecv
		}

	return null
	}
