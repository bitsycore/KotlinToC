package DataClassTest

// 1. Basic data class with val params
data class Vec2(val x: Float, val y: Float)

// 2. Data class with var params
data class MutablePoint(var x: Int, var y: Int)

// 3. Data class with mixed val/var params
data class Mixed(val id: Int, var count: Int)

// 4. Data class with plain params (no val/var) - ctor-only, no auto-generated properties
data class PlainParams(name: String, age: Int)

// 5. Data class with String field
data class Person(val name: String, val age: Int)

// 6. Data class with nullable fields
data class NullablePoint(val x: Int?, val y: Float?)

// 7. Nested data class
data class Rect(val origin: Vec2, val size: Vec2)

// 8. Data class with secondary constructors
data class Vec3(val x: Float, val y: Float, val z: Float) {
    constructor(v: Float) : this(v, v, v)
    constructor(x: Int, y: Int, z: Int) : this(x.toFloat(), y.toFloat(), z.toFloat())
    constructor() : this(0.0f, 0.0f, 0.0f) {
        println("Vec3 empty ctor body executed")
    }
}

// 9. Data class with default parameter values
data class WithDefaults(val x: Int = 10, var y: String = "hello")

fun passVecPtr(inVec: @Ptr Vec2) {
    println("passVecPtr: $inVec")
}

fun passNullableVecPtr(inVec: @Ptr Vec2? = null) {
    if (inVec == null) {
        println("passNullableVecPtr: inVec is null")
        return
    }
    println("passNullableVecPtr: ${inVec.value()}")
}

fun passVecValue(inVec: Vec2) {
    println("passVecValue: $inVec")
}

fun passNullableVecValue(inVec: Vec2? = null) {
    if (inVec == null) {
        println("passNullableVecValue: inVec is null")
        return
    }
    println("passNullableVecValue: $inVec")
}

fun passMutablePointPtr(p: @Ptr MutablePoint) {
    println("passMutablePointPtr before set: $p")
    p.set(MutablePoint(99, 100))
    println("passMutablePointPtr after set: $p")
}

fun passMutablePointPtrNullable(p: @Ptr MutablePoint?) {
    println("passMutablePointPtrNullable before set: $p")
    p?.set(MutablePoint(99, 100))
    println("passMutablePointPtrNullable after set: $p")
}

fun passMutablePoint(p: MutablePoint) {
    println("passMutablePoint before set: $p")
    p.x = p.x + 99
    println("passMutablePoint after set: $p")
}

fun passMutablePointNullable(p: MutablePoint?) {
    println("passMutablePointNullable before set: $p")
    if (p != null) {
        p.x = p.x + 99
    }
    println("passMutablePointNullable after set: $p")
}

fun main() {
    // =================================
    // 1. Vec2 - basic val data class
    // =================================
    val v = Vec2(1.0f, 2.0f)
    if (v.x != 1.0f || v.y != 2.0f) error("FAIL Vec2 field access")
    println("Vec2 field access: ok")

    // equals
    val a = Vec2(1.0f, 2.0f)
    val b = Vec2(1.0f, 2.0f)
    val c = Vec2(3.0f, 4.0f)
    if (a != b) error("FAIL Vec2 equals (same values)")
    if (a == c) error("FAIL Vec2 not-equals (different values)")
    println("Vec2 equals: ok")

    // hashCode
    if (a.hashCode() != b.hashCode()) error("FAIL Vec2 hashCode (same values)")
    println("Vec2 hashCode consistent: ok")

    // toString
    println("Vec2 toString: $a")

    // copy - no args
    val vCopy1 = a.copy()
    if (vCopy1.x != 1.0f || vCopy1.y != 2.0f) error("FAIL Vec2 copy() no args")
    println("Vec2 copy() no args: ok")

    // copy - single named arg
    val vCopy2 = a.copy(x = 5.0f)
    if (vCopy2.x != 5.0f || vCopy2.y != 2.0f) error("FAIL Vec2 copy(x=5.0f)")
    println("Vec2 copy(x=5.0f): ok")

    // copy - multiple named args
    val vCopy3 = a.copy(x = 7.0f, y = 8.0f)
    if (vCopy3.x != 7.0f || vCopy3.y != 8.0f) error("FAIL Vec2 copy(x=7.0f, y=8.0f)")
    println("Vec2 copy(x=7f, y=8f): ok")

    // copy - single different arg
    val vCopy4 = a.copy(y = 9.0f)
    if (vCopy4.x != 1.0f || vCopy4.y != 9.0f) error("FAIL Vec2 copy(y=9.0f)")
    println("Vec2 copy(y=9.0f): ok")

    // =================================
    // 2. MutablePoint - var data class
    // =================================
    val mp = MutablePoint(10, 20)
    if (mp.x != 10 || mp.y != 20) error("FAIL MutablePoint field access")
    mp.x = 30
    mp.y = 40
    if (mp.x != 30 || mp.y != 40) error("FAIL MutablePoint var mutation")
    println("MutablePoint var mutation: ok")

    // equals after mutation
    val mp2 = MutablePoint(30, 40)
    if (mp != mp2) error("FAIL MutablePoint equals after mutation")
    println("MutablePoint equals: ok")

    // hashCode after mutation
    if (mp.hashCode() != mp2.hashCode()) error("FAIL MutablePoint hashCode after mutation")
    println("MutablePoint hashCode: ok")

    // toString
    println("MutablePoint toString: $mp")

    // copy with var
    val mpCopy = mp.copy(y = 100)
    if (mpCopy.x != 30 || mpCopy.y != 100) error("FAIL MutablePoint copy(y=100)")
    println("MutablePoint copy: ok")

    // =================================
    // 3. Mixed - val/var mixed params
    // =================================
    val m1 = Mixed(1, 10)
    if (m1.id != 1 || m1.count != 10) error("FAIL Mixed field access")
    m1.count = 20
    if (m1.count != 20) error("FAIL Mixed var mutation")
    println("Mixed mutation: ok")

    // equals
    val m2 = Mixed(1, 20)
    if (m1 != m2) error("FAIL Mixed equals")
    println("Mixed equals: ok")

    // hashCode
    if (m1.hashCode() != m2.hashCode()) error("FAIL Mixed hashCode")
    println("Mixed hashCode: ok")

    // toString
    println("Mixed toString: $m1")

    // copy
    val mCopy = m1.copy(count = 55)
    if (mCopy.id != 1 || mCopy.count != 55) error("FAIL Mixed copy(count=55)")
    println("Mixed copy: ok")

    // =================================
    // 4. PlainParams - no val/var (ctor-only params)
    // =================================
    val pl1 = PlainParams("test", 42)
    val pl2 = PlainParams("test", 42)
    val pl3 = PlainParams("other", 99)

    // hashCode: without properties, falls back to identity hash (pointer-based)
    if (pl1.hashCode() == pl2.hashCode()) error("FAIL PlainParams hashCode should differ by identity")
    println("PlainParams identity hashCode: ok")

    // equals: empty props -> always true
    if (pl1 != pl2) error("FAIL PlainParams equals (empty props = always equal)")
    if (pl1 != pl3) error("FAIL PlainParams equals across different ctor args")
    println("PlainParams equals: ok")

    // toString (empty props -> just closing paren)
    // TODO: toString on data class with no val/var props produces just ")" without open
    //       paren because the loop over ci.props is empty.
    println("PlainParams toString: $pl1")

    // copy (empty props -> struct copy)
    val plCopy = pl1.copy()
    if (pl1 != plCopy) error("FAIL PlainParams copy (empty struct)")
    println("PlainParams copy: ok")

    // =================================
    // 5. Person - String field data class
    // =================================
    val p1 = Person("Alice", 30)
    val p2 = Person("Alice", 30)
    val p3 = Person("Bob", 30)
    val p4 = Person("Alice", 25)

    // field access
    if (p1.name != "Alice" || p1.age != 30) error("FAIL Person field access")
    println("Person field access: ok")

    // equals with String
    if (p1 != p2) error("FAIL Person equals (same)")
    if (p1 == p3) error("FAIL Person equals (different name)")
    if (p1 == p4) error("FAIL Person equals (different age)")
    println("Person equals: ok")

    // hashCode with String
    if (p1.hashCode() != p2.hashCode()) error("FAIL Person hashCode (same)")
    println("Person hashCode consistent: ok")

    // toString with String
    println("Person toString: $p1")

    // copy with String
    val pCopy = p1.copy(age = 31)
    if (pCopy.name != "Alice" || pCopy.age != 31) error("FAIL Person copy(age=31)")
    println("Person copy: ok")

    // =================================
    // 6. NullablePoint - nullable fields
    // =================================
    val np1 = NullablePoint(42, 3.14f)
    val np2 = NullablePoint(42, 3.14f)
    val np3 = NullablePoint(null, null)
    val np4 = NullablePoint(42, null)

    // equals with nullable fields
    if (np1 != np2) error("FAIL NullablePoint equals (same)")
    if (np1 == np3) error("FAIL NullablePoint equals (vs all-null)")
    if (np3 != NullablePoint(null, null)) error("FAIL NullablePoint equals (null==null)")
    println("NullablePoint equals: ok")

    // hashCode with nullable fields
    if (np1.hashCode() != np2.hashCode()) error("FAIL NullablePoint hashCode (same)")
    println("NullablePoint hashCode: ok")

    // toString (null rendering)
    println("NullablePoint toString (some): $np1")
    println("NullablePoint toString (none): $np3")
    println("NullablePoint toString (mixed): $np4")

    // field access - non-null and null value checks
    if (np1.x != 42 || np1.y != 3.14f) error("FAIL NullablePoint field access (some)")
    if (np3.x != null || np3.y != null) error("FAIL NullablePoint null access")
    println("NullablePoint field access: ok")

    // copy with partial override on nullable fields
    val npCopyOverride = np3.copy(x = 99)
    if (npCopyOverride.x != 99 || npCopyOverride.y != null) error("FAIL NullablePoint copy(x=99) on null fields")
    println("NullablePoint copy override: ok")

    // copy on nullable fields - no-arg copy works
    val npCopy = np1.copy()
    if (npCopy != np1) error("FAIL NullablePoint copy no-args")
    println("NullablePoint copy no-arg: ok")

    // =================================
    // 7. Rect - nested data class
    // =================================
    val r1 = Rect(Vec2(0.0f, 0.0f), Vec2(10.0f, 5.0f))
    val r2 = Rect(Vec2(0.0f, 0.0f), Vec2(10.0f, 5.0f))
    val r3 = Rect(Vec2(1.0f, 1.0f), Vec2(10.0f, 5.0f))

    // field access (nested)
    if (r1.origin.x != 0.0f || r1.size.y != 5.0f) error("FAIL Rect nested field access")
    println("Rect nested field access: ok")

    // equals (recursive into nested data classes)
    if (r1 != r2) error("FAIL Rect equals (same)")
    if (r1 == r3) error("FAIL Rect equals (different origin)")
    println("Rect equals (nested): ok")

    // hashCode (recursive)
    if (r1.hashCode() != r2.hashCode()) error("FAIL Rect hashCode (same)")
    println("Rect hashCode (nested): ok")

    // toString (recursive)
    println("Rect toString: $r1")

    // copy (nested)
    val rCopy = r1.copy(size = Vec2(20.0f, 30.0f))
    if (rCopy.origin.x != 0.0f || rCopy.size.x != 20.0f || rCopy.size.y != 30.0f) error("FAIL Rect copy")
    println("Rect copy: ok")

    // =================================
    // 8. Vec3 - secondary constructors
    // =================================
    // primary
    val v3a = Vec3(1.0f, 2.0f, 3.0f)
    if (v3a.x != 1.0f || v3a.y != 2.0f || v3a.z != 3.0f) error("FAIL Vec3 primary ctor")
    println("Vec3 primary ctor: ok")

    // single float secondary
    val v3b = Vec3(5.0f)
    if (v3b.x != 5.0f || v3b.y != 5.0f || v3b.z != 5.0f) error("FAIL Vec3 single float ctor")
    println("Vec3 single float ctor: ok")

    // int secondary
    val v3c = Vec3(7, 8, 9)
    if (v3c.x != 7.0f || v3c.y != 8.0f || v3c.z != 9.0f) error("FAIL Vec3 int ctor")
    println("Vec3 int ctor: ok")

    // empty secondary with body
    val v3d = Vec3()
    if (v3d.x != 0.0f || v3d.y != 0.0f || v3d.z != 0.0f) error("FAIL Vec3 empty ctor")
    println("Vec3 empty ctor: ok")

    // equals with secondary-constructed objects
    if (v3a == v3b) error("FAIL Vec3 equals across different ctors")
    val v3e = Vec3(1.0f, 2.0f, 3.0f)
    if (v3a != v3e) error("FAIL Vec3 equals same values")
    println("Vec3 equals (secondary): ok")

    // hashCode with secondary-constructed objects
    if (v3a.hashCode() != v3e.hashCode()) error("FAIL Vec3 hashCode (same)")
    println("Vec3 hashCode (secondary): ok")

    // toString on secondary-constructed
    println("Vec3 toString: $v3a")

    // copy on secondary-constructed
    val v3Copy = v3a.copy(z = 99.0f)
    if (v3Copy.x != 1.0f || v3Copy.y != 2.0f || v3Copy.z != 99.0f) error("FAIL Vec3 copy")
    println("Vec3 copy: ok")

    // =================================
    // 8. passMutablePoint - verify that passing a mutable data class to a function allows mutation of the original object
    // =================================
    val pointShouldNotMutate = MutablePoint(10, 20)
    passMutablePoint(pointShouldNotMutate)
    if (pointShouldNotMutate.x != 10 || pointShouldNotMutate.y != 20) error("FAIL passMutablePoint should not mutate original")

    val pointShouldNotMutate2 = MutablePoint(10, 20)
    passMutablePointNullable(pointShouldNotMutate2)
    passMutablePointNullable(null)
    if (pointShouldNotMutate2.x != 10 || pointShouldNotMutate2.y != 20) error("FAIL passMutablePoint should not mutate original")

    // =================================
    // 9. WithDefaults - default values
    // =================================
    val wd1 = WithDefaults()
    if (wd1.x != 10 || wd1.y != "hello") error("FAIL WithDefaults default values")
    println("WithDefaults default: ok")

    val wd2 = WithDefaults(5)
    if (wd2.x != 5 || wd2.y != "hello") error("FAIL WithDefaults partial default")
    println("WithDefaults partial: ok")

    val wd3 = WithDefaults(7, "world")
    if (wd3.x != 7 || wd3.y != "world") error("FAIL WithDefaults named")
    println("WithDefaults explicit: ok")

    // equals
    val wd4 = WithDefaults(7, "world")
    if (wd3 != wd4) error("FAIL WithDefaults equals")
    println("WithDefaults equals: ok")

    // hashCode
    if (wd3.hashCode() != wd4.hashCode()) error("FAIL WithDefaults hashCode")
    println("WithDefaults hashCode: ok")

    // toString
    println("WithDefaults toString: $wd3")

    // copy
    val wdCopy = wd3.copy(y = "copied")
    if (wdCopy.x != 7 || wdCopy.y != "copied") error("FAIL WithDefaults copy")
    println("WithDefaults copy: ok")

    // =================================
    // 10. @Ptr data class tests
    val vec = Vec2(100.0f, 200.0f)

    // ptr() -> pointer
    passVecPtr(vec.ptr())

    // @Ptr nullable
    passNullableVecPtr(vec.ptr())
    passNullableVecPtr()
    passNullableVecPtr(null)

    // @Ptr value type pass
    passVecValue(vec)
    passNullableVecValue(vec)
    passNullableVecValue()
    passNullableVecValue(null)

    // @Ptr with var data class
    val mpForPtr = MutablePoint(50, 60)
    val mpPtr = mpForPtr.ptr()
    passMutablePointPtr(mpPtr)
    // verify mutation through set()
    val mutatedMp = mpPtr.value()
    if (mutatedMp.x != 99 || mutatedMp.y != 100) error("FAIL @Ptr set() mutation")
    println("@Ptr set() mutation verified: ok")

    // @Ptr Nullable with var data class
    val mpForPtrNullable = MutablePoint(50, 60)
    val mpPtrNullable = mpForPtrNullable.ptr()
    passMutablePointPtrNullable(mpPtrNullable)
    passMutablePointPtrNullable(null)
    // verify mutation through set()
    val mutatedMpNullable = mpPtrNullable.value()
    if (mutatedMpNullable.x != 99 || mutatedMpNullable.y != 100) error("FAIL @Ptr Nullable set() mutation")
    println("@Ptr Nullable set() mutation verified: ok")

    // @Ptr .value() dereference
    val directVec = Vec2(300.0f, 400.0f)
    val directPtr = directVec.ptr()
    val derefd = directPtr.value()
    if (derefd.x != 300.0f || derefd.y != 400.0f) error("FAIL @Ptr value()")
    println("@Ptr value(): ok")

    // @Ptr .set()
    val setVec = Vec2(0.0f, 0.0f)
    val setPtr = setVec.ptr()
    setPtr.set(Vec2(50.0f, 60.0f))
    val setVal = setPtr.value()
    if (setVal.x != 50.0f || setVal.y != 60.0f) error("FAIL @Ptr .set()")
    println("@Ptr .set(): ok")

    // @Ptr .copy()
    val copyFrom = Vec2(1.0f, 2.0f)
    val copyFromPtr = copyFrom.ptr()
    val copied = copyFromPtr.copy(x = 10.0f)
    if (copied.x != 10.0f || copied.y != 2.0f) error("FAIL @Ptr copy()")
    println("@Ptr copy(): ok")

    // @Ptr equals (structural via ClassName_equals)
    val eqA = Vec2(10.0f, 20.0f)
    val eqB = Vec2(10.0f, 20.0f)
    val eqPtrA = eqA.ptr()
    val eqPtrB = eqB.ptr()
    if (eqPtrA != eqPtrB) error("FAIL @Ptr equals (same struct)")
    // verify different values are not equal
    val eqC = Vec2(30.0f, 40.0f)
    val eqPtrC = eqC.ptr()
    if (eqPtrA == eqPtrC) error("FAIL @Ptr equals (different struct)")
    println("@Ptr equals: ok")

    // @Ptr hashCode
    val hashSrc = Vec2(7.0f, 8.0f)
    val hashPtr = hashSrc.ptr()
    println("@Ptr hashCode: ${hashPtr.hashCode()}")

    // @Ptr toString
    val displayVec = Vec2(10.0f, 20.0f)
    val displayVecPtr = displayVec.ptr()
    println("@Ptr toString: $displayVecPtr")

    // @Ptr with nested data class
    val rect = Rect(Vec2(1.0f, 1.0f), Vec2(2.0f, 2.0f))
    val rectPtr = rect.ptr()
    println("@Ptr nested toString: $rectPtr")

    // =================================
    // 11. Generic copy scenarios
    // =================================

    // copy chain
    val chainA = Vec2(1.0f, 1.0f)
    val chainB = chainA.copy(x = 2.0f)
    val chainC = chainB.copy(y = 3.0f)
    val chain = chainC.copy(x = 10.0f, y = 20.0f)
    if (chain.x != 10.0f || chain.y != 20.0f) error("FAIL copy chain")
    println("copy chain: ok")

    // copy unchanged
    val unchanged = Vec2(5.0f, 6.0f).copy()
    if (unchanged.x != 5.0f || unchanged.y != 6.0f) error("FAIL copy unchanged")
    println("copy unchanged: ok")

    // copy all fields from a nested data class
    val nestedCopy = r1.copy()
    if (nestedCopy != r1) error("FAIL nested copy no args")
    println("nested copy no args: ok")

    // =================================
    // 12. equals/hashCode contract
    // =================================

    // If a == b then a.hashCode() == b.hashCode()
    val eqX = Vec2(1.0f, 2.0f)
    val eqY = Vec2(1.0f, 2.0f)
    if (eqX == eqY && eqX.hashCode() != eqY.hashCode()) error("FAIL equals/hashCode contract")
    println("equals/hashCode contract: ok")

    // Consistent across types
    val per1 = Person("X", 1)
    val per2 = Person("X", 1)
    if (per1 == per2 && per1.hashCode() != per2.hashCode()) error("FAIL Person equals/hashCode contract")
    println("Person equals/hashCode contract: ok")

    // =================================
    // 13. String interpolation with data class
    // =================================
    val strVec = Vec2(1.5f, 2.5f)
    val message = "Vec2 result: $strVec"
    println("String interpolation: $message")
    println("Direct interpolation: $strVec")

    // Nested in string
    val nestedInStr = "$r1"
    println("Nested interpolation: $nestedInStr")

    println("ALL OK")
}
