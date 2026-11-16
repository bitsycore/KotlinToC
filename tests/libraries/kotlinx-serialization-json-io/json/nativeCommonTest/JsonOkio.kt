package kotlinx.serialization.json.okio

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource

// ktc2's own, in the place of kotlinx-serialization-json-okio (see Okio.kt): through the text of the buffer.

fun <T> Json.encodeToBufferedSink(serializer: SerializationStrategy<T>, value: T, sink: BufferedSink) {
	(sink as Buffer).writeUtf8(encodeToString(serializer, value))
}

fun <T> Json.decodeFromBufferedSource(deserializer: DeserializationStrategy<T>, source: BufferedSource): T =
	decodeFromString(deserializer, (source as Buffer).readUtf8())
