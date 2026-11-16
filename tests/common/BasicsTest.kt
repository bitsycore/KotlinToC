import kotlin.test.*

// The first common tests: what must hold on the JVM, on Kotlin/Native and in C alike.

class NumbersTest {
	@Test
	fun integerDivisionTruncates() {
		assertEquals(3, 7 / 2)
		assertEquals(-3, -7 / 2)
		assertEquals(-1, -7 % 3)
		assertEquals(2, (-7).mod(3))
	}

	@Test
	fun overflowWraps() {
		assertEquals(Int.MIN_VALUE, Int.MAX_VALUE + 1)
		assertEquals(Long.MIN_VALUE, Long.MAX_VALUE + 1)
	}

	@Test
	fun divisionByZeroThrows() {
		val vZero = 0
		assertFailsWith<ArithmeticException> { 1 / vZero }
		assertTrue((1.0 / 0.0).isInfinite())
	}

	@Test
	fun doublesCompareAsAnOrder() {
		assertTrue(Double.NaN.compareTo(1.0) > 0)
		assertTrue((-0.0).compareTo(0.0) < 0)
		assertEquals(0.3, 0.1 + 0.2, 1e-9)
	}

	@Test
	fun textToNumbers() {
		assertEquals(42, "42".toInt())
		assertNull("x".toIntOrNull())
		assertFailsWith<NumberFormatException> { "x".toInt() }
		assertEquals(255, "ff".toInt(16))
		assertEquals(4000000000u, "4000000000".toUInt())
	}
}

class CollectionsTest {
	private val mList = ArrayList<Int>()

	@BeforeTest
	fun fill() {
		for (vIndex in 1..5) mList += vIndex * vIndex
	}

	@Test
	fun listReadsAndWrites() {
		assertEquals(5, mList.size)
		assertEquals(16, mList[3])
		mList[3] = 0
		assertContentEquals(listOf(1, 4, 9, 0, 25), mList)
		assertFailsWith<IndexOutOfBoundsException> { mList[5] }
	}

	@Test
	fun eachTestHasItsOwnObject() {
		assertContentEquals(listOf(1, 4, 9, 16, 25), mList)
	}

	@Test
	fun subListIsAView() {
		mList.subList(1, 3).clear()
		assertEquals(listOf(1, 16, 25), mList)
	}

	@Test
	fun changedWhileGoneOver() {
		assertFailsWith<ConcurrentModificationException> { for (vItem in mList) if (vItem == 1) mList.add(0) }
	}

	@Test
	fun mapsAndSets() {
		val vMap = hashMapOf("a" to 1, "b" to 2)
		vMap["c"] = 3
		assertEquals(3, vMap.size)
		assertContains(vMap, "c")
		assertNull(vMap["z"])
		assertEquals(setOf(1, 2, 3), vMap.values.toSet())
		assertFailsWith<NoSuchElementException> { vMap.getValue("z") }
		assertEquals(listOf(1, 2, 3), setOf(3, 1, 2).sorted())
	}

	@Test
	fun numbersSeenAsObjects() {
		val vAny: List<Any> = mList
		assertFalse(vAny.contains("x"))
		assertTrue(vAny.contains(9))
		assertEquals(-1, vAny.indexOf(2.5))
		assertIs<List<*>>(vAny)
	}
}

class TextTest {
	@Test
	fun searchingInAccentedText() {
		val vText = "héllo wörld 😀"
		assertEquals(6, vText.indexOf("wörld"))
		assertEquals(12, vText.indexOf("😀"))
		assertEquals(14, vText.length)
		assertContains(vText, "ö")
		assertEquals("HÉLLO", vText.substring(0, 5).uppercase())
	}

	@Test
	fun buildingText() {
		val vBuilder = StringBuilder()
		vBuilder.append("a").append(1).append('c').append(2.5)
		assertEquals("a1c2.5", vBuilder.toString())
		assertEquals("x=3, y=[1, 2]", "x=${1 + 2}, y=${listOf(1, 2)}")
	}

	@Test
	fun splittingAndJoining() {
		assertEquals(listOf("a", "b", "", "c"), "a,b,,c".split(","))
		assertEquals("a-b", listOf("a", "b").joinToString("-"))
		assertEquals(listOf("one", "two"), "one two".split(Regex("\\s+")))
	}
}

class ObjectsTest {
	data class Point(val x: Int, val y: Int)
	open class Shape(val name: String)
	class Circle : Shape("circle")
	class Box<T>(val value: T)

	@Test
	fun dataClasses() {
		assertEquals(Point(1, 2), Point(1, 2))
		assertNotSame(Point(1, 2), Point(1, 2))
		assertEquals(Point(1, 2).hashCode(), Point(1, 2).hashCode())
		assertEquals("Point(x=1, y=2)", Point(1, 2).toString())
		assertEquals(Point(1, 9), Point(1, 2).copy(y = 9))
	}

	@Test
	fun classesAsValues() {
		val vShape: Shape = Circle()
		assertEquals(Circle::class, vShape::class)
		assertNotEquals<Any>(Shape::class, vShape::class)
		assertEquals("Circle", vShape::class.simpleName)
	}

	@Test
	fun genericsOverNumbersAndObjects() {
		val vBoxes: List<Box<*>> = listOf(Box(1), Box("s"), Box(2.5))
		assertEquals(listOf<Any?>(1, "s", 2.5), vBoxes.map { it.value })
		assertTrue(vBoxes.all { (it as Any) is Box<*> })
	}

	@Test
	fun lateinitAndNulls() {
		class Holder { lateinit var text: String }
		val vHolder = Holder()
		assertFails { vHolder.text.length }
		vHolder.text = "set"
		assertEquals(3, vHolder.text.length)
		val vNull: String? = null
		assertFailsWith<NullPointerException> { vNull!!.length }
	}

	@Ignore
	@Test
	fun notRun() {
		fail("an ignored test ran")
	}
}

@Test
fun aTestOutsideAnyClass() {
	expect(6) { listOf(1, 2, 3).sum() }
}
