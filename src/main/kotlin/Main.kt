package com.bitsycore.ktc

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.codegen.CCodeGen
import com.bitsycore.ktc.codegen.COutput
import com.bitsycore.ktc.parser.Lexer
import com.bitsycore.ktc.parser.Parser
import java.io.File
import kotlin.system.exitProcess

val aClass = object {}.javaClass

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println("Usage: ktc <file.kt...> [-o <output_dir>] [--mem-track] [--disposed=ASSERT|LOG|NO] [--double-dispose=ASSERT|LOG|NO] [--main <qualified.name>] [--ast] [--dump-semantics]")
        System.err.println("  Transpiles Kotlin subset files to C11.")
        System.err.println("  --mem-track                  Enable allocation tracking (alloc/free counts + leak report)")
        System.err.println("  --disposed=ASSERT|LOG|NO     Use-after-dispose: abort / log+continue / ignore (default: NO)")
        System.err.println("  --double-dispose=ASSERT|LOG|NO  Double-dispose: abort / log+continue / ignore (default: NO)")
        System.err.println("  --main <qualified.name>      Select the entry point by qualified name (e.g. com.example.Main.run)")
        System.err.println("                               Required when multiple main-compatible functions exist.")
        System.err.println("                               Valid signatures: fun <name>(), fun <name>(): Int,")
        System.err.println("                                                 fun <name>(args: Array<String>),")
        System.err.println("                                                 fun <name>(args: Array<String>): Int")
        System.err.println("  --ast                        Dump parsed AST and exit (no C output)")
        System.err.println("  --dump-semantics             Dump AST + semantic analysis and exit")
        exitProcess(1)
    }

    // Parse args: collect .kt files and flags
    val inputPaths = mutableListOf<String>()
    var outputDir = "."
    var memTrack = false
    var disposedMode = "NO"        // ASSERT | LOG | NO
    var doubleDisposeMode = "NO"   // ASSERT | LOG | NO
    var mainOverride: String? = null  // --main qualified.name
    var dumpAst = false
    var dumpSemantics = false
    var i = 0
    while (i < args.size) {
        if (args[i] == "-o" && i + 1 < args.size) {
            outputDir = args[i + 1]
            i += 2
        } else if (args[i] == "--mem-track") {
            memTrack = true
            i++
        } else if (args[i].startsWith("--disposed=")) {
            disposedMode = args[i].removePrefix("--disposed=").uppercase()
            i++
        } else if (args[i].startsWith("--double-dispose=")) {
            doubleDisposeMode = args[i].removePrefix("--double-dispose=").uppercase()
            i++
        } else if (args[i] == "--main" && i + 1 < args.size) {
            mainOverride = args[i + 1]
            i += 2
        } else if (args[i] == "--ast") {
            dumpAst = true
            i++
        } else if (args[i] == "--dump-semantics") {
            dumpSemantics = true
            i++
        } else {
            inputPaths += args[i]
            i++
        }
    }

    if (inputPaths.isEmpty()) {
        System.err.println("Error: no input files specified")
        exitProcess(1)
    }

    // Resolve input files
    val inputFiles = mutableListOf<File>()
    for (path in inputPaths) {
        val f = File(path)
        if (f.exists()) {
            inputFiles += f
        } else {
            System.err.println("Error: file not found: $path")
            exitProcess(1)
        }
    }

    // ── Lex & Parse all files ────────────────────────────────────────
    data class ParsedSource(val file: File, val ast: KtFile, val sourceLines: List<String>)

    val parsedFiles = mutableListOf<ParsedSource>()

    // Collect raw sources first for infix prescan, then parse
    data class RawSource(
        val vFile: File,
        val vName: String,
        val vSource: String,
        val vIsStdlib: Boolean
    ) // file + text + origin flag

    val vRawSources = mutableListOf<RawSource>() // all sources before parsing

    // Collect stdlib .kt files from resources
    val stdlibDir = aClass.getResource("/stdlib") ?: aClass.getResource("/stdlib/")
    if (stdlibDir != null) {
        val stdlibFiles = when (stdlibDir.protocol) { // discover stdlib file names
            "jar" -> {
                val connection = stdlibDir.openConnection()
                val jarFile = (connection as java.net.JarURLConnection).jarFile
                jarFile.entries().asSequence()
                    .filter { !it.isDirectory && it.name.startsWith("stdlib/") && it.name.endsWith(".kt") }
                    .map { it.name.removePrefix("stdlib/") }
                    .toList()
            }

            "file" -> {
                File(stdlibDir.toURI()).listFiles()
                    ?.filter { it.name.endsWith(".kt") }
                    ?.map { it.name }
                    ?: emptyList()
            }

            else -> emptyList()
        }
        for (name in stdlibFiles.sorted()) {
            val res = aClass.getResourceAsStream("/stdlib/$name")
            if (res != null) vRawSources += RawSource(File("stdlib/$name"), name, res.bufferedReader().readText(), true)
        }
    }

    // Collect user sources
    for (inputFile in inputFiles) {
        vRawSources += RawSource(inputFile, inputFile.name, inputFile.readText(), false)
    }

    // Pre-scan all sources for infix function names (must happen before any parsing)
    val vInfixNameRx = Regex("""\binfix\s+fun\b[^(.]+\.(\w+)\s*\(""") // matches: infix fun <T> Recv.name(
    for (vRaw in vRawSources) {
        vInfixNameRx.findAll(vRaw.vSource).forEach { vMatch ->
            Parser.INFIX_IDS.add(vMatch.groupValues[1]) // register as infix operator name
        }
    }

    // Parse all collected sources
    for (vRaw in vRawSources) {
        try {
            val vTokens = Lexer(vRaw.vSource).tokenize() // lex
            val vAst = Parser(vTokens).parseFile().copy(sourceFile = vRaw.vName) // parse
            parsedFiles += ParsedSource(vRaw.vFile, vAst, vRaw.vSource.lines())
        } catch (e: Exception) {
            val vPrefix = if (vRaw.vIsStdlib) "Stdlib error" else "Error" // error origin label
            System.err.println("$vPrefix in ${vRaw.vName}: ${e.message}")
            exitProcess(1)
        }
    }

    // ── Dump AST if --ast flag is set ─────────────────────────────────
    if (dumpAst) {
        for (ps in parsedFiles) {
            println("=== AST: ${ps.ast.sourceFile.ifEmpty { ps.file.name }} ===")
            println(dumpAst(ps.ast))
        }
        return
    }

    // ── Dump semantics if --dump-semantics flag is set ─────────────────
    if (dumpSemantics) {
        val allAsts = parsedFiles.map { it.ast }
        for (ps in parsedFiles) {
            println("=== AST: ${ps.ast.sourceFile.ifEmpty { ps.file.name }} ===")
            println(dumpAst(ps.ast))
        }
        val lastPs = parsedFiles.last()
        try {
            val gen = CCodeGen(
                lastPs.ast,
                allAsts,
                lastPs.sourceLines,
                memTrack = false,
                sourceFileName = lastPs.ast.sourceFile.ifEmpty { lastPs.file.name })
            gen.collectAndScan()
            println(gen.dumpSemantics())
        } catch (e: Exception) {
            System.err.println("Semantic analysis error: ${e.message}")
        }
        return
    }

    // ── Group files by package ───────────────────────────────────────
    // Files with the same package are merged into a single output unit.
    // Files with different packages produce separate .c/.h outputs.
    val byPackage = parsedFiles.groupBy { it.ast.pkg ?: it.file.nameWithoutExtension }

    val outDir = File(outputDir)
    outDir.mkdirs()
    val ktcDir = File(outDir, "ktc")          // root ktc/ subdir for all intrinsic + stdlib output
    val ktcCoreDir = File(ktcDir, "core")     // ktc/core/ for ktc_core.h / ktc_macro.h / ktc_core.c
    ktcCoreDir.mkdirs()

    val allAsts = parsedFiles.map { it.ast }.filter { !it.documentationOnly }
    val ktcOutputNames = mutableListOf<String>()  // paths relative to ktc/  (e.g. "std/Heap")
    val userOutputNames = mutableListOf<String>() // paths relative to outDir (e.g. "com/example/Point")

    for ((pkg, group) in byPackage) {
        // Merge all files in the same package into one KtFile — skip documentation-only files entirely
        val realGroup = group.filter { !it.ast.documentationOnly }
        if (realGroup.isEmpty()) continue
        val mergedImports = realGroup.flatMap { it.ast.imports }.distinct()
        val mergedDecls = realGroup.flatMap { it.ast.decls }
        val mergedFile = KtFile(realGroup.first().ast.pkg, mergedImports, mergedDecls)
        val mergedSourceLines = realGroup.flatMap { it.sourceLines }

        val srcName = if (realGroup.size == 1) realGroup.first().file.name else "$pkg.kt"

        val output: COutput
        try {
            output = CCodeGen(
                mergedFile,
                allAsts,
                mergedSourceLines,
                memTrack = memTrack,
                disposedMode = disposedMode,
                doubleDisposeMode = doubleDisposeMode,
                sourceFileName = srcName
            ).generate()
        } catch (e: Exception) {
            System.err.println("CodeGen error in '$srcName': ${e.message}")
            e.printStackTrace()
            exitProcess(1)
        }

        val baseName    = mergedFile.pkg?.replace('.', '_') ?: pkg  // mangled pkg (e.g. "com_example")
        val isKtcPkg    = baseName.startsWith("ktc_")               // is this a ktc.* stdlib package?
        val vPkgPath    = mergedFile.pkg?.replace('.', '/') ?: pkg  // e.g. "com/example", "ktc/std", or pkg key
        // Package header goes in the package subdirectory as _Package.h (alphabetically first in dir).
        // ktc packages: ktc/<subpath>/_Package.h;  user packages: outDir/<pkgPath>/_Package.h
        val pkgHdrDir   = when {
            isKtcPkg -> {
                val vSubPath = vPkgPath.removePrefix("ktc/")        // e.g. "std" from "ktc/std"
                if (vSubPath.isNotEmpty()) File(ktcDir, vSubPath).also { it.mkdirs() } else ktcDir
                }
            vPkgPath.isNotEmpty() -> File(outDir, vPkgPath).also { it.mkdirs() }
            else -> outDir
            }
        val headerFile  = File(pkgHdrDir, "_Package.h")
        headerFile.writeText(output.header)
        println("  wrote ${headerFile.path}")

        // Write each .c file to the directory determined by its routingPkg
        for ((vFileName, vSourceFile) in output.sources) {
            val vRoutingPkg  = vSourceFile.routingPkg                    // dot-separated, e.g. "ktc.std"
            val vRoutingPath = vRoutingPkg.replace('.', '/')             // e.g. "ktc/std"
            val vIsKtcFile   = vRoutingPkg.startsWith("ktc.")            // true for all ktc.* packages
                            || vRoutingPkg == "ktc_std"                  // legacy fallback
            val vFileSrcDir: File
            val vRelBase: String                                          // path for compile command
            if (vIsKtcFile) {
                // Strip the "ktc/" prefix to get the subpath within ktcDir
                val vSubPath = vRoutingPath.removePrefix("ktc/")         // e.g. "std" from "ktc/std"
                vFileSrcDir = if (vSubPath.isNotEmpty()) File(ktcDir, vSubPath).also { it.mkdirs() } else ktcDir
                val vBase   = vFileName.removeSuffix(".c")               // e.g. "Heap"
                vRelBase    = if (vSubPath.isNotEmpty()) "$vSubPath/$vBase" else vBase
                ktcOutputNames += vRelBase
                } else {
                // User package: use the routing path directly under outDir
                vFileSrcDir = if (vRoutingPath.isNotEmpty())
                                  File(outDir, vRoutingPath).also { it.mkdirs() }
                              else outDir
                val vBase   = vFileName.removeSuffix(".c")
                vRelBase    = if (vRoutingPath.isNotEmpty()) "$vRoutingPath/$vBase" else vBase
                userOutputNames += vRelBase
                }
            val vOutputFile = File(vFileSrcDir, vFileName)
            vOutputFile.writeText(vSourceFile.content)
            println("  wrote ${vOutputFile.path}")
            }
    }

    // ── Locate entry-point function ─────────────────────────────
    /*
    A function is a valid entry-point candidate when its signature matches one of:
      fun <name>()
      fun <name>(): Int
      fun <name>(args: Array<String>)
      fun <name>(args: Array<String>): Int
    With no --main flag, only functions literally named "main" are considered.
    With --main "pkg.name" the search is by qualified name; the function name
    may differ from "main".
    */
    data class MainCandidate(
        val vPkg:  String,    // dot-separated package, may be empty
        val vFun:  FunDecl,   // the function declaration
        val vFile: String     // originating source file name
    )

    /* Check whether a FunDecl has a valid entry-point signature */
    fun isValidEntrySignature(inFun: FunDecl): Boolean {
        val vRetOk  = inFun.returnType == null || inFun.returnType.name == "Unit" ||
                      inFun.returnType.name == "Int"   // Unit/absent or Int return
        val vArgsOk = inFun.params.isEmpty() ||
                      (inFun.params.size == 1 &&
                       inFun.params[0].type.name == "Array" &&
                       inFun.params[0].type.typeArgs.singleOrNull()?.name == "String")
        return vRetOk && vArgsOk
    }

    /* Format a candidate for error messages */
    fun formatCandidate(inC: MainCandidate): String {
        val vQName  = if (inC.vPkg.isNotEmpty()) "${inC.vPkg}.${inC.vFun.name}" else inC.vFun.name
        val vParams = inC.vFun.params.joinToString(", ") { "${it.name}: ${it.type.name}" }
        val vRet    = inC.vFun.returnType?.name?.let { ": $it" } ?: ""
        return "  $vQName($vParams)$vRet  [${inC.vFile}]"
    }

    val vNonDocFiles = parsedFiles.filter { !it.ast.documentationOnly }  // skip doc-only files

    val vMainCandidate: MainCandidate?

    if (mainOverride != null) {
        // --main "a.b.c.funName": split at the last dot to separate package from function name
        val vDotIdx  = mainOverride.lastIndexOf('.')                        // split point
        val vOvrPkg  = if (vDotIdx > 0) mainOverride.substring(0, vDotIdx) else ""  // package part
        val vOvrName = mainOverride.substring(vDotIdx + 1)                  // function name part
        val vFound   = vNonDocFiles
            .filter { it.ast.pkg == vOvrPkg.ifEmpty { null } || (vOvrPkg.isEmpty() && it.ast.pkg == null) }
            .flatMap { ps -> ps.ast.decls.filterIsInstance<FunDecl>()
                .filter { it.name == vOvrName }
                .map { MainCandidate(ps.ast.pkg ?: "", it, ps.ast.sourceFile) } }
        when {
            vFound.isEmpty() -> {
                System.err.println("Error: --main \"$mainOverride\" not found.")
                System.err.println("  No top-level function '$vOvrName' in package '${vOvrPkg.ifEmpty { "<root>" }}'.")
                exitProcess(1)
            }
            !isValidEntrySignature(vFound.first().vFun) -> {
                System.err.println("Error: --main \"$mainOverride\" has an invalid entry-point signature.")
                System.err.println("  Valid signatures: fun name(), fun name(): Int,")
                System.err.println("                    fun name(args: Array<String>), fun name(args: Array<String>): Int")
                exitProcess(1)
            }
            else -> vMainCandidate = vFound.first()
        }
    } else {
        // Auto-detect: collect every top-level function named "main" with a valid signature
        val vCandidates = vNonDocFiles.flatMap { ps ->
            ps.ast.decls.filterIsInstance<FunDecl>()
                .filter { it.name == "main" && isValidEntrySignature(it) }
                .map { MainCandidate(ps.ast.pkg ?: "", it, ps.ast.sourceFile) }
        }
        when {
            vCandidates.isEmpty() -> vMainCandidate = null  // no main — library output, no main.c
            vCandidates.size == 1 -> vMainCandidate = vCandidates.first()
            else -> {
                System.err.println("Error: multiple entry-point functions found. Use --main <qualified.name> to select one:")
                vCandidates.forEach { System.err.println(formatCandidate(it)) }
                exitProcess(1)
            }
        }
    }

    // ── Generate main.c from the resolved entry point ────────────
    var vGeneratedMainC = false
    if (vMainCandidate != null) {
        val vMainFun  = vMainCandidate.vFun                                      // the entry-point FunDecl
        val vMPkg     = vMainCandidate.vPkg                                      // package, may be empty
        val vMPrefix  = vMPkg.replace('.', '_').let { if (it.isNotEmpty()) "${it}_" else "" }
        val vCMain    = "${vMPrefix}${vMainFun.name}"                            // prefixed C function name
        val vMPkgPath = if (vMPkg.isNotEmpty()) vMPkg.replace('.', '/') else ""  // e.g. "test/Main"
        val vPkgHdr   = if (vMPkgPath.isNotEmpty()) "$vMPkgPath/_Package.h" else "_Package.h"
        val vHasArgs  = vMainFun.params.size == 1                                // Array<String> param?
        val vReturnsInt = vMainFun.returnType?.name == "Int"                     // Int return type?
        val vMainC = buildString {
            appendLine("/* main.c — C entry point generated by ktc */")
            appendLine("#include \"$vPkgHdr\"")
            appendLine()
            if (vHasArgs) {
                appendLine("int main(int argc, char* argv[]) {")
                appendLine("    ktc_String \$args_buf[256];")
                appendLine("    ktc_Int \$nargs = (argc > 1) ? (ktc_Int)(argc - 1) : 0;")
                appendLine("    if (\$nargs > 256) \$nargs = 256;")
                appendLine("    for (ktc_Int \$i = 0; \$i < \$nargs; \$i++) {")
                appendLine("        \$args_buf[\$i] = (ktc_String){argv[\$i + 1], (ktc_Int)strlen(argv[\$i + 1])};")
                appendLine("    }")
                appendLine("    ktc_ArrayTrampoline \$vargs = {0, \$nargs, \$args_buf};")
                appendLine("    ktc_core_mainInit();")
                if (vReturnsInt)
                    appendLine("    return (int)${vCMain}(\$vargs);")
                else {
                    appendLine("    ${vCMain}(\$vargs);")
                    appendLine("    return 0;")
                }
            } else {
                appendLine("int main(void) {")
                appendLine("    ktc_core_mainInit();")
                if (vReturnsInt)
                    appendLine("    return (int)${vCMain}();")
                else {
                    appendLine("    ${vCMain}();")
                    appendLine("    return 0;")
                }
            }
            append("}")
        }
        val vMainCFile = File(outDir, "main.c")
        vMainCFile.writeText(vMainC)
        println("  wrote ${vMainCFile.path}")
        userOutputNames += "main"
        vGeneratedMainC = true
    }

    // ── Copy intrinsic files to ktc/core/ ───────────────────────
    for (vName in listOf("ktc_macro.h", "ktc_core.h", "ktc_core.c")) {
        val vDst = File(ktcCoreDir, vName)
        val vSrc = aClass.getResourceAsStream("/ktc/$vName")
        if (vSrc != null) {
            vDst.writeText(vSrc.bufferedReader().readText())
        } else {
            System.err.println("Warning: $vName not found in resources, copy it manually.")
        }
    }

    // Print compile command: core first, then other ktc/ files, then user files.
    // ktcOutputNames are paths relative to ktc/ (e.g. "core/ktc_core", "std/Heap").
    // userOutputNames are paths relative to outDir (e.g. "com/example/Point").
    val ktcSources  = (listOf("core/ktc_core") + ktcOutputNames.sorted())
        .joinToString(" ") { "ktc/$it.c" }
    val userSources = userOutputNames.sorted().joinToString(" ") { "$it.c" }
    // Derive binary name from the last path component of the first user output name
    val mainBase = userOutputNames.firstOrNull()
        ?.substringAfterLast('/')?.substringBefore('_')?.ifEmpty { "output" } ?: "output"
    println("Done. Compile with:  cc -std=c11 -iquote . -o $mainBase $ktcSources $userSources")
}

// ═══════════════════════════ AST Dump ═══════════════════════════

private fun indent(n: Int) = "  ".repeat(n)

private fun dumpAst(file: KtFile): String {
    val sb = StringBuilder()
    if (file.pkg != null) sb.appendLine("${indent(0)}package ${file.pkg}")
    for (imp in file.imports) sb.appendLine("${indent(0)}import $imp")
    for (decl in file.decls) sb.append(dumpDecl(decl, 0))
    return sb.toString()
}

private fun dumpDecl(d: Decl, depth: Int): String {
    val sb = StringBuilder()
    val id = indent(depth)
    when (d) {
        is FunDecl -> {
            val p = d.params.joinToString(", ") { dumpParam(it) }
            val r = if (d.returnType != null) ": ${dumpTypeRef(d.returnType)}" else ""
            val tps = if (d.typeParams.isNotEmpty()) "<${d.typeParams.joinToString(", ")}>" else ""
            val recv = if (d.receiver != null) "${dumpTypeRef(d.receiver)}." else ""
            val op = if (d.isOperator) "operator " else ""
            sb.appendLine("${id}${op}fun $recv${d.name}$tps($p)$r")
            if (d.body != null) sb.append(dumpBlock(d.body, depth + 1))
        }

        is ClassDecl -> {
            val tps = if (d.typeParams.isNotEmpty()) "<${d.typeParams.joinToString(", ")}>" else ""
            val ds = if (d.isData) "data " else ""
            val ifs =
                if (d.superInterfaces.isNotEmpty()) " : ${d.superInterfaces.joinToString(", ") { dumpTypeRef(it) }}" else ""
            sb.appendLine("${id}${ds}class ${d.name}$tps$ifs")
            for (cp in d.ctorParams) sb.appendLine("${indent(depth + 1)}ctor ${dumpCtorParam(cp)}")
            for (m in d.members) sb.append(dumpDecl(m, depth + 1))
            for (init in d.initBlocks) sb.append(dumpBlock(init, depth + 1, "init"))
        }

        is EnumDecl -> {
            sb.appendLine("${id}enum ${d.name} { ${d.entries.joinToString(", ")} }")
        }

        is InterfaceDecl -> {
            val tps = if (d.typeParams.isNotEmpty()) "<${d.typeParams.joinToString(", ")}>" else ""
            val ifs =
                if (d.superInterfaces.isNotEmpty()) " : ${d.superInterfaces.joinToString(", ") { dumpTypeRef(it) }}" else ""
            sb.appendLine("${id}interface ${d.name}$tps$ifs")
            for (p in d.properties) sb.append(dumpDecl(p, depth + 1))
            for (m in d.methods) sb.append(dumpDecl(m, depth + 1))
        }

        is ObjectDecl -> {
            sb.appendLine("${id}object ${d.name}")
            for (m in d.members) sb.append(dumpDecl(m, depth + 1))
        }

        is PropDecl -> {
            val mut = if (d.mutable) "var" else "val"
            val tp = if (d.type != null) ": ${dumpTypeRef(d.type)}" else ""
            val init = if (d.init != null) " = ${dumpExpr(d.init)}" else ""
            sb.appendLine("${id}$mut ${d.name}$tp$init")
        }
    }
    return sb.toString()
}

private fun dumpCtorParam(cp: CtorParam): String {
    val kw = if (cp.isVar) "var " else if (cp.isVal) "val " else ""
    val def = if (cp.default != null) " = ${dumpExpr(cp.default)}" else ""
    return "$kw${cp.name}: ${dumpTypeRef(cp.type)}$def"
}

private fun dumpParam(p: Param): String {
    val va = if (p.isVararg) "vararg " else ""
    val def = if (p.default != null) " = ${dumpExpr(p.default)}" else ""
    return "$va${p.name}: ${dumpTypeRef(p.type)}$def"
}

private fun dumpTypeRef(t: TypeRef): String {
    val tps = if (t.typeArgs.isNotEmpty()) "<${t.typeArgs.joinToString(", ") { dumpTypeRef(it) }}>" else ""
    val nll = if (t.nullable) "?" else ""
    val fn = if (t.funcParams != null) {
        val ps = t.funcParams.joinToString(", ") { dumpTypeRef(it) }
        "($ps) -> ${dumpTypeRef(t.funcReturn!!)}"
    } else ""
    val ann = if (t.annotations.isNotEmpty()) {
        t.annotations.joinToString(" ") {
            "@${it.name}${
                if (it.args.isNotEmpty()) "(${
                    it.args.joinToString(", ") { argsStr ->
                        dumpExpr(argsStr)
                    }
                })" else ""
            }"
        } + " "
    } else ""
    return "$ann${t.name}$tps$fn$nll"
}

private fun dumpBlock(b: Block, depth: Int, label: String = ""): String {
    val sb = StringBuilder()
    val id = indent(depth)
    val pre = if (label.isNotEmpty()) "$label " else ""
    sb.appendLine("${id}${pre}{")
    for (s in b.stmts) sb.append(dumpStmt(s, depth))
    sb.appendLine("${id}}")
    return sb.toString()
}

private fun dumpStmt(s: Stmt, depth: Int): String {
    val sb = StringBuilder()
    val id = indent(depth)
    when (s) {
        is ExprStmt -> sb.appendLine("${id}${dumpExpr(s.expr)}")
        is VarDeclStmt -> {
            val mut = if (s.mutable) "var" else "val"
            val tp = if (s.type != null) ": ${dumpTypeRef(s.type)}" else ""
            val init = if (s.init != null) " = ${dumpExpr(s.init)}" else ""
            sb.appendLine("${id}$mut ${s.name}$tp$init")
        }

        is AssignStmt -> sb.appendLine("${id}${dumpExpr(s.target)} ${s.op} ${dumpExpr(s.value)}")
        is ReturnStmt -> sb.appendLine("${id}return${if (s.value != null) " ${dumpExpr(s.value)}" else ""}")
        is ForStmt -> {
            sb.appendLine("${id}for (${s.varName} in ${dumpExpr(s.iter)})")
            sb.append(dumpBlock(s.body, depth))
        }

        is WhileStmt -> {
            sb.appendLine("${id}while (${dumpExpr(s.cond)})")
            sb.append(dumpBlock(s.body, depth))
        }

        is DoWhileStmt -> {
            sb.appendLine("${id}do")
            sb.append(dumpBlock(s.body, depth))
            sb.appendLine("${id}while (${dumpExpr(s.cond)})")
        }

        is BreakStmt -> sb.appendLine("${id}break")
        is ContinueStmt -> sb.appendLine("${id}continue")
        is DeferStmt -> {
            sb.appendLine("${id}defer")
            sb.append(dumpBlock(s.body, depth))
        }

        is CommentStmt -> sb.appendLine("${id}comment ${s.text}")
    }
    return sb.toString()
}

private fun dumpExpr(e: Expr): String = when (e) {
    is IntLit -> if (e.hex) "0x${e.value.toString(16)}" else "${e.value}"
    is LongLit -> if (e.hex) "0x${e.value.toString(16)}L" else "${e.value}L"
    is UIntLit -> if (e.hex) "0x${e.value.toString(16)}u" else "${e.value}u"
    is ULongLit -> if (e.hex) "0x${e.value.toString(16)}UL" else "${e.value}UL"
    is DoubleLit -> "${e.value}"
    is FloatLit -> "${e.value}f"
    is BoolLit -> "${e.value}"
    is CharLit -> "'${e.value}'"
    is StrLit -> "\"${e.value}\""
    is StrTemplateExpr -> "\"${
        e.parts.joinToString("") {
            when (it) {
                is LitPart -> it.text; is ExprPart -> "\${${dumpExpr(it.expr)}}"
            }
        }
    }\""

    is NullLit -> "null"
    is NameExpr -> e.name
    is ThisExpr -> "this"
    is BinExpr -> "(${dumpExpr(e.left)} ${e.op} ${dumpExpr(e.right)})"
    is PrefixExpr -> "(${e.op}${dumpExpr(e.expr)})"
    is PostfixExpr -> "(${dumpExpr(e.expr)}${e.op})"
    is CallExpr -> {
        val tas = if (e.typeArgs.isNotEmpty()) {
            "<${e.typeArgs.joinToString(", ") { dumpTypeRef(it) }}>"
        } else ""
        val args = e.args.joinToString(", ") { if (it.isSpread) "*${dumpExpr(it.expr)}" else dumpExpr(it.expr) }
        "${dumpExpr(e.callee)}$tas($args)"
    }

    is DotExpr -> "${dumpExpr(e.obj)}.${e.name}"
    is SafeDotExpr -> "${dumpExpr(e.obj)}?.${e.name}"
    is IndexExpr -> "${dumpExpr(e.obj)}[${dumpExpr(e.index)}]"
    is IfExpr -> {
        val els = if (e.els != null) {
            val es = StringBuilder()
            for (s in e.els.stmts) es.append(dumpStmt(s, 0))
            " else { ${es.toString().trim()} }"
        } else ""
        val ts = StringBuilder()
        for (s in e.then.stmts) ts.append(dumpStmt(s, 0))
        "if (${dumpExpr(e.cond)}) { ${ts.toString().trim()} }$els"
    }

    is WhenExpr -> {
        val brs = e.branches.joinToString(" ") { b ->
            val conds = if (b.conds == null) "else" else b.conds.joinToString(", ") { dumpWhenCond(it) }
            val body = StringBuilder()
            for (s in b.body.stmts) body.append(dumpStmt(s, 0))
            "$conds -> { ${body.toString().trim()} }"
        }
        val sub = if (e.subject != null) "(${dumpExpr(e.subject)})" else ""
        "when$sub { $brs }"
    }

    is NotNullExpr -> "${dumpExpr(e.expr)}!!"
    is ElvisExpr -> "(${dumpExpr(e.left)} ?: ${dumpExpr(e.right)})"
    is IsCheckExpr -> "${dumpExpr(e.expr)} ${if (e.negated) "!is" else "is"} ${dumpTypeRef(e.type)}"
    is CastExpr -> "${dumpExpr(e.expr)} as ${dumpTypeRef(e.type)}"
    is FunRefExpr -> "::${e.name}"
    is LambdaExpr -> {
        val params = if (e.params.isNotEmpty()) e.params.joinToString(", ") + " -> " else ""
        val body = e.body.joinToString("; ") { dumpStmt(it, 0).trim() }
        "{ $params$body }"
    }
}

private fun dumpWhenCond(c: WhenCond): String = when (c) {
    is ExprCond -> dumpExpr(c.expr)
    is IsCond -> "${if (c.negated) "!is " else "is "}${dumpTypeRef(c.type)}"
    is InCond -> "${if (c.negated) "!in " else "in "}${dumpExpr(c.expr)}"
}
