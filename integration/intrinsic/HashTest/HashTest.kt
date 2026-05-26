package HashTest

data class Vec2(val x: Float, val y: Float)
data class Point3D(val x: Float, val y: Float, val z: Float)
data class Player(val name: String, val score: Int)
data class Pair2(val a: Int, val b: Int)
class RegularClass(val id: Int)

fun main() {
	// Same value data classes produce same hash
	val a = Vec2(1.0f, 2.0f)
	val b = Vec2(1.0f, 2.0f)
	val c = Vec2(3.0f, 4.0f)
	if (a.hashCode() != b.hashCode()) error("FAIL same value -> same hash")
	println("same value -> same hash: ok")
	if (a.hashCode() == c.hashCode()) error("FAIL diff value -> diff hash")
	println("diff value -> diff hash: ok")

	// 3-field data class
	val p1 = Point3D(1.0f, 2.0f, 3.0f)
	val p2 = Point3D(1.0f, 2.0f, 3.0f)
	val p3 = Point3D(3.0f, 2.0f, 1.0f)
	if (p1.hashCode() != p2.hashCode()) error("FAIL Point3D same -> same hash")
	println("Point3D same -> same hash: ok")
	if (p1.hashCode() == p3.hashCode()) error("FAIL Point3D reversed -> diff hash")
	println("Point3D reversed -> diff hash: ok")

	// String + Int fields
	val pl1 = Player("Alice", 100)
	val pl2 = Player("Alice", 100)
	val pl3 = Player("Bob", 100)
	val pl4 = Player("Alice", 200)
	if (pl1.hashCode() != pl2.hashCode()) error("FAIL Player same -> same hash")
	println("Player same -> same hash: ok")
	if (pl1.hashCode() == pl3.hashCode()) error("FAIL Player diff name -> diff hash")
	println("Player diff name -> diff hash: ok")
	if (pl1.hashCode() == pl4.hashCode()) error("FAIL Player diff score -> diff hash")
	println("Player diff score -> diff hash: ok")

	// Order of fields matters
	val pair1 = Pair2(1, 2)
	val pair2 = Pair2(2, 1)
	if (pair1.hashCode() == pair2.hashCode()) error("FAIL Pair2 field order matters")
	println("Pair2 field order matters: ok")

	// Same Pair same hash
	val pair3 = Pair2(1, 2)
	if (pair1.hashCode() != pair3.hashCode()) error("FAIL Pair2 same -> same hash")
	println("Pair2 same -> same hash: ok")

	// Regular class: different instances → different hash (address-based)
	val r1 = RegularClass(1)
	val r2 = RegularClass(2)
	if (r1.hashCode() == r2.hashCode()) error("FAIL RegularClass should differ by address")
	println("RegularClass different because diff address: ok")

	// Zero/negative values
	val z1 = Vec2(0.0f, 0.0f)
	val z2 = Vec2(0.0f, 0.0f)
	if (z1.hashCode() != z2.hashCode()) error("FAIL zero vec same hash")
	println("zero vec same hash: ok")

	println("ALL OK")
}
