@file:OptIn(UnsafeDuringIrConstructionAPI::class)

package com.bitsycore.ktc2.frontend

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irBoolean
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irEquals
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irIfThen
import org.jetbrains.kotlin.ir.builders.irImplicitCast
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irReturnUnit
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.isObject
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.resolveFakeOverride
import java.io.File

/*
The test runner of `ktc2 test`, as Kotlin/Native's compiler makes it with `-generate-test-runner`: every class of the
program with `@Test` functions is a suite of Native's runner (`kotlin.native.internal.test`, of the module kotlin-test),
and so are the top-level `@Test` functions of a file. Native generates a class for each suite; here the module declares
the suites once (`Ktc2TestRegistry.kt`) and three functions whose bodies are written below, in IR, before the importer
reads the module: `ktc2RegisterTests` (each suite and each of its functions, by numbers), `ktc2NewInstance` (an object
of a class of tests) and `ktc2Call` (a function of a suite). A test that is `@Ignore`d is registered as one and never
called, so that its body is not translated; one the command leaves out (`ktc2.testExclude`) is not registered.
*/

/** The system property that asks for the test runner: set by `ktc2 test` and by the suite. */
const val kTestModeProperty = "ktc2.test"

/**
 * The tests left out, one a line: `name`, `Class.name` or the suite's whole name and the test's; any of them after
 * `path::`, for the file of tests whose path ends so only, and `path::*` for every test of that file (a file compiled
 * for what the others use of it).
 */
const val kTestExcludeProperty = "ktc2.testExclude"

/** Where a program compiled as tests starts. */
const val kTestEntryPoint = "kotlin.native.internal.test.ktc2TestMain"

/** The file, next to the C, that says which file each suite comes from: `suite<TAB>path`, a line each. */
const val kTestSuitesFile = "tests.txt"

private const val kRunnerPackage = "kotlin.native.internal.test"
private const val kTest = "kotlin.test.Test"
private const val kIgnore = "kotlin.test.Ignore"
private const val kBeforeTest = "kotlin.test.BeforeTest"
private const val kAfterTest = "kotlin.test.AfterTest"

/** A function of a suite: a test, or a function to run before or after each. */
private class SuiteFunction(val index: Int, val function: IrSimpleFunction)

/** A suite: a class of tests ([owner]), or the top-level tests of a file when [owner] is null. */
private class Suite(val index: Int, val name: String, val file: IrFile, val owner: IrClass?, val isIgnored: Boolean) {
	val tests = ArrayList<Pair<String, SuiteFunction?>>()
	val before = ArrayList<SuiteFunction>()
	val after = ArrayList<SuiteFunction>()
	val called = ArrayList<SuiteFunction>()
	private var mNext = 0

	/** A number for [inFunction] in this suite, and the function to call by it. */
	fun add(inFunction: IrSimpleFunction): SuiteFunction = SuiteFunction(mNext++, inFunction).also { called += it }
}

private fun IrDeclaration.isMarked(inAnnotation: String): Boolean =
	annotations.any { it.type.classOrNull?.owner?.kotlinFqName?.asString() == inAnnotation }

/** True when [inFunction], or the function it stands for in its class when it is inherited as it is, is marked [inAnnotation]. */
private fun isMarkedFunction(inFunction: IrSimpleFunction, inAnnotation: String): Boolean =
	(if (inFunction.isFakeOverride) inFunction.resolveFakeOverride() ?: inFunction else inFunction).isMarked(inAnnotation)

/** The name Kotlin gives the class of a file's top-level declarations: `pkg.NameKt`. */
private fun facadeName(inFile: IrFile): String {
	// As Kotlin names it for the JVM: an identifier (`32defaults.kt` is `_32defaultsKt`).
	val vBase = File(inFile.fileEntry.name).nameWithoutExtension.replaceFirstChar { it.uppercase() }
		.map { if (Character.isJavaIdentifierPart(it)) it else '_' }.joinToString("")
	val vName = (if (vBase.isEmpty() || !Character.isJavaIdentifierStart(vBase[0])) "_$vBase" else vBase) + "Kt"
	val vPackage = inFile.packageFqName.asString()
	return if (vPackage.isEmpty()) vName else "$vPackage.$vName"
}

/**
 * Writes the bodies of the runner's functions in [inModule] for the tests of the program's own files (those not in
 * [inLibraryFiles]), and the list of suites into [inOutDir]. Does nothing when the program is not compiled as tests.
 */
internal fun writeTestRegistry(inModule: IrModuleFragment, inContext: IrPluginContext, inLibraryFiles: Set<String>, inOutDir: File, outErrors: MutableList<String>) {
	if (System.getProperty(kTestModeProperty) != "true") return
	val vRunner = inModule.files.filter { it.packageFqName.asString() == kRunnerPackage }.flatMap { it.declarations }.filterIsInstance<IrSimpleFunction>()
	fun runner(inName: String): IrSimpleFunction? = vRunner.firstOrNull { it.name.asString() == inName }
	val vRegister = runner("ktc2RegisterTests")
	val vNewInstance = runner("ktc2NewInstance")
	val vCall = runner("ktc2Call")
	val vSuiteFunction = runner("ktc2Suite")
	val vTestFunction = runner("ktc2Test")
	val vBeforeAfter = runner("ktc2Function")
	if (vRegister == null || vNewInstance == null || vCall == null || vSuiteFunction == null || vTestFunction == null || vBeforeAfter == null) {
		outErrors += "the tests need the module kotlin-test (its runner was not found)"
		return
	}
	val vExcluded = System.getProperty(kTestExcludeProperty, "").lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
	fun matches(inEntry: String, inSuite: String, inTest: String): Boolean =
		inEntry == "*" || inEntry == inTest || inEntry == "$inSuite.$inTest" || inEntry == "${inSuite.substringAfterLast('.')}.$inTest" ||
			(inEntry.endsWith(".$inTest") && inSuite.endsWith("." + inEntry.removeSuffix(".$inTest")))
	fun isExcluded(inFile: IrFile, inSuite: String, inTest: String): Boolean {
		val vPath by lazy { File(inFile.fileEntry.name).absoluteFile.normalize().invariantSeparatorsPath }
		return vExcluded.any { vEntry ->
			val vScope = vEntry.substringBefore("::", "")
			(vScope.isEmpty() || vPath.endsWith(vScope.replace('\\', '/'))) && matches(vEntry.substringAfter("::"), inSuite, inTest)
		}
	}

	// The suites, in the order of the files and of their declarations.
	val vSuites = ArrayList<Suite>()
	for (vFile in inModule.files) {
		if (File(vFile.fileEntry.name).absoluteFile.normalize().invariantSeparatorsPath in inLibraryFiles) continue
		if (vFile.packageFqName.asString() == kRunnerPackage) continue
		val vTopLevel = vFile.declarations.filterIsInstance<IrSimpleFunction>()
		if (vTopLevel.any { it.isMarked(kTest) }) {
			val vSuite = Suite(vSuites.size, facadeName(vFile), vFile, null, false)
			fill(vSuite, vTopLevel, ::isExcluded, outErrors)
			if (vSuite.tests.isNotEmpty()) vSuites += vSuite
		}
		fun visit(inClass: IrClass, inIgnored: Boolean) {
			val vIgnored = inIgnored || inClass.isMarked(kIgnore)
			val vFunctions = inClass.declarations.filterIsInstance<IrSimpleFunction>()
			val vIsTestClass = !inClass.isInterface && inClass.modality != org.jetbrains.kotlin.descriptors.Modality.ABSTRACT &&
				!inClass.isCompanion && inClass.typeParameters.isEmpty() && !inClass.isInner && vFunctions.any { isMarkedFunction(it, kTest) }
			if (vIsTestClass) {
				val vConstructor = inClass.declarations.filterIsInstance<IrConstructor>().firstOrNull { vConstructor -> vConstructor.parameters.none { it.kind == IrParameterKind.Regular && it.defaultValue == null } }
				if (!inClass.isObject && vConstructor == null) {
					outErrors += "${File(vFile.fileEntry.name).name}: the class of tests ${inClass.kotlinFqName} has no constructor without arguments"
				} else {
					val vSuite = Suite(vSuites.size, inClass.kotlinFqName.asString(), vFile, inClass, vIgnored)
					fill(vSuite, vFunctions, ::isExcluded, outErrors)
					// A suite whose tests are all left out is not one.
					if (vSuite.tests.isNotEmpty()) vSuites += vSuite
				}
			}
			inClass.declarations.filterIsInstance<IrClass>().forEach { visit(it, vIgnored) }
		}
		vFile.declarations.filterIsInstance<IrClass>().forEach { visit(it, false) }
	}

	val vBuilder = DeclarationIrBuilder(inContext, vRegister.symbol)
	vRegister.body = vBuilder.irBlockBody {
		for (vSuite in vSuites) {
			+irCall(vSuiteFunction).also {
				it.arguments[0] = irInt(vSuite.index)
				it.arguments[1] = irString(vSuite.name)
				it.arguments[2] = irBoolean(vSuite.isIgnored)
				it.arguments[3] = irBoolean(vSuite.owner == null)
			}
			for ((vName, vFunction) in vSuite.tests) {
				+irCall(vTestFunction).also {
					it.arguments[0] = irInt(vSuite.index)
					it.arguments[1] = irInt(vFunction?.index ?: -1)
					it.arguments[2] = irString(vName)
					it.arguments[3] = irBoolean(vSuite.isIgnored || vFunction == null)
				}
			}
			for ((vList, vIsBefore) in listOf(vSuite.before to true, vSuite.after to false)) {
				for (vFunction in vList) {
					+irCall(vBeforeAfter).also {
						it.arguments[0] = irInt(vSuite.index)
						it.arguments[1] = irInt(vFunction.index)
						it.arguments[2] = irBoolean(vIsBefore)
					}
				}
			}
		}
	}

	// ktc2NewInstance(suite): an object of each class of tests that is run.
	val vInstanceSuite = vNewInstance.parameters.first { it.kind == IrParameterKind.Regular }
	val vInstanceBody = vNewInstance.body as IrBlockBody
	val vMade = ArrayList<IrStatement>()
	DeclarationIrBuilder(inContext, vNewInstance.symbol).run {
		for (vSuite in vSuites) {
			val vClass = vSuite.owner ?: continue
			if (vSuite.isIgnored || vSuite.called.isEmpty()) continue
			val vObject = if (vClass.isObject) irGetObject(vClass.symbol) else irCall(vClass.declarations.filterIsInstance<IrConstructor>().first { vConstructor -> vConstructor.parameters.none { it.kind == IrParameterKind.Regular && it.defaultValue == null } })
			vMade += irIfThen(context.irBuiltIns.unitType, irEquals(irGet(vInstanceSuite), irInt(vSuite.index)), irReturn(vObject))
		}
	}
	vInstanceBody.statements.addAll(0, vMade)

	// ktc2Call(suite, function, instance): each function of each suite, on the object for a class.
	val vParams = vCall.parameters.filter { it.kind == IrParameterKind.Regular }
	val vCallBody = vCall.body as IrBlockBody
	val vCalls = ArrayList<IrStatement>()
	DeclarationIrBuilder(inContext, vCall.symbol).run {
		for (vSuite in vSuites) {
			if (vSuite.isIgnored || vSuite.called.isEmpty()) continue
			val vBranches = irBlock {
				for (vEntry in vSuite.called) {
					val vInvoke = irCall(vEntry.function).also { vInvocation ->
						val vClass = vSuite.owner
						if (vClass != null) vInvocation.arguments[0] = irImplicitCast(irGet(vParams[2]), vClass.defaultType)
					}
					+irIfThen(context.irBuiltIns.unitType, irEquals(irGet(vParams[1]), irInt(vEntry.index)), irBlock {
						+vInvoke
						+irReturnUnit()
					})
				}
			}
			vCalls += irIfThen(context.irBuiltIns.unitType, irEquals(irGet(vParams[0]), irInt(vSuite.index)), vBranches)
		}
	}
	vCallBody.statements.addAll(0, vCalls)

	inOutDir.mkdirs()
	File(inOutDir, kTestSuitesFile).writeText(vSuites.joinToString("") { "${it.name}\t${File(it.file.fileEntry.name).absoluteFile.normalize().invariantSeparatorsPath}\n" })
}

/** The tests of [inSuite] among [inFunctions], and its functions to run before and after each. */
private fun fill(inSuite: Suite, inFunctions: List<IrSimpleFunction>, inExcluded: (IrFile, String, String) -> Boolean, outErrors: MutableList<String>) {
	for (vFunction in inFunctions) {
		val vName = vFunction.name.asString()
		when {
			isMarkedFunction(vFunction, kTest) -> {
				if (inExcluded(inSuite.file, inSuite.name, vName)) continue
				if (vFunction.isSuspend) {
					outErrors += "${File(inSuite.file.fileEntry.name).name}: the test ${inSuite.name}.$vName is a suspend function, which a test cannot be"
					continue
				}
				val vIgnored = inSuite.isIgnored || isMarkedFunction(vFunction, kIgnore)
				inSuite.tests += vName to (if (vIgnored) null else inSuite.add(vFunction))
			}
			isMarkedFunction(vFunction, kBeforeTest) -> inSuite.before += inSuite.add(vFunction)
			isMarkedFunction(vFunction, kAfterTest) -> inSuite.after += inSuite.add(vFunction)
		}
	}
}
