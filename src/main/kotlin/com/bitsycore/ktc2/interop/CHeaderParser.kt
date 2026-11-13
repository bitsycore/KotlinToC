package com.bitsycore.ktc2.interop

// ==================
// MARK: C declarations
// ==================

/** A C type, as far as bindings need to know it. */
sealed interface CType

data object CVoid : CType

/** An arithmetic type: its Kotlin counterpart and the interop variable class holding one. */
data class CPrim(val kotlin: String, val variable: String) : CType

/** A type bindings cannot express (`long double`, `__int128`, a vector…); [what] names it. */
data class CUnsupported(val what: String) : CType

class CPointerTo(val target: CType, val isTargetConst: Boolean) : CType

class CArrayOf(val element: CType) : CType

class CFunctionType(val result: CType, val params: List<CParam>, val isVariadic: Boolean) : CType

class CParam(val name: String?, val type: CType)

/** A use of a typedef name; [target] is what it stands for. */
class CTypedefRef(val name: String, val target: CType) : CType

class CField(val name: String, val type: CType)

/** A struct or union. [fields] is null while only its name is known (an opaque type). */
class CRecord(val isUnion: Boolean, var tag: String?, val file: String) : CType {
	var typedefName: String? = null
	var fields: List<CField>? = null

	/** How C code names the type. */
	val spelling: String get() = tag?.let { (if (isUnion) "union " else "struct ") + it } ?: typedefName ?: ""
}

class CEnum(var tag: String?, val file: String) : CType {
	var typedefName: String? = null

	/** Name and value of each constant; the value is null when it could not be computed here. */
	val constants = ArrayList<Pair<String, Long?>>()

	/** Set when a constant is given its value in the declaration (`A = 4`). */
	var hasExplicitValues = false

	val isUnsigned: Boolean get() = constants.none { (it.second ?: 0L) < 0L }
}

class CFunctionDecl(val name: String, val type: CFunctionType, val file: String)

class CTypedefDecl(val name: String, val type: CType, val file: String)

/** A variable declared `extern`: storage the library defines. */
class CVariableDecl(val name: String, val type: CType, val isConst: Boolean, val file: String)

/** An object-like macro: `#define NAME body`. */
class CMacro(val name: String, val body: String, val file: String)

/** The typedef names behind [this], removed. */
fun CType.resolved(): CType = if (this is CTypedefRef) target.resolved() else this

// ==================
// MARK: Tokens
// ==================

private enum class TokenKind { IDENT, NUMBER, STRING, CHAR, PUNCT, END }

private class Token(val kind: TokenKind, val text: String, val file: String)

private val kPunctuation = listOf("...", "<<=", ">>=", "->", "<<", ">>", "==", "!=", "<=", ">=", "&&", "||", "++", "--", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "##")

/** Thrown inside the parser when a declaration uses something it does not know; the declaration is skipped. */
private class Unparsed(inMessage: String) : Exception(inMessage)

// ==================
// MARK: Parser
// ==================

/**
 * Reads the declarations out of preprocessed C (`gcc -E -dD`): functions, structs, unions,
 * enums, typedefs and object-like macros, each with the header it comes from.
 *
 * This is a declaration parser, not a compiler: function bodies and initializers are skipped,
 * and a declaration using syntax it does not know is dropped without affecting the others.
 *
 * @param inLongSize `sizeof(long)` of the target, which decides whether `long` is Int or Long
 */
class CHeaderParser(private val mText: String, private val mLongSize: Int) {

	val functions = ArrayList<CFunctionDecl>()
	val records = ArrayList<CRecord>()
	val enums = ArrayList<CEnum>()
	val typedefs = ArrayList<CTypedefDecl>()
	val variables = ArrayList<CVariableDecl>()
	val macros = ArrayList<CMacro>()

	private val mTokens = ArrayList<Token>()
	private var mAt = 0
	private val mTypedefs = HashMap<String, CType>()
	private val mRecordsByTag = HashMap<String, CRecord>()
	private val mEnumsByTag = HashMap<String, CEnum>()
	private val mEnumValues = HashMap<String, Long>()

	/** Reads every declaration of the text; returns this parser, now filled. */
	fun parse(): CHeaderParser {
		tokenize(mText)
		mTokens += Token(TokenKind.END, "", "")
		while (peek().kind != TokenKind.END) {
			val vStart = mAt
			try {
				externalDeclaration()
			} catch (vError: Unparsed) {
				mAt = vStart
				skipDeclaration()
			}
			if (mAt == vStart) mAt++
		}
		return this
	}

	// ============
	//  Tokenizer

	private fun tokenize(inText: String) {
		var vFile = ""
		for (vRawLine in inText.lineSequence()) {
			val vLine = vRawLine.trimStart()
			if (vLine.startsWith("#")) {
				val vDirective = vLine.drop(1).trimStart()
				val vMarker = Regex("^(?:line\\s+)?\\d+\\s+\"((?:[^\"\\\\]|\\\\.)*)\"").find(vDirective)
				if (vMarker != null) {
					vFile = vMarker.groupValues[1].replace("\\\\", "/").replace('\\', '/')
				} else if (vDirective.startsWith("define")) {
					val vMatch = Regex("^define\\s+([A-Za-z_][A-Za-z_0-9]*)(\\(?)(.*)$").find(vDirective)
					// A name followed at once by '(' is a function-like macro: not a constant.
					if (vMatch != null && vMatch.groupValues[2].isEmpty()) macros += CMacro(vMatch.groupValues[1], vMatch.groupValues[3].trim(), vFile)
				}
				continue
			}
			tokenizeLine(vLine, vFile)
		}
	}

	private fun tokenizeLine(inLine: String, inFile: String) {
		var vIndex = 0
		while (vIndex < inLine.length) {
			val vChar = inLine[vIndex]
			when {
				vChar.isWhitespace() -> vIndex++
				vChar.isLetter() || vChar == '_' || vChar == '$' -> {
					val vStart = vIndex
					while (vIndex < inLine.length && (inLine[vIndex].isLetterOrDigit() || inLine[vIndex] == '_' || inLine[vIndex] == '$')) vIndex++
					val vWord = inLine.substring(vStart, vIndex)
					// A prefixed literal (L"..", u8'..') is one token with its quotes.
					if (vIndex < inLine.length && (inLine[vIndex] == '"' || inLine[vIndex] == '\'') && vWord in setOf("L", "u", "U", "u8")) {
						vIndex = quoted(inLine, vIndex, inFile)
					} else {
						mTokens += Token(TokenKind.IDENT, vWord, inFile)
					}
				}
				vChar.isDigit() || (vChar == '.' && vIndex + 1 < inLine.length && inLine[vIndex + 1].isDigit()) -> {
					val vStart = vIndex
					while (vIndex < inLine.length) {
						val vNext = inLine[vIndex]
						// A sign belongs to the number only right after its exponent letter (e, or p in hexadecimal).
						val vPrevious = if (vIndex > vStart) inLine[vIndex - 1].lowercaseChar() else ' '
						val vIsHex = inLine.startsWith("0x", vStart, ignoreCase = true)
						val vIsExponentSign = (vNext == '+' || vNext == '-') && ((vPrevious == 'e' && !vIsHex) || (vPrevious == 'p' && vIsHex))
						if (vNext.isLetterOrDigit() || vNext == '.' || vNext == '_' || vIsExponentSign) vIndex++ else break
					}
					mTokens += Token(TokenKind.NUMBER, inLine.substring(vStart, vIndex), inFile)
				}
				vChar == '"' || vChar == '\'' -> vIndex = quoted(inLine, vIndex, inFile)
				else -> {
					val vPunct = kPunctuation.firstOrNull { inLine.startsWith(it, vIndex) } ?: vChar.toString()
					mTokens += Token(TokenKind.PUNCT, vPunct, inFile)
					vIndex += vPunct.length
				}
			}
		}
	}

	/** Reads the string or character literal starting at [inStart]; returns the index after it. */
	private fun quoted(inLine: String, inStart: Int, inFile: String): Int {
		val vQuote = inLine[inStart]
		var vIndex = inStart + 1
		while (vIndex < inLine.length && inLine[vIndex] != vQuote) vIndex += if (inLine[vIndex] == '\\') 2 else 1
		val vEnd = minOf(vIndex + 1, inLine.length)
		mTokens += Token(if (vQuote == '"') TokenKind.STRING else TokenKind.CHAR, inLine.substring(inStart, vEnd), inFile)
		return vEnd
	}

	// ============
	//  Token access

	private fun peek(inAhead: Int = 0): Token = mTokens[minOf(mAt + inAhead, mTokens.size - 1)]

	private fun next(): Token = mTokens[mAt].also { if (it.kind != TokenKind.END) mAt++ }

	private fun isPunct(inText: String, inAhead: Int = 0): Boolean = peek(inAhead).let { it.kind == TokenKind.PUNCT && it.text == inText }

	private fun isWord(inText: String, inAhead: Int = 0): Boolean = peek(inAhead).let { it.kind == TokenKind.IDENT && it.text == inText }

	private fun accept(inText: String): Boolean = if (isPunct(inText)) { mAt++; true } else false

	private fun expect(inText: String) {
		if (!accept(inText)) throw Unparsed("expected '$inText' but found '${peek().text}'")
	}

	/** Skips a bracketed group starting at the current token, which must be [inOpen]. */
	private fun skipGroup(inOpen: String, inClose: String) {
		expect(inOpen)
		var vDepth = 1
		while (vDepth > 0 && peek().kind != TokenKind.END) {
			val vToken = next()
			if (vToken.kind == TokenKind.PUNCT && vToken.text == inOpen) vDepth++
			if (vToken.kind == TokenKind.PUNCT && vToken.text == inClose) vDepth--
		}
	}

	/** Skips to the end of the declaration the parser gave up on: its `;`, or the body of a definition. */
	private fun skipDeclaration() {
		var vDepth = 0
		while (peek().kind != TokenKind.END) {
			val vToken = next()
			if (vToken.kind != TokenKind.PUNCT) continue
			when (vToken.text) {
				"(", "[", "{" -> vDepth++
				")", "]" -> vDepth--
				"}" -> {
					vDepth--
					// The end of a function body; a struct body is followed by declarators or ';'.
					if (vDepth <= 0 && !isPunct(";") && peek().kind != TokenKind.IDENT && !isPunct("*")) return
				}
				";" -> if (vDepth <= 0) return
			}
		}
	}

	// ============
	//  Specifiers

	private class Specifiers(val type: CType, val isTypedef: Boolean, val isConst: Boolean, val isExtern: Boolean)

	private val mStorageWords = setOf(
		"extern", "static", "inline", "__inline", "__inline__", "_Noreturn", "register", "auto", "__thread", "_Thread_local",
		"volatile", "__volatile__", "restrict", "__restrict", "__restrict__", "_Atomic", "__extension__", "__cdecl", "__stdcall",
		"__fastcall", "__ptr32", "__ptr64", "__unaligned", "__w64", "__signed", "__forceinline", "_Nonnull", "_Nullable", "_Null_unspecified",
	)
	private val mTypeWords = setOf(
		"void", "char", "short", "int", "long", "float", "double", "signed", "unsigned", "_Bool", "bool", "__int8", "__int16", "__int32",
		"__int64", "__int128", "__signed__", "_Complex", "__complex__", "_Float16", "_Float32", "_Float64", "_Float128", "__float128",
	)

	/** Skips attributes, calling conventions and the other words that say nothing about the type. */
	private fun skipNoise(): Boolean {
		var vAny = false
		while (true) {
			val vToken = peek()
			if (vToken.kind != TokenKind.IDENT) break
			when (vToken.text) {
				"__attribute__", "__attribute", "__declspec", "_Alignas", "__alignas", "__asm__", "__asm", "asm" -> {
					mAt++
					if (isPunct("(")) skipGroup("(", ")")
				}
				in mStorageWords, "const", "__const", "__const__" -> mAt++
				else -> break
			}
			vAny = true
		}
		return vAny
	}

	private fun specifiers(): Specifiers {
		var vIsTypedef = false
		var vIsConst = false
		var vIsExtern = false
		var vBase: CType? = null
		val vWords = ArrayList<String>()
		while (true) {
			val vToken = peek()
			if (vToken.kind != TokenKind.IDENT) break
			val vText = vToken.text
			when {
				vText == "typedef" -> { vIsTypedef = true; mAt++ }
				vText == "const" || vText == "__const" || vText == "__const__" -> { vIsConst = true; mAt++ }
				vText == "__attribute__" || vText == "__attribute" || vText == "__declspec" || vText == "_Alignas" || vText == "__alignas" -> {
					mAt++
					if (isPunct("(")) skipGroup("(", ")")
				}
				vText in mStorageWords -> {
					if (vText == "extern") vIsExtern = true
					mAt++
				}
				vText == "struct" || vText == "union" -> { mAt++; vBase = record(vText == "union") }
				vText == "enum" -> { mAt++; vBase = enumeration() }
				vText == "__typeof__" || vText == "typeof" || vText == "__typeof" || vText == "_Atomic" -> throw Unparsed(vText)
				vText in mTypeWords -> { vWords += vText; mAt++ }
				vText == "__builtin_va_list" || vText == "__gnuc_va_list" && vText !in mTypedefs -> { vBase = CUnsupported("va_list"); mAt++ }
				vBase == null && vWords.isEmpty() && vText in mTypedefs -> { vBase = CTypedefRef(vText, mTypedefs.getValue(vText)); mAt++ }
				else -> break
			}
		}
		val vType = vBase ?: if (vWords.isEmpty()) throw Unparsed("no type before '${peek().text}'") else arithmetic(vWords)
		return Specifiers(vType, vIsTypedef, vIsConst, vIsExtern)
	}

	private fun arithmetic(inWords: List<String>): CType {
		val vIsUnsigned = "unsigned" in inWords
		val vLongs = inWords.count { it == "long" }
		fun integer(inSigned: String): CType = if (vIsUnsigned) CPrim("U$inSigned", "U${inSigned}Var") else CPrim(inSigned, "${inSigned}Var")
		return when {
			inWords.any { it == "_Complex" || it == "__complex__" || it == "__int128" || it.startsWith("_Float") || it == "__float128" } -> CUnsupported(inWords.joinToString(" "))
			"void" in inWords -> CVoid
			"_Bool" in inWords || "bool" in inWords -> CPrim("Boolean", "BooleanVar")
			"char" in inWords || "__int8" in inWords -> integer("Byte")
			"short" in inWords || "__int16" in inWords -> integer("Short")
			"float" in inWords -> CPrim("Float", "FloatVar")
			"double" in inWords -> if (vLongs > 0) CUnsupported("long double") else CPrim("Double", "DoubleVar")
			"__int64" in inWords || vLongs >= 2 -> integer("Long")
			vLongs == 1 -> integer(if (mLongSize == 8) "Long" else "Int")
			else -> integer("Int")
		}
	}

	// ============
	//  Structs and enums

	private fun record(inIsUnion: Boolean): CRecord {
		skipNoise()
		val vTag = if (peek().kind == TokenKind.IDENT) next().text else null
		skipNoise()
		val vKey = (if (inIsUnion) "union " else "struct ") + vTag
		val vRecord = (if (vTag != null) mRecordsByTag[vKey] else null) ?: CRecord(inIsUnion, vTag, peek().file).also {
			if (vTag != null) mRecordsByTag[vKey] = it
			records += it
		}
		if (!isPunct("{")) return vRecord
		val vBodyStart = mAt
		try {
			expect("{")
			val vFields = ArrayList<CField>()
			while (!isPunct("}")) {
				if (peek().kind == TokenKind.END) throw Unparsed("unterminated struct")
				if (accept(";")) continue
				if (isWord("_Static_assert") || isWord("static_assert")) {
					mAt++
					skipGroup("(", ")")
					continue
				}
				val vSpecifiers = specifiers()
				if (isPunct(";")) {
					// An unnamed struct or union member: its fields are reached through the outer one.
					(vSpecifiers.type as? CRecord)?.takeIf { it.tag == null }?.fields?.let { vFields += it }
					mAt++
					continue
				}
				while (true) {
					if (isPunct(":")) {
						// An unnamed bit field only pads.
						mAt++
						constantExpression()
					} else {
						val (vName, vType) = declarator()(vSpecifiers.type, vSpecifiers.isConst)
						if (accept(":")) constantExpression()
						skipNoise()
						if (vName != null) vFields += CField(vName, vType)
					}
					if (!accept(",")) break
				}
				expect(";")
			}
			expect("}")
			vRecord.fields = vFields
		} catch (vError: Unparsed) {
			// The type stays usable by name; only its fields are unknown.
			mAt = vBodyStart
			skipGroup("{", "}")
		}
		skipNoise()
		return vRecord
	}

	private fun enumeration(): CEnum {
		skipNoise()
		val vTag = if (peek().kind == TokenKind.IDENT) next().text else null
		val vEnum = (if (vTag != null) mEnumsByTag[vTag] else null) ?: CEnum(vTag, peek().file).also {
			if (vTag != null) mEnumsByTag[vTag] = it
			enums += it
		}
		// A fixed underlying type (`enum x : int`) changes nothing here.
		if (accept(":")) specifiers()
		if (!accept("{")) return vEnum
		var vNextValue: Long? = 0L
		while (!isPunct("}")) {
			if (peek().kind != TokenKind.IDENT) throw Unparsed("enum constant expected")
			val vName = next().text
			skipNoise()
			if (accept("=")) {
				vEnum.hasExplicitValues = true
				val vStart = mAt
				vNextValue = try {
					constantExpression()
				} catch (vError: Unparsed) {
					mAt = vStart
					null
				}
				// Whatever was not understood is skipped up to the next constant.
				var vDepth = 0
				while (!(vDepth == 0 && (isPunct(",") || isPunct("}"))) && peek().kind != TokenKind.END) {
					if (isPunct("(")) vDepth++
					if (isPunct(")")) vDepth--
					mAt++
				}
			}
			vEnum.constants += vName to vNextValue
			vNextValue?.let { mEnumValues[vName] = it }
			vNextValue = vNextValue?.plus(1)
			if (!accept(",")) break
		}
		expect("}")
		skipNoise()
		return vEnum
	}

	// ============
	//  Declarators

	/** True when the '(' at the current token opens a nested declarator rather than a parameter list. */
	private fun opensNestedDeclarator(): Boolean {
		val vAfter = peek(1)
		if (vAfter.kind == TokenKind.PUNCT) return vAfter.text == "*" || vAfter.text == "(" || vAfter.text == "^"
		if (vAfter.kind != TokenKind.IDENT) return false
		val vText = vAfter.text
		if (vText == "__attribute__" || vText == "__cdecl" || vText == "__stdcall" || vText == "__fastcall" || vText == "__declspec") return true
		return vText !in mTypeWords && vText !in mTypedefs && vText !in mStorageWords && vText != "struct" && vText != "union" && vText != "enum" && vText != "const"
	}

	/**
	 * Parses a declarator - named or abstract - and returns the function that, given the type
	 * the specifiers name, yields the declared name and its full type.
	 */
	private fun declarator(): (CType, Boolean) -> Pair<String?, CType> {
		var vPointers = 0
		while (true) {
			skipNoise()
			if (accept("*") || accept("^")) vPointers++ else break
		}
		skipNoise()
		var vInner: ((CType, Boolean) -> Pair<String?, CType>)? = null
		var vName: String? = null
		if (isPunct("(") && opensNestedDeclarator()) {
			mAt++
			vInner = declarator()
			expect(")")
		} else if (peek().kind == TokenKind.IDENT) {
			vName = next().text
		}
		val vSuffixes = ArrayList<(CType) -> CType>()
		while (true) {
			if (isPunct("[")) {
				skipGroup("[", "]")
				vSuffixes += { CArrayOf(it) }
			} else if (isPunct("(")) {
				val (vParams, vIsVariadic) = parameters()
				vSuffixes += { CFunctionType(it, vParams, vIsVariadic) }
			} else {
				break
			}
		}
		skipNoise()
		return { vBase, vIsConst ->
			var vType = vBase
			var vConst = vIsConst
			repeat(vPointers) {
				vType = CPointerTo(vType, vConst)
				vConst = false
			}
			for (vSuffix in vSuffixes.reversed()) vType = vSuffix(vType)
			if (vInner != null) vInner(vType, false) else vName to vType
		}
	}

	private fun parameters(): Pair<List<CParam>, Boolean> {
		expect("(")
		val vParams = ArrayList<CParam>()
		var vIsVariadic = false
		if (accept(")")) return vParams to false
		if (isWord("void") && isPunct(")", 1)) {
			mAt += 2
			return vParams to false
		}
		while (true) {
			if (accept("...")) {
				vIsVariadic = true
				break
			}
			val vSpecifiers = specifiers()
			val (vName, vType) = declarator()(vSpecifiers.type, vSpecifiers.isConst)
			// Arrays and functions are passed as pointers.
			val vPassed = when (vType) {
				is CArrayOf -> CPointerTo(vType.element, vSpecifiers.isConst)
				is CFunctionType -> CPointerTo(vType, false)
				else -> vType
			}
			vParams += CParam(vName, vPassed)
			if (!accept(",")) break
		}
		expect(")")
		return vParams to vIsVariadic
	}

	// ============
	//  Declarations

	private fun externalDeclaration() {
		if (accept(";")) return
		if (isWord("_Static_assert") || isWord("static_assert") || isWord("__asm__") || isWord("asm")) {
			mAt++
			skipGroup("(", ")")
			return
		}
		val vFile = peek().file
		val vSpecifiers = specifiers()
		if (accept(";")) return
		while (true) {
			val (vName, vType) = declarator()(vSpecifiers.type, vSpecifiers.isConst)
			if (vName == null) throw Unparsed("declaration without a name")
			if (vSpecifiers.isTypedef) {
				mTypedefs[vName] = vType
				typedefs += CTypedefDecl(vName, vType, vFile)
				// A struct or enum without a tag takes the name of its typedef. Of two names the one a program
				// is meant to write wins over a private one (`FILE` over `__FILE`, which the GNU C library declares first).
				fun isBetter(inKnown: String?): Boolean = inKnown == null || (inKnown.startsWith("_") && !vName.startsWith("_"))
				(vType as? CRecord)?.let { if (isBetter(it.typedefName)) it.typedefName = vName }
				(vType as? CEnum)?.let { if (isBetter(it.typedefName)) it.typedefName = vName }
			} else if (vType is CFunctionType) {
				functions += CFunctionDecl(vName, vType, vFile)
			} else if (vSpecifiers.isExtern) {
				// For a pointer the `const` in front belongs to what it points at, not to the variable.
				variables += CVariableDecl(vName, vType, vSpecifiers.isConst && vType !is CPointerTo, vFile)
			}
			if (isPunct("{")) {
				// A definition: the body is of no interest.
				skipGroup("{", "}")
				return
			}
			if (accept("=")) {
				var vDepth = 0
				while (peek().kind != TokenKind.END && !(vDepth == 0 && (isPunct(",") || isPunct(";")))) {
					if (isPunct("(") || isPunct("{") || isPunct("[")) vDepth++
					if (isPunct(")") || isPunct("}") || isPunct("]")) vDepth--
					mAt++
				}
			}
			if (!accept(",")) break
		}
		expect(";")
	}

	// ============
	//  Constant expressions

	private val mBinaryLevels = listOf(
		listOf("||"), listOf("&&"), listOf("|"), listOf("^"), listOf("&"), listOf("==", "!="),
		listOf("<", ">", "<=", ">="), listOf("<<", ">>"), listOf("+", "-"), listOf("*", "/", "%"),
	)

	/** Evaluates the integer constant expression at the current token. */
	private fun constantExpression(): Long {
		val vCondition = binary(0)
		if (!accept("?")) return vCondition
		val vThen = constantExpression()
		expect(":")
		val vElse = constantExpression()
		return if (vCondition != 0L) vThen else vElse
	}

	private fun binary(inLevel: Int): Long {
		if (inLevel == mBinaryLevels.size) return unary()
		var vLeft = binary(inLevel + 1)
		while (true) {
			val vOperator = mBinaryLevels[inLevel].firstOrNull { isPunct(it) } ?: return vLeft
			mAt++
			val vRight = binary(inLevel + 1)
			vLeft = when (vOperator) {
				"||" -> if (vLeft != 0L || vRight != 0L) 1L else 0L
				"&&" -> if (vLeft != 0L && vRight != 0L) 1L else 0L
				"|" -> vLeft or vRight
				"^" -> vLeft xor vRight
				"&" -> vLeft and vRight
				"==" -> if (vLeft == vRight) 1L else 0L
				"!=" -> if (vLeft != vRight) 1L else 0L
				"<" -> if (vLeft < vRight) 1L else 0L
				">" -> if (vLeft > vRight) 1L else 0L
				"<=" -> if (vLeft <= vRight) 1L else 0L
				">=" -> if (vLeft >= vRight) 1L else 0L
				"<<" -> vLeft shl vRight.toInt()
				">>" -> vLeft shr vRight.toInt()
				"+" -> vLeft + vRight
				"-" -> vLeft - vRight
				"*" -> vLeft * vRight
				"/" -> if (vRight == 0L) throw Unparsed("division by zero") else vLeft / vRight
				else -> if (vRight == 0L) throw Unparsed("division by zero") else vLeft % vRight
			}
		}
	}

	private fun unary(): Long {
		if (accept("-")) return -unary()
		if (accept("+")) return unary()
		if (accept("~")) return unary().inv()
		if (accept("!")) return if (unary() == 0L) 1L else 0L
		if (isPunct("(")) {
			val vAfter = peek(1)
			val vIsCast = vAfter.kind == TokenKind.IDENT && (vAfter.text in mTypeWords || vAfter.text in mTypedefs || vAfter.text == "const")
			if (vIsCast) {
				// The cast is taken to keep the value.
				skipGroup("(", ")")
				return unary()
			}
			mAt++
			val vValue = constantExpression()
			expect(")")
			return vValue
		}
		val vToken = next()
		return when (vToken.kind) {
			TokenKind.NUMBER -> integerLiteral(vToken.text)?.first ?: throw Unparsed("not an integer: ${vToken.text}")
			TokenKind.CHAR -> charLiteral(vToken.text) ?: throw Unparsed("character literal ${vToken.text}")
			TokenKind.IDENT -> mEnumValues[vToken.text] ?: throw Unparsed("not a constant: ${vToken.text}")
			else -> throw Unparsed("not a constant: ${vToken.text}")
		}
	}

	/** The value of an object-like macro whose body is an integer constant expression, with whether it is unsigned. */
	fun macroInteger(inBody: String): Pair<Long, Boolean>? {
		val vSaved = ArrayList(mTokens)
		val vSavedAt = mAt
		mTokens.clear()
		tokenizeLine(inBody, "")
		val vIsUnsigned = mTokens.any { it.kind == TokenKind.NUMBER && integerLiteral(it.text)?.second == true }
		mTokens += Token(TokenKind.END, "", "")
		mAt = 0
		val vValue = try {
			constantExpression().takeIf { peek().kind == TokenKind.END }
		} catch (vError: Unparsed) {
			null
		}
		mTokens.clear()
		mTokens += vSaved
		mAt = vSavedAt
		return vValue?.let { it to vIsUnsigned }
	}

	companion object {
		/** The value of a C integer literal and whether it is written unsigned; null for anything else. */
		fun integerLiteral(inText: String): Pair<Long, Boolean>? {
			val vDigits = inText.trimEnd('u', 'U', 'l', 'L')
			val vSuffix = inText.drop(vDigits.length).lowercase()
			if (vSuffix.any { it != 'u' && it != 'l' }) return null
			val vValue = when {
				vDigits.startsWith("0x", ignoreCase = true) -> vDigits.drop(2).toULongOrNull(16)
				vDigits.startsWith("0b", ignoreCase = true) -> vDigits.drop(2).toULongOrNull(2)
				vDigits.length > 1 && vDigits.startsWith("0") -> vDigits.drop(1).toULongOrNull(8)
				else -> vDigits.toULongOrNull()
			} ?: return null
			return vValue.toLong() to ('u' in vSuffix)
		}

		/** The code of a plain character literal (`'a'`, `'\n'`, `'\x41'`). */
		fun charLiteral(inText: String): Long? {
			val vBody = inText.substringAfter('\'').substringBeforeLast('\'')
			if (vBody.length == 1) return vBody[0].code.toLong()
			if (!vBody.startsWith("\\")) return null
			return when (vBody[1]) {
				'n' -> 10L
				't' -> 9L
				'r' -> 13L
				'0' -> if (vBody.length == 2) 0L else vBody.drop(1).toLongOrNull(8)
				'\\' -> 92L
				'\'' -> 39L
				'"' -> 34L
				'a' -> 7L
				'b' -> 8L
				'f' -> 12L
				'v' -> 11L
				'x' -> vBody.drop(2).toLongOrNull(16)
				else -> vBody.drop(1).toLongOrNull(8)
			}
		}
	}
}
