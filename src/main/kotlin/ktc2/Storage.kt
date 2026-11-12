package ktc2

// ==================
// MARK: Storage hints
// ==================

/**
 * Use-site storage hint: the object is stored inline in its slot (a field or a local)
 * instead of behind a counted reference. Ignored on the JVM.
 *
 * Written on the type: `val origin: @Value Vec2`. For the elements of an array there is
 * [ValueArray]: `Array<@Value T>` is not accepted.
 */
@Target(AnnotationTarget.TYPE)
@Retention(AnnotationRetention.SOURCE)
annotation class Value

/**
 * Declaration-site form of [Value]: every slot of the annotated class is stored inline.
 * The class must only declare `val` properties.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class Struct

/**
 * On an `enum class`: its values are numbers, a C `enum`, and not objects. An entry is the
 * constant, `==` and `when` compare numbers, and `name`, `ordinal` and the properties of the
 * constructor are looked up from the number. Where Kotlin needs an object (`Any`, a nullable,
 * a type argument as in `List<Key>`) the value is replaced by an object kept for its entry,
 * which is an ordinary `Enum`.
 *
 * What cannot be a number is refused: an entry with a body, a property that is not a `val` of
 * the constructor, a constructor argument that is not a constant. On the JVM the class is the
 * enum it is declared as.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class SimpleEnum

/**
 * On one `Int` parameter of the constructor of a [SimpleEnum] class: the number of an entry in
 * C is the value it gives for this parameter (`enum { Key_A = 4 }`), which has to be different
 * for each entry. Without it the number is the ordinal.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.SOURCE)
annotation class EnumValue

/**
 * Fixed element count of an array slot, stored inline in its owner or, for a local, in the frame.
 *
 * @property inN element count, a compile-time constant
 */
@Target(AnnotationTarget.TYPE, AnnotationTarget.PROPERTY, AnnotationTarget.LOCAL_VARIABLE)
@Retention(AnnotationRetention.SOURCE)
annotation class Size(val inN: Int)

/**
 * An array that stores its elements themselves: numbers, `@Struct` values and value classes
 * lie side by side in one block, with nothing boxed (an `Array<Point>` holds a reference per
 * element). For any other element type it is an ordinary array. On the JVM it behaves the
 * same and is stored however the JVM stores it.
 *
 * @param inSize number of elements
 * @param inInit gives the element at each index
 */
class ValueArray<T>(inSize: Int, inInit: (Int) -> T) : Iterable<T> {

	private val mItems = ArrayList<T>(inSize)

	init {
		for (vIndex in 0 until inSize) mItems.add(inInit(vIndex))
	}

	val size: Int get() = mItems.size

	val indices: IntRange get() = 0 until mItems.size

	operator fun get(inIndex: Int): T = mItems[inIndex]

	operator fun set(inIndex: Int, inValue: T) {
		mItems[inIndex] = inValue
	}

	override fun iterator(): Iterator<T> = mItems.iterator()
}

/**
 * Runs [inBlock] with every object it creates - directly or in the functions it calls -
 * carved out of blocks of [inSize] bytes that are freed together when the block returns.
 * An object still referred to afterwards (the result, typically) stays valid: it keeps its
 * block alive until it is released itself. On the JVM this only runs the block.
 */
@Suppress("UNUSED_PARAMETER")
inline fun <R> arena(inSize: Int, inBlock: () -> R): R = inBlock()
