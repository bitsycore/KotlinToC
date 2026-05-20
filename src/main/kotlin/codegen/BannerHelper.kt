package com.bitsycore.ktc.codegen

import com.bitsycore.ktc.ast.TypeRef

internal const val kHdrRule = "═══════════════════════════════════════════════════════════"

internal fun CCodeGen.classBlockHeader(
    inKind: String,
    inName: String,
    inTypeParams: List<String>,
    inSuperInterfaces: List<TypeRef>,
    inPkg: String,
    inFile: String,
    inCName: String
): String {
    val vTypeParamStr = if (inTypeParams.isNotEmpty()) "<${inTypeParams.joinToString(", ")}>" else ""
    val vSuperStr = if (inSuperInterfaces.isNotEmpty())
        " : ${inSuperInterfaces.joinToString(", ") { typeRefToStr(it) }}" else ""
    val vSig = "$inKind $inName$vTypeParamStr$vSuperStr"
    return buildString {
        appendLine("/* $kHdrRule")
        appendLine(" * $vSig")
        if (inPkg.isNotEmpty()) appendLine(" * package: $inPkg")
        appendLine(" * file: $inFile")
        appendLine(" * mangled: $inCName")
        append(" * $kHdrRule */")
    }
}

/* Build the closing block comment for a class/data-class section. */
internal fun CCodeGen.classBlockFooter(inKind: String, inName: String, inTypeParams: List<String>): String {
    val vTypeParamStr = if (inTypeParams.isNotEmpty()) "<${inTypeParams.joinToString(", ")}>" else ""
    return buildString {
        appendLine("/* $kHdrRule")
        appendLine(" * END $inKind $inName$vTypeParamStr")
        append(" * $kHdrRule */")
    }
}

/* Build a banner comment for a group of top-level functions from the same file. */
internal fun CCodeGen.funBlockHeader(inPkg: String, inFile: String): String =
    buildString {
        appendLine("/* $kHdrRule")
        appendLine(" * top-level")
        if (inPkg.isNotEmpty()) appendLine(" * package: $inPkg")
        appendLine(" * file: $inFile")
        append(" * $kHdrRule */")
    }

/*
Build the preamble block comment for a per-declaration .c source file.
inKind      — "class", "data class", "object", "companion object", "enum", "top-level"
inKtName    — Kotlin display name including generic args (e.g. "MapIterator<String, String>")
inPkg       — Kotlin package string (e.g. "com.example"); empty for no-package
inCName     — C mangled identifier (e.g. "com_example_Foo")
inSrcFile   — original Kotlin source filename (template source for generic instantiations)
inInstFrom  — source file that triggered the instantiation; empty for non-generic declarations
*/
internal fun CCodeGen.cSourceFileHeader(
    inKind: String,
    inKtName: String,
    inPkg: String,
    inCName: String,
    inSrcFile: String,
    inInstFrom: String = ""
): String = buildString {
    appendLine("/* $kHdrRule")
    appendLine(" * $inKind $inKtName")
    if (inPkg.isNotEmpty()) appendLine(" * package: $inPkg")
    if (inSrcFile.isNotEmpty()) appendLine(" * source:  $inSrcFile")
    appendLine(" * mangled: $inCName")
    if (inInstFrom.isNotEmpty()) appendLine(" * instantiated from: $inInstFrom")
    append(" * $kHdrRule */")
}

/* Emit a functions banner when the source file changes; tracks lastEmittedFunFile. */
internal fun CCodeGen.maybeEmitFunBanner(inFunName: String) {
    val vFile = declSourceFile[inFunName] ?: currentSourceFile // source file for this function
    if (vFile != lastEmittedFunFile) {
        hdr.appendLine()
        hdr.appendLine(funBlockHeader(file.pkg ?: "", vFile))
        lastEmittedFunFile = vFile
    }
}

// ── class / data class ───────────────────────────────────────────

/* Returns a box-style section comment: // ═══ //\n//   title   //\n// ═══ // */
internal fun boxSection(inTitle: String): String {
    val vRuler = "═".repeat(inTitle.length + 6) // ruler width matches title with 4-space padding each side
    return "/* $vRuler *\n *    $inTitle    *\n * $vRuler */"
}

