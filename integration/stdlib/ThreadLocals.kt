import kotlin.native.concurrent.ThreadLocal
import ktc2.concurrent.thread

// `@ThreadLocal` as on Kotlin/Native: each thread has its own value of the property and its own
// instance of the object, made the first time that thread uses them. The JVM has no such thing:
// ThreadLocals.expected holds what Kotlin/Native prints.

@ThreadLocal
var counter = 0

@ThreadLocal
val names = ArrayList<String>().also { println("names made") }

val shared = ArrayList<String>()

@ThreadLocal
object Cache {
	val entries = HashMap<String, Int>()

	init {
		println("cache made")
	}

	fun put(key: String, value: Int) {
		entries[key] = value
	}
}

fun main() {
	counter = 5
	names += "main"
	shared += "main"
	Cache.put("a", 1)
	val vWorker = thread {
		names += "worker"
		println("thread sees counter = $counter")
		counter += 2
		shared += "worker"
		println("thread names = $names")
		Cache.put("b", 2)
		println("thread cache = ${Cache.entries}")
	}
	vWorker.join()
	println("main counter = $counter")
	println("main names = $names")
	println("main cache = ${Cache.entries}")
	println("shared = $shared")
}
