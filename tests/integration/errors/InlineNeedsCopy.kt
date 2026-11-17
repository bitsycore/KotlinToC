import ktc2.Value

// A field held in place is part of its owner. A second long-lived name for a mutable one
// would be another object than on the JVM, where both names are the same object: ktc2 asks
// for the copy to be written.

class Counter(var count: Int)

class Owner {
	val hits: @Value Counter = Counter(0)
}

val gCounters = ArrayList<Counter>()

fun share(inOwner: Owner) {
	gCounters.add(inOwner.hits)
}

fun main() {
	val vOwner = Owner()
	share(vOwner)
	vOwner.hits.count++
	println(gCounters[0].count)
}
