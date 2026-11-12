import ktc2.Deinit
import ktc2.Struct

// A value that is copied has no last reference: it cannot have a deinit.

@Struct
class Point(val x: Int) : Deinit {
	override fun deinit() {
		println("never")
	}
}

fun main() {
	val vAny: Any = Point(1)
	println(vAny is Deinit)
}
