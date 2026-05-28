package FileSystemTest

// Smoke test for ktc.std.FileSystem — exercises Path normalization, exists/delete/rename,
// directory create/list, metadata, and source/sink read/write.

fun testPathBasics() {
	val p = Path("foo/bar/baz.kt")
	if (p.s != "foo/bar/baz.kt") error("FAIL Path.s: ${p.s}")
	if (p.name != "baz.kt")        error("FAIL Path.name")
	if (p.nameWithoutExtension != "baz") error("FAIL Path.nameWithoutExtension")
	if (p.extension != "kt")       error("FAIL Path.extension")
	// `!!` on a function/property result that's nullable — exercises the
	// unwrap path for value-type Optional returns.
	val pParent = p.parent!!
	if (pParent.s != "foo/bar") error("FAIL Path.parent: ${pParent.s}")

	// `?.` on a function-result nullable — exercises the spill-to-temp path so
	// the LHS isn't evaluated twice. `Path("x").parent` returns null (no slash).
	val noParent = Path("x").parent?.s
	if (noParent != null) error("FAIL: expected null parent.s, got $noParent")
	if (p.isAbsolute)              error("FAIL Path.isAbsolute (relative)")

	val a = Path("/tmp/x")
	if (!a.isAbsolute) error("FAIL Path.isAbsolute (POSIX abs)")

	// Drive-letter absolute paths (Windows style — use forward slashes)
	val w = Path("C:/Users/me/file.txt")
	if (!w.isAbsolute) error("FAIL Path.isAbsolute (drive)")
	if (w.extension != "txt") error("FAIL Path drive ext")

	// Join — use intermediates to avoid chained-rvalue codegen quirks.
	val j0 = Path("a")
	val j1 = j0.child("b")
	val j = j1.child("c.dat")
	if (j.s != "a/b/c.dat") error("FAIL Path join: ${j.s}")

	// Joining an absolute child replaces the receiver (Okio semantics)
	val rBase = Path("a/b")
	val r = rBase.child("/etc/passwd")
	if (r.s != "/etc/passwd") error("FAIL Path abs-child replace: ${r.s}")
}

fun testWriteAndRead() {
	val p = Path("fs_test_data.txt")
	// Clean slate
	if (FileSystem.exists(p)) FileSystem.delete(p)
	if (FileSystem.exists(p)) error("FAIL: delete didn't remove")

	if (!FileSystem.writeUtf8(p, "Hello, world!\nLine 2\n")) error("FAIL writeUtf8")
	if (!FileSystem.exists(p)) error("FAIL exists after write")

	val meta = FileSystem.metadata(p)
	if (!meta.isRegularFile) error("FAIL meta isRegularFile")
	if (meta.isDirectory)    error("FAIL meta isDirectory (should be false)")
	if (meta.size != 21L) error("FAIL meta size: expected 21, got ${meta.size}")

	// Read it back via a FileSource
	val src = FileSystem.source(p)
	if (!src.isOpen) error("FAIL source open")
	val buf = ByteArray(64)
	val n = src.read(buf.asRaw(), buf.size)
	src.close()
	if (n != 21) error("FAIL read count: got $n")
	// Spot-check first byte
	if (buf[0] != 72.toByte()) error("FAIL read content: byte 0 = ${buf[0]}")

	// Cleanup
	if (!FileSystem.delete(p)) error("FAIL delete after read")
	if (FileSystem.exists(p))  error("FAIL still exists after delete")
}

fun testRename() {
	val a = Path("fs_test_rename_a.txt")
	val b = Path("fs_test_rename_b.txt")
	if (FileSystem.exists(a)) FileSystem.delete(a)
	if (FileSystem.exists(b)) FileSystem.delete(b)
	FileSystem.writeUtf8(a, "x")
	if (!FileSystem.rename(a, b)) error("FAIL rename")
	if (FileSystem.exists(a))    error("FAIL: source still exists after rename")
	if (!FileSystem.exists(b))   error("FAIL: dest missing after rename")
	FileSystem.delete(b)
}

fun testDirectoryAndList() {
	val dir = Path("fs_test_dir")
	if (FileSystem.exists(dir)) {
		// best-effort cleanup of any leftover
		listDir(dir) { name -> FileSystem.delete(dir.child(name)) }
		FileSystem.delete(dir)
	}
	if (!FileSystem.createDirectory(dir)) error("FAIL createDirectory")
	val meta = FileSystem.metadata(dir)
	if (!meta.isDirectory) error("FAIL dir.isDirectory")
	if (meta.isRegularFile) error("FAIL dir.isRegularFile (should be false)")

	FileSystem.writeUtf8(dir.child("a.txt"), "A")
	FileSystem.writeUtf8(dir.child("b.txt"), "BB")
	FileSystem.writeUtf8(dir.child("c.dat"), "CCC")

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
	if (count != 3) error("FAIL list count: $count")
	if (!sawA || !sawB || !sawC) error("FAIL list contents")

	// Cleanup
	FileSystem.delete(dir.child("a.txt"))
	FileSystem.delete(dir.child("b.txt"))
	FileSystem.delete(dir.child("c.dat"))
	FileSystem.delete(dir)
}

fun main(args: Array<String>) {
	testPathBasics()
	testWriteAndRead()
	testRename()
	testDirectoryAndList()
	println("ALL OK")
}
