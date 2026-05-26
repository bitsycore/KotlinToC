package core

interface Drawable {
	fun draw(): Int
	fun name(): String
}

interface Tickable {
	fun tick(dt: Float): Float
}

data class Sprite(val id: Int, val x: Float, val y: Float) : Drawable {
	override fun draw(): Int = id
	override fun name(): String = "Sprite"
}
