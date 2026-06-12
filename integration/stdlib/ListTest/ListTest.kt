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
	if (array.size != 5) fatalError("FAIL array.size=${array.size}")
	println("Sizeof array2: ${array2.size}")
	if (array2.size != 100) fatalError("FAIL array2.size=${array2.size}")
	println("Sizeof array3: ${array3.size}")
	if (array3.size != 180) fatalError("FAIL array3.size=${array3.size}")

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
	if (listVec.size != 10) fatalError("FAIL v2.size=${listVec.size}")

	val list = ArrayList<Int>(Heap, 8).allocWith(Heap)

	list.add(10)
	list.add(20)
	list.add(30)
	list.add(40)
	list.add(50)

	println("size:")
	println(list.size)
	if (list.size != 5) fatalError("FAIL list.size=${list.size}")

	println("get(0), get(2):")
	println(list.get(0))
	println(list.get(2))

	list.set(1, 99)
	println("after set(1, 99), get(1):")
	println(list.get(1))
	if (list.get(1) != 99) fatalError("FAIL get(1) after set")

	val removed = list.removeAt(0)
	println("removed:")
	println(removed)
	if (removed != 10) fatalError("FAIL removed=$removed")
	println("size after remove:")
	println(list.size)
	if (list.size != 4) fatalError("FAIL size after remove=${list.size}")

	println("contains 99:")
	println(list.contains(99))
	if (list.contains(99) == false) fatalError("FAIL should contain 99")
	println("contains 777:")
	println(list.contains(777))
	if (list.contains(777)) fatalError("FAIL should not contain 777")

	println("indexOf 50:")
	println(list.indexOf(50))
	if (list.indexOf(50) != 3) fatalError("FAIL indexOf 50")

	// ── Custom deep clone: independent backing buffer (Ref<ArrayList<Int>>) ──
	val orig = ArrayList<Int>(Heap, 4)
	defer orig.dispose()
	orig.add(1)
	orig.add(2)
	orig.add(3)
	val dup = orig.clone()          // deep clone — heap Ref<ArrayList<Int>> with its OWN buffer
	dup.set(0, 99)                  // mutate the clone only
	println("deep clone: orig[0]=${orig.get(0)} dup[0]=${dup.get(0)}")
	if (orig.get(0) != 1) fatalError("FAIL clone not deep — orig[0] mutated to ${orig.get(0)}")
	if (dup.get(0) != 99) fatalError("FAIL clone — dup[0]=${dup.get(0)}")
	if (dup.size != 3) fatalError("FAIL clone size=${dup.size}")
	dup.dispose()                   // free the clone's buffer, then the clone itself
	Heap.freeMem(dup)
	println("clone deep-copy OK")

	// Clone THROUGH the Cloneable<List<Int>> interface (polymorphic dispatch). The vtable covariant-return
	// trampoline wraps the concrete clone into a List<Int> fat pointer, so dup2 is a real Ref<interface>:
	// .size dispatches via the vtable, dispose() (on Any) works, and freeMem frees the heap object.
	val cloneable: Cloneable<List<Int>> = orig
	val dup2 = cloneable.clone()
	if (dup2.size != orig.size) fatalError("FAIL interface clone size=${dup2.size}")
	dup2.dispose()
	Heap.freeMem(dup2)
	println("Cloneable<List> interface clone OK")

	list.clear()
	println("size after clear:")
	println(list.size)
	if (list.size != 0) fatalError("FAIL size after clear=${list.size}")

	// Typed throws (Kotlin semantics): first/last on empty -> NoSuchElementException,
	// size-based index guard -> IndexOutOfBoundsException, iterator past the end ->
	// NoSuchElementException. All catchable.
	var thrown = 0
	if (!list.isEmpty()) fatalError("FAIL isEmpty after clear")
	try {
		val x = list.first()
		fatalError("FAIL empty first $x")
	} catch (e: NoSuchElementException) { thrown += 1 }
	list.add(7)
	list.add(8)
	if (list.first() != 7 || list.last() != 8) fatalError("FAIL first/last")
	try {
		val x = list[5]
		fatalError("FAIL size-guarded get $x")
	} catch (e: IndexOutOfBoundsException) {
		if (!e.message.contains("5")) fatalError("FAIL oob message ${e.message}")
		thrown += 10
	}
	val tailIt = list.iterator()
	tailIt.next()
	tailIt.next()
	try {
		val x = tailIt.next()
		fatalError("FAIL iterator past end $x")
	} catch (e: NoSuchElementException) { thrown += 100 }
	if (thrown != 111) fatalError("FAIL typed throws thrown=$thrown")
	println("typed throws OK")

	list.dispose()
	Heap.freeMem(list)
}
