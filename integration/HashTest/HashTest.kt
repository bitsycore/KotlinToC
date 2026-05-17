package HashTest

data class Vec2(val x: Float, val y: Float)
data class Point3D(val x: Float, val y: Float, val z: Float)
data class Player(val name: String, val score: Int)
class RegularClass(val id: Int)

fun main() {
    val a = Vec2(1.0f, 2.0f)
    val b = Vec2(1.0f, 2.0f)
    val c = Vec2(3.0f, 4.0f)

    if (a.hashCode() != b.hashCode()) error("FAIL same value -> same hash")
    println("same value -> same hash: ok")
    if (a.hashCode() == c.hashCode()) error("FAIL diff value -> diff hash (may be acceptable collision)")
    println("diff value -> diff hash: ok")

    val p1 = Point3D(1.0f, 2.0f, 3.0f)
    val p2 = Point3D(1.0f, 2.0f, 3.0f)
    if (p1.hashCode() != p2.hashCode()) error("FAIL Point3D same -> same hash")
    println("Point3D same -> same hash: ok")

    val pl1 = Player("Alice", 100)
    val pl2 = Player("Alice", 100)
    val pl3 = Player("Bob", 100)
    if (pl1.hashCode() != pl2.hashCode()) error("FAIL Player same -> same hash")
    println("Player same -> same hash: ok")
    if (pl1.hashCode() == pl3.hashCode()) error("FAIL Player diff -> diff hash (may be acceptable collision)")
    println("Player diff -> diff hash: ok")

    val r1 = RegularClass(1)
    val r2 = RegularClass(2)
    if (r1.hashCode() == r2.hashCode()) error("FAIL RegularClass should differ by address")
    println("RegularClass different because diff address: ok")

    println("done")
}
