package showcase.core

import showcase.core.tests.collectionsGroup
import showcase.core.tests.concurrencyGroup
import showcase.core.tests.errorsGroup
import showcase.core.tests.languageGroup
import showcase.core.tests.shapesGroup
import showcase.core.tests.textGroup
import showcase.core.tests.valuesGroup

// ==================
// MARK: Checks
// ==================

/** One thing that was verified: what, whether it held, and what was found when it did not. */
class Check(val name: String, val passed: Boolean, val detail: String)

/** What a group of tests writes its checks into. */
class Recorder {
	val checks = ArrayList<Check>()

	fun expect(name: String, expected: Any?, actual: Any?) {
		val vPassed = expected == actual
		checks.add(Check(name, vPassed, if (vPassed) "" else "expected $expected, got $actual"))
	}

	fun check(name: String, condition: Boolean) {
		checks.add(Check(name, condition, if (condition) "" else "does not hold"))
	}

	/** Verifies that [block] throws, with [message] as the message of what it throws. */
	fun fails(name: String, message: String?, block: () -> Unit) {
		val vFound = try {
			block()
			"nothing thrown"
		} catch (vError: Exception) {
			if (vError.message == message) "" else "thrown with '${vError.message}'"
		}
		checks.add(Check(name, vFound.isEmpty(), vFound))
	}
}

// ==================
// MARK: Groups
// ==================

/** What running a group gave. */
class Outcome(val title: String, val checks: List<Check>) {
	val passed: Int get() = checks.count { it.passed }

	val failed: Int get() = checks.size - passed

	val isSuccess: Boolean get() = failed == 0

	/** A headline, then one line for each check that failed. */
	fun lines(): List<String> {
		val vLines = ArrayList<String>()
		vLines.add("$title: $passed of ${checks.size} passed")
		for (vCheck in checks) if (!vCheck.passed) vLines.add("  FAILED ${vCheck.name}: ${vCheck.detail}")
		return vLines
	}
}

/** Tests about one subject, run together. */
class Group(val title: String, private val mBody: (Recorder) -> Unit) {
	fun run(): Outcome {
		val vRecorder = Recorder()
		try {
			mBody(vRecorder)
		} catch (vError: Exception) {
			vRecorder.checks.add(Check("the group itself", false, "stopped by an exception: ${vError.message}"))
		}
		return Outcome(title, vRecorder.checks)
	}
}

/** Every group there is, in the order they are shown. */
fun allGroups(): List<Group> = listOf(
	languageGroup(),
	collectionsGroup(),
	textGroup(),
	errorsGroup(),
	valuesGroup(),
	concurrencyGroup(),
	shapesGroup(),
)

/** One line for the whole of [outcomes]. */
fun summary(outcomes: List<Outcome>): String {
	val vPassed = outcomes.sumOf { it.passed }
	val vTotal = outcomes.sumOf { it.checks.size }
	val vGroups = outcomes.count { it.isSuccess }
	return "$vPassed of $vTotal checks passed, $vGroups of ${outcomes.size} groups without a failure"
}

/** Runs every group and prints what it gave: the program without a window. Returns true when nothing failed. */
fun runInConsole(): Boolean {
	val vOutcomes = allGroups().map { it.run() }
	for (vOutcome in vOutcomes) vOutcome.lines().forEach { println(it) }
	println(summary(vOutcomes))
	return vOutcomes.all { it.isSuccess }
}
