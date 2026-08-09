package com.bitsycore.ktc.codegen.expression

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.codegen.*
import com.bitsycore.ktc.types.KtcType

// Print helpers and string template expression codegen.
// toString/StringBuffer append helpers are in StringToString.kt.

/* True when expr is a c.fnName(...) passthrough call whose return type is unknown. */
internal fun CCodeGen.isCPassthroughCall(expr: Expr): Boolean {
    if (expr !is CallExpr) return false
    val callee = expr.callee
    if (callee !is DotExpr) return false
    val obj = callee.obj
    if (obj !is NameExpr) return false
    return isCInteropName(obj.name) && lookupVar(obj.name) == null
}

internal fun CCodeGen.genPrintln(args: List<Arg>): String {
	if (args.isEmpty()) return "printf(\"\\n\")"
	return genPrintCall(args, newline = true)
	}

internal fun CCodeGen.genPrint(args: List<Arg>): String {
	if (args.isEmpty()) return "(void)0"
	return genPrintCall(args, newline = false)
	}

internal fun CCodeGen.genPrintCall(args: List<Arg>, newline: Boolean): String {
	val arg = args[0].expr
	val nl  = if (newline) "\\n" else ""

	if (arg is StrTemplateExpr) return genPrintfFromTemplate(arg, nl)

	val t        = inferExprType(arg) ?: "Int"
	val tKtc     = inferExprTypeKtc(arg) ?: KtcType.Prim(KtcType.PrimKind.Int)
	val tKtcCore = tKtc.stripNullable
	val expr     = genExpr(arg)

	if (tKtc is KtcType.Nullable) {
		val safeExpr = if (!isSimpleCExpr(expr)) {
			val vTmp = tmp(); preStmts += "${cTypeStr(t)} $vTmp = ($expr);"; vTmp
			} else expr
		val isPtrNull = tKtc.inner is KtcType.Ptr && !isValueNullableKtc(tKtc)
		val hasExpr   = if (isPtrNull) "$safeExpr != NULL"
			else if (isValueNullableKtc(tKtc)) "$safeExpr.tag == ktc_SOME"
			else "${safeExpr}\$has"
		val fmt = printfFmt(tKtcCore) + nl
		val a   = printfArg(safeExpr, tKtcCore)
		return "($hasExpr ? printf(\"$fmt\", $a) : printf(\"null$nl\"))"
		}

	if (classes.containsKey(t) && classes[t]!!.isData) {
		val maxLen = toStringMaxLen(t)
		if (maxLen != null && maxLen <= 512) {
			val buf = tmp(); val vTmp = tmp()
			preStmts += "${cTypeStr(t)} $vTmp = ($expr);"
			preStmts += "ktc_Char ${buf}[$maxLen];"
			preStmts += "ktc_StrBuf ${buf}_sb = {${buf}, 0, $maxLen};"
			preStmts += "${typeFlatName(t)}_toString(&$vTmp, &${buf}_sb);"
			return "printf(\"%.*s$nl\", (ktc_Int)${buf}_sb.len, ${buf}_sb.ptr)"
			}
		val buf = tmp(); val vTmp = tmp()
		preStmts += "${cTypeStr(t)} $vTmp = ($expr);"
		preStmts += "ktc_StrBuf ${buf}_sb = {NULL, 0, 0};"
		preStmts += "${typeFlatName(t)}_toString(&$vTmp, &${buf}_sb);"
		preStmts += "ktc_Char* $buf = (ktc_Char*)ktc_core_alloca(${buf}_sb.len + 1);"
		preStmts += "${buf}_sb = (ktc_StrBuf){${buf}, 0, ${buf}_sb.len + 1};"
		preStmts += "${typeFlatName(t)}_toString(&$vTmp, &${buf}_sb);"
		return "printf(\"%.*s$nl\", (ktc_Int)${buf}_sb.len, ${buf}_sb.ptr)"
		}
	if (classes.containsKey(t) || objects.containsKey(t) || interfaces.containsKey(t)) {
		val str    = genToString(expr, t)
		val tmpStr = tmp()
		preStmts += "ktc_String $tmpStr = $str;"
		return "printf(\"%.*s$nl\", (ktc_Int)${tmpStr}.len, ${tmpStr}.ptr)"
		}
	val indirectBase = (tKtc as? KtcType.Ptr)?.inner?.let { it as? KtcType.User }?.baseName
	if (indirectBase != null && classes[indirectBase]?.isData == true) {
		val maxLen = toStringMaxLen(indirectBase)
		if (maxLen != null && maxLen <= 512) {
			val buf = tmp()
			preStmts += "ktc_Char ${buf}[$maxLen];"
			preStmts += "ktc_StrBuf ${buf}_sb = {${buf}, 0, $maxLen};"
			preStmts += "${typeFlatName(indirectBase)}_toString($expr, &${buf}_sb);"
			return "printf(\"%.*s$nl\", (ktc_Int)${buf}_sb.len, ${buf}_sb.ptr)"
			}
		val buf = tmp()
		preStmts += "ktc_StrBuf ${buf}_sb = {NULL, 0, 0};"
		preStmts += "${typeFlatName(indirectBase)}_toString($expr, &${buf}_sb);"
		preStmts += "ktc_Char* $buf = (ktc_Char*)ktc_core_alloca(${buf}_sb.len + 1);"
		preStmts += "${buf}_sb = (ktc_StrBuf){${buf}, 0, ${buf}_sb.len + 1};"
		preStmts += "${typeFlatName(indirectBase)}_toString($expr, &${buf}_sb);"
		return "printf(\"%.*s$nl\", (ktc_Int)${buf}_sb.len, ${buf}_sb.ptr)"
		}
	if (t == "String") {
		val safeExpr = if (!isSimpleCExpr(expr)) { val vTmp = tmp(); preStmts += "ktc_String $vTmp = ($expr);"; vTmp } else expr
		return "printf(\"%.*s$nl\", (ktc_Int)($safeExpr).len, ($safeExpr).ptr)"
		}
	if (t in enums) {
		val cName    = typeFlatName(t)
		val ei       = enums[t]!!
		val safeExpr = if (!isSimpleCExpr(expr)) { val vTmp = tmp(); preStmts += "$cName $vTmp = ($expr);"; vTmp } else expr
		// Simple enum: int ordinal → index names[]. Full enum: struct value → read .name.
		return if (ei.isSimple)
			"printf(\"%.*s$nl\", (ktc_Int)${cName}_names[$safeExpr].len, ${cName}_names[$safeExpr].ptr)"
		else
			"printf(\"%.*s$nl\", (ktc_Int)($safeExpr).name.len, ($safeExpr).name.ptr)"
		}
	val fmt = printfFmt(tKtcCore) + nl
	val a   = printfArg(expr, tKtcCore)
	return "printf(\"$fmt\", $a)"
	}

internal fun CCodeGen.genPrintfFromTemplate(tmpl: StrTemplateExpr, nl: String): String {
	val fmt      = StringBuilder()
	val argsList = mutableListOf<String>()
	for (part in tmpl.parts) {
		when (part) {
			is LitPart  -> fmt.append(escapeStr(part.text))
			is ExprPart -> {
				val isCPassthroughP = isCPassthroughCall(part.expr)
				val tKtc     = inferExprTypeKtc(part.expr) ?: if (isCPassthroughP) KtcType.Str else KtcType.Prim(KtcType.PrimKind.Int)
				val tKtcCore = tKtc.stripNullable
				fmt.append(printfFmt(tKtcCore))
				val exprStr  = genExpr(part.expr)
				if (isCPassthroughP && inferExprTypeKtc(part.expr) == null) {
					argsList += exprStr
				} else when (tKtcCore) {
					is KtcType.Str -> {
						val s = if (!isSimpleCExpr(exprStr)) {
							val v = tmp(); preStmts += "ktc_String $v = ($exprStr);"; v
							} else exprStr
						argsList += "(ktc_Int)($s).len, ($s).ptr"
						}
					is KtcType.User if tKtcCore.kind == KtcType.UserKind.Enum -> {
						val cName = typeFlatName(tKtcCore.baseName)
						val ei    = enums[tKtcCore.baseName]
						val s = if (!isSimpleCExpr(exprStr)) {
							val v = tmp(); preStmts += "$cName $v = ($exprStr);"; v
							} else exprStr
						// Simple enum: $s is the int ordinal → index into names[]. Full enum: $s is a struct → read .name.
						if (ei != null && !ei.isSimple)
							argsList += "(ktc_Int)($s).name.len, ($s).name.ptr"
						else
							argsList += "(ktc_Int)${cName}_names[$s].len, ${cName}_names[$s].ptr"
						}
					else -> argsList += printfArg(exprStr, tKtcCore)
					}
				}
			}
		}
	fmt.append(nl)
	val argsStr = if (argsList.isNotEmpty()) ", " + argsList.joinToString(", ") else ""
	return "printf(\"$fmt\"$argsStr)"
	}

// ── string template (returns ktc_String via preStmts) ──────────────────

/* Append a string template directly to an external StrBuf (e.g. sb parameter in toString).
No local allocation - the caller provides the buffer. */
internal fun CCodeGen.genStrTemplateToSb(e: StrTemplateExpr, sbExpr: String) {
    for (part in e.parts) {
        when (part) {
            is LitPart  -> preStmts += "ktc_core_sb_append_str($sbExpr, ktc_core_str(\"${escapeStr(part.text)}\"));"
            is ExprPart -> {
                val isCPassthroughS = isCPassthroughCall(part.expr)
                val tKtc = inferExprTypeKtc(part.expr) ?: if (isCPassthroughS) KtcType.Str else KtcType.Prim(KtcType.PrimKind.Int)
                val expr = genExpr(part.expr)
                val append = if (isCPassthroughS && inferExprTypeKtc(part.expr) == null)
                    "ktc_core_sb_append_cstr($sbExpr, $expr);"
                else genSbAppendKtc(sbExpr, expr, tKtc)
                preStmts += append
                }
            }
        }
    }

internal fun CCodeGen.genStrTemplate(e: StrTemplateExpr): String {
	val buf = tmp()

	data class PartData(val lit: String? = null, val sbAppend: String? = null, val sizeContrib: String? = null)

	val parts = mutableListOf<PartData>()
	for (part in e.parts) {
		when (part) {
			is LitPart -> {
				val last = parts.lastOrNull()
				if (last?.lit != null) parts[parts.lastIndex] = PartData(lit = last.lit + part.text)
				else parts += PartData(lit = part.text)
				}
			is ExprPart -> {
				val isCPassthroughS = isCPassthroughCall(part.expr)
				val tKtc = inferExprTypeKtc(part.expr) ?: if (isCPassthroughS) KtcType.Str else KtcType.Prim(KtcType.PrimKind.Int)
				val isCPassNullInfer = isCPassthroughS && inferExprTypeKtc(part.expr) == null
				var expr = genExpr(part.expr)

				// Evaluate a non-trivial interpolated value exactly once (count/fill passes + nullable
				// append would otherwise re-run it). (B8)
				if (!isCPassNullInfer) expr = spillTemplatePart(expr, tKtc)

				// Compute size contribution for computed-size single-pass optimization
				var sizeContrib: String? = null
				if (!isCPassNullInfer && tKtc !is KtcType.Nullable) {
					val tCore = tKtc.stripNullable
					if (tCore is KtcType.Str) {
						sizeContrib = "($expr).len"
					} else {
						val t = inferExprType(part.expr)
						if (t != null) {
							val ml = toStringMaxLen(t)
							if (ml != null) sizeContrib = "$ml"
						}
					}
				}

				val append = if (isCPassNullInfer)
					"ktc_core_sb_append_cstr(&${buf}_sb, $expr);"
				else genSbAppendKtc("&${buf}_sb", expr, tKtc)
				parts += PartData(sbAppend = append, sizeContrib = sizeContrib)
				}
			}
		}
	val maxLen = templateMaxLen(e)
	if (maxLen != null && maxLen <= 512) {
		preStmts += "ktc_Char* $buf = (ktc_Char*)ktc_core_alloca($maxLen + 1);"
		preStmts += "ktc_StrBuf ${buf}_sb = {${buf}, 0, $maxLen};"
		for (p in parts) {
			when {
				p.lit      != null -> preStmts += "ktc_core_sb_append_str(&${buf}_sb, ktc_core_str(\"${escapeStr(p.lit)}\"));"
				p.sbAppend != null -> preStmts += p.sbAppend
				}
			}
		return "ktc_core_sb_to_string(&${buf}_sb)"
		}
	// Computed-size single-pass: sum compile-time maxLen constants with runtime .len for Strings
	val allComputable = parts.all { it.lit != null || it.sizeContrib != null }
	if (allComputable) {
		var constSize = 0
		val dynamicSizes = mutableListOf<String>()
		for (p in parts) {
			if (p.lit != null) constSize += p.lit.length
			else if (p.sizeContrib != null) {
				val asInt = p.sizeContrib.toIntOrNull()
				if (asInt != null) constSize += asInt
				else dynamicSizes += p.sizeContrib
			}
		}
		val sizeExpr = buildString {
			if (constSize > 0 || dynamicSizes.isEmpty()) append(constSize)
			for (d in dynamicSizes) {
				if (isNotEmpty()) append(" + ")
				append(d)
			}
		}
		val sizeVar = tmp()
		preStmts += "ktc_Int $sizeVar = $sizeExpr;"
		preStmts += "ktc_Char* $buf = (ktc_Char*)ktc_core_alloca($sizeVar + 1);"
		preStmts += "ktc_StrBuf ${buf}_sb = {${buf}, 0, $sizeVar};"
		for (p in parts) {
			when {
				p.lit      != null -> preStmts += "ktc_core_sb_append_str(&${buf}_sb, ktc_core_str(\"${escapeStr(p.lit)}\"));"
				p.sbAppend != null -> preStmts += p.sbAppend
				}
			}
		return "ktc_core_sb_to_string(&${buf}_sb)"
		}
	// Two-pass fallback: count with NULL buffer, then alloca exact size
	preStmts += "ktc_StrBuf ${buf}_sb = {NULL, 0, 0};"
	for (p in parts) {
		when {
			p.lit      != null -> preStmts += "ktc_core_sb_append_str(&${buf}_sb, ktc_core_str(\"${escapeStr(p.lit)}\"));"
			p.sbAppend != null -> preStmts += p.sbAppend
			}
		}
	preStmts += "ktc_Char* $buf = (ktc_Char*)ktc_core_alloca(${buf}_sb.len + 1);"
	preStmts += "${buf}_sb = (ktc_StrBuf){${buf}, 0, ${buf}_sb.len + 1};"
	for (p in parts) {
		when {
			p.lit      != null -> preStmts += "ktc_core_sb_append_str(&${buf}_sb, ktc_core_str(\"${escapeStr(p.lit)}\"));"
			p.sbAppend != null -> preStmts += p.sbAppend
			}
		}
	return "ktc_core_sb_to_string(&${buf}_sb)"
	}
