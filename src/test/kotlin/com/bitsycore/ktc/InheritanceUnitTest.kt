package com.bitsycore.ktc

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Tests for class inheritance (open/abstract/sealed) — the InheritDesugar pass:
 * interface + $Impl synthesis, child augmentation, ctor-call rewriting, and
 * the v1 diagnostics.
 */
class InheritanceUnitTest : TranspilerTestBase() {

	@Test fun openClassBecomesInterfacePlusImpl() {
		val r = transpile("""
			package test
			open class Animal(val name: String) {
				open fun sound(): Int = 0
			}
			fun main() {
				val a = Animal("x")
				println(a.sound())
			}
		""")
		// The ctor call is rewritten to the hidden concrete class.
		assertTrue(r.source.contains("test_Animal\$Impl_primaryConstructor"), "ctor rewrites to \$Impl")
		// The interface vtable exists and Animal$Impl implements it.
		assertTrue(r.header.contains("test_Animal\$Impl_as_Animal") || r.source.contains("test_Animal\$Impl_as_Animal"),
			"\$Impl implements the synthesized interface")
	}

	@Test fun childInheritsFieldsAndMethods() {
		val r = transpile("""
			package test
			open class Animal(val name: String, val legs: Int = 4) {
				open fun sound(): Int = 0
				fun describe(): Int = legs
			}
			class Dog(name: String, val breed: String) : Animal(name) {
				override fun sound(): Int = 1
			}
			fun main() {
				val d = Dog("rex", "lab")
				println(d.name)
				println(d.describe())
			}
		""")
		// Dog's struct carries the inherited fields (name from super-arg, legs from default).
		val vDogStruct = r.header.substringAfter("KTC_TYPE_NAME test_Dog\n").substringBefore("#undef")
		assertTrue(r.header.contains("ktc_String name") || vDogStruct.contains("name"), "Dog stores inherited 'name'")
		assertTrue(r.source.contains("test_Dog_describe"), "Dog inherits the concrete method body")
		assertTrue(r.source.contains("test_Dog_sound"), "Dog emits its override")
	}

	@Test fun abstractClassHasNoImplAndCannotInstantiate() {
		val r = transpile("""
			package test
			abstract class Shape(val sides: Int) {
				abstract fun area(): Float
			}
			class Rect(val w: Float) : Shape(4) {
				override fun area(): Float = w * w
			}
			fun main() {
				val s: Shape = Rect(2.0f)
				println(s.area())
			}
		""")
		assertTrue(!r.source.contains("Shape\$Impl"), "abstract class must not get an \$Impl")
		transpileExpectError("""
			package test
			abstract class Shape(val sides: Int)
			fun main() {
				val s = Shape(1)
			}
		""", "Cannot instantiate")
	}

	@Test fun extendingFinalClassRefused() {
		transpileExpectError("""
			package test
			class Base(val x: Int)
			class Child(x: Int) : Base(x)
			fun main() {}
		""", "final")
	}

	@Test fun overridingFinalMethodRefused() {
		transpileExpectError("""
			package test
			open class Base {
				fun work(): Int = 1
			}
			class Child : Base() {
				override fun work(): Int = 2
			}
			fun main() {}
		""", "final")
	}

	@Test fun redeclaringInheritedPropRefused() {
		transpileExpectError("""
			package test
			open class Base(val x: Int)
			class Child(val x: Int) : Base(x)
			fun main() {}
		""", "already a stored property")
	}

	@Test fun superMethodCallLowersToParentCopy() {
		val r = transpile("""
			package test
			open class Base {
				open fun work(): Int = 1
			}
			class Child : Base() {
				override fun work(): Int = super.work() + 1
			}
			fun main() { println(Child().work()) }
		""")
		// super.work() rewrites to a private level-qualified copy of Base's body.
		assertTrue(r.source.contains("work\$super\$Base"), "super call targets the parent's copied body")
	}

	@Test fun superWithoutParentRefused() {
		transpileExpectError("""
			package test
			class Lonely {
				fun work(): Int = super.work() + 1
			}
			fun main() {}
		""", "no parent class")
	}

	@Test fun missingSuperArgWithoutDefaultRefused() {
		transpileExpectError("""
			package test
			open class Base(val x: Int)
			class Child : Base()
			fun main() {}
		""", "missing super-constructor argument")
	}

	@Test fun sealedClassWhenExhaustive() {
		val r = transpile("""
			package test
			sealed class Node(val id: Int)
			class Leaf(id: Int, val v: Int) : Node(id)
			class Branch(id: Int) : Node(id)
			fun weight(n: Node): Int = when (n) {
				is Leaf   -> n.v
				is Branch -> 100
			}
			fun main() { println(weight(Leaf(1, 2))) }
		""")
		// No exhaustiveness warning expected — both subclasses covered.
		assertTrue(r.source.contains("test_Leaf_TYPE_ID"), "when dispatches on subclass typeIds")
	}
}
