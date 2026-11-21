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
import com.bitsycore.ktc2.backend.ReleaseLadders
import com.bitsycore.ktc2.kir.KirDump
import com.bitsycore.ktc2.interop.BindingGenerator
import com.bitsycore.ktc2.interop.DefFile
import com.bitsycore.ktc2.backend.Devirtualize
import com.bitsycore.ktc2.backend.EscapeAnalysis
import com.bitsycore.ktc2.backend.ExceptionLowering
import com.bitsycore.ktc2.backend.ListLoops
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
	/**
	 * [inSources], where a file written for Kotlin/Native (under `nativeMain`, `posixMain`, `unixMain`,
	 * `linuxMain`, `mingwMain` and the like) is replaced by a copy that imports `kotlin.native.*`, which
	 * Kotlin/Native imports by default and the JVM's compiler does not. The import is put on the
	 * `package` line, so that the lines of the copy are those of the file.
	 */
	private fun withNativeImports(inSources: List<File>, inOutDir: File): List<File> = inSources.map { vSource ->
		val vPath = vSource.invariantSeparatorsPath
		if (vSource.extension != "kt" || !kNativeSourceSet.containsMatchIn(vPath)) return@map vSource
		val vText = vSource.readText()
		val vPackage = Regex("(?m)^package [\\w.`]+").find(vText) ?: return@map vSource
		// Under gen/native, by its module and its place there (`kotlinx-io@0.9.3/core/nativeMain/...`); a file of no module
		// of the cache by its whole path.
		val vCache = File(com.bitsycore.ktc2.cacheDirectory(), "modules").absoluteFile
		val vPlace = vSource.absoluteFile.relativeToOrNull(vCache)?.invariantSeparatorsPath?.takeIf { !it.startsWith("..") }
			?: vPath.substringAfterLast(":").trimStart('/').replace(':', '_')
		val vCopy = File(inOutDir, "gen/native/$vPlace")
		val vCopied = vText.replaceRange(vPackage.range, vPackage.value + "; import kotlin.native.*")
		vCopy.parentFile.mkdirs()
		if (!vCopy.isFile || vCopy.readText() != vCopied) vCopy.writeText(vCopied)
		vCopy
	}

	/** A directory of sources written for Kotlin/Native (kotlinx-datetime's `tzfileMain`, `windowsMain`... are too). */
	private val kNativeSourceSet = Regex("/(native|posix|unix|linux|mingw|windows|apple|darwin|tzfile|tzdbOnFilesystem|androidNative|nativeNon[A-Za-z]+)(Main|Test)/")

	/**
	 * A source set between the common one and the platform's, with `actual`s of the common `expect`s and `expect`s of
	 * its own: `nativeMain`, or kotlinx-datetime's `commonKotlinMain` (its date arithmetic in Kotlin, shared by Native and the web).
	 */
	private val kMiddleSourceSet = Regex("/(native|commonKotlin)Main/")

	/**
	 * True for a source of a common source set (its main code or its tests), of `concurrent`, common to the JVM and
	 * Native, or of `nativeCommonTest`: what the suite puts in the place of a library ktc2 has not, on the level of the
	 * common tests that use it (okio for kotlinx.serialization's tests), where the JVM has the library itself.
	 */
	private fun isCommonSource(inSource: File): Boolean = kCommonSourceSet.containsMatchIn(inSource.invariantSeparatorsPath)

	private val kCommonSourceSet = Regex("/(common|concurrent|nativeCommon|annotationsCommon|assertionsCommon)(Main|Test)/")

	/**
	 * @param inOwnSources the program's own files among [inGivenSources] (not those of the modules it depends on); null:
	 *   all of them. With the library kept between compilations, the modules' files are kept with it, and only these
	 *   are compiled on top: what changes from one build of a program to the next.
	 */
	fun compile(inGivenSources: List<File>, inOutDir: File, inDumpIr: Boolean, inOwnSources: Collection<File>? = null): Boolean {
		val vSources = withNativeImports(inGivenSources, inOutDir)
		val vOwnGiven = inOwnSources?.map { it.absoluteFile.normalize() }?.toSet()
		val vOwn = if (vOwnGiven == null) vSources.toSet() else vSources.filterIndexed { vIndex, _ -> inGivenSources[vIndex].absoluteFile.normalize() in vOwnGiven }.toSet()
		inOutDir.mkdirs()
		System.setProperty(kOutDirProperty, inOutDir.absolutePath)
		System.setProperty(kDumpIrProperty, inDumpIr.toString())
		System.setProperty(kErrorCountProperty, "0")
		// With the library kept between compilations, its sources are in a place of this process, not of this program, and
		// the modules' sources are kept with it. The program's own files are one part on top of it, which has no room for
		// common code: a program with common sources of its own (a module of `expect`s and `actual`s) is compiled whole.
		val vKeepsLibrary = keepsLibrary() && vOwn.none { isCommonSource(it) }
		com.bitsycore.ktc2.Progress.begin("Kotlin → IR", "${vSources.size} files, with the library")
		val vLibrary = try {
			librarySources(if (vKeepsLibrary) kLibraryDir else inOutDir)
		} catch (vError: IllegalStateException) {
			com.bitsycore.ktc2.Progress.end("failed")
			System.err.println("ktc2: error: ${vError.message}")
			return false
		}
		// Each .def file becomes a Kotlin source of bindings, compiled with the program.
		val vDefinitions = vSources.filter { it.extension == "def" }
		val vCompiler = com.bitsycore.ktc2.cCompiler()
		val vBindings = try {
			val vExtraCompilerOpts = System.getProperty("ktc2.copt", "").lines().filter { it.isNotEmpty() }
			val vExtraLinkerOpts = System.getProperty("ktc2.lopt", "").lines().filter { it.isNotEmpty() }
			val vGenerated = vDefinitions.map {
				BindingGenerator(DefFile(it, vCompiler), vCompiler, "", vExtraCompilerOpts, vExtraLinkerOpts).generate(File(inOutDir, "gen/interop"), File(inOutDir, "c"))
			}
			// `import platform.posix.*` asks for the C library, described by a .def of ktc2's own.
			val vUsesPosix = (vSources - vDefinitions.toSet()).any { vSource -> Regex("\\bplatform\\.posix\\b").containsMatchIn(vSource.readText()) }
			if (vUsesPosix && vDefinitions.none { DefFile(it).packageName == "platform.posix" }) {
				val vDefinition = File(inOutDir, "gen/interop/posix.def")
				vDefinition.parentFile.mkdirs()
				vDefinition.writeText(resourceText("/ktc2/interop/posix.def"))
				vGenerated + BindingGenerator(DefFile(vDefinition, vCompiler), vCompiler, resourceText("/ktc2/interop/posix.kt.txt")).generate(File(inOutDir, "gen/interop"), File(inOutDir, "c"))
			} else {
				vGenerated
			}
		} catch (vError: IllegalStateException) {
			System.err.println("ktc2: error: ${vError.message}")
			return false
		}
		// What the command and the modules ask (-copt, -lopt, compilerOpts...) is for the whole program, with or without a .def.
		val vCommandCompilerOpts = System.getProperty("ktc2.copt", "").lines().filter { it.isNotEmpty() }
		val vCommandLinkerOpts = System.getProperty("ktc2.lopt", "").lines().filter { it.isNotEmpty() }
		System.setProperty(kCompilerOptsProperty, (vCommandCompilerOpts + vBindings.flatMap { it.compilerOpts }).distinct().joinToString("\n"))
		System.setProperty(kLinkerOptsProperty, (vCommandLinkerOpts + vBindings.flatMap { it.linkerOpts }).distinct().joinToString("\n"))
		// The files that are not the program's: the library sources and the generated bindings.
		System.setProperty(kLibraryFilesProperty, (vLibrary + vBindings.map { it.source }).joinToString("\n") { it.absoluteFile.normalize().invariantSeparatorsPath })
		val vProgram = (vSources - vDefinitions.toSet()) + vBindings.map { it.source }
		// What is compiled with the library: everything when nothing is kept; when it is, the modules' files and the
		// bindings of their .def files (the program's own .def files and the C library's bindings go on top with it,
		// unless a module uses those too). The rest, on top, is what [PhasedCompiler] resolves for each build.
		val vOwnDefinitions = vDefinitions.filter { it in vOwn }.map { DefFile(it).packageName }.toSet()
		val vModuleFiles = vProgram.filter { it !in vOwn && it !in vBindings.map { vBinding -> vBinding.source } }
		val vModulesUsePosix = vModuleFiles.any { vSource -> Regex("\\bplatform\\.posix\\b").containsMatchIn(vSource.readText()) }
		val vOnTop = if (!vKeepsLibrary) emptyList() else vProgram.filter { vFile ->
			val vBinding = vBindings.firstOrNull { it.source == vFile }
			when {
				vBinding == null -> vFile in vOwn
				vFile.nameWithoutExtension == "platform_posix" -> !vModulesUsePosix
				else -> vOwnDefinitions.any { vFile.nameWithoutExtension == it.replace('.', '_') }
			}
		}
		val vWithLibrary = vProgram - vOnTop.toSet()
		val vArgs = buildList {
			vWithLibrary.forEach { add(it.path) }
			vLibrary.forEach { add(it.path) }
			if (vLibrary.isNotEmpty()) {
				// The flags the standard library itself is built with.
				add("-opt-in=kotlin.RequiresOptIn")
				add("-opt-in=kotlin.contracts.ExperimentalContracts")
				add("-opt-in=kotlin.ExperimentalMultiplatform")
				add("-opt-in=kotlin.ExperimentalStdlibApi")
				// What the tests of the standard library are compiled with besides (`ktc2.optIn`, set by the suite).
				System.getProperty("ktc2.optIn")?.split(',')?.forEach { add("-opt-in=$it") }
				// What the modules' files name in `optIn`.
				System.getProperty("ktc2.moduleOptIn")?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.forEach { add("-opt-in=$it") }
				add("-Xreturn-value-checker=check")
				add("-Xdont-warn-on-error-suppression")
				// The common sources declare `expect`s; the platform layer provides the `actual`s.
				add("-Xmulti-platform")
				// A module's own sources under a `commonMain` directory are common code too (kotlinx-io's: its `expect`s, with the `actual`s beside them).
				// A module's `concurrentMain` (common to the JVM and Native, with `actual`s of its `commonMain`) is a level of
				// its own, between the common code and `nativeMain`, as in its build; its `concurrentTest` with it.
				val vConcurrent = vWithLibrary.filter { Regex("/concurrent(Main|Test)/").containsMatchIn(it.invariantSeparatorsPath) }
				val vCommonProgram = vWithLibrary.filter { isCommonSource(it) && it !in vConcurrent }
				// `kotlin.test` (Ktc2Test.kt, plain Kotlin) is common too: common tests use it.
				val vCommon = vLibrary.filter { "commonMain" in it.invariantSeparatorsPath || it.name.startsWith("Ktc2Common") || it.name == "Ktc2Test.kt" } + vCommonProgram
				// What Kotlin/Native shares with other targets below the common code, taken from the Wasm sources jar:
				// commonNonJvmMain, then nativeWasmMain below it. Each has `actual`s of the `expect`s above it and
				// `expect`s whose `actual`s are below it (in ktc2's platform layer): levels of their own, as those
				// source sets are in the standard library's build.
				val vNonJvm = vLibrary.filter { "commonNonJvmMain" in it.invariantSeparatorsPath }
				val vNativeWasm = vLibrary.filter { "nativeWasmMain" in it.invariantSeparatorsPath }
				val vShared = vNonJvm + vNativeWasm
				// Those of `nativeMain` are a level of their own between: they have `actual`s of the common
				// `expect`s, and `expect`s whose `actual`s are below them (ktc2's part of the module). The
				// compiler is then told the levels (its "fragments"), as Gradle tells it a hierarchy of source sets.
				val vNative = vWithLibrary.filter { kMiddleSourceSet.containsMatchIn(it.invariantSeparatorsPath) }
				val vOwnProgram = vWithLibrary
				if (vNative.isEmpty() && vShared.isEmpty() && vConcurrent.isEmpty()) {
					add("-Xcommon-sources=" + vCommon.joinToString(",") { it.path })
				} else {
					val vFragments = ArrayList<Pair<String, List<File>>>()
					val vRefines = ArrayList<String>()
					vFragments += "common" to vCommon
					var vAbove = "common"
					for ((vName, vFiles) in listOf("nonJvm" to vNonJvm, "nativeWasm" to vNativeWasm)) {
						if (vFiles.isEmpty()) continue
						vFragments += vName to vFiles
						vRefines += "$vName:$vAbove"
						vAbove = vName
					}
					if (vConcurrent.isNotEmpty()) {
						vFragments += "concurrent" to vConcurrent
						vRefines += "concurrent:$vAbove"
						vAbove = "concurrent"
					}
					val vLayer = vLibrary - vCommon.toSet() - vShared.toSet()
					if (vNative.isNotEmpty()) {
						// What Kotlin/Native's platform libraries are to `nativeMain`, ktc2's platform layer and the
						// bindings (platform.posix) are here: on the level of `nativeMain`, which sees them. Below
						// it, what refines it: the module's own part and the program.
						val vSeenByNative = vNative + vLayer + vBindings.map { it.source }.filter { it in vWithLibrary }
						vFragments += "native" to vSeenByNative
						vFragments += "platform" to vOwnProgram.filter { it !in vCommon && it !in vSeenByNative && it !in vConcurrent }
						vRefines += "native:$vAbove"
						vRefines += "platform:native"
					} else {
						vFragments += "platform" to (vLayer + vOwnProgram.filter { it !in vCommon && it !in vConcurrent })
						vRefines += "platform:$vAbove"
					}
					add("-Xfragments=" + vFragments.joinToString(",") { it.first })
					add("-Xfragment-sources=" + vFragments.flatMap { (vName, vFiles) -> vFiles.map { "$vName:" + it.path } }.joinToString(","))
					add("-Xfragment-refines=" + vRefines.joinToString(","))
				}
				add("-nowarn")
			}
			// Library sources are declared in the real kotlin.* packages and stand in for the jar's declarations.
			add("-Xallow-kotlin-package")
			// The language as of Kotlin 2.5, with what it still calls experimental: `companion { }`
			// blocks and companion extensions, collection literals (`[1, 2, 3]`), full value classes.
			// The standard library's own sources use the first two.
			addAll(kLanguageOptions)
			// Where the Kotlin compiler writes what a JVM compilation leaves (its module file), which ktc2 never reads: not the
			// program's output.
			add("-d"); add(File(kLibraryDir, "jvm-unused").path)
			add("-no-stdlib"); add("-no-reflect")
			// The JDK's API as of Java 17, before SequencedCollection: a program has no JDK, and on Kotlin/Native
			// `list.removeFirst()`, `getFirst()`, `reversed()` are the library's extensions, not the members Java 21
			// gives every List (`reversed()` there is a view, not a copy). The members also had no override in
			// ktc2's ArrayList when the library was compiled apart from the program.
			add("-Xjdk-release=17")
			add("-cp"); add(listOf(filteredStdlib(vLibrary).path, if (vKeepsLibrary) keptCopy(codeSourceOf(ktc2.Struct::class.java)) else codeSourceOf(ktc2.Struct::class.java)).joinToString(File.pathSeparator))
			add("-Xplugin=${pluginClasspath(usesSerialization(inGivenSources))}")
			// KTC2_TIMING: also the compiler's own account of its phases.
			if (com.bitsycore.ktc2.Settings.flag("debug.timing") && !vKeepsLibrary) add("-Xdump-perf=" + File(inOutDir, "perf.log").path)
		}
		val vStart = System.nanoTime()
		System.setProperty(kStartProperty, vStart.toString())
		val vExit = when {
			vKeepsLibrary -> {
				// The library is the same while its files (the modules' with it) and the options are.
				val vKey = vArgs.joinToString(";") + (vLibrary + vWithLibrary).joinToString("") { ";${it.length()} ${it.lastModified()}" }
				PhasedCompiler.compileOnLibrary(vArgs, vKey, vOnTop)
			}
			else -> K2JVMCompiler().exec(System.err, *vArgs.toTypedArray())
		}
		reportTime("total", vStart)
		return vExit == ExitCode.OK && System.getProperty(kErrorCountProperty) == "0"
	}

	/**
	 * Whether this process keeps the front end's work on the library from one compilation to the
	 * next (`PhasedCompiler.compileOnLibrary`): when it is one that compiles again and again - the
	 * process `ktc2` keeps running, or the test suite, which says so with `ktc2.keepLibrary` - and
	 * `KTC2_KEEP_LIBRARY=0` does not forbid it. A process that compiles once has nothing to keep.
	 */
	private fun keepsLibrary(): Boolean =
		com.bitsycore.ktc2.Settings.choice("daemon.keepLibrary") ?: (com.bitsycore.ktc2.gIsDaemon || System.getProperty("ktc2.keepLibrary") == "true")

	/** Where the library sources of this process are, when its analysis is kept between compilations. */
	private val kLibraryDir: File by lazy {
		File(System.getProperty("java.io.tmpdir"), "ktc2/library-${ProcessHandle.current().pid()}").also { vDirectory ->
			Runtime.getRuntime().addShutdownHook(Thread { vDirectory.deleteRecursively() })
		}
	}

	/**
	 * [inPath] itself when it is a directory, or a copy of that jar in [kLibraryDir]: the compiler
	 * keeps the jars of a compilation mapped while its analysis is kept, and a mapped file cannot be
	 * replaced on Windows - ktc2's own jar could not be installed again while a process holds it.
	 */
	private fun keptCopy(inPath: String): String {
		val vFile = File(inPath)
		if (!vFile.isFile) return inPath
		val vCopy = File(kLibraryDir, "classpath/${vFile.name}")
		if (!vCopy.isFile || vCopy.length() != vFile.length() || vCopy.lastModified() < vFile.lastModified()) {
			vCopy.parentFile.mkdirs()
			vFile.copyTo(vCopy, overwrite = true)
		}
		return vCopy.path
	}

	/**
	 * How long the last compilation spent in the Kotlin front end (Kotlin to IR) and in ktc2's translation of the IR to
	 * C, in nanoseconds: what the suite shows of each program's steps.
	 */
	@Volatile
	var lastFrontEndNanos: Long = 0

	@Volatile
	var lastTranslationNanos: Long = 0

	/** With `KTC2_TIMING` set, prints how long the step [inWhat] took since [inSince] (a `System.nanoTime()`). */
	internal fun reportTime(inWhat: String, inSince: Long) {
		if (com.bitsycore.ktc2.Settings.flag("debug.timing")) System.err.println("ktc2: time: $inWhat ${(System.nanoTime() - inSince) / 1_000_000} ms")
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
	 * `ktc2/modules/stdlib/hidden.txt`. The copy is cached in the temporary directory.
	 */
	private fun filteredStdlib(inLibrary: List<File>): File {
		val vOriginal = File(codeSourceOf(Unit::class.java))
		if (inLibrary.isEmpty() || !vOriginal.isFile) return vOriginal
		val vNames = inLibrary.filter { "commonMain" in it.invariantSeparatorsPath }.map { it.nameWithoutExtension.replaceFirstChar { vChar -> vChar.uppercase() } }.toSet()
		val vHidden = (K2Frontend::class.java.getResourceAsStream("/ktc2/modules/stdlib/hidden.txt")?.use { it.readBytes().decodeToString() } ?: "")
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
	fun compileJvm(inSources: List<File>, inOutDir: File, inOptions: List<String> = emptyList()): Boolean {
		val vArgs = inSources.map { it.path } + listOf(
			// (A test is compiled with ktc2's own `kotlin.test`, a source in a package of the library.)
			"-d", inOutDir.path, "-no-stdlib", "-no-reflect", "-nowarn", "-Xallow-kotlin-package", "-cp", userClasspath(),
			// The inline functions of the `ktc2.` package are built for the JVM this runs on.
			"-jvm-target", System.getProperty("java.specification.version"),
		) + kLanguageOptions + inOptions +
			(if (usesSerialization(inSources)) listOf("-Xplugin=${serializationPlugin()}") else emptyList()) +
			// Sources of a multiplatform module (the tests of the Kotlin library) are all its common part: an optional
			// `expect` (`@ThreadLocal`) is allowed there without its `actual`.
			(if ("-Xmulti-platform" in inOptions && inOptions.none { it.startsWith("-Xcommon-sources=") }) listOf("-Xcommon-sources=" + inSources.joinToString(",") { it.path }) else emptyList())
		return K2JVMCompiler().exec(System.err, *vArgs.toTypedArray()) == ExitCode.OK
	}

	/**
	 * The library sources compiled with every program, the module `stdlib` (modules/stdlib):
	 * - ktc2's platform layer, the bare names of its `files.txt`: files of the module itself, copied with it into
	 *   `<inOutDir>/modules/stdlib` ([bundledModule]);
	 * - files of the Kotlin standard library, the paths of its `files.txt`, from its sources at the version of
	 *   the compiler, which ktc2 fetches into its cache ([upstreamOf]).
	 *
	 * @throws IllegalStateException when the standard library's sources cannot be had
	 */
	private fun librarySources(inOutDir: File): List<File> {
		val vModule = com.bitsycore.ktc2.bundledModule("stdlib", inOutDir) ?: throw IllegalStateException("ktc2 carries no module 'stdlib'")
		val (vOwn, vListed) = vModule.listed.filter { it.isHere }.partition { '/' !in it.path }
		val vResult = ArrayList<File>()
		vResult += vOwn.map { File(vModule.directory, it.path) }.filter { it.isFile }
		val vUpstream = com.bitsycore.ktc2.upstreamOf(vModule) ?: throw IllegalStateException("the sources of the Kotlin standard library could not be had")
		vResult += com.bitsycore.ktc2.listedFiles(vModule, vUpstream, vListed) ?: throw IllegalStateException("the standard library's files.txt names what its sources do not have")
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
	/** True when a program's sources use kotlinx.serialization: its compiler plugin then runs with ktc2's. */
	fun usesSerialization(inSources: List<File>): Boolean =
		inSources.any { it.extension == "kt" && it.isFile && "kotlinx.serialization" in it.readText() }

	/** The jar of kotlinx.serialization's compiler plugin, which ktc2 carries. */
	fun serializationPlugin(): String = codeSourceOf(Class.forName("org.jetbrains.kotlinx.serialization.compiler.extensions.SerializationComponentRegistrar"))

	/**
	 * The plugins of a compilation: ktc2's, after kotlinx.serialization's when [inSerialization], so that the serializers
	 * the latter writes are in the IR ktc2's receives.
	 */
	private fun pluginClasspath(inSerialization: Boolean): String {
		val vRoots = linkedSetOf<String>()
		if (inSerialization) vRoots += serializationPlugin()
		vRoots += codeSourceOf(Ktc2Registrar::class.java)
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
		org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter.registerExtension(Ktc2FirRegistrar())
	}
}

/** What ktc2 changes in the front end: see [Ktc2InlineChecks]. */
class Ktc2FirRegistrar : org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar() {
	override fun ExtensionRegistrarContext.configurePlugin() {
		+::Ktc2InlineChecks
	}
}

/**
 * Puts the inline checks of no platform in the place of the JVM's, which ktc2's front end is: a local `inline fun`
 * is refused only by the JVM (kotlinx-datetime has some, which Kotlin/Native compiles). The JVM side of the suite still
 * compiles with the JVM's checks.
 */
@OptIn(org.jetbrains.kotlin.fir.SessionConfiguration::class)
class Ktc2InlineChecks(inSession: org.jetbrains.kotlin.fir.FirSession) : org.jetbrains.kotlin.fir.extensions.FirExtensionSessionComponent(inSession) {
	init {
		inSession.register(
			org.jetbrains.kotlin.fir.analysis.checkers.FirInlineCheckerPlatformSpecificComponent::class,
			// As a plain component: the composable `register` would add it to the JVM's, whose check would still run.
			object : org.jetbrains.kotlin.fir.analysis.checkers.FirInlineCheckerPlatformSpecificComponent() {} as org.jetbrains.kotlin.fir.FirSessionComponent,
		)
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
		K2Frontend.lastFrontEndNanos = vStart - System.getProperty(kStartProperty).toLong()
		com.bitsycore.ktc2.Progress.end("${moduleFragment.files.size} files")
		com.bitsycore.ktc2.Progress.begin("IR → C")
		try {
			translate(moduleFragment, pluginContext)
			val vWritten = File(System.getProperty(kOutDirProperty), "c").walkTopDown().count { it.isFile && it.extension == "c" && it.name != "ktc2_all.c" }
			com.bitsycore.ktc2.Progress.end("$vWritten C files")
		} finally {
			K2Frontend.lastTranslationNanos = System.nanoTime() - vStart
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

		writeAssociatedObjects(moduleFragment, pluginContext)
		val vLibraryFiles = System.getProperty(kLibraryFilesProperty, "").lines().toSet()
		// `ktc2 test`: the suites of the program's tests, for Kotlin/Native's runner.
		val vTestErrors = ArrayList<String>()
		writeTestRegistry(moduleFragment, pluginContext, vLibraryFiles, vOutDir, vTestErrors)
		if (vTestErrors.isNotEmpty()) {
			vTestErrors.forEach { System.err.println("ktc2: error: $it") }
			System.setProperty(kErrorCountProperty, vTestErrors.size.toString())
			return
		}
		val vImporter = KirImporter(pluginContext.irBuiltIns)
		vImporter.localNames = LocalClassNames().of(moduleFragment)
		val vJavaUses = JavaUseCheck(vLibraryFiles).also { it.check(moduleFragment.files) }
		if (vJavaUses.errors.isNotEmpty()) {
			// Nothing else is said about such a program: every use would be reported again as unsupported.
			vJavaUses.errors.forEach { System.err.println("ktc2: error: $it") }
			System.setProperty(kErrorCountProperty, vJavaUses.errors.size.toString())
			return
		}
		// The cycles a program makes through coroutines and lambdas that it can avoid with the API as it is: warnings.
		if (System.getProperty("ktc2.warnCycles") != "false") {
			val vUserCycles = UserCycleLint(vLibraryFiles, vOutDir).also { it.check(moduleFragment.files) }.warnings
			vUserCycles.forEach { System.err.println("ktc2: warning: $it") }
			// Kept next to the output too, where a run of the suite, which does not show them, leaves them to read.
			File(vOutDir, "user-cycles.txt").let { if (vUserCycles.isEmpty()) it.delete() else it.writeText(vUserCycles.joinToString("\n", postfix = "\n")) }
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
		val vDumpFilter = com.bitsycore.ktc2.Settings.text("debug.dumpKir").takeIf { it.isNotEmpty() }
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
		// Run always: it also finds the cycles of two objects that the runtime frees (KClass.pairCycles).
		val vCycles = CycleLint(vModule).also { it.run() }
		if (System.getProperty("ktc2.warnCycles") != "false") vCycles.warnings.forEach { System.err.println("ktc2: warning: $it") }
		// KTC2_SKIP names optimizations to leave out (devirtualize, ranges, handover, brief), to find which one a wrong program comes from.
		val vSkipped = com.bitsycore.ktc2.Settings.list("debug.skip").toSet()
		val vDirect = Devirtualize(vModule).also { if ("devirtualize" !in vSkipped) it.run() }
		val vLists = ListLoops(vModule).also { if ("listloops" !in vSkipped) it.run() }
		val vRanges = RangeChecks(vModule).also { if ("ranges" !in vSkipped) it.run() }
		if (com.bitsycore.ktc2.Settings.flag("debug.passes")) {
			System.err.println("ktc2: passes: ${vDirect.copied} functions copied for the classes they are called with, ${vDirect.resolved} dispatched calls made direct, ${vDirect.answered} type tests answered, ${vLists.rewritten} loops over a list or a map written without an iterator, ${vLists.accesses} list accesses written out, ${vRanges.proven} array accesses proven in range")
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
		dumpKir("ArcInsert: retains and releases")
		val vLadders = ReleaseLadders(vModule)
		if ("ladders" !in vSkipped) vLadders.run()
		if (com.bitsycore.ktc2.Settings.flag("debug.passes")) System.err.println("ktc2: passes: ${vLadders.exits} ways out sharing their releases, ${vLadders.saved} releases fewer")
		dumpKir("ReleaseLadders: the ways out share their releases, as the C is written from")
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
