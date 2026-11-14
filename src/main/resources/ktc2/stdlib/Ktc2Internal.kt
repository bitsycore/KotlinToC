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

// The iterators of the arrays of numbers: `array.iterator()` is lowered to the function of its kind.

private class BooleanArrayIterator(private val array: BooleanArray) : BooleanIterator() {
	private var index = 0

	override fun hasNext(): Boolean = index < array.size

	override fun nextBoolean(): Boolean {
		if (index >= array.size) throw NoSuchElementException()
		return array[index++]
	}
}

public fun booleanArrayIterator(array: BooleanArray): BooleanIterator = BooleanArrayIterator(array)

private class ByteArrayIterator(private val array: ByteArray) : ByteIterator() {
	private var index = 0

	override fun hasNext(): Boolean = index < array.size

	override fun nextByte(): Byte {
		if (index >= array.size) throw NoSuchElementException()
		return array[index++]
	}
}

public fun byteArrayIterator(array: ByteArray): ByteIterator = ByteArrayIterator(array)

private class ShortArrayIterator(private val array: ShortArray) : ShortIterator() {
	private var index = 0

	override fun hasNext(): Boolean = index < array.size

	override fun nextShort(): Short {
		if (index >= array.size) throw NoSuchElementException()
		return array[index++]
	}
}

public fun shortArrayIterator(array: ShortArray): ShortIterator = ShortArrayIterator(array)

private class IntArrayIterator(private val array: IntArray) : IntIterator() {
	private var index = 0

	override fun hasNext(): Boolean = index < array.size

	override fun nextInt(): Int {
		if (index >= array.size) throw NoSuchElementException()
		return array[index++]
	}
}

public fun intArrayIterator(array: IntArray): IntIterator = IntArrayIterator(array)

private class LongArrayIterator(private val array: LongArray) : LongIterator() {
	private var index = 0

	override fun hasNext(): Boolean = index < array.size

	override fun nextLong(): Long {
		if (index >= array.size) throw NoSuchElementException()
		return array[index++]
	}
}

public fun longArrayIterator(array: LongArray): LongIterator = LongArrayIterator(array)

private class CharArrayIterator(private val array: CharArray) : CharIterator() {
	private var index = 0

	override fun hasNext(): Boolean = index < array.size

	override fun nextChar(): Char {
		if (index >= array.size) throw NoSuchElementException()
		return array[index++]
	}
}

public fun charArrayIterator(array: CharArray): CharIterator = CharArrayIterator(array)

private class FloatArrayIterator(private val array: FloatArray) : FloatIterator() {
	private var index = 0

	override fun hasNext(): Boolean = index < array.size

	override fun nextFloat(): Float {
		if (index >= array.size) throw NoSuchElementException()
		return array[index++]
	}
}

public fun floatArrayIterator(array: FloatArray): FloatIterator = FloatArrayIterator(array)

private class DoubleArrayIterator(private val array: DoubleArray) : DoubleIterator() {
	private var index = 0

	override fun hasNext(): Boolean = index < array.size

	override fun nextDouble(): Double {
		if (index >= array.size) throw NoSuchElementException()
		return array[index++]
	}
}

public fun doubleArrayIterator(array: DoubleArray): DoubleIterator = DoubleArrayIterator(array)

/**
 * A String holding [length] UTF-16 code units of [chars] starting at [start].
 * Implemented by the C runtime (`ktc2_stringOfChars`), like every `external` function here.
 */
public external fun stringOfChars(chars: CharArray, start: Int, length: Int): String

/** The UTF-16 code units [startIndex, endIndex) of [string] as a new String. */
/** Writes the UTF-16 code units of [string] into [chars] from index [at]; [chars] must have the room. */
public external fun stringToChars(string: String, chars: CharArray, at: Int)

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
 * One atomic operation on element [index] of [array] (unchecked): [operation] 0 reads, 1 writes [a], 2 writes [a] and
 * gives what was there, 3 writes [b] if [a] is there and gives what was there, 4 adds [a] and gives what was there.
 */
public external fun atomicInt(array: IntArray, index: Int, operation: Int, a: Int, b: Int): Int

/** Ends the program with [status] (`kotlin.system.exitProcess`). */
public external fun exitProcess(status: Int)

/** Fills [bytes] with random bytes from the operating system, fit for secrets. */
public external fun secureRandomBytes(bytes: ByteArray)

/** [atomicInt] for a LongArray. */
public external fun atomicLong(array: LongArray, index: Int, operation: Int, a: Long, b: Long): Long

/** True when the bytes from [startIndex] to [endIndex] are not all UTF-8. */
public external fun utf8Malformed(bytes: ByteArray, startIndex: Int, endIndex: Int): Boolean

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

/** Gives up the handle of a thread nobody will join: the system then keeps nothing of it once it has ended. */
public external fun threadRelease(handle: Long)

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

/**
 * `windowedIterator` of the library (behind `chunked` and `windowed` of what is not a list), which there is a
 * coroutine: the same windows, from an iterator that keeps where it is in fields.
 */
public fun <T> kotlin_collections_windowedIterator(iterator: Iterator<T>, size: Int, step: Int, partialWindows: Boolean, reuseBuffer: Boolean): Iterator<List<T>> {
	if (!iterator.hasNext()) return emptyList<List<T>>().iterator()
	return WindowIterator(iterator, size, step, partialWindows, reuseBuffer)
}

private class WindowIterator<T>(
	private val source: Iterator<T>,
	private val size: Int,
	private val step: Int,
	private val partialWindows: Boolean,
	private val reuseBuffer: Boolean,
) : Iterator<List<T>> {
	/** What a new buffer starts with: a window may be asked as long as `Int.MAX_VALUE` (`windowed(Int.MAX_VALUE, 5)`). */
	private val initialCapacity = if (size < 1024) size else 1024

	private var buffer = ArrayList<T>(initialCapacity)

	/** Elements of the source still to pass over before the next window starts (a step longer than a window). */
	private var skip = 0

	/** The window handed out last has not been moved on from yet: that is done when the next one is asked for. */
	private var isHandedOut = false
	private var isSourceDone = false
	private var isDone = false
	private var window: List<T>? = null

	/** What follows a window once the caller comes back for more. */
	private fun moveOn() {
		if (!isHandedOut) return
		isHandedOut = false
		if (step >= size) {
			if (reuseBuffer) buffer.clear() else buffer = ArrayList(initialCapacity)
			skip = step - size
		} else {
			// Windows that overlap: the next one keeps what the step does not pass.
			val vKept = ArrayList<T>(initialCapacity)
			for (vIndex in step until buffer.size) vKept.add(buffer[vIndex])
			if (reuseBuffer) {
				buffer.clear()
				buffer.addAll(vKept)
			} else {
				buffer = vKept
			}
		}
	}

	private fun find() {
		if (window != null || isDone) return
		moveOn()
		while (!isSourceDone && source.hasNext()) {
			val vElement = source.next()
			if (skip > 0) {
				skip--
				continue
			}
			buffer.add(vElement)
			if (buffer.size == size) {
				window = buffer
				isHandedOut = true
				return
			}
		}
		val vWasDone = isSourceDone
		isSourceDone = true
		if (step >= size) {
			// What is left when the source ends, once.
			if (!vWasDone && buffer.isNotEmpty() && partialWindows) {
				window = buffer
				return
			}
		} else if (partialWindows && buffer.isNotEmpty()) {
			window = buffer
			// More than a step of it left: another, shorter window follows; otherwise this is the last.
			if (buffer.size > step) isHandedOut = true else isDone = true
			return
		}
		isDone = true
	}

	override fun hasNext(): Boolean {
		find()
		return window != null
	}

	override fun next(): List<T> {
		find()
		val vWindow = window ?: throw NoSuchElementException()
		window = null
		return vWindow
	}
}

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
/*
`removeFirst` and `removeLast` of a list are extensions of the Kotlin library; the JDK's lists
have members of those names since Java 21, which a program compiled for the JVM calls instead.
They do what the extensions do, and fail as the members do. (What only the JDK has is not here: a program has no JDK.)
*/
public fun <T> kotlin_collections_MutableList_removeFirst(list: MutableList<T>): T =
	if (list.isEmpty()) throw NoSuchElementException() else list.removeAt(0)

public fun <T> kotlin_collections_MutableList_removeLast(list: MutableList<T>): T =
	if (list.isEmpty()) throw NoSuchElementException() else list.removeAt(list.size - 1)

/** `Char(code)`: the character with that number, which has to be one. */
public fun kotlin_Char__Int(code: Int): Char {
	if (code < 0 || code > 0xFFFF) throw IllegalArgumentException("Invalid Char code: $code")
	return code.toChar()
}

public fun kotlin_Char__UShort(code: UShort): Char = code.toInt().toChar()

public fun kotlin_Int_rangeTo(start: Int, end: Int): IntRange = IntRange(start, end)

public fun kotlin_Long_rangeTo(start: Long, end: Long): LongRange = LongRange(start, end)

public fun kotlin_Char_rangeTo(start: Char, end: Char): CharRange = CharRange(start, end)

/** `a..<b` used as a value. */
public fun kotlin_Int_rangeUntil(start: Int, end: Int): IntRange = start until end

public fun kotlin_Long_rangeUntil(start: Long, end: Long): LongRange = start until end

public fun kotlin_Char_rangeUntil(start: Char, end: Char): CharRange = start until end

public fun kotlin_UInt_rangeTo(start: UInt, end: UInt): kotlin.ranges.UIntRange = kotlin.ranges.UIntRange(start, end)

public fun kotlin_ULong_rangeTo(start: ULong, end: ULong): kotlin.ranges.ULongRange = kotlin.ranges.ULongRange(start, end)

public fun kotlin_UInt_rangeUntil(start: UInt, end: UInt): kotlin.ranges.UIntRange = start until end

public fun kotlin_ULong_rangeUntil(start: ULong, end: ULong): kotlin.ranges.ULongRange = start until end

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

/**
 * What [of] holds of the exceptions that were suppressed for it (`addSuppressed`): a list, or
 * null while there is none. A field of every exception; ktc2 lowers the two calls to the field.
 */
public fun throwableSuppressed(of: Throwable): Any? = null

public fun setThrowableSuppressed(of: Throwable, list: Any?) {
}

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

/** True when [value] is of the class called [name] or of a class under it. */
public external fun classIsInstance(value: Any, name: String): Boolean

/** The Kotlin name of the class of [value], as the runtime's table of types has it. */
public external fun className(value: Any): String

/**
 * `x::class` and `Name::class`: a class as a value. There is no reflection behind it: it knows
 * its name, and two of them are equal when they name the same class. Everything else a
 * `KClass` offers fails.
 */
public class ClassReference(private val mName: String) : kotlin.reflect.KClass<Any> {
	private fun unavailable(): Nothing = throw UnsupportedOperationException("Reflection is not available: class '$mName'")

	override val simpleName: String? get() = mName.substringAfterLast('.')
	override val qualifiedName: String? get() = mName

	override val members: Collection<kotlin.reflect.KCallable<*>> get() = unavailable()
	override val constructors: Collection<kotlin.reflect.KFunction<Any>> get() = unavailable()
	override val nestedClasses: Collection<kotlin.reflect.KClass<*>> get() = unavailable()
	override val objectInstance: Any? get() = unavailable()
	override val typeParameters: List<kotlin.reflect.KTypeParameter> get() = unavailable()
	override val supertypes: List<kotlin.reflect.KType> get() = unavailable()
	override val sealedSubclasses: List<kotlin.reflect.KClass<out Any>> get() = unavailable()
	override val visibility: kotlin.reflect.KVisibility? get() = unavailable()
	override val annotations: List<Annotation> get() = unavailable()
	override val isFinal: Boolean get() = unavailable()
	override val isOpen: Boolean get() = unavailable()
	override val isAbstract: Boolean get() = unavailable()
	override val isSealed: Boolean get() = unavailable()
	override val isData: Boolean get() = unavailable()
	override val isInner: Boolean get() = unavailable()
	override val isCompanion: Boolean get() = unavailable()
	override val isFun: Boolean get() = unavailable()
	override val isValue: Boolean get() = unavailable()

	/** For a class: an object of it or of a class under it. Of an interface nothing is known here: false. */
	override fun isInstance(value: Any?): Boolean = value != null && classIsInstance(value, mName)

	override fun equals(other: Any?): Boolean = other is ClassReference && other.mName == mName

	override fun hashCode(): Int = mName.hashCode()

	override fun toString(): String = "class $mName"
}

/** `Name::class`. */
public fun classReference(name: String): Any = ClassReference(name)

/** `x::class` for an object. */
public fun classOf(value: Any): Any = ClassReference(className(value))

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

/** A `lateinit` property or variable read before it was given a value. */
public fun throwUninitialized(name: String): Nothing =
	throw UninitializedPropertyAccessException("lateinit property $name has not been initialized")

/** A failed `as` cast. */
public fun throwClassCast(): Nothing = throw ClassCastException()

/** An array asked for with a length below zero. */
public fun throwNegativeArraySize(size: Int): Nothing = throw NegativeArraySizeException(size.toString())

/** `containsAll` of a collection of numbers that is asked as a collection of objects: [elements] may hold anything. */
public fun containsAllOfAny(collection: Collection<Any?>, elements: Collection<Any?>): Boolean {
	for (vElement in elements) if (!collection.contains(vElement)) return false
	return true
}

/**
 * What a cleaner that is kept as an object is (`ktc2.createCleaner`): the runtime calls [deinit]
 * once, when the last reference to the object goes and before its properties are given up.
 * Not for programs: theirs is `createCleaner`, which the JVM and Kotlin/Native have too.
 */
public interface Deinit {
	public fun deinit()
}

// ==================
// MARK: Mutable property references
// ==================

/*
A reference to a `var` (`::top`, `data::count`) where Kotlin wants a KMutableProperty0 or 1: it reads as
the function that reads the property (`get()` is lowered to `invoke`) and holds the function that writes it
(`set` is lowered to [mutableReferenceSet0] and [mutableReferenceSet1]).
*/

public class MutableReference0<V>(private val getter: () -> V, private val setter: (V) -> Unit) : () -> V {
	override fun invoke(): V = getter()
	public fun set(value: V): Unit = setter(value)
}

public class MutableReference1<T, V>(private val getter: (T) -> V, private val setter: (T, V) -> Unit) : (T) -> V {
	override fun invoke(receiver: T): V = getter(receiver)
	public fun set(receiver: T, value: V): Unit = setter(receiver, value)
}

public fun <V> mutableReference0(getter: () -> V, setter: (V) -> Unit): Any = MutableReference0(getter, setter)

public fun <T, V> mutableReference1(getter: (T) -> V, setter: (T, V) -> Unit): Any = MutableReference1(getter, setter)

public fun <V> mutableReferenceSet0(reference: Any, value: V) {
	(reference as MutableReference0<V>).set(value)
}

public fun <T, V> mutableReferenceSet1(reference: Any, receiver: T, value: V) {
	(reference as MutableReference1<T, V>).set(receiver, value)
}

// ==================
// MARK: Sequences without coroutines
// ==================

/*
The functions of the library written with `sequence { }`, which needs coroutines, as iterators that keep where
they are in fields. Each does what the library's body does, in the same order: nothing before the first element
is asked for, the source's iterator asked for where the body asks for it, the same calls to the lambdas and to
`Random`. A call to the library's function is lowered to the one here of the same name (`kWithoutCoroutine`).
*/

/** An iterator that works out its next element only when asked: [step] calls [emit] once, or returns false at the end. */
private abstract class Generator<T> : Iterator<T> {
	private var state = 0
	private var pending: Any? = null

	/** Works out the next element and gives it to [emit]; false when there is none. */
	protected abstract fun step(): Boolean

	protected fun emit(value: T) {
		pending = value
	}

	override fun hasNext(): Boolean {
		if (state == 0) state = if (step()) 1 else 2
		return state == 1
	}

	override fun next(): T {
		if (!hasNext()) throw NoSuchElementException()
		state = 0
		val vValue = pending as T
		pending = null
		return vValue
	}
}

private class RunningFold<T, R>(private val source: Sequence<T>, initial: R, private val operation: (Int, R, T) -> R) : Generator<R>() {
	private var accumulator: R = initial
	private var iterator: Iterator<T>? = null
	private var index = 0

	override fun step(): Boolean {
		val vIterator = iterator
		if (vIterator == null) {
			iterator = source.iterator()
			emit(accumulator)
			return true
		}
		if (!vIterator.hasNext()) return false
		accumulator = operation(kotlin.collections.checkIndexOverflow(index++), accumulator, vIterator.next())
		emit(accumulator)
		return true
	}
}

private class RunningReduce<S, T : S>(private val source: Sequence<T>, private val operation: (Int, S, T) -> S) : Generator<S>() {
	private var iterator: Iterator<T>? = null
	private var accumulator: Any? = null
	private var index = 1

	override fun step(): Boolean {
		var vIterator = iterator
		if (vIterator == null) {
			vIterator = source.iterator()
			iterator = vIterator
			if (!vIterator.hasNext()) return false
			val vFirst: S = vIterator.next()
			accumulator = vFirst
			emit(vFirst)
			return true
		}
		if (!vIterator.hasNext()) return false
		val vNext = operation(kotlin.collections.checkIndexOverflow(index++), accumulator as S, vIterator.next())
		accumulator = vNext
		emit(vNext)
		return true
	}
}

private class ZipWithNext<T, R>(private val source: Sequence<T>, private val transform: (T, T) -> R) : Generator<R>() {
	private var iterator: Iterator<T>? = null
	private var current: Any? = null

	override fun step(): Boolean {
		var vIterator = iterator
		if (vIterator == null) {
			vIterator = source.iterator()
			iterator = vIterator
			if (!vIterator.hasNext()) return false
			current = vIterator.next()
		}
		if (!vIterator.hasNext()) return false
		val vNext = vIterator.next()
		emit(transform(current as T, vNext))
		current = vNext
		return true
	}
}

private class IfEmpty<T>(private val source: Sequence<T>, private val defaultValue: () -> Sequence<T>) : Generator<T>() {
	private var iterator: Iterator<T>? = null

	override fun step(): Boolean {
		var vIterator = iterator
		if (vIterator == null) {
			vIterator = source.iterator()
			if (!vIterator.hasNext()) vIterator = defaultValue().iterator()
			iterator = vIterator
		}
		if (!vIterator.hasNext()) return false
		emit(vIterator.next())
		return true
	}
}

private class Shuffled<T>(private val source: Sequence<T>, private val random: kotlin.random.Random) : Generator<T>() {
	private var buffer: MutableList<T>? = null

	override fun step(): Boolean {
		var vBuffer = buffer
		if (vBuffer == null) {
			vBuffer = source.toMutableList()
			buffer = vBuffer
		}
		if (vBuffer.isEmpty()) return false
		val vAt = random.nextInt(vBuffer.size)
		val vLast = vBuffer.removeLast()
		emit(if (vAt < vBuffer.size) vBuffer.set(vAt, vLast) else vLast)
		return true
	}
}

private class FlatMapIndexed<T, C, R>(private val source: Sequence<T>, private val transform: (Int, T) -> C, private val iterator: (C) -> Iterator<R>) : Generator<R>() {
	private var outer: Iterator<T>? = null
	private var inner: Iterator<R>? = null
	private var index = 0

	override fun step(): Boolean {
		var vOuter = outer
		if (vOuter == null) {
			vOuter = source.iterator()
			outer = vOuter
		}
		while (true) {
			val vInner = inner
			if (vInner != null && vInner.hasNext()) {
				emit(vInner.next())
				return true
			}
			if (!vOuter.hasNext()) return false
			inner = iterator(transform(kotlin.collections.checkIndexOverflow(index++), vOuter.next()))
		}
	}
}

public fun <T, R> kotlin_sequences_runningFold(source: Sequence<T>, initial: R, operation: (R, T) -> R): Sequence<R> =
	Sequence { RunningFold(source, initial) { _, vAccumulator: R, vElement: T -> operation(vAccumulator, vElement) } }

public fun <T, R> kotlin_sequences_runningFoldIndexed(source: Sequence<T>, initial: R, operation: (Int, R, T) -> R): Sequence<R> =
	Sequence { RunningFold(source, initial, operation) }

public fun <S, T : S> kotlin_sequences_runningReduce(source: Sequence<T>, operation: (S, T) -> S): Sequence<S> =
	Sequence { RunningReduce<S, T>(source) { _, vAccumulator: S, vElement: T -> operation(vAccumulator, vElement) } }

public fun <S, T : S> kotlin_sequences_runningReduceIndexed(source: Sequence<T>, operation: (Int, S, T) -> S): Sequence<S> =
	Sequence { RunningReduce(source, operation) }

public fun <T, R> kotlin_sequences_zipWithNext(source: Sequence<T>, transform: (T, T) -> R): Sequence<R> =
	Sequence { ZipWithNext(source, transform) }

public fun <T> kotlin_sequences_ifEmpty(source: Sequence<T>, defaultValue: () -> Sequence<T>): Sequence<T> =
	Sequence { IfEmpty(source, defaultValue) }

public fun <T> kotlin_sequences_shuffled(source: Sequence<T>, random: kotlin.random.Random): Sequence<T> =
	Sequence { Shuffled(source, random) }

public fun <T, C, R> kotlin_sequences_flatMapIndexed(source: Sequence<T>, transform: (Int, T) -> C, iterator: (C) -> Iterator<R>): Sequence<R> =
	Sequence { FlatMapIndexed(source, transform, iterator) }

// ==================
// MARK: Types (typeOf)
// ==================

/**
 * What `typeOf<T>()` gives: the class, the projections of the arguments and whether it may be null. The
 * compiler builds it where `typeOf` is written, from the type T stands for there. It is written out as the
 * JVM writes it (`kotlin.collections.List<kotlin.String>?`) and compared by what it is made of.
 */
public class TypeReference(
	override val classifier: kotlin.reflect.KClassifier?,
	override val arguments: List<kotlin.reflect.KTypeProjection>,
	override val isMarkedNullable: Boolean,
) : kotlin.reflect.KType {
	override val annotations: List<Annotation> get() = emptyList()

	override fun equals(other: Any?): Boolean =
		other is TypeReference && classifier == other.classifier && arguments == other.arguments && isMarkedNullable == other.isMarkedNullable

	override fun hashCode(): Int = (classifier.hashCode() * 31 + arguments.hashCode()) * 31 + isMarkedNullable.hashCode()

	override fun toString(): String {
		val vName = (classifier as? kotlin.reflect.KClass<*>)?.qualifiedName ?: classifier.toString()
		val vArguments = if (arguments.isEmpty()) "" else arguments.joinToString(", ", "<", ">")
		return vName + vArguments + if (isMarkedNullable) "?" else ""
	}
}

/** A [TypeReference]: [arguments] holds KTypeProjections. */
public fun typeReference(classifier: Any, nullable: Boolean, arguments: Array<Any?>): Any {
	val vArguments = ArrayList<kotlin.reflect.KTypeProjection>(arguments.size)
	for (vArgument in arguments) vArguments.add(vArgument as kotlin.reflect.KTypeProjection)
	return TypeReference(classifier as kotlin.reflect.KClassifier, vArguments, nullable)
}

/** The projection of a type argument: [variance] -1 for a star, 0 invariant, 1 `in`, 2 `out`. */
public fun typeProjection(variance: Int, type: Any?): Any = when (variance) {
	-1 -> kotlin.reflect.KTypeProjection.STAR
	1 -> kotlin.reflect.KTypeProjection.contravariant(type as kotlin.reflect.KType)
	2 -> kotlin.reflect.KTypeProjection.covariant(type as kotlin.reflect.KType)
	else -> kotlin.reflect.KTypeProjection.invariant(type as kotlin.reflect.KType)
}
