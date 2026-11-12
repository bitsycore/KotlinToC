package com.bitsycore.ktc2.frontend

import org.jetbrains.kotlin.KtIoFileSourceFile
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.arguments.parseCommandLineArguments
import org.jetbrains.kotlin.cli.common.diagnosticsCollector
import org.jetbrains.kotlin.cli.common.messages.GroupingMessageCollector
import org.jetbrains.kotlin.cli.common.messages.MessageRenderer
import org.jetbrains.kotlin.cli.common.messages.PrintingMessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.cli.jvm.compiler.prepareIncrementalCompilationContextAndLibrariesClasspath
import org.jetbrains.kotlin.cli.pipeline.ArgumentsPipelineArtifact
import org.jetbrains.kotlin.cli.pipeline.CheckCompilationErrors
import org.jetbrains.kotlin.cli.pipeline.jvm.JvmConfigurationPipelinePhase
import org.jetbrains.kotlin.cli.pipeline.jvm.JvmFir2IrPipelinePhase
import org.jetbrains.kotlin.cli.pipeline.jvm.JvmFrontendPipelineArtifact
import org.jetbrains.kotlin.cli.pipeline.jvm.JvmFrontendPipelinePhase
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.config.Services
import org.jetbrains.kotlin.cli.jvm.compiler.javaInterop
import org.jetbrains.kotlin.compiler.plugin.getCompilerExtensions
import org.jetbrains.kotlin.config.AnalysisFlag
import org.jetbrains.kotlin.config.AnalysisFlags
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl
import org.jetbrains.kotlin.config.languageVersionSettings
import org.jetbrains.kotlin.diagnostics.impl.DiagnosticsCollectorImpl
import org.jetbrains.kotlin.fir.FirSourceModuleData
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.moduleData
import org.jetbrains.kotlin.fir.pipeline.AllModulesFrontendOutput
import org.jetbrains.kotlin.fir.pipeline.buildResolveAndCheckFirViaLightTree
import org.jetbrains.kotlin.fir.session.FirJvmSessionFactory
import org.jetbrains.kotlin.fir.session.KmpModuleKind
import org.jetbrains.kotlin.jvm.environment.JvmClasspath
import org.jetbrains.kotlin.name.Name
import java.io.File

/**
 * The Kotlin compiler run phase by phase, where `K2JVMCompiler.exec` runs them all: its
 * configuration, its front end, and the conversion to IR, during which [Ktc2Extension] is
 * called and translates. Nothing after that: no JVM class is wanted.
 *
 * Run this way, the front end's work on the library can be kept. Most of a compilation is
 * the front end reading and resolving the library sources, which are the same every time; a
 * program is a few files on top of them. [compileOnLibrary] resolves the library once per
 * process and, for each program, adds one more part to the compilation (in the compiler's
 * words: a source session that depends on the library's, as one source set of a
 * multiplatform module depends on another) and resolves only that. The conversion to IR is
 * done for the whole each time: it is fast, and gives each program an IR of its own.
 *
 * None of this is a public interface of the compiler: it is what its own command line does
 * inside, called step by step, and has to be looked at again with each version of it.
 */
@OptIn(org.jetbrains.kotlin.config.MessageCollectorAccess::class)
internal object PhasedCompiler {

	private fun collector(): GroupingMessageCollector =
		GroupingMessageCollector(PrintingMessageCollector(System.err, MessageRenderer.PLAIN_RELATIVE_PATHS, false), false, false)

	/** The configuration and the front end of the compiler, for the sources and options [inArguments] name. */
	private fun frontend(inArguments: List<String>, inCollector: GroupingMessageCollector, inDisposable: Disposable): JvmFrontendPipelineArtifact? {
		val vArguments = K2JVMCompilerArguments()
		parseCommandLineArguments(inArguments, vArguments)
		val vInput = ArgumentsPipelineArtifact(vArguments, Services.EMPTY, inDisposable, inCollector, K2JVMCompiler().defaultPerformanceManager)
		return JvmFrontendPipelinePhase.executePhase(JvmConfigurationPipelinePhase.executePhase(vInput))
	}

	// ==================
	// MARK: The library, kept
	// ==================

	/**
	 * [inConfiguration] for the conversion to IR of a compilation whose library part is converted
	 * again for the next program. The conversion empties the bodies of what it has converted, to
	 * free memory, unless the compilation is one of a multiplatform module compiled part by part,
	 * where the parts above are needed again as here: it is told that this is one.
	 */
	private fun conversionSettings(inConfiguration: CompilerConfiguration): CompilerConfiguration {
		val vSettings = inConfiguration.languageVersionSettings as LanguageVersionSettingsImpl
		fun <T> field(inName: String): T {
			val vField = LanguageVersionSettingsImpl::class.java.getDeclaredField(inName)
			vField.isAccessible = true
			@Suppress("UNCHECKED_CAST")
			return vField.get(vSettings) as T
		}
		val vFlags = field<Map<AnalysisFlag<*>, Any?>>("analysisFlags") + (AnalysisFlags.hierarchicalMultiplatformCompilation to true)
		val vCopy = inConfiguration.copy()
		vCopy.languageVersionSettings = LanguageVersionSettingsImpl(vSettings.languageVersion, vSettings.apiVersion, vFlags, field<Map<LanguageFeature, LanguageFeature.State>>("specificFeatures"))
		return vCopy
	}

	/** The library as the front end left it: what [key] says it was made from, and what a program is added to. */
	private class Library(val key: String, val disposable: Disposable, val frontend: JvmFrontendPipelineArtifact, val classpath: JvmClasspath)

	private var mLibrary: Library? = null

	/** How many programs were compiled on the library that is kept: it is made again now and then, since each leaves something behind. */
	private var mPrograms = 0

	private const val kProgramsPerLibrary = 200

	/**
	 * Compiles the program [inProgram] on the library that [inLibraryArguments] describe (its
	 * sources and the options of the whole compilation), resolving the library only when it is
	 * not the one this process already holds ([inLibraryKey] tells them apart).
	 */
	@Synchronized
	fun compileOnLibrary(inLibraryArguments: List<String>, inLibraryKey: String, inProgram: List<File>): ExitCode {
		var vLibrary = mLibrary
		var vSince = System.nanoTime()
		fun lap(inWhat: String) {
			K2Frontend.reportTime(inWhat, vSince)
			vSince = System.nanoTime()
		}
		if (vLibrary == null || vLibrary.key != inLibraryKey || mPrograms >= kProgramsPerLibrary) {
			vLibrary?.let { Disposer.dispose(it.disposable) }
			mLibrary = null
			mPrograms = 0
			val vCollector = collector()
			val vDisposable = Disposer.newDisposable("ktc2 library")
			val vFrontend = try {
				frontend(inLibraryArguments, vCollector, vDisposable)?.takeIf { !CheckCompilationErrors.CheckDiagnosticCollector.checkHasErrorsAndReportToMessageCollector(it.configuration) }
			} finally {
				vCollector.flush()
			}
			if (vFrontend == null) {
				Disposer.dispose(vDisposable)
				return ExitCode.COMPILATION_ERROR
			}
			val vClasspath = prepareIncrementalCompilationContextAndLibrariesClasspath(vFrontend.configuration, vFrontend.environment).first
			vLibrary = Library(inLibraryKey, vDisposable, vFrontend, vClasspath)
			mLibrary = vLibrary
			lap("the library, resolved")
		}
		mPrograms++

		val vCollector = collector()
		try {
			// The program's own configuration: its messages and its diagnostics are not the library's.
			val vConfiguration = vLibrary.frontend.configuration.copy()
			vConfiguration.put(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, vCollector)
			vConfiguration.diagnosticsCollector = DiagnosticsCollectorImpl()
			val vOutputs = vLibrary.frontend.frontendOutput.outputs
			val vAbove = vOutputs.last().session.moduleData
			val vModule = FirSourceModuleData(Name.special("<program>"), vAbove.dependencies, listOf(vAbove), vAbove.friendDependencies, vAbove.platform, false)
			val vContext = FirJvmSessionFactory.Context(vConfiguration, vLibrary.frontend.environment, vLibrary.classpath, vLibrary.frontend.environment.javaInterop(vConfiguration))
			val vSession = FirJvmSessionFactory.createSourceSession(
				vModule, { null }, vConfiguration.getCompilerExtensions(FirExtensionRegistrar), vConfiguration, vContext, KmpModuleKind.LeafRegularModule,
			) {}
			val vOutput = buildResolveAndCheckFirViaLightTree(vSession, inProgram.map { KtIoFileSourceFile(it) }, vConfiguration.diagnosticsCollector, null)
			if (CheckCompilationErrors.CheckDiagnosticCollector.checkHasErrorsAndReportToMessageCollector(vConfiguration)) return ExitCode.COMPILATION_ERROR
			lap("the program, resolved")
			val vAll = vLibrary.frontend.copy(
				frontendOutput = AllModulesFrontendOutput(vOutputs + vOutput),
				configuration = conversionSettings(vConfiguration),
				sourceFiles = vLibrary.frontend.sourceFiles + inProgram.map { KtIoFileSourceFile(it) },
			)
			JvmFir2IrPipelinePhase.executePhase(vAll)
			lap("conversion to IR and translation")
			if (CheckCompilationErrors.CheckDiagnosticCollector.checkHasErrorsAndReportToMessageCollector(vAll.configuration)) return ExitCode.COMPILATION_ERROR
			return if (vCollector.hasErrors()) ExitCode.COMPILATION_ERROR else ExitCode.OK
		} finally {
			vCollector.flush()
		}
	}
}
