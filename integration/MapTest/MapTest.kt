package MapTest

fun main() {
    // Setup
    val map = HashMap<Int, String>(16)

    // Test put via operator set
    map[1] = "hello"
    map[2] = "world"
    map.set(3, "foo")

    if (!(1 in map)) error("FAIL get(1) missing")
    if (!(2 in map)) error("FAIL get(2) missing")
    if (!(3 in map)) error("FAIL get(3) missing")
    println("get(1) = ${map[1]}")
    println("get(2) = ${map.get(2)}")

    // Test containsKey via `in` operator
    println("1 in map = ${1 in map}")
    println("99 in map = ${99 in map}")
    println("99 !in map = ${99 !in map}")
    if (!(1 in map)) error("FAIL 1 in map")
    if (99 in map) error("FAIL 99 in map should be false")
    if (!(99 !in map)) error("FAIL 99 !in map")

    // Test size
    println("size = ${map.size}")
    if (map.size != 3) error("FAIL size=${map.size}")

    // Test overwrite
    map[1] = "replaced"
    if (!(1 in map)) error("FAIL key 1 missing after overwrite")
    if (map.size != 3) error("FAIL size after overwrite=${map.size}")
    println("get(1) after overwrite = ${map[1]}")
    println("size after overwrite = ${map.size}")

    // Test remove
    val removed = map.remove(2)
    if (removed == false) error("FAIL remove(2) returned false")
    if (2 in map) error("FAIL 2 should not be in map after remove")
    if (map.size != 2) error("FAIL size after remove=${map.size}")
    println("remove(2) = $removed")
    println("2 in map after remove = ${2 in map}")
    println("size after remove = ${map.size}")

    // Test get on missing key
    val missing = map[999]
    if (missing != null) error("FAIL missing should be null")
    if (missing == null) {
        println("get(999) = null")
    }

    // Test clear
    map.clear()
    if (map.size != 0) error("FAIL size after clear=${map.size}")
    if (map.isEmpty() == false) error("FAIL isEmpty after clear")
    println("size after clear = ${map.size}")
    println("isEmpty after clear = ${map.isEmpty()}")

    // Test many puts (triggers growth)
    for (i in 0 until 100) {
        map[i] = "val"
    }
    if (map.size != 100) error("FAIL size after 100 puts=${map.size}")
    if (!(50 in map)) error("FAIL 50 not in map")
    if (100 in map) error("FAIL 100 should not be in map")
    println("size after 100 puts = ${map.size}")
    println("50 in map = ${50 in map}")
    println("100 in map = ${100 in map}")

    // Test for-in iteration over map
    val iterMap = HashMap<Int, String>(8)
    iterMap[10] = "ten"
    iterMap[20] = "twenty"
    iterMap[30] = "thirty"
    println("iterating map:")
    for (entry in iterMap) {
        println("  ${entry.first} -> ${entry.second}")
    }
    iterMap.dispose()

    // Test mapOf
    val m1 = mapOf("A" to "Alpha", "B" to "Bravo")
    defer m1.dispose()
    println("mapOf keys:")
    for (entry in m1) {
        println("  ${entry.first} -> ${entry.second}")
    }

    // Test mutableMapOf
    val m2 = mutableMapOf("X" to "Xray", "Y" to "Yankee")
    defer m2.dispose()
    m2["Z"] = "Zulu"
    println("mutableMapOf after add:")
    for (entry in m2) {
        println("  ${entry.first} -> ${entry.second}")
    }

    map.dispose()
    println("done")
}
