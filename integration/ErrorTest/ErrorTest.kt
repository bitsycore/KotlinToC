package ErrorTest

fun main() {
    // ── check(value: Boolean) ────────────────────────────────────────
    check(true)
    println("check(true): ok")

    // ── check(value, lazyMessage) ────────────────────────────────────
    check(1 + 1 == 2, { "math broke" })
    println("check(1+1==2): ok")

    // ── require(value, lazyMessage) ──────────────────────────────────
    require(10 > 5, { "number compare broke" })
    println("require(10>5): ok")

    // ── require(value, lazyMessage) with string length ───────────────
    require("abc".length == 3, { "string length wrong" })
    println("require(length==3): ok")

    // ── checkNotNull(value: T?) ──────────────────────────────────────
    val a: Int? = 99
    val aa: Int = checkNotNull(a)
    if (aa != 99) error("checkNotNull should return 99")
    println("checkNotNull(a): $aa")

    // ── checkNotNull smart cast ──────────────────────────────────────
    val b: Int? = 77
    val bb: Int = checkNotNull(b) { "b is null" }
    if (bb != 77) error("checkNotNull with lazyMsg")
    val b2: Int = b  // smart cast: b is now non-null
    if (b2 != 77) error("smart cast failed for b, got $b2")
    println("checkNotNull smart cast: $b2")

    // ── requireNotNull(value: T?) ────────────────────────────────────
    val c: Float? = 3.14f
    val cc: Float = requireNotNull(c)
    if (cc != 3.14f) error("requireNotNull should return 3.14")
    val c2: Float = c
    if (c2 != 3.14f) error("smart cast failed for c")
    println("requireNotNull(c): $cc")

    // ── checkNotNull with String ─────────────────────────────────────
    val s: String? = "hello"
    val t: String = checkNotNull(s) { "missing string" }
    if (t != "hello") error("checkNotNull string failed")
    val t2: String = s
    if (t2 != "hello") error("smart cast string failed")
    println("checkNotNull string: $t2")

    println("ALL OK")
}
