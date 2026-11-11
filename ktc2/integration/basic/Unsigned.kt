package basic

fun checksum(seed: UInt, rounds: Int): UInt {
	var vHash = seed
	for (vI in 0 until rounds) {
		vHash = (vHash xor (vHash shl 13)) * 2654435761u
		vHash = vHash xor (vHash shr 7)
	}
	return vHash
}

fun main() {
	val vA = 4000000000u
	val vB = 500000000u
	println(vA + vB)
	println(vB - vA)
	println(vA * 3u)
	println(vA / 7u)
	println(vA % 7u)
	println(vA shr 4)
	println(vA shl 1)
	println(vA.inv())
	println(vA and 0xFFFFu)
	println(vA > vB)
	println(vA.compareTo(vB))
	println(vA == 4000000000u)

	val vWide: ULong = 18446744073709551615uL
	println(vWide)
	println(vWide + 2uL)
	println(vWide / 10uL)
	println(vWide shr 60)
	println(vA.toULong() * vA)

	val vByte: UByte = 200u
	val vShort: UShort = 65535u
	println(vByte)
	println(vByte + vByte)
	println((vByte + vByte).toUByte())
	println(vShort + 1u)
	println(vByte < 201u.toUByte())

	// Conversions
	println(vA.toInt())
	println((-1).toUInt())
	println((-1).toULong())
	println(vA.toLong())
	println(vA.toDouble())
	println(3.7.toUInt())
	println((-3.7).toUInt())
	println(1.0e30.toUInt())
	println(vWide.toDouble())
	println(300.toUByte())
	println((-1L).toULong())

	var vCount = 0u
	vCount++
	vCount += 41u
	println("count=$vCount hash=${checksum(12345u, 8)}")
}
