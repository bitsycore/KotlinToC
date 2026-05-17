package game

data class Vec3(val x: Float, val y: Float, val z: Float)

fun dot(a: Vec3, b: Vec3): Float {
    return a.x * b.x + a.y * b.y + a.z * b.z
}