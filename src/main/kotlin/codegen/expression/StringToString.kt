package com.bitsycore.ktc.codegen.expression

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.codegen.*
import com.bitsycore.ktc.types.KtcType

// toString dispatch, max-length inference, and StringBuffer append helpers.

/* Integer primitive toString lowering descriptor:
   (ktc_core_sb_append_* function name, stack buffer size in bytes).
   Mapped over genToString's `when (type)` to avoid 8 near-identical branches. */
private val kIntegerToStringInfo: Map<String, Pair<String, Int>> = mapOf(
	"Byte"    to ("ktc_core_sb_append_byte"   to 6),
	"Short"   to ("ktc_core_sb_append_short"  to 7),
	"Int"     to ("ktc_core_sb_append_int"    to 12),
	"Long"    to ("ktc_core_sb_append_long"   to 21),
	"UByte"   to ("ktc_core_sb_append_ubyte"  to 4),
	"UShort"  to ("ktc_core_sb_append_ushort" to 6),
	"UInt"    to ("ktc_core_sb_append_uint"   to 11),
	"ULong"   to ("ktc_core_sb_append_ulong"  to 21),
	)

private val kToStringPrimitiveMaxLen = mapOf(
	"Boolean" to 5,   // "false"
	"Byte"    to 4,   // "-128"
	"UByte"   to 3,   // "255"
	"Short"   to 6,   // "-32768"
	"UShort"  to 5,   // "65535"
	"Int"     to 11,  // "-2147483648"
	"UInt"    to 10,  // "4294967295"
	"Long"    to 20,  // "-9223372036854775808"
	"ULong"   to 20,  // "18446744073709551615"
	"Float"   to 24,  // sb_append_float uses 24-byte buffer
	"Double"  to 32,  // sb_append_double uses 32-byte buffer
	"Char"    to 8    // %c format buffer
	)

internal fun CCodeGen.toStringMaxLen(baseType: String, visited: MutableSet<String> = mutableSetOf()): Int? {
	val t = baseType.removeSuffix("?").removeSuffix("*")
	if (t in visited) return null
	visited.add(t)
	val primMax = kToStringPrimitiveMaxLen[t]
	if (primMax != null) { visited.remove(t); return primMax }
	val ci = classes[t]
	if (ci != null && ci.isValue) {
		val underlyingName = ci.ctorProps.first().typeRef.name
		visited.remove(t); return toStringMaxLen(underlyingName, visited)
	}
	if (ci != null && ci.isData) {
		var total = t.length + 2  // "Name(" + ")"
		for ((i, prop) in ci.props.withIndex()) {
			val (name, propType) = prop
			val tBase      = resolveTypeName(propType).toInternalStr
			val baseClean  = tBase.removeSuffix("?")
			val fieldMax   = toStringMaxLen(baseClean, visited)
			if (fieldMax == null) { visited.remove(t); return null }
			val prefixLen  = name.length + if (i == 0) 1 else 3  // "name=" or ", name="
			total += prefixLen
			total += if (propType.nullable) maxOf(fieldMax, 4) else fieldMax
			}
		visited.remove(t); return total
		}
		// Non-data class with custom toString: inspect the return template
		if (ci != null && !ci.isData) {
			val vDecl = allClassDecls[t] ?: allClassDecls[ci.name]
			val vToString = vDecl?.members?.filterIsInstance<FunDecl>()?.find { it.name == "toString" }
			if (vToString != null) {
				val vLast = vToString.body?.stmts?.lastOrNull()
				val vRet = when (vLast) {
					is ReturnStmt -> vLast.value
					is ExprStmt  -> vLast.expr
					else         -> null
					}
				if (vRet is StrTemplateExpr) {
					val vTmplMax = templateMaxLen(vRet)
					if (vTmplMax != null) { visited.remove(t); return vTmplMax }
					}
				}
			}
	if (classes.containsKey(t) || objects.containsKey(t) || interfaces.containsKey(t)) {
		visited.remove(t); return ktDisplayName(t).length + 10
		}
	visited.remove(t); return null
	}

/** Compute max output length for a string template, or null if any part is unbounded. */
internal fun CCodeGen.templateMaxLen(tmpl: StrTemplateExpr): Int? {
	var total = 0
	for (part in tmpl.parts) {
		when (part) {
			is LitPart  -> total += part.text.length
			is ExprPart -> {
				val t   = inferExprType(part.expr) ?: return null
				val max = toStringMaxLen(t) ?: return null
				total += max
				}
			}
		}
	return total
	}

/* Stamp out an integer-toString lowering for [recv]: a stack buffer of [inSz]
   chars wrapped in a ktc_StrBuf, then [inAppendFn]() appends the digits. */
private fun CCodeGen.emitIntegerToString(recv: String, inAppendFn: String, inSz: Int): String {
	val buf = tmp()
	preStmts += "ktc_Char ${buf}[$inSz];"
	preStmts += "ktc_StrBuf ${buf}_sb = {${buf}, 0, $inSz};"
	preStmts += "$inAppendFn(&${buf}_sb, $recv);"
	return "ktc_core_sb_to_string(&${buf}_sb)"
	}

// ── toString dispatch ────────────────────────────────────────────────────

internal fun CCodeGen.genToStringKtc(recv: String, type: KtcType): String = genToString(recv, type.toInternalStr)

internal fun CCodeGen.genToString(recv: String, type: String): String {
	val base   = type.removeSuffix("*").removeSuffix("?")
	val valCi  = classes[base]
	if (valCi != null && valCi.isValue) {
		return genToString(recv, valCi.ctorProps.first().typeRef.name)
	}
	val cName  = typeFlatName(base)
	val isPtr  = parseResolvedTypeName(type) is KtcType.Ptr
	// Enum toString: simple → names[ordinal], full → struct.name (the entry name as a ktc_String).
	val ei = enums[base]
	if (ei != null) {
		return if (ei.isSimple) "${cName}_names[$recv]" else "($recv).name"
		}
	if (classes.containsKey(base) && classes[base]!!.isData) {
		val maxLen = toStringMaxLen(base)
		if (maxLen != null && maxLen <= 512) {
			val buf = tmp(); val vTmp = tmp()
			preStmts += "${cTypeStr(base)} $vTmp = ($recv);"
			preStmts += "ktc_Char ${buf}[${maxLen + 1}];"
			preStmts += "ktc_StrBuf ${buf}_sb = {${buf}, 0, $maxLen};"
			preStmts += "${cName}_toString(&$vTmp, &${buf}_sb);"
			return "ktc_core_sb_to_string(&${buf}_sb)"
			}
		val buf = tmp(); val vTmp = tmp()
		preStmts += "${cTypeStr(base)} $vTmp = ($recv);"
		preStmts += "ktc_StrBuf ${buf}_sb = {NULL, 0, 0};"
		preStmts += "${cName}_toString(&$vTmp, &${buf}_sb);"
		preStmts += "ktc_Char* $buf = (ktc_Char*)ktc_core_alloca(${buf}_sb.len + 1);"
		preStmts += "${buf}_sb = (ktc_StrBuf){${buf}, 0, ${buf}_sb.len + 1};"
		preStmts += "${cName}_toString(&$vTmp, &${buf}_sb);"
		return "ktc_core_sb_to_string(&${buf}_sb)"
		}
	if (classes.containsKey(base) && classes[base]!!.methods.any { it.name == "toString" }) {
		val buf      = tmp()
		val selfExpr = if (isPtr) recv else "&$recv"
		preStmts += "ktc_StrBuf ${buf}_sb = {NULL, 0, 0};"
		preStmts += "${cName}_toString($selfExpr, &${buf}_sb);"
		preStmts += "ktc_Char* $buf = (ktc_Char*)ktc_core_alloca(${buf}_sb.len + 1);"
		preStmts += "${buf}_sb = (ktc_StrBuf){${buf}, 0, ${buf}_sb.len + 1};"
		preStmts += "${cName}_toString($selfExpr, &${buf}_sb);"
		return "ktc_core_sb_to_string(&${buf}_sb)"
		}
	if (objects.containsKey(base) && objects[base]!!.methods.any { it.name == "toString" }) {
		return "${cName}_toString()"
		}
	if (objects.containsKey(base)) {
		val maxLen   = toStringMaxLen(base)
		val buf      = tmp()
		val selfExpr = if (isPtr) recv else "&$recv"
		if (maxLen != null) {
			preStmts += "ktc_Char ${buf}[${maxLen + 1}];"
			preStmts += "ktc_StrBuf ${buf}_sb = {${buf}, 0, $maxLen};"
			preStmts += "${cName}_toString($selfExpr, &${buf}_sb);"
			} else {
			preStmts += "ktc_StrBuf ${buf}_sb = {NULL, 0, 0};"
			preStmts += "${cName}_toString($selfExpr, &${buf}_sb);"
			preStmts += "ktc_Char* $buf = (ktc_Char*)ktc_core_alloca(${buf}_sb.len + 1);"
			preStmts += "${buf}_sb = (ktc_StrBuf){${buf}, 0, ${buf}_sb.len + 1};"
			preStmts += "${cName}_toString($selfExpr, &${buf}_sb);"
			}
		return "ktc_core_sb_to_string(&${buf}_sb)"
		}
	kIntegerToStringInfo[type]?.let { (appendFn, sz) ->
		return emitIntegerToString(recv, appendFn, sz)
		}
	return when (type) {
		"Float" -> {
			val buf = tmp()
			preStmts += "ktc_Char ${buf}[24];"
			"ktc_core_float_to_string($buf, 24, $recv)"
			}
		"Double" -> {
			val buf = tmp()
			preStmts += "ktc_Char ${buf}[32];"
			"ktc_core_double_to_string($buf, 32, $recv)"
			}
		"Boolean" -> {
			"(($recv) ? ktc_core_str(\"true\") : ktc_core_str(\"false\"))"
			}
		"Char" -> {
			val buf = tmp(); val lenVar = tmp()
			preStmts += "ktc_Char ${buf}[8];"
			preStmts += "ktc_Int $lenVar = snprintf($buf, 8, \"%c\", (ktc_Char)($recv));"
			"ktc_core_string_wrap($buf, $lenVar)"
			}
		"String" -> recv
		else -> {
			val base2   = type.removeSuffix("*").removeSuffix("?")
			val hasHash = classes.containsKey(base2) || objects.containsKey(base2)
			val hasIface = interfaces.containsKey(base2)
			val isObj   = objects.containsKey(base2)
			val maxLen  = toStringMaxLen(base2)
			val sz      = if (maxLen != null) maxLen + 2 else 64
			if (hasHash) {
				val cName2  = typeFlatName(base2)
				val buf     = tmp()
				val isPtr2  = parseResolvedTypeName(type) is KtcType.Ptr
				val hcExpr  = if (isObj) "${cName2}_hashCode()"
					else "${cName2}_hashCode(${if (isPtr2) recv else "&$recv"})"
				preStmts += "ktc_Char ${buf}[$sz];"
				preStmts += "snprintf($buf, $sz, \"%s@%x\", \"${ktDisplayName(base2)}\", $hcExpr);"
				"ktc_core_str($buf)"
				} else if (hasIface) {
				val buf = tmp()
				val hcExpr = if (base2 in simpleUnionInterfaces) genSimpleUnionDispatch(base2, recv, "hashCode", "")
					else "$recv.vt->hashCode(${ifaceVtableSelf(base2, recv)})"
				preStmts += "ktc_Char ${buf}[$sz];"
				preStmts += "snprintf($buf, $sz, \"%s@%x\", \"${ktDisplayName(base2)}\", $hcExpr);"
				"ktc_core_str($buf)"
				} else {
				"ktc_core_str(\"<$type>\")"
				}
			}
		}
	}

// ── toString into a StringBuffer (single pass) ──────────────────────────

internal fun CCodeGen.genToStringInto(recv: String, type: String, sb: String): String {
	val valCi = classes[type]
	if (valCi != null && valCi.isValue) {
		return genToStringInto(recv, valCi.ctorProps.first().typeRef.name, sb)
	}
	if (classes.containsKey(type) && classes[type]!!.isData) {
		val vTmp = tmp()
		preStmts += "${cTypeStr(type)} $vTmp = ($recv);"
		preStmts += "${typeFlatName(type)}_toString(&$vTmp, &$sb);"
		return "ktc_core_sb_to_string(&$sb)"
		}
	when (type) {
		"Byte"    -> preStmts += "ktc_core_sb_append_byte(&$sb, $recv);"
		"Short"   -> preStmts += "ktc_core_sb_append_short(&$sb, $recv);"
		"Int"     -> preStmts += "ktc_core_sb_append_int(&$sb, $recv);"
		"Long"    -> preStmts += "ktc_core_sb_append_long(&$sb, $recv);"
		"Float"   -> preStmts += "ktc_core_sb_append_float(&$sb, $recv);"
		"Double"  -> preStmts += "ktc_core_sb_append_double(&$sb, $recv);"
		"Boolean" -> preStmts += "ktc_core_sb_append_bool(&$sb, $recv);"
		"Char"    -> preStmts += "ktc_core_sb_append_char(&$sb, $recv);"
		"String"  -> return recv
		"UByte"   -> preStmts += "ktc_core_sb_append_ubyte(&$sb, $recv);"
		"UShort"  -> preStmts += "ktc_core_sb_append_ushort(&$sb, $recv);"
		"UInt"    -> preStmts += "ktc_core_sb_append_uint(&$sb, $recv);"
		"ULong"   -> preStmts += "ktc_core_sb_append_ulong(&$sb, $recv);"
		else -> {
			val base    = type.removeSuffix("*").removeSuffix("?")
			val hasHash = classes.containsKey(base) || objects.containsKey(base)
			val hasIface = interfaces.containsKey(base)
			if (hasHash) {
				val cName  = typeFlatName(base)
				val isPtr  = parseResolvedTypeName(type) is KtcType.Ptr
				val isObj  = objects.containsKey(base)
				if (isObj) {
					val selfExpr = if (isPtr) recv else "&$recv"
					preStmts += "${cName}_toString($selfExpr, &$sb);"
					} else {
					val hcExpr = "${cName}_hashCode(${if (isPtr) recv else "&$recv"})"
					val buf = tmp()
					preStmts += "ktc_Char ${buf}[64];"
					preStmts += "snprintf($buf, 64, \"%s@%x\", \"${ktDisplayName(base)}\", $hcExpr);"
					preStmts += "ktc_core_sb_append_cstr(&$sb, $buf);"
					}
				} else if (hasIface) {
				val buf = tmp()
				val hcExpr = if (base in simpleUnionInterfaces) genSimpleUnionDispatch(base, recv, "hashCode", "")
					else "$recv.vt->hashCode(${ifaceVtableSelf(base, recv)})"
				preStmts += "ktc_Char ${buf}[64];"
				preStmts += "snprintf($buf, 64, \"%s@%x\", \"${ktDisplayName(base)}\", $hcExpr);"
				preStmts += "ktc_core_sb_append_cstr(&$sb, $buf);"
				} else {
				preStmts += "ktc_core_sb_append_str(&$sb, ktc_core_str(\"<$type>\"));"
				}
			}
		}
	return "ktc_core_sb_to_string(&$sb)"
	}

// ── StrBuf append helper ─────────────────────────────────────────────────

// Spill a non-trivial interpolated value into a temp (declared via preStmts) so it is evaluated
// exactly once: the two-pass count/fill template lowering emits each append twice, and the nullable
// append embeds the value twice (tag check + value), so `${f()}` would otherwise run f() 2–4×. (B8)
// Returns the expression to use downstream — the temp name, or the original when it is a simple
// expression or a form that must stay a bare name (the generic-optional `name$has` dual variable).
internal fun CCodeGen.spillTemplatePart(inExpr: String, inType: KtcType): String {
	val vCanSpill = when {
		inType !is KtcType.Nullable -> true
		isValueNullableKtc(inType)  -> true
		inType.inner is KtcType.Ptr -> true
		else                        -> false
	}
	if (isSimpleCExpr(inExpr) || !vCanSpill) return inExpr
	val vCType = when {
		inType is KtcType.Nullable && isValueNullableKtc(inType) -> optCTypeName(inType.inner.toInternalStr)
		inType is KtcType.Nullable                              -> cTypeStr(inType.inner)
		else                                                    -> cTypeStr(inType)
	}
	val v = tmp(); preStmts += "$vCType $v = ($inExpr);"; return v
}

internal fun CCodeGen.genSbAppend(sbRef: String, expr: String, type: String): String =
	genSbAppendKtc(sbRef, expr, parseResolvedTypeName(type))

internal fun CCodeGen.genSbAppendKtc(sbRef: String, expr: String, type: KtcType): String {
	if (type is KtcType.Nullable) {
		val base = type.inner
		if (isValueNullableKtc(type)) {
			val inner = genSbAppendKtc(sbRef, "($expr).value", base).removeSuffix(";")
			return "if (($expr).tag == ktc_SOME) { $inner; } else { ktc_core_sb_append_str($sbRef, ktc_core_str(\"null\")); }"
			} else if (base is KtcType.Ptr) {
			val inner = genSbAppendKtc(sbRef, expr, base).removeSuffix(";")
			return "if ($expr != NULL) { $inner; } else { ktc_core_sb_append_str($sbRef, ktc_core_str(\"null\")); }"
			} else {
			val inner = genSbAppendKtc(sbRef, expr, base).removeSuffix(";")
			return "if (${expr}\$has) { $inner; } else { ktc_core_sb_append_str($sbRef, ktc_core_str(\"null\")); }"
			}
		}
	return when (type) {
		is KtcType.Prim -> when (type.kind) {
			KtcType.PrimKind.Byte    -> "ktc_core_sb_append_byte($sbRef, $expr);"
			KtcType.PrimKind.Short   -> "ktc_core_sb_append_short($sbRef, $expr);"
			KtcType.PrimKind.Int     -> "ktc_core_sb_append_int($sbRef, $expr);"
			KtcType.PrimKind.Long    -> "ktc_core_sb_append_long($sbRef, $expr);"
			KtcType.PrimKind.Float   -> "ktc_core_sb_append_float($sbRef, $expr);"
			KtcType.PrimKind.Double  -> "ktc_core_sb_append_double($sbRef, $expr);"
			KtcType.PrimKind.Boolean -> "ktc_core_sb_append_bool($sbRef, $expr);"
			KtcType.PrimKind.Char    -> "ktc_core_sb_append_char($sbRef, $expr);"
			KtcType.PrimKind.UByte   -> "ktc_core_sb_append_ubyte($sbRef, $expr);"
			KtcType.PrimKind.UShort  -> "ktc_core_sb_append_ushort($sbRef, $expr);"
			KtcType.PrimKind.UInt    -> "ktc_core_sb_append_uint($sbRef, $expr);"
			KtcType.PrimKind.ULong   -> "ktc_core_sb_append_ulong($sbRef, $expr);"
			KtcType.PrimKind.Rune    -> "ktc_core_sb_append_int($sbRef, $expr);"
			}
		is KtcType.Str  -> "ktc_core_sb_append_str($sbRef, $expr);"
		is KtcType.User -> {
			if (type.kind == KtcType.UserKind.ValueClass) {
				val ci = classes[type.baseName]
				if (ci != null) {
					val underlyingType = parseResolvedTypeName(ci.ctorProps.first().typeRef.name)
					return genSbAppendKtc(sbRef, expr, underlyingType)
				}
			}
			if (type.kind == KtcType.UserKind.Enum) {
				val ei = enums[type.baseName]
				val cName = typeFlatName(type.baseName)
				return if (ei != null && !ei.isSimple)
					"ktc_core_sb_append_str($sbRef, ($expr).name);"
				else
					"ktc_core_sb_append_str($sbRef, ${cName}_names[$expr]);"
				}
			if (type.kind == KtcType.UserKind.DataClass) {
				val baseName = type.baseName
				if (classes.containsKey(baseName)) {
					val vTmp = tmp()
					"{ ${type.toCType()} $vTmp = ($expr); ${typeFlatName(baseName)}_toString(&$vTmp, $sbRef); }"
					} else "ktc_core_sb_append_str($sbRef, ktc_core_str(\"<${type.toCType()}>\"));"
				} else {
				val baseName = type.baseName
				val typeStr  = type.toCType()
				if (classes.containsKey(baseName) || objects.containsKey(baseName)) {
					val buf   = tmp()
					val cName = typeFlatName(baseName)
					preStmts += "ktc_Char ${buf}[64];"
					preStmts += "snprintf($buf, 64, \"%s@%x\", \"${ktDisplayName(baseName)}\", ${cName}_hashCode(&$expr));"
					"ktc_core_sb_append_cstr($sbRef, $buf);"
					} else if (interfaces.containsKey(baseName)) {
					val buf = tmp()
					val hcExpr = if (baseName in simpleUnionInterfaces) genSimpleUnionDispatch(baseName, expr, "hashCode", "")
						else "$expr.vt->hashCode(${ifaceVtableSelf(baseName, expr)})"
					preStmts += "ktc_Char ${buf}[64];"
					preStmts += "snprintf($buf, 64, \"%s@%x\", \"${ktDisplayName(baseName)}\", $hcExpr);"
					"ktc_core_sb_append_cstr($sbRef, $buf);"
					} else "ktc_core_sb_append_str($sbRef, ktc_core_str(\"<$typeStr>\"));"
				}
			}
		is KtcType.Ptr -> {
			val base    = type.inner
			val baseStr = base.toInternalStr
			if (base is KtcType.User && base.kind == KtcType.UserKind.DataClass && classes.containsKey(baseStr)) {
				val vTmp = tmp()
				"{ ${base.toCType()} $vTmp = (*$expr); ${typeFlatName(baseStr)}_toString(&$vTmp, $sbRef); }"
				} else if (classes.containsKey(baseStr) || objects.containsKey(baseStr)) {
				val buf   = tmp()
				val cName = typeFlatName(baseStr)
				preStmts += "ktc_Char ${buf}[64];"
				preStmts += "snprintf($buf, 64, \"%s@%x\", \"${ktDisplayName(baseStr)}\", ${cName}_hashCode($expr));"
				"ktc_core_sb_append_cstr($sbRef, $buf);"
				} else "ktc_core_sb_append_str($sbRef, ktc_core_str(\"<${baseStr}>\"));"
			}
		else -> "ktc_core_sb_append_str($sbRef, ktc_core_str(\"<${type.toCType()}>\"));"
		}
	}
