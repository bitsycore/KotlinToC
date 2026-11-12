@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "UNCHECKED_CAST")

package ktc2.internal

/*
ktc2 platform layer: what the Kotlin standard library expects its platform to provide.
The functions here are the few primitives the rest is written on.
*/

/**
 * A new array of [size] elements of type [T], zero-initialized.
 *
 * ktc2 lowers a call to this directly (the body is never used): the element type is the one
 * [T] stands for in the instantiation being compiled, so `newArray<Int>` is an array of ints.
 */
public fun <T> newArray(size: Int): Array<T> = arrayOfNulls<Any?>(size) as Array<T>

/**
 * A fixed-size block of [size] values of type [T], stored as they are: a `Storage<Int>` is a
 * block of ints, where an `Array<Int>` holds boxed values as Kotlin requires.
 *
 * ktc2 lowers the type and its members directly; the bodies here are never used.
 */
public class Storage<T>(size: Int) {
	public val size: Int = size

	public operator fun get(index: Int): T = throw UnsupportedOperationException()

	public operator fun set(index: Int, value: T) {
	}

	/** `get` without the test of [index], for code that has made sure of it. */
	public fun at(index: Int): T = throw UnsupportedOperationException()

	/** `set` without the test of [index], for code that has made sure of it. */
	public fun put(index: Int, value: T) {
	}

	/**
	 * Empties the slot at [index], for a collection that no longer has an element there: a
	 * reference is given up, so the object does not outlive its removal. Nothing for numbers.
	 */
	public fun drop(index: Int) {
	}
}

/** Iterates an array; `array.iterator()` is lowered to [arrayIterator]. */
public class ArrayIterator<T>(private val array: Array<out T>) : Iterator<T> {
	private var index = 0

	override fun hasNext(): Boolean = index < array.size

	override fun next(): T {
		if (index >= array.size) throw NoSuchElementException()
		return array[index++]
	}
}

public fun <T> arrayIterator(array: Array<out T>): Iterator<T> = ArrayIterator(array)

/**
 * A String holding [length] UTF-16 code units of [chars] starting at [start].
 * Implemented by the C runtime (`ktc2_stringOfChars`), like every `external` function here.
 */
public external fun stringOfChars(chars: CharArray, start: Int, length: Int): String

/** The UTF-16 code units [startIndex, endIndex) of [string] as a new String. */
public external fun stringSubstring(string: String, startIndex: Int, endIndex: Int): String

/** Parses [string] as `Double.parseDouble` does into `result[0]`; false when it is not a number. */
public external fun stringToDouble(string: String, result: DoubleArray): Boolean

/**
 * Where [other] is found in [string] at or after [fromIndex], or with [isBackwards] at or
 * before it: the index, -1 when it is not there, -2 when the runtime leaves the search to
 * Kotlin code (it only searches text that is all ASCII, where a character is a byte).
 */
public external fun stringFind(string: String, other: String, fromIndex: Int, isBackwards: Boolean): Int

/** The same for one character. */
public external fun stringFindChar(string: String, char: Char, fromIndex: Int, isBackwards: Boolean): Int

/**
 * Whether [length] characters of [string] from [offset] are those of [other] from
 * [otherOffset]: 1 or 0, or -2 when the runtime leaves the comparison to Kotlin code (one of
 * the strings is not all ASCII). The offsets are inside the strings.
 */
public external fun stringRegionEquals(string: String, offset: Int, other: String, otherOffset: Int, length: Int): Int

/** The UTF-8 bytes of [string]. */
public external fun stringUtf8(string: String): ByteArray

/** A String from the UTF-8 bytes [startIndex, endIndex) of [bytes]. */
public external fun stringOfUtf8(bytes: ByteArray, startIndex: Int, endIndex: Int): String

/**
 * What kind of array [value] is: 0 when it is not one (or is an array of unsigned or nullable
 * values), 1 for an `Array<T>`, 2 to 9 for BooleanArray, ByteArray, ShortArray, IntArray,
 * LongArray, CharArray, FloatArray, DoubleArray.
 */
public external fun arrayKind(value: Any?): Int

/** The weak cell of [target]: refers to it without keeping it alive. Null for null. */
public external fun weakCell(target: Any?): Any?

/** The target of a weak [cell], or null once it has been destroyed. */
public external fun weakGet(cell: Any?): Any?

/** Runs [thread] on a new thread, entering through [threadEntry]; returns the handle [threadJoin] takes. */
public external fun threadStart(thread: Any): Long

/** Waits for the thread behind [handle] to end. */
public external fun threadJoin(handle: Long)

/** Suspends the calling thread for [milliseconds]; returns at once for zero or less. */
public external fun threadSleep(milliseconds: Long)

/** The next line of the standard input as UTF-8 bytes, without its line end; null at the end of the input. */
public external fun readLineBytes(): ByteArray?

/** The time of day, in nanoseconds since 1970. */
public external fun timeNanos(): Long

/** A counter that only goes forward, in nanoseconds from some moment: for measuring durations. */
public external fun ticksNanos(): Long

/** What `Any()` creates. */
private class PlainObject

/** A new object with nothing in it: `Any()`. */
public fun newObject(): Any = PlainObject()

/** Where the runtime enters a new thread. */
public fun threadEntry(thread: Any) {
	(thread as ktc2.concurrent.Thread).run()
}

/** Takes the program-wide lock behind `synchronized`; recursive. */
public external fun monitorEnter(lock: Any)

public external fun monitorExit(lock: Any)

/** Opens an arena with blocks of [size] bytes on this thread; returns the handle [arenaLeave] takes. */
public external fun arenaEnter(size: Int): Long

public external fun arenaLeave(arena: Long)

// ==================
// MARK: Library functions provided here
// ==================

/*
A library function that has no Kotlin body ktc2 can read (it is implemented on the JVM side)
is redirected to the function here named after it, dots replaced by underscores.
*/

/** `Array<out T>.asList()` */
public fun <T> kotlin_collections_asList(array: Array<out T>): List<T> {
	val vList = ArrayList<T>(array.size)
	for (vElement in array) vList.add(vElement)
	return vList
}

/** `Array<out T>.toSet()` */
public fun <T> kotlin_collections_toSet(array: Array<out T>): Set<T> {
	val vSet = LinkedHashSet<T>(array.size)
	for (vElement in array) vSet.add(vElement)
	return vSet
}

/** `Array<out T>.sort()` for comparable elements: a stable insertion sort. */
public fun <T : Comparable<T>> kotlin_collections_sort(array: Array<T>) {
	for (vIndex in 1 until array.size) {
		val vValue = array[vIndex]
		var vHole = vIndex
		while (vHole > 0 && array[vHole - 1].compareTo(vValue) > 0) {
			array[vHole] = array[vHole - 1]
			vHole--
		}
		array[vHole] = vValue
	}
}

/** `CharArray.single()` */
public fun kotlin_collections_single__CharArray(array: CharArray): Char {
	if (array.size != 1) throw IllegalArgumentException(if (array.size == 0) "Array is empty." else "Array has more than one element.")
	return array[0]
}

/** `CharArray.any { }` */
public inline fun kotlin_collections_any__CharArray(array: CharArray, predicate: (Char) -> Boolean): Boolean {
	for (vElement in array) {
		if (predicate(vElement)) return true
	}
	return false
}

/** `a..b` used as a value (a loop over a range does not build one). */
public fun kotlin_Int_rangeTo(start: Int, end: Int): IntRange = IntRange(start, end)

public fun kotlin_Long_rangeTo(start: Long, end: Long): LongRange = LongRange(start, end)

public fun kotlin_Char_rangeTo(start: Char, end: Char): CharRange = CharRange(start, end)

/** `a..<b` used as a value. */
public fun kotlin_Int_rangeUntil(start: Int, end: Int): IntRange = start until end

public fun kotlin_Long_rangeUntil(start: Long, end: Long): LongRange = start until end

public fun kotlin_Char_rangeUntil(start: Char, end: Char): CharRange = start until end

// ==================
// MARK: Failures raised by generated code
// ==================

/** `x!!` on null. */
public fun throwNullPointer(): Nothing = throw NullPointerException()

/** An array index outside the array. */
public fun throwIndexOutOfBounds(index: Int, size: Int): Nothing =
	throw ArrayIndexOutOfBoundsException("Index $index out of bounds for length $size")

/** A string index outside the string. */
public fun throwStringIndexOutOfBounds(index: Int, length: Int): Nothing =
	throw StringIndexOutOfBoundsException("Index $index out of bounds for length $length")

/** A range that is not inside the string, given to `substring` or `subSequence`. */
public fun throwSubstringRange(begin: Int, end: Int, length: Int): Nothing =
	throw StringIndexOutOfBoundsException("Range [$begin, $end) out of bounds for length $length")

/** An integer division or remainder by zero. */
public fun throwDivideByZero(): Nothing = throw ArithmeticException("/ by zero")

/** `valueOf` of the enum class [enumName] was given [name], which no entry has. */
public fun throwNoEnumConstant(enumName: String, name: String): Nothing = throw IllegalArgumentException("No enum constant $enumName.$name")

/** `Enum.entries`: the constants of an enum class as a list. */
public fun <E : Enum<E>> enumEntriesOf(values: Array<E>): kotlin.enums.EnumEntries<E> = kotlin.enums.enumEntries(values)

/**
 * What a delegate is told about the property it serves (`getValue(thisRef, property)`): its
 * name. There is no reflection behind it - everything else a `KProperty` offers fails.
 */
public class PropertyReference(override val name: String) : kotlin.reflect.KProperty<Any?> {
	private fun unavailable(): Nothing = throw UnsupportedOperationException("Reflection is not available: property '$name'")

	override val annotations: List<Annotation> get() = unavailable()
	override val parameters: List<kotlin.reflect.KParameter> get() = unavailable()
	override val returnType: kotlin.reflect.KType get() = unavailable()
	override val typeParameters: List<kotlin.reflect.KTypeParameter> get() = unavailable()
	override val visibility: kotlin.reflect.KVisibility? get() = unavailable()
	override val isFinal: Boolean get() = unavailable()
	override val isOpen: Boolean get() = unavailable()
	override val isAbstract: Boolean get() = unavailable()
	override val isSuspend: Boolean get() = unavailable()
	@OptIn(kotlin.reflect.ExperimentalCompanionExtensions::class)
	override val companionExtensionClass: kotlin.reflect.KClass<*>? get() = unavailable()
	override val container: kotlin.reflect.KDeclarationContainer? get() = unavailable()
	override val isLateinit: Boolean get() = unavailable()
	override val isConst: Boolean get() = unavailable()
	override val getter: kotlin.reflect.KProperty.Getter<Any?> get() = unavailable()

	override fun call(vararg args: Any?): Any? = unavailable()

	override fun callBy(args: Map<kotlin.reflect.KParameter, Any?>): Any? = unavailable()

	override fun toString(): String = "property $name"
}

public fun propertyReference(name: String): Any = PropertyReference(name)

/** How a data or value class describes a property that is an array: by its content. */
public fun kotlin_internal_ir_dataClassArrayMemberToString(value: Any?): String = when (arrayKind(value)) {
	1 -> (value as Array<*>).contentToString()
	2 -> (value as BooleanArray).contentToString()
	3 -> (value as ByteArray).contentToString()
	4 -> (value as ShortArray).contentToString()
	5 -> (value as IntArray).contentToString()
	6 -> (value as LongArray).contentToString()
	7 -> (value as CharArray).contentToString()
	8 -> (value as FloatArray).contentToString()
	9 -> (value as DoubleArray).contentToString()
	else -> value.toString()
}

public fun kotlin_internal_ir_dataClassArrayMemberHashCode(value: Any?): Int = when (arrayKind(value)) {
	1 -> (value as Array<*>).contentHashCode()
	2 -> (value as BooleanArray).contentHashCode()
	3 -> (value as ByteArray).contentHashCode()
	4 -> (value as ShortArray).contentHashCode()
	5 -> (value as IntArray).contentHashCode()
	6 -> (value as LongArray).contentHashCode()
	7 -> (value as CharArray).contentHashCode()
	8 -> (value as FloatArray).contentHashCode()
	9 -> (value as DoubleArray).contentHashCode()
	else -> value?.hashCode() ?: 0
}

/** What is reached through coroutines, which are not there. */
public fun throwNoCoroutines(): Nothing =
	throw UnsupportedOperationException("coroutines are not supported: this is reached through sequence { } or iterator { }")

/** A failed `as` cast. */
public fun throwClassCast(): Nothing = throw ClassCastException()
