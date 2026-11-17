// A `var` that goes down a structure (`node = node.next`) need not count each object it
// passes, as long as nothing unlinks them meanwhile. These are the cases where it may, and
// the ones where something does unlink them: each must print what the JVM prints.

class Node(val value: Int, val next: Node?)

class Link(var value: Int) {
	var next: Link? = null
}

class Tree(val value: Int) {
	var left: Tree? = null
	var right: Tree? = null
}

fun build(inCount: Int): Node? {
	var vHead: Node? = null
	for (vIndex in 0 until inCount) vHead = Node(vIndex, vHead)
	return vHead
}

fun links(inCount: Int): Link? {
	var vHead: Link? = null
	for (vIndex in 0 until inCount) {
		val vLink = Link(vIndex)
		vLink.next = vHead
		vHead = vLink
	}
	return vHead
}

// ==================
// MARK: Only looking
// ==================

fun sum(inHead: Node?): Int {
	var vSum = 0
	var vNode = inHead
	while (vNode != null) {
		vSum += vNode.value
		vNode = vNode.next
	}
	return vSum
}

fun sumOwn(): Int {
	var vHead = build(3)
	vHead = build(5)
	var vSum = 0
	var vNode = vHead
	while (vNode != null) {
		vSum += vNode.value
		vNode = vNode.next
	}
	return vSum
}

fun last(inHead: Link?): Link? {
	var vLast: Link? = null
	var vLink = inHead
	while (vLink != null) {
		vLast = vLink
		vLink = vLink.next
	}
	return vLast
}

fun find(inRoot: Tree?, inValue: Int): Boolean {
	var vTree = inRoot
	while (vTree != null) {
		if (vTree.value == inValue) return true
		vTree = if (inValue < vTree.value) vTree.left else vTree.right
	}
	return false
}

fun insert(inRoot: Tree, inValue: Int) {
	var vTree = inRoot
	while (true) {
		if (inValue < vTree.value) {
			val vLeft = vTree.left
			if (vLeft == null) {
				vTree.left = Tree(inValue)
				return
			}
			vTree = vLeft
		} else {
			val vRight = vTree.right
			if (vRight == null) {
				vTree.right = Tree(inValue)
				return
			}
			vTree = vRight
		}
	}
}

// ==================
// MARK: Unlinking on the way
// ==================

/** Drops the links with an odd value: the one being looked at may be the one dropped. */
fun dropOdd(inHead: Link?): Link? {
	var vHead = inHead
	while (vHead != null && vHead.value % 2 == 1) vHead = vHead.next
	var vLink = vHead
	while (vLink != null) {
		var vNext = vLink.next
		while (vNext != null && vNext.value % 2 == 1) vNext = vNext.next
		vLink.next = vNext
		vLink = vNext
	}
	return vHead
}

fun reverse(inHead: Link?): Link? {
	var vDone: Link? = null
	var vLink = inHead
	while (vLink != null) {
		val vNext = vLink.next
		vLink.next = vDone
		vDone = vLink
		vLink = vNext
	}
	return vDone
}

/** Empties the list while it goes down it: each link is the last reference to the next. */
fun consume(inHolder: Array<Link?>): Int {
	var vSum = 0
	var vLink = inHolder[0]
	inHolder[0] = null
	while (vLink != null) {
		vSum += vLink.value
		val vNext = vLink.next
		vLink.next = null
		vLink = vNext
	}
	return vSum
}

/** The variable the walk started from is given another list on the way. */
fun restart(): Int {
	var vHead = build(4)
	var vSum = 0
	var vNode = vHead
	var vRound = 0
	while (vNode != null) {
		vSum += vNode.value
		vNode = vNode.next
		if (vRound++ == 1) vHead = build(2)
	}
	return vSum + (vHead?.value ?: 0)
}

/** A list long enough that giving it up cell after cell, each from inside the one before, would not fit the stack. */
fun long(): Int {
	val vHead = build(1_000_000)
	var vCount = 0
	var vNode = vHead
	while (vNode != null) {
		vCount++
		vNode = vNode.next
	}
	return vCount
}

fun text(inHead: Link?): String {
	val vText = StringBuilder()
	var vLink = inHead
	while (vLink != null) {
		vText.append(vLink.value).append(' ')
		vLink = vLink.next
	}
	return vText.toString().trim()
}

fun main() {
	println(sum(build(10)))
	println(sumOwn())
	println(last(links(6))?.value)
	val vRoot = Tree(50)
	for (vValue in listOf(30, 70, 20, 40, 60, 80, 35)) insert(vRoot, vValue)
	println("${find(vRoot, 35)} ${find(vRoot, 36)} ${find(vRoot, 80)}")
	println(text(dropOdd(links(9))))
	println(text(dropOdd(links(2))))
	println(text(reverse(links(5))))
	println(consume(arrayOf(links(7))))
	println(restart())
	println(long())
}
