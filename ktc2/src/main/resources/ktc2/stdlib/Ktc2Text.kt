@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "NOTHING_TO_INLINE", "ACTUAL_ANNOTATIONS_NOT_MATCH_EXPECT")

package kotlin.text

import ktc2.internal.stringOfChars

/*
ktc2 platform layer for kotlin.text: Appendable and StringBuilder.
*/

public actual interface Appendable {
	public actual fun append(value: Char): Appendable
	public actual fun append(value: CharSequence?): Appendable
	public actual fun append(value: CharSequence?, startIndex: Int, endIndex: Int): Appendable
}

/**
 * A growable buffer of UTF-16 code units.
 */
public actual class StringBuilder actual constructor(capacity: Int) : Appendable, CharSequence {

	private var chars: CharArray = CharArray(if (capacity > 16) capacity else 16)
	private var count: Int = 0

	public actual constructor() : this(16)

	public actual constructor(content: CharSequence) : this(content.length + 16) {
		append(content)
	}

	public actual constructor(content: String) : this(content.length + 16) {
		append(content)
	}

	actual override val length: Int get() = count

	actual override operator fun get(index: Int): Char {
		if (index < 0 || index >= count) throw IndexOutOfBoundsException("index: $index, length: $count")
		return chars[index]
	}

	actual override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = substring(startIndex, endIndex)

	public actual fun capacity(): Int = chars.size

	public actual fun ensureCapacity(minimumCapacity: Int) {
		if (minimumCapacity <= chars.size) return
		var vCapacity = chars.size * 2 + 2
		if (vCapacity < minimumCapacity) vCapacity = minimumCapacity
		val vBigger = CharArray(vCapacity)
		for (vIndex in 0 until count) vBigger[vIndex] = chars[vIndex]
		chars = vBigger
	}

	public actual fun trimToSize() {
	}

	// ============
	//  append

	actual override fun append(value: Char): StringBuilder {
		ensureCapacity(count + 1)
		chars[count] = value
		count++
		return this
	}

	actual override fun append(value: CharSequence?): StringBuilder {
		val vSource: CharSequence = value ?: "null"
		return append(vSource, 0, vSource.length)
	}

	actual override fun append(value: CharSequence?, startIndex: Int, endIndex: Int): StringBuilder {
		val vSource: CharSequence = value ?: "null"
		ensureCapacity(count + endIndex - startIndex)
		for (vIndex in startIndex until endIndex) {
			chars[count] = vSource[vIndex]
			count++
		}
		return this
	}

	public actual fun append(value: String?): StringBuilder {
		val vSource: String = value ?: "null"
		val vLength = vSource.length
		ensureCapacity(count + vLength)
		for (vIndex in 0 until vLength) {
			chars[count] = vSource[vIndex]
			count++
		}
		return this
	}

	public actual fun append(value: Any?): StringBuilder = append("$value")

	public actual fun append(value: Boolean): StringBuilder = append("$value")

	public actual fun append(value: Int): StringBuilder = append("$value")

	public actual fun append(value: Long): StringBuilder = append("$value")

	public actual fun append(value: Float): StringBuilder = append("$value")

	public actual fun append(value: Double): StringBuilder = append("$value")

	public actual fun append(value: CharArray): StringBuilder {
		ensureCapacity(count + value.size)
		for (vChar in value) {
			chars[count] = vChar
			count++
		}
		return this
	}

	// ============
	//  insert

	/** Opens a gap of [size] code units at [index]. */
	private fun openGap(index: Int, size: Int) {
		if (index < 0 || index > count) throw IndexOutOfBoundsException("index: $index, length: $count")
		ensureCapacity(count + size)
		var vIndex = count - 1
		while (vIndex >= index) {
			chars[vIndex + size] = chars[vIndex]
			vIndex--
		}
		count += size
	}

	public actual fun insert(index: Int, value: String?): StringBuilder {
		val vSource: String = value ?: "null"
		val vLength = vSource.length
		openGap(index, vLength)
		for (vIndex in 0 until vLength) chars[index + vIndex] = vSource[vIndex]
		return this
	}

	public actual fun insert(index: Int, value: Char): StringBuilder {
		openGap(index, 1)
		chars[index] = value
		return this
	}

	public actual fun insert(index: Int, value: CharArray): StringBuilder {
		openGap(index, value.size)
		for (vIndex in 0 until value.size) chars[index + vIndex] = value[vIndex]
		return this
	}

	public actual fun insert(index: Int, value: CharSequence?): StringBuilder = insert(index, "$value")

	public actual fun insert(index: Int, value: Any?): StringBuilder = insert(index, "$value")

	public actual fun insert(index: Int, value: Boolean): StringBuilder = insert(index, "$value")

	public actual fun insert(index: Int, value: Int): StringBuilder = insert(index, "$value")

	public actual fun insert(index: Int, value: Long): StringBuilder = insert(index, "$value")

	public actual fun insert(index: Int, value: Float): StringBuilder = insert(index, "$value")

	public actual fun insert(index: Int, value: Double): StringBuilder = insert(index, "$value")

	// ============
	//  Other operations

	public actual fun reverse(): StringBuilder {
		var vLow = 0
		var vHigh = count - 1
		while (vLow < vHigh) {
			val vTemp = chars[vLow]
			chars[vLow] = chars[vHigh]
			chars[vHigh] = vTemp
			vLow++
			vHigh--
		}
		return this
	}

	public actual fun setLength(newLength: Int) {
		if (newLength < 0) throw IndexOutOfBoundsException("newLength: $newLength")
		ensureCapacity(newLength)
		for (vIndex in count until newLength) chars[vIndex] = '\u0000'
		count = newLength
	}

	public actual fun substring(startIndex: Int): String = substring(startIndex, count)

	public actual fun substring(startIndex: Int, endIndex: Int): String {
		if (startIndex < 0 || endIndex > count || startIndex > endIndex) {
			throw IndexOutOfBoundsException("startIndex: $startIndex, endIndex: $endIndex, length: $count")
		}
		return stringOfChars(chars, startIndex, endIndex - startIndex)
	}

	private fun matchesAt(string: String, index: Int): Boolean {
		for (vOffset in 0 until string.length) {
			if (chars[index + vOffset] != string[vOffset]) return false
		}
		return true
	}

	public actual fun indexOf(string: String): Int = indexOf(string, 0)

	public actual fun indexOf(string: String, startIndex: Int): Int {
		var vIndex = if (startIndex < 0) 0 else startIndex
		while (vIndex + string.length <= count) {
			if (matchesAt(string, vIndex)) return vIndex
			vIndex++
		}
		return -1
	}

	public actual fun lastIndexOf(string: String): Int = lastIndexOf(string, count)

	public actual fun lastIndexOf(string: String, startIndex: Int): Int {
		var vIndex = count - string.length
		if (vIndex > startIndex) vIndex = startIndex
		while (vIndex >= 0) {
			if (matchesAt(string, vIndex)) return vIndex
			vIndex--
		}
		return -1
	}

	/** Removes the code units in [startIndex, endIndex). */
	internal fun removeRange(startIndex: Int, endIndex: Int) {
		if (startIndex < 0 || endIndex > count || startIndex > endIndex) {
			throw IndexOutOfBoundsException("startIndex: $startIndex, endIndex: $endIndex, length: $count")
		}
		val vRemoved = endIndex - startIndex
		for (vIndex in endIndex until count) chars[vIndex - vRemoved] = chars[vIndex]
		count -= vRemoved
	}

	internal fun setAt(index: Int, value: Char) {
		if (index < 0 || index >= count) throw IndexOutOfBoundsException("index: $index, length: $count")
		chars[index] = value
	}

	internal fun copyInto(destination: CharArray, destinationOffset: Int, startIndex: Int, endIndex: Int) {
		for (vIndex in startIndex until endIndex) destination[destinationOffset + vIndex - startIndex] = chars[vIndex]
	}

	override fun toString(): String = stringOfChars(chars, 0, count)
}

// ==================
// MARK: StringBuilder extensions
// ==================

public actual fun StringBuilder.append(value: Byte): StringBuilder = append(value.toInt())

public actual fun StringBuilder.append(value: Short): StringBuilder = append(value.toInt())

public actual fun StringBuilder.insert(index: Int, value: Byte): StringBuilder = insert(index, value.toInt())

public actual fun StringBuilder.insert(index: Int, value: Short): StringBuilder = insert(index, value.toInt())

public actual fun StringBuilder.clear(): StringBuilder {
	setLength(0)
	return this
}

public actual operator fun StringBuilder.set(index: Int, value: Char) {
	setAt(index, value)
}

public actual fun StringBuilder.setRange(startIndex: Int, endIndex: Int, value: String): StringBuilder {
	removeRange(startIndex, endIndex)
	return insert(startIndex, value)
}

public actual fun StringBuilder.deleteAt(index: Int): StringBuilder {
	removeRange(index, index + 1)
	return this
}

public actual fun StringBuilder.deleteRange(startIndex: Int, endIndex: Int): StringBuilder {
	removeRange(startIndex, endIndex)
	return this
}

public actual fun StringBuilder.toCharArray(destination: CharArray, destinationOffset: Int, startIndex: Int, endIndex: Int) {
	copyInto(destination, destinationOffset, startIndex, endIndex)
}

public actual fun StringBuilder.appendRange(value: CharArray, startIndex: Int, endIndex: Int): StringBuilder {
	for (vIndex in startIndex until endIndex) append(value[vIndex])
	return this
}

public actual fun StringBuilder.appendRange(value: CharSequence, startIndex: Int, endIndex: Int): StringBuilder =
	append(value, startIndex, endIndex)

public actual fun StringBuilder.insertRange(index: Int, value: CharArray, startIndex: Int, endIndex: Int): StringBuilder {
	var vAt = index
	for (vIndex in startIndex until endIndex) {
		insert(vAt, value[vIndex])
		vAt++
	}
	return this
}

public actual fun StringBuilder.insertRange(index: Int, value: CharSequence, startIndex: Int, endIndex: Int): StringBuilder {
	var vAt = index
	for (vIndex in startIndex until endIndex) {
		insert(vAt, value[vIndex])
		vAt++
	}
	return this
}

public actual fun StringBuilder.appendLine(value: Byte): StringBuilder = append(value).append('\n')

public actual fun StringBuilder.appendLine(value: Short): StringBuilder = append(value).append('\n')

public actual fun StringBuilder.appendLine(value: Int): StringBuilder = append(value).append('\n')

public actual fun StringBuilder.appendLine(value: Long): StringBuilder = append(value).append('\n')

public actual fun StringBuilder.appendLine(value: Float): StringBuilder = append(value).append('\n')

public actual fun StringBuilder.appendLine(value: Double): StringBuilder = append(value).append('\n')
