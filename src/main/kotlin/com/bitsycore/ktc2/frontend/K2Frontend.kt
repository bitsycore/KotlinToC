@file:OptIn(ExperimentalCompilerApi::class)

package com.bitsycore.ktc2.frontend

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import com.bitsycore.ktc2.backend.ArcInsert
import com.bitsycore.ktc2.backend.CEmitter
import com.bitsycore.ktc2.backend.CycleLint
import com.bitsycore.ktc2.kir.KirDump
import com.bitsycore.ktc2.interop.BindingGenerator
import com.bitsycore.ktc2.interop.DefFile
import com.bitsycore.ktc2.backend.Devirtualize
import com.bitsycore.ktc2.backend.EscapeAnalysis
import com.bitsycore.ktc2.backend.ExceptionLowering
import com.bitsycore.ktc2.backend.RangeChecks
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.util.dump
import org.jetbrains.kotlin.ir.util.dumpKotlinLike
import java.io.File

// The compiler loads the registrar in its own classloader, so options travel as system properties.
private const val kOutDirProperty = "ktc2.outDir"
private const val kDumpIrProperty = "ktc2.dumpIr"

/** The packages whose code is the library's, not the program's: left out of a dump of KIR unless asked for. */
private val kLibraryPackages = listOf("kotlin", "kotlinx", "ktc2", "java", "platform")
private const val kErrorCountProperty = "ktc2.errorCount"

/** The experimental language features programs may use; given to both the C and the JVM reference compilation. */
private val kLanguageOptions = listOf("-Xcompanion-blocks-and-extensions", "-Xcollection-literals", "-Xfull-value-classes")
private const val kCompilerOptsProperty = "ktc2.compilerOpts"
private const val kLinkerOptsProperty = "ktc2.linkerOpts"

/** Name of the file, in the output directory, that lists what a compilation produced. */
const val kManifestName = "ktc2_build.txt"

/** System property listing the source files that are not the program's, one absolute path per line. */
private const val kLibraryFilesProperty = "ktc2.libraryFiles"

/** System property holding the `System.nanoTime()` at which the compiler was started. */
private const val kStartProperty = "ktc2.startTime"

// ==================
// MARK: Driver
// ==================

/**
 * Runs the Kotlin K2 compiler in-process and hands the resolved module IR to the ktc2 back end.
 * The same run also writes the JVM classes used as the reference ("oracle") build.
 */
object K2Frontend {

	/**
	 * Compiles [inSources] into C under `<inOutDir>/c`.
	 *
	 * The Kotlin standard library is compiled from source together with the program (see
	 * [librarySources]); only what the program reaches ends up in the output.
	 *
	 * @param inDumpIr also write the IR received from K2 (`ir.txt`, `ir.kt.txt`)
	 * @return true when neither K2 nor the ktc2 back end reported an error
	 */
	fun compile(inSources: List<File>, inOutDir: File, inDumpIr: Boolean): Boolean {
		inOutDir.mkdirs()
		System.setProperty(kOutDirProperty, inOutDir.absolutePath)
		System.setProperty(kDumpIrProperty, inDumpIr.toString())
		System.setProperty(kErrorCountProperty, "0")
		val vLibrary = librarySources(inOutDir)
		// Each .def file becomes a Kotlin source of bindings, compiled with the program.
		val vDefinitions = inSources.filter { it.extension == "def" }
		val vCompiler = com.bitsycore.ktc2.cCompiler()
		val vBindings = try {
			val vExtraCompilerOpts = System.getProperty("ktc2.copt", "").lines().filter { it.isNotEmpty() }
			val vExtraLinkerOpts = System.getProperty("ktc2.lopt", "").lines().filter { it.isNotEmpty() }
			val vGenerated = vDefinitions.map {
				BindingGenerator(DefFile(it), vCompiler, "", vExtraCompilerOpts, vExtraLinkerOpts).generate(File(inOutDir, "interop"), File(inOutDir, "c"))
			}
			// `import platform.posix.*` asks for the C library, described by a .def of ktc2's own.
			val vUsesPosix = (inSources - vDefinitions.toSet()).any { vSource -> Regex("\\bplatform\\.posix\\b").containsMatchIn(vSource.readText()) }
			if (vUsesPosix && vDefinitions.none { DefFile(it).packageName == "platform.posix" }) {
				val vDefinition = File(inOutDir, "interop/posix.def")
				vDefinition.parentFile.mkdirs()
				vDefinition.writeText(resourceText("/ktc2/interop/posix.def"))
				vGenerated + BindingGenerator(DefFile(vDefinition), vCompiler, resourceText("/ktc2/interop/posix.kt.txt")).generate(File(inOutDir, "interop"), File(inOutDir, "c"))
			} else {
				vGenerated
			}
		} catch (vError: IllegalStateException) {
			System.err.println("ktc2: error: ${vError.message}")
			return false
		}
		System.setProperty(kCompilerOptsProperty, vBindings.flatMap { it.compilerOpts }.distinct().joinToString("\n"))
		System.setProperty(kLinkerOptsProperty, vBindings.flatMap { it.linkerOpts }.distinct().joinToString("\n"))
		// The files that are not the program's: the library sources and the generated bindings.
		System.setProperty(kLibraryFilesProperty, (vLibrary + vBindings.map { it.source }).joinToString("\n") { it.absoluteFile.invariantSeparatorsPath })
		val vArgs = buildList {
			(inSources - vDefinitions.toSet()).forEach { add(it.path) }
			vBindings.forEach { add(it.source.path) }
			vLibrary.forEach { add(it.path) }
			if (vLibrary.isNotEmpty()) {
				// The flags the standard library itself is built with.
				add("-opt-in=kotlin.RequiresOptIn")
				add("-opt-in=kotlin.contracts.ExperimentalContracts")
				add("-opt-in=kotlin.ExperimentalMultiplatform")
				add("-opt-in=kotlin.ExperimentalStdlibApi")
				add("-Xreturn-value-checker=check")
				add("-Xdont-warn-on-error-suppression")
				// The common sources declare `expect`s; the platform layer provides the `actual`s.
				add("-Xmulti-platform")
				add("-Xcommon-sources=" + vLibrary.filter { "commonMain" in it.invariantSeparatorsPath || it.name.startsWith("Ktc2Common") }.joinToString(",") { it.path })
				add("-nowarn")
			}
			// Library sources are declared in the real kotlin.* packages and stand in for the jar's declarations.
			add("-Xallow-kotlin-package")
			// The language as of Kotlin 2.5, with what it still calls experimental: `companion { }`
			// blocks and companion extensions, collection literals (`[1, 2, 3]`), full value classes.
			// The standard library's own sources use the first two.
			addAll(kLanguageOptions)
			add("-d"); add(File(inOutDir, "jvm-unused").path)
			add("-no-stdlib"); add("-no-reflect")
			add("-cp"); add(listOf(filteredStdlib(vLibrary).path, codeSourceOf(ktc2.Struct::class.java)).joinToString(File.pathSeparator))
			add("-Xplugin=${pluginClasspath()}")
		}
		val vStart = System.nanoTime()
		System.setProperty(kStartProperty, vStart.toString())
		val vExit = K2JVMCompiler().exec(System.err, *vArgs.toTypedArray())
		reportTime("total", vStart)
		return vExit == ExitCode.OK && System.getProperty(kErrorCountProperty) == "0"
	}

	/** With `KTC2_TIMING` set, prints how long the step [inWhat] took since [inSince] (a `System.nanoTime()`). */
	internal fun reportTime(inWhat: String, inSince: Long) {
		if (System.getenv("KTC2_TIMING") != null) System.err.println("ktc2: time: $inWhat ${(System.nanoTime() - inSince) / 1_000_000} ms")
	}

	/**
	 * A copy of the kotlin-stdlib jar without the classes that hold what [inLibrary] declares
	 * from source. With both present, a call could see two candidates (the source function
	 * and the jar's), and the JVM typealiases (`ArrayList` = `java.util.ArrayList`) would
	 * compete with the platform layer's classes.
	 *
	 * The top-level declarations of `Name.kt` are compiled on the JVM into a class
	 * `<Facade>Kt__NameKt` (or `NameKt`), and their JVM-specific counterparts into
	 * `…NameJVMKt` / `…NameJvmKt`; those are the classes left out, plus the ones listed in
	 * `ktc2/stdlib/hidden.txt`. The copy is cached in the temporary directory.
	 */
	private fun filteredStdlib(inLibrary: List<File>): File {
		val vOriginal = File(codeSourceOf(Unit::class.java))
		if (inLibrary.isEmpty() || !vOriginal.isFile) return vOriginal
		val vNames = inLibrary.filter { "commonMain" in it.invariantSeparatorsPath }.map { it.nameWithoutExtension.replaceFirstChar { vChar -> vChar.uppercase() } }.toSet()
		val vHidden = (K2Frontend::class.java.getResourceAsStream("/ktc2/stdlib/hidden.txt")?.use { it.readBytes().decodeToString() } ?: "")
			.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()
		val vKey = (vOriginal.path + vOriginal.lastModified() + vNames.sorted() + vHidden.sorted()).hashCode().toUInt().toString(16)
		val vFiltered = File(System.getProperty("java.io.tmpdir"), "ktc2/kotlin-stdlib-$vKey.jar")
		if (vFiltered.isFile) return vFiltered

		fun isHidden(inEntry: String): Boolean {
			if (!inEntry.endsWith(".class")) return false
			if (inEntry in vHidden) return true
			val vClass = inEntry.substringAfterLast('/').removeSuffix(".class").substringBefore('$')
			val vPart = vClass.substringAfter("Kt__", vClass)
			val vFile = vPart.removeSuffix("Kt").removeSuffix("JVM").removeSuffix("Jvm")
			return vPart.endsWith("Kt") && vFile in vNames
		}
		vFiltered.parentFile.mkdirs()
		val vTemp = File(vFiltered.path + ".tmp")
		java.util.zip.ZipFile(vOriginal).use { vZip ->
			java.util.zip.ZipOutputStream(vTemp.outputStream().buffered()).use { vOut ->
				for (vEntry in vZip.entries()) {
					if (isHidden(vEntry.name)) continue
					vOut.putNextEntry(java.util.zip.ZipEntry(vEntry.name))
					if (!vEntry.isDirectory) vZip.getInputStream(vEntry).use { it.copyTo(vOut) }
					vOut.closeEntry()
				}
			}
		}
		vTemp.renameTo(vFiltered)
		return vFiltered
	}

	private fun resourceText(inPath: String): String =
		K2Frontend::class.java.getResourceAsStream(inPath)?.use { it.readBytes().decodeToString() } ?: throw IllegalStateException("missing resource $inPath")

	/**
	 * Compiles [inSources] as ordinary Kotlin/JVM into [inOutDir], against the real standard
	 * library and nothing of ktc2 but the `ktc2.` package: the reference build tests compare with.
	 */
	fun compileJvm(inSources: List<File>, inOutDir: File): Boolean {
		val vArgs = inSources.map { it.path } + listOf(
			"-d", inOutDir.path, "-no-stdlib", "-no-reflect", "-nowarn", "-cp", userClasspath(),
			// The inline functions of the `ktc2.` package are built for the JVM this runs on.
			"-jvm-target", System.getProperty("java.specification.version"),
		) + kLanguageOptions
		return K2JVMCompiler().exec(System.err, *vArgs.toTypedArray()) == ExitCode.OK
	}

	/**
	 * The library sources compiled with every program:
	 * - files of the Kotlin standard library, unmodified, listed in `ktc2/stdlib/files.txt` and
	 *   read from the directory named by the `ktc2.stdlibDir` system property (the unpacked
	 *   kotlin-stdlib sources jar) or, without it, from the copies ktc2 is packaged with;
	 * - ktc2's platform layer, the `.kt` resources under `ktc2/stdlib/`, copied to `<inOutDir>/stdlib`.
	 */
	private fun librarySources(inOutDir: File): List<File> {
		val vResult = ArrayList<File>()
		val vListing = K2Frontend::class.java.getResourceAsStream("/ktc2/stdlib/files.txt")?.use { it.readBytes().decodeToString() } ?: ""
		val vEntries = vListing.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
		val vStdlibDir = System.getProperty("ktc2.stdlibDir")?.let { File(it) }
		for (vEntry in vEntries) {
			if (vEntry.endsWith(".kt") && !vEntry.contains('/')) {
				val vText = K2Frontend::class.java.getResourceAsStream("/ktc2/stdlib/$vEntry")?.use { it.readBytes().decodeToString() } ?: continue
				val vCopy = File(inOutDir, "stdlib/$vEntry")
				vCopy.parentFile.mkdirs()
				if (!vCopy.isFile || vCopy.readText() != vText) vCopy.writeText(vText)
				vResult += vCopy
			} else if (vStdlibDir != null) {
				File(vStdlibDir, vEntry).takeIf { it.isFile }?.let { vResult += it }
			} else {
				// The installed ktc2 carries the files it needs of the standard library sources.
				val vBytes = K2Frontend::class.java.getResourceAsStream("/ktc2/kotlin-stdlib/$vEntry")?.use { it.readBytes() } ?: continue
				val vCopy = File(inOutDir, "stdlib/$vEntry")
				vCopy.parentFile.mkdirs()
				if (!vCopy.isFile || !vCopy.readBytes().contentEquals(vBytes)) vCopy.writeBytes(vBytes)
				vResult += vCopy
			}
		}
		return vResult
	}

	/** Classpath user programs compile against: the Kotlin builtins and the `ktc2.` package. */
	fun userClasspath(): String =
		listOf(Unit::class.java, ktc2.Struct::class.java)
			.map { codeSourceOf(it) }
			.distinct()
			.joinToString(File.pathSeparator)

	/**
	 * Where the compiler finds [Ktc2Registrar] and its service file: this jar, or the classes
	 * and resources directories when running unpackaged.
	 */
	private fun pluginClasspath(): String {
		val vRoots = linkedSetOf(codeSourceOf(Ktc2Registrar::class.java))
		val vService = "META-INF/services/${CompilerPluginRegistrar::class.java.name}"
		for (vUrl in Ktc2Registrar::class.java.classLoader.getResources(vService)) {
			if (vUrl.protocol != "file" || Ktc2Registrar::class.java.name !in vUrl.readText()) continue
			vRoots += File(vUrl.toURI()).parentFile.parentFile.parentFile.path
		}
		return vRoots.joinToString(",")
	}

	private fun codeSourceOf(inClass: Class<*>): String =
		File(inClass.protectionDomain.codeSource.location.toURI()).path
}

// ==================
// MARK: Compiler hook
// ==================

/** Registers [Ktc2Extension] with the K2 compiler. */
class Ktc2Registrar : CompilerPluginRegistrar() {

	override val pluginId: String = "ktc2"

	override val supportsK2: Boolean = true

	override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
		IrGenerationExtension.registerExtension(Ktc2Extension())
	}
}

/**
 * Entry of the ktc2 back end: receives the module IR after FIR→IR, before any JVM lowering,
 * imports it into KIR and emits C under `<outDir>/c`.
 */
class Ktc2Extension : IrGenerationExtension {

	override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
		K2Frontend.reportTime("Kotlin front end", System.getProperty(kStartProperty).toLong())
		val vStart = System.nanoTime()
		try {
			translate(moduleFragment, pluginContext)
		} finally {
			K2Frontend.reportTime("translation to C", vStart)
			// The compiler would go on to produce JVM classes of the program and the library sources,
			// which nobody wants: with no file left, it has nothing to produce.
			moduleFragment.files.clear()
		}
	}

	private fun translate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
		val vOutDir = File(System.getProperty(kOutDirProperty))
		if (System.getProperty(kDumpIrProperty) == "true") {
			File(vOutDir, "ir.txt").writeText(moduleFragment.dump())
			File(vOutDir, "ir.kt.txt").writeText(moduleFragment.dumpKotlinLike())
		}

		val vImporter = KirImporter(pluginContext.irBuiltIns)
		val vJavaUses = JavaUseCheck(System.getProperty(kLibraryFilesProperty, "").lines().toSet()).also { it.check(moduleFragment.files) }
		if (vJavaUses.errors.isNotEmpty()) {
			// Nothing else is said about such a program: every use would be reported again as unsupported.
			vJavaUses.errors.forEach { System.err.println("ktc2: error: $it") }
			System.setProperty(kErrorCountProperty, vJavaUses.errors.size.toString())
			return
		}
		val vModule = vImporter.import(moduleFragment)
		if (vModule.entry == null) vImporter.errors += "no 'fun main()' found"
		vImporter.errors.forEach { System.err.println("ktc2: error: $it") }
		System.setProperty(kErrorCountProperty, vImporter.errors.size.toString())
		if (vImporter.errors.isNotEmpty()) return
		vImporter.warnings.forEach { System.err.println("ktc2: warning: $it") }

		// With `--dump-ir`, KIR is written after the importer and after each pass (`kir/`): the code of
		// the program itself, or with KTC2_DUMP_KIR=<text> the functions whose C name contains the
		// text (`all`: every function, the library's too).
		val vDumpFilter = System.getenv("KTC2_DUMP_KIR")
		val vDumpsKir = System.getProperty(kDumpIrProperty) == "true" || vDumpFilter != null
		var vStage = 0
		fun dumpKir(inStage: String) {
			if (!vDumpsKir) return
			val vFile = File(vOutDir, "kir/${vStage++}-${inStage.takeWhile { it.isLetter() }.lowercase()}.txt")
			KirDump(vModule).write(vFile, inStage) { vFunction ->
				when (vDumpFilter) {
					null -> kLibraryPackages.none { vFunction.unit.pkg == it || vFunction.unit.pkg.startsWith("$it.") }
					"all" -> true
					else -> vDumpFilter in vFunction.cName
				}
			}
		}
		dumpKir("Import: as the importer wrote it")
		if (System.getProperty("ktc2.warnCycles") != "false") {
			CycleLint(vModule).also { it.run() }.warnings.forEach { System.err.println("ktc2: warning: $it") }
		}
		// KTC2_SKIP names optimizations to leave out (devirtualize, ranges, handover, brief), to find which one a wrong program comes from.
		val vSkipped = (System.getenv("KTC2_SKIP") ?: "").split(',').map { it.trim() }.toSet()
		val vDirect = Devirtualize(vModule).also { if ("devirtualize" !in vSkipped) it.run() }
		val vRanges = RangeChecks(vModule).also { if ("ranges" !in vSkipped) it.run() }
		if (System.getenv("KTC2_DEBUG_PASSES") != null) {
			System.err.println("ktc2: passes: ${vDirect.copied} functions copied for the classes they are called with, ${vDirect.resolved} dispatched calls made direct, ${vDirect.answered} type tests answered, ${vRanges.proven} array accesses proven in range")
		}
		dumpKir("Devirtualize and RangeChecks: calls made direct, type tests answered, accesses proven in range")
		ExceptionLowering(vModule).run()
		dumpKir("ExceptionLowering: throw and try as a parked exception and tests")
		val vEscapes = EscapeAnalysis(vModule)
		vEscapes.run()
		dumpKir("EscapeAnalysis: objects on the stack, results built in place")
		if (System.getProperty("ktc2.warnHeap") == "true") vEscapes.heapReport.forEach { System.err.println("ktc2: heap: $it") }
		if (vEscapes.errors.isNotEmpty()) {
			vEscapes.errors.forEach { System.err.println("ktc2: error: $it") }
			System.setProperty(kErrorCountProperty, vEscapes.errors.size.toString())
			return
		}
		ArcInsert(vModule, mHandsOverAtLastUse = "handover" !in vSkipped, mBorrowsBriefly = "brief" !in vSkipped, mWalks = "walk" !in vSkipped, mOwnsParameters = "owned" !in vSkipped).run()
		dumpKir("ArcInsert: retains and releases, as the C is written from")
		val vSources = CEmitter(vModule).emit(File(vOutDir, "c"))
		val vMain = moduleFragment.files.first { vFile -> vFile.declarations.any { it is IrSimpleFunction && isEntryPoint(it) } }
		val vFacade = File(vMain.fileEntry.name).nameWithoutExtension.replaceFirstChar { it.uppercase() } + "Kt"
		val vPackage = vMain.packageFqName.asString()
		File(vOutDir, kManifestName).writeText(buildString {
			appendLine("jvmMain=" + if (vPackage.isEmpty()) vFacade else "$vPackage.$vFacade")
			vSources.forEach { appendLine("c=$it") }
			// A program that starts threads is linked with the thread library.
			if (vModule.usesThreads) appendLine("threads=true")
			// What the C libraries bound with .def files ask of the C compiler and the linker.
			System.getProperty(kCompilerOptsProperty, "").lines().filter { it.isNotEmpty() }.forEach { appendLine("cflag=$it") }
			System.getProperty(kLinkerOptsProperty, "").lines().filter { it.isNotEmpty() }.forEach { appendLine("ldflag=$it") }
		})
	}
}
