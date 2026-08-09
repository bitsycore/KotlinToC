package com.bitsycore.ktc

import kotlin.test.Test

class LambdaInlineUnitTest : TranspilerTestBase() {

    @Test fun inlineFunBasicExpansion() {
        val r = transpile("""
            package test.Main
            inline fun twice(x: Int): Int = x * 2
            fun main(args: Array<String>) {
                val r = twice(5)
            }
        """)
        r.sourceContains("/* inline twice(x = 5): Int */")
        r.sourceContains("_x = 5;")
        // Collapsed: no goto label, no $ir temp
        r.sourceNotContains("\$end_ir_")
        r.sourceNotContains("\$ir")
        r.sourceNotContains("test_Main_twice")
    }

    @Test fun inlineFunWithBlockBody() {
        val r = transpile("""
            package test.Main
            inline fun greet(name: String): String {
                val msg = "Hello, " + name
                return msg
            }
            fun main(args: Array<String>) {
                val g = greet("World")
            }
        """)
        r.sourceContains("/* inline greet(name = \"World\"): String */")
        r.sourceContains("_name = ktc_core_str(\"World\");")
    }

    @Test fun inlineFunWithReturn() {
        val r = transpile("""
            package test.Main
            inline fun firstPositive(a: Int, b: Int): Int {
                if (a > 0) return a
                return b
            }
            fun main(args: Array<String>) {
                val r = firstPositive(-1, 5)
            }
        """)
        r.sourceContains("\$end_ir_")
    }

    @Test fun lambdaAsInlineArg() {
        val r = transpile("""
            package test.Main
            inline fun foo(x: Int, block: (Int) -> Int): Int {
                return block(x)
            }
            fun main(args: Array<String>) {
                val r = foo(5) { it * 2 }
            }
        """)
        r.sourceContains("/* inline foo(x = 5, block = Fun(Int)->Int): Int */")
        r.sourceContains("_x = 5;")
    }

    @Test fun lambdaWithExplicitParams() {
        val r = transpile("""
            package test.Main
            inline fun combine(a: Int, b: Int, fn: (Int, Int) -> Int): Int {
                return fn(a, b)
            }
            fun main(args: Array<String>) {
                val r = combine(3, 7) { x, y -> x + y }
            }
        """)
        r.sourceContains("/* inline combine(a = 3, b = 7, fn = Fun(Int,Int)->Int): Int */")
        r.sourceContains("_a = 3;")
        r.sourceContains("_b = 7;")
    }

    @Test fun inlineFunNotEmittedAsStandalone() {
        val r = transpile("""
            package test.Main
            inline fun square(x: Int): Int = x * x
            fun main(args: Array<String>) {
                val r = square(4)
            }
        """)
        r.sourceNotContains("test_Main_square")
    }

    @Test fun standaloneTypedLambdaLowersToClosure() {
        // A value-position lambda assigned to a val is a general (frame-bound) closure: it lowers to a
        // per-lambda functor struct + a generated invoke function, and f(x) dispatches through it. The
        // type is inferred from the lambda's own typed parameters when the variable has no annotation.
        val r = transpile("""
            package test.Main
            fun main(args: Array<String>) {
                val f = { x: Int -> x * 2 }
                val y = f(21)
            }
        """)
        r.sourceContains("_invoke")          // generated functor invoke
        r.sourceContains("Closure")           // per-lambda functor struct
    }

    @Test fun standaloneUninferableLambdaErrors() {
        // Without a variable annotation AND without typed parameters there is nothing to infer the
        // closure type from, so it is rejected with a fix-it (annotate the param or the variable).
        transpileExpectError("""
            package test.Main
            fun main(args: Array<String>) {
                val f = { x -> x * 2 }
            }
        """, "Cannot infer the type of lambda")
    }

    @Test fun safeDotRefValueWriteOnPrimitiveRef() {
        // p?.refValue = x on a Ref<Int>? must write through (*p = x), not treat refValue as a field.
        val r = transpile("""
            package test.Main
            fun main(args: Array<String>) {
                var n = 5
                val p: Ref<Int>? = n.asRef()
                p?.refValue = 42
            }
        """)
        r.sourceContains("*p = 42")
        r.sourceNotContains("->refValue")
    }

    @Test fun captureAsRefOnAlreadyRefErrors() {
        // capture(x.asRef()) where x is already a Ref<T> would capture a pointer-to-pointer - rejected.
        transpileExpectError("""
            package test.Main
            fun main(args: Array<String>) {
                var n = 5
                val r: Ref<Int> = n.asRef()
                val f: () -> Unit = { capture(r.asRef()); r.refValue = 1 }
            }
        """, "already a reference")
    }

    @Test fun captureNameCollidesWithParamErrors() {
        // A capture sharing a name with a closure parameter would emit a duplicate C declaration.
        transpileExpectError("""
            package test.Main
            fun main(args: Array<String>) {
                val x = 10
                val f: (Int) -> Int = { x -> capture(x); x + 1 }
            }
        """, "collides with the closure parameter")
    }

    @Test fun itImplicitParamWrongArityErrors() {
        // `it` is only the implicit parameter when the expected type has exactly one parameter.
        transpileExpectError("""
            package test.Main
            fun run0(f: () -> Int): Int = f()
            fun main(args: Array<String>) {
                val r = run0({ it + 1 })
            }
        """, "implicit parameter only when")
    }

    @Test fun heapPromoteClosureViaCopyWith() {
        // closure.copyWith(Heap) heap-promotes a frame-bound functor → a Ref<(Int)->Int> (a heap
        // ktc_Closure*); callable via the cast-erased invoke and returnable/storable.
        val r = transpile("""
            package test.Main
            fun makeAdder(base: Int): Ref<(Int) -> Int> {
                val c = { x: Int -> capture(base); x + base }
                return c.copyWith(Heap)
            }
            fun main(args: Array<String>) {
                val g: Ref<(Int) -> Int> = makeAdder(10)
                val y = g(5)
                Heap.freeMem(g)
            }
        """)
        r.sourceContains("ktc_Closure* test_Main_makeAdder")   // returns a heap reference - the escape
        r.sourceContains("_invoke_erased")            // erased trampoline stored in the fat pointer
        r.sourceContains("g->invoke")                 // called through the heap fat pointer
    }

    @Test fun heapClosureStoredInFieldIsCallable() {
        // A heap closure stored in a class field is callable through the field: obj.f(x).
        val r = transpile("""
            package test.Main
            class Handler(val f: Ref<(Int) -> Int>)
            fun main(args: Array<String>) {
                val base = 10
                val c = { x: Int -> capture(base); x + base }
                val h = Handler(c.copyWith(Heap))
                val y = h.f(5)
                Heap.freeMem(h.f)
            }
        """)
        r.sourceContains("->invoke")   // dispatched through the field's fat pointer
    }

    @Test fun frameBoundClosureToHeapRefErrors() {
        // Storing a frame-bound functor in a Ref<(Int)->Int> must heap-promote (.copyWith), not .asRef()
        // (which would point at the dying stack functor). The fix-it names .copyWith(allocator).
        transpileExpectError("""
            package test.Main
            fun main(args: Array<String>) {
                val base = 10
                val c = { x: Int -> capture(base); x + base }
                val g: Ref<(Int) -> Int> = c
            }
        """, ".copyWith(allocator)")
    }

    @Test fun bareFunctionTypeReturnErrors() {
        // Returning a bare (frame-bound) function type is refused - return Ref<(Int)->Int> (heap closure).
        transpileExpectError("""
            package test.Main
            fun makeAdder(base: Int): (Int) -> Int {
                val c = { x: Int -> capture(base); x + base }
                return c.copyWith(Heap)
            }
        """, "bare function type")
    }

    @Test fun heapPromoteByRefCaptureErrors() {
        // A closure that captured a stack local by reference can't be heap-promoted (would dangle).
        transpileExpectError("""
            package test.Main
            fun main(args: Array<String>) {
                var n = 5
                val c = { x: Int -> capture(n.asRef()); x + n.refValue }
                val g = c.copyWith(Heap)
            }
        """, "captured a local by reference")
    }

    @Test fun stdlibLetExpansion() {
        val r = transpileMainWithStdlib("""
            val r = "hello".let { it.length }
        """)
        r.sourceContains("/* inline ")
        r.sourceContains("let(block = Fun(T)->R): R */")
        r.sourceContains("\$end_ir_")
    }

    @Test fun stdlibApplyExpansion() {
        // apply's body receives the receiver as `this`. Use a method that resolves via
        // the explicit `this.` chain so the inline expansion can compile end-to-end.
        val r = transpileMainWithStdlib("""
            val buf = CharArray(64)
            val sb = StringBuffer(buf.asRef(), 0).apply {
                this.append("a")
            }
        """)
        r.sourceContains("/* inline ")
        r.sourceContains("apply(block = Fun(T|)->Unit): T */")
    }

    @Test fun stdlibRunExpansion() {
        val r = transpileMainWithStdlib("""
            val r = run { 42 }
        """)
        r.sourceContains("/* inline run(")
    }

    @Test fun stdlibWithExpansion() {
        val r = transpileMainWithStdlib("""
            val sb = StringBuilder()
            val len = with(sb) {
                length
            }
        """)
        r.sourceContains("/* inline with(")
    }

    @Test fun stdlibAlsoExpansion() {
        val r = transpileMainWithStdlib("""
            val r = "test".also { it.length }
        """)
        r.sourceContains("/* inline ")
        r.sourceContains("also(block = Fun(T)->Unit): T */")
    }

    @Test fun stdlibTakeIfExpansion() {
        val r = transpileMainWithStdlib("""
            val r = 42.takeIf { it > 0 }
        """)
        r.sourceContains("/* inline ")
        r.sourceContains("takeIf(predicate = Fun(T)->Boolean): T */")
    }

    @Test fun stdlibRepeatExpansion() {
        val r = transpileMainWithStdlib("""
            var sum = 0
            repeat(3) { sum += it }
        """)
        r.sourceContains("/* inline repeat(")
    }
}
