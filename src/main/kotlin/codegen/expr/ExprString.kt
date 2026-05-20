package com.bitsycore.ktc.codegen.expr

import com.bitsycore.ktc.ast.Arg
import com.bitsycore.ktc.ast.ExprPart
import com.bitsycore.ktc.ast.LitPart
import com.bitsycore.ktc.ast.StrTemplateExpr
import com.bitsycore.ktc.codegen.*
import com.bitsycore.ktc.types.KtcType

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
    val nl = if (newline) "\\n" else ""

    // String template → direct printf
    if (arg is StrTemplateExpr) {
        return genPrintfFromTemplate(arg, nl)
    }

    val t = inferExprType(arg) ?: "Int"
    val tKtc = inferExprTypeKtc(arg) ?: KtcType.Prim(KtcType.PrimKind.Int)
    val tKtcCore = (tKtc as? KtcType.Nullable)?.inner ?: tKtc
    val expr = genExpr(arg)

    // Nullable → ternary: $has ? printf(value) : printf("null")
    if (tKtc is KtcType.Nullable) {
        // Materialize only when complex to avoid repeated evaluation
        val safeExpr = if (!isSimpleCExpr(expr)) {
            val vTmp = tmp(); preStmts += "${cTypeStr(t)} $vTmp = ($expr);"; vTmp
        } else expr
        val isPtrNull = tKtc.inner is KtcType.Ptr && !isValueNullableKtc(tKtc)
        val hasExpr = if (isPtrNull) "$safeExpr != NULL" else if (isValueNullableKtc(tKtc)) "$safeExpr.tag == ktc_SOME" else "${safeExpr}\$has"
        val fmt = printfFmt(tKtcCore) + nl
        val a = printfArg(safeExpr, tKtcCore)
        return "($hasExpr ? printf(\"$fmt\", $a) : printf(\"null$nl\"))"
    }

    // data class → two-pass StrBuf for exact-size alloca (or fixed buffer if bounded)
    if (classes.containsKey(t) && classes[t]!!.isData) {
        val maxLen = toStringMaxLen(t)
        if (maxLen != null && maxLen <= 512) {
            val buf = tmp()
            val vTmp = tmp()
            preStmts += "${cTypeStr(t)} $vTmp = ($expr);"
            preStmts += "ktc_Char ${buf}[$maxLen];"
            preStmts += "ktc_StrBuf ${buf}_sb = {${buf}, 0, $maxLen};"
            preStmts += "${typeFlatName(t)}_toString(&$vTmp, &${buf}_sb);"
            return "printf(\"%.*s$nl\", (ktc_Int)${buf}_sb.len, ${buf}_sb.ptr)"
        }
        val buf = tmp()
        val vTmp = tmp()
        preStmts += "${cTypeStr(t)} $vTmp = ($expr);"
        preStmts += "ktc_StrBuf ${buf}_sb = {NULL, 0, 0};"
        preStmts += "${typeFlatName(t)}_toString(&$vTmp, &${buf}_sb);"
        preStmts += "ktc_Char* $buf = (ktc_Char*)ktc_core_alloca(${buf}_sb.len + 1);"
        preStmts += "${buf}_sb = (ktc_StrBuf){${buf}, 0, ${buf}_sb.len + 1};"
        preStmts += "${typeFlatName(t)}_toString(&$vTmp, &${buf}_sb);"
        return "printf(\"%.*s$nl\", (ktc_Int)${buf}_sb.len, ${buf}_sb.ptr)"
    }
    // Non-data class/object/interface → use toString()
    if (classes.containsKey(t) || objects.containsKey(t) || interfaces.containsKey(t)) {
        val str = genToString(expr, t)
        val tmpStr = tmp()
        preStmts += "ktc_String $tmpStr = $str;"
        return "printf(\"%.*s$nl\", (ktc_Int)${tmpStr}.len, ${tmpStr}.ptr)"
    }
    // Heap/Ptr/Value to data class → pass pointer directly
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

    // String / enum: printfArg expands expr twice (.len + .ptr or names[x] twice) — materialize if complex
    if (t == "String") {
        val safeExpr = if (!isSimpleCExpr(expr)) { val vTmp = tmp(); preStmts += "ktc_String $vTmp = ($expr);"; vTmp } else expr
        return "printf(\"%.*s$nl\", (ktc_Int)($safeExpr).len, ($safeExpr).ptr)"
    }
    if (t in enums) {
        val cName = typeFlatName(t)
        val safeExpr = if (!isSimpleCExpr(expr)) { val vTmp = tmp(); preStmts += "$cName $vTmp = ($expr);"; vTmp } else expr
        return "printf(\"%.*s$nl\", (ktc_Int)${cName}_names[$safeExpr].len, ${cName}_names[$safeExpr].ptr)"
    }
    val fmt = printfFmt(tKtcCore) + nl
    val a = printfArg(expr, tKtcCore)
    return "printf(\"$fmt\", $a)"
}

internal fun CCodeGen.genPrintfFromTemplate(tmpl: StrTemplateExpr, nl: String): String {
    val fmt = StringBuilder()
    val argsList = mutableListOf<String>()
    for (part in tmpl.parts) {
        when (part) {
            is LitPart -> fmt.append(escapeStr(part.text))
            is ExprPart -> {
                val tKtc = inferExprTypeKtc(part.expr) ?: KtcType.Prim(KtcType.PrimKind.Int)
                val tKtcCore = (tKtc as? KtcType.Nullable)?.inner ?: tKtc
                fmt.append(printfFmt(tKtcCore))
                val exprStr = genExpr(part.expr)
                // String / enum: materialize if complex to avoid double-evaluation
                when (tKtcCore) {
                    is KtcType.Str -> {
                        val s = if (!isSimpleCExpr(exprStr)) {
                            val v = tmp(); preStmts += "ktc_String $v = ($exprStr);"; v
                        } else exprStr
                        argsList += "(ktc_Int)($s).len, ($s).ptr"
                    }

                    is KtcType.User if tKtcCore.kind == KtcType.UserKind.Enum -> {
                        val cName = typeFlatName(tKtcCore.baseName)
                        val s = if (!isSimpleCExpr(exprStr)) {
                            val v = tmp(); preStmts += "$cName $v = ($exprStr);"; v
                        } else exprStr
                        argsList += "(ktc_Int)${cName}_names[$s].len, ${cName}_names[$s].ptr"
                    }

                    else -> {
                        argsList += printfArg(exprStr, tKtcCore)
                    }
                }
            }
        }
    }
    fmt.append(nl)
    val argsStr = if (argsList.isNotEmpty()) ", " + argsList.joinToString(", ") else ""
    return "printf(\"$fmt\"$argsStr)"
}

// ── string template (returns ktc_String via preStmts) ─────────────

internal fun CCodeGen.genStrTemplate(e: StrTemplateExpr): String {
    val buf = tmp()

    // Pre-compute expressions (genExpr increments tmp counter, so do this once)
    data class PartData(val lit: String? = null, val sbAppend: String? = null)

    val parts = mutableListOf<PartData>()
    for (part in e.parts) {
        when (part) {
            is LitPart -> {
                val last = parts.lastOrNull()
                if (last?.lit != null) {
                    parts[parts.lastIndex] = PartData(lit = last.lit + part.text)
                } else {
                    parts += PartData(lit = part.text)
                }
            }

            is ExprPart -> {
                val tKtc = inferExprTypeKtc(part.expr) ?: KtcType.Prim(KtcType.PrimKind.Int)
                val expr = genExpr(part.expr)
                parts += PartData(sbAppend = genSbAppendKtc("&${buf}_sb", expr, tKtc))
            }
        }
    }
    val maxLen = templateMaxLen(e)
    if (maxLen != null && maxLen <= 512) {
        // Single pass with alloca buffer (alloca = function-frame lifetime, safe when pointer escapes block)
        preStmts += "ktc_Char* $buf = (ktc_Char*)ktc_core_alloca($maxLen);"
        preStmts += "ktc_StrBuf ${buf}_sb = {${buf}, 0, $maxLen};"
        for (p in parts) {
            when {
                p.lit != null -> preStmts += "ktc_core_sb_append_str(&${buf}_sb, ktc_core_str(\"${escapeStr(p.lit)}\"));"
                p.sbAppend != null -> preStmts += p.sbAppend
            }
        }
        return "ktc_core_sb_to_string(&${buf}_sb)"
    }
    // First pass: count length with NULL buffer
    preStmts += "ktc_StrBuf ${buf}_sb = {NULL, 0, 0};"
    for (p in parts) {
        when {
            p.lit != null -> preStmts += "ktc_core_sb_append_str(&${buf}_sb, ktc_core_str(\"${escapeStr(p.lit)}\"));"
            p.sbAppend != null -> preStmts += p.sbAppend
        }
    }
    // Allocate exact size
    preStmts += "ktc_Char* $buf = (ktc_Char*)ktc_core_alloca(${buf}_sb.len + 1);"
    preStmts += "${buf}_sb = (ktc_StrBuf){${buf}, 0, ${buf}_sb.len + 1};"
    // Second pass: write to real buffer
    for (p in parts) {
        when {
            p.lit != null -> preStmts += "ktc_core_sb_append_str(&${buf}_sb, ktc_core_str(\"${escapeStr(p.lit)}\"));"
            p.sbAppend != null -> preStmts += p.sbAppend
        }
    }
    return "ktc_core_sb_to_string(&${buf}_sb)"
}

// ── compile-time toString max length inference ──────────────────

private val toStringPrimitiveMaxLen = mapOf(
    "Boolean" to 5,   // "false"
    "Byte" to 4,   // "-128"
    "UByte" to 3,   // "255"
    "Short" to 6,   // "-32768"
    "UShort" to 5,   // "65535"
    "Int" to 11,  // "-2147483648"
    "UInt" to 10,  // "4294967295"
    "Long" to 20,  // "-9223372036854775808"
    "ULong" to 20,  // "18446744073709551615"
    "Float" to 64,  // %f via ktc_core_sb_append_double
    "Double" to 64,  // %f via ktc_core_sb_append_double
    "Char" to 8    // %c format buffer
)

internal fun CCodeGen.toStringMaxLen(baseType: String, visited: MutableSet<String> = mutableSetOf()): Int? {
    val t = baseType.removeSuffix("?").removeSuffix("*")
    if (t in visited) return null
    visited.add(t)

    val primMax = toStringPrimitiveMaxLen[t]
    if (primMax != null) {
        visited.remove(t)
        return primMax
    }

    // Data class: compute from field types recursively
    val ci = classes[t]
    if (ci != null && ci.isData) {
        var total = t.length + 2  // "Name(" + ")"
        for ((i, prop) in ci.props.withIndex()) {
            val (name, propType) = prop
            val tBase = resolveTypeName(propType).toInternalStr
            val baseClean = tBase.removeSuffix("?")
            val fieldMax = toStringMaxLen(baseClean, visited)
            if (fieldMax == null) {
                visited.remove(t); return null
            }
            val prefixLen = name.length + if (i == 0) 1 else 3  // "name=" or ", name="
            total += prefixLen
            total += if (propType.nullable) maxOf(fieldMax, 4) else fieldMax
        }
        visited.remove(t)
        return total
    }

    // Default class / object / interface: "Name@XXXXXXXX"
    if (classes.containsKey(t) || objects.containsKey(t) || interfaces.containsKey(t)) {
        visited.remove(t)
        return ktDisplayName(t).length + 10
    }

    visited.remove(t)
    return null
}

/** Compute max output length for a string template, or null if any part is unbounded. */
internal fun CCodeGen.templateMaxLen(tmpl: StrTemplateExpr): Int? {
    var total = 0
    for (part in tmpl.parts) {
        when (part) {
            is LitPart -> total += part.text.length
            is ExprPart -> {
                val t = inferExprType(part.expr) ?: return null
                val tKtc = inferExprTypeKtc(part.expr)
                val max = toStringMaxLen(t) ?: return null
                total += max
            }
        }
    }
    return total
}

// ── toString dispatch ────────────────────────────────────────────

internal fun CCodeGen.genToStringKtc(recv: String, type: KtcType): String = genToString(recv, type.toInternalStr)

internal fun CCodeGen.genToString(recv: String, type: String): String {
    val base = type.removeSuffix("*").removeSuffix("?")
    val cName = typeFlatName(base)
    val isPtr = parseResolvedTypeName(type) is KtcType.Ptr

    // Data class: call ClassName_toString(&tmp, &sb)
    if (classes.containsKey(base) && classes[base]!!.isData) {
        val maxLen = toStringMaxLen(base)
        if (maxLen != null && maxLen <= 512) {
            // Single pass with fixed stack buffer (maxLen known at compile time)
            val buf = tmp()
            val vTmp = tmp()
            preStmts += "${cTypeStr(base)} $vTmp = ($recv);"
            preStmts += "ktc_Char ${buf}[$maxLen];"
            preStmts += "ktc_StrBuf ${buf}_sb = {${buf}, 0, $maxLen};"
            preStmts += "${cName}_toString(&$vTmp, &${buf}_sb);"
            return "ktc_core_sb_to_string(&${buf}_sb)"
        }
        // Two-pass with alloca (unbounded fields or too large)
        val buf = tmp()
        val vTmp = tmp()
        preStmts += "${cTypeStr(base)} $vTmp = ($recv);"
        preStmts += "ktc_StrBuf ${buf}_sb = {NULL, 0, 0};"
        preStmts += "${cName}_toString(&$vTmp, &${buf}_sb);"
        preStmts += "ktc_Char* $buf = (ktc_Char*)ktc_core_alloca(${buf}_sb.len + 1);"
        preStmts += "${buf}_sb = (ktc_StrBuf){${buf}, 0, ${buf}_sb.len + 1};"
        preStmts += "${cName}_toString(&$vTmp, &${buf}_sb);"
        return "ktc_core_sb_to_string(&${buf}_sb)"
    }

    // Non-data class with explicit toString override: call ClassName_toString(&self, sb)
    if (classes.containsKey(base) && classes[base]!!.methods.any { it.name == "toString" }) {
        val buf = tmp()
        val selfExpr = if (isPtr) recv else "&$recv"
        preStmts += "ktc_StrBuf ${buf}_sb = {NULL, 0, 0};"
        preStmts += "${cName}_toString($selfExpr, &${buf}_sb);"
        preStmts += "ktc_Char* $buf = (ktc_Char*)ktc_core_alloca(${buf}_sb.len + 1);"
        preStmts += "${buf}_sb = (ktc_StrBuf){${buf}, 0, ${buf}_sb.len + 1};"
        preStmts += "${cName}_toString($selfExpr, &${buf}_sb);"
        return "ktc_core_sb_to_string(&${buf}_sb)"
    }

    // Object with explicit toString override: call ObjectName_toString() → String
    if (objects.containsKey(base) && objects[base]!!.methods.any { it.name == "toString" }) {
        return "${cName}_toString()"
    }

    // Object implicit toString: use public toString(self, sb) with stack buffer
    if (objects.containsKey(base)) {
        val maxLen = toStringMaxLen(base)
        val buf = tmp()
        val selfExpr = if (isPtr) recv else "&$recv"
        if (maxLen != null) {
            preStmts += "ktc_Char ${buf}[$maxLen];"
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

    return when (type) {
        "Byte" -> {
            val buf = tmp()
            val sz = 6   // "-128\0" = 5+1
            preStmts += "ktc_Char ${buf}[$sz];"
            preStmts += "ktc_StrBuf ${buf}_sb = {${buf}, 0, $sz};"
            preStmts += "ktc_core_sb_append_byte(&${buf}_sb, $recv);"
            "ktc_core_sb_to_string(&${buf}_sb)"
        }

        "Short" -> {
            val buf = tmp()
            val sz = 7   // "-32768\0" = 6+1
            preStmts += "ktc_Char ${buf}[$sz];"
            preStmts += "ktc_StrBuf ${buf}_sb = {${buf}, 0, $sz};"
            preStmts += "ktc_core_sb_append_short(&${buf}_sb, $recv);"
            "ktc_core_sb_to_string(&${buf}_sb)"
        }

        "Int" -> {
            val buf = tmp()
            val sz = 12   // "-2147483648\0" = 11+1
            preStmts += "ktc_Char ${buf}[$sz];"
            preStmts += "ktc_StrBuf ${buf}_sb = {${buf}, 0, $sz};"
            preStmts += "ktc_core_sb_append_int(&${buf}_sb, $recv);"
            "ktc_core_sb_to_string(&${buf}_sb)"
        }

        "Long" -> {
            val buf = tmp()
            val sz = 21   // "-9223372036854775808\0" = 20+1
            preStmts += "ktc_Char ${buf}[$sz];"
            preStmts += "ktc_StrBuf ${buf}_sb = {${buf}, 0, $sz};"
            preStmts += "ktc_core_sb_append_long(&${buf}_sb, $recv);"
            "ktc_core_sb_to_string(&${buf}_sb)"
        }

        "UByte" -> {
            val buf = tmp()
            val sz = 4   // "255\0" = 3+1
            preStmts += "ktc_Char ${buf}[$sz];"
            preStmts += "ktc_StrBuf ${buf}_sb = {${buf}, 0, $sz};"
            preStmts += "ktc_core_sb_append_ubyte(&${buf}_sb, $recv);"
            "ktc_core_sb_to_string(&${buf}_sb)"
        }

        "UShort" -> {
            val buf = tmp()
            val sz = 6   // "65535\0" = 5+1
            preStmts += "ktc_Char ${buf}[$sz];"
            preStmts += "ktc_StrBuf ${buf}_sb = {${buf}, 0, $sz};"
            preStmts += "ktc_core_sb_append_ushort(&${buf}_sb, $recv);"
            "ktc_core_sb_to_string(&${buf}_sb)"
        }

        "UInt" -> {
            val buf = tmp()
            val sz = 11   // "4294967295\0" = 10+1
            preStmts += "ktc_Char ${buf}[$sz];"
            preStmts += "ktc_StrBuf ${buf}_sb = {${buf}, 0, $sz};"
            preStmts += "ktc_core_sb_append_uint(&${buf}_sb, $recv);"
            "ktc_core_sb_to_string(&${buf}_sb)"
        }

        "ULong" -> {
            val buf = tmp()
            val sz = 21   // "18446744073709551615\0" = 20+1
            preStmts += "ktc_Char ${buf}[$sz];"
            preStmts += "ktc_StrBuf ${buf}_sb = {${buf}, 0, $sz};"
            preStmts += "ktc_core_sb_append_ulong(&${buf}_sb, $recv);"
            "ktc_core_sb_to_string(&${buf}_sb)"
        }

        "Float" -> {
            val buf = tmp()
            preStmts += "ktc_Char ${buf}[32];"
            preStmts += "snprintf($buf, 32, \"%g\", (ktc_Double)($recv));"
            "ktc_core_str($buf)"
        }

        "Double" -> {
            val buf = tmp()
            preStmts += "ktc_Char ${buf}[32];"
            preStmts += "snprintf($buf, 32, \"%g\", $recv);"
            "ktc_core_str($buf)"
        }

        "Boolean" -> {
            val buf = tmp()
            val sz = 6   // "false\0" = 5+1
            preStmts += "ktc_Char ${buf}[$sz];"
            preStmts += "snprintf($buf, $sz, \"%s\", ($recv) ? \"true\" : \"false\");"
            "ktc_core_str($buf)"
        }

        "Char" -> {
            val buf = tmp()
            preStmts += "ktc_Char ${buf}[8];"
            preStmts += "snprintf($buf, 8, \"%c\", (ktc_Char)($recv));"
            "ktc_core_str($buf)"
        }

        "String" -> recv
        else -> {
            // Default toString: ClassName@hexHashCode (Java-like)
            val base = type.removeSuffix("*").removeSuffix("?")
            val hasHash = classes.containsKey(base) || objects.containsKey(base)
            val hasIface = interfaces.containsKey(base)
            val isObj   = objects.containsKey(base)
            val maxLen = toStringMaxLen(base)  // name@XXXXXXXX
            val sz = if (maxLen != null) maxLen + 2 else 64
            if (hasHash) {
                val cName = typeFlatName(base)
                val buf = tmp()
                val isPtr = parseResolvedTypeName(type) is KtcType.Ptr
                // Objects: hashCode takes no args; classes: hashCode takes &self
                val hcExpr = if (isObj) "${cName}_hashCode()"
                             else "${cName}_hashCode(${if (isPtr) recv else "&$recv"})"
                preStmts += "ktc_Char ${buf}[$sz];"
                preStmts += "snprintf($buf, $sz, \"%s@%x\", \"${ktDisplayName(base)}\", $hcExpr);"
                "ktc_core_str($buf)"
            } else if (hasIface) {
                val buf = tmp()
                preStmts += "ktc_Char ${buf}[$sz];"
                preStmts += "snprintf($buf, $sz, \"%s@%x\", \"${ktDisplayName(base)}\", $recv.vt->hashCode(${ifaceVtableSelf(base, recv)}));"
                "ktc_core_str($buf)"
            } else {
                "ktc_core_str(\"<$type>\")"
            }
        }
    }
}

// ── toString into a StringBuffer (single pass) ────────────────────

internal fun CCodeGen.genToStringInto(recv: String, type: String, sb: String): String {
    if (classes.containsKey(type) && classes[type]!!.isData) {
        val vTmp = tmp()
        preStmts += "${cTypeStr(type)} $vTmp = ($recv);"
        preStmts += "${typeFlatName(type)}_toString(&$vTmp, &$sb);"
        return "ktc_core_sb_to_string(&$sb)"
    }
    // For primitives and other types, append to the given StrBuf
    when (type) {
        "Byte" -> preStmts += "ktc_core_sb_append_byte(&$sb, $recv);"
        "Short" -> preStmts += "ktc_core_sb_append_short(&$sb, $recv);"
        "Int" -> preStmts += "ktc_core_sb_append_int(&$sb, $recv);"
        "Long" -> preStmts += "ktc_core_sb_append_long(&$sb, $recv);"
        "Float" -> preStmts += "ktc_core_sb_append_float(&$sb, $recv);"
        "Double" -> preStmts += "ktc_core_sb_append_double(&$sb, $recv);"
        "Boolean" -> preStmts += "ktc_core_sb_append_bool(&$sb, $recv);"
        "Char" -> preStmts += "ktc_core_sb_append_char(&$sb, $recv);"
        "String" -> return recv
        "UByte" -> preStmts += "ktc_core_sb_append_ubyte(&$sb, $recv);"
        "UShort" -> preStmts += "ktc_core_sb_append_ushort(&$sb, $recv);"
        "UInt" -> preStmts += "ktc_core_sb_append_uint(&$sb, $recv);"
        "ULong" -> preStmts += "ktc_core_sb_append_ulong(&$sb, $recv);"
        else -> {
            val base = type.removeSuffix("*").removeSuffix("?")
            val hasHash = classes.containsKey(base) || objects.containsKey(base)
            val hasIface = interfaces.containsKey(base)
            if (hasHash) {
                val cName = typeFlatName(base)
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
                preStmts += "ktc_Char ${buf}[64];"
                preStmts += "snprintf($buf, 64, \"%s@%x\", \"${ktDisplayName(base)}\", $recv.vt->hashCode(${ifaceVtableSelf(base, recv)}));"
                preStmts += "ktc_core_sb_append_cstr(&$sb, $buf);"
            } else {
                preStmts += "ktc_core_sb_append_str(&$sb, ktc_core_str(\"<$type>\"));"
            }
        }
    }
    return "ktc_core_sb_to_string(&$sb)"
}

// ── StrBuf append helper ─────────────────────────────────────────

internal fun CCodeGen.genSbAppend(sbRef: String, expr: String, type: String): String = genSbAppendKtc(sbRef, expr, parseResolvedTypeName(type))

/** KtcType-based overload — delegates to the same logic with pattern matching on KtcType. */
internal fun CCodeGen.genSbAppendKtc(sbRef: String, expr: String, type: KtcType): String {
    // Nullable → conditionally append "null" or the value
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
            KtcType.PrimKind.Byte -> "ktc_core_sb_append_byte($sbRef, $expr);"
            KtcType.PrimKind.Short -> "ktc_core_sb_append_short($sbRef, $expr);"
            KtcType.PrimKind.Int -> "ktc_core_sb_append_int($sbRef, $expr);"
            KtcType.PrimKind.Long -> "ktc_core_sb_append_long($sbRef, $expr);"
            KtcType.PrimKind.Float -> "ktc_core_sb_append_float($sbRef, $expr);"
            KtcType.PrimKind.Double -> "ktc_core_sb_append_double($sbRef, $expr);"
            KtcType.PrimKind.Boolean -> "ktc_core_sb_append_bool($sbRef, $expr);"
            KtcType.PrimKind.Char -> "ktc_core_sb_append_char($sbRef, $expr);"
            KtcType.PrimKind.UByte -> "ktc_core_sb_append_ubyte($sbRef, $expr);"
            KtcType.PrimKind.UShort -> "ktc_core_sb_append_ushort($sbRef, $expr);"
            KtcType.PrimKind.UInt -> "ktc_core_sb_append_uint($sbRef, $expr);"
            KtcType.PrimKind.ULong -> "ktc_core_sb_append_ulong($sbRef, $expr);"
            KtcType.PrimKind.Rune -> "ktc_core_sb_append_int($sbRef, $expr);"
        }
        is KtcType.Str -> "ktc_core_sb_append_str($sbRef, $expr);"
        is KtcType.User -> {
            if (type.kind == KtcType.UserKind.DataClass) {
                val baseName = type.baseName
                if (classes.containsKey(baseName)) {
                    val vTmp = tmp()
                    "{ ${type.toCType()} $vTmp = ($expr); ${typeFlatName(baseName)}_toString(&$vTmp, $sbRef); }"
                } else {
                    "ktc_core_sb_append_str($sbRef, ktc_core_str(\"<${type.toCType()}>\"));"
                }
            } else {
                val baseName = type.baseName
                val typeStr = type.toCType()
                if (classes.containsKey(baseName) || objects.containsKey(baseName)) {
                    val buf = tmp()
                    val cName = typeFlatName(baseName)
                    preStmts += "ktc_Char ${buf}[64];"
                    preStmts += "snprintf($buf, 64, \"%s@%x\", \"${ktDisplayName(baseName)}\", ${cName}_hashCode(&$expr));"
                    "ktc_core_sb_append_cstr($sbRef, $buf);"
                } else if (interfaces.containsKey(baseName)) {
                    val buf = tmp()
                    preStmts += "ktc_Char ${buf}[64];"
                    preStmts += "snprintf($buf, 64, \"%s@%x\", \"${ktDisplayName(baseName)}\", $expr.vt->hashCode(${ifaceVtableSelf(baseName, expr)}));"
                    "ktc_core_sb_append_cstr($sbRef, $buf);"
                } else {
                    "ktc_core_sb_append_str($sbRef, ktc_core_str(\"<$typeStr>\"));"
                }
            }
        }
        is KtcType.Ptr -> {
            val base = type.inner
            val baseStr = base.toInternalStr
            // Data class pointer → use proper toString
            if (base is KtcType.User && base.kind == KtcType.UserKind.DataClass && classes.containsKey(baseStr)) {
                val vTmp = tmp()
                "{ ${base.toCType()} $vTmp = (*$expr); ${typeFlatName(baseStr)}_toString(&$vTmp, $sbRef); }"
            } else if (classes.containsKey(baseStr) || objects.containsKey(baseStr)) {
                val buf = tmp()
                val cName = typeFlatName(baseStr)
                preStmts += "ktc_Char ${buf}[64];"
                preStmts += "snprintf($buf, 64, \"%s@%x\", \"${ktDisplayName(baseStr)}\", ${cName}_hashCode($expr));"
                "ktc_core_sb_append_cstr($sbRef, $buf);"
            } else {
                "ktc_core_sb_append_str($sbRef, ktc_core_str(\"<${baseStr}>\"));"
            }
        }
        else -> "ktc_core_sb_append_str($sbRef, ktc_core_str(\"<${type.toCType()}>\"));"
    }
}

// ── arrayOf helpers ──────────────────────────────────────────────
