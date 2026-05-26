package effects

import core.*

data class Flash(val intensity: Double, val r: Byte, val g: Byte, val b: Byte) : Drawable {
	override fun draw(): Int = (intensity * 255.0).toInt()
	override fun name(): String = "Flash"
}

data class Trail(val length: Int, val segments: Int, val opacity: Float) : Drawable, Tickable {
	override fun draw(): Int = length * segments
	override fun name(): String = "Trail"
	override fun tick(dt: Float): Float = opacity - dt * 0.1f
}
