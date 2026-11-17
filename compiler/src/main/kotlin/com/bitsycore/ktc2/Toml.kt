package com.bitsycore.ktc2

/**
 * The part of TOML that ktc2's files use: comments, `[table]`, `[[array of tables]]`, dotted keys, and values that are
 * strings (`"basic"` with escapes, `'literal'`), booleans, integers, arrays (over several lines, a comma after the last
 * item allowed) and inline tables (`{ url = "...", user = "..." }`).
 *
 * The result is a map from a key to its value: a String, Boolean, Long, List or Map (a table: the same kind of map).
 * Keys are in the order of the file.
 */
class Toml private constructor(private val mText: String, private val mSource: String) {
	/** A mistake in a file of TOML, with where it is. */
	class Error(inMessage: String) : RuntimeException(inMessage)

	private var mPos = 0
	private var mLine = 1

	companion object {
		/** Parses [inText], naming [inSource] (a file) in what it reports. */
		fun parse(inText: String, inSource: String): Map<String, Any> = Toml(inText, inSource).document()
	}

	private fun fail(inMessage: String): Nothing = throw Error("$mSource:$mLine: $inMessage")

	private fun peek(): Char = if (mPos < mText.length) mText[mPos] else '\u0000'

	private fun next(): Char {
		val vChar = mText[mPos++]
		if (vChar == '\n') mLine++
		return vChar
	}

	/** Skips spaces and tabs, and a comment up to the end of the line. */
	private fun skipSpaces() {
		while (mPos < mText.length) {
			when (peek()) {
				' ', '\t' -> next()
				'#' -> while (mPos < mText.length && peek() != '\n') next()
				else -> return
			}
		}
	}

	/** Skips spaces, comments and line ends: the space between the items of an array. */
	private fun skipBlank() {
		while (mPos < mText.length) {
			skipSpaces()
			if (peek() == '\n' || peek() == '\r') next() else return
		}
	}

	/** After a key's value or a table's header: nothing but a comment up to the end of the line. */
	private fun endOfLine() {
		skipSpaces()
		if (peek() == '\r') next()
		if (mPos < mText.length && next() != '\n') fail("unexpected text after the value")
	}

	private fun document(): Map<String, Any> {
		val vRoot = LinkedHashMap<String, Any>()
		var vTable: MutableMap<String, Any> = vRoot
		while (true) {
			skipBlank()
			if (mPos >= mText.length) return vRoot
			if (peek() == '[') {
				next()
				val vIsArray = peek() == '['
				if (vIsArray) next()
				skipSpaces()
				val vPath = keyPath()
				skipSpaces()
				if (next() != ']' || (vIsArray && next() != ']')) fail("a table's name ends with ${if (vIsArray) "]]" else "]"}")
				endOfLine()
				vTable = if (vIsArray) {
					val vParent = tableAt(vRoot, vPath.dropLast(1))
					@Suppress("UNCHECKED_CAST")
					val vList = vParent.getOrPut(vPath.last()) { ArrayList<Any>() } as? MutableList<Any> ?: fail("'${vPath.joinToString(".")}' is not an array of tables")
					LinkedHashMap<String, Any>().also { vList.add(it) }
				} else tableAt(vRoot, vPath)
				continue
			}
			keyValue(vTable)
			endOfLine()
		}
	}

	/** The table at [inPath] under [inRoot], made when it is not there yet. */
	private fun tableAt(inRoot: MutableMap<String, Any>, inPath: List<String>): MutableMap<String, Any> {
		var vTable = inRoot
		for (vKey in inPath) {
			@Suppress("UNCHECKED_CAST")
			vTable = when (val vValue = vTable.getOrPut(vKey) { LinkedHashMap<String, Any>() }) {
				is MutableMap<*, *> -> vValue as MutableMap<String, Any>
				// After `[[maven]]`, `[maven.x]` is a table of its last element.
				is MutableList<*> -> (vValue.lastOrNull() as? MutableMap<String, Any>) ?: fail("'$vKey' is not a table")
				else -> fail("'$vKey' is not a table")
			}
		}
		return vTable
	}

	/** `key = value` into [inTable]. */
	private fun keyValue(inTable: MutableMap<String, Any>) {
		val vPath = keyPath()
		skipSpaces()
		if (mPos >= mText.length || next() != '=') fail("'=' expected after '${vPath.joinToString(".")}'")
		skipSpaces()
		val vTable = tableAt(inTable, vPath.dropLast(1))
		if (vPath.last() in vTable) fail("'${vPath.joinToString(".")}' is given twice")
		vTable[vPath.last()] = value()
	}

	/** A key, dotted or not: `build.cc`, `"a key"`. */
	private fun keyPath(): List<String> {
		val vPath = ArrayList<String>()
		while (true) {
			skipSpaces()
			vPath += when (peek()) {
				'"' -> basicString()
				'\'' -> literalString()
				else -> {
					val vStart = mPos
					while (mPos < mText.length && (peek().isLetterOrDigit() || peek() == '_' || peek() == '-')) next()
					if (vStart == mPos) fail("a key expected")
					mText.substring(vStart, mPos)
				}
			}
			skipSpaces()
			if (peek() != '.') return vPath
			next()
		}
	}

	private fun value(): Any = when (peek()) {
		'"' -> basicString()
		'\'' -> literalString()
		'[' -> array()
		'{' -> inlineTable()
		else -> {
			val vStart = mPos
			while (mPos < mText.length && peek() !in " \t\r\n,]}#") next()
			when (val vWord = mText.substring(vStart, mPos)) {
				"true" -> true
				"false" -> false
				"" -> fail("a value expected")
				else -> vWord.replace("_", "").toLongOrNull() ?: fail("'$vWord' is not a value (a text is between quotes)")
			}
		}
	}

	private fun array(): List<Any> {
		next()
		val vItems = ArrayList<Any>()
		while (true) {
			skipBlank()
			if (peek() == ']') {
				next()
				return vItems
			}
			vItems += value()
			skipBlank()
			when (if (mPos < mText.length) next() else fail("an array is not closed")) {
				',' -> {}
				']' -> return vItems
				else -> fail("',' or ']' expected in an array")
			}
		}
	}

	private fun inlineTable(): Map<String, Any> {
		next()
		val vTable = LinkedHashMap<String, Any>()
		skipSpaces()
		if (peek() == '}') {
			next()
			return vTable
		}
		while (true) {
			skipSpaces()
			keyValue(vTable)
			skipSpaces()
			when (if (mPos < mText.length) next() else fail("an inline table is not closed")) {
				',' -> {}
				'}' -> return vTable
				else -> fail("',' or '}' expected in an inline table")
			}
		}
	}

	private fun basicString(): String {
		next()
		val vText = StringBuilder()
		while (true) {
			if (mPos >= mText.length || peek() == '\n') fail("a text is not closed")
			when (val vChar = next()) {
				'"' -> return vText.toString()
				'\\' -> when (val vEscaped = next()) {
					'n' -> vText.append('\n')
					't' -> vText.append('\t')
					'r' -> vText.append('\r')
					'"', '\\' -> vText.append(vEscaped)
					'u' -> vText.append(mText.substring(mPos, mPos + 4).toInt(16).toChar()).also { mPos += 4 }
					else -> fail("unknown escape '\\$vEscaped'")
				}
				else -> vText.append(vChar)
			}
		}
	}

	private fun literalString(): String {
		next()
		val vStart = mPos
		while (peek() != '\'') {
			if (mPos >= mText.length || peek() == '\n') fail("a text is not closed")
			next()
		}
		return mText.substring(vStart, mPos).also { next() }
	}
}

/** `${env:NAME}` in [inText] replaced by the environment variable NAME (empty when it is not set). */
fun expandEnvironment(inText: String): String =
	Regex("\\$\\{env:([A-Za-z_][A-Za-z0-9_]*)}").replace(inText) { Settings.environment(it.groupValues[1]) ?: "" }
