package names

private val table = intArrayOf(100, 200, 300, 400)

private fun pick(inIndex: Int): Int = table[inIndex] + 1

fun otherPick(inIndex: Int): Int = pick(inIndex)

fun otherSum(): Int = table.sum()
