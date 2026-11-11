package stdlib

fun checksum(inBytes: UByteArray): UInt {
	var vSum = 0u
	for (vByte in inBytes) vSum = vSum * 31u + vByte
	return vSum
}

fun main() {
	val vInts = uintArrayOf(3000000000u, 5u, 42u, 4294967295u)
	println(vInts.size)
	println(vInts[0])
	println(vInts.sum())
	println(vInts.max())
	println(vInts.min())
	println(vInts.contentToString())
	println(vInts.sorted())
	println(vInts.joinToString("|"))
	println(vInts.contains(42u))
	println(vInts.indexOf(5u))
	println(vInts.first() > vInts.last())
	vInts[1] = 6u
	println(vInts.toList())
	println(vInts.map { it / 2u })
	println(vInts.filter { it > 100u }.size)
	println(vInts.sumOf { it.toULong() })
	val vSorted = vInts.copyOf()
	vSorted.sort()
	println(vSorted.contentToString())
	println(vInts.contentEquals(vSorted))
	println(vInts.reversedArray().contentToString())
	println(vInts.asIntArray()[0])

	val vBytes = UByteArray(6) { (it * 50).toUByte() }
	println(vBytes.contentToString())
	println(checksum(vBytes))
	println(vBytes.sum())
	println(vBytes.any { it > 200u })
	println(ubyteArrayOf(255u, 1u).map { it.toInt() })
	val vSigned = byteArrayOf(-1, 2).asUByteArray()
	println(vSigned.contentToString())

	val vLongs = ulongArrayOf(18446744073709551615uL, 1uL)
	println(vLongs.max())
	println(vLongs.sorted())
	val vShorts = UShortArray(3)
	vShorts[2] = 65535u
	println(vShorts.contentToString())
	println(vShorts.isEmpty())
	println(UIntArray(0).isEmpty())
	var vTotal = 0uL
	for ((vIndex, vValue) in vLongs.withIndex()) vTotal += vValue / (vIndex + 2).toULong()
	println(vTotal)
	println("done")
}
