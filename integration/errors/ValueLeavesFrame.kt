import ktc2.Size
import ktc2.Value

// A local marked @Value or @Size has to stay in the frame: one that could outlive its block
// is refused, where an unmarked one would quietly go to the heap.

class Particle(var x: Float, var y: Float)

val gKept = ArrayList<Any>()

fun kept(): Int {
	val vParticle: @Value Particle = Particle(1f, 2f)
	gKept.add(vParticle)
	return gKept.size
}

fun returned(): IntArray {
	@Size(16) val vBuffer = IntArray(16)
	vBuffer[0] = 1
	return vBuffer
}

fun main() {
	println(kept() + returned().size)
}
