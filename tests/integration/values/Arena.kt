package values

import ktc2.arena

class Vertex(val x: Int, val y: Int) {
	var next: Vertex? = null
}

class Summary(val count: Int, val total: Long, val label: String)

class Token(val kind: String, val text: String)

/** Builds a throwaway chain of objects and keeps only numbers. */
fun perimeter(inPoints: Int): Long {
	var vHead: Vertex? = null
	for (vIndex in 0 until inPoints) {
		val vVertex = Vertex(vIndex, vIndex * 2)
		vVertex.next = vHead
		vHead = vVertex
	}
	var vTotal = 0L
	var vAt = vHead
	while (vAt != null) {
		vTotal += vAt.x + vAt.y
		vAt = vAt.next
	}
	return vTotal
}

fun tokenize(inText: String): List<Token> {
	val vTokens = ArrayList<Token>()
	for (vWord in inText.split(" ")) {
		val vKind = if (vWord.all { it.isDigit() }) "number" else "word"
		vTokens.add(Token(vKind, vWord))
	}
	return vTokens
}

/** The result object is created inside the arena and outlives it. */
fun summarize(inText: String): Summary = arena(4096) {
	val vTokens = tokenize(inText)
	val vNumbers = vTokens.filter { it.kind == "number" }.map { it.text.toLong() }
	Summary(vTokens.size, vNumbers.sum(), vTokens.joinToString("|") { it.kind.take(1) + it.text })
}

fun failing(inLimit: Int): Int = arena(1024) {
	val vItems = ArrayList<String>()
	for (vIndex in 0 until 10) {
		if (vIndex == inLimit) throw IllegalStateException("stopped at $vIndex with ${vItems.size} items")
		vItems.add("item $vIndex")
	}
	vItems.size
}

fun main() {
	// Plain temporaries: everything dies with the block.
	println(arena(64 * 1024) { perimeter(1000) })

	// More objects than one block holds: the arena adds blocks.
	println(arena(512) { perimeter(500) })

	val vSummary = summarize("12 apples and 30 pears in 2 baskets")
	println("${vSummary.count} ${vSummary.total} ${vSummary.label}")

	// Nested arenas; the inner result is used by the outer block.
	val vNested = arena(2048) {
		val vOuter = tokenize("a b c")
		val vInner = arena(2048) { tokenize("1 2 3 4").map { it.text } }
		vOuter.size * 10 + vInner.size
	}
	println(vNested)

	// An exception leaves the arena like any other block.
	println(failing(20))
	try {
		failing(3)
	} catch (vError: IllegalStateException) {
		println(vError.message)
	}

	// Objects that outlive their arena, stored where the block could not know.
	val vKept = ArrayList<Vertex>()
	for (vRound in 0 until 3) {
		arena(256) {
			val vVertex = Vertex(vRound, vRound + 1)
			val vScratch = Vertex(-1, -1)
			vScratch.next = vVertex
			if (vRound != 1) vKept.add(vVertex)
		}
	}
	println(vKept.map { "${it.x},${it.y}" })

	// A large object does not fit a small block: it gets a block of its own.
	val vBig = arena(256) {
		val vNumbers = IntArray(5000) { it }
		var vSum = 0
		for (vNumber in vNumbers) vSum += vNumber
		vSum
	}
	println(vBig)
	println("done")
}
