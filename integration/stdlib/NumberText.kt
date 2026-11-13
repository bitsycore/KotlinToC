// Numbers as text, in templates, `toString()` and a StringBuilder, at the ends of their ranges;
// and a StringBuilder given text that is not ASCII.

fun main() {
	val vInts = listOf(0, 1, -1, 9, 10, -10, 99, 100, 12345, -99999, Int.MAX_VALUE, Int.MIN_VALUE)
	for (vValue in vInts) println("$vValue ${vValue.toString().length} ${-vValue} ${vValue.toLong() * 1000003}")
	val vLongs = listOf(0L, -1L, 1234567890123L, Long.MAX_VALUE, Long.MIN_VALUE, Long.MIN_VALUE + 1)
	for (vValue in vLongs) println("$vValue|${vValue.toString()}|" + vValue)
	println("${UInt.MAX_VALUE} ${ULong.MAX_VALUE} ${0u} ${UByte.MAX_VALUE} ${UShort.MAX_VALUE} ${3000000000u} ${10000000000000000000uL}")
	println("${Byte.MIN_VALUE} ${Short.MIN_VALUE} ${Byte.MAX_VALUE} ${true} ${false} ${'x'} ${1.5} ${2.5f}")

	val vBuilder = StringBuilder()
	for (vValue in vInts) vBuilder.append(vValue).append(' ')
	for (vValue in vLongs) vBuilder.append(vValue).append(',')
	println(vBuilder)
	println(vBuilder.length)
	vBuilder.setLength(0)
	vBuilder.append(true).append(false).append(1.25).append(0.5f).append('c').append(null as String?).append(null as Any?).append(listOf(1, 2))
	println(vBuilder)

	// Text of every width, appended whole and in parts.
	val vText = "héllo こんにちは 😀!"
	val vWide = StringBuilder()
	vWide.append(vText).append(vText as CharSequence).append(vText, 1, 4).append(vText, 12, 14)
	println("$vWide ${vWide.length} ${vText.length}")
	println("${vWide[1].code} ${vWide[6].code} ${vWide[12].code} ${vWide[13].code}")
	vWide.insert(2, "é😀").insert(0, 42).insert(0, -7L)
	println(vWide)
	vWide.reverse()
	println(vWide.length)
	val vGrown = StringBuilder(1)
	for (vIndex in 0 until 300) vGrown.append(vIndex % 10).append("é")
	println("${vGrown.length} ${vGrown.substring(0, 12)} ${vGrown.toString().length} ${vGrown.indexOf("9é0")}")
	println(buildString { for (vIndex in -3..3) append(vIndex).append(vIndex.toLong() * 4000000000L) })
	println(CharArray(5) { 'a' + it }.concatToString() + String(charArrayOf('é', 'z')) + charArrayOf("😀"[0], "😀"[1]).concatToString())
}
