@file:OptIn(ExperimentalCompilerApi::class)

package com.bitsycore.ktc2.frontend

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import com.bitsycore.ktc2.backend.CEmitter
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.util.dump
import org.jetbrains.kotlin.ir.util.dumpKotlinLike
import java.io.File

// The compiler loads the registrar in its own classloader, so options travel as system properties.
private const val kOutDirProperty = "ktc2.outDir"
private const val kDumpIrProperty = "ktc2.dumpIr"
private const val kErrorCountProperty = "ktc2.errorCount"

/** Name of the file, in the output directory, that lists what a compilation produced. */
const val kManifestName = "ktc2_build.txt"

// ==================
// MARK: Driver
// ==================

/**
 * Runs the Kotlin K2 compiler in-process and hands the resolved module IR to the ktc2 back end.
 * The same run also writes the JVM classes used as the reference ("oracle") build.
 */
object K2Frontend {

	/**
	 * Compiles [inSources] into [inOutDir].
	 *
	 * @param inDumpIr also write the IR received from K2 (`ir.txt`, `ir.kt.txt`)
	 * @return true when neither K2 nor the ktc2 back end reported an error
	 */
	fun compile(inSources: List<File>, inOutDir: File, inDumpIr: Boolean): Boolean {
		inOutDir.mkdirs()
		System.setProperty(kOutDirProperty, inOutDir.absolutePath)
		System.setProperty(kDumpIrProperty, inDumpIr.toString())
		System.setProperty(kErrorCountProperty, "0")
		val vArgs = buildList {
			inSources.forEach { add(it.path) }
			// Lets the ktc2 std library declare its sources in the real kotlin.* packages.
			add("-Xallow-kotlin-package")
			add("-d"); add(File(inOutDir, "jvm").path)
			add("-no-stdlib"); add("-no-reflect")
			add("-cp"); add(userClasspath())
			add("-Xplugin=${pluginClasspath()}")
		}
		val vExit = K2JVMCompiler().exec(System.err, *vArgs.toTypedArray())
		return vExit == ExitCode.OK && System.getProperty(kErrorCountProperty) == "0"
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
		val vOutDir = File(System.getProperty(kOutDirProperty))
		if (System.getProperty(kDumpIrProperty) == "true") {
			File(vOutDir, "ir.txt").writeText(moduleFragment.dump())
			File(vOutDir, "ir.kt.txt").writeText(moduleFragment.dumpKotlinLike())
		}

		val vImporter = KirImporter()
		val vModule = vImporter.import(moduleFragment)
		if (vModule.entry == null) vImporter.errors += "no 'fun main()' found"
		vImporter.errors.forEach { System.err.println("ktc2: error: $it") }
		System.setProperty(kErrorCountProperty, vImporter.errors.size.toString())
		if (vImporter.errors.isNotEmpty()) return

		val vSources = CEmitter(vModule).emit(File(vOutDir, "c"))
		val vMain = moduleFragment.files.first { vFile -> vFile.declarations.any { it is IrSimpleFunction && it.name.asString() == "main" } }
		val vFacade = File(vMain.fileEntry.name).nameWithoutExtension.replaceFirstChar { it.uppercase() } + "Kt"
		val vPackage = vMain.packageFqName.asString()
		File(vOutDir, kManifestName).writeText(buildString {
			appendLine("jvmMain=" + if (vPackage.isEmpty()) vFacade else "$vPackage.$vFacade")
			vSources.forEach { appendLine("c=$it") }
		})
	}
}
