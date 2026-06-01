package com.bitsycore.ktc

import kotlin.test.Test
import kotlin.test.assertTrue

/*
Tests for the "no implicit copy of value types" rule (E071).
P0 covers the explicit by-value annotation site (val d: Some = a) and the generalized .copy()
that makes the suggested fix work on any class (not just data classes).
*/
class CopyValueUnitTest : TranspilerTestBase() {

	// ── E071: explicit by-value annotation from a class lvalue ────────

	@Test fun explicitValueAnnotationFromClassLvalueErrors() {
		transpileExpectError("""
			package test.Main
			class Box(val x: Int)
			fun main(args: Array<String>) {
				val a = Box(1)
				val d: Box = a
			}
		""", "Implicit copy")
	}

	@Test fun explicitValueAnnotationFromDataClassLvalueErrors() {
		transpileExpectError("""
			package test.Main
			data class Pt(val x: Int, val y: Int)
			fun main(args: Array<String>) {
				val a = Pt(1, 2)
				val d: Pt = a
			}
		""", "Implicit copy")
	}

	// ── Explicit .copy() / fresh ctor are NOT implicit copies ─────────

	@Test fun copyMakesItExplicit() {
		// val d: Box = a.copy() — a.copy() is an rvalue (CallExpr), so no E071.
		transpileMain("""
			val a = Box(1)
			val d: Box = a.copy()
		""", decls = "class Box(val x: Int)")
	}

	@Test fun freshCtorIsNotACopy() {
		// val d: Box = Box(2) — rvalue ctor, never a copy.
		transpileMain("""
			val d: Box = Box(2)
		""", decls = "class Box(val x: Int)")
	}

	// ── P3: .copy() now works on a plain (non-data) class ─────────────

	@Test fun plainClassCopyEmitsValueCopy() {
		val r = transpileMain("""
			val a = Box(7)
			val b = a.copy()
		""", decls = "class Box(val x: Int)")
		// genDataClassCopy with no args yields the value itself → C struct copy on the decl.
		assertTrue(
			r.source.contains("test_Box b = ") || r.source.contains("Box b = "),
			"expected a value-copy declaration for b, got:\n${r.source}"
		)
	}

	// ── Primitives are exempt (no E071) ───────────────────────────────

	@Test fun primitiveValueAnnotationIsFine() {
		transpileMain("""
			val a = 5
			val d: Int = a
		""")
	}
}
