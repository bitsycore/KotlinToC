package com.bitsycore.ktc.codegen.expression

import com.bitsycore.ktc.ast.*
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

/* Emit an in-place fill of [inLen] elements at [inPtr] with [inValueExpr].
Uses memset when [inValueExpr] is a zero literal (valid for any element) or the element
is byte-sized (any value); otherwise emits a bounded element loop. */
private fun CCodeGen.emitArrayFill(inPtr: String, inLen: String, inElemC: String, inValueExpr: String, inIsZeroLit: Boolean) {
	val vByteSized = inElemC in setOf("ktc_Byte", "ktc_UByte", "ktc_Bool")
	when {
		inIsZeroLit -> preStmts += "memset($inPtr, 0, sizeof($inElemC) * (size_t)($inLen));"
		vByteSized  -> preStmts += "memset($inPtr, (int)($inValueExpr), (size_t)($inLen));"
		else -> {
			val vP = tmp(); val vN = tmp(); val vV = tmp(); val vI = tmp()
			preStmts += "$inElemC* $vP = $inPtr;"
			preStmts += "const size_t $vN = (size_t)($inLen);"
			preStmts += "const $inElemC $vV = $inValueExpr;"
			preStmts += "for (size_t $vI = 0; $vI < $vN; $vI++) $vP[$vI] = $vV;"
			}
		}
	}

/* True when [inExpr] is an integer literal equal to zero (all-zero bytes → memset-safe). */
private fun isZeroLit(inExpr: Expr): Boolean =
	(inExpr is IntLit && inExpr.value == 0L) || (inExpr is LongLit && inExpr.value == 0L)

/* Fill start pointer: [inBase] offset by [inFrom] elements (or [inBase] when fromIndex is omitted). */
private fun fillStart(inBase: String, inFrom: String?): String =
	if (inFrom == null) inBase else "($inBase) + ($inFrom)"

/* Fill element count: toIndex - fromIndex (or just toIndex when fromIndex is omitted). */
private fun fillCount(inFrom: String?, inTo: String): String =
	if (inFrom == null) inTo else "($inTo) - ($inFrom)"

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

	// ── Template handle intrinsics (templateOf) ──────────────────────
	// A frame-local Template handle expands its stored template here. computeLen() counts via a NULL
	// StrBuf; toString() builds an owned String; toString(sb) renders into the caller's StringBuffer.
	val vTmplRecv = (inDot.obj as? NameExpr)?.let { lookupLocalVar(it.name)?.template }
	if (vTmplRecv != null) {
		when (vMethod) {
			"computeLen" -> {
				val vSb = tmp()
				preStmts += "ktc_StrBuf $vSb = {NULL, 0, 0};"
				genStrTemplateToSb(vTmplRecv, "&$vSb")
				return "$vSb.len"
				}
			"toString" -> {
				if (inArgs.size == 1) {
					val vSbExpr = genExpr(inArgs[0].expr)
					genStrTemplateToSb(vTmplRecv, "&($vSbExpr)")
					return "ktc_core_sb_to_string(&($vSbExpr))"
					}
				return genStrTemplate(vTmplRecv)
				}
			}
		}

	// sb."text $x" sugar: the parser lowers it to recv.__sbtmpl(<template>). Render into the StringBuffer
	// receiver and return the rendered String (sb-backed, like toString(sb)).
	if (vMethod == "__sbtmpl") {
		if (inRecvType != "ktc_StrBuf" && inRecvType != "StringBuffer")
			codegenError("The receiver of `sb.\"...\"` must be a StringBuffer (got '${inRecvType ?: "?"}').")
		val vSbRef = "&($inRecv)"
		when (val vArg = inArgs[0].expr) {
			is StrTemplateExpr -> genStrTemplateToSb(vArg, vSbRef)
			is StrLit          -> preStmts += "ktc_core_sb_append_str($vSbRef, ${genExpr(vArg)});"
			else               -> codegenError("`sb.\"...\"` expects a string template literal.")
			}
		return "ktc_core_sb_to_string($vSbRef)"
		}

	// ── StringBuffer method intrinsics ───────────────────────────────
	if (inRecvType == "ktc_StrBuf" || inRecvType == "StringBuffer") {
		val vSbRef = "&($inRecv)"
		when (vMethod) {
			"append"       -> if (inArgs.size == 1) {
					val vArg = inArgs[0].expr
					if (vArg is StrTemplateExpr) {
						genStrTemplateToSb(vArg, vSbRef)
						return ""
					}
					val vArgKtc = inferExprTypeKtc(vArg) ?: KtcType.Str
					val vExpr = genExpr(vArg)
					preStmts += genSbAppendKtc(vSbRef, vExpr, vArgKtc)
					return ""
				}
			"appendInt"    -> if (inArgs.size == 1) return "ktc_core_sb_append_int($vSbRef, ${genExpr(inArgs[0].expr)})"
			"appendLong"   -> if (inArgs.size == 1) return "ktc_core_sb_append_long($vSbRef, ${genExpr(inArgs[0].expr)})"
			"appendFloat"  -> if (inArgs.size == 1) return "ktc_core_sb_append_float($vSbRef, ${genExpr(inArgs[0].expr)})"
			"appendDouble" -> if (inArgs.size == 1) return "ktc_core_sb_append_double($vSbRef, ${genExpr(inArgs[0].expr)})"
			"appendBool"   -> if (inArgs.size == 1) return "ktc_core_sb_append_bool($vSbRef, ${genExpr(inArgs[0].expr)})"
			"appendChar"   -> if (inArgs.size == 1) return "ktc_core_sb_append_char($vSbRef, ${genExpr(inArgs[0].expr)})"
			"toString"     -> if (inArgs.isEmpty()) return "ktc_core_sb_to_string($vSbRef)"
		}
	}

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

		// toStringMaxLen() — static upper bound on this value's toString() length, as a compile-time
		// constant. Refused when the type isn't statically bounded (use toStringComputeLen() instead).
		"toStringMaxLen" -> {
			val vMax = toStringMaxLen(inRecvType ?: "Int")
				?: codegenError("toStringMaxLen() is unavailable for '${inRecvType ?: "?"}' — its toString() length is not statically bounded. Use toStringComputeLen() (computed at runtime).")
			return "$vMax"
			}

		// toStringComputeLen() — the runtime toString() length via a counting-only StrBuf pass (no allocation:
		// ptr=NULL accumulates len). For a String it is simply its byte length.
		"toStringComputeLen" -> {
			if (inRecvTypeKtc is KtcType.Str) return "$inRecv.len"
			val vSb = tmp()
			preStmts += "ktc_StrBuf $vSb = {NULL, 0, 0};"
			genToStringInto(inRecv, inRecvType ?: "Int", vSb)   // emits the counting-mode appends as preStmts
			return "$vSb.len"
			}

		// String receivers fall through to the inline stdlib extensions (Strings.kt),
		// which parse via *OrNull and throw NumberFormatException on bad input.
		"toInt"    -> { if (inRecvTypeKtc !is KtcType.Str) return "((ktc_Int)($inRecv))" }
		"toLong"   -> { if (inRecvTypeKtc !is KtcType.Str) return "((ktc_Long)($inRecv))" }
		"toFloat"  -> { if (inRecvTypeKtc !is KtcType.Str) return "((ktc_Float)($inRecv))" }
		"toDouble" -> { if (inRecvTypeKtc !is KtcType.Str) return "((ktc_Double)($inRecv))" }

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

		// substring COPIES into a fresh caller-frame buffer (NUL-terminated) → an owned String, no longer a
		// view. The inline ext funcs (take/drop/trim/removePrefix/substringBefore…) compose this, so they
		// copy too; returning one from a non-inline function now dangles (E020) — use inline or Ref<String>.
		"substring" -> if (inRecvTypeKtc is KtcType.Str) {
			val vFrom = genExpr(inArgs[0].expr)
			val vTo   = if (inArgs.size >= 2) genExpr(inArgs[1].expr) else "$inRecv.len"
			val vBuf  = tmp()
			preStmts += "ktc_Char* $vBuf = (ktc_Char*)ktc_core_alloca($inRecv.len + 1);"
			return "ktc_core_string_substring_copy($vBuf, $inRecv, $vFrom, $vTo)"
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
			val vArgKtc = inferExprTypeKtc(inArgs[0].expr).stripNullable
			val vArg = genExpr(inArgs[0].expr)
			return if (vArgKtc is KtcType.Prim && vArgKtc.kind == KtcType.PrimKind.Char) {
				"ktc_core_string_contains_char($inRecv, $vArg)"
			} else {
				val vT = tmp()
				preStmts += "ktc_Bool $vT = false;"
				preStmts += "for (ktc_Int ${vT}_i = 0; ${vT}_i <= $inRecv.len - $vArg.len; ${vT}_i++) { if (memcmp($inRecv.ptr + ${vT}_i, $vArg.ptr, (size_t)$vArg.len) == 0) { $vT = true; break; } }"
				vT
			}
			}

		"indexOf" -> if (inRecvTypeKtc is KtcType.Str) {
			val vArgKtc = inferExprTypeKtc(inArgs[0].expr).stripNullable
			val vArg = genExpr(inArgs[0].expr)
			return if (vArgKtc is KtcType.Prim && vArgKtc.kind == KtcType.PrimKind.Char) {
				"ktc_core_string_indexOf_char($inRecv, $vArg)"
			} else {
				val vT = tmp()
				preStmts += "ktc_Int $vT = -1;"
				preStmts += "for (ktc_Int ${vT}_i = 0; ${vT}_i <= $inRecv.len - $vArg.len; ${vT}_i++) { if (memcmp($inRecv.ptr + ${vT}_i, $vArg.ptr, (size_t)$vArg.len) == 0) { $vT = ${vT}_i; break; } }"
				vT
			}
			}

		"lastIndexOf" -> if (inRecvTypeKtc is KtcType.Str) {
			val vArgKtc = inferExprTypeKtc(inArgs[0].expr).stripNullable
			val vArg = genExpr(inArgs[0].expr)
			return if (vArgKtc is KtcType.Prim && vArgKtc.kind == KtcType.PrimKind.Char)
				"ktc_core_string_lastIndexOf_char($inRecv, $vArg)"
			else
				"ktc_core_string_lastIndexOf_str($inRecv, $vArg)"
			}

		// Buffer-producing String intrinsics. Each allocates a stack buffer via
		// alloca sized to (input.len + slack) for the worst case, then the core
		// helper writes into it. Output is a ktc_String view of that buffer —
		// safe within the surrounding function frame (same lifetime model as
		// the `+` concat helper).
		"reversed" -> if (inRecvTypeKtc is KtcType.Str) {
			val vBuf = tmp()
			preStmts += "ktc_Char* $vBuf = (ktc_Char*)ktc_core_alloca($inRecv.len + 1);"
			return "ktc_core_string_reversed($vBuf, $inRecv.len + 1, $inRecv)"
			}

		"lowercase" -> if (inRecvTypeKtc is KtcType.Str) {
			val vBuf = tmp()
			preStmts += "ktc_Char* $vBuf = (ktc_Char*)ktc_core_alloca($inRecv.len + 1);"
			return "ktc_core_string_lowercase_ascii($vBuf, $inRecv.len + 1, $inRecv)"
			}

		"uppercase" -> if (inRecvTypeKtc is KtcType.Str) {
			val vBuf = tmp()
			preStmts += "ktc_Char* $vBuf = (ktc_Char*)ktc_core_alloca($inRecv.len + 1);"
			return "ktc_core_string_uppercase_ascii($vBuf, $inRecv.len + 1, $inRecv)"
			}

		"repeat" -> if (inRecvTypeKtc is KtcType.Str && inArgs.isNotEmpty()) {
			val vN = genExpr(inArgs[0].expr)
			val vBuf = tmp()
			// Cap the alloca request to keep `n * len` from blowing the stack —
			// 64KB ceiling is a sane default for repeat-on-stack patterns.
			preStmts += "ktc_Int ${vBuf}_sz = ($inRecv.len * ($vN)) + 1; if (${vBuf}_sz > 65536) ${vBuf}_sz = 65536;"
			preStmts += "ktc_Char* $vBuf = (ktc_Char*)ktc_core_alloca(${vBuf}_sz);"
			return "ktc_core_string_repeat($vBuf, ${vBuf}_sz, $inRecv, $vN)"
			}

		"replace" -> if (inRecvTypeKtc is KtcType.Str && inArgs.size == 2) {
			val vOldKtc = inferExprTypeKtc(inArgs[0].expr).stripNullable
			if (vOldKtc is KtcType.Prim && vOldKtc.kind == KtcType.PrimKind.Char) {
				val vOld = genExpr(inArgs[0].expr)
				val vNew = genExpr(inArgs[1].expr)
				val vBuf = tmp()
				preStmts += "ktc_Char* $vBuf = (ktc_Char*)ktc_core_alloca($inRecv.len + 1);"
				return "ktc_core_string_replace_char($vBuf, $inRecv.len + 1, $inRecv, $vOld, $vNew)"
			}
			}

		"padStart" -> if (inRecvTypeKtc is KtcType.Str && inArgs.isNotEmpty()) {
			val vTarget = genExpr(inArgs[0].expr)
			val vPadCh  = if (inArgs.size >= 2) genExpr(inArgs[1].expr) else "' '"
			val vBuf = tmp()
			preStmts += "ktc_Int ${vBuf}_sz = (($vTarget) > $inRecv.len ? ($vTarget) : $inRecv.len) + 1;"
			preStmts += "ktc_Char* $vBuf = (ktc_Char*)ktc_core_alloca(${vBuf}_sz);"
			return "ktc_core_string_padStart($vBuf, ${vBuf}_sz, $inRecv, $vTarget, $vPadCh)"
			}

		"padEnd" -> if (inRecvTypeKtc is KtcType.Str && inArgs.isNotEmpty()) {
			val vTarget = genExpr(inArgs[0].expr)
			val vPadCh  = if (inArgs.size >= 2) genExpr(inArgs[1].expr) else "' '"
			val vBuf = tmp()
			preStmts += "ktc_Int ${vBuf}_sz = (($vTarget) > $inRecv.len ? ($vTarget) : $inRecv.len) + 1;"
			preStmts += "ktc_Char* $vBuf = (ktc_Char*)ktc_core_alloca(${vBuf}_sz);"
			return "ktc_core_string_padEnd($vBuf, ${vBuf}_sz, $inRecv, $vTarget, $vPadCh)"
			}

		// String.copy() — a fresh owned, NUL-terminated buffer in the caller's frame (no alias).
		// String is a read-only Array: copy mirrors Array.copyOf; asRef/copyWith mirror the array ops.
		"copy" -> if (inRecvTypeKtc is KtcType.Str) {
			val vBuf = tmp()
			preStmts += "ktc_Char* $vBuf = (ktc_Char*)ktc_core_alloca($inRecv.len + 1);"
			return "ktc_core_string_copy($vBuf, $inRecv)"
			}

		// String.asRef() — &s, a Ref<String> (ktc_String*) aliasing this frame String. Frame-bound:
		// returning `s.asRef()` dangles (E120 refuses it). Heap escape is .copyWith / .allocWith.
		// (Ref<String> is a real pointer, NOT a value struct like Ref<Array<T>>: RawArray<String> and
		// Ref<String> are both Ptr(Str), so the value form would collide with RawArray<String>.)
		"asRef" -> if (inRecvTypeKtc is KtcType.Str) return "&($inRecv)"

		// String.copyWith(alloc) / allocWith(alloc) — one heap block holding the ktc_String header AND its
		// NUL-terminated bytes inline; returns a Ref<String> (ktc_String*) that escapes the defining frame.
		// freeMem(r) releases the whole block. allocWith is the move-to-heap alias for an existing String.
		"copyWith", "allocWith" -> if (inRecvTypeKtc is KtcType.Str && inArgs.size == 1) {
			val vResult       = tmp()
			val vSize         = "sizeof(ktc_String) + (size_t)($inRecv.len) + 1"
			val vAllocObjName = (inArgs[0].expr as? NameExpr)?.name
			if (vAllocObjName == "Heap") {
				preStmts += "ktc_String* $vResult = (ktc_String*)${tMalloc(vSize)};"
				} else {
				val vAllocExpr = genExpr(inArgs[0].expr)
				val vIfExpr    = resolveAllocatorIface(inArgs[0].expr, vAllocExpr).ifaceExpr
				preStmts += "ktc_String* $vResult = (ktc_String*)((ktc_Allocator_vt*)$vIfExpr.vt)->allocMem($vIfExpr.obj, $vSize, ${ktSrcStr()});"
				}
			preStmts += "if ($vResult) { ktc_Char* ${vResult}_b = (ktc_Char*)($vResult + 1); if ($inRecv.len > 0) memcpy(${vResult}_b, $inRecv.ptr, (size_t)$inRecv.len); ${vResult}_b[$inRecv.len] = '\\0'; $vResult->ptr = ${vResult}_b; $vResult->len = $inRecv.len; }"
			return vResult
			}

		"toBooleanStrictOrNull" -> if (inRecvTypeKtc is KtcType.Str)
			return tmpStrToNumOptional(inRecv, "ktc_Bool", "toBooleanStrictOrNull")

		"compareTo" -> if (inRecvTypeKtc is KtcType.Str && inArgs.size == 1) {
			val vOther = genExpr(inArgs[0].expr)
			return "ktc_core_string_cmp($inRecv, $vOther)"
			}

		// Nullable-returning helpers. Inline expansion can't type a result
		// variable as ktc_Char$Opt yet, so these build the Optional directly.
		"firstOrNull" -> if (inRecvTypeKtc is KtcType.Str) {
			val vT = tmp()
			preStmts += "ktc_Char\$Opt $vT;"
			preStmts += "if (($inRecv).len == 0) { $vT.tag = ktc_NONE; } else { $vT.tag = ktc_SOME; $vT.value = ($inRecv).ptr[0]; }"
			markOptional(vT)
			return vT
			}

		"lastOrNull" -> if (inRecvTypeKtc is KtcType.Str) {
			val vT = tmp()
			preStmts += "ktc_Char\$Opt $vT;"
			preStmts += "if (($inRecv).len == 0) { $vT.tag = ktc_NONE; } else { $vT.tag = ktc_SOME; $vT.value = ($inRecv).ptr[($inRecv).len - 1]; }"
			markOptional(vT)
			return vT
			}

		"getOrNull" -> if (inRecvTypeKtc is KtcType.Str && inArgs.size == 1) {
			val vIdx = genExpr(inArgs[0].expr)
			val vT = tmp()
			preStmts += "ktc_Char\$Opt $vT;"
			preStmts += "{ ktc_Int ${vT}_i = ($vIdx); if (${vT}_i < 0 || ${vT}_i >= ($inRecv).len) { $vT.tag = ktc_NONE; } else { $vT.tag = ktc_SOME; $vT.value = ($inRecv).ptr[${vT}_i]; } }"
			markOptional(vT)
			return vT
			}

		"toBooleanStrict" -> if (inRecvTypeKtc is KtcType.Str) {
			val vT = tmp()
			preStmts += "ktc_Bool $vT = false;"
			preStmts += "if (!ktc_core_str_toBooleanStrictOrNull($inRecv, &$vT)) { ktc_core_stacktrace_print(\"IllegalArgumentException: not a valid Boolean\", 41, \"$currentSourceFile\", ${currentSourceFile.length}, $currentStmtLine); exit(1); }"
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
							} else if (inRecvType!! in simpleUnionInterfaces) {
							genSimpleUnionDispatch(inRecvType, inRecv, "hashCode", "")
							} else {
							"${typeFlatName(inRecvType)}_hashCode(&($inRecv))"
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
	if (vMethod == "asRef" && inRecvTypeKtc != null && inRecvTypeKtc.isArrayLike) {
		return inRecv
		}
	// Array<T>.asRaw() → bare RawArray<T> pointing at the array data (no length).
	if (vMethod == "asRaw" && inRecvTypeKtc != null && inRecvTypeKtc.isArrayLike) {
		val vObjName = (inDot.obj as? NameExpr)?.name
		return when {
			vObjName != null && vObjName in trampolinedParams -> "local\$$vObjName"
			inRecvTypeKtc.asArr?.sized != null                -> inRecv
			else                                              -> "($inRecv).ptr"
			}
		}
	// RawArray<T>.asArray(n) → Ref<Array<T>> (VarArr) over the same data with length n.
	if (vMethod == "asArray" && inRecvTypeKtc is KtcType.Ptr && inRecvTypeKtc.inner !is KtcType.Arr && inArgs.size == 1) {
		val vElemC      = inRecvTypeKtc.inner.toCType()
		val vVarArrType = varArrTypeName(vElemC)
		return "($vVarArrType){$inRecv, ${genExpr(inArgs[0].expr)}}"
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
	if (vMethod == "resizeWith" && inRecvTypeKtc != null && inArgs.size >= 2
		&& (inRecvTypeKtc.isArrayLike || inRecvTypeKtc.stripNullable.let { it is KtcType.Ptr && it.inner !is KtcType.Arr })) {
		// RawArray<T> (Ptr(elem)) has no length: realloc the bare pointer and return it.
		val vRawPtrType   = (inRecvTypeKtc.stripNullable as? KtcType.Ptr)?.takeIf { it.inner !is KtcType.Arr }
		val vElemC        = vRawPtrType?.inner?.toCType() ?: arrayElementCTypeKtc(inRecvTypeKtc)
		val vAllocExpr    = genExpr(inArgs[0].expr)
		val vNewSizeExpr  = genExpr(inArgs[1].expr)
		val vT            = tmp()
		val vIfExpr       = resolveAllocatorIface(inArgs[0].expr, vAllocExpr).ifaceExpr
		val vIsRawArray = vRawPtrType != null
		val vSrcPtr     = if (vIsRawArray) inRecv else "$inRecv.ptr"
		preStmts += "$vElemC* ${vT}_ptr = ($vElemC*)((ktc_Allocator_vt*)$vIfExpr.vt)->reallocMem($vIfExpr.obj, $vSrcPtr, sizeof($vElemC) * (size_t)($vNewSizeExpr), ${ktSrcStr()});"
		if (!vIsRawArray) {
			val vVarArrType = varArrTypeName(vElemC)
			val vResult     = tmp()
			preStmts += "$vVarArrType $vResult = {${vT}_ptr, $vNewSizeExpr};"
			return vResult
			}
		return "${vT}_ptr"
		}
	// Closure functor: c.copyWith(alloc) heap-promotes the frame-bound closure → a Ref<Closure<F>> (a
	// ktc_Closure*). One heap block holds the fat pointer header AND the capture struct inline (env points
	// into its own tail), so freeMem(g) releases everything in a single call. The closure can outlive the
	// defining frame and be returned/stored. Refused for a closure that captured a stack local by reference
	// (its address would dangle).
	if (vMethod == "copyWith" && inRecvType != null && inRecvType in closureStructTypes && inArgs.size == 1) {
		if (inRecvType in closureStructEscapeUnsafe)
			codegenError("Cannot heap-promote this closure with copyWith: it captured a local by reference " +
				"(capture(x.asRef())), whose address would dangle once the closure outlives its frame. Capture by value instead.")
		val vBox          = tmp()
		val vSize         = "sizeof(ktc_Closure) + sizeof($inRecvType)"
		val vAllocObjName = (inArgs[0].expr as? NameExpr)?.name
		if (vAllocObjName == "Heap") {
			preStmts += "ktc_Closure* $vBox = (ktc_Closure*)${tMalloc(vSize)};"
			} else {
			val vAllocExpr = genExpr(inArgs[0].expr)
			val vIfExpr    = resolveAllocatorIface(inArgs[0].expr, vAllocExpr).ifaceExpr
			preStmts += "ktc_Closure* $vBox = (ktc_Closure*)((ktc_Allocator_vt*)$vIfExpr.vt)->allocMem($vIfExpr.obj, $vSize, ${ktSrcStr()});"
			}
		preStmts += "if ($vBox) { $vBox->env = (char*)$vBox + sizeof(ktc_Closure); *($inRecvType*)$vBox->env = $inRecv; $vBox->invoke = (void(*)(void))${inRecvType}_invoke_erased; }"
		return vBox
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
		val vIfExpr       = resolveAllocatorIface(inArgs[0].expr, vAllocExpr).ifaceExpr
		preStmts += "$vElemC* ${vT}_ptr = ($vElemC*)((ktc_Allocator_vt*)$vIfExpr.vt)->allocMem($vIfExpr.obj, sizeof($vElemC) * (size_t)($vSrcLen), ${ktSrcStr()});"
		preStmts += "if (${vT}_ptr) memcpy(${vT}_ptr, $vSrcPtr, (size_t)$vSrcLen * sizeof($vElemC));"
		preStmts += "$vVarArrType $vResult = {${vT}_ptr, $vSrcLen};"
		return vResult
		}
	// copyInto: memcpy elements [startIndex, endIndex) of the receiver into destination at destinationOffset.
	// No allocation. Works for Array<T> (VarArr) and RawArray<T> (bare ptr), for both receiver and dest.
	// Args (positional): destination, destinationOffset = 0, startIndex = 0, endIndex (= size for Array;
	// REQUIRED for RawArray, which has no length).
	if (vMethod == "copyInto" && inRecvTypeKtc != null && inArgs.isNotEmpty()) {
		val vSrcCore = inRecvTypeKtc.stripNullable
		val vSrcRaw  = (vSrcCore as? KtcType.Ptr)?.takeIf { it.inner !is KtcType.Arr }   // RawArray<T> receiver
		if (vSrcCore.isArrayLike || vSrcRaw != null) {
			val vElemC  = if (vSrcRaw != null) vSrcRaw.inner.toCType() else arrayElementCTypeKtc(vSrcCore)
			val vSrcPtr = when {
				inDot.obj is NameExpr && inDot.obj.name in trampolinedParams -> "local\$${inDot.obj.name}"
				vSrcRaw != null                                              -> inRecv          // RawArray: bare ptr
				else                                                         -> "$inRecv.ptr"   // Array: VarArr.ptr
				}
			val vDestExpr = genExpr(inArgs[0].expr)
			val vDestCore = inferExprTypeKtc(inArgs[0].expr).stripNullable
			val vDestRaw  = (vDestCore as? KtcType.Ptr)?.inner?.let { it !is KtcType.Arr } == true
			val vDestOff  = if (inArgs.size >= 2) genExpr(inArgs[1].expr) else "0"
			val vStart    = if (inArgs.size >= 3) genExpr(inArgs[2].expr) else "0"
			val vEnd      = when {
				inArgs.size >= 4 -> genExpr(inArgs[3].expr)
				vSrcRaw != null  -> codegenError("RawArray.copyInto requires an explicit endIndex — RawArray has no length to default to.")
				else             -> "$inRecv.len"
				}
			val vDest = tmp()
			val vDestPtr: String
			if (vDestRaw) {
				preStmts += "$vElemC* $vDest = $vDestExpr;"
				vDestPtr = vDest
				} else {
				preStmts += "${varArrTypeName(vElemC)} $vDest = $vDestExpr;"
				vDestPtr = "$vDest.ptr"
				}
			preStmts += "memcpy($vDestPtr + ($vDestOff), $vSrcPtr + ($vStart), (size_t)(($vEnd) - ($vStart)) * sizeof($vElemC));"
			return vDest
			}
		}
	if (vMethod == "fill" && inRecvTypeKtc != null) {
		val vCore = inRecvTypeKtc.stripNullable
		// Array<T>.fill(element, fromIndex = 0, toIndex = size) — length known from VarArr / @Size / trampoline.
		if (vCore.isArrayLike && inArgs.size in 1..3) {
			val vElemC   = arrayElementCTypeKtc(vCore)
			val vObjName = (inDot.obj as? NameExpr)?.name
			val vIsTramp = vObjName != null && vObjName in trampolinedParams
			val vSizedN  = vCore.asArr?.sized
			val vPtr = when {
				vIsTramp        -> "local\$$vObjName"
				vSizedN != null -> inRecv
				else            -> "($inRecv).ptr"
				}
			val vLen = when {
				vIsTramp        -> arrayParamSizeExpr(vObjName)
				vSizedN != null -> vSizedN.toString()
				else            -> "($inRecv).len"
				}
			val vFrom = if (inArgs.size >= 2) genExpr(inArgs[1].expr) else null
			val vTo   = if (inArgs.size >= 3) genExpr(inArgs[2].expr) else vLen
			emitArrayFill(fillStart(vPtr, vFrom), fillCount(vFrom, vTo), vElemC, genExpr(inArgs[0].expr), isZeroLit(inArgs[0].expr))
			return ""
			}
		// RawArray<T>.fill(size, element, fromIndex = 0, toIndex = size) — count supplied explicitly.
		if (vCore is KtcType.Ptr && vCore.inner !is KtcType.Arr && inArgs.size in 2..4) {
			val vElemC = vCore.inner.toCType()
			val vSize  = genExpr(inArgs[0].expr)
			val vFrom  = if (inArgs.size >= 3) genExpr(inArgs[2].expr) else null
			val vTo    = if (inArgs.size >= 4) genExpr(inArgs[3].expr) else vSize
			emitArrayFill(fillStart(inRecv, vFrom), fillCount(vFrom, vTo), vElemC, genExpr(inArgs[1].expr), isZeroLit(inArgs[1].expr))
			return ""
			}
		}
	if ((vMethod == "get" || vMethod == "set") && inRecvTypeKtc != null && inRecvTypeKtc.isArrayLike) {
		val vIdx        = inArgs.getOrNull(0)?.let { genExpr(it.expr) } ?: "0"
		val vRecvName   = (inDot.obj as? NameExpr)?.name
		val vIsTramp    = vRecvName != null && vRecvName in trampolinedParams
		val vSizedN     = inRecvTypeKtc.asArr?.sized
		val vIsSized    = vSizedN != null
		// arr.get(i)/set(i,v) are the method spelling of arr[i] — apply the SAME default-ON bounds
		// check the IndexExpr path applies, so the safety net isn't silently lost. (RawArray, having
		// no length, is excluded by the isArrayLike guard and stays unchecked, same as bare-pointer [].)
		val vLen = when {
			vIsTramp        -> arrayParamSizeExpr(vRecvName)
			vSizedN != null -> vSizedN.toString()
			else            -> "($inRecv).len"
			}
		val vCheckedIdx = wrapBoundsIdx(vIdx, vLen, inArgs.getOrNull(0)?.expr, inDot.obj)
		val vAccessExpr = if (vIsTramp || vIsSized) "$inRecv[$vCheckedIdx]" else "$inRecv.ptr[$vCheckedIdx]"
		if (vMethod == "get") return vAccessExpr
		val vValExpr = inArgs.getOrNull(1)?.let { genExpr(it.expr) } ?: "0"
		return "($vAccessExpr = $vValExpr)"
		}

	return null
	}
