package ListTest.Main

// Disposable, List<T>, MutableList<T>, ArrayList<T> come from ktc stdlib (auto-imported)

data class Vec2(val x: Int, val y: Int)

// ── main ─────────────────────────────────────────────────────────────

fun <T> newArray(size: Int = 100) : Ref<Array<T>> {
	return Array<T>(size).allocWith(Heap)!!
}

fun main(args: Array<String>) {

	val array = newArray<Int>(5)
	defer Heap.freeMem(array)
	val array2 = newArray<Int>()
	defer Heap.freeMem(array2)
	val array3 = newArray<Int>(180)
	defer Heap.freeMem(array3)

	println("Sizeof array: ${array.size}")
	if (array.size != 5) error("FAIL array.size=${array.size}")
	println("Sizeof array2: ${array2.size}")
	if (array2.size != 100) error("FAIL array2.size=${array2.size}")
	println("Sizeof array3: ${array3.size}")
	if (array3.size != 180) error("FAIL array3.size=${array3.size}")

	val listVec = ArrayList<Vec2>(Heap, 8).allocWith(Heap)
	defer Heap.freeMem(listVec)
	defer listVec.dispose()

	listVec.add(Vec2(1,1))
	listVec.add(Vec2(2,2))
	listVec.add(Vec2(3,3))
	listVec.add(Vec2(4,4))
	listVec.add(Vec2(5,5))
	listVec.add(Vec2(6,6))
	listVec.add(Vec2(7,7))
	listVec.add(Vec2(8,8))
	listVec.add(Vec2(9,9))
	listVec.add(Vec2(0,0))

	for(i in 0..<listVec.size) {
		println("v2.get($i) = ${listVec[i]}")
	}
	if (listVec.size != 10) error("FAIL v2.size=${listVec.size}")

	val list = ArrayList<Int>(Heap, 8).allocWith(Heap)

	list.add(10)
	list.add(20)
	list.add(30)
	list.add(40)
	list.add(50)

	println("size:")
	println(list.size)
	if (list.size != 5) error("FAIL list.size=${list.size}")

	println("get(0), get(2):")
	println(list.get(0))
	println(list.get(2))

	list.set(1, 99)
	println("after set(1, 99), get(1):")
	println(list.get(1))
	if (list.get(1) != 99) error("FAIL get(1) after set")

	val removed = list.removeAt(0)
	println("removed:")
	println(removed)
	if (removed != 10) error("FAIL removed=$removed")
	println("size after remove:")
	println(list.size)
	if (list.size != 4) error("FAIL size after remove=${list.size}")

	println("contains 99:")
	println(list.contains(99))
	if (list.contains(99) == false) error("FAIL should contain 99")
	println("contains 777:")
	println(list.contains(777))
	if (list.contains(777)) error("FAIL should not contain 777")

	println("indexOf 50:")
	println(list.indexOf(50))
	if (list.indexOf(50) != 3) error("FAIL indexOf 50")

	// ── Custom deep clone: independent backing buffer (Ref<ArrayList<Int>>) ──
	val orig = ArrayList<Int>(Heap, 4)
	defer orig.dispose()
	orig.add(1)
	orig.add(2)
	orig.add(3)
	val dup = orig.clone()          // deep clone — heap Ref<ArrayList<Int>> with its OWN buffer
	dup.set(0, 99)                  // mutate the clone only
	println("deep clone: orig[0]=${orig.get(0)} dup[0]=${dup.get(0)}")
	if (orig.get(0) != 1) error("FAIL clone not deep — orig[0] mutated to ${orig.get(0)}")
	if (dup.get(0) != 99) error("FAIL clone — dup[0]=${dup.get(0)}")
	if (dup.size != 3) error("FAIL clone size=${dup.size}")
	dup.dispose()                   // free the clone's buffer, then the clone itself
	Heap.freeMem(dup)
	println("clone deep-copy OK")
	// (List<T> : Cloneable<List<T>> — the contract is enforced by the std-lib; concrete .clone() above
	//  returns Ref<ArrayList<Int>>. Cloning *through* the Cloneable<List<Int>> interface returns
	//  Ref<List<Int>>, which can't be disposed — List has no dispose() — so prefer the concrete clone.)

	list.clear()
	println("size after clear:")
	println(list.size)
	if (list.size != 0) error("FAIL size after clear=${list.size}")

	list.dispose()
	Heap.freeMem(list)
}
