package actors

import core.*

data class Player(val hp: Int, val name_: String, val speed: Float) : Drawable, Tickable {
	override fun draw(): Int = 100 + hp
	override fun name(): String = name_
	override fun tick(dt: Float): Float = speed * dt
}

data class Enemy(val kind: Int, val dmg: Float) : Drawable, Tickable {
	override fun draw(): Int = 200 + kind
	override fun name(): String = "Enemy"
	override fun tick(dt: Float): Float = dmg * dt
}

data class Particle(val life: Float, val vx: Float, val vy: Float, val size: Byte) : Tickable {
	override fun tick(dt: Float): Float = life - dt
}
