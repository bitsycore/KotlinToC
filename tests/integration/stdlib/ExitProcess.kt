import kotlin.system.exitProcess

/** `exitProcess` ends the program where it is called: what follows does not run, `finally` blocks included. */
fun stop(): Nothing {
	try {
		println("before")
		exitProcess(0)
	} finally {
		println("finally: not printed")
	}
}

fun main() {
	println("start")
	val values = listOf(1, 2, 3)
	for (v in values) {
		if (v == 2) stop()
		println(v)
	}
	println("not reached")
}
