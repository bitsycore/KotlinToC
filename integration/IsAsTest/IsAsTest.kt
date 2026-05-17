package IsAsTest

// ==================
// MARK: Hierarchy
// ==================

interface Shape {
    fun area(): Float
    fun name(): String
}

interface Labeled {
    fun label(): String
}

class Circle(val radius: Float) : Shape, Labeled {
    override fun area(): Float = radius * radius * 3.14f
    override fun name(): String = "Circle"
    override fun label(): String = "circle-label"
}

class Rectangle(val width: Float, val height: Float) : Shape, Labeled {
    override fun area(): Float = width * height
    override fun name(): String = "Rectangle"
    override fun label(): String = "rect-label"
}

class Triangle(val base: Float, val height: Float) : Shape {
    override fun area(): Float = base * height * 0.5f
    override fun name(): String = "Triangle"
}

// ==================
// MARK: Extension functions on interface
// ==================

fun Shape.printArea() {
    println("area=${area()}")
}

fun Shape.classify(): String {
    return when (this) {
        is Circle    -> "round r=${radius}"
        is Rectangle -> "${width}x${height}"
        is Triangle  -> "tri b=${base}"
        else         -> "unknown"
    }
}

// ==================
// MARK: Helpers
// ==================

fun printShape(inS: Shape) {
    println(inS.name())
    inS.printArea()
}

fun makeShape(inKind: Int): Shape {
    if (inKind == 0) return Circle(5.0f) as Shape
    if (inKind == 1) return Rectangle(4.0f, 3.0f) as Shape
    return Triangle(6.0f, 2.0f) as Shape
}

// ==================
// MARK: Main
// ==================

fun main() {

    // ── Vtable dispatch ───────────────────────────────────────────────
    val vC = Circle(3.0f)
    val vR = Rectangle(4.0f, 5.0f)
    val vT = Triangle(6.0f, 4.0f)
    println(vC.name())
    println(vR.name())
    println(vT.name())

    // ── as cast to interface, vtable call ─────────────────────────────
    val vSC: Shape = vC as Shape
    val vSR: Shape = vR as Shape
    val vST: Shape = vT as Shape
    println(vSC.name())
    println(vSR.name())
    println(vST.name())

    // ── is check on interface-typed variable ──────────────────────────
    if (vSC is Circle) {
        println("is Circle ok")
    }
    if (vSC is Rectangle) {
        println("WRONG: should not reach")
    }

    // ── !is check ─────────────────────────────────────────────────────
    if (vSC !is Rectangle) {
        println("not Rectangle ok")
    }

    // ── is smart-cast: access class-specific field after check ────────
    if (vSC is Circle) {
        println("r=${vSC.radius}")
    }
    if (vSR is Rectangle) {
        println("w=${vSR.width} h=${vSR.height}")
    }

    // ── when dispatch with is branches and smart cast ─────────────────
    val vShapes: Array<Shape> = arrayOf(vSC, vSR, vST)
    for (vS in vShapes) {
        val vDesc = when (vS) {
            is Circle    -> "C r=${vS.radius}"
            is Rectangle -> "R ${vS.width}x${vS.height}"
            is Triangle  -> "T b=${vS.base}"
            else         -> "?"
        }
        println(vDesc)
    }

    // ── Extension on interface via interface variable ─────────────────
    vSC.printArea()
    vSR.printArea()

    // ── classify: extension uses when + is + smart cast ───────────────
    println(vSC.classify())
    println(vSR.classify())
    println(vST.classify())

    // ── makeShape: interface returned from function ───────────────────
    val vFromFn: Shape = makeShape(0)
    println(vFromFn.name())
    println(vFromFn.classify())
    vFromFn.printArea()

    // ── as? safe cast success ─────────────────────────────────────────
    val vMaybeC = (vFromFn as? Circle)
    if (vMaybeC != null) {
        println("safe cast ok r=${vMaybeC.radius}")
    }

    // ── as? safe cast failure ─────────────────────────────────────────
    val vMaybeR = (vFromFn as? Rectangle)
    if (vMaybeR == null) {
        println("safe cast null ok")
    }

    // ── Pass interface to function ────────────────────────────────────
    printShape(vSR)

    // ── Multiple interfaces: cast to second interface ─────────────────
    val vL: Labeled = vC as Labeled
    println(vL.label())
    val vLR: Labeled = vR as Labeled
    println(vLR.label())

    // ── is check on nullable String ───────────────────────────────────
    val vNullable: String? = "hello"
    if (vNullable is String) {
        println("nullable is String: ${vNullable.length}")
    }

    val vNull: String? = null
    if (vNull is String) {
        println("WRONG: null should not be String")
    }

    // ── is check on nullable String false branch ──────────────────────
    if (vNull !is String) {
        println("null !is String ok")
    }

    // ── error() exit on wrong branch ──────────────────────────────────
    if (vSC !is Circle) {
        error("expected Circle")
    }
    if (vSR !is Rectangle) {
        error("expected Rectangle")
    }
    if (vST !is Triangle) {
        error("expected Triangle")
    }

    // ── error() exit on failed as? cast ───────────────────────────────
    val vBadCast = (vSC as? Rectangle)
    if (vBadCast != null) {
        error("expected null from bad as?")
    }

    println("done")
}
