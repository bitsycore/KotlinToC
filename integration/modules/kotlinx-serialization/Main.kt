import kotlinx.serialization.*
import kotlinx.serialization.builtins.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*
import kotlinx.serialization.modules.*

class Point(val x: Int, val y: Int)

object PointSerializer : KSerializer<Point> {
	override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Point") {
		element<Int>("x")
		element<Int>("y")
	}

	override fun serialize(encoder: Encoder, value: Point) {
		encoder.encodeStructure(descriptor) {
			encodeIntElement(descriptor, 0, value.x)
			encodeIntElement(descriptor, 1, value.y)
		}
	}

	override fun deserialize(decoder: Decoder): Point = throw UnsupportedOperationException()
}

@OptIn(ExperimentalSerializationApi::class)
class TextEncoder(val out: StringBuilder) : AbstractEncoder() {
	override val serializersModule: SerializersModule = EmptySerializersModule()
	override fun encodeValue(value: Any) { out.append(value).append(' ') }
	override fun encodeNull() { out.append("null ") }
	override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder { out.append(descriptor.serialName).append("{ "); return this }
	override fun endStructure(descriptor: SerialDescriptor) { out.append("} ") }
}

fun <T> show(serializer: SerializationStrategy<T>, value: T): String {
	val vOut = StringBuilder()
	TextEncoder(vOut).encodeSerializableValue(serializer, value)
	return vOut.toString().trim()
}

fun main() {
	println(show(Int.serializer(), 42))
	println(show(ListSerializer(String.serializer()), listOf("a", "b")))
	println(show(MapSerializer(String.serializer(), Int.serializer()), mapOf("one" to 1)))
	println(show(PointSerializer, Point(1, 2)))
	println(show(ListSerializer(PointSerializer.nullable), listOf(Point(3, 4), null)))
	println(ListSerializer(Int.serializer()).descriptor.serialName)
	println(PointSerializer.descriptor.elementNames.toList())
}
