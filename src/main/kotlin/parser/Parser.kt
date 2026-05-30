package com.bitsycore.ktc.parser

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.ast.Annotation

class Parser(private val tokens: List<Token>) {
    private var pos = 0
    private var nesting = 0          // depth inside () [] {}
    private var noNewlineExpr = false // when true, parseExpr stops at newlines
    private var anonObjectCounter = 0
    private val syntheticDecls = mutableListOf<Decl>()

    // ═══════════════════════════ Public entry ═════════════════════════

    fun parseFile(): KtFile {
        skipNL()
        /* Consume recognized @file: annotations at the top of the file.
        Supported:
          @file:DocumentationOnly          — no C output, decls still visible to other files
          @file:cInclude("path")           — emits #include <path> in every generated .c
          @file:cIncludeRelative("path")   — emits #include "path" in every generated .c
        Unknown @file: names stop the loop and are left for normal declaration parsing. */
        val vCIncludes = mutableListOf<CInclude>()
        while (pos + 3 < tokens.size
            && tokens[pos].type     == TokenType.AT
            && tokens[pos + 1].type == TokenType.IDENT && tokens[pos + 1].value == "file"
            && tokens[pos + 2].type == TokenType.COLON
            && tokens[pos + 3].type == TokenType.IDENT) {
            when (tokens[pos + 3].value) {
                "DocumentationOnly" -> return KtFile(null, emptyList(), emptyList(), documentationOnly = true)
                "cInclude", "cIncludeRelative" -> {
                    val vAngle = tokens[pos + 3].value == "cInclude"
                    pos += 4
                    expect(TokenType.LPAREN)
                    val vPath = expect(TokenType.STRING_LIT).value
                    expect(TokenType.RPAREN)
                    skipTerminator(); skipNL()
                    vCIncludes += CInclude(vPath, vAngle)
                }
                else -> break
            }
        }
        val pkg = if (at(TokenType.PACKAGE)) { advance(); parseQualifiedName().also { skipTerminator() } } else null
        val imports = mutableListOf<String>()
        while (at(TokenType.IMPORT)) { advance(); imports += parseQualifiedName(); skipTerminator() }
        val decls = mutableListOf<Decl>()
        while (!at(TokenType.EOF)) {
            skipNL()
            if (at(TokenType.EOF)) break
            if (at(TokenType.COMMENT)) { advance(); skipTerminator(); continue }
            decls += parseDecl()
        }
        return KtFile(pkg, imports, decls + syntheticDecls, cIncludes = vCIncludes)
    }

    // ═══════════════════════════ Declarations ═════════════════════════

    private fun parseDecl(): Decl {
        while (true) {
            skipNL()
            if (at(TokenType.COMMENT)) { advance(); skipTerminator(); continue }
            break
        }
        // track 'override', 'operator', 'infix', 'inline', 'private', 'internal' modifiers
        // Modifiers can appear in any order — Kotlin doesn't impose a canonical order
        // and users naturally write `inline operator fun` (matching the JetBrains
        // docs) as often as `operator inline fun`. Loop until we stop seeing one.
        var isOverride = false; var isOperator = false; var isInfix = false
        var isPrivate = false; var isInternal = false; var isTailrec = false
        var isInlineExplicit = false; var isValue = false; var isSealed = false
        loop@ while (true) {
            when {
                at(TokenType.OVERRIDE) -> { isOverride = true; advance() }
                at(TokenType.IDENT) && cur().value == "operator" -> { isOperator = true; advance() }
                at(TokenType.IDENT) && cur().value == "infix"    -> { isInfix    = true; advance() }
                at(TokenType.PRIVATE)  -> { isPrivate  = true; advance() }
                at(TokenType.INTERNAL) -> { isInternal = true; advance() }
                at(TokenType.TAILREC)  -> { isTailrec  = true; advance() }
                at(TokenType.IDENT) && cur().value == "inline" &&
                    peek().type in setOf(TokenType.FUN, TokenType.VAL, TokenType.VAR, TokenType.CLASS,
                                         TokenType.IDENT, TokenType.PRIVATE, TokenType.INTERNAL,
                                         TokenType.TAILREC, TokenType.OVERRIDE)
                    -> { isInlineExplicit = true; advance() }
                at(TokenType.IDENT) && cur().value == "value" && peek().type == TokenType.CLASS
                    -> { isValue = true; advance() }
                at(TokenType.SEALED) -> { isSealed = true; advance() }
                else -> break@loop
            }
        }
        if (isPrivate && isInternal) error("'private' and 'internal' are mutually exclusive on the same declaration")
        val isInline = isInlineExplicit || isInfix
        return when {
            at(TokenType.FUN)    -> parseFunDecl(isOperator = isOperator, isPrivate = isPrivate, isInternal = isInternal, isInline = isInline, isOverride = isOverride, isInfix = isInfix, isTailrec = isTailrec)
            at(TokenType.DATA)   -> { if (isPrivate) error("private with data not supported"); advance(); expect(TokenType.CLASS); parseClassDecl(isData = true, isInternal = isInternal) }
            at(TokenType.CLASS)  -> { advance(); parseClassDecl(isData = false, isValue = isValue, isSealed = isSealed, isInternal = isInternal) }
            at(TokenType.IDENT) && cur().value == "annotation" && peek().type == TokenType.CLASS -> {
                advance(); advance(); parseClassDecl(isData = false)
            }
            at(TokenType.ENUM)   -> { advance(); expect(TokenType.CLASS); parseEnumDecl() }
            at(TokenType.INTERFACE) -> parseInterfaceDecl(isSealed = isSealed)
            at(TokenType.TYPEALIAS) -> parseTypeAliasDecl()
            at(TokenType.IDENT) && cur().value == "companion" && peek().type == TokenType.OBJECT -> {
                advance()  // consume "companion"
                parseCompanionObjectDecl()
            }
            at(TokenType.OBJECT) -> parseObjectDecl()
            at(TokenType.VAL)    -> parsePropDecl(mutable = false, isPrivate = isPrivate, isInternal = isInternal, isInline = isInlineExplicit)
            at(TokenType.VAR)    -> parsePropDecl(mutable = true, isPrivate = isPrivate, isInternal = isInternal, isInline = isInlineExplicit)
            at(TokenType.AT) -> {
                val anns = parseAnnotations()
                when {
                    at(TokenType.VAL)    -> parsePropDecl(mutable = false, preAnnotations = anns, isPrivate = isPrivate, isInternal = isInternal, isInline = isInlineExplicit)
                    at(TokenType.VAR)    -> parsePropDecl(mutable = true, preAnnotations = anns, isPrivate = isPrivate, isInternal = isInternal, isInline = isInlineExplicit)
                    at(TokenType.OBJECT) -> parseObjectDecl(anns)
                    at(TokenType.IDENT) && cur().value == "companion" && peek().type == TokenType.OBJECT -> {
                        advance()
                        parseCompanionObjectDecl(anns)
                    }
                    at(TokenType.FUN) -> parseFunDecl(
                        isOperator = isOperator, isPrivate = isPrivate, isInternal = isInternal, isInline = isInline,
                        isOverride = isOverride, isInfix = isInfix, isTailrec = isTailrec, annotations = anns
                    )
                    at(TokenType.DATA) -> {
                        advance(); expect(TokenType.CLASS)
                        parseClassDecl(isData = true, annotations = anns, isInternal = isInternal)
                    }
                    at(TokenType.CLASS) -> { advance(); parseClassDecl(isData = false, annotations = anns, isValue = isValue, isInternal = isInternal) }
                    at(TokenType.IDENT) && cur().value == "value" && peek().type == TokenType.CLASS -> {
                        advance(); advance()
                        parseClassDecl(isData = false, annotations = anns, isValue = true, isInternal = isInternal)
                    }
                    at(TokenType.SEALED) -> {
                        advance()
                        if (at(TokenType.INTERFACE)) parseInterfaceDecl(isSealed = true, annotations = anns)
                        else { advance(); parseClassDecl(isData = false, annotations = anns, isSealed = true, isInternal = isInternal) }
                    }
                    at(TokenType.INTERFACE) -> parseInterfaceDecl(annotations = anns)
                    at(TokenType.ENUM) -> { advance(); expect(TokenType.CLASS); parseEnumDecl(annotations = anns) }
                    else -> error("Annotations @${anns.joinToString(" ") { it.name }} before '${cur().value}' are not supported")
                }
            }
            at(TokenType.INIT)   -> { advance(); FunDecl("init", emptyList(), null, parseBlock()) }
            else -> error("Expected declaration at ${cur()}")
        }
    }

    // ── fun ──────────────────────────────────────────────────────────

    // Parse an optional `<A, B, …>` type-parameter list, skipping variance/reified modifiers and
    // bounds. Returns the parameter names, or an empty list when no `<` follows. (R11)
    private fun parseTypeParamList(): List<String> {
        if (!at(TokenType.LT)) return emptyList()
        advance(); nesting++; skipNL()
        skipTypeParamModifiers()
        val params = mutableListOf(expectIdent())
        skipTypeParamBound()
        while (at(TokenType.COMMA)) { advance(); skipNL(); skipTypeParamModifiers(); params += expectIdent(); skipTypeParamBound() }
        expect(TokenType.GT); nesting--
        return params
    }

    private fun parseFunDecl(isOperator: Boolean = false, isPrivate: Boolean = false, isInternal: Boolean = false, isInline: Boolean = false, isOverride: Boolean = false, isInfix: Boolean = false, isTailrec: Boolean = false, annotations: List<Annotation> = emptyList()): FunDecl {
        expect(TokenType.FUN)
        // Parse optional type parameters: fun <reified T, U> name(...)
        val typeParams = parseTypeParamList()
        // Parse annotations (@Size(N), etc.)
        val firstAnnotations = parseAnnotations()
        val firstName = expectIdent()
        // Parse optional type args on receiver: fun Foo<Int>.bar() or fun Foo<*>.bar()
        val receiverTypeArgs = if (at(TokenType.LT)) {
            advance(); nesting++; skipNL()
            val args = mutableListOf(parseTypeRefOrStar())
            while (at(TokenType.COMMA)) { advance(); skipNL(); args += parseTypeRefOrStar() }
            expect(TokenType.GT); nesting--
            args
        } else emptyList()
        // Extension function: fun ReceiverType.name(...) or fun ReceiverType?.name(...)
        // Also supports nested receivers: fun SDL3.Window.destroy()
        val receiver: TypeRef?
        val name: String
        if (at(TokenType.DOT) || at(TokenType.QUESTION_DOT)) {
            val nullable = at(TokenType.QUESTION_DOT)
            advance()  // skip . or ?.
            // Build up the full receiver qualified name, e.g. "SDL3.Window"
            val vReceiverParts = mutableListOf(firstName, expectIdent())
            while (at(TokenType.DOT)) {
                advance()
                vReceiverParts += expectIdent()
            }
            val vRecvName = vReceiverParts.dropLast(1).joinToString(".")  // "SDL3"
            name = vReceiverParts.last()  // "destroy" (the actual function name)
            receiver = TypeRef(vRecvName, nullable, receiverTypeArgs, annotations = firstAnnotations)
        } else {
            receiver = null
            name = firstName
        }
        expect(TokenType.LPAREN); nesting++
        val params = parseParamList()
        expect(TokenType.RPAREN); nesting--
        val retType = if (at(TokenType.COLON)) { advance(); skipNL(); parseTypeRef() } else null
        skipNL()
        val body: Block? = when {
            at(TokenType.LBRACE) -> parseBlock()
            at(TokenType.EQ) -> { advance(); skipNL(); val e = parseExpr(); skipTerminator(); Block(listOf(ReturnStmt(e))) }
            else -> null
        }
        skipTerminator()
        return FunDecl(name, params, retType, body, receiver, typeParams, isOperator, isPrivate, isInternal, isInline, isOverride, isInfix, isTailrec, annotations)
    }

    private fun parseParamList(): List<Param> {
        val list = mutableListOf<Param>()
        skipNL()
        while (!at(TokenType.RPAREN) && !at(TokenType.EOF)) {
            // Check for 'vararg' modifier (contextual keyword)
            val isVararg = at(TokenType.IDENT) && cur().value == "vararg"
            if (isVararg) advance()
            val name = expectIdent()
            expect(TokenType.COLON); skipNL()
            val type = parseTypeRef()
            val default = if (at(TokenType.EQ)) { advance(); skipNL(); parseExpr() } else null
            list += Param(name, type, default, isVararg)
            if (at(TokenType.COMMA)) { advance(); skipNL() } else break
        }
        skipNL()
        return list
    }

    // ── class / data class ───────────────────────────────────────────

    private fun parseClassDecl(isData: Boolean, annotations: List<Annotation> = emptyList(), isValue: Boolean = false, isSealed: Boolean = false, isInternal: Boolean = false): ClassDecl {
        val name = expectIdent()
        // Parse type parameters: class Foo<out T, in U>(...)
        val typeParams = parseTypeParamList()
        val ctorParams = if (at(TokenType.LPAREN)) {
            advance(); nesting++
            val p = parseCtorParams()
            expect(TokenType.RPAREN); nesting--
            p
        } else emptyList()
        // Parse super interfaces/classes:  : Iface1<T>, Iface2, SealedParent()
        val superInterfaces = mutableListOf<TypeRef>()
        if (at(TokenType.COLON)) {
            advance(); skipNL()
            superInterfaces += parseTypeRef()
            if (at(TokenType.LPAREN)) { advance(); expect(TokenType.RPAREN) }
            while (at(TokenType.COMMA)) { advance(); skipNL(); superInterfaces += parseTypeRef()
                if (at(TokenType.LPAREN)) { advance(); expect(TokenType.RPAREN) }
            }
        }
        skipNL()
        val members = mutableListOf<Decl>()
        val inits = mutableListOf<Block>()
        val secondaryCtors = mutableListOf<SecondaryCtor>()
        if (at(TokenType.LBRACE)) {
            advance(); nesting++; skipNL()
            while (!at(TokenType.RBRACE) && !at(TokenType.EOF)) {
                skipNL(); if (at(TokenType.RBRACE)) break
                if (at(TokenType.COMMENT)) {
                    advance(); skipTerminator()
                } else if (at(TokenType.INIT)) {
                    advance(); inits += parseBlock(); skipTerminator()
                } else if (at(TokenType.IDENT) && cur().value == "constructor") {
                    secondaryCtors += parseSecondaryCtor()
                } else {
                    members += parseDecl()
                }
                skipNL()
            }
            expect(TokenType.RBRACE); nesting--
        }
        skipTerminator()
        return ClassDecl(name, isData, ctorParams, members, inits, superInterfaces, typeParams, secondaryCtors, annotations, isValue, isSealed, isInternal)
    }

    private fun parseCtorParams(): List<CtorParam> {
        val list = mutableListOf<CtorParam>()
        skipNL()
        while (!at(TokenType.RPAREN) && !at(TokenType.EOF)) {
            val annotations = parseAnnotations()
            val isPriv = at(TokenType.PRIVATE)
            if (isPriv) advance()
            var isVal = false; var isVar = false
            if (at(TokenType.VAL)) { isVal = true; advance() }
            else if (at(TokenType.VAR)) { isVar = true; advance() }
            val name = expectIdent()
            expect(TokenType.COLON); skipNL()
            val type = parseTypeRef()
            val finalType = if (annotations.isEmpty()) type else type.copy(annotations = type.annotations + annotations)
            val default = if (at(TokenType.EQ)) { advance(); skipNL(); parseExpr() } else null
            list += CtorParam(name, finalType, default, isVal, isVar, isPriv)
            if (at(TokenType.COMMA)) { advance(); skipNL() } else break
        }
        skipNL()
        return list
    }

    // ── secondary constructor ─────────────────────────────────────────

    private fun parseSecondaryCtor(): SecondaryCtor {
        advance()   // skip "constructor"
        expect(TokenType.LPAREN); nesting++
        val params = parseParamList()
        expect(TokenType.RPAREN); nesting--
        skipNL()
        // delegation: : this(args)
        expect(TokenType.COLON); skipNL()
        val delegation = parseDelegationCall()
        skipNL()
        val body = when {
            at(TokenType.LBRACE) -> parseBlock()
            else -> Block(emptyList())
        }
        skipTerminator()
        return SecondaryCtor(params, delegation, body)
    }

    private fun parseDelegationCall(): CallExpr {
        expect(TokenType.THIS)
        expect(TokenType.LPAREN); nesting++
        val args = parseArgList()
        expect(TokenType.RPAREN); nesting--
        return CallExpr(ThisExpr, args)
    }

    // ── enum class ───────────────────────────────────────────────────

    private fun parseEnumDecl(annotations: List<Annotation> = emptyList()): EnumDecl {
        val name = expectIdent()
        // Primary constructor params on the enum: `enum class Op(val sym: String) { ... }`.
        val ctorParams = if (at(TokenType.LPAREN)) {
            advance(); nesting++
            val p = parseCtorParams()
            expect(TokenType.RPAREN); nesting--
            p
        } else emptyList()
        expect(TokenType.LBRACE); nesting++; skipNL()
        val entries = mutableListOf<EnumEntry>()
        while (!at(TokenType.RBRACE) && !at(TokenType.EOF) && !at(TokenType.SEMICOLON)) {
            val vEntry = expectIdent()
            // Per-entry constructor args: `PLUS("+")`.
            val vEntryArgs = if (at(TokenType.LPAREN)) {
                advance(); nesting++
                val a = parseArgList()
                expect(TokenType.RPAREN); nesting--
                a
            } else emptyList()
            // Per-entry override body block: `PLUS { override fun apply(...) = ... }`.
            val vOverrides = mutableListOf<FunDecl>()
            if (at(TokenType.LBRACE)) {
                advance(); nesting++; skipNL()
                while (!at(TokenType.RBRACE) && !at(TokenType.EOF)) {
                    if (at(TokenType.COMMENT)) { advance(); skipTerminator(); continue }
                    val vDecl = parseDecl()
                    if (vDecl !is FunDecl)
                        error("Enum entry '${name}.$vEntry' body only supports function declarations (got ${vDecl::class.simpleName}).")
                    vOverrides += vDecl
                    skipNL()
                }
                expect(TokenType.RBRACE); nesting--
            }
            entries += EnumEntry(vEntry, vEntryArgs, vOverrides)
            if (at(TokenType.COMMA)) { advance(); skipNL() } else break
        }
        // skipNL() consumes both newlines AND semicolons; check SEMICOLON first
        // so we can distinguish `;` (potential body separator) from a trailing newline.
        while (at(TokenType.NEWLINE)) advance()
        val members = mutableListOf<Decl>()
        if (at(TokenType.SEMICOLON)) {
            advance()
            while (at(TokenType.NEWLINE) || at(TokenType.SEMICOLON)) advance()
            // Body methods/properties — same parse rules as class members.
            while (!at(TokenType.RBRACE) && !at(TokenType.EOF)) {
                if (at(TokenType.COMMENT)) { advance(); skipTerminator() }
                else members += parseDecl()
                skipNL()
            }
        }
        expect(TokenType.RBRACE); nesting--
        skipTerminator()
        // @SimpleEnum asserts the enum is the int-only form: no ctor params, no per-entry
        // args, no body members. The default is now full enums; @SimpleEnum keeps the
        // zero-overhead C-int representation for users who want it explicitly.
        val isSimpleMarker = annotations.any { it.name == "SimpleEnum" }
        if (isSimpleMarker) {
            if (ctorParams.isNotEmpty())
                error("@SimpleEnum enum '$name' must have no constructor parameters")
            if (entries.any { it.args.isNotEmpty() })
                error("@SimpleEnum enum '$name' must have no per-entry arguments")
            if (members.isNotEmpty())
                error("@SimpleEnum enum '$name' must have no body members")
        }
        return EnumDecl(name, entries, annotations, ctorParams, members)
    }

    // ── typealias ────────────────────────────────────────────────────

    /* `typealias Name = TargetType` — resolved by substitution during
     * type-name resolution. Generic alias parameters (`typealias Foo<T> = ...`)
     * are not supported (use the underlying type directly). */
    private fun parseTypeAliasDecl(): TypeAliasDecl {
        expect(TokenType.TYPEALIAS); skipNL()
        val vName = expectIdent(); skipNL()
        expect(TokenType.EQ); skipNL()
        val vTarget = parseTypeRef()
        skipTerminator()
        return TypeAliasDecl(vName, vTarget)
    }

    // ── interface ─────────────────────────────────────────────────────

    private fun parseInterfaceDecl(isSealed: Boolean = false, annotations: List<Annotation> = emptyList()): InterfaceDecl {
        expect(TokenType.INTERFACE)
        val name = expectIdent()
        // Parse type parameters: interface Foo<out T, in U>
        val typeParams = parseTypeParamList()
        // Parse super interfaces: : SuperIface<T>, OtherIface
        val superInterfaces = mutableListOf<TypeRef>()
        if (at(TokenType.COLON)) {
            advance(); skipNL()
            superInterfaces += parseTypeRef()
            while (at(TokenType.COMMA)) { advance(); skipNL(); superInterfaces += parseTypeRef() }
        }
        skipNL()
        val methods = mutableListOf<FunDecl>()
        val properties = mutableListOf<PropDecl>()
        val nestedClasses = mutableListOf<ClassDecl>()
        var companionMembers: List<Decl> = emptyList()
        if (at(TokenType.LBRACE)) {
            advance(); nesting++; skipNL()
            while (!at(TokenType.RBRACE) && !at(TokenType.EOF)) {
                skipNL(); if (at(TokenType.RBRACE)) break
                // skip 'override' modifier inside interfaces
                if (at(TokenType.OVERRIDE)) advance()
                // track 'operator' modifier inside interfaces
                val isOp = at(TokenType.IDENT) && cur().value == "operator"
                if (isOp) advance()
                when {
                    at(TokenType.FUN) -> methods += parseFunDecl(isOperator = isOp)
                    at(TokenType.VAL) -> properties += parsePropDecl(mutable = false)
                    at(TokenType.VAR) -> properties += parsePropDecl(mutable = true)
                    at(TokenType.DATA) -> { advance(); expect(TokenType.CLASS); nestedClasses += parseClassDecl(isData = true) }
                    at(TokenType.CLASS) -> { advance(); nestedClasses += parseClassDecl(isData = false) }
                    at(TokenType.IDENT) && cur().value == "companion" && peek().type == TokenType.OBJECT -> {
                        advance()
                        val obj = parseCompanionObjectDecl()
                        companionMembers = obj.members
                    }
                    else -> error("Expected fun, val, var, or class in interface body at ${cur()}")
                }
                skipNL()
            }
            expect(TokenType.RBRACE); nesting--
        }
        skipTerminator()
        return InterfaceDecl(name, methods, properties, typeParams, superInterfaces, nestedClasses, isSealed, annotations, companionMembers)
    }

    // ── object ───────────────────────────────────────────────────────

    private fun parseObjectDecl(anns: List<Annotation> = emptyList()): ObjectDecl {
        expect(TokenType.OBJECT)
        val name = expectIdent()
        skipNL()
        // Parse optional super interfaces: object Name : Interface1, Interface2 { ... }
        val superInterfaces = mutableListOf<TypeRef>()
        if (at(TokenType.COLON)) {
            advance(); skipNL()
            while (true) {
                val iface = parseTypeRef()
                superInterfaces += iface
                if (at(TokenType.COMMA)) { advance(); skipNL() }
                else break
            }
            skipNL()
        }
        val members = mutableListOf<Decl>()
        if (at(TokenType.LBRACE)) {
            advance(); nesting++; skipNL()
            while (!at(TokenType.RBRACE) && !at(TokenType.EOF)) {
                skipNL(); if (at(TokenType.RBRACE)) break
                members += parseDecl(); skipNL()
            }
            expect(TokenType.RBRACE); nesting--
        }
        skipTerminator()
        return ObjectDecl(name, members, anns, superInterfaces)
    }

    private fun parseAnonObjectExpr(): ObjectExpr {
        expect(TokenType.OBJECT)
        skipNL()
        if (!at(TokenType.COLON)) error("Anonymous object requires ': Interface' after 'object'")
        advance(); skipNL()
        val superInterfaces = mutableListOf<TypeRef>()
        while (true) {
            superInterfaces += parseTypeRef()
            if (at(TokenType.COMMA)) { advance(); skipNL() }
            else break
        }
        skipNL()
        val members = mutableListOf<Decl>()
        if (at(TokenType.LBRACE)) {
            advance(); nesting++; skipNL()
            while (!at(TokenType.RBRACE) && !at(TokenType.EOF)) {
                skipNL(); if (at(TokenType.RBRACE)) break
                members += parseDecl(); skipNL()
            }
            expect(TokenType.RBRACE); nesting--
        }
        val name = "\$anon_${anonObjectCounter++}"
        syntheticDecls += ObjectDecl(name, members, superInterfaces = superInterfaces)
        return ObjectExpr(name)
    }

    // ── companion object ─────────────────────────────────────────────

    /*
    Parses a companion object declaration.
    "companion" has already been consumed by the caller.
    Supports: companion object { ... }
    and:      companion object Name { ... }
    The companion is stored as ObjectDecl with name "Companion" when unnamed,
    or with its explicit name when provided.
    */
    private fun parseCompanionObjectDecl(anns: List<Annotation> = emptyList()): ObjectDecl {
        expect(TokenType.OBJECT)
        skipNL()
        val vName = if (at(TokenType.IDENT)) expectIdent() else "Companion" // explicit or default name
        skipNL()
        val vMembers = mutableListOf<Decl>()
        if (at(TokenType.LBRACE)) {
            advance(); nesting++; skipNL()
            while (!at(TokenType.RBRACE) && !at(TokenType.EOF)) {
                skipNL(); if (at(TokenType.RBRACE)) break
                vMembers += parseDecl(); skipNL()
            }
            expect(TokenType.RBRACE); nesting--
        }
        skipTerminator()
        return ObjectDecl(vName, vMembers, anns)
    }

    // ── val / var (top-level or class-level property) ────────────────

    private fun parsePropDecl(mutable: Boolean, preAnnotations: List<Annotation> = emptyList(), isPrivate: Boolean = false, isInternal: Boolean = false, isInline: Boolean = false): PropDecl {
        val line = cur().line
        val typeAnnotations = if (preAnnotations.isEmpty()) parseAnnotations() else emptyList()
        advance()   // skip val/var
        // Skip type parameters on extension properties: val <T> Receiver<T>.name
        if (at(TokenType.LT)) {
            advance(); nesting++
            while (!at(TokenType.GT) && !at(TokenType.EOF)) advance()
            expect(TokenType.GT); nesting--
        }
        // Extension property: val Receiver.name get() = expr
        val receiver: TypeRef?
        val name: String
        val vFirstName = expectIdent()
        // Skip type args on receiver: Result<T>.name
        val vRecvTypeArgs = mutableListOf<TypeRef>()
        if (at(TokenType.LT)) {
            advance(); nesting++; skipNL()
            vRecvTypeArgs += parseTypeRef()
            while (at(TokenType.COMMA)) { advance(); skipNL(); vRecvTypeArgs += parseTypeRef() }
            expect(TokenType.GT); nesting--
        }
        if (at(TokenType.DOT)) {
            advance()
            // Build full receiver name chain (e.g. SDL3.Event)
            val vParts = mutableListOf(vFirstName, expectIdent())
            while (at(TokenType.DOT)) { advance(); vParts += expectIdent() }
            val vRecvName = vParts.dropLast(1).joinToString(".")
            name = vParts.last()
            receiver = TypeRef(vRecvName, typeArgs = vRecvTypeArgs)
        } else {
            receiver = null
            name = vFirstName
        }
        val type = if (at(TokenType.COLON)) {
            advance(); skipNL()
            val t = parseTypeRef()
            if (typeAnnotations.isEmpty()) t else t.copy(annotations = t.annotations + typeAnnotations)
        } else null
        // Peek ahead to check for custom accessors (get/set) before parsing init
        var vGetter: Expr? = null
        var vSetterParam: String? = null
        var vSetterBody: Block? = null
        val vSaved = pos
        if (at(TokenType.NEWLINE)) advance()
        if (at(TokenType.IDENT) && cur().value == "get") {
            advance()
            expect(TokenType.LPAREN); expect(TokenType.RPAREN)
            if (at(TokenType.EQ)) { advance(); skipNL(); vGetter = parseExpr() }
            if (at(TokenType.NEWLINE)) advance()
            }
        if (at(TokenType.IDENT) && cur().value == "set") {
            advance()
            expect(TokenType.LPAREN)
            vSetterParam = expectIdent()
            expect(TokenType.RPAREN)
            skipNL()
            vSetterBody = parseBlock()
            }
        val vHasAccessor = vGetter != null || vSetterBody != null
        if (!vHasAccessor) pos = vSaved  // rollback if no getter/setter found

        // by lazy { body }
        var lazyInit: Block? = null
        if (!vHasAccessor && at(TokenType.IDENT) && cur().value == "by") {
            val savedBy = pos
            advance(); skipNL()
            if (at(TokenType.IDENT) && cur().value == "lazy") {
                if (mutable) error("'by lazy' is not allowed on 'var' declarations")
                advance(); skipNL()
                lazyInit = parseBlock()
            } else {
                pos = savedBy
            }
        }
        val init = if (lazyInit == null && !vHasAccessor && at(TokenType.EQ)) { advance(); skipNL(); parseExpr() } else null
        var isPrivateSet = false
        if (!isPrivate) {
            if (at(TokenType.NEWLINE)) advance()
            if (at(TokenType.PRIVATE)) {
                val savedPos = pos
                advance()
                skipNL()
                if (at(TokenType.IDENT) && cur().value == "set") {
                    if (!mutable) error("'private set' is not allowed on 'val'")
                    advance()
                    isPrivateSet = true
                } else {
                    pos = savedPos
                }
            }
        }
        skipTerminator()
        return PropDecl(name, type, init, mutable, line, isPrivate, isPrivateSet, isInternal = isInternal, annotations = preAnnotations,
            receiver = receiver, getter = vGetter, setterParam = vSetterParam, setterBody = vSetterBody, isInline = isInline, lazyInit = lazyInit)
    }

    // ═══════════════════════════ Statements ═══════════════════════════

    private fun parseBlock(): Block {
        expect(TokenType.LBRACE); nesting++; skipNL()
        val stmts = mutableListOf<Stmt>()
        while (!at(TokenType.RBRACE) && !at(TokenType.EOF)) {
            skipNL(); if (at(TokenType.RBRACE)) break
            stmts += parseStmt()
            skipTerminator()
            skipNL()
        }
        expect(TokenType.RBRACE); nesting--
        return Block(stmts)
    }

    private fun parseStmt(): Stmt {
        skipNL()
        val stmtLine = cur().line
        val stmtCol = cur().col
        val stmt = when {
            at(TokenType.COMMENT) -> { val text = advance().value; CommentStmt(text) }
            at(TokenType.VAL) -> parseVarDeclStmt(mutable = false)
            at(TokenType.VAR) -> parseVarDeclStmt(mutable = true)
            at(TokenType.RETURN) -> { advance(); val v = if (atExprStart()) parseExpr() else null; ReturnStmt(v) }
            at(TokenType.FOR)    -> parseForStmt()
            at(TokenType.WHILE)  -> parseWhileStmt()
            at(TokenType.DO)     -> parseDoWhileStmt()
            at(TokenType.BREAK)  -> { advance(); BreakStmt() }
            at(TokenType.CONTINUE) -> { advance(); ContinueStmt() }
            at(TokenType.DEFER)  -> parseDeferStmt()
            else -> parseExprOrAssignStmt()
        }
        stmt.line = stmtLine
        stmt.col = stmtCol
        return stmt
    }

    private fun parseVarDeclStmt(mutable: Boolean): Stmt {
        advance()   // skip val/var
        // Destructuring: `val (a, b, ...) = expr`
        if (at(TokenType.LPAREN)) {
            advance(); skipNL()
            val names = mutableListOf<String>()
            names += expectIdent()
            while (at(TokenType.COMMA)) { advance(); skipNL(); names += expectIdent() }
            skipNL(); expect(TokenType.RPAREN); skipNL()
            expect(TokenType.EQ); skipNL()
            val init = parseExpr()
            return DestructuringDeclStmt(names, init, mutable)
        }
        val name = expectIdent()
        val type = if (at(TokenType.COLON)) { advance(); skipNL(); parseTypeRef() } else null
        // by lazy { body }
        if (at(TokenType.IDENT) && cur().value == "by") {
            val savedBy = pos
            advance(); skipNL()
            if (at(TokenType.IDENT) && cur().value == "lazy") {
                if (mutable) error("'by lazy' is not allowed on 'var' declarations")
                advance(); skipNL()
                val body = parseBlock()
                return VarDeclStmt(name, type, null, mutable, lazyInit = body)
            }
            pos = savedBy
        }
        val init = if (at(TokenType.EQ)) { advance(); skipNL(); parseExpr() } else null
        return VarDeclStmt(name, type, init, mutable)
    }

    private fun parseExprOrAssignStmt(): Stmt {
        val expr = parseExpr()
        return if (at(TokenType.EQ) || at(TokenType.PLUS_EQ) || at(TokenType.MINUS_EQ) ||
            at(TokenType.STAR_EQ) || at(TokenType.SLASH_EQ) || at(TokenType.PERCENT_EQ)) {
            val op = advance().value; skipNL()
            val value = parseExpr()
            AssignStmt(expr, op, value)
        } else {
            ExprStmt(expr)
        }
    }

    // ── for ──────────────────────────────────────────────────────────

    private fun parseForStmt(): ForStmt {
        expect(TokenType.FOR)
        expect(TokenType.LPAREN); nesting++; skipNL()
        // Optional destructuring: `for ((a, b) in pairs)` — collect names,
        // generate a synthetic temp name for the iterator element binding.
        val vDestructure = mutableListOf<String>()
        val varName: String
        if (at(TokenType.LPAREN)) {
            advance(); skipNL()
            vDestructure += expectIdent()
            while (at(TokenType.COMMA)) { advance(); skipNL(); vDestructure += expectIdent() }
            skipNL(); expect(TokenType.RPAREN); skipNL()
            varName = "\$ditem_" + vDestructure.joinToString("_")
        } else {
            varName = expectIdent()
        }
        expect(TokenType.IN); skipNL()
        val iter = parseExpr()
        expect(TokenType.RPAREN); nesting--; skipNL()
        val body = parseBlock()
        return ForStmt(varName, iter, body, destructureNames = vDestructure)
    }

    // ── while / do-while ─────────────────────────────────────────────

    private fun parseWhileStmt(): WhileStmt {
        expect(TokenType.WHILE)
        expect(TokenType.LPAREN); nesting++; skipNL()
        val cond = parseExpr()
        expect(TokenType.RPAREN); nesting--; skipNL()
        return WhileStmt(cond, parseBlock())
    }

    private fun parseDoWhileStmt(): DoWhileStmt {
        expect(TokenType.DO); skipNL()
        val body = parseBlock(); skipNL()
        expect(TokenType.WHILE)
        expect(TokenType.LPAREN); nesting++; skipNL()
        val cond = parseExpr()
        expect(TokenType.RPAREN); nesting--
        return DoWhileStmt(body, cond)
    }

    // ── defer ────────────────────────────────────────────────────────

    private fun parseDeferStmt(): DeferStmt {
        expect(TokenType.DEFER); skipNL()
        val body = if (at(TokenType.LBRACE)) parseBlock()
                   else Block(listOf(ExprStmt(parseExpr())))
        return DeferStmt(body)
    }

    // ═══════════════════════════ Expressions (Pratt) ═════════════════

    fun parseExpr(minPrec: Int = 0): Expr {
        var left = parsePrefixExpr()
        while (true) {
            left = parsePostfixChain(left)
            if (!noNewlineExpr) skipNL()
            val prec = binaryPrec()
            if (prec < 0 || prec < minPrec) break

            when (cur().type) {
                // ── elvis ?: (right-assoc) ──
                TokenType.QUESTION_COLON -> {
                    advance(); skipNL()
                    val right = parseExpr(prec)      // same prec → right-assoc
                    left = ElvisExpr(left, right)
                }
                // ── is / !is ──
                TokenType.IS -> {
                    advance(); skipNL()
                    left = IsCheckExpr(left, parseTypeRef(), negated = false)
                }
                TokenType.EXCL -> {
                    // !in  or  !is  — peek ahead
                    if (peek().type == TokenType.IS) {
                        advance(); advance(); skipNL()
                        left = IsCheckExpr(left, parseTypeRef(), negated = true)
                    } else if (peek().type == TokenType.IN) {
                        advance(); advance(); skipNL()
                        left = BinExpr(left, "!in", parseExpr(prec + 1))
                    } else break
                }
                TokenType.IN -> {
                    advance(); skipNL()
                    left = BinExpr(left, "in", parseExpr(prec + 1))
                }
                TokenType.AS -> {
                    advance(); skipNL()
                    if (at(TokenType.QUESTION)) {
                        advance(); skipNL()
                        left = CastExpr(left, parseTypeRef(), safe = true)
                    } else {
                        left = CastExpr(left, parseTypeRef())
                    }
                }
                // ── infix identifiers: until, downTo, step ──
                TokenType.IDENT -> {
                    val name = cur().value
                    if (name !in INFIX_IDS) break
                    advance(); skipNL()
                    left = BinExpr(left, name, parseExpr(prec + 1))
                }
                // ── standard binary ops ──
                else -> {
                    val op = advance(); skipNL()
                    val rightPrec = prec + 1       // left-assoc
                    left = BinExpr(left, op.value, parseExpr(rightPrec))
                }
            }
        }
        return left
    }

    // ── Prefix unary ─────────────────────────────────────────────────

    private fun parsePrefixExpr(): Expr {
        return when {
            at(TokenType.MINUS) || at(TokenType.EXCL) || at(TokenType.PLUS_PLUS) || at(TokenType.MINUS_MINUS) -> {
                // guard: EXCL followed by IN/IS is NOT a prefix — fall through
                if (at(TokenType.EXCL) && (peek().type == TokenType.IN || peek().type == TokenType.IS)) {
                    parsePrimary()
                } else {
                    val op = advance().value; skipNL()
                    // Apply postfix chain (. [] () !!) to the operand first so that
                    // !foo.bar() parses as !(foo.bar()) rather than (!foo).bar()
                    PrefixExpr(op, parsePostfixChain(parsePrefixExpr()))
                }
            }
            at(TokenType.PLUS) -> { advance(); skipNL(); parsePrefixExpr() }   // unary + is no-op
            else -> parsePrimary()
        }
    }

    // ── Postfix chain: . ?. () [] ++ -- !! ───────────────────────────

    private fun parsePostfixChain(start: Expr): Expr {
        var e = start
        loop@ while (true) {
            e = when {
                at(TokenType.DOT) -> {
                    advance(); skipNL()
                    val dotExpr = DotExpr(e, expectIdent())
                    // Allow no-paren trailing lambda: expr.method { lambda }
                    if (at(TokenType.LBRACE)) CallExpr(dotExpr, listOf(Arg(null, parseLambdaExpr())))
                    else dotExpr
                }
                at(TokenType.QUESTION_DOT) -> {
                    advance(); skipNL()
                    val safeDotExpr = SafeDotExpr(e, expectIdent())
                    // Allow no-paren trailing lambda: expr?.method { lambda }
                    if (at(TokenType.LBRACE)) CallExpr(safeDotExpr, listOf(Arg(null, parseLambdaExpr())))
                    else safeDotExpr
                }
                at(TokenType.LBRACE) -> {
                    // No-paren trailing lambda: handleEvent { event -> ... }
                    CallExpr(e, listOf(Arg(null, parseLambdaExpr())))
                    }
                at(TokenType.LPAREN) -> {
                    advance(); nesting++; skipNL()
                    val args = parseArgList()
                    expect(TokenType.RPAREN); nesting--
                    val allArgs = if (at(TokenType.LBRACE)) args + Arg(null, parseLambdaExpr()) else args
                    // !helper() → PrefixExpr(!, CallExpr(helper, ())) not CallExpr(PrefixExpr(!,helper), ())
                    if (e is PrefixExpr) {
                        PrefixExpr(e.op, CallExpr(e.expr, allArgs))
                    } else {
                        CallExpr(e, allArgs)
                    }
                }
                // Type-parameterized call: malloc<Int>(n)  or  Array<Int>.method(args)  or  Result.Ok<Int>(x)
                at(TokenType.LT) && (e is NameExpr || e is DotExpr) && looksLikeTypeArgs() -> {
                    val typeArgs = parseTypeArgList()
                    if (at(TokenType.DOT)) {
                        // Array<Int>.method(args)
                        advance(); skipNL()
                        val dotExpr = DotExpr(e, expectIdent())
                        if (at(TokenType.LPAREN)) {
                            advance(); nesting++; skipNL()
                            val args = parseArgList()
                            expect(TokenType.RPAREN); nesting--
                            val allArgs = if (at(TokenType.LBRACE)) args + Arg(null, parseLambdaExpr()) else args
                            CallExpr(dotExpr, allArgs, typeArgs)
                        } else if (at(TokenType.LBRACE)) {
                            CallExpr(dotExpr, listOf(Arg(null, parseLambdaExpr())), typeArgs)
                        } else dotExpr
                    } else {
                        expect(TokenType.LPAREN); nesting++; skipNL()
                        val args = parseArgList()
                        expect(TokenType.RPAREN); nesting--
                        val allArgs = if (at(TokenType.LBRACE)) args + Arg(null, parseLambdaExpr()) else args
                        CallExpr(e, allArgs, typeArgs)
                    }
                }
                at(TokenType.LBRACKET) -> {
                    advance(); nesting++; skipNL()
                    val idx = parseExpr()
                    expect(TokenType.RBRACKET); nesting--
                    IndexExpr(e, idx)
                }
                at(TokenType.COLON_COLON) && e is NameExpr && peek().type == TokenType.CLASS -> {
                    advance(); advance() // skip :: and class
                    ClassRefExpr(e.name)
                }
                at(TokenType.EXCL_EXCL) -> { advance(); NotNullExpr(e) }
                at(TokenType.PLUS_PLUS)  -> { advance(); PostfixExpr(e, "++") }
                at(TokenType.MINUS_MINUS) -> { advance(); PostfixExpr(e, "--") }
                else -> break@loop
            }
        }
        return e
    }

    private fun parseArgList(): List<Arg> {
        val list = mutableListOf<Arg>()
        skipNL()
        while (!at(TokenType.RPAREN) && !at(TokenType.EOF)) {
            // Try named arg:  name = expr
            val name = if (at(TokenType.IDENT) && peek().type == TokenType.EQ) {
                val n = advance().value; advance(); n      // consume ident and =
            } else null
            skipNL()
            // Check for spread operator: *array
            val isSpread = at(TokenType.STAR)
            if (isSpread) advance()
            list += Arg(name, parseExpr(), isSpread)
            if (at(TokenType.COMMA)) { advance(); skipNL() } else break
        }
        skipNL()
        return list
    }

    // ── Primary ──────────────────────────────────────────────────────

    private fun parsePrimary(): Expr {
        skipNL()
        return when {
            at(TokenType.INT_LIT)    -> {
                val raw = advance().value
                val hex = raw.startsWith("0x") || raw.startsWith("0X")
                val value = if (hex) raw.substring(2).toLong(16) else raw.toLong()
                IntLit(value, hex)
            }
            at(TokenType.LONG_LIT)   -> {
                val raw = advance().value.removeSuffix("L")
                val hex = raw.startsWith("0x") || raw.startsWith("0X")
                val value = if (hex) raw.substring(2).toLong(16) else raw.toLong()
                LongLit(value, hex)
            }
            at(TokenType.UINT_LIT)   -> {
                var raw = advance().value
                raw = raw.removeSuffix("u").removeSuffix("U")
                val hex = raw.startsWith("0x") || raw.startsWith("0X")
                val value = if (hex) raw.substring(2).toLong(16) else raw.toLong()
                UIntLit(value, hex)
            }
            at(TokenType.ULONG_LIT)  -> {
                var raw = advance().value
                // Strip u/U and L in any order (42uL, 42UL, 42Lu are all valid)
                raw = raw.replace("u", "").replace("U", "").replace("L", "")
                val hex = raw.startsWith("0x") || raw.startsWith("0X")
                val value = if (hex) raw.substring(2).toULong(16) else raw.toULong()
                ULongLit(value, hex)
            }
            at(TokenType.FLOAT_LIT)  -> FloatLit(advance().value.removeSuffix("f").removeSuffix("F").toDouble())
            at(TokenType.DOUBLE_LIT) -> DoubleLit(advance().value.toDouble())
            at(TokenType.CHAR_LIT)   -> CharLit(advance().value[0])
            at(TokenType.TRUE)       -> { advance(); BoolLit(true) }
            at(TokenType.FALSE)      -> { advance(); BoolLit(false) }
            at(TokenType.NULL)       -> { advance(); NullLit }
            at(TokenType.THIS)       -> { advance(); ThisExpr }
            at(TokenType.STRING_LIT) -> StrLit(advance().value)
            at(TokenType.STR_TMPL_START) -> parseStringTemplate()
            at(TokenType.IDENT)      -> NameExpr(advance().value)
            at(TokenType.COLON_COLON) -> { advance(); FunRefExpr(expectIdent()) }
            at(TokenType.IF)         -> parseIfExpr()
            at(TokenType.WHEN)       -> parseWhenExpr()
            at(TokenType.LBRACE)     -> parseLambdaExpr()
            at(TokenType.LPAREN)     -> { advance(); nesting++; skipNL(); val e = parseExpr(); skipNL(); expect(TokenType.RPAREN); nesting--; e }
            at(TokenType.OBJECT)     -> parseAnonObjectExpr()
            else -> error("Expected expression, got ${cur()}")
        }
    }

    // ── String template ──────────────────────────────────────────────

    private fun parseStringTemplate(): StrTemplateExpr {
        expect(TokenType.STR_TMPL_START)
        val parts = mutableListOf<StrPart>()
        while (!at(TokenType.STR_TMPL_END) && !at(TokenType.EOF)) {
            when {
                at(TokenType.STR_TMPL_PART) -> parts += LitPart(advance().value)
                at(TokenType.TMPL_REF)      -> parts += ExprPart(NameExpr(advance().value))
                at(TokenType.TMPL_EXPR_START) -> {
                    advance()    // skip ${
                    parts += ExprPart(parseExpr())
                    expect(TokenType.TMPL_EXPR_END)
                }
                else -> break
            }
        }
        expect(TokenType.STR_TMPL_END)
        return StrTemplateExpr(parts)
    }

    // ── if (expression / statement) ──────────────────────────────────

    private fun parseIfExpr(): IfExpr {
        expect(TokenType.IF)
        expect(TokenType.LPAREN); nesting++; skipNL()
        val cond = parseExpr()
        expect(TokenType.RPAREN); nesting--; skipNL()
        val thenBlock = if (at(TokenType.LBRACE)) parseBlock() else Block(listOf(parseSingleStmtOrExpr()))
        skipNL()
        val elseBlock = if (at(TokenType.ELSE)) {
            advance(); skipNL()
            if (at(TokenType.IF)) Block(listOf(ExprStmt(parseIfExpr())))
            else if (at(TokenType.LBRACE)) parseBlock()
            else Block(listOf(parseSingleStmtOrExpr()))
        } else null
        return IfExpr(cond, thenBlock, elseBlock)
    }

    /** Parse a single statement when braces are omitted (e.g. `if (c) return x`). */
    private fun parseSingleStmtOrExpr(): Stmt {
        val stmtLine = cur().line
        val stmtCol = cur().col
        val stmt = when {
            at(TokenType.RETURN)   -> { advance(); val v = if (atExprStart()) parseExpr() else null; ReturnStmt(v) }
            at(TokenType.BREAK)    -> { advance(); BreakStmt() }
            at(TokenType.CONTINUE) -> { advance(); ContinueStmt() }
            else -> {
                val expr = parseExpr()
                if (at(TokenType.EQ) || at(TokenType.PLUS_EQ) || at(TokenType.MINUS_EQ) ||
                    at(TokenType.STAR_EQ) || at(TokenType.SLASH_EQ) || at(TokenType.PERCENT_EQ)) {
                    val op = advance().value; skipNL()
                    val value = parseExpr()
                    AssignStmt(expr, op, value)
                } else {
                    ExprStmt(expr)
                }
            }
        }
        stmt.line = stmtLine
        stmt.col = stmtCol
        return stmt
    }

    // ── when (expression / statement) ────────────────────────────────

    private fun parseWhenExpr(): WhenExpr {
        expect(TokenType.WHEN); skipNL()
        val subject = if (at(TokenType.LPAREN)) {
            advance(); nesting++; skipNL()
            val s = parseExpr()
            expect(TokenType.RPAREN); nesting--; skipNL()
            s
        } else null
        expect(TokenType.LBRACE); nesting++; skipNL()
        val branches = mutableListOf<WhenBranch>()
        while (!at(TokenType.RBRACE) && !at(TokenType.EOF)) {
            skipNL(); if (at(TokenType.RBRACE)) break
            branches += parseWhenBranch()
            skipTerminator(); skipNL()
        }
        expect(TokenType.RBRACE); nesting--
        return WhenExpr(subject, branches)
    }

    private fun parseWhenBranch(): WhenBranch {
        val conds: List<WhenCond>? = if (at(TokenType.ELSE)) { advance(); null }
        else {
            val list = mutableListOf<WhenCond>()
            list += parseWhenCond()
            while (at(TokenType.COMMA)) { advance(); skipNL(); list += parseWhenCond() }
            list
        }
        expect(TokenType.ARROW); skipNL()
        val prevNoNL = noNewlineExpr
        noNewlineExpr = true
        val body = if (at(TokenType.LBRACE)) parseBlock() else {
            // Single-statement body: may be an assignment (x = y) or expression call
            Block(listOf(parseStmt()))
            }
        noNewlineExpr = prevNoNL
        return WhenBranch(conds, body)
    }

    private fun parseWhenCond(): WhenCond {
        skipNL()
        return when {
            at(TokenType.IS) -> { advance(); skipNL(); IsCond(parseTypeRef()) }
            at(TokenType.EXCL) && peek().type == TokenType.IS -> { advance(); advance(); skipNL(); IsCond(parseTypeRef(), negated = true) }
            at(TokenType.IN) -> { advance(); skipNL(); InCond(parseExpr(PREC_NAMED + 1)) }
            at(TokenType.EXCL) && peek().type == TokenType.IN -> { advance(); advance(); skipNL(); InCond(parseExpr(PREC_NAMED + 1), negated = true) }
            else -> ExprCond(parseExpr())
        }
    }

    // ═══════════════════════════ Type references ══════════════════════

    /** Lookahead: does `<` here start type args? Check for `<Ident>` or `<Ident,` pattern. */
    private fun looksLikeTypeArgs(): Boolean {
        // Save position, peek ahead: < IDENT > ( or < IDENT , ... or < IDENT < (nested type args)
        val saved = pos
        try {
            if (!at(TokenType.LT)) return false
            advance(); skipNL()
            if (!at(TokenType.IDENT)) return false
            val name = tokens[pos].value
            if (name.isEmpty() || name[0].isLowerCase()) return false  // types start uppercase
            advance(); skipNL()
            // Skip dotted names: SDL3.FPoint, Outer.Inner.Nested
            while (at(TokenType.DOT) && pos + 1 < tokens.size && tokens[pos + 1].type == TokenType.IDENT) {
                advance(); advance(); skipNL()
            }
            // Skip nullable marker for type args like <Int?>
            if (at(TokenType.QUESTION)) { advance(); skipNL() }
            return at(TokenType.GT) || at(TokenType.COMMA) || at(TokenType.LT)
        } finally {
            pos = saved
        }
    }

    /** Parse `<Type, Type, ...>` type argument list. */
    private fun parseTypeArgList(): List<TypeRef> {
        expect(TokenType.LT); nesting++; skipNL()
        val args = mutableListOf(parseTypeRefOrStar())
        while (at(TokenType.COMMA)) { advance(); skipNL(); args += parseTypeRefOrStar() }
        expect(TokenType.GT); nesting--
        return args
    }

    private fun skipTypeParamModifiers() {
        while (true) {
            if (at(TokenType.IDENT) && cur().value in setOf("reified", "out")) { advance(); continue }
            if (at(TokenType.IN)) { advance(); continue }
            break
        }
    }

    private fun skipTypeParamBound() {
        if (at(TokenType.COLON)) { advance(); skipNL(); parseTypeRef() }
    }

    /** Parse a type reference or star projection (*). Star is represented as TypeRef("*"). */
    private fun parseTypeRefOrStar(): TypeRef {
        if (at(TokenType.STAR)) { advance(); return TypeRef("*") }
        return parseTypeRef()
    }

    private fun parseTypeRef(): TypeRef {
        // Parse annotations: @Size(5) Array<Int>
        val annotations = parseAnnotations()
        // Receiver function type: T.(params) -> R or T.() -> R
        if (at(TokenType.IDENT) && peek().type == TokenType.DOT) {
            val savedForReceiver = pos  // save before consuming — qualified names like c.SDL_Window must not be eaten here
            val recvName = expectIdent()
            expect(TokenType.DOT)
            if (at(TokenType.LPAREN)) {
                val saved = pos
                val savedNesting = nesting
                try {
                    advance(); nesting++; skipNL()
                    val paramTypes = mutableListOf<TypeRef>()
                    while (!at(TokenType.RPAREN) && !at(TokenType.EOF)) {
                        paramTypes += parseTypeRef()
                        if (at(TokenType.COMMA)) { advance(); skipNL() } else break
                    }
                    expect(TokenType.RPAREN); nesting--; skipNL()
                    if (at(TokenType.ARROW)) {
                        advance(); skipNL()
                        val retType = parseTypeRef()
                        val nullable = if (at(TokenType.QUESTION)) { advance(); true } else false
                        return TypeRef("Function", nullable, emptyList(), paramTypes, retType, TypeRef(recvName), annotations)
                    }
                } catch (_: Exception) { }
                pos = saved
                nesting = savedNesting   // restore nesting on backtrack — the body above already adjusted it on success
            } else {
                pos = savedForReceiver  // not a receiver function type — let parseQualifiedName handle it
            }
        }
        // Function type: (T, T, ...) -> R
        if (at(TokenType.LPAREN)) {
            val saved = pos
            val savedNesting = nesting
            try {
                advance(); nesting++; skipNL()
                val paramTypes = mutableListOf<TypeRef>()
                while (!at(TokenType.RPAREN) && !at(TokenType.EOF)) {
                    paramTypes += parseTypeRef()
                    if (at(TokenType.COMMA)) { advance(); skipNL() } else break
                }
                expect(TokenType.RPAREN); nesting--; skipNL()
                if (at(TokenType.ARROW)) {
                    advance(); skipNL()
                    val retType = parseTypeRef()
                    val nullable = if (at(TokenType.QUESTION)) { advance(); true } else false
                    return TypeRef("Function", nullable, emptyList(), paramTypes, retType)
                }
            } catch (_: Exception) { }
            // Not a function type — rollback (shouldn't normally happen in type position)
            pos = saved
            nesting = savedNesting
        }
        val name = parseQualifiedName()
        val typeArgs = if (at(TokenType.LT)) {
            advance(); nesting++; skipNL()
            val args = mutableListOf(parseTypeRefOrStar())
            while (at(TokenType.COMMA)) { advance(); skipNL(); args += parseTypeRefOrStar() }
            expect(TokenType.GT); nesting--
            args
        } else emptyList()
        val nullable = if (at(TokenType.QUESTION)) { advance(); true } else false
        return TypeRef(name, nullable, typeArgs, annotations = annotations)
    }

    // ═══════════════════════════ Annotations ════════════════════════

    private fun parseAnnotations(): List<Annotation> {
        val anns = mutableListOf<Annotation>()
        while (at(TokenType.AT)) {
            anns += parseAnnotation()
            skipNL()
        }
        return anns
    }

    private fun parseAnnotation(): Annotation {
        expect(TokenType.AT)
        val name = expectIdent()
        val args = if (at(TokenType.LPAREN)) {
            advance(); nesting++; skipNL()
            val args = mutableListOf<Expr>()
            while (!at(TokenType.RPAREN) && !at(TokenType.EOF)) {
                args += parseExpr()
                if (at(TokenType.COMMA)) { advance(); skipNL() } else break
            }
            expect(TokenType.RPAREN); nesting--
            args
        } else emptyList()
        return Annotation(name, args)
    }

    // ═══════════════════════════ Precedence table ════════════════════

    companion object {
        var INFIX_IDS: MutableSet<String> = mutableSetOf("until", "downTo", "step", "to", "and", "or", "xor", "shl", "shr", "ushr")
        // Levels — higher binds tighter
        const val PREC_DISJUNCTION  = 1   // ||
        const val PREC_CONJUNCTION  = 2   // &&
        const val PREC_EQUALITY     = 3   // == !=
        const val PREC_COMPARISON   = 4   // < > <= >=
        const val PREC_NAMED        = 5   // in  !in  is  !is
        const val PREC_ELVIS        = 6   // ?:
        const val PREC_INFIX_FN     = 7   // until  downTo  step
        const val PREC_RANGE        = 8   // ..
        const val PREC_ADDITIVE     = 9   // + -
        const val PREC_MULTIPLICATIVE = 10 // * / %
        const val PREC_AS           = 11  // as
    }

    private fun binaryPrec(): Int = when (cur().type) {
        TokenType.PIPE_PIPE     -> PREC_DISJUNCTION
        TokenType.AMP_AMP       -> PREC_CONJUNCTION
        TokenType.EQ_EQ, TokenType.REF_EQ, TokenType.EXCL_EQ -> PREC_EQUALITY
        TokenType.LT, TokenType.GT, TokenType.LT_EQ, TokenType.GT_EQ -> PREC_COMPARISON
        TokenType.IN            -> PREC_NAMED
        TokenType.IS            -> PREC_NAMED
        TokenType.EXCL          -> if (peek().type == TokenType.IN || peek().type == TokenType.IS) PREC_NAMED else -1
        TokenType.QUESTION_COLON -> PREC_ELVIS
        TokenType.IDENT         -> if (cur().value in INFIX_IDS) PREC_INFIX_FN else -1
        TokenType.DOT_DOT       -> PREC_RANGE
        TokenType.PLUS, TokenType.MINUS -> PREC_ADDITIVE
        TokenType.STAR, TokenType.SLASH, TokenType.PERCENT -> PREC_MULTIPLICATIVE
        TokenType.AS            -> PREC_AS
        else -> -1
    }

    // ═══════════════════════════ Helpers ══════════════════════════════

    private fun cur(): Token = tokens[pos]
    private fun peek(): Token = if (pos + 1 < tokens.size) tokens[pos + 1] else tokens.last()
    private fun at(type: TokenType): Boolean = cur().type == type
    private fun advance(): Token = tokens[pos].also { pos++ }

    private fun expect(type: TokenType): Token {
        if (!at(type)) error("Expected $type but got ${cur()}")
        return advance()
    }

    private fun expectIdent(): String {
        if (!at(TokenType.IDENT) && !at(TokenType.INIT)) error("Expected identifier but got ${cur()}")
        return advance().value
    }

    private fun parseQualifiedName(): String {
        val sb = StringBuilder(expectIdent())
        while (at(TokenType.DOT)) {
            if (peek().type == TokenType.IDENT) {
                advance(); sb.append('.').append(advance().value)
            } else if (peek().type == TokenType.STAR) {
                advance(); advance(); sb.append(".*")
                break
            } else break
        }
        return sb.toString()
    }

    /** Skip newlines (and semicolons) — significant only when nesting==0, but we
     *  always allow skipping them explicitly.  */
    private fun skipNL() {
        while (at(TokenType.NEWLINE) || at(TokenType.SEMICOLON)) advance()
    }

    private fun skipTerminator() {
        // consume at least one newline/semicolon if present (but don't require it)
        while (at(TokenType.NEWLINE) || at(TokenType.SEMICOLON)) advance()
    }

    /** True when the current token could be the start of an expression. */
    private fun atExprStart(): Boolean = when (cur().type) {
        TokenType.INT_LIT, TokenType.LONG_LIT, TokenType.UINT_LIT, TokenType.ULONG_LIT,
        TokenType.FLOAT_LIT, TokenType.DOUBLE_LIT,
        TokenType.STRING_LIT, TokenType.CHAR_LIT, TokenType.STR_TMPL_START,
        TokenType.TRUE, TokenType.FALSE, TokenType.NULL, TokenType.THIS,
        TokenType.IDENT, TokenType.LPAREN, TokenType.IF, TokenType.WHEN,
        TokenType.COLON_COLON,
        TokenType.MINUS, TokenType.EXCL, TokenType.PLUS_PLUS, TokenType.MINUS_MINUS,
        TokenType.LBRACE -> true
        else -> false
    }

    /*
    Parses a lambda expression: { [param1, param2, ... ->] statements }
    The parameter list before -> is optional. If absent, the lambda takes no named params
    (the body may still reference `it` if the expected type has one parameter).
    */
    private fun parseLambdaExpr(): LambdaExpr {
        expect(TokenType.LBRACE)
        nesting++
        skipNL()
        val params = mutableListOf<String>()
        val savedPos = pos
        try {
            while (at(TokenType.IDENT)) {
                params += advance().value
                skipNL()
                if (at(TokenType.COMMA)) { advance(); skipNL() } else break
            }
            if (at(TokenType.ARROW)) {
                advance(); skipNL()
            } else {
                pos = savedPos
                params.clear()
            }
        } catch (_: Exception) {
            pos = savedPos
            params.clear()
        }
        val stmts = mutableListOf<Stmt>()
        while (!at(TokenType.RBRACE) && !at(TokenType.EOF)) {
            skipNL()
            if (at(TokenType.RBRACE)) break
            stmts += parseStmt()
            skipTerminator()
            skipNL()
        }
        expect(TokenType.RBRACE)
        nesting--
        return LambdaExpr(params, stmts)
    }
}
