package com.bitsycore.ktc.codegen.expression

import com.bitsycore.ktc.ast.Arg
import com.bitsycore.ktc.ast.DotExpr
import com.bitsycore.ktc.ast.NameExpr
import com.bitsycore.ktc.ast.StrLit
import com.bitsycore.ktc.codegen.*
import com.bitsycore.ktc.types.KtcType

// ── Built-in and array method dispatch ───────────────────────────
// Returns null when method is not a recognised built-in (caller continues dispatch).

/* Emit a ktc_core_str_xOrNull → Optional conversion.
inOptCType: C type for the Optional struct (e.g. "ktc_Int").
inFuncName: the ktc_core_str_* function suffix (e.g. "toIntOrNull").
inIntCType: intermediate parse-target type — defaults to inOptCType, differs only for Float (uses Double).
inCast:     optional cast expression prepended to the value assignment (e.g. "(ktc_Float)"). */
private fun CCodeGen.tmpStrToNumOptional(
	inRecv:     String,
	inOptCType: String,
	inFuncName: String,
	inIntCType: String = inOptCType,
	inCast:     String = ""
	): String {
	val vT = tmp()
	preStmts += "$inIntCType ${vT}_val;"
	preStmts += "$inOptCType\$Opt $vT;"
	preStmts += "$vT.tag = ktc_core_str_$inFuncName($inRecv, &${vT}_val) ? ktc_SOME : ktc_NONE;"
	preStmts += "$vT.value = $inCast${vT}_val;"
	markOptional(vT)
	return vT
	}

/* Handles built-in String/primitive methods and array methods.
Returns the expression string, or null if not a recognised built-in. */
internal fun CCodeGen.genBuiltinMethodCallOrNull(
	inDot: DotExpr,
	inArgs: List<Arg>,
	inRecv: String,
	inRecvType: String?,
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
		"toIntOrNull"    -> if (inRecvTypeKtc is KtcType.Str) return tmpStrToNumOptional(inRecv, "ktc_Int",    "toIntOrNull")
		"toLongOrNull"   -> if (inRecvTypeKtc is KtcType.Str) return tmpStrToNumOptional(inRecv, "ktc_Long",   "toLongOrNull")
		"toDoubleOrNull" -> if (inRecvTypeKtc is KtcType.Str) return tmpStrToNumOptional(inRecv, "ktc_Double", "toDoubleOrNull")
		"toFloatOrNull"  -> if (inRecvTypeKtc is KtcType.Str) return tmpStrToNumOptional(inRecv, "ktc_Float",  "toDoubleOrNull", inIntCType = "ktc_Double", inCast = "(ktc_Float)")

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
		return if (vDotName != null && vDotName in trampolinedParams) arrayParamSizeExpr(vDotName) else "${inRecv}.len"
		}
	if (vMethod == "ptr" && inRecvTypeKtc != null && inRecvTypeKtc.isArrayLike) {
		return inRecv
		}
	if (vMethod == "copyOf" && inRecvTypeKtc != null && inRecvTypeKtc.isArrayLike && inArgs.size == 1) {
		val vElemC      = arrayElementCTypeKtc(inRecvTypeKtc)
		val vVarArrType = varArrTypeName(vElemC)
		val vNewSize    = genExpr(inArgs[0].expr)
		val vSrcLen     = when {
			inDot.obj is NameExpr && inDot.obj.name in trampolinedParams -> arrayParamSizeExpr(inDot.obj.name)
			else -> "$inRecv.len"
			}
		val vSrcPtr     = when {
			inDot.obj is NameExpr && inDot.obj.name in trampolinedParams -> "local\$${inDot.obj.name}"
			else -> "$inRecv.ptr"
			}
		val vData    = tmp()
		val vT       = tmp()
		val vCopyLen = tmp()
		preStmts += "$vElemC* $vData = ($vElemC*)ktc_core_alloca(sizeof($vElemC) * (size_t)($vNewSize));"
		preStmts += "const ktc_Int $vCopyLen = ($vSrcLen < ($vNewSize)) ? $vSrcLen : ($vNewSize);"
		preStmts += "memcpy($vData, $vSrcPtr, (size_t)$vCopyLen * sizeof($vElemC));"
		preStmts += "if ($vCopyLen < ($vNewSize)) memset($vData + $vCopyLen, 0, (size_t)(($vNewSize) - $vCopyLen) * sizeof($vElemC));"
		preStmts += "$vVarArrType $vT = {$vData, $vNewSize};"
		return vT
		}
	if (vMethod == "resizeWith" && inRecvTypeKtc != null && inRecvTypeKtc.isArrayLike && inArgs.size >= 2) {
		val vElemC        = arrayElementCTypeKtc(inRecvTypeKtc)
		val vAllocExpr    = genExpr(inArgs[0].expr)
		val vNewSizeExpr  = genExpr(inArgs[1].expr)
		val vT            = tmp()
		val vAllocKtc     = inferExprTypeKtc(inArgs[0].expr)
		val vAllocCore    = vAllocKtc.stripNullable
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
		val vIsRawArray = inRecvTypeKtc.asArr == null && inRecvTypeKtc is KtcType.Ptr
		val vSrcPtr     = if (vIsRawArray) inRecv else "$inRecv.ptr"
		preStmts += "$vElemC* ${vT}_ptr = ($vElemC*)((ktc_std_Allocator_vt*)$vIfExpr.vt)->reallocMem($vIfExpr.obj, $vSrcPtr, sizeof($vElemC) * (size_t)($vNewSizeExpr), ${ktSrcStr()});"
		if (!vIsRawArray) {
			val vVarArrType = varArrTypeName(vElemC)
			val vResult     = tmp()
			preStmts += "$vVarArrType $vResult = {${vT}_ptr, $vNewSizeExpr};"
			return vResult
			}
		return "${vT}_ptr"
		}
	if (vMethod == "copyWith" && inRecvTypeKtc != null && inRecvTypeKtc.isArrayLike && inArgs.size == 1) {
		val vElemC        = arrayElementCTypeKtc(inRecvTypeKtc)
		val vAllocExpr    = genExpr(inArgs[0].expr)
		val vSrcLen       = when {
			inDot.obj is NameExpr && inDot.obj.name in trampolinedParams -> arrayParamSizeExpr(inDot.obj.name)
			else -> "$inRecv.len"
			}
		val vSrcPtr       = when {
			inDot.obj is NameExpr && inDot.obj.name in trampolinedParams -> "local\$${inDot.obj.name}"
			else -> "$inRecv.ptr"
			}
		val vVarArrType   = varArrTypeName(vElemC)
		val vResult       = tmp()
		val vAllocObjName = (inArgs[0].expr as? NameExpr)?.name
		if (vAllocObjName == "Heap") {
			preStmts += "$vElemC* ${vResult}_ptr = ($vElemC*)${tMalloc("sizeof($vElemC) * (size_t)($vSrcLen)")};"
			preStmts += "if (${vResult}_ptr) memcpy(${vResult}_ptr, $vSrcPtr, (size_t)$vSrcLen * sizeof($vElemC));"
			preStmts += "$vVarArrType $vResult = {${vResult}_ptr, $vSrcLen};"
			return vResult
			}
		val vT            = tmp()
		val vAllocKtc     = inferExprTypeKtc(inArgs[0].expr)
		val vAllocCore    = vAllocKtc.stripNullable
		val vIsTrampoline = vAllocCore is KtcType.Ptr && vAllocCore.inner is KtcType.User && vAllocCore.inner.kind == KtcType.UserKind.Interface
		val vIfExpr: String
		if (vIsTrampoline) {
			vIfExpr = vAllocExpr
			} else {
			if (vAllocObjName != null && objects.containsKey(vAllocObjName)) {
				val vCConcrete = typeFlatName(vAllocObjName); val vTypeId = getTypeId(vAllocObjName)
				preStmts += "ktc_IfacePtr $vT = {{$vTypeId}, (const void*)&${vCConcrete}_Allocator_vt, (void*)&$vAllocExpr};"
				vIfExpr = vT
				} else { vIfExpr = vAllocExpr }
			}
		preStmts += "$vElemC* ${vT}_ptr = ($vElemC*)((ktc_std_Allocator_vt*)$vIfExpr.vt)->allocMem($vIfExpr.obj, sizeof($vElemC) * (size_t)($vSrcLen), ${ktSrcStr()});"
		preStmts += "if (${vT}_ptr) memcpy(${vT}_ptr, $vSrcPtr, (size_t)$vSrcLen * sizeof($vElemC));"
		preStmts += "$vVarArrType $vResult = {${vT}_ptr, $vSrcLen};"
		return vResult
		}
	if ((vMethod == "get" || vMethod == "set") && inRecvTypeKtc != null && inRecvTypeKtc.isArrayLike) {
		val vIdx        = inArgs.getOrNull(0)?.let { genExpr(it.expr) } ?: "0"
		val vRecvName   = (inDot.obj as? NameExpr)?.name
		val vIsTramp    = vRecvName != null && vRecvName in trampolinedParams
		val vIsSized    = inRecvTypeKtc.asArr?.sized != null
		val vAccessExpr = if (vIsTramp || vIsSized) "$inRecv[$vIdx]" else "$inRecv.ptr[$vIdx]"
		if (vMethod == "get") return vAccessExpr
		val vValExpr = inArgs.getOrNull(1)?.let { genExpr(it.expr) } ?: "0"
		return "($vAccessExpr = $vValExpr)"
		}

	return null
	}
