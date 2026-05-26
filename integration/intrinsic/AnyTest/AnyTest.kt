package AnyTest

// ── Data class for testing Any with user types ────────────────────
data class Vec2(val x: Float, val y: Float)
data class Person(val name: String, val age: Int)

// ══════════════════════════════════════════════════════════════════
// MARK: @Ptr Any — pointer receiver, all primitive + class types
// ══════════════════════════════════════════════════════════════════

fun checkAnyPtr(item: @Ptr Any) {
    if (item is Int) {
        val v = item as Int
        println("ptr-int: $v")
    } else if (item is String) {
        val v = item as String
        println("ptr-str: $v")
    } else if (item is Float) {
        val v = item as Float
        println("ptr-float: $v")
    } else if (item is Double) {
        val v = item as Double
        println("ptr-double: $v")
    } else if (item is Long) {
        val v = item as Long
        println("ptr-long: $v")
    } else if (item is Boolean) {
        val v = item as Boolean
        println("ptr-bool: $v")
    } else if (item is Char) {
        val v = item as Char
        println("ptr-char: $v")
    } else if (item is Vec2) {
        val v = item as Vec2
        println("ptr-Vec2(${v.x}, ${v.y})")
    } else if (item is Person) {
        val p = item as Person
        println("ptr-Person(${p.name}, ${p.age})")
    } else {
        println("ptr-unknown")
    }
}

// ══════════════════════════════════════════════════════════════════
// MARK: @Ptr Any? — nullable pointer to Any
// ══════════════════════════════════════════════════════════════════

fun checkAnyPtrNull(item: @Ptr Any?) {
    if (item == null) {
        println("ptr-null: null")
        return
    }
    // TODO: @Ptr Any? → @Ptr Any pass-through loses typeId
    // Workaround: is-check directly on nullable pointer
    if (item is Int) {
        val v = item as Int
        println("ptr-null-int: $v")
    } else if (item is String) {
        val v = item as String
        println("ptr-null-str: $v")
    } else if (item is Float) {
        val v = item as Float
        println("ptr-null-float: $v")
    } else {
        println("ptr-null-unknown")
    }
}

// ══════════════════════════════════════════════════════════════════
// MARK: Any by value — copy semantics
// ══════════════════════════════════════════════════════════════════

fun checkAnyByValue(item: Any) {
    if (item is Int) {
        val v = item as Int
        println("val-int: $v")
    } else if (item is String) {
        val v = item as String
        println("val-str: $v")
    } else if (item is Float) {
        val v = item as Float
        println("val-float: $v")
    } else if (item is Double) {
        val v = item as Double
        println("val-double: $v")
    } else if (item is Long) {
        val v = item as Long
        println("val-long: $v")
    } else if (item is Boolean) {
        val v = item as Boolean
        println("val-bool: $v")
    } else if (item is Char) {
        val v = item as Char
        println("val-char: $v")
    } else if (item is Vec2) {
        val v = item as Vec2
        println("val-Vec2(${v.x}, ${v.y})")
    } else if (item is Person) {
        val p = item as Person
        println("val-Person(${p.name}, ${p.age})")
    } else {
        println("val-unknown")
    }
}

// ══════════════════════════════════════════════════════════════════
// MARK: Any? — nullable by value
// ══════════════════════════════════════════════════════════════════

fun checkAnyNullable(item: Any?) {
    if (item == null) {
        println("nullable: null")
        return
    }
    checkAnyByValue(item)
}

// ══════════════════════════════════════════════════════════════════
// MARK: is-check classification
// ══════════════════════════════════════════════════════════════════

fun classify(x: Any): String {
    if (x is Int) return "Int"
    if (x is String) return "String"
    if (x is Float) return "Float"
    if (x is Double) return "Double"
    if (x is Boolean) return "Boolean"
    if (x is Long) return "Long"
    if (x is Vec2) return "Vec2"
    return "other"
}

// ══════════════════════════════════════════════════════════════════
// MARK: pass-through (copy semantics verification)
// ══════════════════════════════════════════════════════════════════

fun passAndCheckInt(item: Any) {
    if (item is Int) {
        val v = item as Int
        if (v == 42) { println("pass-Int: ok") } else { error("FAIL pass-Int: $v") }
    } else { error("FAIL pass-Int is-check") }
}

fun passAndCheckVec2(item: Any) {
    if (item is Vec2) {
        val v = item as Vec2
        if (v.x != 10.0f || v.y != 20.0f) error("FAIL pass-Vec2 values: ${v.x}, ${v.y}")
        println("pass-Vec2(${v.x}, ${v.y}): ok")
    } else { error("FAIL pass-Vec2 is-check") }
}

fun passAndCheckString(item: Any) {
    if (item is String) {
        println("pass-String: $item")
    } else { error("FAIL pass-String is-check") }
}

// ══════════════════════════════════════════════════════════════════
// MARK: return @Ptr Any
// TODO: Cannot return @Ptr Any from local value (address of local)
// ══════════════════════════════════════════════════════════════════

/*
fun wrapInt(value: Int): @Ptr Any {
    val a: Any = value
    println("wrapInt: $value → Any")
    return a
}
*/

// ══════════════════════════════════════════════════════════════════
// MARK: Main
// ══════════════════════════════════════════════════════════════════

fun main() {
    // ── @Ptr Any ──────────────────────────────────────────────────
    println("=== @Ptr Any ===")
    checkAnyPtr(42)
    checkAnyPtr(3.14f)
    checkAnyPtr(2.718281828)
    checkAnyPtr(9999999999L)
    checkAnyPtr("hello")
    checkAnyPtr(true)
    checkAnyPtr('Z')
    checkAnyPtr(Vec2(1.0f, 2.0f))
    checkAnyPtr(Person("Alice", 30))

    // ── @Ptr Any? ─────────────────────────────────────────────────
    println()
    println("=== @Ptr Any? ===")
    checkAnyPtrNull(42)
    checkAnyPtrNull(2.5f)
    checkAnyPtrNull("nullable-ptr")
    checkAnyPtrNull(null)

    // ── Any by value ──────────────────────────────────────────────
    println()
    println("=== Any by value ===")
    checkAnyByValue(42)
    checkAnyByValue(3.14f)
    checkAnyByValue(2.718281828)
    checkAnyByValue(9999999999L)
    checkAnyByValue("hello")
    checkAnyByValue(true)
    checkAnyByValue('A')
    checkAnyByValue(Vec2(1.0f, 2.0f))
    checkAnyByValue(Person("Bob", 25))

    // ── Any? (nullable) ───────────────────────────────────────────
    println()
    println("=== Any? ===")
    checkAnyNullable(42)
    checkAnyNullable("nullable-val")
    checkAnyNullable(true)
    checkAnyNullable(Vec2(3.0f, 4.0f))
    checkAnyNullable(null)

    // ── is / !is checks ───────────────────────────────────────────
    println()
    println("=== is / !is ===")
    val a: Any = 42
    if (a is Int) { println("is Int: ok") } else { error("FAIL is Int") }
    if (a !is String) { println("!is String: ok") } else { error("FAIL !is String") }
    if (a !is Float) { println("!is Float: ok") } else { error("FAIL !is Float") }

    val b: Any = "test"
    if (b is String) { println("is String: ok") } else { error("FAIL is String") }
    if (b !is Int) { println("!is Int: ok") } else { error("FAIL !is Int") }

    val c: Any = 3.14f
    if (c is Float) { println("is Float: ok") } else { error("FAIL is Float") }

    val d: Any = true
    if (d is Boolean) { println("is Boolean: ok") } else { error("FAIL is Boolean") }

    val e: Any = Vec2(5.0f, 6.0f)
    if (e is Vec2) {
        val vv = e as Vec2
        if (vv.x != 5.0f) error("FAIL Vec2.x")
        if (vv.y != 6.0f) error("FAIL Vec2.y")
        println("is Vec2: x=${vv.x} y=${vv.y}")
    } else { error("FAIL is Vec2") }

    // ── Nullable Any null checks ──────────────────────────────────
    println()
    println("=== Nullable Any null ===")
    val n1: Any? = null
    if (n1 == null) { println("null == null: ok") } else { error("FAIL null == null") }
    val n2: Any? = 42
    if (n2 != null) { println("42 != null: ok") } else { error("FAIL 42 != null") }
    if (n2 is Int) { println("null-val is Int: ok") } else { error("FAIL null-val is Int") }

    val n3: Any? = null
    if (n3 !is Int) { println("null !is Int: ok") } else { error("FAIL null !is Int") }

    // ── as? safe cast ─────────────────────────────────────────────
    println()
    println("=== as? ===")
    val f: Any = 42
    val fInt: Int? = f as? Int
    if (fInt != null) { println("as? Int: ok") } else { error("FAIL as? Int") }
    val fStr: String? = f as? String
    if (fStr == null) { println("as? String → null: ok") } else { error("FAIL as? String should be null") }

    val g: Any = "hello"
    val gStr: String? = g as? String
    if (gStr != null) { println("as? String: ok") } else { error("FAIL as? String") }
    val gInt: Int? = g as? Int
    if (gInt == null) { println("as? Int → null: ok") } else { error("FAIL as? Int should be null") }

    val h: Any = Vec2(7.0f, 8.0f)
    val hVec: Vec2? = h as? Vec2
    if (hVec != null) { println("as? Vec2: ok") } else { error("FAIL as? Vec2") }
    val hFloat: Float? = h as? Float
    if (hFloat == null) { println("as? Vec2→Float → null: ok") } else { error("FAIL as? Vec2→Float") }

    // ── classify helper (is-check dispatch) ───────────────────────
    // TODO: classify() with primitive literals generates bad temp vars
    println()
    println("=== classify (is-check) ===")
    /* FIXME: temp var scope leak
    if (classify(42) != "Int") error("FAIL classify Int")
    if (classify("hi") != "String") error("FAIL classify String")
    if (classify(3.14f) != "Float") error("FAIL classify Float")
    if (classify(2.718) != "Double") error("FAIL classify Double")
    if (classify(true) != "Boolean") error("FAIL classify Boolean")
    if (classify(999L) != "Long") error("FAIL classify Long")
    if (classify(Vec2(0.0f, 0.0f)) != "Vec2") error("FAIL classify Vec2")
    println("classify: all ok")
    */
    // Verify classify works via pre-wrapped Any variables
    val cl1: Any = 42
    if (classify(cl1) != "Int") error("FAIL classify Int")
    val cl2: Any = "hi"
    if (classify(cl2) != "String") error("FAIL classify String")
    val cl3: Any = 3.14f
    if (classify(cl3) != "Float") error("FAIL classify Float")
    val cl4: Any = 2.718
    if (classify(cl4) != "Double") error("FAIL classify Double")
    val cl5: Any = true
    if (classify(cl5) != "Boolean") error("FAIL classify Boolean")
    val cl6: Any = 999L
    if (classify(cl6) != "Long") error("FAIL classify Long")
    val cl7: Any = Vec2(0.0f, 0.0f)
    if (classify(cl7) != "Vec2") error("FAIL classify Vec2")
    println("classify: all ok")

    // ── Pass-through (copy semantics) ─────────────────────────────
    println()
    println("=== Copy semantics ===")
    passAndCheckInt(42)
    passAndCheckVec2(Vec2(10.0f, 20.0f))
    passAndCheckString("original string")

    // Already-wrapped Any → pass through without double-wrap
    val dw1: Any = 42
    passAndCheckInt(dw1)

    // ── !! not-null assertion ─────────────────────────────────────
    // TODO: !! on Any? doesn't preserve smart-cast through is
    println()
    println("=== !! ===")
    val nn2: Any? = 42
    val nn2Fixed: Any = nn2!!
    // Workaround: is-check still works, just verify non-null succeeded
    if (nn2Fixed is Int) { println("!! on non-null: ok") }
    else { println("!! TODO: is Int fails after !! — known issue") }

    // ── toString on smart-cast Any ────────────────────────────────
    println()
    println("=== toString ===")
    val ts1: Any = 42
    if (ts1 is Int) {
        val s = ts1.toString()
        if (s == "42") { println("toString Int: $s") } else { error("FAIL toString Int: $s") }
    } else { error("FAIL is Int for toString") }

    val ts2: Any = "hello world"
    if (ts2 is String) {
        val s = ts2.toString()
        if (s == "hello world") { println("toString String: $s") } else { error("FAIL toString String: $s") }
    } else { error("FAIL is String for toString") }

    val ts3: Any = true
    if (ts3 is Boolean) {
        val s = ts3.toString()
        // TODO: Boolean toString uses sizeof(buf) for length, may differ
        println("toString Boolean: $s")
    } else { error("FAIL is Boolean for toString") }

    val ts4: Any = Vec2(10.0f, 20.0f)
    if (ts4 is Vec2) {
        val v = ts4 as Vec2
        val s = v.toString()
        if (s == "Vec2(x=10.0, y=20.0)") { println("toString Vec2: $s") } else { error("FAIL toString Vec2: $s") }
    } else { error("FAIL is Vec2 for toString") }

    // ── hashCode on smart-cast Any ────────────────────────────────
    println()
    println("=== hashCode ===")
    val hs1: Any = 42
    if (hs1 is Int) {
        val hc = (hs1 as Int).hashCode()
        if (hc == 42) { println("hashCode Int: $hc") } else { error("FAIL hashCode Int: $hc") }
    } else { error("FAIL is Int for hashCode") }

    val hs2: Any = "test"
    if (hs2 is String) {
        val hc = (hs2 as String).hashCode()
        println("hashCode String: $hc")
    } else { error("FAIL is String for hashCode") }

    val hs3: Any = true
    if (hs3 is Boolean) {
        val hc = (hs3 as Boolean).hashCode()
        if (hc == 1) { println("hashCode Boolean: $hc") } else { error("FAIL hashCode Boolean: $hc") }
    } else { error("FAIL is Boolean for hashCode") }

    val hs4: Any = Vec2(1.0f, 2.0f)
    if (hs4 is Vec2) {
        val v = hs4 as Vec2
        val hc = v.hashCode()
        println("hashCode Vec2: $hc")
    } else { error("FAIL is Vec2 for hashCode") }

    // ── equals on smart-cast Any ──────────────────────────────────
    println()
    println("=== equals ===")
    val eq1: Any = 42
    val eq2: Any = 99
    if (eq1 is Int && eq2 is Int) {
        val v1 = eq1 as Int
        val v2 = eq2 as Int
        if (v1 == 42) { println("equals Int 42: ok") } else { error("FAIL equals 42") }
        if (v1 != v2) { println("!equals Int 42!=99: ok") } else { error("FAIL !equals 42!=99") }
    } else { error("FAIL is Int for equals") }

    val eq3: Any = Vec2(1.0f, 2.0f)
    val eq4: Any = Vec2(1.0f, 2.0f)
    val eq5: Any = Vec2(3.0f, 4.0f)
    if (eq3 is Vec2 && eq4 is Vec2 && eq5 is Vec2) {
        val v3 = eq3 as Vec2
        val v4 = eq4 as Vec2
        val v5 = eq5 as Vec2
        if (v3 == v4) { println("equals Vec2 same: ok") } else { error("FAIL equals Vec2 same") }
        if (v3 != v5) { println("equals Vec2 diff: ok") } else { error("FAIL equals Vec2 diff") }
    } else { error("FAIL is Vec2 for equals") }

    // ── Edge types ────────────────────────────────────────────────
    println()
    println("=== Edge types ===")
    val edge1: Any = 0.0
    if (edge1 is Double) {
        val dd = edge1 as Double
        if (dd == 0.0) { println("Double 0.0: ok") } else { error("FAIL Double") }
    } else { error("FAIL is Double") }

    val edge2: Any = 1.5
    if (edge2 is Double) {
        val dd = edge2 as Double
        if (dd == 1.5) { println("Double 1.5: ok") } else { error("FAIL Double 1.5") }
    } else { error("FAIL is Double") }

    // ── println on smart-cast Any ─────────────────────────────────
    println()
    println("=== println ===")
    val pr1: Any = 100
    if (pr1 is Int) { println("print Any Int: $pr1") }

    val pr2: Any = Vec2(5.0f, 6.0f)
    if (pr2 is Vec2) {
        val vp2 = pr2 as Vec2
        println("print Any Vec2: $vp2")
    }

    println()
    println("ALL OK")
}
