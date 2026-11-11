package objects

class Counter(val name: String) {
	private var mTotal = 0

	inner class Step(val amount: Int) {
		fun apply(): Int {
			mTotal += amount
			return mTotal
		}

		fun describe(): String = "$name +$amount"
	}

	fun step(inAmount: Int): Step = Step(inAmount)

	fun total(): Int = mTotal
}

open class Grid<T>(val width: Int, private val mFill: T) {
	private val mCells = ArrayList<T>()

	init {
		for (vIndex in 0 until width) mCells.add(mFill)
	}

	open inner class Cursor {
		protected var position = 0

		open fun read(): T = mCells[position]

		fun move(): Boolean {
			if (position + 1 >= width) return false
			position++
			return true
		}
	}

	inner class Editor(inStart: Int) : Cursor() {
		init {
			position = inStart
		}

		fun write(inValue: T) {
			mCells[position] = inValue
		}

		override fun read(): T = mCells[position]
	}

	fun cursor(): Cursor = Cursor()

	fun dump(): String = mCells.joinToString(",")
}

class Shelf(private val mBooks: List<String>) : Iterable<String> {
	inner class ShelfIterator : Iterator<String> {
		private var mIndex = 0

		override fun hasNext(): Boolean = mIndex < mBooks.size

		override fun next(): String = mBooks[mIndex++]
	}

	override fun iterator(): Iterator<String> = ShelfIterator()
}

fun main() {
	val vCounter = Counter("clicks")
	val vOne = vCounter.step(1)
	val vTen = vCounter.Step(10)
	println(vOne.apply())
	println(vTen.apply())
	println(vOne.apply())
	println(vTen.describe())
	println(vCounter.total())

	val vGrid = Grid(4, 0)
	val vEditor = vGrid.Editor(1)
	vEditor.write(7)
	vEditor.move()
	vEditor.write(8)
	println(vGrid.dump())
	val vCursor = vGrid.cursor()
	var vSum = vCursor.read()
	while (vCursor.move()) vSum += vCursor.read()
	println(vSum)
	val vReader: Grid<Int>.Cursor = vEditor
	println(vReader.read())

	val vNames = Grid(2, "-")
	vNames.Editor(0).write("first")
	println(vNames.dump())

	val vShelf = Shelf(listOf("Dune", "Emma", "Ulysses"))
	for (vBook in vShelf) println(vBook)
	println(vShelf.map { it.length })

	// The iterators of the standard library's abstract lists are inner classes.
	val vView = intArrayOf(4, 5, 6).asList()
	val vIterator = vView.listIterator(1)
	while (vIterator.hasNext()) println("${vIterator.nextIndex()}=${vIterator.next()}")
	while (vIterator.hasPrevious()) print("${vIterator.previous()} ")
	println()
	println(vView.subList(0, 2).sum())
	println("done")
}
