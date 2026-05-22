package com.bitsycore.ktc.codegen.statement

import com.bitsycore.ktc.ast.Arg
import com.bitsycore.ktc.ast.ExprPart
import com.bitsycore.ktc.ast.LitPart
import com.bitsycore.ktc.ast.StrTemplateExpr
import com.bitsycore.ktc.codegen.*
import com.bitsycore.ktc.codegen.expression.*
import com.bitsycore.ktc.types.KtcType

// ── print / println ──────────────────────────────────────────────

/** Emit println as C statements. */
internal fun CCodeGen.emitPrintlnStmt(args: List<Arg>, ind: String) {
    if (args.isEmpty()) {
        impl.appendLine("${ind}printf(\"\\n\");"); return
    }
    emitPrintStmtInner(args, ind, newline = true)
}

internal fun CCodeGen.emitPrintStmt(args: List<Arg>, ind: String) {
    if (args.isEmpty()) return
    emitPrintStmtInner(args, ind, newline = false)
}

internal fun CCodeGen.emitPrintStmtInner(args: List<Arg>, ind: String, newline: Boolean) {
    val arg = args[0].expr
    val nl = if (newline) "\\n" else ""

    // String template
    if (arg is StrTemplateExpr) {
        if (templateNeedsStrBuf(arg)) {
            emitPrintTemplateViaStrBuf(arg, ind, newline)
        } else {
            val printfStr = genPrintfFromTemplate(arg, nl)
            flushPreStmts(ind)
            impl.appendLine("$ind$printfStr;")
        }
        return
    }

    val tKtc = inferExprTypeKtc(arg) ?: KtcType.Prim(KtcType.PrimKind.Int)
    val tKtcCore = tKtc.stripNullable
    var expr = genExpr(arg)
    flushPreStmts(ind)

    // Nullable → if (tag == ktc_SOME) print(value) else print("null")
    if (tKtc is KtcType.Nullable) {
        val isValNull = isValueNullableKtc(tKtc)
        val isPtrNull = !isValNull && tKtc.inner is KtcType.Ptr
        // Materialize only when complex to avoid repeated evaluation
        if (!isSimpleCExpr(expr)) {
            val vTmp = tmp()
            impl.appendLine("${ind}${tKtc.toCType()} $vTmp = ($expr);")
            expr = vTmp
        }
        val hasExpr = when {
            isValNull -> "$expr.tag == ktc_SOME"
            isPtrNull -> "$expr != NULL"
            else -> "${expr}\$has"
        }
        val valExpr = if (isValNull) "$expr.value" else expr
        // data class → use StrBuf toString with null guard
        val dataClass = (tKtcCore as? KtcType.User)?.takeIf { it.kind == KtcType.UserKind.DataClass }?.baseName
            ?: ((tKtcCore as? KtcType.Ptr)?.inner as? KtcType.User)?.takeIf { it.kind == KtcType.UserKind.DataClass }?.baseName
        if (dataClass != null) {
            val buf = tmp()
            val isIndirect = tKtcCore is KtcType.Ptr
            val recv = if (isIndirect) valExpr else "&($valExpr)"
            val maxLen = toStringMaxLen(dataClass)
            if (maxLen != null && maxLen <= 512) {
                impl.appendLine("${ind}char ${buf}[$maxLen];")
                impl.appendLine("${ind}ktc_StrBuf ${buf}_sb = {${buf}, 0, $maxLen};")
                impl.appendLine("${ind}if ($hasExpr) { ${typeFlatName(dataClass)}_toString($recv, &${buf}_sb); }")
                impl.appendLine("${ind}else { ktc_core_sb_append_str(&${buf}_sb, ktc_core_str(\"null\")); }")
                impl.appendLine("${ind}printf(\"%.*s$nl\", (ktc_Int)${buf}_sb.len, ${buf}_sb.ptr);")
            } else {
                impl.appendLine("${ind}ktc_StrBuf ${buf}_sb = {NULL, 0, 0};")
                impl.appendLine("${ind}if ($hasExpr) { ${typeFlatName(dataClass)}_toString($recv, &${buf}_sb); }")
                impl.appendLine("${ind}char* $buf = (char*)ktc_core_alloca(${buf}_sb.len + 1);")
                impl.appendLine("${ind}${buf}_sb = (ktc_StrBuf){${buf}, 0, ${buf}_sb.len + 1};")
                impl.appendLine("${ind}if ($hasExpr) { ${typeFlatName(dataClass)}_toString($recv, &${buf}_sb); }")
                impl.appendLine("${ind}else { ktc_core_sb_append_str(&${buf}_sb, ktc_core_str(\"null\")); }")
                impl.appendLine("${ind}printf(\"%.*s$nl\", (ktc_Int)${buf}_sb.len, ${buf}_sb.ptr);")
            }
        } else {
            val fmt = printfFmt(tKtcCore) + nl
            val a = printfArg(valExpr, tKtcCore)
            impl.appendLine("${ind}if ($hasExpr) { printf(\"$fmt\", $a); }")
            impl.appendLine("${ind}else { printf(\"null$nl\"); }")
        }
        return
    }

    // data class → toString into StrBuf, then printf (fixed buffer if bounded)
    if (tKtcCore is KtcType.User && tKtcCore.kind == KtcType.UserKind.DataClass) {
        val buf = tmp()
        val vTmp = tmp()
        val baseName = tKtcCore.baseName
        val maxLen = toStringMaxLen(baseName)
        if (maxLen != null && maxLen <= 512) {
            impl.appendLine("${ind}${tKtcCore.toCType()} $vTmp = ($expr);")
            impl.appendLine("${ind}char ${buf}[$maxLen];")
            impl.appendLine("${ind}ktc_StrBuf ${buf}_sb = {${buf}, 0, $maxLen};")
            impl.appendLine("${ind}${typeFlatName(baseName)}_toString(&$vTmp, &${buf}_sb);")
            impl.appendLine("${ind}printf(\"%.*s$nl\", (ktc_Int)${buf}_sb.len, ${buf}_sb.ptr);")
        } else {
            impl.appendLine("${ind}${tKtcCore.toCType()} $vTmp = ($expr);")
            impl.appendLine("${ind}ktc_StrBuf ${buf}_sb = {NULL, 0, 0};")
            impl.appendLine("${ind}${typeFlatName(baseName)}_toString(&$vTmp, &${buf}_sb);")
            impl.appendLine("${ind}char* $buf = (char*)ktc_core_alloca(${buf}_sb.len + 1);")
            impl.appendLine("${ind}${buf}_sb = (ktc_StrBuf){${buf}, 0, ${buf}_sb.len + 1};")
            impl.appendLine("${ind}${typeFlatName(baseName)}_toString(&$vTmp, &${buf}_sb);")
            impl.appendLine("${ind}printf(\"%.*s$nl\", (ktc_Int)${buf}_sb.len, ${buf}_sb.ptr);")
        }
        return
    }

    // Heap/Ptr/Value pointer to data class → pass pointer directly
    val indirectBase = (tKtcCore as? KtcType.Ptr)?.inner?.let { it as? KtcType.User }?.baseName
    if (indirectBase != null && classes[indirectBase]?.isData == true) {
        val buf = tmp()
        val maxLen = toStringMaxLen(indirectBase)
        if (maxLen != null && maxLen <= 512) {
            impl.appendLine("${ind}char ${buf}[$maxLen];")
            impl.appendLine("${ind}ktc_StrBuf ${buf}_sb = {${buf}, 0, $maxLen};")
            impl.appendLine("${ind}${typeFlatName(indirectBase)}_toString($expr, &${buf}_sb);")
            impl.appendLine("${ind}printf(\"%.*s$nl\", (ktc_Int)${buf}_sb.len, ${buf}_sb.ptr);")
        } else {
            impl.appendLine("${ind}ktc_StrBuf ${buf}_sb = {NULL, 0, 0};")
            impl.appendLine("${ind}${typeFlatName(indirectBase)}_toString($expr, &${buf}_sb);")
            impl.appendLine("${ind}char* $buf = (char*)ktc_core_alloca(${buf}_sb.len + 1);")
            impl.appendLine("${ind}${buf}_sb = (ktc_StrBuf){${buf}, 0, ${buf}_sb.len + 1};")
            impl.appendLine("${ind}${typeFlatName(indirectBase)}_toString($expr, &${buf}_sb);")
            impl.appendLine("${ind}printf(\"%.*s$nl\", (ktc_Int)${buf}_sb.len, ${buf}_sb.ptr);")
        }
        return
    }

    // Non-data class/object/interface → use toString()
    if (tKtcCore is KtcType.User) {
        val str = genToStringKtc(expr, tKtcCore)
        flushPreStmts(ind)
        val tmpStr = tmp()
        impl.appendLine("${ind}ktc_String $tmpStr = $str;")
        impl.appendLine("${ind}printf(\"%.*s$nl\", (ktc_Int)${tmpStr}.len, ${tmpStr}.ptr);")
        return
    }

    // String: printf(".*s") needs .len + .ptr — materialize if complex
    if (tKtcCore is KtcType.Str) {
        val safeExpr = if (!isSimpleCExpr(expr)) { val vTmp = tmp(); impl.appendLine("${ind}ktc_String $vTmp = ($expr);"); vTmp } else expr
        impl.appendLine("${ind}printf(\"%.*s$nl\", (ktc_Int)($safeExpr).len, ($safeExpr).ptr);")
        return
    }

    val fmt = printfFmt(tKtcCore) + nl
    val a = printfArg(expr, tKtcCore)
    impl.appendLine("${ind}printf(\"$fmt\", $a);")
}

/** Check if a template contains data class or nullable expressions (need StrBuf). */
internal fun CCodeGen.templateNeedsStrBuf(tmpl: StrTemplateExpr): Boolean {
    return tmpl.parts.any { part ->
        part is ExprPart && run {
            val tKtc = inferExprTypeKtc(part.expr)
            tKtc is KtcType.User || tKtc is KtcType.Nullable || tKtc is KtcType.Ptr
        }
    }
}

/** Emit a println/print of a complex string template via ktc_StrBuf. */
internal fun CCodeGen.emitPrintTemplateViaStrBuf(tmpl: StrTemplateExpr, ind: String, newline: Boolean) {
    val buf = tmp()

    data class PartData(val lit: String? = null, val sbAppend: String? = null)

    val parts = mutableListOf<PartData>()
    for (part in tmpl.parts) {
        when (part) {
            is LitPart -> {
                val last = parts.lastOrNull()
                if (last?.lit != null) parts[parts.lastIndex] = PartData(lit = last.lit + part.text)
                else parts += PartData(lit = part.text)
            }

            is ExprPart -> {
                val tKtc = inferExprTypeKtc(part.expr) ?: KtcType.Prim(KtcType.PrimKind.Int)
                val expr = genExpr(part.expr)
                parts += PartData(sbAppend = genSbAppendKtc("&${buf}_sb", expr, tKtc))
            }
        }
    }
    val nl = if (newline) "\\n" else ""
    val maxLen = templateMaxLen(tmpl)
    if (maxLen != null && maxLen <= 512) {
        impl.appendLine("${ind}char ${buf}[$maxLen];")
        impl.appendLine("${ind}ktc_StrBuf ${buf}_sb = {${buf}, 0, $maxLen};")
        for (p in parts) {
            when {
                p.lit != null -> impl.appendLine("${ind}ktc_core_sb_append_str(&${buf}_sb, ktc_core_str(\"${escapeStr(p.lit)}\"));")
                p.sbAppend != null -> {
                    flushPreStmts(ind)
                    impl.appendLine("$ind${p.sbAppend}")
                }
            }
        }
        impl.appendLine("${ind}printf(\"%.*s$nl\", (ktc_Int)${buf}_sb.len, ${buf}_sb.ptr);")
        return
    }
    // First pass: count
    impl.appendLine("${ind}ktc_StrBuf ${buf}_sb = {NULL, 0, 0};")
    for (p in parts) {
        when {
            p.lit != null -> impl.appendLine("${ind}ktc_core_sb_append_str(&${buf}_sb, ktc_core_str(\"${escapeStr(p.lit)}\"));")
            p.sbAppend != null -> {
                flushPreStmts(ind)
                impl.appendLine("$ind${p.sbAppend}")
            }
        }
    }
    // Allocate + second pass
    impl.appendLine("${ind}char* $buf = (char*)ktc_core_alloca(${buf}_sb.len + 1);")
    impl.appendLine("${ind}${buf}_sb = (ktc_StrBuf){${buf}, 0, ${buf}_sb.len + 1};")
    for (p in parts) {
        when {
            p.lit != null -> impl.appendLine("${ind}ktc_core_sb_append_str(&${buf}_sb, ktc_core_str(\"${escapeStr(p.lit)}\"));")
            p.sbAppend != null -> impl.appendLine("$ind${p.sbAppend}")
        }
    }
    impl.appendLine("${ind}printf(\"%.*s$nl\", (ktc_Int)${buf}_sb.len, ${buf}_sb.ptr);")
}
