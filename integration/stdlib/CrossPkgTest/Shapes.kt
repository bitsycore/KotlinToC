package shapes

import core.*

data class Circle(val radius: Float, val cx: Float, val cy: Float) : Drawable {
	override fun draw(): Int = 1
	override fun name(): String = "Circle"
}

data class Rect(val w: Float, val h: Float, val x: Float, val y: Float) : Drawable, Tickable {
	override fun draw(): Int = 2
	override fun name(): String = "Rect"
	override fun tick(dt: Float): Float = w * h * dt
}
