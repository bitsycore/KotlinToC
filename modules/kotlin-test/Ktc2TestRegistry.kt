@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package kotlin.native.internal.test

/*
ktc2's part of the test runner: what Kotlin/Native's compiler generates for `-generate-test-runner` (a suite for each
class of tests, registered before `main`), written by ktc2's compiler for `ktc2 test` as the bodies of the three
functions below (`TestRegistry.kt` of the compiler). Each class of tests is a number, and each of its tests, `@BeforeTest`
and `@AfterTest` functions another: the runner calls them through [ktc2NewInstance] and [ktc2Call].
*/

/** The suites registered, by their numbers. */
private val mSuites = ArrayList<TestSuite>()

/** A class of tests: its objects made by [ktc2NewInstance], one for each test as on Kotlin/Native. */
private class Ktc2ClassSuite(inName: String, inIgnored: Boolean, private val mIndex: Int) : BaseClassSuite<Any, Unit>(inName, inIgnored) {
	override fun createInstance(): Any = ktc2NewInstance(mIndex)
}

/** Registers the suite [inIndex], named [inName]: a class of tests, or the top-level tests of a file when [inTopLevel]. */
@PublishedApi
internal fun ktc2Suite(inIndex: Int, inName: String, inIgnored: Boolean, inTopLevel: Boolean) {
	mSuites.add(if (inTopLevel) TopLevelSuite(inName) else Ktc2ClassSuite(inName, inIgnored, inIndex))
}

/** Registers the test [inFunction] of the suite [inSuite], named [inName]. An ignored test is never called. */
@PublishedApi
internal fun ktc2Test(inSuite: Int, inFunction: Int, inName: String, inIgnored: Boolean) {
	when (val vSuite = mSuites[inSuite]) {
		is TopLevelSuite -> vSuite.registerTestCase(inName, { ktc2Call(inSuite, inFunction, Unit) }, inIgnored)
		is Ktc2ClassSuite -> vSuite.registerTestCase(inName, { ktc2Call(inSuite, inFunction, this) }, inIgnored)
	}
}

/** Registers the function [inFunction] of the suite [inSuite] to run before each test of it ([inBefore]) or after. */
@PublishedApi
internal fun ktc2Function(inSuite: Int, inFunction: Int, inBefore: Boolean) {
	val vKind = if (inBefore) TestFunctionKind.BEFORE_TEST else TestFunctionKind.AFTER_TEST
	when (val vSuite = mSuites[inSuite]) {
		is TopLevelSuite -> vSuite.registerFunction(vKind) { ktc2Call(inSuite, inFunction, Unit) }
		is Ktc2ClassSuite -> vSuite.registerFunction(vKind, { inInstance: Any -> ktc2Call(inSuite, inFunction, inInstance) })
	}
}

/** Registers every suite of the program, in the order of its files: the body is written by the compiler. */
@PublishedApi
internal fun ktc2RegisterTests() {
}

/** A new object of the class of tests [inSuite]: the body is written by the compiler. */
@PublishedApi
internal fun ktc2NewInstance(inSuite: Int): Any = throw IllegalStateException("no class of tests $inSuite")

/** Calls the function [inFunction] of the suite [inSuite], on [inInstance] for a class: the body is written by the compiler. */
@PublishedApi
internal fun ktc2Call(inSuite: Int, inFunction: Int, inInstance: Any) {
	throw IllegalStateException("no test $inFunction in suite $inSuite of $inInstance")
}

/** Where a program compiled by `ktc2 test` starts: its tests registered, then Kotlin/Native's runner, with its options. */
public fun ktc2TestMain(args: Array<String>) {
	ktc2RegisterTests()
	testMain(args)
}
