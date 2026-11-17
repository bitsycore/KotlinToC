// A class as a value: `x::class` and `Name::class` compared, and their names.

package classes

open class Animal
class Dog : Animal()
class Cat : Animal()
class Outer { class Nested; inner class Inner }
data class Pair2(val a: Int)
object Single
enum class Color { RED }
interface Marker
class Generic<T>(val value: T)

inline fun <reified T> nameOf(): String? = T::class.simpleName
inline fun <reified T> isExactly(inValue: Any): Boolean = inValue::class == T::class

fun main() {
	val vAnimals: List<Animal> = listOf(Dog(), Cat(), Animal())
	println(vAnimals.map { it::class.simpleName })
	println(vAnimals.map { it::class == Dog::class })
	println(vAnimals.map { it::class == Animal::class })
	println("${vAnimals[0]::class == vAnimals[0]::class} ${vAnimals[0]::class == vAnimals[1]::class} ${Dog::class == Dog::class} ${Dog::class != Cat::class} ${Dog::class.hashCode() == Dog()::class.hashCode()}")
	println("${Dog::class.simpleName} ${Dog::class.qualifiedName} ${Outer.Nested::class.simpleName} ${Outer.Nested::class.qualifiedName} ${Outer().Inner()::class.simpleName} ${Pair2(1)::class.simpleName} ${Single::class.simpleName} ${Color.RED::class.simpleName} ${Marker::class.simpleName} ${Generic(1)::class.simpleName} ${Generic("s")::class == Generic(1)::class} ${Generic::class.qualifiedName}")
	val vValues: List<Any> = listOf(1, 2L, "text", 1.5, 'c', true, 2.5f, Dog(), Unit)
	println(vValues.map { it::class.simpleName })
	println(vValues.map { it::class == Int::class || it::class == String::class })
	println("${1::class == Int::class} ${"s"::class == String::class} ${1.5::class.simpleName} ${String::class.qualifiedName} ${Int::class.qualifiedName} ${Any::class.simpleName} ${(1 as Any)::class == Int::class} ${(1 as Any)::class == Long::class}")
	println("${nameOf<Dog>()} ${nameOf<String>()} ${nameOf<Int>()} ${nameOf<List<Int>>()} ${isExactly<Dog>(Dog())} ${isExactly<Animal>(Dog())} ${isExactly<Int>(5)}")
	val vByClass = hashMapOf(Dog::class to "dog", Cat::class to "cat")
	println("${vByClass[vAnimals[0]::class]} ${vByClass[vAnimals[1]::class]} ${vByClass[vAnimals[2]::class]} ${vByClass.size}")
	val vError: Throwable = IllegalStateException("x")
	println("${ArrayList<Int>()::class == ArrayList::class} ${HashMap<String, Int>()::class == HashMap::class} ${StringBuilder()::class == StringBuilder::class} ${ArrayList<Int>()::class.simpleName} ${NoSuchElementException()::class == NoSuchElementException::class}")
	println("${vError::class.simpleName} ${vError::class == IllegalStateException::class} ${vError::class == RuntimeException::class} ${when (vError::class) { IllegalArgumentException::class -> "argument"; IllegalStateException::class -> "state"; else -> "other" }}")
}
