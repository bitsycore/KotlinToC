/* Compared with TypeOf.expected: the JVM without kotlin-reflect writes a type with Java names and "(Kotlin reflection is not available)". The expected text is what Kotlin/Native 2.4.21 prints for this program (checked). */
import kotlin.reflect.KTypeProjection
import kotlin.reflect.KVariance
import kotlin.reflect.typeOf

/** `typeOf<T>()`: the class, the arguments and whether it may be null, compared and written out. */
class Box<T>(val item: T)

data class Point(val x: Int, val y: Int)

enum class Color { RED }

inline fun <reified T> describe(): String {
	val type = typeOf<T>()
	return "$type nullable=${type.isMarkedNullable} arguments=${type.arguments}"
}

inline fun <reified T> listOfT() = typeOf<List<T>>()

fun main() {
	println(typeOf<Int>())
	println(typeOf<Int?>())
	println(typeOf<String>())
	println(typeOf<List<String>>())
	println(typeOf<Map<String, List<Int?>>>())
	println(typeOf<Array<out Number>>())
	println(typeOf<MutableList<in Int>>())
	println(typeOf<List<*>>())
	println(typeOf<IntArray>())
	println(typeOf<Box<Point>>())
	println(typeOf<Color>())
	println(typeOf<Unit>())
	println(typeOf<Any?>())
	println(typeOf<Pair<Int, String>>())
	println(describe<Box<Int>?>())
	println(listOfT<Double>())
	println(typeOf<List<String>>() == typeOf<List<String>>())
	println(typeOf<List<String>>() == typeOf<List<Int>>())
	println(typeOf<Int>() == typeOf<Int?>())
	println(typeOf<List<String>>().hashCode() == typeOf<List<String>>().hashCode())
	println(typeOf<Point>().classifier == Point::class)
	println(typeOf<List<Int>>().arguments[0].type == typeOf<Int>())
	println(typeOf<List<Int>>().arguments[0].variance)
	println(KTypeProjection.STAR)
	println(KTypeProjection.covariant(typeOf<Int>()))
	println(KTypeProjection(KVariance.IN, typeOf<String>()))
	println(typeOf<List<*>>().arguments[0] == KTypeProjection.STAR)
}
