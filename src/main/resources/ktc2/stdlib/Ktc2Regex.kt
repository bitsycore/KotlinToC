@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "ACTUAL_ANNOTATIONS_NOT_MATCH_EXPECT", "ACTUAL_WITHOUT_EXPECT", "UNCHECKED_CAST")

package kotlin.text

/*
kotlin.text.Regex for ktc2: a backtracking matcher written in Kotlin, with the syntax and the
behavior of java.util.regex for what it covers:

  literals and escapes (\t \n \r \f \e \0 \xHH \uHHHH, \Q…\E), `.`, character classes with
  ranges, negation, nested classes and intersection (&&), \d \w \s \D \W \S, \p{…} for the
  general categories of Unicode, the POSIX classes, the java… classes and the binary
  properties, anchors ^ $ \A \z \Z \b \B, capturing, named, non-capturing and atomic groups,
  alternation, greedy, lazy and possessive quantifiers (* + ? {n} {n,} {n,m}), back
  references (\1, \k<name>), look-ahead and look-behind, and the flags i m s x u d, for the
  whole pattern, from a place on ((?i)), or for a group ((?i:…)).

As in Java, (?i) alone knows the case of ASCII letters only; RegexOption.IGNORE_CASE and (?iu)
know the case of every letter. \p{…} also names scripts (\p{IsGreek}, \p{script=Latin}) and
blocks (\p{InArrows}, \p{block=BasicLatin}), by their full names. The flag d is known.

The flag U makes \w, \d, \s, \b and the POSIX classes (\p{Alpha}...) follow Unicode, as
UNICODE_CHARACTER_CLASS does in Java (and turns on u). Scripts are also named by their
four-letter codes (\p{sc=Latn}, \p{IsCyrl}).
*/

// ==================
// MARK: Public types
// ==================

public actual enum class RegexOption {
	IGNORE_CASE,
	MULTILINE
}

public actual data class MatchGroup(public actual val value: String, public val range: IntRange)

public actual operator fun MatchGroupCollection.get(name: String): MatchGroup? {
	val vNamed = this as? MatchNamedGroupCollection ?: throw UnsupportedOperationException("Retrieving groups by name is not supported on this collection.")
	return vNamed.get(name)
}

// ==================
// MARK: Character sets
// ==================

// The classes a pattern can name. The ones of ASCII:
private const val kClassDigit = 1
private const val kClassWord = 2
private const val kClassSpace = 3
private const val kClassLower = 10
private const val kClassUpper = 11
private const val kClassAlpha = 12
private const val kClassAlnum = 14
private const val kClassPunct = 15
private const val kClassGraph = 16
private const val kClassPrint = 17
private const val kClassBlank = 18
private const val kClassControl = 19
private const val kClassHexDigit = 20
private const val kClassAscii = 22

// The ones that ask the functions of Char:
private const val kJavaLowerCase = 30
private const val kJavaUpperCase = 31
private const val kJavaWhitespace = 32
private const val kJavaLetter = 33
private const val kJavaDigit = 34
private const val kJavaLetterOrDigit = 35
private const val kJavaDefined = 37
private const val kJavaTitleCase = 38
private const val kUnicodeAlphabetic = 40
private const val kUnicodeWhiteSpace = 44

// What \w, \b and the POSIX classes are with the flag U (java.util.regex.CharPredicates):
private const val kUnicodeWord = 50
private const val kUnicodeAlnum = 51
private const val kUnicodePunct = 52
private const val kUnicodeGraph = 53
private const val kUnicodePrint = 54
private const val kUnicodeBlank = 55
private const val kUnicodeControl = 56
private const val kUnicodeHexDigit = 57

/** A general category of Unicode: this plus the ordinal of its CharCategory. */
private const val kClassCategory = 100

/** The categories whose code starts with a letter (`L`, `N`…): this plus the letter. */
private const val kClassCategoryGroup = 200

/** `LC`: a letter that has a case. */
private const val kClassCasedLetter = 400

/** A script: this plus its place among the names of scripts. A block: the same with its own base. */
private const val kClassScript = 1000
private const val kClassBlock = 2000

/** The first letter of the code of each CharCategory, in the order of the entries. */
private const val kCategoryGroups = "CLLLLLMMMNNNZZZCCCCPPPPPSSSSPP"

private fun isAsciiLetter(inChar: Char): Boolean = inChar in 'a'..'z' || inChar in 'A'..'Z'

private fun isAsciiPunctuation(inChar: Char): Boolean = inChar in '!'..'/' || inChar in ':'..'@' || inChar in '['..'`' || inChar in '{'..'~'

/** The categories a word character of the flag U may be of besides the alphabetic ones: marks, decimal digits, connectors. */
private val kWordCategories = intArrayOf(
	CharCategory.NON_SPACING_MARK.ordinal, CharCategory.ENCLOSING_MARK.ordinal, CharCategory.COMBINING_SPACING_MARK.ordinal,
	CharCategory.DECIMAL_DIGIT_NUMBER.ordinal, CharCategory.CONNECTOR_PUNCTUATION.ordinal,
)

private val kPunctuationCategories = intArrayOf(
	CharCategory.CONNECTOR_PUNCTUATION.ordinal, CharCategory.DASH_PUNCTUATION.ordinal, CharCategory.START_PUNCTUATION.ordinal,
	CharCategory.END_PUNCTUATION.ordinal, CharCategory.OTHER_PUNCTUATION.ordinal, CharCategory.INITIAL_QUOTE_PUNCTUATION.ordinal,
	CharCategory.FINAL_QUOTE_PUNCTUATION.ordinal,
)

/** Unicode's White_Space, as Java has it: the separators, the controls from tab to carriage return, and NEL. */
private fun isWhiteSpace(inChar: Char): Boolean {
	val vCategory = unicodeCategory(inChar)
	return vCategory == CharCategory.SPACE_SEPARATOR.ordinal || vCategory == CharCategory.LINE_SEPARATOR.ordinal ||
		vCategory == CharCategory.PARAGRAPH_SEPARATOR.ordinal || inChar in '\u0009'..'\u000D' || inChar == '\u0085'
}

/** What is visible: neither a separator, a control, half of a pair nor unassigned. */
private fun isGraph(inChar: Char): Boolean = when (unicodeCategory(inChar)) {
	CharCategory.SPACE_SEPARATOR.ordinal, CharCategory.LINE_SEPARATOR.ordinal, CharCategory.PARAGRAPH_SEPARATOR.ordinal,
	CharCategory.CONTROL.ordinal, CharCategory.SURROGATE.ordinal, CharCategory.UNASSIGNED.ordinal -> false
	else -> true
}

private fun isBlank(inChar: Char): Boolean = unicodeCategory(inChar) == CharCategory.SPACE_SEPARATOR.ordinal || inChar == '\t'

private fun inNamedClass(inKind: Int, inChar: Char): Boolean = when (inKind) {
	kClassDigit -> inChar in '0'..'9'
	kClassWord -> isAsciiLetter(inChar) || inChar in '0'..'9' || inChar == '_'
	kClassSpace -> inChar == ' ' || inChar == '\t' || inChar == '\n' || inChar == '\r' || inChar == '\u000B' || inChar == '\u000C'
	kClassLower -> inChar in 'a'..'z'
	kClassUpper -> inChar in 'A'..'Z'
	kClassAlpha -> isAsciiLetter(inChar)
	kClassAlnum -> isAsciiLetter(inChar) || inChar in '0'..'9'
	kClassPunct -> isAsciiPunctuation(inChar)
	kClassGraph -> isAsciiLetter(inChar) || inChar in '0'..'9' || isAsciiPunctuation(inChar)
	kClassPrint -> inChar in ' '..'~'
	kClassBlank -> inChar == ' ' || inChar == '\t'
	kClassControl -> inChar.code < 0x20 || inChar.code == 0x7F
	kClassHexDigit -> inChar in '0'..'9' || inChar in 'a'..'f' || inChar in 'A'..'F'
	kClassAscii -> inChar.code < 0x80
	kJavaLowerCase -> inChar.isLowerCase()
	kJavaUpperCase -> inChar.isUpperCase()
	// Character.isWhitespace leaves out the spaces that do not break a line.
	kJavaWhitespace -> inChar.isWhitespace() && inChar != '\u00A0' && inChar != '\u2007' && inChar != '\u202F'
	kJavaLetter -> inChar.isLetter()
	kJavaDigit -> inChar.isDigit()
	kJavaLetterOrDigit -> inChar.isLetterOrDigit()
	kJavaDefined -> inChar.isDefined()
	kJavaTitleCase -> inChar.isTitleCase()
	kUnicodeAlphabetic -> unicodeIsAlphabetic(inChar)
	kUnicodeWhiteSpace -> isWhiteSpace(inChar)
	kUnicodeWord -> unicodeIsAlphabetic(inChar) || unicodeCategory(inChar) in kWordCategories || inChar == '\u200C' || inChar == '\u200D'
	kUnicodeAlnum -> unicodeIsAlphabetic(inChar) || inChar.isDigit()
	kUnicodePunct -> unicodeCategory(inChar) in kPunctuationCategories
	kUnicodeGraph -> isGraph(inChar)
	kUnicodePrint -> (isGraph(inChar) || isBlank(inChar)) && unicodeCategory(inChar) != CharCategory.CONTROL.ordinal
	kUnicodeBlank -> isBlank(inChar)
	kUnicodeControl -> unicodeCategory(inChar) == CharCategory.CONTROL.ordinal
	kUnicodeHexDigit -> inChar.isDigit() || inChar in '0'..'9' || inChar in 'A'..'F' || inChar in 'a'..'f' ||
		inChar in '\uFF10'..'\uFF19' || inChar in '\uFF21'..'\uFF26' || inChar in '\uFF41'..'\uFF46'
	kClassCasedLetter -> unicodeCategory(inChar) in 1..3
	else -> if (inKind >= kClassBlock) {
		unicodeBlock(inChar) == inKind - kClassBlock
	} else if (inKind >= kClassScript) {
		unicodeScript(inChar) == inKind - kClassScript
	} else {
		val vCategory = unicodeCategory(inChar)
		if (inKind >= kClassCategoryGroup) kCategoryGroups[vCategory].code == inKind - kClassCategoryGroup else vCategory == inKind - kClassCategory
	}
}

// How letters are compared: as they are, without the case of ASCII letters, without the case of any letter.
private const val kCaseExact = 0
private const val kCaseAscii = 1
private const val kCaseUnicode = 2

private fun sameChar(inLeft: Char, inRight: Char, inCase: Int): Boolean {
	if (inLeft == inRight) return true
	if (inCase == kCaseExact) return false
	if (inCase == kCaseAscii) return isAsciiLetter(inLeft) && isAsciiLetter(inRight) && (inLeft.code or 0x20) == (inRight.code or 0x20)
	// As Java: by the capital letters, and by what those are in lower case (`ϑ` and `ϴ` are both a `θ` then).
	val vLeft = inLeft.uppercaseChar()
	val vRight = inRight.uppercaseChar()
	return vLeft == vRight || vLeft.lowercaseChar() == vRight.lowercaseChar()
}

/** What a `[…]`, a `\d` or a `.` accepts: ranges, named classes and nested sets, possibly negated. */
private class CharSet(val negated: Boolean) {
	val rangeStarts = ArrayList<Char>()
	val rangeEnds = ArrayList<Char>()
	val kinds = ArrayList<Int>()
	val negatedKinds = ArrayList<Int>()
	val nested = ArrayList<CharSet>()

	/** What comes after `&&`: a character has to be in each of these as well. */
	val intersected = ArrayList<CharSet>()

	fun addRange(inFrom: Char, inTo: Char) {
		rangeStarts.add(inFrom)
		rangeEnds.add(inTo)
	}

	private fun holdsItself(inChar: Char): Boolean {
		for (vIndex in 0 until rangeStarts.size) if (inChar >= rangeStarts[vIndex] && inChar <= rangeEnds[vIndex]) return true
		// By index: this runs for every character tried, and a `for` over a list makes an iterator each time.
		for (vIndex in 0 until kinds.size) if (inNamedClass(kinds[vIndex], inChar)) return true
		for (vIndex in 0 until negatedKinds.size) if (!inNamedClass(negatedKinds[vIndex], inChar)) return true
		for (vIndex in 0 until nested.size) if (nested[vIndex].accepts(inChar, kCaseExact)) return true
		return false
	}

	private fun holds(inChar: Char): Boolean {
		if (!holdsItself(inChar)) return false
		for (vIndex in 0 until intersected.size) if (!intersected[vIndex].accepts(inChar, kCaseExact)) return false
		return true
	}

	fun accepts(inChar: Char, inCase: Int): Boolean {
		var vHolds = holds(inChar)
		if (!vHolds && inCase == kCaseAscii && isAsciiLetter(inChar)) vHolds = holds((inChar.code xor 0x20).toChar())
		if (!vHolds && inCase == kCaseUnicode) vHolds = holds(inChar.lowercaseChar()) || holds(inChar.uppercaseChar())
		return vHolds != negated
	}
}

// ==================
// MARK: Parsed pattern
// ==================

private abstract class Piece

private class Literal(val char: Char, val case: Int) : Piece()

private class SetPiece(val set: CharSet, val case: Int) : Piece()

/** `.`: anything but a line terminator, or anything at all with (?s). With (?d) only `\n` ends a line. */
private class Dot(val matchesLines: Boolean, val unixLines: Boolean) : Piece()

private class Chain(val items: List<Piece>) : Piece()

private class Alternatives(val options: List<Piece>) : Piece()

/** A capturing group; [index] is 0 for one that does not capture. */
private class Group(val index: Int, val body: Piece) : Piece()

private class Repeat(val body: Piece, val min: Int, val max: Int, val greedy: Boolean) : Piece()

private class BackReference(val group: Int, val case: Int) : Piece()

/** `(?>…)`, and what a possessive quantifier makes of its loop: once matched, it is not gone back into. */
private class Atomic(val body: Piece) : Piece()

private class Look(val body: Piece, val ahead: Boolean, val negated: Boolean) : Piece()

/** `^` `$` `\A` `\z` `\Z` `\b` `\B`, named by the character that writes them; [multiline] is the flag where it stands. */
private class Anchor(val kind: Char, val multiline: Boolean, val unixLines: Boolean, val unicodeWords: Boolean = false) : Piece()

// ==================
// MARK: Parser
// ==================

private class Parser(private val mPattern: String, inIgnoreCase: Boolean, inMultiline: Boolean) {
	private var mAt = 0
	var groupCount = 0
	val groupNames = HashMap<String, Int>()

	// The flags where the parser stands: a group ends what was set inside it.
	private var mIgnoreCase = inIgnoreCase
	private var mUnicodeCase = inIgnoreCase
	private var mMultiline = inMultiline
	private var mDotAll = false
	private var mComments = false
	private var mUnixLines = false

	/** The flag U: the classes follow Unicode. */
	private var mUnicodeClasses = false

	private fun case(): Int = if (!mIgnoreCase) kCaseExact else if (mUnicodeCase) kCaseUnicode else kCaseAscii

	private fun fail(inWhat: String): Nothing = throw IllegalArgumentException("$inWhat near index $mAt\n$mPattern")

	private fun more(): Boolean = mAt < mPattern.length

	private fun peek(): Char = mPattern[mAt]

	private fun eat(inChar: Char): Boolean {
		if (more() && peek() == inChar) {
			mAt++
			return true
		}
		return false
	}

	private fun looksAt(inText: String): Boolean = mPattern.startsWith(inText, mAt)

	fun parse(): Piece {
		val vPiece = alternatives()
		if (more()) fail(if (peek() == ')') "Unmatched closing ')'" else "Unexpected character '${peek()}'")
		return vPiece
	}

	/** What [inBody] parses, with the flags as they were before it put back afterwards. */
	private inline fun scoped(inBody: () -> Piece): Piece {
		val vIgnoreCase = mIgnoreCase
		val vUnicodeCase = mUnicodeCase
		val vMultiline = mMultiline
		val vDotAll = mDotAll
		val vComments = mComments
		val vUnixLines = mUnixLines
		val vUnicodeClasses = mUnicodeClasses
		val vPiece = inBody()
		mUnixLines = vUnixLines
		mUnicodeClasses = vUnicodeClasses
		mIgnoreCase = vIgnoreCase
		mUnicodeCase = vUnicodeCase
		mMultiline = vMultiline
		mDotAll = vDotAll
		mComments = vComments
		return vPiece
	}

	/** Reads the flags of `(?imsxu-imsxu` and sets them. */
	private fun flags() {
		var vIsOn = true
		while (more() && peek() != ')' && peek() != ':') {
			when (val vFlag = mPattern[mAt++]) {
				'-' -> vIsOn = false
				'i' -> mIgnoreCase = vIsOn
				'u' -> mUnicodeCase = vIsOn
				'm' -> mMultiline = vIsOn
				's' -> mDotAll = vIsOn
				'x' -> mComments = vIsOn
				'd' -> mUnixLines = vIsOn
				// As in Java, U brings u with it.
				'U' -> {
					mUnicodeClasses = vIsOn
					mUnicodeCase = vIsOn
				}
				else -> fail("Unknown inline modifier '$vFlag'")
			}
		}
	}

	/** With the flag x: white space and what follows a `#` to the end of the line are not part of the pattern. */
	private fun skipComments() {
		while (mComments && more()) {
			if (peek() == '#') {
				while (more() && peek() != '\n') mAt++
			} else if (peek() == ' ' || peek() == '\t' || peek() == '\n' || peek() == '\r' || peek() == '\u000C') {
				mAt++
			} else {
				return
			}
		}
	}

	private fun alternatives(): Piece {
		val vOptions = ArrayList<Piece>()
		vOptions.add(sequence())
		while (eat('|')) vOptions.add(sequence())
		return if (vOptions.size == 1) vOptions[0] else Alternatives(vOptions)
	}

	private fun sequence(): Piece {
		val vItems = ArrayList<Piece>()
		while (true) {
			skipComments()
			if (!more() || peek() == '|' || peek() == ')') break
			if (looksAt("\\Q")) {
				mAt += 2
				val vEnd = mPattern.indexOf("\\E", mAt).let { if (it < 0) mPattern.length else it }
				for (vIndex in mAt until vEnd) vItems.add(Literal(mPattern[vIndex], case()))
				mAt = if (vEnd + 2 <= mPattern.length) vEnd + 2 else mPattern.length
				continue
			}
			vItems.add(quantified(atom()))
		}
		return if (vItems.size == 1) vItems[0] else Chain(vItems)
	}

	private fun number(): Int {
		val vStart = mAt
		while (more() && peek() in '0'..'9') mAt++
		if (mAt == vStart) fail("Illegal repetition")
		return mPattern.substring(vStart, mAt).toInt()
	}

	private fun quantified(inAtom: Piece): Piece {
		skipComments()
		if (!more()) return inAtom
		var vMin = 0
		var vMax = Int.MAX_VALUE
		when (peek()) {
			'*' -> mAt++
			'+' -> {
				mAt++
				vMin = 1
			}
			'?' -> {
				mAt++
				vMax = 1
			}
			'{' -> {
				mAt++
				vMin = number()
				vMax = vMin
				if (eat(',')) vMax = if (more() && peek() == '}') Int.MAX_VALUE else number()
				if (!eat('}')) fail("Unclosed counted closure")
				if (vMax < vMin) fail("Illegal repetition range")
			}
			else -> return inAtom
		}
		if (inAtom is Anchor || inAtom is Look) fail("Dangling meta character")
		var vGreedy = true
		var vPossessive = false
		if (eat('?')) vGreedy = false else if (eat('+')) vPossessive = true
		val vLoop = Repeat(inAtom, vMin, vMax, vGreedy)
		// Possessive: what the loop took is kept, whatever follows.
		val vRepeat: Piece = if (vPossessive) Atomic(vLoop) else vLoop
		// `a**` and the like are errors in Java too.
		if (more() && (peek() == '*' || peek() == '+' || peek() == '?')) fail("Dangling meta character '${peek()}'")
		return vRepeat
	}

	private fun atom(): Piece {
		val vChar = mPattern[mAt++]
		when (vChar) {
			'(' -> return group()
			'[' -> return SetPiece(charClass(), case())
			'.' -> return Dot(mDotAll, mUnixLines)
			'^', '$' -> return Anchor(vChar, mMultiline, mUnixLines)
			'*', '+', '?' -> fail("Dangling meta character '$vChar'")
			'\\' -> return escape()
			else -> return Literal(vChar, case())
		}
	}

	private fun group(): Piece {
		if (!eat('?')) {
			val vIndex = ++groupCount
			val vBody = scoped { alternatives() }
			if (!eat(')')) fail("Unclosed group")
			return Group(vIndex, vBody)
		}
		if (!more()) fail("Unclosed group")
		if (peek() in "imsxud-U") {
			// Flags: for what follows up to the end of the group they are written in, or for a group of their own.
			val vBefore = scopedFlags()
			if (vBefore != null) return vBefore
			if (!eat(')')) fail("Unclosed group")
			return Chain(emptyList())
		}
		val vKind = mPattern[mAt++]
		val vPiece: Piece = when (vKind) {
			':' -> Group(0, scoped { alternatives() })
			'=' -> Look(scoped { alternatives() }, true, false)
			'!' -> Look(scoped { alternatives() }, true, true)
			'>' -> Atomic(scoped { alternatives() })
			'<' -> {
				if (eat('=')) {
					Look(scoped { alternatives() }, false, false)
				} else if (eat('!')) {
					Look(scoped { alternatives() }, false, true)
				} else {
					val vStart = mAt
					while (more() && peek() != '>') mAt++
					val vName = mPattern.substring(vStart, mAt)
					if (!eat('>') || vName.isEmpty()) fail("Named capturing group is missing trailing '>'")
					if (groupNames.containsKey(vName)) fail("Named capturing group <$vName> is already defined")
					val vIndex = ++groupCount
					groupNames[vName] = vIndex
					Group(vIndex, scoped { alternatives() })
				}
			}
			else -> fail("Unknown inline modifier")
		}
		if (!eat(')')) fail("Unclosed group")
		return vPiece
	}

	/**
	 * After `(?` and before flags. `(?i:…)` is a group with those flags, returned; for `(?i)` the
	 * flags are set where the parser stands and null is returned, the `)` not yet read.
	 */
	private fun scopedFlags(): Piece? {
		val vStart = mAt
		while (more() && peek() != ')' && peek() != ':') mAt++
		val vIsGroup = more() && peek() == ':'
		mAt = vStart
		if (!vIsGroup) {
			flags()
			return null
		}
		val vBody = scoped {
			flags()
			mAt++
			alternatives()
		}
		if (!eat(')')) fail("Unclosed group")
		return Group(0, vBody)
	}

	private fun hex(inDigits: Int): Char {
		if (mAt + inDigits > mPattern.length) fail("Illegal hexadecimal escape sequence")
		val vValue = mPattern.substring(mAt, mAt + inDigits).toIntOrNull(16) ?: fail("Illegal hexadecimal escape sequence")
		mAt += inDigits
		return vValue.toChar()
	}

	/** The class a `\p{…}` names; null when it is not one known here. */
	private fun classNamed(inName: String): Int? {
		// With the flag U the POSIX classes follow Unicode.
		if (mUnicodeClasses) {
			when (inName) {
				"Lower" -> return kJavaLowerCase
				"Upper" -> return kJavaUpperCase
				"Alpha" -> return kUnicodeAlphabetic
				"Digit" -> return kJavaDigit
				"Alnum" -> return kUnicodeAlnum
				"Punct" -> return kUnicodePunct
				"Graph" -> return kUnicodeGraph
				"Print" -> return kUnicodePrint
				"Blank" -> return kUnicodeBlank
				"Cntrl" -> return kUnicodeControl
				"XDigit" -> return kUnicodeHexDigit
				"Space" -> return kUnicodeWhiteSpace
			}
		}
		when (inName) {
			"Lower" -> return kClassLower
			"Upper" -> return kClassUpper
			"ASCII" -> return kClassAscii
			"Alpha" -> return kClassAlpha
			"Digit" -> return kClassDigit
			"Alnum" -> return kClassAlnum
			"Punct" -> return kClassPunct
			"Graph" -> return kClassGraph
			"Print" -> return kClassPrint
			"Blank" -> return kClassBlank
			"Cntrl" -> return kClassControl
			"XDigit" -> return kClassHexDigit
			"Space" -> return kClassSpace
			"javaLowerCase" -> return kJavaLowerCase
			"javaUpperCase" -> return kJavaUpperCase
			"javaTitleCase" -> return kJavaTitleCase
			"javaWhitespace" -> return kJavaWhitespace
			"javaLetter" -> return kJavaLetter
			"javaDigit" -> return kJavaDigit
			"javaLetterOrDigit" -> return kJavaLetterOrDigit
			"javaAlphabetic" -> return kUnicodeAlphabetic
			"javaDefined" -> return kJavaDefined
		}
		var vName = inName
		var vIsScript = false
		val vEquals = vName.indexOf('=')
		if (vEquals >= 0) {
			val vKey = vName.substring(0, vEquals).lowercase()
			vName = vName.substring(vEquals + 1)
			when (vKey) {
				"general_category", "gc" -> {}
				"script", "sc" -> return scriptNamed(vName)?.let { kClassScript + it }
				"block", "blk" -> return unicodeBlockNamed(vName).takeIf { it >= 0 }?.let { kClassBlock + it }
				else -> return null
			}
		} else if (vName.startsWith("In")) {
			return unicodeBlockNamed(vName.substring(2)).takeIf { it >= 0 }?.let { kClassBlock + it }
		} else if (vName.startsWith("Is") || vName.startsWith("is")) {
			vIsScript = true
			vName = vName.substring(2)
			// A property that is true or false of a character.
			when (vName.lowercase().replace("_", "")) {
				"alphabetic" -> return kUnicodeAlphabetic
				"letter" -> return kJavaLetter
				"uppercase" -> return kJavaUpperCase
				"lowercase" -> return kJavaLowerCase
				"titlecase" -> return kJavaTitleCase
				"whitespace" -> return kUnicodeWhiteSpace
				"digit" -> return kJavaDigit
				"punctuation" -> return kClassCategoryGroup + 'P'.code
				"control" -> return kClassCategory + CharCategory.CONTROL.ordinal
				"assigned" -> return kJavaDefined
			}
		}
		// A general category by its code: one letter for a group of them, two for one.
		if (vName == "LC") return kClassCasedLetter
		if (vName.length == 1 && vName[0] in "LMNPSZC") return kClassCategoryGroup + vName[0].code
		if (vName.length == 2) {
			for (vCategory in CharCategory.entries) if (vCategory.code == vName) return kClassCategory + vCategory.ordinal
		}
		// `IsLatin`: a script.
		return if (vIsScript) scriptNamed(vName)?.let { kClassScript + it } else null
	}

	/** The place of a script named by its name (`Latin`) or its four-letter code (`Latn`). */
	private fun scriptNamed(inName: String): Int? =
		unicodeScriptNamed(inName).takeIf { it >= 0 } ?: unicodeScriptCoded(inName).takeIf { it >= 0 }

	/** The named class after `\p` or `\P`, added to [outSet]. */
	private fun property(inNegated: Boolean, outSet: CharSet) {
		val vName: String
		if (eat('{')) {
			val vStart = mAt
			while (more() && peek() != '}') mAt++
			vName = mPattern.substring(vStart, mAt)
			if (!eat('}')) fail("Unclosed character family")
		} else {
			if (!more()) fail("Illegal character family")
			vName = mPattern[mAt++].toString()
		}
		val vKind = classNamed(vName) ?: fail("Unknown character property name {$vName}")
		if (inNegated) outSet.negatedKinds.add(vKind) else outSet.kinds.add(vKind)
	}

	/**
	 * What follows a backslash that stands for a set of characters, added to [outSet]; false
	 * when it is something else.
	 */
	private fun classEscape(inChar: Char, outSet: CharSet): Boolean {
		when (inChar) {
			'd' -> outSet.kinds.add(if (mUnicodeClasses) kJavaDigit else kClassDigit)
			'D' -> outSet.negatedKinds.add(if (mUnicodeClasses) kJavaDigit else kClassDigit)
			'w' -> outSet.kinds.add(if (mUnicodeClasses) kUnicodeWord else kClassWord)
			'W' -> outSet.negatedKinds.add(if (mUnicodeClasses) kUnicodeWord else kClassWord)
			's' -> outSet.kinds.add(if (mUnicodeClasses) kUnicodeWhiteSpace else kClassSpace)
			'S' -> outSet.negatedKinds.add(if (mUnicodeClasses) kUnicodeWhiteSpace else kClassSpace)
			'p' -> property(false, outSet)
			'P' -> property(true, outSet)
			else -> return false
		}
		return true
	}

	/** The single character an escape stands for (`\n`, `\x41`, `\.`). */
	private fun literalEscape(inChar: Char): Char = when (inChar) {
		't' -> '\t'
		'n' -> '\n'
		'r' -> '\r'
		'f' -> '\u000C'
		'e' -> '\u001B'
		'a' -> '\u0007'
		'0' -> {
			// As in Java: one to three digits in base 8, three only below 0400.
			if (!more() || peek() !in '0'..'7') fail("Illegal octal escape sequence")
			val vLimit = if (peek() <= '3') 3 else 2
			var vValue = 0
			var vDigits = 0
			while (more() && vDigits < vLimit && peek() in '0'..'7') {
				vValue = vValue * 8 + (mPattern[mAt++] - '0')
				vDigits++
			}
			vValue.toChar()
		}
		'x' -> hex(2)
		'u' -> hex(4)
		else -> {
			if (inChar.isLetterOrDigit()) fail("Illegal/unsupported escape sequence")
			inChar
		}
	}

	private fun escape(): Piece {
		if (!more()) fail("Unexpected internal error")
		val vChar = mPattern[mAt++]
		val vSet = CharSet(false)
		if (classEscape(vChar, vSet)) return SetPiece(vSet, case())
		when (vChar) {
			'b', 'B', 'A', 'z', 'Z' -> return Anchor(vChar, mMultiline, mUnixLines, mUnicodeClasses)
			'k' -> {
				if (!eat('<')) fail("\\k is not followed by '<' for named capturing group")
				val vStart = mAt
				while (more() && peek() != '>') mAt++
				val vName = mPattern.substring(vStart, mAt)
				if (!eat('>')) fail("Named capturing group is missing trailing '>'")
				return BackReference(groupNames[vName] ?: fail("Named capturing group <$vName> does not exist"), case())
			}
		}
		if (vChar in '1'..'9') {
			// As many digits as still name an existing group.
			var vGroup = vChar - '0'
			while (more() && peek() in '0'..'9' && vGroup * 10 + (peek() - '0') <= groupCount) vGroup = vGroup * 10 + (mPattern[mAt++] - '0')
			return BackReference(vGroup, case())
		}
		return Literal(literalEscape(vChar), case())
	}

	private fun charClass(): CharSet {
		val vSet = CharSet(eat('^'))
		classBody(vSet, true)
		return vSet
	}

	/** Reads the items of a class into [outSet], up to and with its `]`. After `&&` comes another set the characters have to be in too. */
	private fun classBody(outSet: CharSet, inIsStart: Boolean) {
		var vFirst = inIsStart
		while (true) {
			if (!more()) fail("Unclosed character class")
			var vChar = mPattern[mAt++]
			// With the flag x, white space is not part of a class either.
			if (mComments && (vChar == ' ' || vChar == '\t' || vChar == '\n' || vChar == '\r')) continue
			if (vChar == ']' && !vFirst) break
			vFirst = false
			if (vChar == '[') {
				outSet.nested.add(charClass())
				continue
			}
			if (vChar == '&' && more() && peek() == '&') {
				mAt++
				val vOther = CharSet(false)
				classBody(vOther, false)
				outSet.intersected.add(vOther)
				return
			}
			if (vChar == '\\') {
				if (!more()) fail("Unclosed character class")
				val vEscaped = mPattern[mAt++]
				if (classEscape(vEscaped, outSet)) continue
				vChar = literalEscape(vEscaped)
			}
			// A range, unless the '-' is the last thing in the class.
			if (more() && peek() == '-' && mAt + 1 < mPattern.length && mPattern[mAt + 1] != ']') {
				mAt++
				var vEnd = mPattern[mAt++]
				if (vEnd == '[') fail("Illegal character range")
				if (vEnd == '\\') {
					if (!more()) fail("Unclosed character class")
					vEnd = literalEscape(mPattern[mAt++])
				}
				if (vEnd < vChar) fail("Illegal character range")
				outSet.addRange(vChar, vEnd)
			} else {
				outSet.addRange(vChar, vChar)
			}
		}
	}
}

// ==================
// MARK: Matcher
// ==================

/** The counters of a pattern without loops: none, the same for every search. */
private val kNoLoops = IntArray(0)

/** The state of one attempt to match. */
private class Matching(val input: CharArray, inGroupCount: Int, val loops: List<Loop>) {
	/** Start and end of each group, -1 while it has not taken part. Group 0 is the whole match. */
	val groups = IntArray((inGroupCount + 1) * 2)

	/** For each loop: how many times it has gone round, and where the current round started. */
	val counts = if (loops.isEmpty()) kNoLoops else IntArray(loops.size)
	val starts = if (loops.isEmpty()) kNoLoops else IntArray(loops.size)

	/** When not negative, the match has to end exactly here (matchEntire, look-behind). */
	var requiredEnd = -1

	fun reset() {
		for (vIndex in 0 until groups.size) groups[vIndex] = -1
	}
}

/**
 * A step of the compiled pattern. [match] tries this step at [inAt] and then everything after
 * it; it returns where the whole match ends, or -1.
 */
private abstract class Node {
	var next: Node? = null

	abstract fun match(inState: Matching, inAt: Int): Int

	protected fun rest(inState: Matching, inAt: Int): Int = next!!.match(inState, inAt)
}

private class Accept : Node() {
	override fun match(inState: Matching, inAt: Int): Int =
		if (inState.requiredEnd >= 0 && inAt != inState.requiredEnd) -1 else inAt
}

private class CharNode(val char: Char, val case: Int) : Node() {
	override fun match(inState: Matching, inAt: Int): Int =
		if (inAt < inState.input.size && sameChar(inState.input[inAt], char, case)) rest(inState, inAt + 1) else -1
}

private fun isLineEnd(inChar: Char, inUnixLines: Boolean): Boolean =
	inChar == '\n' || (!inUnixLines && (inChar == '\r' || inChar == '\u0085' || inChar == '\u2028' || inChar == '\u2029'))

private class SetNode(val set: CharSet?, val matchesLines: Boolean, val case: Int, val unixLines: Boolean) : Node() {
	fun accepts(inChar: Char): Boolean =
		if (set != null) set.accepts(inChar, case) else matchesLines || !isLineEnd(inChar, unixLines)

	override fun match(inState: Matching, inAt: Int): Int {
		if (inAt >= inState.input.size) return -1
		val vChar = inState.input[inAt]
		// The two halves of a character outside the first 65536 are one character: taken together, or not at all.
		if (vChar.isHighSurrogate() && inAt + 1 < inState.input.size && inState.input[inAt + 1].isLowSurrogate()) {
			return if (set == null || (accepts(vChar) && accepts(inState.input[inAt + 1]))) rest(inState, inAt + 2) else -1
		}
		return if (accepts(vChar)) rest(inState, inAt + 1) else -1
	}
}

/** What `\\b` takes for part of a word: what `\\w` accepts, with or without the flag U. */
private fun isWordChar(inChar: Char, inUnicode: Boolean): Boolean = inNamedClass(if (inUnicode) kUnicodeWord else kClassWord, inChar)

private class AnchorNode(val kind: Char, val multiline: Boolean, val unixLines: Boolean, val unicodeWords: Boolean) : Node() {
	private fun isLineEnd(inChar: Char): Boolean = isLineEnd(inChar, unixLines)

	override fun match(inState: Matching, inAt: Int): Int {
		val vInput = inState.input
		val vLength = vInput.size
		val vHolds = when (kind) {
			'^' -> inAt == 0 || (multiline && isLineEnd(vInput[inAt - 1]) && inAt < vLength && !(vInput[inAt - 1] == '\r' && vInput[inAt] == '\n'))
			'$' -> if (multiline) {
				inAt == vLength || (isLineEnd(vInput[inAt]) && !(vInput[inAt] == '\n' && inAt > 0 && vInput[inAt - 1] == '\r'))
			} else {
				inAt == vLength || (inAt == vLength - 1 && isLineEnd(vInput[inAt])) || (inAt == vLength - 2 && vInput[inAt] == '\r' && vInput[inAt + 1] == '\n')
			}
			'A' -> inAt == 0
			'z' -> inAt == vLength
			'Z' -> inAt == vLength || (inAt == vLength - 1 && isLineEnd(vInput[inAt])) || (inAt == vLength - 2 && vInput[inAt] == '\r' && vInput[inAt + 1] == '\n')
			else -> {
				val vBefore = inAt > 0 && isWordChar(vInput[inAt - 1], unicodeWords)
				val vAfter = inAt < vLength && isWordChar(vInput[inAt], unicodeWords)
				(vBefore != vAfter) == (kind == 'b')
			}
		}
		return if (vHolds) rest(inState, inAt) else -1
	}
}

/** Records where a group starts or ends; undone when what follows does not match. */
private class GroupMark(val slot: Int) : Node() {
	override fun match(inState: Matching, inAt: Int): Int {
		val vOld = inState.groups[slot]
		inState.groups[slot] = inAt
		val vEnd = rest(inState, inAt)
		if (vEnd < 0) inState.groups[slot] = vOld
		return vEnd
	}
}

private class BranchNode(val options: List<Node>) : Node() {
	override fun match(inState: Matching, inAt: Int): Int {
		for (vIndex in 0 until options.size) {
			val vEnd = options[vIndex].match(inState, inAt)
			if (vEnd >= 0) return vEnd
		}
		return -1
	}
}

private class BackReferenceNode(val group: Int, val case: Int) : Node() {
	override fun match(inState: Matching, inAt: Int): Int {
		// A group the pattern does not have matches nothing, as in Java.
		if (group * 2 + 1 >= inState.groups.size) return -1
		val vStart = inState.groups[group * 2]
		val vEnd = inState.groups[group * 2 + 1]
		// A group that did not take part matches nothing, as in Java.
		if (vStart < 0 || vEnd < 0) return -1
		val vLength = vEnd - vStart
		if (inAt + vLength > inState.input.size) return -1
		for (vIndex in 0 until vLength) if (!sameChar(inState.input[vStart + vIndex], inState.input[inAt + vIndex], case)) return -1
		return rest(inState, inAt + vLength)
	}
}

/** A repeated single character or set: counted directly, then given back one at a time. */
private class SimpleLoop(val item: Node, val min: Int, val max: Int, val greedy: Boolean) : Node() {
	private fun accepts(inState: Matching, inAt: Int): Boolean {
		if (inAt >= inState.input.size) return false
		val vChar = inState.input[inAt]
		return if (item is CharNode) sameChar(vChar, item.char, item.case) else (item as SetNode).accepts(vChar)
	}

	override fun match(inState: Matching, inAt: Int): Int {
		var vCount = 0
		if (greedy) {
			while (vCount < max && accepts(inState, inAt + vCount)) vCount++
			while (vCount >= min) {
				val vEnd = rest(inState, inAt + vCount)
				if (vEnd >= 0) return vEnd
				vCount--
			}
			return -1
		}
		while (vCount < min) {
			if (!accepts(inState, inAt + vCount)) return -1
			vCount++
		}
		while (true) {
			val vEnd = rest(inState, inAt + vCount)
			if (vEnd >= 0) return vEnd
			if (vCount >= max || !accepts(inState, inAt + vCount)) return -1
			vCount++
		}
	}
}

/**
 * A repeated group. The body ends in a [LoopEnd] that comes back here; how many rounds have
 * been made is kept in the matching state, saved and restored around each attempt.
 */
private class Loop(val id: Int, val min: Int, val max: Int, val greedy: Boolean) : Node() {
	var body: Node? = null

	override fun match(inState: Matching, inAt: Int): Int {
		val vCount = inState.counts[id]
		val vStart = inState.starts[id]
		inState.counts[id] = 0
		inState.starts[id] = -1
		val vEnd = round(inState, inAt)
		if (vEnd < 0) {
			inState.counts[id] = vCount
			inState.starts[id] = vStart
		}
		return vEnd
	}

	private fun again(inState: Matching, inAt: Int, inCount: Int): Int {
		val vStart = inState.starts[id]
		inState.counts[id] = inCount + 1
		inState.starts[id] = inAt
		val vEnd = body!!.match(inState, inAt)
		if (vEnd < 0) {
			inState.counts[id] = inCount
			inState.starts[id] = vStart
		}
		return vEnd
	}

	fun round(inState: Matching, inAt: Int): Int {
		val vCount = inState.counts[id]
		if (vCount < min) return again(inState, inAt, vCount)
		if (greedy) {
			if (vCount < max) {
				val vEnd = again(inState, inAt, vCount)
				if (vEnd >= 0) return vEnd
			}
			return rest(inState, inAt)
		}
		val vEnd = rest(inState, inAt)
		if (vEnd >= 0) return vEnd
		return if (vCount < max) again(inState, inAt, vCount) else -1
	}

	fun leave(inState: Matching, inAt: Int): Int = rest(inState, inAt)
}

/**
 * The end of a loop's body. It names its loop by number and finds it through the matching
 * state: a reference back to the loop would make a cycle, which reference counting never frees.
 */
private class LoopEnd(val loopId: Int) : Node() {
	override fun match(inState: Matching, inAt: Int): Int {
		val vLoop = inState.loops[loopId]
		// A round that consumed nothing would repeat forever: the loop ends there.
		if (inAt == inState.starts[loopId]) return if (inState.counts[loopId] >= vLoop.min) vLoop.leave(inState, inAt) else -1
		return vLoop.round(inState, inAt)
	}
}

private class LookNode(val body: Node, val ahead: Boolean, val negated: Boolean) : Node() {
	override fun match(inState: Matching, inAt: Int): Int {
		val vRequired = inState.requiredEnd
		var vFound = false
		if (ahead) {
			inState.requiredEnd = -1
			vFound = body.match(inState, inAt) >= 0
		} else {
			// Behind: some start before here from which the body ends exactly here.
			inState.requiredEnd = inAt
			var vStart = inAt
			while (vStart >= 0 && !vFound) {
				vFound = body.match(inState, vStart) >= 0
				vStart--
			}
		}
		inState.requiredEnd = vRequired
		return if (vFound != negated) rest(inState, inAt) else -1
	}
}

/** `(?>…)`: the body is matched on its own, the first way it can; what follows cannot make it try another. */
private class AtomicNode(val body: Node) : Node() {
	override fun match(inState: Matching, inAt: Int): Int {
		val vRequired = inState.requiredEnd
		// What the body sets in the groups is undone if what follows fails.
		val vGroups = if (inState.groups.size > 2) inState.groups.copyOf() else null
		inState.requiredEnd = -1
		val vBodyEnd = body.match(inState, inAt)
		inState.requiredEnd = vRequired
		if (vBodyEnd < 0) return -1
		val vEnd = rest(inState, vBodyEnd)
		if (vEnd < 0 && vGroups != null) vGroups.copyInto(inState.groups)
		return vEnd
	}
}

/** Turns the parsed pattern into linked nodes. */
private class Compiler {
	/** The loops of the pattern, each at the index that is its id. */
	val loops = ArrayList<Loop>()

	/** The nodes for [inPiece], continuing with [inNext]. */
	fun compile(inPiece: Piece, inNext: Node): Node {
		when (inPiece) {
			is Literal -> return CharNode(inPiece.char, inPiece.case).also { it.next = inNext }
			is SetPiece -> return SetNode(inPiece.set, false, inPiece.case, false).also { it.next = inNext }
			is Dot -> return SetNode(null, inPiece.matchesLines, kCaseExact, inPiece.unixLines).also { it.next = inNext }
			is Anchor -> return AnchorNode(inPiece.kind, inPiece.multiline, inPiece.unixLines, inPiece.unicodeWords).also { it.next = inNext }
			is BackReference -> return BackReferenceNode(inPiece.group, inPiece.case).also { it.next = inNext }
			is Atomic -> return AtomicNode(compile(inPiece.body, Accept())).also { it.next = inNext }
			is Chain -> {
				var vNext = inNext
				for (vIndex in inPiece.items.size - 1 downTo 0) vNext = compile(inPiece.items[vIndex], vNext)
				return vNext
			}
			is Alternatives -> {
				val vOptions = ArrayList<Node>()
				for (vOption in inPiece.options) vOptions.add(compile(vOption, inNext))
				return BranchNode(vOptions).also { it.next = inNext }
			}
			is Group -> {
				if (inPiece.index == 0) return compile(inPiece.body, inNext)
				val vClose = GroupMark(inPiece.index * 2 + 1).also { it.next = inNext }
				return GroupMark(inPiece.index * 2).also { it.next = compile(inPiece.body, vClose) }
			}
			is Look -> {
				val vBody = compile(inPiece.body, Accept())
				return LookNode(vBody, inPiece.ahead, inPiece.negated).also { it.next = inNext }
			}
			is Repeat -> {
				val vBody = inPiece.body
				if (vBody is Literal || vBody is SetPiece || vBody is Dot) {
					return SimpleLoop(compile(vBody, Accept()), inPiece.min, inPiece.max, inPiece.greedy).also { it.next = inNext }
				}
				val vLoop = Loop(loops.size, inPiece.min, inPiece.max, inPiece.greedy)
				loops.add(vLoop)
				vLoop.next = inNext
				vLoop.body = compile(vBody, LoopEnd(vLoop.id))
				return vLoop
			}
			else -> throw IllegalStateException("unknown pattern piece")
		}
	}
}

// ==================
// MARK: Match results
// ==================

private class GroupCollection(private val mMatch: MatchData) : AbstractCollection<MatchGroup?>(), MatchNamedGroupCollection {
	override val size: Int get() = mMatch.groupCount + 1

	override fun isEmpty(): Boolean = false

	override fun iterator(): Iterator<MatchGroup?> {
		val vGroups = ArrayList<MatchGroup?>()
		for (vIndex in 0 until size) vGroups.add(get(vIndex))
		return vGroups.iterator()
	}

	override fun get(index: Int): MatchGroup? {
		if (index < 0 || index > mMatch.groupCount) throw IndexOutOfBoundsException("No group $index")
		val vStart = mMatch.bounds[index * 2]
		val vEnd = mMatch.bounds[index * 2 + 1]
		if (vStart < 0 || vEnd < 0) return null
		return MatchGroup(mMatch.text.substring(vStart, vEnd), IntRange(vStart, vEnd - 1))
	}

	override fun get(name: String): MatchGroup? {
		val vIndex = mMatch.regex.groupIndex(name) ?: throw IllegalArgumentException("No group with name <$name>")
		return get(vIndex)
	}
}

/** One match: the positions of the groups in the text. */
private class MatchData(val regex: Regex, val text: String, val chars: CharArray, val bounds: IntArray, val groupCount: Int) : MatchResult {
	override val range: IntRange get() = IntRange(bounds[0], bounds[1] - 1)

	override val value: String get() = text.substring(bounds[0], bounds[1])

	override val groups: MatchGroupCollection get() = GroupCollection(this)

	override val groupValues: List<String>
		get() {
			val vValues = ArrayList<String>()
			for (vIndex in 0..groupCount) {
				val vStart = bounds[vIndex * 2]
				val vEnd = bounds[vIndex * 2 + 1]
				vValues.add(if (vStart < 0 || vEnd < 0) "" else text.substring(vStart, vEnd))
			}
			return vValues
		}

	override fun next(): MatchResult? {
		val vFrom = bounds[1] + if (bounds[1] == bounds[0]) 1 else 0
		// The search goes on in the characters this match was found in: they are not copied again.
		return if (vFrom <= text.length) regex.search(text, vFrom, false, false, chars) else null
	}
}

// ==================
// MARK: Regex
// ==================

public actual class Regex actual constructor(pattern: String, options: Set<RegexOption>) {
	public actual val pattern: String = pattern
	public actual val options: Set<RegexOption> = options

	private val mRoot: Node
	private val mGroupCount: Int
	private val mLoops: List<Loop>
	private val mGroupNames: HashMap<String, Int>

	/** The character every match starts with, when the pattern starts with one, exactly; -1 otherwise. */
	private val mFirst: Int

	init {
		val vParser = Parser(pattern, options.contains(RegexOption.IGNORE_CASE), options.contains(RegexOption.MULTILINE))
		val vPiece = vParser.parse()
		val vCompiler = Compiler()
		mRoot = vCompiler.compile(vPiece, Accept())
		mGroupCount = vParser.groupCount
		mLoops = vCompiler.loops
		mGroupNames = vParser.groupNames
		// Through the starts of groups, which match nothing themselves.
		var vNode: Node? = mRoot
		while (vNode is GroupMark) vNode = vNode.next
		mFirst = if (vNode is CharNode && vNode.case == kCaseExact) vNode.char.code else -1
	}

	public actual constructor(pattern: String) : this(pattern, emptySet())

	public actual constructor(pattern: String, option: RegexOption) : this(pattern, setOf(option))

	internal fun groupIndex(inName: String): Int? = mGroupNames[inName]

	/**
	 * The first match starting at [inFrom] or, unless [inAnchored], later; with [inEntire] it
	 * has to reach the end of the text. [inChars] is the text as characters when the caller has it.
	 */
	internal fun search(inText: String, inFrom: Int, inAnchored: Boolean, inEntire: Boolean, inChars: CharArray? = null): MatchResult? {
		val vChars = inChars ?: inText.toCharArray()
		val vState = Matching(vChars, mGroupCount, mLoops)
		if (inEntire) vState.requiredEnd = inText.length
		var vStart = inFrom
		val vLength = vChars.size
		while (vStart <= vLength) {
			if (mFirst >= 0 && !inAnchored) {
				// Nothing matches before the next place the first character is.
				while (vStart < vLength && vChars[vStart].code != mFirst) vStart++
				if (vStart == vLength) break
			}
			vState.reset()
			val vEnd = mRoot.match(vState, vStart)
			if (vEnd >= 0) {
				vState.groups[0] = vStart
				vState.groups[1] = vEnd
				return MatchData(this, inText, vChars, vState.groups, mGroupCount)
			}
			if (inAnchored) break
			vStart++
		}
		return null
	}

	public actual fun matchEntire(input: CharSequence): MatchResult? = search(input.toString(), 0, true, true)

	public actual infix fun matches(input: CharSequence): Boolean = matchEntire(input) != null

	public actual fun matchAt(input: CharSequence, index: Int): MatchResult? {
		if (index < 0 || index > input.length) throw IndexOutOfBoundsException("index out of bounds: $index, input length: ${input.length}")
		return search(input.toString(), index, true, false)
	}

	public actual fun matchesAt(input: CharSequence, index: Int): Boolean = matchAt(input, index) != null

	public actual fun containsMatchIn(input: CharSequence): Boolean = search(input.toString(), 0, false, false) != null

	public actual fun find(input: CharSequence, startIndex: Int): MatchResult? {
		if (startIndex < 0 || startIndex > input.length) {
			throw IndexOutOfBoundsException("Start index out of bounds: $startIndex, input length: ${input.length}")
		}
		return search(input.toString(), startIndex, false, false)
	}

	public actual fun findAll(input: CharSequence, startIndex: Int): Sequence<MatchResult> {
		if (startIndex < 0 || startIndex > input.length) {
			throw IndexOutOfBoundsException("Start index out of bounds: $startIndex, input length: ${input.length}")
		}
		val vText = input.toString()
		return generateSequence({ search(vText, startIndex, false, false) }, { it.next() })
	}

	/** Appends [inReplacement] to [outText], with `$1`, `${name}` and `\x` filled in from [inMatch]. */
	private fun expand(inReplacement: String, inMatch: MatchResult, outText: StringBuilder) {
		var vIndex = 0
		while (vIndex < inReplacement.length) {
			val vChar = inReplacement[vIndex++]
			if (vChar == '\\') {
				if (vIndex == inReplacement.length) throw IllegalArgumentException("character to be escaped is missing")
				outText.append(inReplacement[vIndex++])
			} else if (vChar == '$') {
				if (vIndex == inReplacement.length) throw IllegalArgumentException("Illegal group reference: group index is missing")
				var vGroup: Int
				if (inReplacement[vIndex] == '{') {
					val vEnd = inReplacement.indexOf('}', vIndex)
					if (vEnd < 0) throw IllegalArgumentException("named capturing group is missing trailing '}'")
					val vName = inReplacement.substring(vIndex + 1, vEnd)
					vGroup = mGroupNames[vName] ?: throw IllegalArgumentException("No group with name {$vName}")
					vIndex = vEnd + 1
				} else {
					if (inReplacement[vIndex] !in '0'..'9') throw IllegalArgumentException("Illegal group reference")
					vGroup = inReplacement[vIndex++] - '0'
					if (vGroup > mGroupCount) throw IndexOutOfBoundsException("No group $vGroup")
					// Further digits belong to the number while they still name a group.
					while (vIndex < inReplacement.length && inReplacement[vIndex] in '0'..'9' && vGroup * 10 + (inReplacement[vIndex] - '0') <= mGroupCount) {
						vGroup = vGroup * 10 + (inReplacement[vIndex++] - '0')
					}
				}
				outText.append(inMatch.groupValues[vGroup])
			} else {
				outText.append(vChar)
			}
		}
	}

	public actual fun replace(input: CharSequence, replacement: String): String {
		val vText = input.toString()
		var vMatch = search(vText, 0, false, false) ?: return vText
		val vResult = StringBuilder()
		var vLast = 0
		while (true) {
			vResult.append(vText, vLast, vMatch.range.first)
			expand(replacement, vMatch, vResult)
			vLast = vMatch.range.last + 1
			vMatch = vMatch.next() ?: break
		}
		vResult.append(vText, vLast, vText.length)
		return vResult.toString()
	}

	public actual fun replace(input: CharSequence, transform: (MatchResult) -> CharSequence): String {
		val vText = input.toString()
		var vMatch = search(vText, 0, false, false) ?: return vText
		val vResult = StringBuilder()
		var vLast = 0
		while (true) {
			vResult.append(vText, vLast, vMatch.range.first)
			vResult.append(transform(vMatch))
			vLast = vMatch.range.last + 1
			vMatch = vMatch.next() ?: break
		}
		vResult.append(vText, vLast, vText.length)
		return vResult.toString()
	}

	public actual fun replaceFirst(input: CharSequence, replacement: String): String {
		val vText = input.toString()
		val vMatch = search(vText, 0, false, false) ?: return vText
		val vResult = StringBuilder()
		vResult.append(vText, 0, vMatch.range.first)
		expand(replacement, vMatch, vResult)
		vResult.append(vText, vMatch.range.last + 1, vText.length)
		return vResult.toString()
	}

	public actual fun split(input: CharSequence, limit: Int): List<String> {
		if (limit < 0) throw IllegalArgumentException("Limit must be non-negative, but was $limit")
		val vText = input.toString()
		var vMatch = if (limit == 1) null else search(vText, 0, false, false)
		if (vMatch == null) return listOf(vText)
		val vResult = ArrayList<String>()
		var vLast = 0
		val vLastSplit = limit - 1
		while (vMatch != null) {
			vResult.add(vText.substring(vLast, vMatch.range.first))
			vLast = vMatch.range.last + 1
			if (vLastSplit >= 0 && vResult.size == vLastSplit) break
			vMatch = vMatch.next()
		}
		vResult.add(vText.substring(vLast, vText.length))
		return vResult
	}

	public actual fun splitToSequence(input: CharSequence, limit: Int): Sequence<String> = split(input, limit).asSequence()

	override fun toString(): String = pattern

	public actual companion object {
		public actual fun fromLiteral(literal: String): Regex = Regex(escape(literal))

		/** [literal] quoted so that every character stands for itself. */
		public actual fun escape(literal: String): String {
			if (literal.indexOf("\\E") < 0) return "\\Q$literal\\E"
			val vResult = StringBuilder("\\Q")
			var vFrom = 0
			while (true) {
				val vAt = literal.indexOf("\\E", vFrom)
				if (vAt < 0) break
				vResult.append(literal, vFrom, vAt).append("\\E\\\\E\\Q")
				vFrom = vAt + 2
			}
			return vResult.append(literal, vFrom, literal.length).append("\\E").toString()
		}

		public actual fun escapeReplacement(literal: String): String {
			if (literal.indexOf('\\') < 0 && literal.indexOf('$') < 0) return literal
			val vResult = StringBuilder()
			for (vChar in literal) {
				if (vChar == '\\' || vChar == '$') vResult.append('\\')
				vResult.append(vChar)
			}
			return vResult.toString()
		}
	}
}
