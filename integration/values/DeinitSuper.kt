// A `deinit` that overrides the one of a superclass runs in its place, as any override does:
// ktc2 warns when it does not call `super.deinit()` (DeinitSuper.warnings). The JVM never calls
// `deinit`, so the output is compared with DeinitSuper.expected.

import ktc2.Deinit

open class Base : Deinit {
	override fun deinit() {
		println("base")
	}
}

class Good : Base() {
	override fun deinit() {
		println("good")
		super.deinit()
	}
}

class Forgetful : Base() {
	override fun deinit() {
		println("forgetful")
	}
}

class Plain : Base()

fun main() {
	Good()
	Forgetful()
	Plain()
}
