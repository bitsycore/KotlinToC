package test

class Outer {
	class Inner(val x: Int)
	class Helper(val name: String)

	class Container(val inner: Outer.Inner) {
		fun value(): Int = inner.x
	}
}

data class Vec2(val x: Float, val y: Float) {
	class NestedPair(val a: Int, val b: Int)
	data class NestedData(val label: String, val value: Int)
}

class A {
	class B {
		class C(val v: Int) {
			fun doubled(): Int = v * 2
		}
	}
}

class Tree {
	class Node(val value: Int, var left: Int, var right: Int)
}

class Config {
	class Display(val width: Int, val height: Int) {
		fun area(): Int = width * height
	}
	class Audio(val volume: Int, val muted: Boolean)
}

fun main() {
	// Simple nested class
	val inner = Outer.Inner(42)
	if (inner.x != 42) fatalError("FAIL simple nested x=${inner.x}")
	println("Outer.Inner: ${inner.x}")

	val h = Outer.Helper("test")
	if (h.name != "test") fatalError("FAIL nested string name=${h.name}")
	println("Outer.Helper: ${h.name}")

	// Nested class containing another nested class
	val container = Outer.Container(Outer.Inner(99))
	if (container.value() != 99) fatalError("FAIL container value=${container.value()}")
	println("Container.value: ${container.value()}")

	// Nested inside data class
	val np = Vec2.NestedPair(1, 2)
	if (np.a != 1 || np.b != 2) fatalError("FAIL data class nested a=${np.a} b=${np.b}")
	println("Vec2.NestedPair: ${np.a}, ${np.b}")

	// Nested data class inside data class
	val nd = Vec2.NestedData("score", 100)
	if (nd.label != "score" || nd.value != 100) fatalError("FAIL nested data: $nd")
	println("Vec2.NestedData: $nd")

	// Deeply nested with method
	val deep = A.B.C(99)
	if (deep.v != 99) fatalError("FAIL deeply nested v=${deep.v}")
	if (deep.doubled() != 198) fatalError("FAIL deep method=${deep.doubled()}")
	println("A.B.C: ${deep.v}, doubled=${deep.doubled()}")

	// Tree node
	val node = Tree.Node(10, -1, -1)
	if (node.value != 10) fatalError("FAIL node value=${node.value}")
	node.left = 5
	node.right = 15
	if (node.left != 5 || node.right != 15) fatalError("FAIL node children")
	println("Tree.Node: ${node.value} left=${node.left} right=${node.right}")

	// Config-style nested classes
	val display = Config.Display(1920, 1080)
	if (display.area() != 2073600) fatalError("FAIL display area=${display.area()}")
	println("Config.Display: ${display.width}x${display.height} area=${display.area()}")

	val audio = Config.Audio(80, false)
	if (audio.volume != 80 || audio.muted) fatalError("FAIL audio")
	println("Config.Audio: vol=${audio.volume} muted=${audio.muted}")

	// Multiple instances of same nested type
	val i1 = Outer.Inner(10)
	val i2 = Outer.Inner(20)
	val i3 = Outer.Inner(30)
	if (i1.x + i2.x + i3.x != 60) fatalError("FAIL sum=${i1.x + i2.x + i3.x}")
	println("sum of 3 inners: ${i1.x + i2.x + i3.x}")

	println("ALL OK")
}
