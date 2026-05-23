package com.bitsycore.ktc.parser

import com.bitsycore.ktc.ast.KEYWORDS
import com.bitsycore.ktc.ast.Token
import com.bitsycore.ktc.ast.TokenType

class Lexer(private val src: String) {
    private var pos = 0
    private var line = 1
    private var col = 1
    private val tokens = mutableListOf<Token>()

    // ── Mode stack for string template handling ──────────────────────
    private enum class Mode { NORMAL, STRING, RAW_STRING, TMPL_EXPR }
    private val modes = ArrayDeque<Mode>().apply { addLast(Mode.NORMAL) }
    private var tmplBraceDepth = 0
    private var inTemplateLiteral = false   // has current string emitted STR_TMPL_START?

    // ── Public entry point ───────────────────────────────────────────

    fun tokenize(): List<Token> {
        while (pos < src.length) {
            when (modes.last()) {
                Mode.NORMAL     -> lexNormal()
                Mode.STRING     -> lexStringContent()
                Mode.RAW_STRING -> lexRawStringContent()
                Mode.TMPL_EXPR  -> lexTemplateExpr()
            }
        }
        emit(TokenType.EOF, "")
        return tokens
    }

    // ── Normal mode ──────────────────────────────────────────────────

    private fun lexNormal() {
        skipSpaces()
        if (pos >= src.length) return
        val c = cur()
        when {
            c == '\n' || c == '\r' -> lexNewline()
            c == '/' && pos + 1 < src.length && src[pos + 1] == '/' -> lexLineComment()
            c == '/' && pos + 1 < src.length && src[pos + 1] == '*' -> lexBlockComment()
            c == '"'  -> startString()
            c == '\'' -> lexChar()
            c.isDigit() -> lexNumber()
            c.isLetter() || c == '_' -> lexIdent()
            else -> lexPunct()
        }
    }

    // ── Template expression mode (same as normal but tracks } depth) ─

    private fun lexTemplateExpr() {
        skipSpaces()
        if (pos >= src.length) return
        val c = cur()
        if (c == '}' && tmplBraceDepth == 0) {
            advance()
            emit(TokenType.TMPL_EXPR_END, "}")
            modes.removeLast()          // back to STRING
            return
        }
        if (c == '{') tmplBraceDepth++
        if (c == '}') tmplBraceDepth--
        lexNormal()
    }

    // ── String content mode ──────────────────────────────────────────

    private fun startString() {
        // Check for triple-quoted raw string: """
        if (pos + 2 < src.length && src[pos] == '"' && src[pos + 1] == '"' && src[pos + 2] == '"') {
            advance(); advance(); advance() // skip opening """
            modes.addLast(Mode.RAW_STRING)
            return
        }
        advance()                       // skip opening "
        inTemplateLiteral = false
        modes.addLast(Mode.STRING)
    }

    private fun lexStringContent() {
        val sb = StringBuilder()
        while (pos < src.length) {
            when (val c = cur()) {
                '"' -> {
                    advance()
                    if (inTemplateLiteral) {
                        if (sb.isNotEmpty()) emit(TokenType.STR_TMPL_PART, sb.toString())
                        emit(TokenType.STR_TMPL_END, "")
                    } else {
                        emit(TokenType.STRING_LIT, sb.toString())
                    }
                    modes.removeLast()
                    return
                }
                '\\' -> sb.append(readEscape())
                '$' if pos + 1 < src.length && (src[pos + 1].isLetterOrDigitOrUnderscore()) -> {
                    ensureTmplStarted(sb)
                    advance()                   // skip $
                    val name = readIdentRaw()
                    emit(TokenType.TMPL_REF, name)
                }
                '$' if pos + 1 < src.length && src[pos + 1] == '{' -> {
                    ensureTmplStarted(sb)
                    advance(); advance()        // skip ${
                    emit(TokenType.TMPL_EXPR_START, "\${")
                    tmplBraceDepth = 0
                    modes.addLast(Mode.TMPL_EXPR)
                    return                      // hand control to lexTemplateExpr
                }
                '\n' -> {
                    sb.append(c); advance(); line++; col = 1
                }
                else -> { sb.append(c); advance() }
            }
        }
        error("Unterminated string at line $line")
    }

    // ── Raw string content ("""...""") ────────────────────────────────

    private fun lexRawStringContent() {
        val sb = StringBuilder()
        while (pos < src.length) {
            // Check for closing """
            if (cur() == '"' && pos + 1 < src.length && src[pos + 1] == '"' && pos + 2 < src.length && src[pos + 2] == '"') {
                advance(); advance(); advance() // skip """
                emit(TokenType.STRING_LIT, sb.toString())
                modes.removeLast()
                return
            }
            val c = cur()
            if (c == '\n') { line++; col = 1 }
            sb.append(c); advance()
        }
        error("Unterminated raw string at line $line")
    }

    private fun ensureTmplStarted(sb: StringBuilder) {
        if (!inTemplateLiteral) {
            inTemplateLiteral = true
            emit(TokenType.STR_TMPL_START, "")
        }
        if (sb.isNotEmpty()) {
            emit(TokenType.STR_TMPL_PART, sb.toString())
            sb.clear()
        }
    }

    // ── Character literal ────────────────────────────────────────────

    private fun lexChar() {
        advance()                           // skip opening '
        val ch = if (cur() == '\\') readEscape() else { val c = cur(); advance(); c }
        if (pos < src.length && cur() == '\'') advance() else error("Unterminated char at line $line")
        if (ch.code > 0xFF) error("Multi-byte character '$ch' not supported in C (char is 8-bit) at line $line")
        emit(TokenType.CHAR_LIT, ch.toString())
    }

    // ── Number literal ───────────────────────────────────────────────

    private fun lexNumber() {
        val start = pos
        var isHex = false
        var isBin = false
        var isLong = false
        var isFloat = false
        var isDouble = false
        var isUnsigned = false

        // Check for 0x / 0X hex prefix
        if (pos + 1 < src.length && cur() == '0' && (src[pos + 1] == 'x' || src[pos + 1] == 'X')) {
            advance(); advance() // skip 0x
            isHex = true
        } else if (pos + 1 < src.length && cur() == '0' && (src[pos + 1] == 'b' || src[pos + 1] == 'B')) {
            advance(); advance() // skip 0b
            isBin = true
        }

        // Read digits (hex, binary, or decimal)
        if (isHex) {
            while (pos < src.length) {
                val c = cur()
                if (c == '_') { advance(); continue }
                if (c.isDigit() || c in 'a'..'f' || c in 'A'..'F') { advance(); continue }
                break
            }
        } else if (isBin) {
            while (pos < src.length) {
                val c = cur()
                if (c == '_') { advance(); continue }
                if (c == '0' || c == '1') { advance(); continue }
                break
            }
        } else {
            while (pos < src.length && (cur().isDigit() || cur() == '_')) advance()
            if (pos < src.length && cur() == '.' && pos + 1 < src.length && src[pos + 1].isDigit()) {
                advance()
                while (pos < src.length && (cur().isDigit() || cur() == '_')) advance()
                isDouble = true
            }
            if (pos < src.length && (cur() == 'e' || cur() == 'E')) {
                advance()
                if (pos < src.length && (cur() == '+' || cur() == '-')) advance()
                while (pos < src.length && cur().isDigit()) advance()
                isDouble = true
            }
        }

        // Suffixes: u/U and L only for integers; f/F can override double
        if (!isDouble && pos < src.length && (cur() == 'u' || cur() == 'U')) { isUnsigned = true; advance() }
        if (!isDouble && pos < src.length && (cur() == 'L')) { isLong = true; advance() }
        if (!isUnsigned && !isDouble && pos < src.length && (cur() == 'u' || cur() == 'U')) { isUnsigned = true; advance() }
        if (!isUnsigned && pos < src.length && (cur() == 'f' || cur() == 'F')) { isFloat = true; isDouble = false; advance() }

        var raw = src.substring(start, pos).replace("_", "")
        if (isBin) {
            // Strip 0b/0B prefix and all suffix chars (u/U/L), then parse as binary
            val digits = raw.removePrefix("0b").removePrefix("0B")
                .replace("u", "").replace("U", "").replace("L", "")
            val longVal = digits.toLong(2)
            val suffix = when {
                isUnsigned && isLong -> "UL"
                isUnsigned -> "u"
                isLong -> "L"
                else -> ""
            }
            raw = "$longVal$suffix"
        }
        // hex: keep as-is (C supports 0x natively), just normalize suffix casing
        if (isHex && isUnsigned) {
            raw = raw.removeSuffix("u").removeSuffix("U")
            if (isLong) raw = raw.removeSuffix("L")
            raw += if (isLong) "UL" else "U"
        }

        val type = when {
            isFloat    -> TokenType.FLOAT_LIT
            isDouble   -> TokenType.DOUBLE_LIT
            isUnsigned && isLong -> TokenType.ULONG_LIT
            isUnsigned -> TokenType.UINT_LIT
            isLong     -> TokenType.LONG_LIT
            else       -> TokenType.INT_LIT
        }
        emit(type, raw)
    }

    // ── Identifier / keyword ─────────────────────────────────────────

    private fun lexIdent() {
        val name = readIdentRaw()
        val type = KEYWORDS[name] ?: TokenType.IDENT
        emit(type, name)
    }

    private fun readIdentRaw(): String {
        val start = pos
        while (pos < src.length && (cur().isLetterOrDigit() || cur() == '_')) advance()
        return src.substring(start, pos)
    }

    // ── Punctuation / operators ──────────────────────────────────────

    private fun lexPunct() {
        val c = cur()
        val nc = if (pos + 1 < src.length) src[pos + 1] else '\u0000'
        val nnc = if (pos + 2 < src.length) src[pos + 2] else '\u0000'
        when (c) {
            '(' -> { advance(); emit(TokenType.LPAREN, "(") }
            ')' -> { advance(); emit(TokenType.RPAREN, ")") }
            '{' -> { advance(); emit(TokenType.LBRACE, "{") }
            '}' -> { advance(); emit(TokenType.RBRACE, "}") }
            '[' -> { advance(); emit(TokenType.LBRACKET, "[") }
            ']' -> { advance(); emit(TokenType.RBRACKET, "]") }
            ',' -> { advance(); emit(TokenType.COMMA, ",") }
            ':' if nc == ':' -> { advance(); advance(); emit(TokenType.COLON_COLON, "::") }
            ':' -> { advance(); emit(TokenType.COLON, ":") }
            ';' -> { advance(); emit(TokenType.SEMICOLON, ";") }
            '.' if nc == '.' && nnc == '<' -> { advance(); advance(); advance(); emit(TokenType.DOT_DOT, "..<") }
            '.' if nc == '.' -> { advance(); advance(); emit(TokenType.DOT_DOT, "..") }
            '.' -> { advance(); emit(TokenType.DOT, ".") }
            '+' if nc == '+' -> { advance(); advance(); emit(TokenType.PLUS_PLUS, "++") }
            '+' if nc == '=' -> { advance(); advance(); emit(TokenType.PLUS_EQ, "+=") }
            '+' -> { advance(); emit(TokenType.PLUS, "+") }
            '-' if nc == '-' -> { advance(); advance(); emit(TokenType.MINUS_MINUS, "--") }
            '-' if nc == '>' -> { advance(); advance(); emit(TokenType.ARROW, "->") }
            '-' if nc == '=' -> { advance(); advance(); emit(TokenType.MINUS_EQ, "-=") }
            '-' -> { advance(); emit(TokenType.MINUS, "-") }
            '*' if nc == '=' -> { advance(); advance(); emit(TokenType.STAR_EQ, "*=") }
            '*' -> { advance(); emit(TokenType.STAR, "*") }
            '/' if nc == '=' -> { advance(); advance(); emit(TokenType.SLASH_EQ, "/=") }
            '/' -> { advance(); emit(TokenType.SLASH, "/") }
            '%' if nc == '=' -> { advance(); advance(); emit(TokenType.PERCENT_EQ, "%=") }
            '%' -> { advance(); emit(TokenType.PERCENT, "%") }
            '=' if nc == '=' && nnc == '=' -> { advance(); advance(); advance(); emit(TokenType.REF_EQ, "===") }
            '=' if nc == '=' -> { advance(); advance(); emit(TokenType.EQ_EQ, "==") }
            '=' -> { advance(); emit(TokenType.EQ, "=") }
            '!' if nc == '=' -> { advance(); advance(); emit(TokenType.EXCL_EQ, "!=") }
            '!' if nc == '!' -> { advance(); advance(); emit(TokenType.EXCL_EXCL, "!!") }
            '!' -> { advance(); emit(TokenType.EXCL, "!") }
            '<' if nc == '=' -> { advance(); advance(); emit(TokenType.LT_EQ, "<=") }
            '<' -> { advance(); emit(TokenType.LT, "<") }
            '>' if nc == '=' -> { advance(); advance(); emit(TokenType.GT_EQ, ">=") }
            '>' -> { advance(); emit(TokenType.GT, ">") }
            '&' if nc == '&' -> { advance(); advance(); emit(TokenType.AMP_AMP, "&&") }
            '|' if nc == '|' -> { advance(); advance(); emit(TokenType.PIPE_PIPE, "||") }
            '?' if nc == '.' -> { advance(); advance(); emit(TokenType.QUESTION_DOT, "?.") }
            '?' if nc == ':' -> { advance(); advance(); emit(TokenType.QUESTION_COLON, "?:") }
            '?' -> { advance(); emit(TokenType.QUESTION, "?") }
            '@' -> { advance(); emit(TokenType.AT, "@") }
            else -> error("Unexpected character '$c' at line $line col $col")
        }
    }

    // ── Newlines ─────────────────────────────────────────────────────

    private fun lexNewline() {
        if (cur() == '\r' && pos + 1 < src.length && src[pos + 1] == '\n') advance()
        advance()
        emit(TokenType.NEWLINE, "\\n")
        line++; col = 1
    }

    // ── Comments ─────────────────────────────────────────────────────

    private fun lexLineComment() {
        advance(); advance()               // skip //
        val start = pos
        while (pos < src.length && cur() != '\n') advance()
        val text = src.substring(start, pos).trim()
        emit(TokenType.COMMENT, "//$text")
    }

    private fun lexBlockComment() {
        advance(); advance()               // skip /*
        val start = pos
        var depth = 1
        while (pos < src.length && depth > 0) {
            if (cur() == '/' && pos + 1 < src.length && src[pos + 1] == '*') { advance(); advance(); depth++ }
            else if (cur() == '*' && pos + 1 < src.length && src[pos + 1] == '/') { advance(); advance(); depth-- }
            else { if (cur() == '\n') { line++; col = 1 }; advance() }
        }
        val end = pos - 2  // before */
        val text = src.substring(start, end).trim().replace("\n", " ")
        emit(TokenType.COMMENT, "/*$text*/")
    }

    // ── Escape sequences ─────────────────────────────────────────────

    private fun readEscape(): Char {
        advance()   // skip backslash
        val c = cur()
        if (c == 'u') {
            advance() // skip 'u'
            // Read 4 hex digits
            val hexDigits = StringBuilder()
            repeat(4) {
                if (pos >= src.length) error("Unterminated unicode escape at line $line")
                val h = cur()
                if (!h.isDigit() && h !in 'a'..'f' && h !in 'A'..'F')
                    error("Invalid unicode escape at line $line")
                hexDigits.append(h)
                advance()
            }
            return hexDigits.toString().toInt(16).toChar()
        }
        advance()
        return when (c) {
            'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'
            '\\' -> '\\'; '\'' -> '\''; '"' -> '"'; '$' -> '$'; '0' -> '\u0000'
            else -> c
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private fun cur(): Char = src[pos]
    private fun advance() { pos++; col++ }
    private fun skipSpaces() { while (pos < src.length && cur().let { it == ' ' || it == '\t' }) advance() }
    private fun emit(type: TokenType, value: String) { tokens.add(Token(type, value, line, col)) }

    private fun Char.isLetterOrDigitOrUnderscore(): Boolean = isLetter() || this == '_'
}
