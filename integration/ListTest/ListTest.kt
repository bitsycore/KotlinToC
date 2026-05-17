package ListTest.Main

// Disposable, List<T>, MutableList<T>, ArrayList<T> come from ktc stdlib (auto-imported)

data class Vec2(val x: Int, val y: Int)

// ── main ─────────────────────────────────────────────────────────────

fun <T> newArray(size: Int = 100) : @Ptr Array<T> {
	return HeapAlloc<Array<T>>(size)!!
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

	val listVec = ArrayList<Vec2>.allocWith(Heap, Heap, 8)
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

	val list = ArrayList<Int>.allocWith(Heap, Heap, 8)

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

	list.clear()
	println("size after clear:")
	println(list.size)
	if (list.size != 0) error("FAIL size after clear=${list.size}")

	list.dispose()
	Heap.freeMem(list)
}
