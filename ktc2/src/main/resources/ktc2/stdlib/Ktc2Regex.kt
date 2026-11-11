@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "ACTUAL_ANNOTATIONS_NOT_MATCH_EXPECT", "ACTUAL_WITHOUT_EXPECT", "UNCHECKED_CAST")

package kotlin.text

/*
kotlin.text.Regex for ktc2: a backtracking matcher written in Kotlin, with the syntax and the
behavior of java.util.regex for what it covers:

  literals and escapes (\t \n \r \f \e \0 \xHH \uHHHH, \Q…\E), `.`, character classes with
  ranges, negation and nested classes, \d \w \s \D \W \S, \p{…} for the common classes,
  anchors ^ $ \A \z \Z \b \B, capturing, named and non-capturing groups, alternation,
  greedy and lazy quantifiers (* + ? {n} {n,} {n,m}), back references (\1, \k<name>),
  look-ahead and look-behind, and the inline flags (?i) (?m) (?s) at the start of the pattern.

Not covered: possessive quantifiers and atomic groups, class intersection (&&), flags in the
middle of a pattern, Unicode properties beyond letters and digits. A pattern using them is
refused with an IllegalArgumentException when the Regex is created.
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

private const val kClassDigit = 1
private const val kClassWord = 2
private const val kClassSpace = 3
private const val kClassLetter = 4
private const val kClassUpper = 5
private const val kClassLower = 6
private const val kClassAlnum = 7
private const val kClassPunct = 8

private fun inNamedClass(inKind: Int, inChar: Char): Boolean = when (inKind) {
	kClassDigit -> inChar in '0'..'9'
	kClassWord -> inChar in 'a'..'z' || inChar in 'A'..'Z' || inChar in '0'..'9' || inChar == '_'
	kClassSpace -> inChar == ' ' || inChar == '\t' || inChar == '\n' || inChar == '\r' || inChar == '\u000B' || inChar == '\u000C'
	kClassLetter -> inChar.isLetter()
	kClassUpper -> inChar.isUpperCase()
	kClassLower -> inChar.isLowerCase()
	kClassAlnum -> inChar.isLetterOrDigit()
	else -> inChar in '!'..'/' || inChar in ':'..'@' || inChar in '['..'`' || inChar in '{'..'~'
}

/** What a `[…]`, a `\d` or a `.` accepts: ranges, named classes and nested sets, possibly negated. */
private class CharSet(val negated: Boolean) {
	val rangeStarts = ArrayList<Char>()
	val rangeEnds = ArrayList<Char>()
	val kinds = ArrayList<Int>()
	val negatedKinds = ArrayList<Int>()
	val nested = ArrayList<CharSet>()

	fun addRange(inFrom: Char, inTo: Char) {
		rangeStarts.add(inFrom)
		rangeEnds.add(inTo)
	}

	private fun holds(inChar: Char): Boolean {
		for (vIndex in 0 until rangeStarts.size) if (inChar >= rangeStarts[vIndex] && inChar <= rangeEnds[vIndex]) return true
		for (vKind in kinds) if (inNamedClass(vKind, inChar)) return true
		for (vKind in negatedKinds) if (!inNamedClass(vKind, inChar)) return true
		for (vSet in nested) if (vSet.accepts(inChar, false)) return true
		return false
	}

	fun accepts(inChar: Char, inIgnoreCase: Boolean): Boolean {
		var vHolds = holds(inChar)
		if (!vHolds && inIgnoreCase) vHolds = holds(inChar.lowercaseChar()) || holds(inChar.uppercaseChar())
		return vHolds != negated
	}
}

// ==================
// MARK: Parsed pattern
// ==================

private abstract class Piece

private class Literal(val char: Char) : Piece()

private class SetPiece(val set: CharSet) : Piece()

/** `.`: anything but a line terminator, or anything at all with (?s). */
private class Dot(val matchesLines: Boolean) : Piece()

private class Chain(val items: List<Piece>) : Piece()

private class Alternatives(val options: List<Piece>) : Piece()

/** A capturing group; [index] is 0 for one that does not capture. */
private class Group(val index: Int, val body: Piece) : Piece()

private class Repeat(val body: Piece, val min: Int, val max: Int, val greedy: Boolean) : Piece()

private class BackReference(val group: Int) : Piece()

private class Look(val body: Piece, val ahead: Boolean, val negated: Boolean) : Piece()

/** `^` `$` `\A` `\z` `\Z` `\b` `\B`, named by the character that writes them. */
private class Anchor(val kind: Char) : Piece()

// ==================
// MARK: Parser
// ==================

private class Parser(private val mPattern: String) {
	private var mAt = 0
	var groupCount = 0
	val groupNames = HashMap<String, Int>()
	var ignoreCase = false
	var multiline = false
	var dotAll = false

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
		// Flags that apply to the whole pattern.
		while (looksAt("(?") && mAt + 2 < mPattern.length && mPattern[mAt + 2] in "imsx" ) {
			var vEnd = mAt + 2
			while (vEnd < mPattern.length && mPattern[vEnd] in "imsx") vEnd++
			if (vEnd >= mPattern.length || mPattern[vEnd] != ')') break
			for (vIndex in mAt + 2 until vEnd) {
				when (mPattern[vIndex]) {
					'i' -> ignoreCase = true
					'm' -> multiline = true
					's' -> dotAll = true
					else -> fail("Unsupported flag '${mPattern[vIndex]}'")
				}
			}
			mAt = vEnd + 1
		}
		val vPiece = alternatives()
		if (more()) fail(if (peek() == ')') "Unmatched closing ')'" else "Unexpected character '${peek()}'")
		return vPiece
	}

	private fun alternatives(): Piece {
		val vOptions = ArrayList<Piece>()
		vOptions.add(sequence())
		while (eat('|')) vOptions.add(sequence())
		return if (vOptions.size == 1) vOptions[0] else Alternatives(vOptions)
	}

	private fun sequence(): Piece {
		val vItems = ArrayList<Piece>()
		while (more() && peek() != '|' && peek() != ')') {
			if (looksAt("\\Q")) {
				mAt += 2
				val vEnd = mPattern.indexOf("\\E", mAt).let { if (it < 0) mPattern.length else it }
				for (vIndex in mAt until vEnd) vItems.add(Literal(mPattern[vIndex]))
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
		if (eat('?')) vGreedy = false else if (more() && peek() == '+') fail("Possessive quantifiers are not supported")
		val vRepeat = Repeat(inAtom, vMin, vMax, vGreedy)
		// `a**` and the like are errors in Java too.
		if (more() && (peek() == '*' || peek() == '+' || peek() == '?')) fail("Dangling meta character '${peek()}'")
		return vRepeat
	}

	private fun atom(): Piece {
		val vChar = mPattern[mAt++]
		when (vChar) {
			'(' -> return group()
			'[' -> return SetPiece(charClass())
			'.' -> return Dot(dotAll)
			'^', '$' -> return Anchor(vChar)
			'*', '+', '?' -> fail("Dangling meta character '$vChar'")
			'\\' -> return escape()
			else -> return Literal(vChar)
		}
	}

	private fun group(): Piece {
		if (!eat('?')) {
			val vIndex = ++groupCount
			val vBody = alternatives()
			if (!eat(')')) fail("Unclosed group")
			return Group(vIndex, vBody)
		}
		if (!more()) fail("Unclosed group")
		val vKind = mPattern[mAt++]
		val vPiece: Piece = when (vKind) {
			':' -> Group(0, alternatives())
			'=' -> Look(alternatives(), true, false)
			'!' -> Look(alternatives(), true, true)
			'<' -> {
				if (eat('=')) {
					Look(alternatives(), false, false)
				} else if (eat('!')) {
					Look(alternatives(), false, true)
				} else {
					val vStart = mAt
					while (more() && peek() != '>') mAt++
					val vName = mPattern.substring(vStart, mAt)
					if (!eat('>') || vName.isEmpty()) fail("Named capturing group is missing trailing '>'")
					if (groupNames.containsKey(vName)) fail("Named capturing group <$vName> is already defined")
					val vIndex = ++groupCount
					groupNames[vName] = vIndex
					Group(vIndex, alternatives())
				}
			}
			'>' -> fail("Atomic groups are not supported")
			else -> fail("Unknown inline modifier")
		}
		if (!eat(')')) fail("Unclosed group")
		return vPiece
	}

	private fun hex(inDigits: Int): Char {
		if (mAt + inDigits > mPattern.length) fail("Illegal hexadecimal escape sequence")
		val vValue = mPattern.substring(mAt, mAt + inDigits).toIntOrNull(16) ?: fail("Illegal hexadecimal escape sequence")
		mAt += inDigits
		return vValue.toChar()
	}

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
		val vKind = when (vName.removePrefix("Is").removePrefix("is")) {
			"L", "Alpha", "Alphabetic", "Letter" -> kClassLetter
			"Lu", "Upper", "Uppercase" -> kClassUpper
			"Ll", "Lower", "Lowercase" -> kClassLower
			"N", "Nd", "Digit" -> kClassDigit
			"Alnum" -> kClassAlnum
			"Punct", "P" -> kClassPunct
			"Space", "White_Space", "Z" -> kClassSpace
			else -> fail("Unknown character property name {$vName}")
		}
		if (inNegated) outSet.negatedKinds.add(vKind) else outSet.kinds.add(vKind)
	}

	/**
	 * What follows a backslash that stands for a set of characters, added to [outSet]; false
	 * when it is something else.
	 */
	private fun classEscape(inChar: Char, outSet: CharSet): Boolean {
		when (inChar) {
			'd' -> outSet.kinds.add(kClassDigit)
			'D' -> outSet.negatedKinds.add(kClassDigit)
			'w' -> outSet.kinds.add(kClassWord)
			'W' -> outSet.negatedKinds.add(kClassWord)
			's' -> outSet.kinds.add(kClassSpace)
			'S' -> outSet.negatedKinds.add(kClassSpace)
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
			var vValue = 0
			var vDigits = 0
			while (more() && vDigits < 3 && peek() in '0'..'7') {
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
		if (classEscape(vChar, vSet)) return SetPiece(vSet)
		when (vChar) {
			'b', 'B', 'A', 'z', 'Z' -> return Anchor(vChar)
			'k' -> {
				if (!eat('<')) fail("\\k is not followed by '<' for named capturing group")
				val vStart = mAt
				while (more() && peek() != '>') mAt++
				val vName = mPattern.substring(vStart, mAt)
				if (!eat('>')) fail("Named capturing group is missing trailing '>'")
				return BackReference(groupNames[vName] ?: fail("Named capturing group <$vName> does not exist"))
			}
		}
		if (vChar in '1'..'9') {
			// As many digits as still name an existing group.
			var vGroup = vChar - '0'
			while (more() && peek() in '0'..'9' && vGroup * 10 + (peek() - '0') <= groupCount) vGroup = vGroup * 10 + (mPattern[mAt++] - '0')
			return BackReference(vGroup)
		}
		return Literal(literalEscape(vChar))
	}

	private fun charClass(): CharSet {
		val vSet = CharSet(eat('^'))
		var vFirst = true
		while (true) {
			if (!more()) fail("Unclosed character class")
			var vChar = mPattern[mAt++]
			if (vChar == ']' && !vFirst) break
			vFirst = false
			if (vChar == '[') {
				vSet.nested.add(charClass())
				continue
			}
			if (vChar == '&' && more() && peek() == '&') fail("Character class intersection is not supported")
			if (vChar == '\\') {
				if (!more()) fail("Unclosed character class")
				val vEscaped = mPattern[mAt++]
				if (classEscape(vEscaped, vSet)) continue
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
				vSet.addRange(vChar, vEnd)
			} else {
				vSet.addRange(vChar, vChar)
			}
		}
		return vSet
	}
}

// ==================
// MARK: Matcher
// ==================

/** The state of one attempt to match. */
private class Matching(val input: CharArray, inGroupCount: Int, val loops: List<Loop>, val ignoreCase: Boolean, val multiline: Boolean) {
	/** Start and end of each group, -1 while it has not taken part. Group 0 is the whole match. */
	val groups = IntArray((inGroupCount + 1) * 2)

	/** For each loop: how many times it has gone round, and where the current round started. */
	val counts = IntArray(loops.size)
	val starts = IntArray(loops.size)

	/** When not negative, the match has to end exactly here (matchEntire, look-behind). */
	var requiredEnd = -1

	fun reset() {
		for (vIndex in 0 until groups.size) groups[vIndex] = -1
	}

	fun same(inLeft: Char, inRight: Char): Boolean =
		inLeft == inRight || (ignoreCase && (inLeft.lowercaseChar() == inRight.lowercaseChar() || inLeft.uppercaseChar() == inRight.uppercaseChar()))
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

private class CharNode(val char: Char) : Node() {
	override fun match(inState: Matching, inAt: Int): Int =
		if (inAt < inState.input.size && inState.same(inState.input[inAt], char)) rest(inState, inAt + 1) else -1
}

private fun isLineEnd(inChar: Char): Boolean =
	inChar == '\n' || inChar == '\r' || inChar == '\u0085' || inChar == '\u2028' || inChar == '\u2029'

private class SetNode(val set: CharSet?, val matchesLines: Boolean) : Node() {
	fun accepts(inState: Matching, inChar: Char): Boolean =
		if (set != null) set.accepts(inChar, inState.ignoreCase) else matchesLines || !isLineEnd(inChar)

	override fun match(inState: Matching, inAt: Int): Int =
		if (inAt < inState.input.size && accepts(inState, inState.input[inAt])) rest(inState, inAt + 1) else -1
}

private fun isWordChar(inChar: Char): Boolean = inChar.isLetterOrDigit() || inChar == '_'

private class AnchorNode(val kind: Char) : Node() {
	override fun match(inState: Matching, inAt: Int): Int {
		val vInput = inState.input
		val vLength = vInput.size
		val vHolds = when (kind) {
			'^' -> inAt == 0 || (inState.multiline && isLineEnd(vInput[inAt - 1]) && inAt < vLength && !(vInput[inAt - 1] == '\r' && vInput[inAt] == '\n'))
			'$' -> if (inState.multiline) {
				inAt == vLength || (isLineEnd(vInput[inAt]) && !(vInput[inAt] == '\n' && inAt > 0 && vInput[inAt - 1] == '\r'))
			} else {
				inAt == vLength || (inAt == vLength - 1 && isLineEnd(vInput[inAt])) || (inAt == vLength - 2 && vInput[inAt] == '\r' && vInput[inAt + 1] == '\n')
			}
			'A' -> inAt == 0
			'z' -> inAt == vLength
			'Z' -> inAt == vLength || (inAt == vLength - 1 && isLineEnd(vInput[inAt])) || (inAt == vLength - 2 && vInput[inAt] == '\r' && vInput[inAt + 1] == '\n')
			else -> {
				val vBefore = inAt > 0 && isWordChar(vInput[inAt - 1])
				val vAfter = inAt < vLength && isWordChar(vInput[inAt])
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
		for (vOption in options) {
			val vEnd = vOption.match(inState, inAt)
			if (vEnd >= 0) return vEnd
		}
		return -1
	}
}

private class BackReferenceNode(val group: Int) : Node() {
	override fun match(inState: Matching, inAt: Int): Int {
		val vStart = inState.groups[group * 2]
		val vEnd = inState.groups[group * 2 + 1]
		// A group that did not take part matches nothing, as in Java.
		if (vStart < 0 || vEnd < 0) return -1
		val vLength = vEnd - vStart
		if (inAt + vLength > inState.input.size) return -1
		for (vIndex in 0 until vLength) if (!inState.same(inState.input[vStart + vIndex], inState.input[inAt + vIndex])) return -1
		return rest(inState, inAt + vLength)
	}
}

/** A repeated single character or set: counted directly, then given back one at a time. */
private class SimpleLoop(val item: Node, val min: Int, val max: Int, val greedy: Boolean) : Node() {
	private fun accepts(inState: Matching, inAt: Int): Boolean {
		if (inAt >= inState.input.size) return false
		val vChar = inState.input[inAt]
		return if (item is CharNode) inState.same(vChar, item.char) else (item as SetNode).accepts(inState, vChar)
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

/** Turns the parsed pattern into linked nodes. */
private class Compiler {
	/** The loops of the pattern, each at the index that is its id. */
	val loops = ArrayList<Loop>()

	/** The nodes for [inPiece], continuing with [inNext]. */
	fun compile(inPiece: Piece, inNext: Node): Node {
		when (inPiece) {
			is Literal -> return CharNode(inPiece.char).also { it.next = inNext }
			is SetPiece -> return SetNode(inPiece.set, false).also { it.next = inNext }
			is Dot -> return SetNode(null, inPiece.matchesLines).also { it.next = inNext }
			is Anchor -> return AnchorNode(inPiece.kind).also { it.next = inNext }
			is BackReference -> return BackReferenceNode(inPiece.group).also { it.next = inNext }
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
private class MatchData(val regex: Regex, val text: String, val bounds: IntArray, val groupCount: Int) : MatchResult {
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
		return if (vFrom <= text.length) regex.search(text, vFrom, false, false) else null
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
	private val mIgnoreCase: Boolean
	private val mMultiline: Boolean

	init {
		val vParser = Parser(pattern)
		val vPiece = vParser.parse()
		val vCompiler = Compiler()
		mRoot = vCompiler.compile(vPiece, Accept())
		mGroupCount = vParser.groupCount
		mLoops = vCompiler.loops
		mGroupNames = vParser.groupNames
		mIgnoreCase = vParser.ignoreCase || options.contains(RegexOption.IGNORE_CASE)
		mMultiline = vParser.multiline || options.contains(RegexOption.MULTILINE)
	}

	public actual constructor(pattern: String) : this(pattern, emptySet())

	public actual constructor(pattern: String, option: RegexOption) : this(pattern, setOf(option))

	internal fun groupIndex(inName: String): Int? = mGroupNames[inName]

	/**
	 * The first match starting at [inFrom] or, unless [inAnchored], later; with [inEntire] it
	 * has to reach the end of the text.
	 */
	internal fun search(inText: String, inFrom: Int, inAnchored: Boolean, inEntire: Boolean): MatchResult? {
		val vState = Matching(inText.toCharArray(), mGroupCount, mLoops, mIgnoreCase, mMultiline)
		if (inEntire) vState.requiredEnd = inText.length
		var vStart = inFrom
		while (vStart <= inText.length) {
			vState.reset()
			val vEnd = mRoot.match(vState, vStart)
			if (vEnd >= 0) {
				vState.groups[0] = vStart
				vState.groups[1] = vEnd
				return MatchData(this, inText, vState.groups, mGroupCount)
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
