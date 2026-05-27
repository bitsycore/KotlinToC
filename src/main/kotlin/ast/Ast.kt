package com.bitsycore.ktc.ast

// ═══════════════════════════ Type Reference ═══════════════════════════

data class TypeRef(
    val name: String,
    val nullable: Boolean = false,
    val typeArgs: List<TypeRef> = emptyList(),
    val funcParams: List<TypeRef>? = null,   // non-null → this is a function type: (params) -> returnType
    val funcReturn: TypeRef? = null,           // return type when funcParams != null
    val funcReceiver: TypeRef? = null,         // receiver type for T.(params) -> R
    val annotations: List<Annotation> = emptyList()
)

// ═══════════════════════════ File ═══════════════════════════

/* A C #include directive emitted at the top of every .c file generated from the annotated .kt file.
angle = true  → #include <path>   (@file:cInclude)
angle = false → #include "path"   (@file:cIncludeRelative) */
data class CInclude(val path: String, val angle: Boolean) {
    fun toCDirective(): String = if (angle) "#include <$path>" else "#include \"$path\""
    }

data class KtFile(
    val pkg: String?,
    val imports: List<String>,
    val decls: List<Decl>,
    val sourceFile: String = "",
    val documentationOnly: Boolean = false,  // true when file starts with @file:DocumentationOnly
    val cIncludes: List<CInclude> = emptyList()  // from @file:cInclude / @file:cIncludeRelative
)

// ═══════════════════════════ Declarations ═══════════════════════════

sealed class Decl

data class FunDecl(
    val name: String,
    val params: List<Param>,
    val returnType: TypeRef?,
    val body: Block?,
    val receiver: TypeRef? = null,
    val typeParams: List<String> = emptyList(),
    val isOperator: Boolean = false,
    val isPrivate: Boolean = false,
    val isInline: Boolean = false,
    val isOverride: Boolean = false,
    val isInfix: Boolean = false,
    val isTailrec: Boolean = false,
    val annotations: List<Annotation> = emptyList()  // declaration-level annotations (e.g. @DocumentationOnly)
) : Decl()

data class ClassDecl(
    val name: String,
    val isData: Boolean,
    val ctorParams: List<CtorParam>,
    val members: List<Decl>,
    val initBlocks: List<Block>,
    val superInterfaces: List<TypeRef> = emptyList(),
    val typeParams: List<String> = emptyList(),
    val secondaryCtors: List<SecondaryCtor> = emptyList(),
    val annotations: List<Annotation> = emptyList(),
    val isValue: Boolean = false,
    val isSealed: Boolean = false
) : Decl()

data class EnumDecl(
    val name: String,
    val entries: List<String>
) : Decl()

data class InterfaceDecl(
    val name: String,
    val methods: List<FunDecl>,
    val properties: List<PropDecl> = emptyList(),
    val typeParams: List<String> = emptyList(),
    val superInterfaces: List<TypeRef> = emptyList(),
    val nestedClasses: List<ClassDecl> = emptyList(),
    val isSealed: Boolean = false
) : Decl()

data class ObjectDecl(
    val name: String,
    val members: List<Decl>,
    val annotations: List<Annotation> = emptyList(),
    val superInterfaces: List<TypeRef> = emptyList()
) : Decl()

/* `typealias Name = Target`. Resolved by substitution during type resolution
— no codegen emission. */
data class TypeAliasDecl(
    val name:   String,
    val target: TypeRef
) : Decl()

data class PropDecl(
    val name: String,
    val type: TypeRef?,
    val init: Expr?,
    val mutable: Boolean,
    val line: Int = 0,
    val isPrivate: Boolean = false,
    val isPrivateSet: Boolean = false,
    val annotations: List<Annotation> = emptyList(),
    val receiver: TypeRef? = null,     // extension property: val Receiver.name
    val getter: Expr? = null,          // get() = expr
    val setterParam: String? = null,   // set(param)
    val setterBody: Block? = null,     // set(param) { body }
    val isInline: Boolean = false,     // inline val: getter expanded at access site (extension props only)
    val lazyInit: Block? = null        // by lazy { body }
) : Decl()

// ═══════════════════════════ Parameters ═══════════════════════════

data class Param(
    val name: String,
    val type: TypeRef,
    val default: Expr? = null,
    val isVararg: Boolean = false
)

data class CtorParam(
    val name: String,
    val type: TypeRef,
    val default: Expr? = null,
    val isVal: Boolean = false,
    val isVar: Boolean = false,
    val isPrivate: Boolean = false
)

data class SecondaryCtor(
    val params: List<Param>,
    val delegation: CallExpr,  // this(args...) call to delegate to primary
    val body: Block
)

// ═══════════════════════════ Block ═══════════════════════════

data class Block(val stmts: List<Stmt>)

// ═══════════════════════════ Statements ═══════════════════════════

sealed class Stmt {
    var line: Int = 0
    var col: Int = 0
}

data class ExprStmt(val expr: Expr) : Stmt()

data class VarDeclStmt(
    val name: String,
    val type: TypeRef?,
    val init: Expr?,
    val mutable: Boolean,
    val lazyInit: Block? = null
) : Stmt()

/* Destructuring decl: `val (a, b, ...) = expr` / `var (...) = expr`.
A name of "_" means the slot is discarded. Codegen evaluates [init] once into a
local, then binds each name to the corresponding positional ctor-param field. */
data class DestructuringDeclStmt(
    val names:   List<String>,
    val init:    Expr,
    val mutable: Boolean
) : Stmt()

data class AssignStmt(
    val target: Expr,
    val op: String,        // = += -= *= /= %=
    val value: Expr
) : Stmt()

data class ReturnStmt(val value: Expr?) : Stmt()
/* For-loop with optional destructuring of each element. When destructureNames
is empty, varName binds the element directly (idiomatic for-each).
When destructureNames is non-empty, the iterator element is decomposed into
those names via componentN() / ctor-param field access — varName is then
the implementation-detail temp holding the element. Both forms can carry
the same body. */
data class ForStmt(val varName: String, val iter: Expr, val body: Block, val destructureNames: List<String> = emptyList()) : Stmt()
data class WhileStmt(val cond: Expr, val body: Block) : Stmt()
data class DoWhileStmt(val body: Block, val cond: Expr) : Stmt()
class BreakStmt : Stmt()
class ContinueStmt : Stmt()
data class DeferStmt(val body: Block) : Stmt()
data class CommentStmt(val text: String) : Stmt()

// ═══════════════════════════ Expressions ═══════════════════════════

sealed class Expr

data class IntLit(val value: Long, val hex: Boolean = false) : Expr()
data class LongLit(val value: Long, val hex: Boolean = false) : Expr()
data class UIntLit(val value: Long, val hex: Boolean = false) : Expr()
data class ULongLit(val value: ULong, val hex: Boolean = false) : Expr()
data class DoubleLit(val value: Double) : Expr()
data class FloatLit(val value: Double) : Expr()
data class BoolLit(val value: Boolean) : Expr()
data class CharLit(val value: Char) : Expr()
data class StrLit(val value: String) : Expr()
data class StrTemplateExpr(val parts: List<StrPart>) : Expr()
object NullLit : Expr()
data class NameExpr(val name: String) : Expr()
object ThisExpr : Expr()

data class BinExpr(val left: Expr, val op: String, val right: Expr) : Expr()
data class PrefixExpr(val op: String, val expr: Expr) : Expr()
data class PostfixExpr(val expr: Expr, val op: String) : Expr()

data class CallExpr(val callee: Expr, val args: List<Arg>, val typeArgs: List<TypeRef> = emptyList()) : Expr()
data class DotExpr(val obj: Expr, val name: String) : Expr()
data class SafeDotExpr(val obj: Expr, val name: String) : Expr()
data class IndexExpr(val obj: Expr, val index: Expr) : Expr()

data class IfExpr(val cond: Expr, val then: Block, val els: Block?) : Expr()
data class WhenExpr(val subject: Expr?, val branches: List<WhenBranch>) : Expr()

data class NotNullExpr(val expr: Expr) : Expr()   // !!
data class ElvisExpr(val left: Expr, val right: Expr) : Expr()   // ?:
data class IsCheckExpr(val expr: Expr, val type: TypeRef, val negated: Boolean) : Expr()
data class CastExpr(val expr: Expr, val type: TypeRef, val safe: Boolean = false) : Expr()
data class FunRefExpr(val name: String) : Expr()   // ::functionName
data class ClassRefExpr(val typeName: String) : Expr()   // Type::class
data class LambdaExpr(val params: List<String>, val body: List<Stmt>) : Expr()
data class ObjectExpr(val syntheticName: String) : Expr()  // object : Interface { ... }

// ═══════════════════════════ Helpers ═══════════════════════════

data class Annotation(
    val name: String,
    val args: List<Expr> = emptyList()
)

data class Arg(val name: String? = null, val expr: Expr, val isSpread: Boolean = false)

sealed class StrPart
data class LitPart(val text: String) : StrPart()
data class ExprPart(val expr: Expr) : StrPart()

data class WhenBranch(val conds: List<WhenCond>?, val body: Block)   // conds==null → else

sealed class WhenCond
data class ExprCond(val expr: Expr) : WhenCond()
data class IsCond(val type: TypeRef, val negated: Boolean = false) : WhenCond()
data class InCond(val expr: Expr, val negated: Boolean = false) : WhenCond()

// ═══════════════════════════ TypeRef extensions ════════════════════════════

/* True when this TypeRef carries a @Size(N) annotation. */
fun TypeRef.hasSizeAnnotation(): Boolean = annotations.any { it.name == "Size" }

/*
Returns the integer value of the @Size(N) annotation, or null if absent / malformed.
Supports both Int and Long literals as the annotation argument.
*/
fun TypeRef.getSizeAnnotation(): Int? {
    val vAnn = annotations.find { it.name == "Size" } ?: return null
    if (vAnn.args.isEmpty()) return null
    return when (val vArg = vAnn.args[0]) {
        is IntLit  -> vArg.value.toInt()
        is LongLit -> vArg.value.toInt()
        else       -> null
    }
}

/* True when this TypeRef is a @Size(N)-annotated String — a fixed-capacity string buffer. */
fun TypeRef.isSizedString(): Boolean = hasSizeAnnotation() && name == "String"

/* True when this TypeRef is a reference/pointer type — either Ref<T> syntax or internal @Ptr annotation. */
fun TypeRef.isRefType(): Boolean = name == "Ref" || annotations.any { it.name == "Ptr" }

/* Nullable accounting for Ref<T?> where nullability sits on the inner type arg, not the outer TypeRef. */
fun TypeRef.isEffectivelyNullable(): Boolean = nullable || (name == "Ref" && typeArgs.firstOrNull()?.nullable == true)

/* Normalize Ref<T> → @Ptr T so downstream code only sees the @Ptr form. */
fun TypeRef.normalizeRef(): TypeRef {
	if (name != "Ref" || typeArgs.isEmpty()) return this
	val inner = typeArgs[0]
	return TypeRef(inner.name, inner.nullable || nullable, inner.typeArgs,
		inner.funcParams, inner.funcReturn, inner.funcReceiver,
		inner.annotations + Annotation("Ptr"))
}
