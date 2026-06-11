package com.bitsycore.ktc

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Tests for try/catch/finally/throw — the setjmp/longjmp lowering (KTC_TRY
 * macro family), catch type matching via TYPE_IDs, arena take, return/break
 * interplay, and the E130-E133 diagnostics.
 */
class ExceptionUnitTest : TranspilerTestBase() {

	// Local Throwable hierarchy so error-path tests don't need the full stdlib.
	private val kLocalThrowable = """
		interface Throwable { val message: String }
		interface Exception : Throwable
		class MyError(override val message: String) : Exception
	""".trimIndent()

	// ── Lowering shape ───────────────────────────────────────────────

	@Test fun basicTryCatchLowering() {
		val r = transpileMainWithStdlib("""
			try {
				throw RuntimeException("boom")
			} catch (e: RuntimeException) {
				println(e.message)
			}
		""")
		assertTrue(r.source.contains("KTC_TRY("), "try should open a KTC_TRY frame")
		assertTrue(r.source.contains("KTC_CATCH("), "catch should emit KTC_CATCH")
		assertTrue(r.source.contains("KTC_EXC_TYPE_ID() == ktc_RuntimeException\$Impl_TYPE_ID"),
			"catch condition should compare the in-flight TYPE_ID against the hierarchy's classes")
		assertTrue(r.source.contains("KTC_END_TRY;"), "construct should close with KTC_END_TRY")
		assertTrue(r.source.contains("ktc_core_exc_take("), "used binding should be taken off the arena")
		assertTrue(r.source.contains("ktc_core_alloca"), "message bytes should be alloca'd onto the frame")
	}

	@Test fun throwLowering() {
		val r = transpileMainWithStdlib("""
			throw RuntimeException("boom")
		""")
		assertTrue(r.source.contains("ktc_core_exc_throw("), "throw should call the runtime throw")
		assertTrue(r.source.contains("sizeof(ktc_RuntimeException\$Impl)"), "throw passes the concrete struct size")
		assertTrue(r.source.contains("offsetof(ktc_RuntimeException\$Impl, message)"),
			"throw passes the message field offset for relocation")
	}

	@Test fun catchByInterfaceMatchesAllImplementors() {
		val r = transpileMainWithStdlib("""
			try {
				throw IllegalStateException("bad")
			} catch (e: Exception) {
				println(e.message)
			}
		""")
		// The Exception catch must OR over the classes of the whole hierarchy.
		assertTrue(r.source.contains("ktc_IllegalStateException\$Impl_TYPE_ID"), "catch covers IllegalStateException")
		assertTrue(r.source.contains("ktc_RuntimeException\$Impl_TYPE_ID"), "catch covers RuntimeException")
		assertTrue(r.source.contains(".vt ="), "iface binding selects a vtable from the typeId")
		assertTrue(r.source.contains("__typeId"), "iface binding fills the union typeId")
	}

	@Test fun tryFinallyOnly() {
		val r = transpileMainWithStdlib("""
			try {
				println("body")
			} finally {
				println("cleanup")
			}
		""")
		assertTrue(r.source.contains("KTC_FINALLY("), "finally should emit KTC_FINALLY")
		assertTrue(!r.source.contains("KTC_CATCH("), "no catch clause expected")
	}

	@Test fun functionWithTryGetsDeoptAttribute() {
		val r = transpileMainWithStdlib("""
			try {
				println("x")
			} catch (e: Exception) {
				println("y")
			}
		""")
		assertTrue(r.source.contains("KTC_TRY_FN"),
			"a function containing try must carry the setjmp-safe attribute")
	}

	@Test fun unusedCatchBindingSkipsTake() {
		val r = transpileMainWithStdlib("""
			try {
				println("x")
			} catch (e: RuntimeException) {
				println("ignored")
			}
		""")
		assertTrue(!r.source.contains("ktc_core_exc_take("),
			"an unused catch binding must not emit the arena take")
	}

	@Test fun nestedTryUsesDistinctFrames() {
		val r = transpileMainWithStdlib("""
			try {
				try {
					println("inner")
				} catch (a: RuntimeException) {
					println("a")
				}
			} catch (b: RuntimeException) {
				println("b")
			}
		""")
		val vFrames = Regex("KTC_TRY\\((\\$\\w+)\\)").findAll(r.source).map { it.groupValues[1] }.toList()
		assertTrue(vFrames.size == 2 && vFrames[0] != vFrames[1],
			"nested tries must declare distinct frame variables, got $vFrames")
	}

	@Test fun rethrowCaughtBinding() {
		val r = transpileMainWithStdlib("""
			try {
				throw RuntimeException("boom")
			} catch (e: RuntimeException) {
				throw e
			}
		""")
		// RuntimeException is a class-hierarchy type → the binding is a fat value;
		// the rethrow dispatches on the concrete typeId for sizeof/offsetof.
		assertTrue(r.source.contains("switch (KTC_GET_TYPEID(e.__typeId))"),
			"interface-typed rethrow should switch on the concrete typeId")
		assertTrue(r.source.contains("ktc_core_exc_throw((ktc_RuntimeException\$Impl*)&e.data"),
			"rethrow should pass the catch-local copy back to the runtime throw")
	}

	// ── return / break interplay ─────────────────────────────────────

	@Test fun returnInsideTryPopsFrameAndRunsFinally() {
		val r = transpileMainWithStdlib(
			body = """
				val x = f()
				println(x)
			""",
			decls = """
				fun f(): Int {
					try {
						return 1
					} finally {
						println("cleanup")
					}
					return 0
				}
			""")
		assertTrue(r.source.contains("KTC_TRY_LEAVE("),
			"return inside try must pop the exception frame")
		// The finally body (a printf — literals are interned, so match the call shape)
		// must be re-emitted between the frame pop and the return.
		val vLeave   = r.source.indexOf("KTC_TRY_LEAVE(")
		val vReturn  = r.source.indexOf("return", vLeave)
		val vBetween = r.source.substring(vLeave, vReturn)
		assertTrue("printf" in vBetween,
			"finally body must be re-emitted between the frame pop and the return")
	}

	@Test fun breakInsideLoopInsideTryIsFine() {
		val r = transpileMainWithStdlib("""
			try {
				while (true) {
					break
				}
			} catch (e: RuntimeException) {
				println("x")
			}
		""")
		assertTrue(r.source.contains("break;"), "break targeting a loop inside the try is allowed")
	}

	// ── Diagnostics ──────────────────────────────────────────────────

	@Test fun breakAcrossTryBoundaryRefused() {
		transpileMainExpectError(
			body = """
				while (true) {
					try {
						break
					} finally {
						println("f")
					}
				}
			""",
			expectedMsg = "E132")
	}

	@Test fun continueAcrossTryBoundaryRefused() {
		transpileMainExpectError(
			body = """
				while (true) {
					try {
						continue
					} finally {
						println("f")
					}
				}
			""",
			expectedMsg = "E132")
	}

	@Test fun returnInsideFinallyRefused() {
		transpileMainExpectError(
			body = """
				try {
					println("x")
				} finally {
					return
				}
			""",
			expectedMsg = "E133")
	}

	@Test fun throwNonThrowableRefused() {
		transpileMainExpectError(
			body = """
				throw 42
			""",
			expectedMsg = "E130")
	}

	@Test fun catchNonThrowableTypeRefused() {
		transpileMainExpectError(
			body = """
				try {
					println("x")
				} catch (e: String) {
					println("y")
				}
			""",
			expectedMsg = "E131")
	}

	@Test fun throwClassNotImplementingThrowableRefused() {
		transpileMainExpectError(
			decls = """
				class NotAnError(val message: String)
			""",
			body = """
				throw NotAnError("nope")
			""",
			expectedMsg = "E130")
	}

	@Test fun localThrowableHierarchyWorksWithoutStdlib() {
		// The hierarchy is matched by name — a package-local Throwable works too.
		val r = transpileMain(
			decls = kLocalThrowable,
			body = """
				try {
					throw MyError("boom")
				} catch (e: Throwable) {
					println(e.message)
				}
			""")
		assertTrue(r.source.contains("ktc_core_exc_throw("))
	}

	@Test fun unreachableCatchWarns() {
		val r = transpileMainWithStdlib("""
			try {
				println("x")
			} catch (e: Exception) {
				println("broad")
			} catch (e: RuntimeException) {
				println("never reached")
			}
		""")
		assertTrue(r.warningCount > 0, "a catch fully covered by an earlier clause should warn")
	}

	@Test fun unreachableCodeAfterThrowWarns() {
		// Inside a nested block — the W024 check covers block bodies (top-level
		// function statements are exempt, same as for `return`).
		val r = transpileMainWithStdlib("""
			try {
				throw RuntimeException("x")
				println("dead")
			} catch (e: RuntimeException) {
				println("caught")
			}
		""")
		assertTrue(r.warningCount > 0, "code after throw should warn as unreachable")
	}
}
