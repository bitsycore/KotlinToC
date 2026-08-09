package FileSystemTest

// Smoke test for ktc.std.FileSystem - exercises Path normalization, exists/delete/rename,
// directory create/list, metadata, and source/sink read/write.

fun testPathBasics() {
	val p = Path("foo/bar/baz.kt")
	if (p.s != "foo/bar/baz.kt") fatalError("FAIL Path.s: ${p.s}")
	if (p.name != "baz.kt")        fatalError("FAIL Path.name")
	if (p.nameWithoutExtension != "baz") fatalError("FAIL Path.nameWithoutExtension")
	if (p.extension != "kt")       fatalError("FAIL Path.extension")
	// `!!` on a function/property result that's nullable - exercises the
	// unwrap path for value-type Optional returns.
	val pParent = p.parent!!
	if (pParent.s != "foo/bar") fatalError("FAIL Path.parent: ${pParent.s}")

	// `?.` on a function-result nullable - exercises the spill-to-temp path so
	// the LHS isn't evaluated twice. `Path("x").parent` returns null (no slash).
	val noParent = Path("x").parent?.s
	if (noParent != null) fatalError("FAIL: expected null parent.s, got $noParent")

	// `.cast<T>()` - unchecked reinterpret. Round-trips a Long through Int and
	// back to confirm the emission produces a usable C cast expression.
	val raw: Long = 42L
	val asInt: Int = raw.cast<Long, Int>()
	if (asInt != 42) fatalError("FAIL Long.cast<Int>: $asInt")
	if (p.isAbsolute)              fatalError("FAIL Path.isAbsolute (relative)")

	val a = Path("/tmp/x")
	if (!a.isAbsolute) fatalError("FAIL Path.isAbsolute (POSIX abs)")

	// Drive-letter absolute paths (Windows style - use forward slashes)
	val w = Path("C:/Users/me/file.txt")
	if (!w.isAbsolute) fatalError("FAIL Path.isAbsolute (drive)")
	if (w.extension != "txt") fatalError("FAIL Path drive ext")

	// Chained method calls AND operator overload - `path / "sub"` dispatches
	// to Path.div, then chains again. Exercises both the &-of-rvalue spill
	// and the new operator-overload dispatch in genBin.
	val j = Path("a") / "b" / "c.dat"
	if (j.s != "a/b/c.dat") fatalError("FAIL Path join: ${j.s}")

	// Joining an absolute child replaces the receiver (Okio semantics)
	val r = Path("a/b") / "/etc/passwd"
	if (r.s != "/etc/passwd") fatalError("FAIL Path abs-child replace: ${r.s}")
}

fun testWriteAndRead() {
	val p = Path("fs_test_data.txt")
	// Clean slate
	if (FileSystem.exists(p.asRef())) FileSystem.delete(p.asRef())
	if (FileSystem.exists(p.asRef())) fatalError("FAIL: delete didn't remove")

	if (!FileSystem.writeUtf8(p.asRef(), "Hello, world!\nLine 2\n")) fatalError("FAIL writeUtf8")
	if (!FileSystem.exists(p.asRef())) fatalError("FAIL exists after write")

	val meta = FileSystem.metadata(p.asRef())
	if (!meta.isRegularFile) fatalError("FAIL meta isRegularFile")
	if (meta.isDirectory)    fatalError("FAIL meta isDirectory (should be false)")
	if (meta.size != 21L) fatalError("FAIL meta size: expected 21, got ${meta.size}")

	// Read it back via a FileSource
	val src = FileSystem.source(p.asRef())
	if (!src.isOpen) fatalError("FAIL source open")
	val buf = ByteArray(64)
	val n = src.read(buf.asRaw(), buf.size)
	src.close()
	if (n != 21) fatalError("FAIL read count: got $n")
	// Spot-check first byte
	if (buf[0] != 72.toByte()) fatalError("FAIL read content: byte 0 = ${buf[0]}")

	// Cleanup
	if (!FileSystem.delete(p.asRef())) fatalError("FAIL delete after read")
	if (FileSystem.exists(p.asRef()))  fatalError("FAIL still exists after delete")
}

fun testRename() {
	val a = Path("fs_test_rename_a.txt")
	val b = Path("fs_test_rename_b.txt")
	if (FileSystem.exists(a.asRef())) FileSystem.delete(a.asRef())
	if (FileSystem.exists(b.asRef())) FileSystem.delete(b.asRef())
	FileSystem.writeUtf8(a.asRef(), "x")
	if (!FileSystem.rename(a.asRef(), b.asRef())) fatalError("FAIL rename")
	if (FileSystem.exists(a.asRef()))    fatalError("FAIL: source still exists after rename")
	if (!FileSystem.exists(b.asRef()))   fatalError("FAIL: dest missing after rename")
	FileSystem.delete(b.asRef())
}

fun testDirectoryAndList() {
	val dir = Path("fs_test_dir")
	if (FileSystem.exists(dir.asRef())) {
		// best-effort cleanup of any leftover
		listDir(dir) { name -> FileSystem.delete(dir.child(name).asRef()) }
		FileSystem.delete(dir.asRef())
	}
	if (!FileSystem.createDirectory(dir.asRef())) fatalError("FAIL createDirectory")
	val meta = FileSystem.metadata(dir.asRef())
	if (!meta.isDirectory) fatalError("FAIL dir.isDirectory")
	if (meta.isRegularFile) fatalError("FAIL dir.isRegularFile (should be false)")

	FileSystem.writeUtf8(dir.child("a.txt").asRef(), "A")
	FileSystem.writeUtf8(dir.child("b.txt").asRef(), "BB")
	FileSystem.writeUtf8(dir.child("c.dat").asRef(), "CCC")

	var count = 0
	var sawA = false
	var sawB = false
	var sawC = false
	listDir(dir) { name ->
		count++
		if (name == "a.txt") sawA = true
		if (name == "b.txt") sawB = true
		if (name == "c.dat") sawC = true
	}
	if (count != 3) fatalError("FAIL list count: $count")
	if (!sawA || !sawB || !sawC) fatalError("FAIL list contents")

	// Cleanup
	FileSystem.delete(dir.child("a.txt").asRef())
	FileSystem.delete(dir.child("b.txt").asRef())
	FileSystem.delete(dir.child("c.dat").asRef())
	FileSystem.delete(dir.asRef())
}

fun main(args: Array<String>) {
	testPathBasics()
	testWriteAndRead()
	testRename()
	testDirectoryAndList()
	println("ALL OK")
}
