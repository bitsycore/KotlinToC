package com.bitsycore.ktc

import com.bitsycore.ktc.ast.KtFile
import com.bitsycore.ktc.codegen.CCodeGen
import com.bitsycore.ktc.codegen.generate
import com.bitsycore.ktc.parser.Lexer
import com.bitsycore.ktc.parser.Parser
import org.intellij.lang.annotations.Language
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Base class for KotlinToC transpiler tests.
 *
 * Provides helpers to transpile Kotlin snippets and assert on the generated C code.
 * Each test feeds a self-contained Kotlin source through Lexer → Parser → CCodeGen
 * and checks the .c / .h output for expected patterns.
 *
 * ### C Compile Verification
 *
 * When the system property `ktc.verifyCompile=true` (or env var `KTC_VERIFY_COMPILE=true`)
 * is set, every transpile call also compiles the generated C output with a C compiler
 * using `-c` (compile to object, no link). This catches invalid C code early.
 *
 * ```bash
 * # Enable compile verification
 * ./gradlew test -Dktc.verifyCompile=true
 *
 * # Or via environment variable
 * $env:KTC_VERIFY_COMPILE = "true"
 * ./gradlew test
 * ```
 */
open class TranspilerTestBase {

    data class TranspileResult(
        val header: String,
        val source: String,
        val pkg: String,
        val warningCount: Int = 0
    ) {
        override fun toString(): String {
            return "$header\n$source"
        }
    }

    companion object {
        /**
         * Whether to compile the generated C code after transpiling.
         * Controlled by system property `ktc.verifyCompile` or env var `KTC_VERIFY_COMPILE`.
         */
        val verifyCompile: Boolean by lazy {
            val prop = System.getProperty("ktc.verifyCompile")
            if (prop != null) prop.toBoolean()
            else System.getenv("KTC_VERIFY_COMPILE")?.toBoolean() ?: false
        }

        /** Detect a C compiler on PATH. Returns null if none found. */
        private val ccCompiler: String? by lazy {
            if (!verifyCompile) return@lazy null
            val candidates = if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) {
                listOf("gcc", "clang", "cl")
            } else if(System.getProperty("os.name").contains("MacOS", ignoreCase = true)) {
                listOf("clang", "cc", "gcc")
            } else {
                listOf("cc", "gcc", "clang")
            }
            for (candidate in candidates) {
                try {
                    val proc = ProcessBuilder(candidate, "--version")
                        .redirectErrorStream(true)
                        .start()
                    val hasOutput = proc.inputStream.bufferedReader().use { it.readText().isNotBlank() }
                    proc.waitFor()
                    if (hasOutput) return@lazy candidate
                } catch (_: Exception) {
                    // not found, try next
                }
            }
            null
        }

        /** Temp directory root for compile verification outputs. */
        private val verifyTempDir: java.nio.file.Path by lazy {
            java.nio.file.Files.createTempDirectory("ktc_test_compile_")
        }
    }

    // ── Core transpile helper ────────────────────────────────────────

    /**
     * Transpile a Kotlin source string to C.
     * [src] should be a complete Kotlin file (with package, declarations, etc.).
     * If no package is given, one is auto-generated.
     */
    protected fun transpile(@Language("kotlin") src: String): TranspileResult {
        val source = src.trimIndent()
        prescanInfix(source) // register infix function names before parsing
        val tokens = Lexer(source).tokenize()
        val ast = Parser(tokens).parseFile()
        val allAsts = listOf(ast)
        val gen = CCodeGen(ast, allAsts, source.lines())
        val output = gen.generate()
        val result = TranspileResult(
            header = output.header,
            source = output.sources.values.joinToString("\n") { it.content },
            pkg = ast.pkg ?: "test",
            warningCount = gen.diagnosticWarningCount
        )
        if (verifyCompile) verifyCompiles(result)
        return result
    }

    /* Pre-scan source text for infix function names and add them to Parser.INFIX_IDS. */
    private val vInfixNameRx = Regex("""\binfix\s+fun\b[^(.]+\.(\w+)\s*\(""")
    private fun prescanInfix(inSource: String) {
        vInfixNameRx.findAll(inSource).forEach { vMatch -> Parser.INFIX_IDS.add(vMatch.groupValues[1]) }
    }

    /*
    Loads and parses all stdlib .kt files by scanning the /stdlib/ resource directory,
    plus the /modules/std/ module (mirrors what the transpiler does when std is active).
    Also prescans each file for infix function names. Returns ASTs for CCodeGen context.
    */
    protected fun loadStdlibAsts(): List<KtFile> {
        val cls = this.javaClass
        val vSources = mutableListOf<Pair<String, String>>()

        fun collectDir(resourcePath: String, prefix: String) {
            val dir = cls.getResource(resourcePath) ?: cls.getResource("$resourcePath/") ?: return
            val names = when (dir.protocol) {
                "jar" -> {
                    val conn = dir.openConnection() as java.net.JarURLConnection
                    conn.jarFile.entries().asSequence()
                        .filter { !it.isDirectory && it.name.startsWith("$prefix/") && it.name.endsWith(".kt") && !it.name.removePrefix("$prefix/").contains('/') }
                        .map { it.name.removePrefix("$prefix/") }
                        .toList()
                }
                "file" -> java.io.File(dir.toURI()).listFiles()
                    ?.filter { it.name.endsWith(".kt") }
                    ?.map { it.name } ?: emptyList()
                else -> emptyList()
            }
            names.sorted().mapNotNullTo(vSources) { name ->
                val res = cls.getResourceAsStream("/$prefix/$name") ?: return@mapNotNullTo null
                name to res.bufferedReader().readText()
            }
        }

        collectDir("/ktc", "ktc")
        collectDir("/modules/ktc.std", "modules/ktc.std")

        // Prescan all sources for infix names before any parsing
        vSources.forEach { (_, src) -> prescanInfix(src) }
        return vSources.mapNotNull { (name, src) ->
            val tokens = Lexer(src).tokenize()
            Parser(tokens).parseFile().copy(sourceFile = name)
        }
    }

    /*
    Transpiles [src] with the full stdlib included in the allAsts context,
    so stdlib types (Random, ArrayList, etc.) are visible to the codegen.
    The generated output is for the primary file only.
    Stdlib is loaded (and prescanned for infix names) BEFORE parsing the user source
    so stdlib infix operators like `to` are recognized during parsing.
    */
    protected fun transpileWithStdlib(@Language("kotlin") src: String): TranspileResult {
        val vSource = src.trimIndent()
        val vStdlibAsts = loadStdlibAsts()  // prescans stdlib infix names into Parser.INFIX_IDS
        prescanInfix(vSource)                // also prescan user source for any user-defined infix
        val vTokens = Lexer(vSource).tokenize()
        // Inject "ktc.std.*" so the codegen emits the #include for the std package header.
        val vAst = Parser(vTokens).parseFile()
            .let { it.copy(imports = (it.imports + "ktc.std.*").distinct()) }
        val vAllAsts = vStdlibAsts + vAst
        val vOutput = CCodeGen(vAst, vAllAsts, vSource.lines()).generate()
        val result = TranspileResult(
            header = vOutput.header,
            source = vOutput.sources.values.joinToString("\n"),
            pkg = vAst.pkg ?: "test"
        )
        if (verifyCompile) verifyCompiles(result)
        return result
    }

    /*
    Transpiles a single stdlib .kt file (by filename, e.g. "Random.kt") as the
    primary output, with all other stdlib files as cross-file context.
    Use this to verify declarations emitted by the stdlib itself.
    */
    protected fun transpileStdlibFile(vFileName: String): TranspileResult {
        val vRes = this.javaClass.getResourceAsStream("/ktc/$vFileName")
            ?: this.javaClass.getResourceAsStream("/modules/ktc.std/$vFileName")
            ?: error("stdlib file not found: $vFileName")
        val vSource = vRes.bufferedReader().readText()
        val vTokens = Lexer(vSource).tokenize()
        val vAst = Parser(vTokens).parseFile().copy(sourceFile = vFileName)
        val vAllAsts = loadStdlibAsts()
        val vOutput = CCodeGen(vAst, vAllAsts, vSource.lines()).generate()
        val result = TranspileResult(
            header = vOutput.header,
            source = vOutput.sources.values.joinToString("\n"),
            pkg = vAst.pkg ?: "ktc_std"
        )
        if (verifyCompile) verifyCompiles(result)
        return result
    }

    /*
    Shorthand: wrap body in a test package + main, transpile with stdlib context.
    */
    protected fun transpileMainWithStdlib(
        @Language("kotlin") body: String,
        decls: String = "",
        pkg: String = "test.Main"
    ): TranspileResult {
        val vSrc = buildString {
            appendLine("package $pkg")
            appendLine()
            if (decls.isNotBlank()) {
                appendLine(decls.trimIndent())
                appendLine()
            }
            appendLine("fun main(args: Array<String>) {")
            for (vLine in body.trimIndent().lines()) {
                appendLine("    $vLine")
            }
            appendLine("}")
        }
        return transpileWithStdlib(vSrc)
    }

    /**
     * Shorthand: wrap body in a test package + main function, transpile.
     * [body] is placed inside `fun main(args: Array<String>) { ... }`.
     * [decls] are placed at top-level (before main).
     */
    protected fun transpileMain(@Language("kotlin") body: String,@Language("kotlin") decls: String = "", pkg: String = "test.Main"): TranspileResult {
        val src = buildString {
            appendLine("package $pkg")
            appendLine()
            if (decls.isNotBlank()) {
                appendLine(decls.trimIndent())
                appendLine()
            }
            appendLine("fun main(args: Array<String>) {")
            for (line in body.trimIndent().lines()) {
                appendLine("    $line")
            }
            appendLine("}")
        }
        return transpile(src)
    }

    // ── C Compile Verification ──────────────────────────────────────

    data class CompileResult(
        val succeeded: Boolean,
        val command: String,
        val output: String,
        val exitCode: Int,
        val source: String,
        val header: String
    )

    private val compileCounter = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Compiles [result]'s generated C code to an object file.
     * Uses `-c` (compile only, no link) so no `main()` is required.
     * Returns a [CompileResult] for further assertions.
     */
    protected fun compileC(result: TranspileResult): CompileResult {
        val cc = ccCompiler ?: return CompileResult(
            succeeded = true, command = "", output = "no C compiler found", exitCode = 0,
            source = result.source, header = result.header
        )
        val idx = compileCounter.incrementAndGet()
        val dir = verifyTempDir.resolve("test_$idx").toFile()
        dir.mkdirs()

        val intrinsicH = javaClass.getResourceAsStream("/ktc/core/ktc_core.h")
            ?.bufferedReader()?.readText() ?: error("ktc_core.h not found")
        val intrinsicC = javaClass.getResourceAsStream("/ktc/core/ktc_core.c")
            ?.bufferedReader()?.readText() ?: error("ktc_core.c not found")
        java.io.File(dir, "ktc_core.h").writeText(intrinsicH)
        java.io.File(dir, "ktc_core.c").writeText(intrinsicC)

        val pkgBase = result.pkg.replace('.', '_')
        java.io.File(dir, "$pkgBase.h").writeText(result.header)
        java.io.File(dir, "$pkgBase.c").writeText(result.source)

        val cFile = java.io.File(dir, "$pkgBase.c")
        val oFile = java.io.File(dir, "$pkgBase.o")
        val compileCmd = listOf(cc, "-std=c11", "-c", cFile.absolutePath, "-o", oFile.absolutePath)

        return try {
            val proc = ProcessBuilder(compileCmd)
                .directory(dir)
                .redirectErrorStream(true)
                .start()
            val compileOutput = proc.inputStream.bufferedReader().readText()
            val ec = proc.waitFor()
            CompileResult(
                succeeded = ec == 0,
                command = compileCmd.joinToString(" "),
                output = compileOutput,
                exitCode = ec,
                source = result.source,
                header = result.header
            )
        } catch (e: Exception) {
            CompileResult(
                succeeded = false,
                command = compileCmd.joinToString(" "),
                output = "C compiler invocation failed: ${e.message}",
                exitCode = -1,
                source = result.source,
                header = result.header
            )
        }
    }

    private fun verifyCompiles(result: TranspileResult) {
        val cr = compileC(result)
        if (!cr.succeeded) {
            fail("C compilation failed (exit ${cr.exitCode}).\nCommand: ${cr.command}\nOutput:\n${cr.output}")
        }
    }

    // ── CompileResult helpers ─────────────────────────────────────

    fun CompileResult.compileSucceeded(message: String? = null) {
        assertTrue(succeeded, message ?: "C compilation failed (exit $exitCode).\nCommand: $command\nOutput:\n$output")
    }

    fun CompileResult.compileOutputContains(text: String, message: String? = null) {
        assertTrue(output.contains(text), message ?: "Compile output should contain:\n  «$text»\n\nActual:\n$output")
    }

    fun CompileResult.compileOutputMatches(regex: Regex, message: String? = null) {
        assertTrue(regex.containsMatchIn(output), message ?: "Compile output should match:\n  «${regex.pattern}»\n\nActual:\n$output")
    }

    // ── Assertion helpers ────────────────────────────────────────────

    /** Assert the C source contains [text] (substring match). */
    protected fun TranspileResult.sourceContains(text: String, message: String? = null) {
        assertTrue(
            source.contains(text),
            message ?: "Expected C source to contain:\n  «$text»\n\nActual source:\n$source"
        )
    }

    protected fun TranspileResult.sourceContainsXTime(text: String, time: Int = 1, message: String? = null) {
        var count = 0
        var pos = source.indexOf(text)
        while (pos >= 0) { count++; pos = source.indexOf(text, pos + 1) }
        assertEquals(
            time, count,
            message ?: "Expected C source to contain «$text» exactly once, but found $count times.\n\nActual source:\n$source"
        )
    }

    /** Assert the C header contains [text]. */
    protected fun TranspileResult.headerContains(text: String, message: String? = null) {
        assertTrue(
            header.contains(text),
            message ?: "Expected C header to contain:\n  «$text»\n\nActual header:\n$header"
        )
    }

    /** Assert the C source does NOT contain [text]. */
    protected fun TranspileResult.sourceNotContains(text: String, message: String? = null) {
        assertTrue(
            !source.contains(text),
            message ?: "Expected C source NOT to contain:\n  «$text»\n\nActual source:\n$source"
        )
    }

    /** Assert the C source matches [regex]. */
    protected fun TranspileResult.sourceMatches(regex: Regex, message: String? = null) {
        assertTrue(
            regex.containsMatchIn(source),
            message ?: "Expected C source to match:\n  «${regex.pattern}»\n\nActual source:\n$source"
        )
    }

    /** Assert the C header matches [regex]. */
    protected fun TranspileResult.headerMatches(regex: Regex, message: String? = null) {
        assertTrue(
            regex.containsMatchIn(header),
            message ?: "Expected C header to match:\n  «${regex.pattern}»\n\nActual header:\n$header"
        )
    }

    protected fun TranspileResult.hasWarnings(expected: Int = 1) {
        assertEquals(expected, warningCount, "Expected $expected warning(s), got $warningCount")
    }

    protected fun TranspileResult.hasNoWarnings() {
        assertEquals(0, warningCount, "Expected no warnings, got $warningCount")
    }

    /** Assert transpilation fails with an error containing [expectedMsg]. */
    protected fun transpileExpectError(src: String, expectedMsg: String) {
        try {
            transpile(src.trimIndent())
            fail("Expected transpilation to fail with error containing '$expectedMsg', but it succeeded")
        } catch (e: Exception) {
            assertEquals(
                e.message?.contains(expectedMsg),
                true,
                "Expected error containing '$expectedMsg', got: ${e.message}"
            )
        }
    }

    /**
     * Mark a test as "not yet implemented" — skipped via JUnit5 assumption
     * instead of failing. Use for valid Kotlin syntax that the transpiler
     * doesn't support yet.
     */
    protected fun notYetImpl(reason: String = "not yet implemented") {
        org.junit.jupiter.api.Assumptions.assumeTrue(false, "NOT YET IMPL: $reason")
    }

    /** Assert transpilation of a main-body snippet fails. */
    protected fun transpileMainExpectError(body: String, expectedMsg: String, decls: String = "", pkg: String = "test.Main") {
        val src = buildString {
            appendLine("package $pkg")
            appendLine()
            if (decls.isNotBlank()) {
                appendLine(decls.trimIndent())
                appendLine()
            }
            appendLine("fun main(args: Array<String>) {")
            for (line in body.trimIndent().lines()) {
                appendLine("    $line")
            }
            appendLine("}")
        }
        transpileExpectError(src, expectedMsg)
    }
}
