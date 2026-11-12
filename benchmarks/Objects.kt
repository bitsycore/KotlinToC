// Benchmark: many small short-lived objects, fields read and written, a linked structure.

/** A point in the plane; made and dropped in large numbers. */
class Vec(val x: Double, val y: Double) {
	operator fun plus(inOther: Vec): Vec = Vec(x + inOther.x, y + inOther.y)
	operator fun times(inFactor: Double): Vec = Vec(x * inFactor, y * inFactor)
	fun dot(inOther: Vec): Double = x * inOther.x + y * inOther.y
}

/** A body that moves: holds two vectors and replaces them at each step. */
class Particle(var position: Vec, var velocity: Vec) {
	fun step(inDt: Double) {
		position = position + velocity * inDt
		if (position.x < 0.0 || position.x > 100.0) velocity = Vec(-velocity.x, velocity.y)
		if (position.y < 0.0 || position.y > 100.0) velocity = Vec(velocity.x, -velocity.y)
	}
}

/** One cell of a singly linked list. */
class Node(val value: Int, val next: Node?)

/** Builds a list of [inCount] cells and sums it. */
fun chain(inCount: Int): Long {
	var vHead: Node? = null
	for (vI in 0 until inCount) vHead = Node(vI, vHead)
	var vSum = 0L
	var vNode = vHead
	while (vNode != null) {
		vSum += vNode.value
		vNode = vNode.next
	}
	return vSum
}

fun main() {
	val vParticles = ArrayList<Particle>()
	for (vI in 0 until 1000) vParticles.add(Particle(Vec(vI % 100.0, (vI * 7) % 100.0), Vec((vI % 13) - 6.0, (vI % 7) - 3.0)))
	for (vStep in 0 until 10000) {
		for (vParticle in vParticles) vParticle.step(0.01)
	}
	var vEnergy = 0.0
	for (vParticle in vParticles) vEnergy += vParticle.velocity.dot(vParticle.velocity) + vParticle.position.x
	println(vEnergy)
	var vTotal = 0L
	for (vRound in 0 until 800) vTotal += chain(20000)
	println(vTotal)
}
