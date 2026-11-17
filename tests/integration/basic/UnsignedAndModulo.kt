fun main() {
	println("4000000000".toUInt())
	println("${"18446744073709551615".toULong()} ${"255".toUByte()} ${"x".toUIntOrNull()} ${"ff".toUInt(16)} ${7.mod(3)} ${(-7).mod(3)} ${(-7).floorDiv(2)} ${(-7L).mod(3L)} ${7.5.mod(2.0)}")
	println("${(1u..5u).toList()} ${(5u downTo 1u step 2).toList()} ${3u in 1u..5u} ${(0u until 3u).sum()} ${10u.coerceIn(1u, 5u)} ${maxOf(3u, 7u)} ${5u.toString(2)} ${UInt.MAX_VALUE.toString(16)}")
	println("${255.toHexString()} ${byteArrayOf(1, -1).toHexString()} ${"ff".hexToInt()}")
	println("${1.hashCode()} ${"a".hashCode()} ${null.hashCode()} ${true.hashCode()} ${1.5.hashCode()} ${'a'.hashCode()} ${5L.hashCode()}")
}
