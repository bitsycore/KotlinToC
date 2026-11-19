// What `ktc2 test` registers as tests, as Kotlin/Native's runner does: a class's @Test functions on a new object each,
// between its @BeforeTest and @AfterTest functions; those it inherits; an object's; a nested class's; never an @Ignore'd one.

package ktc2test.language.testclasses

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals

class Counted {
	private var mSteps = ArrayList<String>()

	@BeforeTest
	fun prepare() {
		mSteps += "before"
	}

	@AfterTest
	fun finish() {
		println("after: ${mSteps.joinToString()}")
	}

	@Test
	fun first() {
		mSteps += "first"
		assertEquals(listOf("before", "first"), mSteps)
	}

	@Test
	fun second() {
		// A new object for each test: the first one's steps are not here.
		mSteps += "second"
		assertEquals(listOf("before", "second"), mSteps)
	}

	@Ignore
	@Test
	fun ignored() {
		error("an ignored test is never run")
	}

	class Nested {
		@Test
		fun inside() {
			println("a nested class's test")
		}
	}
}

abstract class Shared {
	abstract val name: String

	@Test
	fun inherited() {
		println("inherited by $name")
	}
}

class FirstShared : Shared() {
	override val name = "first"
}

class SecondShared : Shared() {
	override val name = "second"
}

object Single {
	private var mRuns = 0

	@Test
	fun once() {
		mRuns++
		println("an object's test, run $mRuns")
	}
}

@Ignore
class IgnoredClass {
	@Test
	fun never() {
		error("a test of an ignored class is never run")
	}
}

@Test
fun topLevel() {
	println("a top-level test")
}
