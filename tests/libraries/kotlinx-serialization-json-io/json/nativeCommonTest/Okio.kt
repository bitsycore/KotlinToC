package okio

// ktc2's own, in the place of okio, which ktc2 has no module of: the part kotlinx.serialization's JsonTestBase uses
// for its mode on okio's streams. The JVM side runs okio itself (and kotlinx-serialization-json-okio).

interface BufferedSource

interface BufferedSink

/** Text written as UTF-8 and read back. */
class Buffer : BufferedSource, BufferedSink {
	private val mText = StringBuilder()

	fun writeUtf8(string: String): Buffer {
		mText.append(string)
		return this
	}

	fun readUtf8(): String {
		val vText = mText.toString()
		mText.clear()
		return vText
	}
}
