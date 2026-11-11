package values

import ktc2.Size
import ktc2.Value

class Particle(var x: Float, var y: Float) {
	fun move(inDx: Float, inDy: Float) {
		x += inDx
		y += inDy
	}

	fun energy(): Float = x * x + y * y
}

class Histogram {
	val bins = IntArray(8)

	fun add(inValues: IntArray) {
		for (vValue in inValues) bins[vValue % 8]++
	}
}

fun checksum(inData: ByteArray): Int {
	var vSum = 0
	for (vByte in inData) vSum = vSum * 31 + vByte
	return vSum
}

/** A large working buffer that never leaves the function. */
fun sieve(): Int {
	@Size(2000) val vComposite = BooleanArray(2000)
	var vCount = 0
	for (vNumber in 2 until 2000) {
		if (vComposite[vNumber]) continue
		vCount++
		var vMultiple = vNumber * 2
		while (vMultiple < 2000) {
			vComposite[vMultiple] = true
			vMultiple += vNumber
		}
	}
	return vCount
}

fun simulate(inSteps: Int): Float {
	val vParticle: @Value Particle = Particle(0f, 0f)
	for (vStep in 0 until inSteps) vParticle.move(0.5f, 0.25f)
	return vParticle.energy()
}

fun main() {
	println(sieve())
	println(simulate(8))

	// Passed to functions that only read it.
	@Size(256) val vBuffer = ByteArray(256)
	for (vIndex in 0 until 256) vBuffer[vIndex] = (vIndex % 7).toByte()
	println(checksum(vBuffer))
	println(vBuffer.size)

	// In a loop: the same frame slot each time, zeroed again.
	val vHistogram = Histogram()
	for (vRound in 0 until 3) {
		@Size(100) val vSamples = IntArray(100)
		println(vSamples[99])
		for (vIndex in 0 until 100) vSamples[vIndex] = vIndex * (vRound + 1)
		vHistogram.add(vSamples)
	}
	println(vHistogram.bins.joinToString(" "))

	val vNames: @Size(3) Array<String> = arrayOf("a", "b", "c")
	println(vNames.joinToString(""))
	val vOrigin: @Value Particle = Particle(3f, 4f)
	println(vOrigin.energy())
	try {
		println(vBuffer[300])
	} catch (vError: IndexOutOfBoundsException) {
		println("out of bounds")
	}
	println("done")
}
