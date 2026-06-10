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

/** Parse a TOML array value like `["A", "B"]` into a list of strings. */
private fun parseTomlStringArray(arrayContent: String): List<String> =
    arrayContent.split(',')
        .map { it.trim().removeSurrounding("\"").removeSurrounding("'") }
        .filter { it.isNotEmpty() }

// Parse dependencies from a module.ktc.toml string.
// Accepts both "dependencies = [...]" and "modules = [...]" (legacy deps.ktc.toml compat).
private fun parseDependencies(content: String): List<String> {
    val match = Regex("""^\s*(?:dependencies|modules)\s*=\s*\[([^\]]*)]""", RegexOption.MULTILINE).find(content)
        ?: return emptyList()
    return parseTomlStringArray(match.groupValues[1])
}

// Parse the "main" field from module.ktc.toml (e.g. main = "com.example.run").
private fun parseMainEntry(content: String): String? =
    Regex("""^\s*main\s*=\s*"([^"]+)"""", RegexOption.MULTILINE).find(content)?.groupValues?.get(1)

// Parse the "autoFindMain" field from module.ktc.toml.
private fun parseAutoFindMain(content: String): Boolean =
    Regex("""^\s*autoFindMain\s*=\s*true""", RegexOption.MULTILINE).find(content) != null

// Parse the "executable" field from module.ktc.toml (e.g. executable = "myapp").
private fun parseExecutable(content: String): String? =
    Regex("""^\s*executable\s*=\s*"([^"]+)"""", RegexOption.MULTILINE).find(content)?.groupValues?.get(1)

// A module name that is an absolute path refers to a filesystem directory, not a bundled JAR resource.
private fun isFileSystemModule(name: String): Boolean = File(name).isAbsolute

// Detect a URL module (https://, http://, or file:// prefix).
private fun isUrlModule(name: String): Boolean =
    name.startsWith("https://") || name.startsWith("http://") || name.startsWith("file://")

// Resolve a URL module to a local filesystem path by cloning the repo into a cache directory.
// URL format: "https://...repo.git#subpath/to/module" — splits on last '#'.
// Without '#', the repo root is the module directory.
private fun resolveUrlModule(url: String): String {
    val hashIdx = url.lastIndexOf('#')
    val repoUrl: String
    val subPath: String
    if (hashIdx > 0 && hashIdx < url.length - 1) {
        repoUrl = url.substring(0, hashIdx)
        subPath = url.substring(hashIdx + 1)
    } else {
        repoUrl = if (hashIdx == url.length - 1) url.substring(0, hashIdx) else url
        subPath = ""
    }

    val cacheDir = File(System.getProperty("user.home"), ".ktc/cache")
    cacheDir.mkdirs()

    // Use a hash of the repo URL as the cache folder name
    val repoHash = repoUrl.hashCode().toUInt().toString(16)
    val repoName = repoUrl.substringAfterLast('/').removeSuffix(".git").ifEmpty { "repo" }
    val cloneDir = File(cacheDir, "${repoName}_$repoHash")

    if (cloneDir.exists()) {
        // Pull latest changes
        val pullResult = ProcessBuilder("git", "-C", cloneDir.absolutePath, "pull", "--ff-only", "-q")
            .redirectErrorStream(true).start()
        pullResult.waitFor()
    } else {
        // Clone
        System.err.println("  Fetching module: $repoUrl")
        val cloneResult = ProcessBuilder("git", "clone", "--depth", "1", "-q", repoUrl, cloneDir.absolutePath)
            .redirectErrorStream(true).start()
        val output = cloneResult.inputStream.bufferedReader().readText()
        val exitCode = cloneResult.waitFor()
        if (exitCode != 0) {
            System.err.println("Error: failed to clone module '$repoUrl':\n$output")
            exitProcess(1)
        }
    }

    val moduleDir = if (subPath.isNotEmpty()) File(cloneDir, subPath) else cloneDir
    if (!moduleDir.isDirectory) {
        System.err.println("Error: subpath '$subPath' not found in cloned repo '$repoUrl'")
        exitProcess(1)
    }
    return moduleDir.canonicalPath
}

// Read module.ktc.toml content for a module (bundled or filesystem). Returns null if absent.
private fun readModuleToml(moduleName: String, aClass: Class<*>): String? {
    if (isFileSystemModule(moduleName)) {
        val f = File(moduleName, "module.ktc.toml")
        return if (f.exists()) f.readText() else null
    }
    return aClass.getResourceAsStream("/modules/$moduleName/module.ktc.toml")?.bufferedReader()?.readText()
}

/** Parse dependencies from a module's module.ktc.toml. Returns empty list if absent. */
private fun parseModuleDeps(moduleName: String, aClass: Class<*>): List<String> {
    val content = readModuleToml(moduleName, aClass) ?: return emptyList()
    val deps = parseDependencies(content)
    // Resolve relative and URL dependency paths
    val moduleDir = if (isFileSystemModule(moduleName)) File(moduleName) else null
    return deps.map { dep ->
        when {
            isUrlModule(dep) -> resolveUrlModule(dep)
            (dep.startsWith("./") || dep.startsWith("../")) && moduleDir != null ->
                File(moduleDir, dep).canonicalPath
            else -> dep
        }
    }
}

/** Parse `autoImport = "ktc.std.*"` from a module's module.ktc.toml. Returns null if absent. */
private fun parseModuleAutoImport(moduleName: String, aClass: Class<*>): String? {
    val content = readModuleToml(moduleName, aClass) ?: return null
    return Regex("""^\s*autoImport\s*=\s*"([^"]+)"""", RegexOption.MULTILINE)
        .find(content)?.groupValues?.get(1)
}

/** Expand [seeds] into a full ordered load list by following module `dependencies`, BFS, no duplicates. */
private fun resolveModules(seeds: List<String>, aClass: Class<*>): List<String> {
    val result = mutableListOf<String>()
    val seen = mutableSetOf<String>()
    val queue = ArrayDeque(seeds)
    while (queue.isNotEmpty()) {
        val name = queue.removeFirst()
        if (!seen.add(name)) continue
        val deps = parseModuleDeps(name, aClass)
        // Insert deps before the module itself so dependencies come first
        for (dep in deps.reversed()) queue.addFirst(dep)
        result += name
    }
    return result
}

private fun printDiagnosticsJson(inDiags: List<CCodeGen.Diagnostic>) {
    fun escapeJson(s: String): String = buildString {
        for (c in s) when {
            c == '\\' -> append("\\\\")
            c == '"'  -> append("\\\"")
            c == '\n' -> append("\\n")
            c == '\t' -> append("\\t")
            c == '\r' -> append("\\r")
            c.code < 0x20 -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
    }
    val out = java.io.PrintStream(System.out, true, "UTF-8")
    out.println("[")
    for ((idx, d) in inDiags.withIndex()) {
        val comma = if (idx < inDiags.size - 1) "," else ""
        out.println("""  {"severity":"${d.severity}","message":"${escapeJson(d.message)}","file":"${escapeJson(d.file)}","line":${d.line},"col":${d.col}}$comma""")
    }
    out.println("]")
}

// All warning names that codegen can emit (kept in sync with codegenWarning(name, ...) call sites).
// These are the valid -W<name> / -Wno-<name> suffixes.
private val kWarningNames = listOf(
    "shadow", "nullable-ref", "tailrec-inline", "tailrec-suggestion",
    "const-condition", "exhaustive-when", "null-check", "safe-call",
    "bounds", "sized-array-truncate", "empty-body", "redundant-bang",
    "self-assign", "self-compare", "identical-branches",
    "unreachable", "discarded-alloc", "no-effect-expr", "unused-local", "could-be-val",
)

// Valid values for --disposed / --double-dispose.
private val kDisposeModes = setOf("ASSERT", "LOG", "NO")

/* Print the full CLI usage to the given stream. */
fun printUsage(inOut: java.io.PrintStream) {
    inOut.println("Usage: ktc <file.kt...|module.ktc.toml|dir/> [-o <output_dir>] [--module <name>] [--name <exe>] [--mem-track] [--disposed=ASSERT|LOG|NO] [--double-dispose=ASSERT|LOG|NO] [--main <qualified.name>] [--ast] [--dump-semantics]")
    inOut.println("  Transpiles Kotlin subset files to C11.")
    inOut.println("  A module.ktc.toml or directory containing one can be given instead of .kt files.")
    inOut.println("  --help, -h                   Print this usage and exit")
    inOut.println("  --version                    Print version info and exit")
    inOut.println("  --check                      Validate source (lex + parse + collect), skip C emission")
    inOut.println("  --explain <code>             Print a detailed explanation for an error/warning code (e.g. E020, W002)")
    inOut.println("  --strict                     Promote all warnings to errors")
    inOut.println("  --diagnostics=json           Output errors/warnings as JSON (for editor/LSP integration)")
    inOut.println("  -W<name>                     Enable warning <name> (e.g. -Wshadow)")
    inOut.println("  -Wno-<name>                  Disable warning <name> (e.g. -Wno-safe-call)")
    // Wrap the warning-name list at ~70 cols so --help stays readable.
    val vChunks = kWarningNames.chunked(4)
    vChunks.forEachIndexed { vIdx, vChunk ->
        val vLabel = if (vIdx == 0) "Names: " else "       "
        inOut.println("                               $vLabel${vChunk.joinToString(", ")}${if (vIdx < vChunks.lastIndex) "," else ""}")
    }
    inOut.println("  --mem-track                  Enable allocation tracking (alloc/free counts + leak report)")
    inOut.println("  --check-bounds               Runtime bounds check on every array/string [] access (default ON)")
    inOut.println("  --no-check-bounds            Disable runtime bounds checks (faster, but out-of-range is UB)")
    inOut.println("  --check-null                 Runtime null-deref check on every .refValue access (default ON)")
    inOut.println("  --no-check-null              Disable runtime null-deref checks (faster, but null .refValue is UB)")
    inOut.println("  --disposed=ASSERT|LOG|NO     Use-after-dispose: abort / log+continue / ignore (default: NO)")
    inOut.println("  --double-dispose=ASSERT|LOG|NO  Double-dispose: abort / log+continue / ignore (default: NO)")
    inOut.println("  --main <qualified.name>      Select the entry point by qualified name (e.g. com.example.Main.run)")
    inOut.println("                               Required when multiple main-compatible functions exist.")
    inOut.println("                               Valid signatures: fun <name>(), fun <name>(): Int,")
    inOut.println("                                                 fun <name>(args: Array<String>),")
    inOut.println("                                                 fun <name>(args: Array<String>): Int")
    inOut.println("  --ast                        Dump parsed AST and exit (no C output)")
    inOut.println("  --dump-semantics             Dump AST + semantic analysis and exit")
}

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        printUsage(System.err)
        exitProcess(1)
    }

    // ── --help / -h: print usage to stdout and exit cleanly (any position) ──
    if ("--help" in args || "-h" in args) {
        printUsage(System.out)
        return
    }

    // ── --version: print version and exit early (any position) ──────
    if ("--version" in args) {
        val version = aClass.getPackage()?.implementationVersion ?: "1.0-SNAPSHOT"
        println("ktc $version")
        return
    }

    // ── --explain: print error/warning explanation (any position) ───
    val explainIdx = args.indexOf("--explain")
    if (explainIdx >= 0) {
        val code = args.getOrNull(explainIdx + 1)
        val entry = code?.let { ErrorCatalog.lookup(it) }
        if (entry != null) {
            println("${entry.code}: ${entry.title}")
            println()
            println(entry.explanation)
        } else {
            System.err.println("Unknown error code '${code ?: ""}'.")
            System.err.println("Valid codes: ${ErrorCatalog.allCodes().joinToString(", ") { it.code }}")
            exitProcess(1)
        }
        return
    }

    // Parse args: collect .kt files and flags
    val inputPaths = mutableListOf<String>()
    val moduleNames = mutableListOf<String>()
    var outputDir = "."
    var memTrack = false
    var disposedMode = "NO"        // ASSERT | LOG | NO
    var doubleDisposeMode = "NO"   // ASSERT | LOG | NO
    var checkBounds = true         // runtime bounds check on every array[] access (default ON; --no-check-bounds disables)
    var checkNull   = true         // runtime null-deref check on .refValue accesses (default ON; --no-check-null disables)
    var mainOverride: String? = null  // --main qualified.name
    var nameOverride: String? = null  // --name exe-name
    var dumpAst = false
    var dumpSemantics = false
    var checkOnly = false
    var strict = false
    var diagnosticsJson = false
    val warnFlags = mutableMapOf<String, Boolean>()
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
        } else if (args[i] == "--check-bounds") {
            checkBounds = true   // no-op when default is ON, kept for backwards compat / explicit intent
            i++
        } else if (args[i] == "--no-check-bounds") {
            checkBounds = false
            i++
        } else if (args[i] == "--check-null") {
            checkNull = true   // no-op when default is ON, kept for explicit intent
            i++
        } else if (args[i] == "--no-check-null") {
            checkNull = false
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
        } else if (args[i] == "--name" && i + 1 < args.size) {
            nameOverride = args[i + 1]
            i += 2
        } else if (args[i] == "--ast") {
            dumpAst = true
            i++
        } else if (args[i] == "--dump-semantics") {
            dumpSemantics = true
            i++
        } else if (args[i] == "--check") {
            checkOnly = true
            i++
        } else if (args[i] == "--strict") {
            strict = true
            i++
        } else if (args[i] == "--diagnostics=json") {
            diagnosticsJson = true
            i++
        } else if (args[i].startsWith("-Wno-")) {
            warnFlags[args[i].removePrefix("-Wno-")] = false
            i++
        } else if (args[i].startsWith("-W") && args[i].length > 2) {
            warnFlags[args[i].removePrefix("-W")] = true
            i++
        } else if (args[i] == "--version") {
            i++
        } else if (args[i].startsWith("-")) {
            // Any unrecognized dash-prefixed token is a mistyped/unknown flag, not an input path.
            System.err.println("Error: unknown option '${args[i]}'")
            System.err.println("Run 'ktc --help' for the list of options.")
            exitProcess(2)
        } else {
            inputPaths += args[i]
            i++
        }
    }

    // Validate enum-valued flags before doing any work (silent bad values otherwise half-configure codegen).
    if (disposedMode !in kDisposeModes) {
        System.err.println("Error: --disposed must be ASSERT|LOG|NO, got '$disposedMode'")
        exitProcess(1)
    }
    if (doubleDisposeMode !in kDisposeModes) {
        System.err.println("Error: --double-dispose must be ASSERT|LOG|NO, got '$doubleDisposeMode'")
        exitProcess(1)
    }

    if (inputPaths.isEmpty()) {
        System.err.println("Error: no input files specified")
        exitProcess(1)
    }

    // Resolve input files — a module.ktc.toml (or directory containing one) expands to its .kt files
    val inputFiles = mutableListOf<File>()
    for (path in inputPaths) {
        val f = File(path)
        if (!f.exists()) {
            System.err.println("Error: file not found: $path")
            exitProcess(1)
        }
        // If given a directory, look for module.ktc.toml inside it
        val tomlFile = when {
            f.isDirectory -> File(f, "module.ktc.toml").takeIf { it.exists() }
            f.name == "module.ktc.toml" -> f
            else -> null
        }
        if (tomlFile != null) {
            val tomlDir = tomlFile.parentFile ?: File(".")
            val content = tomlFile.readText()
            // Read config fields from the toml if not already set via CLI
            if (mainOverride == null) {
                val tomlMain = parseMainEntry(content)
                if (tomlMain != null) mainOverride = tomlMain
                else if (parseAutoFindMain(content)) mainOverride = "__auto__"
            }
            if (nameOverride == null) {
                val tomlExe = parseExecutable(content)
                if (tomlExe != null) nameOverride = tomlExe
            }
            for (mod in parseDependencies(content)) {
                if (isUrlModule(mod))
                    moduleNames += resolveUrlModule(mod)
                else if (mod.startsWith("./") || mod.startsWith("../"))
                    moduleNames += File(tomlDir, mod).canonicalPath
                else
                    moduleNames += mod
            }
            // Discover .kt files in the same directory
            val ktFiles = tomlDir.listFiles()?.filter { it.extension == "kt" }?.sorted() ?: emptyList()
            if (ktFiles.isEmpty()) {
                System.err.println("Error: no .kt files in module directory: ${tomlDir.path}")
                exitProcess(1)
            }
            inputFiles += ktFiles
        } else {
            inputFiles += f
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

    // Discover module.ktc.toml (or legacy deps.ktc.toml) in source directories
    val seenDirs = mutableSetOf<File>()
    for (f in inputFiles) {
        val dir = f.parentFile ?: continue
        if (!seenDirs.add(dir)) continue
        val moduleToml = File(dir, "module.ktc.toml")
        val depsToml = File(dir, "deps.ktc.toml")
        val tomlFile = if (moduleToml.exists()) moduleToml else if (depsToml.exists()) depsToml else continue
        val content = tomlFile.readText()
        for (mod in parseDependencies(content)) {
            if (isUrlModule(mod))
                moduleNames += resolveUrlModule(mod)
            else if (mod.startsWith("./") || mod.startsWith("../"))
                moduleNames += File(dir, mod).canonicalPath
            else
                moduleNames += mod
        }
        // Pick up main/autoFindMain/executable from module.ktc.toml if not set via CLI
        if (mainOverride == null) {
            val tomlMain = parseMainEntry(content)
            if (tomlMain != null) mainOverride = tomlMain
            else if (parseAutoFindMain(content)) mainOverride = "__auto__"
        }
        if (nameOverride == null) {
            val tomlExe = parseExecutable(content)
            if (tomlExe != null) nameOverride = tomlExe
        }
    }
    // Resolve URL modules passed via --module args
    for (i in moduleNames.indices) {
        if (isUrlModule(moduleNames[i]))
            moduleNames[i] = resolveUrlModule(moduleNames[i])
    }
    val resolvedModules = resolveModules(moduleNames.distinct(), aClass)
    val moduleAutoImports = resolvedModules.mapNotNull { parseModuleAutoImport(it, aClass) }.distinct()

    // Collect stdlib .kt files from resources
    val stdlibDir = aClass.getResource("/ktc") ?: aClass.getResource("/ktc/")
    if (stdlibDir != null) {
        val stdlibFiles = when (stdlibDir.protocol) { // discover stdlib file names
            "jar" -> {
                val connection = stdlibDir.openConnection()
                val jarFile = (connection as java.net.JarURLConnection).jarFile
                jarFile.entries().asSequence()
                    .filter { !it.isDirectory && it.name.startsWith("ktc/") && it.name.endsWith(".kt") }
                    .map { it.name.removePrefix("ktc/") }
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
            val res = aClass.getResourceAsStream("/ktc/$name")
            if (res != null) vRawSources += RawSource(File("ktc/$name"), name, res.bufferedReader().readText(), true)
        }
    }

    // Load module .kt files from embedded resources (modules/ in JAR) or filesystem
    val moduleCmakes = mutableListOf<String>()
    for (moduleName in resolvedModules) {
        if (isFileSystemModule(moduleName)) {
            val moduleDir = File(moduleName)
            if (!moduleDir.isDirectory) {
                System.err.println("Warning: filesystem module '$moduleName' is not a directory.")
                continue
            }
            val ktFiles = moduleDir.listFiles()?.filter { it.extension == "kt" }?.sortedBy { it.name }
            if (ktFiles.isNullOrEmpty()) {
                System.err.println("Warning: no .kt files in filesystem module '$moduleName'.")
                continue
            }
            for (f in ktFiles) {
                vRawSources += RawSource(f, f.name, f.readText(), false)
            }
            val cmakeFile = File(moduleDir, "module.cmake")
            if (cmakeFile.exists()) moduleCmakes += cmakeFile.readText()
        } else {
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
    }

    // Collect user sources
    for (inputFile in inputFiles) {
        vRawSources += RawSource(inputFile, inputFile.name, inputFile.readText(), false)
    }

    // Pre-scan all sources for infix function names (must happen before any parsing).
    // Matches both `infix fun <T> Recv.name(` (extension) and `infix fun name(` (member) — the
    // method name is the last identifier before the parameter `(`. (B12)
    val vInfixNameRx = Regex("""\binfix\s+fun\b[^(]*?(\w+)\s*\(""")
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

    // Inject module auto-imports (e.g. "ktc.std.*") into user source files.
    // Module/stdlib files (package starts with "ktc") are skipped — they don't need their own auto-import.
    if (moduleAutoImports.isNotEmpty()) {
        for (i in parsedFiles.indices) {
            val ps = parsedFiles[i]
            if (ps.ast.pkg?.let { it == "ktc" || it.startsWith("ktc.") } == true) continue
            val newImports = (ps.ast.imports + moduleAutoImports).distinct()
            parsedFiles[i] = ps.copy(ast = ps.ast.copy(imports = newImports))
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

    // ── --check: validate without emitting code ───────────────────────
    if (checkOnly) {
        val allAsts = parsedFiles.map { it.ast }.filter { !it.documentationOnly }
        val byPkg = parsedFiles.groupBy { it.ast.pkg ?: it.file.nameWithoutExtension }
        var hasError = false
        val allDiagnostics = mutableListOf<CCodeGen.Diagnostic>()
        for ((_, group) in byPkg) {
            val realGroup = group.filter { !it.ast.documentationOnly }
            if (realGroup.isEmpty()) continue
            val mergedImports  = realGroup.flatMap { it.ast.imports }.distinct()
            val mergedDecls    = realGroup.flatMap { it.ast.decls }
            val mergedIncludes = realGroup.flatMap { it.ast.cIncludes }.distinct()
            val mergedFile     = KtFile(realGroup.first().ast.pkg, mergedImports, mergedDecls, cIncludes = mergedIncludes)
            val mergedSourceLines = realGroup.flatMap { it.sourceLines }
            val srcName = if (realGroup.size == 1) realGroup.first().file.name else "${realGroup.first().ast.pkg}.kt"
            try {
                val gen = CCodeGen(
                    mergedFile, allAsts, mergedSourceLines,
                    memTrack = memTrack, disposedMode = disposedMode,
                    doubleDisposeMode = doubleDisposeMode,
                    checkBounds = checkBounds, checkNull = checkNull,
                    strict = strict,
                    sourceFileName = srcName,
                    diagnosticsJson = diagnosticsJson,
                    warnFlags = warnFlags)
                gen.collectAndScan()
                allDiagnostics += gen.diagnostics
            } catch (e: Exception) {
                if (!diagnosticsJson) System.err.println(e.message)
                hasError = true
            }
        }
        if (diagnosticsJson) {
            printDiagnosticsJson(allDiagnostics)
            if (hasError) exitProcess(1)
            return
        }
        if (hasError) exitProcess(1)
        println("OK")
        return
    }

    // ── Group files by package ───────────────────────────────────────
    // Files with the same package are merged into a single output unit.
    // Files with different packages produce separate .c/.h outputs.
    val byPackage = parsedFiles.groupBy { it.ast.pkg ?: it.file.nameWithoutExtension }

    val outDir = File(outputDir)
    outDir.mkdirs()
    val ktcDir = File(outDir, "ktc")                  // ktc/ for intrinsic + std package output
    val ktcCoreDir = File(ktcDir, "core")             // ktc/core/ for C runtime files
    ktcDir.mkdirs()
    ktcCoreDir.mkdirs()

    // Track every file we plan to write. Files that already exist with the
    // same content are left untouched (so the C compiler's mtime cache stays
    // warm); stale outputs from a previous run are deleted at the end.
    val tracker = OutputTracker(outDir)

    val allAsts = parsedFiles.map { it.ast }.filter { !it.documentationOnly }
    val allDiagnostics = mutableListOf<CCodeGen.Diagnostic>()
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
            val gen = CCodeGen(
                mergedFile,
                allAsts,
                mergedSourceLines,
                memTrack = memTrack,
                disposedMode = disposedMode,
                doubleDisposeMode = doubleDisposeMode,
                checkBounds = checkBounds,
                checkNull = checkNull,
                strict = strict,
                sourceFileName = srcName,
                diagnosticsJson = diagnosticsJson,
                warnFlags = warnFlags
            )
            output = gen.generate()
            allDiagnostics += gen.diagnostics
        } catch (e: Exception) {
            if (diagnosticsJson) {
                printDiagnosticsJson(allDiagnostics)
            } else {
                System.err.println(e.message)
            }
            exitProcess(1)
        }

        val baseName    = mergedFile.pkg?.replace('.', '_') ?: pkg  // mangled pkg (e.g. "com_example")
        val isKtcPkg    = baseName == "ktc" || baseName.startsWith("ktc_") // ktc or ktc.* packages
        val vPkgPath    = mergedFile.pkg?.replace('.', '/') ?: pkg  // e.g. "com/example", "ktc/std", "ktc"
        // Package header goes in the package subdirectory as _package_.h (alphabetically first in dir).
        // ktc packages: ktc/<subpath>/_package_.h;  user packages: outDir/<pkgPath>/_package_.h
        val pkgHdrDir   = when {
            isKtcPkg -> {
                val vSubPath = vPkgPath.removePrefix("ktc").removePrefix("/") // "ktc/std"→"std", "ktc"→""
                if (vSubPath.isNotEmpty()) File(ktcDir, vSubPath).also { it.mkdirs() } else ktcDir
                }
            vPkgPath.isNotEmpty() -> File(outDir, vPkgPath).also { it.mkdirs() }
            else -> outDir
            }
        val headerFile  = File(pkgHdrDir, "_package_.h")
        if (tracker.write(headerFile, output.header) && !diagnosticsJson) println("  wrote ${headerFile.path}")

        // Write each .c file to the directory determined by its routingPkg
        for ((vFileName, vSourceFile) in output.sources) {
            val vRoutingPkg  = vSourceFile.routingPkg                    // dot-separated, e.g. "ktc.std", "ktc"
            val vRoutingPath = vRoutingPkg.replace('.', '/')             // e.g. "ktc/std", "ktc"
            val vIsKtcFile   = vRoutingPkg == "ktc" || vRoutingPkg.startsWith("ktc.") // ktc or ktc.* packages
            val vFileSrcDir: File
            val vRelBase: String                                          // path for compile command
            if (vIsKtcFile) {
                // Strip "ktc" prefix to get the subpath within ktcDir ("ktc/std"→"std", "ktc"→"")
                val vSubPath = vRoutingPath.removePrefix("ktc").removePrefix("/")
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
            if (tracker.write(vOutputFile, vSourceFile.content) && !diagnosticsJson) println("  wrote ${vOutputFile.path}")
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

    if (mainOverride != null && mainOverride != "__auto__") {
        // --main "a.b.c.funName" or main = "a.b.c.funName" in module.ktc.toml
        val vMain    = mainOverride
        val vDotIdx  = vMain.lastIndexOf('.')                                // split point
        val vOvrPkg  = if (vDotIdx > 0) vMain.substring(0, vDotIdx) else ""  // package part
        val vOvrName = vMain.substring(vDotIdx + 1)                          // function name part
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
        if (tracker.write(vMainCFile, vMainC) && !diagnosticsJson) println("  wrote ${vMainCFile.path}")
        userOutputNames += "main"
        vGeneratedMainC = true
    }

    // ── Copy intrinsic files to ktc/core/ ───────────────────────
    for (vName in listOf("ktc_macro.h", "ktc_thread.h", "ktc_thread.c", "ktc_core.h", "ktc_core.c",
                         "ktc_core_fs.h", "ktc_core_fs.c", "ktc_core_exception.h", "ktc_core_exception.c")) {
        val vDst = File(ktcCoreDir, vName)
        val vSrc = aClass.getResourceAsStream("/ktc/core/$vName")
        if (vSrc != null) {
            tracker.write(vDst, vSrc.bufferedReader().readText())
        } else {
            System.err.println("Warning: $vName not found in resources, copy it manually.")
        }
    }

    // Build full source lists (paths relative to outDir) for compile hints and CMake.
    // ktcOutputNames are paths relative to ktc/ (e.g. "std/Heap").
    // userOutputNames are paths relative to outDir (e.g. "com/example/Point").
    val vCoreFullSrcs = listOf("ktc/core/ktc_core.c", "ktc/core/ktc_thread.c", "ktc/core/ktc_core_fs.c",
                               "ktc/core/ktc_core_exception.c")
    val vKtcFullSrcs  = ktcOutputNames.sorted().map { "ktc/$it.c" }
    val vUserFullSrcs = userOutputNames.sorted().map { "$it.c" }
    fun shellQuote(path: String) = if ('$' in path || ' ' in path) "'" + path + "'" else path
    val ktcSources    = (vCoreFullSrcs + vKtcFullSrcs).joinToString(" ") { shellQuote(it) }
    val userSources   = vUserFullSrcs.joinToString(" ") { shellQuote(it) }
    // Derive binary name: --name override, else heuristic from first user output
    val mainBase = nameOverride
        ?: userOutputNames.firstOrNull()
            ?.substringAfterLast('/')?.substringBefore('_')?.ifEmpty { "output" }
        ?: "output"

    // ── Generate CMakeLists.txt (+ ktc_modules.cmake if modules active) ─────────
    writeCmakeFiles(outDir, mainBase, vCoreFullSrcs + vKtcFullSrcs, vUserFullSrcs, moduleCmakes, tracker)

    // ── Clean up stale transpiler outputs from previous runs ────────────────────
    // Any .c/.h/CMakeLists.txt/ktc_modules.cmake in outDir that wasn't part of
    // this emission set is deleted. Files the transpiler doesn't own (_cmake/,
    // ktc_user.cmake, DLLs, the final executable) are left untouched. This is
    // what lets the C compiler keep its incremental cache: unchanged files
    // weren't rewritten, so their mtimes weren't bumped.
    val vRemoved = tracker.cleanupStale()
    if (!diagnosticsJson) for (vR in vRemoved) println("  removed (stale) $vR")

    if (diagnosticsJson && allDiagnostics.isNotEmpty()) {
        printDiagnosticsJson(allDiagnostics)
    }
    if (!diagnosticsJson) {
        println("Done. Compile with:  cc -std=c11 -iquote . -o $mainBase $ktcSources $userSources")
        println("  or: cmake -B build . && cmake --build build")
    }
}
