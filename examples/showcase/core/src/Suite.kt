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

/**
 * One thing that was verified.
 *
 * @property detail what was wrong, when it did not hold
 * @property found what the program gave: the value compared, or what was thrown
 */
class Check(val name: String, val passed: Boolean, val detail: String, val found: String)

/** What a group of tests writes its checks into. */
class Recorder {
	val checks = ArrayList<Check>()

	fun expect(name: String, expected: Any?, actual: Any?) {
		val vPassed = expected == actual
		checks.add(Check(name, vPassed, if (vPassed) "" else "expected $expected, got $actual", actual.toString()))
	}

	fun check(name: String, condition: Boolean) {
		checks.add(Check(name, condition, if (condition) "" else "does not hold", if (condition) "holds" else "does not hold"))
	}

	/** Verifies that [block] throws, with [message] as the message of what it throws. */
	fun fails(name: String, message: String?, block: () -> Unit) {
		var vThrown = "nothing thrown"
		val vWrong = try {
			block()
			"nothing thrown"
		} catch (vError: Exception) {
			vThrown = "throws: ${vError.message}"
			if (vError.message == message) "" else "thrown with '${vError.message}'"
		}
		checks.add(Check(name, vWrong.isEmpty(), vWrong, vThrown))
	}
}

// ==================
// MARK: Groups
// ==================

/** How the lines of a log start: a check that held, one that did not, and what a check found. */
const val kPassedMark = " ok  "
const val kFailedMark = "FAIL "
const val kFoundMark = "     "

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

	/**
	 * Everything the group did: the headline, then for each check its name and, on the line
	 * below, what the program gave. A line starts with [kPassedMark], [kFailedMark] or
	 * [kFoundMark], by which whoever shows it can tell them apart.
	 */
	fun log(): List<String> {
		val vLines = ArrayList<String>()
		vLines.add("$title: $passed of ${checks.size} passed")
		for (vCheck in checks) {
			vLines.add((if (vCheck.passed) kPassedMark else kFailedMark) + vCheck.name)
			vLines.add(kFoundMark + if (vCheck.passed) vCheck.found else vCheck.detail)
		}
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
			vRecorder.checks.add(Check("the group itself", false, "stopped by an exception: ${vError.message}", "throws: ${vError.message}"))
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

/**
 * Runs every group and prints what it gave: the program without a window. With [full] every
 * check is printed with what it found, otherwise only the ones that failed.
 * Returns true when nothing failed.
 */
fun runInConsole(full: Boolean = false): Boolean {
	val vOutcomes = allGroups().map { it.run() }
	for (vOutcome in vOutcomes) (if (full) vOutcome.log() else vOutcome.lines()).forEach { println(it) }
	println(summary(vOutcomes))
	return vOutcomes.all { it.isSuccess }
}
