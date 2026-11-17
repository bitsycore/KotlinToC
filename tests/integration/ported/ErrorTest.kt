package ErrorTest

data class Config(val name: String, val value: Int)

fun requirePositive(n: Int): Int {
    require(n > 0) { "n must be positive, got $n" }
    return n
}

fun checkRange(v: Int, lo: Int, hi: Int) {
    check(v >= lo) { "$v < $lo" }
    check(v <= hi) { "$v > $hi" }
}

fun main() {
    // check(true)
    check(true)
    println("check(true): ok")

    // check with lazy message
    check(1 + 1 == 2, { "math broke" })
    println("check(1+1==2): ok")

    // require with lazy message
    require(10 > 5, { "number compare broke" })
    println("require(10>5): ok")

    // require with string length
    require("abc".length == 3, { "string length wrong" })
    println("require(length==3): ok")

    // checkNotNull
    val a: Int? = 99
    val aa: Int = checkNotNull(a)
    if (aa != 99) error("checkNotNull should return 99")
    println("checkNotNull(a): $aa")

    // checkNotNull with smart cast
    val b: Int? = 77
    val bb: Int = checkNotNull(b) { "b is null" }
    if (bb != 77) error("checkNotNull with lazyMsg")
    val b2: Int = b
    if (b2 != 77) error("smart cast failed for b, got $b2")
    println("checkNotNull smart cast: $b2")

    // requireNotNull
    val c: Float? = 3.14f
    val cc: Float = requireNotNull(c)
    if (cc != 3.14f) error("requireNotNull should return 3.14")
    val c2: Float = c
    if (c2 != 3.14f) error("smart cast failed for c")
    println("requireNotNull(c): $cc")

    // checkNotNull with String
    val s: String? = "hello"
    val t: String = checkNotNull(s) { "missing string" }
    if (t != "hello") error("checkNotNull string failed")
    val t2: String = s
    if (t2 != "hello") error("smart cast string failed")
    println("checkNotNull string: $t2")

    // requirePositive helper
    val pos = requirePositive(42)
    if (pos != 42) error("FAIL requirePositive: $pos")
    println("requirePositive(42): $pos")

    // checkRange helper
    checkRange(5, 0, 10)
    checkRange(0, 0, 10)
    checkRange(10, 0, 10)
    println("checkRange: ok")

    // Multiple check/require in sequence
    val cfg = Config("test", 100)
    require(cfg.name.length > 0) { "name empty" }
    require(cfg.value > 0) { "value must be positive" }
    check(cfg.name == "test") { "wrong name" }
    check(cfg.value == 100) { "wrong value" }
    println("Config checks: ok")

    // requireNotNull on data class
    val vc: Config? = Config("prod", 200)
    val vcc: Config = requireNotNull(vc) { "config missing" }
    if (vcc.name != "prod") error("FAIL config name: ${vcc.name}")
    if (vcc.value != 200) error("FAIL config value: ${vcc.value}")
    println("requireNotNull config: ${vcc.name}=${vcc.value}")

    // Boolean checks
    val flag = true
    check(flag) { "flag should be true" }
    require(flag) { "flag required" }
    println("boolean checks: ok")

    println("ALL OK")
}
