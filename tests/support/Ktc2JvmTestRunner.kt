package ktc2.suite

import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.system.exitProcess

/*
The suite's JVM side of `ktc2 test`: runs the tests the C program lists (`--ktest_list_tests`, in a file given as the
argument), in its order, and prints what Kotlin/Native's runner prints with its GTest logger, the times aside, so that
the two outputs compare test by test. A class of tests gets a new object for each test, between its @BeforeTest and
@AfterTest functions, as on Native.
*/

/** A suite of the list: its name and its tests. */
private class ListedSuite(val name: String) {
	val tests = ArrayList<String>()
}

/** The suites of the list [inLines]: a suite is `Name.`, each of its tests `  test` under it. */
private fun suitesOf(inLines: List<String>): List<ListedSuite> {
	val vSuites = ArrayList<ListedSuite>()
	for (vLine in inLines) {
		if (vLine.isBlank()) continue
		if (vLine.startsWith("  ")) vSuites.last().tests += vLine.substring(2) else vSuites += ListedSuite(vLine.removeSuffix("."))
	}
	return vSuites
}

/** The JVM class of [inName] (`pkg.Outer.Inner` is `pkg.Outer$Inner`). */
private fun classOf(inName: String): Class<*>? {
	var vName = inName
	while (true) {
		try {
			return Class.forName(vName)
		} catch (vMissing: ClassNotFoundException) {
			val vDot = vName.lastIndexOf('.')
			if (vDot < 0) return null
			vName = vName.substring(0, vDot) + "$" + vName.substring(vDot + 1)
		}
	}
}

private fun Method.isMarked(inAnnotation: Class<out Annotation>): Boolean = isAnnotationPresent(inAnnotation)

/** The methods of [inClass] and of its superclasses, the nearest first, one for each name. */
private fun methodsOf(inClass: Class<*>): List<Method> {
	val vSeen = HashSet<String>()
	return generateSequence(inClass) { it.superclass }.takeWhile { it != Any::class.java }.flatMap { it.declaredMethods.asSequence() }
		.filter { it.parameterCount == 0 && vSeen.add(it.name) }.onEach { it.isAccessible = true }.toList()
}

/** [inError] as the test failed with it: the exception the method threw. */
private fun cause(inError: Throwable): Throwable = if (inError is InvocationTargetException) inError.targetException ?: inError else inError

fun main(args: Array<String>) {
	val vSuites = suitesOf(File(args[0]).readLines())
	var vTotal = 0
	var vPassed = 0
	val vFailed = ArrayList<String>()
	val vIgnored = ArrayList<String>()
	val vRunnable = vSuites.sumOf { it.tests.size }
	println("[==========] Running $vRunnable tests from ${vSuites.size} test cases.")
	println("[----------] Global test environment set-up.")
	for (vSuite in vSuites) {
		val vClass = classOf(vSuite.name) ?: throw IllegalStateException("no class ${vSuite.name}")
		val vMethods = methodsOf(vClass)
		val vSuiteIgnored = vClass.isAnnotationPresent(kotlin.test.Ignore::class.java)
		val vBefore = vMethods.filter { it.isMarked(kotlin.test.BeforeTest::class.java) }.reversed()
		val vAfter = vMethods.filter { it.isMarked(kotlin.test.AfterTest::class.java) }
		val vIsTopLevel = vMethods.filter { it.isMarked(kotlin.test.Test::class.java) }.all { Modifier.isStatic(it.modifiers) }
		fun newInstance(): Any? {
			if (vIsTopLevel) return null
			vClass.declaredFields.firstOrNull { it.name == "INSTANCE" && Modifier.isStatic(it.modifiers) }?.let { return it.get(null) }
			val vConstructor = vClass.getDeclaredConstructor()
			vConstructor.isAccessible = true
			return vConstructor.newInstance()
		}
		println("[----------] ${vSuite.tests.size} tests from ${vSuite.name}")
		for (vName in vSuite.tests) {
			val vMethod = vMethods.firstOrNull { it.name == vName } ?: vMethods.firstOrNull { it.name.startsWith("$vName$") }
				?: throw IllegalStateException("no test ${vSuite.name}.$vName")
			if (vSuiteIgnored || vMethod.isMarked(kotlin.test.Ignore::class.java)) {
				vIgnored += "${vSuite.name}.$vName"
				vTotal++
				continue
			}
			println("[ RUN      ] ${vSuite.name}.$vName")
			vTotal++
			try {
				val vInstance = newInstance()
				try {
					vBefore.forEach { it.invoke(vInstance) }
					vMethod.invoke(vInstance)
				} finally {
					vAfter.forEach { it.invoke(vInstance) }
				}
				vPassed++
				println("[       OK ] ${vSuite.name}.$vName (0 ms)")
			} catch (vError: Throwable) {
				cause(vError).printStackTrace()
				vFailed += "${vSuite.name}.$vName"
				println("[  FAILED  ] ${vSuite.name}.$vName (0 ms)")
			}
		}
		println("[----------] ${vSuite.tests.size} tests from ${vSuite.name} (0 ms total)\n")
	}
	println("[----------] Global test environment tear-down")
	println("[==========] $vTotal tests from ${vSuites.size} test cases ran. (0 ms total)")
	println("[  PASSED  ] $vPassed tests.")
	if (vIgnored.isNotEmpty()) {
		println("[  SKIPPED ] ${if (vIgnored.size == 1) "1 test" else "${vIgnored.size} tests"}, listed below:")
		vIgnored.forEach { println("[  SKIPPED ] $it") }
	}
	if (vFailed.isNotEmpty()) {
		val vForm = if (vFailed.size == 1) "test" else "tests"
		println("[  FAILED  ] ${vFailed.size} $vForm, listed below:")
		vFailed.forEach { println("[  FAILED  ] $it") }
		println("\n${vFailed.size} FAILED ${vForm.uppercase()}")
		System.out.flush()
		exitProcess(1)
	}
}
