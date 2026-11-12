// Benchmark: arithmetic over arrays of numbers, no objects.

/** The primes below [inLimit], counted with a sieve. */
fun sieve(inLimit: Int): Int {
	val vComposite = BooleanArray(inLimit + 1)
	var vCount = 0
	for (vI in 2..inLimit) {
		if (vComposite[vI]) continue
		vCount++
		var vJ = vI.toLong() * vI
		while (vJ <= inLimit) {
			vComposite[vJ.toInt()] = true
			vJ += vI
		}
	}
	return vCount
}

/** The product of two square matrices of size [inSize], stored row after row. */
fun multiply(inA: DoubleArray, inB: DoubleArray, inSize: Int): DoubleArray {
	val vResult = DoubleArray(inSize * inSize)
	for (vI in 0 until inSize) {
		for (vJ in 0 until inSize) {
			var vSum = 0.0
			for (vK in 0 until inSize) vSum += inA[vI * inSize + vK] * inB[vK * inSize + vJ]
			vResult[vI * inSize + vJ] = vSum
		}
	}
	return vResult
}

/** How many points of a grid stay bounded under z -> z*z + c. */
fun mandelbrot(inSize: Int): Int {
	var vInside = 0
	for (vY in 0 until inSize) {
		for (vX in 0 until inSize) {
			val vCr = 3.0 * vX / inSize - 2.0
			val vCi = 2.0 * vY / inSize - 1.0
			var vZr = 0.0
			var vZi = 0.0
			var vI = 0
			while (vI < 100 && vZr * vZr + vZi * vZi < 4.0) {
				val vNext = vZr * vZr - vZi * vZi + vCr
				vZi = 2.0 * vZr * vZi + vCi
				vZr = vNext
				vI++
			}
			if (vI == 100) vInside++
		}
	}
	return vInside
}

fun main() {
	println(sieve(20000000))
	val vSize = 200
	val vA = DoubleArray(vSize * vSize) { (it % 7).toDouble() }
	val vB = DoubleArray(vSize * vSize) { (it % 5).toDouble() }
	var vTrace = 0.0
	for (vRound in 0 until 40) {
		val vC = multiply(vA, vB, vSize)
		for (vI in 0 until vSize) vTrace += vC[vI * vSize + vI]
	}
	println(vTrace)
	println(mandelbrot(1200))
	val vValues = IntArray(2000000) { (it * 7919) % 10007 }
	var vSum = 0L
	for (vRound in 0 until 60) {
		for (vValue in vValues) vSum += vValue
		for (vI in vValues.indices) vValues[vI] = vValues[vI] xor vRound
	}
	println(vSum)
}
