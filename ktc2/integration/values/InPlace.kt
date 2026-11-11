package values

class Vec3(val x: Double, val y: Double, val z: Double) {
	operator fun plus(inOther: Vec3): Vec3 = Vec3(x + inOther.x, y + inOther.y, z + inOther.z)

	fun scaled(inFactor: Double): Vec3 = Vec3(x * inFactor, y * inFactor, z * inFactor)

	fun length2(): Double = x * x + y * y + z * z

	override fun toString(): String = "($x, $y, $z)"
}

class Labeled(val name: String, val position: Vec3)

/** The result of one function handed straight on as the result of another. */
fun midpoint(inFirst: Vec3, inSecond: Vec3): Vec3 = (inFirst + inSecond).scaled(0.5)

fun make(inIndex: Int): Vec3 {
	if (inIndex < 0) throw IllegalArgumentException("negative index")
	val vResult = Vec3(inIndex.toDouble(), 1.0, 0.0)
	return vResult
}

fun labeled(inIndex: Int): Labeled = Labeled("n$inIndex", make(inIndex))

/** Returns its argument or a new object: the caller cannot supply the room. */
fun orOrigin(inValue: Vec3, inKeep: Boolean): Vec3 = if (inKeep) inValue else Vec3(0.0, 0.0, 0.0)

fun branchy(inKind: Int): Vec3 {
	val vResult = if (inKind == 0) Vec3(1.0, 0.0, 0.0) else Vec3(0.0, 1.0, 0.0)
	return vResult
}

fun main() {
	// Results used and dropped where they are received.
	var vTotal = 0.0
	for (vIndex in 0 until 200) {
		val vMiddle = midpoint(make(vIndex), make(vIndex + 2))
		vTotal += vMiddle.length2()
		vTotal += labeled(vIndex).position.x
		vTotal += (make(vIndex) + make(1)).scaled(2.0).y
	}
	println(vTotal)
	println(midpoint(Vec3(0.0, 0.0, 0.0), Vec3(2.0, 4.0, 6.0)))
	println(branchy(0).x + branchy(1).y)

	// Results that are kept have to outlive the call.
	val vKept = ArrayList<Vec3>()
	for (vIndex in 0 until 3) vKept.add(make(vIndex).scaled(10.0))
	println(vKept)
	val vLabel = labeled(7)
	println("${vLabel.name} ${vLabel.position}")
	var vLast = make(0)
	for (vIndex in 1..4) vLast = vLast + make(vIndex)
	println(vLast)
	println(orOrigin(vLast, true) === vLast)
	println(orOrigin(vLast, false))

	// A function that fails leaves nothing behind.
	try {
		println(make(-1))
	} catch (vError: IllegalArgumentException) {
		println(vError.message)
	}
	println("done")
}
