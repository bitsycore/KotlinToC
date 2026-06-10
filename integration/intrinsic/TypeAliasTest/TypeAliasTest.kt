package TypeAliasTest

/*
 * Integration coverage for `typealias Name = Target` end-to-end.
 * Aliases are resolved by substitution during type-resolution; the
 * emitted C uses the underlying type so the alias name vanishes.
 * Each test runs through value bindings, function signatures, and
 * data-class members to make sure substitution fires everywhere.
 */

// ── Primitive aliases ─────────────────────────────────────────────

typealias UserId = Int
typealias Score  = Long

fun makeUser(id: UserId): UserId = id + 1
fun scoreOf(n: Score): Score = n * 10L

fun testPrimitiveAliases() {
    val id: UserId = 41
    val next = makeUser(id)
    if (next != 42) fatalError("FAIL UserId arithmetic: $next")

    val s: Score = 5L
    val totalScore = scoreOf(s)
    if (totalScore != 50L) fatalError("FAIL Score arithmetic: $totalScore")
    println("primitive aliases ok")
}

// ── Class alias ───────────────────────────────────────────────────

data class Vec2(val x: Float, val y: Float)
typealias Point = Vec2

fun originOf(p: Point): Point = Vec2(0.0f, 0.0f)

fun testClassAlias() {
    val p: Point = Vec2(3.0f, 4.0f)
    if (p.x != 3.0f) fatalError("FAIL Point.x")
    if (p.y != 4.0f) fatalError("FAIL Point.y")
    val zero = originOf(p.copy())
    if (zero.x != 0.0f || zero.y != 0.0f) fatalError("FAIL originOf")
    println("class alias ok")
}

// ── Chained aliases (alias of an alias) ───────────────────────────

typealias A = Int
typealias B = A
typealias C = B

fun useC(v: C): C = v + 1

fun testChainedAliases() {
    val v: C = 99
    if (v != 99) fatalError("FAIL chained literal")
    val w = useC(v)
    if (w != 100) fatalError("FAIL chained call: $w")
    println("chained aliases ok")
}

// ── Nullable target ───────────────────────────────────────────────

typealias MaybeInt = Int?

fun testNullableAlias() {
    // Verify the alias resolves to the same Optional type as `Int?` —
    // assigning null and then a value covers both Optional tags.
    val empty: MaybeInt = null
    val filled: MaybeInt = 42
    if (empty != null)  fatalError("FAIL nullable: empty was non-null")
    if (filled == null) fatalError("FAIL nullable: filled was null")
    println("nullable alias ok")
}

// ── Alias used in a data class field ──────────────────────────────

data class Player(val id: UserId, val pos: Point)

fun testAliasInDataClass() {
    val p = Player(7, Vec2(1.5f, 2.5f))
    if (p.id != 7)        fatalError("FAIL Player.id")
    if (p.pos.x != 1.5f)  fatalError("FAIL Player.pos.x")
    if (p.pos.y != 2.5f)  fatalError("FAIL Player.pos.y")
    println("alias in data class ok")
}

// ════════════════════════════════════════════════════════════════════

fun main() {
    testPrimitiveAliases()
    testClassAlias()
    testChainedAliases()
    testNullableAlias()
    testAliasInDataClass()
    println("done")
}
