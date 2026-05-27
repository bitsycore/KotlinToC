package com.bitsycore.ktc.ast

enum class TokenType {
    // Literals
    INT_LIT, LONG_LIT, UINT_LIT, ULONG_LIT, FLOAT_LIT, DOUBLE_LIT,
    STRING_LIT, CHAR_LIT,

    // String template tokens (for strings containing $ref or ${expr})
    STR_TMPL_START, STR_TMPL_PART, STR_TMPL_END,
    TMPL_REF, TMPL_EXPR_START, TMPL_EXPR_END,

    // Keywords
    PACKAGE, IMPORT,
    FUN, VAL, VAR,
    CLASS, DATA, OBJECT, ENUM,
    IF, ELSE, WHEN,
    FOR, WHILE, DO,
    RETURN, BREAK, CONTINUE, DEFER,
    IN, IS, AS,
    NULL, TRUE, FALSE,
    THIS, INIT, INTERFACE, OVERRIDE, PRIVATE, SET, GET,
    TYPEALIAS, TAILREC, SEALED,

    // Identifier
    IDENT,

    // Operators
    PLUS, MINUS, STAR, SLASH, PERCENT,
    PLUS_PLUS, MINUS_MINUS,
    EQ_EQ, REF_EQ, EXCL_EQ, LT, GT, LT_EQ, GT_EQ,
    AMP_AMP, PIPE_PIPE, EXCL,
    EQ, PLUS_EQ, MINUS_EQ, STAR_EQ, SLASH_EQ, PERCENT_EQ,
    DOT, QUESTION_DOT, QUESTION, EXCL_EXCL,
    QUESTION_COLON,  // ?:
    DOT_DOT,         // ..
    ARROW,           // ->

    // Delimiters
    LPAREN, RPAREN,
    LBRACE, RBRACE,
    LBRACKET, RBRACKET,
    COMMA, COLON, COLON_COLON, SEMICOLON,

    // Annotations
    AT,

    // Special
    NEWLINE, EOF, COMMENT
}

data class Token(
    val type: TokenType,
    val value: String,
    val line: Int,
    val col: Int
) {
    override fun toString(): String = "$type '$value' @ $line:$col"
}

val KEYWORDS: Map<String, TokenType> = mapOf(
    "package"  to TokenType.PACKAGE,
    "import"   to TokenType.IMPORT,
    "fun"      to TokenType.FUN,
    "val"      to TokenType.VAL,
    "var"      to TokenType.VAR,
    "class"    to TokenType.CLASS,
    "data"     to TokenType.DATA,
    "object"   to TokenType.OBJECT,
    "enum"     to TokenType.ENUM,
    "if"       to TokenType.IF,
    "else"     to TokenType.ELSE,
    "when"     to TokenType.WHEN,
    "for"      to TokenType.FOR,
    "while"    to TokenType.WHILE,
    "do"       to TokenType.DO,
    "return"   to TokenType.RETURN,
    "break"    to TokenType.BREAK,
    "continue" to TokenType.CONTINUE,
    "defer"    to TokenType.DEFER,
    "in"       to TokenType.IN,
    "is"       to TokenType.IS,
    "as"       to TokenType.AS,
    "null"     to TokenType.NULL,
    "true"     to TokenType.TRUE,
    "false"    to TokenType.FALSE,
    "this"     to TokenType.THIS,
    "init"     to TokenType.INIT,
    "interface" to TokenType.INTERFACE,
    "override" to TokenType.OVERRIDE,
    "private"  to TokenType.PRIVATE,
    "typealias" to TokenType.TYPEALIAS,
    "tailrec"   to TokenType.TAILREC,
    "sealed"    to TokenType.SEALED,
)
