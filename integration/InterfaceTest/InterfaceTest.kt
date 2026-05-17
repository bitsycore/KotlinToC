package InterfaceTest

interface Shape {
    fun area(): Float
    fun name(): String
}

class Circle(private var radius: Float) : Shape {
    override fun area(): Float = 3.14159f * radius * radius
    override fun name(): String = "Circle"
}

class Square(private var side: Float) : Shape {
    override fun area(): Float = side * side
    override fun name(): String = "Square"
}

interface Resource {
    fun label(): String
}

class File(private var name: String) : Resource {
    override fun dispose() { println("closing file $name") }
    override fun label(): String = "File($name)"
}

// This should return a Union of Circle and Square
fun shapeReturnerById(id: Int): Shape {
    if (id % 2 == 0)
        return Circle(1.0f)
    else
        return Square(1.0f)
}

// This should return a Union of Circle and Square
fun shapeReturnerByIdInfer(id: Int): Shape {
    if (id % 2 == 0)
        return Circle(1.0f)
    else
        return Square(1.0f)
}

// if-expression in block body with return
fun shapeReturnerById2(id: Int): Shape {
    return if (id % 2 == 0)
        Circle(2.0f)
    else
        Square(2.0f)
}

// if-expression as expression body
fun shapeReturnerById3(id: Int): Shape = if (id % 2 == 0)
    Circle(3.0f)
else
    Square(3.0f)

// Infer Type
fun shapeReturnerById3Infer(id: Int) = if (id % 2 == 0)
    Circle(3.0f)
else
    Square(3.0f)

// when-expression as expression body
fun shapeReturnerById4(id: Int): Shape = when {
    id % 2 == 0 -> Circle(4.0f)
    else -> Square(4.0f)
}

// Infer Type
fun shapeReturnerById4Infer(id: Int) = when {
    id % 2 == 0 -> Circle(4.0f)
    else -> Square(4.0f)
}

// ═══════════════════════ @Ptr Interface helpers ══════════════════

fun testAtPtrAlloc(alloc: @Ptr Allocator) {
    val p: @Ptr Byte = alloc.allocMem(32)
    println("@Ptr allocMem(32): ok")
    alloc.freeMem(p)
    println("@Ptr freeMem: ok")
}

class AllocHolder(private val alloc: @Ptr Allocator) {
    fun test() {
        val p: @Ptr Byte = alloc.allocMem(64)
        println("Holder allocMem(64): ok")
        alloc.freeMem(p)
        println("Holder freeMem: ok")
    }

    override fun dispose() {
        println("Holder.dispose called")
    }
}

// ═══════════════════════ Main ═════════════════════════════════

fun main() {
    val c = Circle(2.0f)
    val s = Square(3.0f)

    println("${c.name()}: ${c.area()}")
    println("${s.name()}: ${s.area()}")
    if (c.name() != "Circle") error("FAIL Circle name")
    if (c.area() < 12.56f || c.area() > 12.57f) error("FAIL Circle area: ${c.area()}")
    if (s.name() != "Square") error("FAIL Square name")
    if (s.area() != 9.0f) error("FAIL Square area: ${s.area()}")

    val shape1 = shapeReturnerById(0)
    val shape2 = shapeReturnerById(1)
    shape1.dispose() // Should no op dispose
    shape2.dispose() // Should no op dispose
    println("${shape1.name()}: ${shape1.area()}")
    println("${shape2.name()}: ${shape2.area()}")
    if (shape1.name() != "Circle") error("FAIL shape1 should be Circle")
    if (shape2.name() != "Square") error("FAIL shape2 should be Square")

    // Test if-expression in block body with return
    val shape3 = shapeReturnerById2(0)
    val shape4 = shapeReturnerById2(1)
    println("if-return: ${shape3.name()}: ${shape3.area()}")
    println("if-return: ${shape4.name()}: ${shape4.area()}")
    if (shape3.name() != "Circle") error("FAIL shape3 should be Circle")
    if (shape4.name() != "Square") error("FAIL shape4 should be Square")

    // Test if-expression as expression body
    val shape5 = shapeReturnerById3(0)
    val shape6 = shapeReturnerById3(1)
    println("if-expr: ${shape5.name()}: ${shape5.area()}")
    println("if-expr: ${shape6.name()}: ${shape6.area()}")
    if (shape5.name() != "Circle") error("FAIL shape5 should be Circle")
    if (shape6.name() != "Square") error("FAIL shape6 should be Square")

    // Test when-expression as expression body
    val shape7 = shapeReturnerById4(0)
    val shape8 = shapeReturnerById4(1)
    println("when-expr: ${shape7.name()}: ${shape7.area()}")
    println("when-expr: ${shape8.name()}: ${shape8.area()}")
    if (shape7.name() != "Circle") error("FAIL shape7 should be Circle")
    if (shape8.name() != "Square") error("FAIL shape8 should be Square")

    // Test inferred return type — if-expression
    val shape9 = shapeReturnerById3Infer(0)
    val shape10 = shapeReturnerById3Infer(1)
    println("if-infer: ${shape9.name()}: ${shape9.area()}")
    println("if-infer: ${shape10.name()}: ${shape10.area()}")
    if (shape9.name() != "Circle") error("FAIL shape9 should be Circle")
    if (shape10.name() != "Square") error("FAIL shape10 should be Square")

    // Test inferred return type — when-expression
    val shape11 = shapeReturnerById4Infer(0)
    val shape12 = shapeReturnerById4Infer(1)
    println("when-infer: ${shape11.name()}: ${shape11.area()}")
    println("when-infer: ${shape12.name()}: ${shape12.area()}")
    if (shape11.name() != "Circle") error("FAIL shape11 should be Circle")
    if (shape12.name() != "Square") error("FAIL shape12 should be Square")

    // Interface as parameter (direct, not nullable)
    val shape: Shape = c
    println("shape area: ${shape.area()}")
    if (shape.area() < 12.56f || shape.area() > 12.57f) error("FAIL shape area via interface: ${shape.area()}")

    // Disposable-like interface
    val f = File("data.txt")
    println(f.label())
    f.dispose()

    // dispose through interface (vtable dispatch)
    val r: Resource = f
    println("dispose via Resource interface:")
    r.dispose()

    // dispose on non-override class (no-op via macro)
    println("dispose on Circle (should be no-op):")
    c.dispose()

    // ═══════════════════════ @Ptr Interface dispatch ══════════════
    println()
    println("=== @Ptr Interface ===")
    testAtPtrAlloc(Heap)

    val holder = AllocHolder(Heap)
    holder.test()
    holder.dispose()

    println("done")
}
