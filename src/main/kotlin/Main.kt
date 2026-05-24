package com.bitsycore.ktc

import com.bitsycore.ktc.ast.FunDecl
import com.bitsycore.ktc.ast.KtFile
import com.bitsycore.ktc.codegen.*
import com.bitsycore.ktc.parser.Lexer
import com.bitsycore.ktc.parser.Parser
import java.io.File
import kotlin.system.exitProcess

val aClass = object {}.javaClass

/** Lists resource file names (leaf only) inside resourcePath that end with extension. */
private fun discoverResourceFiles(resourcePath: String, extension: String): List<String> {
    val url = aClass.getResource(resourcePath) ?: return emptyList()
    return when (url.protocol) {
        "jar" -> {
            val conn   = url.openConnection() as java.net.JarURLConnection
            val prefix = resourcePath.removePrefix("/") + "/"
            conn.jarFile.entries().asSequence()
                .filter { !it.isDirectory && it.name.startsWith(prefix) && it.name.endsWith(extension) && !it.name.removePrefix(prefix).contains('/') }
                .map { it.name.removePrefix(prefix) }
                .toList()
        }
        "file" -> File(url.toURI()).listFiles()
            ?.filter { it.name.endsWith(extension) }
            ?.map { it.name }
            ?: emptyList()
        else -> emptyList()
    }
}

/** Parse `modules = ["A", "B"]` from a deps.ktc.toml string. */
private fun parseDepsToml(content: String): List<String> {
    val match = Regex("""^\s*modules\s*=\s*\[([^\]]*)]""", RegexOption.MULTILINE).find(content)
        ?: return emptyList()
    return match.groupValues[1].split(',')
        .map { it.trim().removeSurrounding("\"").removeSurrounding("'") }
        .filter { it.isNotEmpty() }
}

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println("Usage: ktc <file.kt...> [-o <output_dir>] [--module <name>] [--mem-track] [--disposed=ASSERT|LOG|NO] [--double-dispose=ASSERT|LOG|NO] [--main <qualified.name>] [--ast] [--dump-semantics]")
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
    val moduleNames = mutableListOf<String>()
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
        } else if (args[i] == "--module" && i + 1 < args.size) {
            moduleNames += args[i + 1]
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

    // Discover deps.ktc.toml in the source directories and merge with --module args
    val seenDirs = mutableSetOf<File>()
    for (f in inputFiles) {
        val dir = f.parentFile ?: continue
        if (!seenDirs.add(dir)) continue
        val depsFile = File(dir, "deps.ktc.toml")
        if (depsFile.exists()) moduleNames += parseDepsToml(depsFile.readText())
    }
    val resolvedModules = moduleNames.distinct()

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

    // Load module .kt files from embedded resources (modules/ in JAR)
    val moduleCmakes = mutableListOf<String>()
    for (moduleName in resolvedModules) {
        val modulePath = "/modules/$moduleName"
        val moduleKtFiles = discoverResourceFiles(modulePath, ".kt")
        if (moduleKtFiles.isEmpty()) {
            System.err.println("Warning: module '$moduleName' not found in bundled modules.")
            continue
        }
        for (name in moduleKtFiles.sorted()) {
            val res = aClass.getResourceAsStream("$modulePath/$name") ?: continue
            vRawSources += RawSource(File("modules/$moduleName/$name"), name, res.bufferedReader().readText(), false)
        }
        val cmakeRes = aClass.getResourceAsStream("$modulePath/module.cmake")
        if (cmakeRes != null) moduleCmakes += cmakeRes.bufferedReader().readText()
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
        val mergedImports  = realGroup.flatMap { it.ast.imports }.distinct()
        val mergedDecls    = realGroup.flatMap { it.ast.decls }
        val mergedIncludes = realGroup.flatMap { it.ast.cIncludes }.distinct()
        val mergedFile     = KtFile(realGroup.first().ast.pkg, mergedImports, mergedDecls, cIncludes = mergedIncludes)
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
        // Package header goes in the package subdirectory as _package_.h (alphabetically first in dir).
        // ktc packages: ktc/<subpath>/_package_.h;  user packages: outDir/<pkgPath>/_package_.h
        val pkgHdrDir   = when {
            isKtcPkg -> {
                val vSubPath = vPkgPath.removePrefix("ktc/")        // e.g. "std" from "ktc/std"
                if (vSubPath.isNotEmpty()) File(ktcDir, vSubPath).also { it.mkdirs() } else ktcDir
                }
            vPkgPath.isNotEmpty() -> File(outDir, vPkgPath).also { it.mkdirs() }
            else -> outDir
            }
        val headerFile  = File(pkgHdrDir, "_package_.h")
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
        val vPkgHdr   = if (vMPkgPath.isNotEmpty()) "$vMPkgPath/_package_.h" else "_package_.h"
        val vHasArgs  = vMainFun.params.size == 1                                // Array<String> param?
        val vReturnsInt = vMainFun.returnType?.name == "Int"                     // Int return type?
        val vMainC = buildString {
            appendLine("/* main.c — C entry point generated by ktc */")
            appendLine("#include \"$vPkgHdr\"")
            appendLine()
            if (vHasArgs) {
                appendLine("int main(int argc, char* argv[]) {")
                appendLine("    ktc_Int \$nargs = (argc > 1) ? (ktc_Int)(argc - 1) : 0;")
                appendLine("    ktc_String* \$args_buf = (\$nargs > 0) ? (ktc_String*)ktc_core_alloca((size_t)\$nargs * sizeof(ktc_String)) : NULL;")
                appendLine("    for (ktc_Int \$i = 0; \$i < \$nargs; \$i++) {")
                appendLine("        \$args_buf[\$i] = (ktc_String){argv[\$i + 1], (ktc_Int)strlen(argv[\$i + 1])};")
                appendLine("    }")
                appendLine("    ktc_VarArr_ktc_String \$vargs = {\$args_buf, \$nargs};")
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
    for (vName in listOf("ktc_macro.h", "ktc_thread.h", "ktc_thread.c", "ktc_core.h", "ktc_core.c")) {
        val vDst = File(ktcCoreDir, vName)
        val vSrc = aClass.getResourceAsStream("/ktc/$vName")
        if (vSrc != null) {
            vDst.writeText(vSrc.bufferedReader().readText())
        } else {
            System.err.println("Warning: $vName not found in resources, copy it manually.")
        }
    }

    // Build full source lists (paths relative to outDir) for compile hints and CMake.
    // ktcOutputNames are paths relative to ktc/ (e.g. "core/ktc_core", "std/Heap").
    // userOutputNames are paths relative to outDir (e.g. "com/example/Point").
    val vKtcFullSrcs  = (listOf("core/ktc_core") + ktcOutputNames.sorted()).map { "ktc/$it.c" }
    val vUserFullSrcs = userOutputNames.sorted().map { "$it.c" }
    val ktcSources    = vKtcFullSrcs.joinToString(" ")
    val userSources   = vUserFullSrcs.joinToString(" ")
    // Derive binary name from the last path component of the first user output name
    val mainBase = userOutputNames.firstOrNull()
        ?.substringAfterLast('/')?.substringBefore('_')?.ifEmpty { "output" } ?: "output"

    // ── Generate CMakeLists.txt (+ ktc_modules.cmake if modules active) ─────────
    writeCmakeFiles(outDir, mainBase, vKtcFullSrcs, vUserFullSrcs, moduleCmakes)
    println("  wrote ${File(outDir, "CMakeLists.txt").path}")

    println("Done. Compile with:  cc -std=c11 -iquote . -o $mainBase $ktcSources $userSources")
    println("  or: cmake -B build . && cmake --build build")
}
