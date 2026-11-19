import kotlinx.io.*
import kotlinx.io.bytestring.*
import kotlinx.io.files.*

// kotlinx-io as ordinary code uses it: a Buffer written and read in every way, numbers in both
// byte orders, UTF-8 with code points beyond U+FFFF, lines, peeking, copying, a Source and a Sink
// of the program's own, ByteString and its builder, hex and Base64, buffers of many segments, and
// files: written, read, appended to, listed, moved and deleted, in a directory of their own.

class Upper(private val inner: RawSink) : RawSink {
	override fun write(source: Buffer, byteCount: Long) {
		val text = source.readString(byteCount)
		val out = Buffer()
		out.writeString(text.uppercase())
		inner.write(out, out.size)
	}
	override fun flush() = inner.flush()
	override fun close() = inner.close()
}

class Counting(private val total: Long) : RawSource {
	private var given = 0L
	override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
		if (given >= total) return -1
		val n = minOf(byteCount, total - given, 7)
		for (i in 0 until n) sink.writeByte(('a' + ((given + i) % 26).toInt()).code.toByte())
		given += n
		return n
	}
	override fun close() {}
}

@OptIn(ExperimentalStdlibApi::class, kotlin.io.encoding.ExperimentalEncodingApi::class)
fun main() {
	val buffer = Buffer()
	buffer.writeByte(1)
	buffer.writeShort(0x0203)
	buffer.writeInt(0x04050607)
	buffer.writeLong(0x08090a0b0c0d0e0fL)
	buffer.writeShortLe(0x0203)
	buffer.writeIntLe(0x04050607)
	buffer.writeLongLe(-2L)
	buffer.writeFloat(1.5f)
	buffer.writeDouble(-0.25)
	println(buffer.size)
	println(buffer.copy().readByteArray().toHexString())
	println(listOf(buffer.readByte(), buffer.readShort(), buffer.readInt(), buffer.readLong()))
	println(listOf(buffer.readShortLe(), buffer.readIntLe(), buffer.readLongLe()))
	println("${buffer.readFloat()} ${buffer.readDouble()} ${buffer.exhausted()}")

	val text = Buffer()
	text.writeString("first line\nsecond\r\nthird ünïcödé 😀\n")
	text.writeCodePointValue(0x1F600)
	text.writeString("\nend")
	println(text.size)
	println(text.readLine())
	println(text.readLineStrict())
	println(text.peek().readLine())
	println(text.readLine())
	println(text.readCodePointValue().toString(16))
	println(text.readString())
	println(runCatching { text.readByte() }.exceptionOrNull()?.let { it::class.simpleName })

	val upper = Buffer()
	Upper(upper).buffered().use { it.writeString("shout this") }
	println(upper.readString())
	val counted = Counting(40).buffered()
	println(counted.readString(10))
	println(counted.request(100).toString() + " " + counted.readString())

	val big = Buffer()
	repeat(10_000) { big.writeString("line $it\n") }
	val half = Buffer()
	big.copyTo(half, 0, big.size / 2)
	println("${big.size} ${half.size}")
	var count = 0
	var last: String? = null
	while (true) {
		last = big.readLine() ?: break
		count++
	}
	println("$count lines, last $last")
	val other = Buffer()
	other.write(ByteArray(20_000) { (it % 251).toByte() })
	other.skip(5_000)
	println("${other.size} ${other[0]} ${other.readByteArray(3).toList()}")
	val moved = Buffer()
	moved.write(other, 10_000)
	println("${moved.size} ${other.size}")
	println(moved.indexOf(7.toByte(), 300))

	val bytes = ByteString(byteArrayOf(0x48, 0x69, 0x21))
	println(bytes)
	println(bytes.decodeToString())
	println(bytes.toHexString())
	println("Hi!".encodeToByteString() == bytes)
	println(bytes.size.toString() + " " + bytes[1] + " " + bytes.substring(1).decodeToString())
	println(ByteString("abc".encodeToByteArray()).startsWith("ab".encodeToByteString()))
	println(buildByteString { append(1); append(byteArrayOf(2, 3)); append(bytes) }.toHexString())
	println(listOf("b".encodeToByteString(), "a".encodeToByteString(), "ab".encodeToByteString()).sorted().map { it.decodeToString() })
	println(kotlin.io.encoding.Base64.encode(bytes.toByteArray()))
	println("hello".encodeToByteString().indexOf("l".encodeToByteString()))
	val withString = Buffer()
	withString.write(bytes)
	withString.writeString(" and more")
	println(withString.readByteString(3) == bytes)
	println(withString.readString())

	files()
}

fun files() {
	val root = Path(SystemTemporaryDirectory, "ktc2-kotlinx-io-" + kotlin.random.Random.nextInt(1_000_000))
	val nested = Path(root, "a", "b")
	SystemFileSystem.createDirectories(nested)
	println(SystemFileSystem.metadataOrNull(nested)?.isDirectory)
	val file = Path(nested, "data.txt")
	SystemFileSystem.sink(file).buffered().use { sink ->
		sink.writeString("first\n")
		sink.writeInt(42)
	}
	SystemFileSystem.sink(file, append = true).buffered().use { it.writeString("\nappended\n") }
	val metadata = SystemFileSystem.metadataOrNull(file)!!
	println("${metadata.isRegularFile} ${metadata.isDirectory} ${metadata.size}")
	SystemFileSystem.source(file).buffered().use { source ->
		println(source.readLine())
		println(source.readInt())
		println(source.readLine())
		println(source.readLine())
		println(source.exhausted())
	}
	val bytes = Path(nested, "bytes.bin")
	SystemFileSystem.sink(bytes).buffered().use { sink -> repeat(20_000) { sink.writeByte((it % 256).toByte()) } }
	println(SystemFileSystem.metadataOrNull(bytes)?.size)
	println(SystemFileSystem.source(bytes).buffered().use { it.readByteArray().sumOf { b -> b.toInt() and 0xFF } })
	println(SystemFileSystem.list(nested).map { it.name }.sorted())
	val moved = Path(nested, "moved.txt")
	SystemFileSystem.atomicMove(file, moved)
	println("${SystemFileSystem.exists(file)} ${SystemFileSystem.exists(moved)}")
	println("${moved.name} ${moved.parent?.name} ${moved.parent?.parent?.name} ${moved.isAbsolute}")
	println(Path("relative", "name.txt").isAbsolute)
	println(runCatching { SystemFileSystem.source(Path(nested, "missing")) }.exceptionOrNull()?.let { it::class.simpleName })
	println(SystemFileSystem.metadataOrNull(Path(nested, "missing")))
	for (path in listOf(moved, bytes, nested, Path(root, "a"), root)) SystemFileSystem.delete(path)
	println(SystemFileSystem.exists(root))
}
