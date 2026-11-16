package names

private val table = intArrayOf(1, 2, 3)

private fun pick(inIndex: Int): Int = table[inIndex] * 10

fun main() {
	println("${pick(1)} ${otherPick(1)}")
	println("${table.sum()} ${otherSum()}")
}
