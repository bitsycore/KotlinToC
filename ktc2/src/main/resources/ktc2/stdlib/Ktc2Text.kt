@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "ACTUAL_WITHOUT_EXPECT", "NOTHING_TO_INLINE")

package kotlin.text

/*
ktc2 platform layer for kotlin.text.
*/

/**
 * A mutable sequence of characters. It keeps its content as a String and replaces it on
 * every append — simple, and to be replaced by a growable buffer.
 */
public class StringBuilder(content: String) : Appendable, CharSequence {

	private var text: String = content

	public constructor() : this("")

	public constructor(capacity: Int) : this("")

	public constructor(content: CharSequence) : this(content.toString())

	override val length: Int get() = text.length

	override fun get(index: Int): Char = text[index]

	override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
		var vPart = ""
		for (vIndex in startIndex until endIndex) vPart += text[vIndex]
		return vPart
	}

	override fun append(value: Char): StringBuilder {
		text += value
		return this
	}

	override fun append(value: CharSequence?): StringBuilder {
		text += value
		return this
	}

	override fun append(value: CharSequence?, startIndex: Int, endIndex: Int): StringBuilder {
		val vSource: CharSequence = value ?: "null"
		for (vIndex in startIndex until endIndex) text += vSource[vIndex]
		return this
	}

	public fun append(value: Any?): StringBuilder {
		text += value
		return this
	}

	public fun append(value: String?): StringBuilder {
		text += value
		return this
	}

	public fun append(value: Boolean): StringBuilder {
		text += value
		return this
	}

	public fun append(value: Int): StringBuilder {
		text += value
		return this
	}

	public fun append(value: Long): StringBuilder {
		text += value
		return this
	}

	public fun append(value: Float): StringBuilder {
		text += value
		return this
	}

	public fun append(value: Double): StringBuilder {
		text += value
		return this
	}

	public fun clear(): StringBuilder {
		text = ""
		return this
	}

	override fun toString(): String = text
}
