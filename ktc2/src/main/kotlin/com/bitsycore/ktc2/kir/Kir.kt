package com.bitsycore.ktc2.kir

/*
KIR — the ktc2 intermediate representation.

Typed and statement-oriented. Expressions (KExpr) are pure with respect to evaluation order:
anything with a side effect (a call, an assignment, control flow) is a statement, and its
result, if any, is named by a local. This is the form ownership analysis needs later, and it
makes C emission a direct print. KIR does not depend on the Kotlin compiler.
*/

// ==================
// MARK: Types
// ==================

/** Primitive kinds and their C spelling. */
enum class KPrim(val cName: String) {
	BOOLEAN("bool"),
	BYTE("int8_t"),
	SHORT("int16_t"),
	INT("int32_t"),
	LONG("int64_t"),
	CHAR("uint16_t"),
	FLOAT("float"),
	DOUBLE("double"),
	UBYTE("uint8_t"),
	USHORT("uint16_t"),
	UINT("uint32_t"),
	ULONG("uint64_t");

	val isFloating: Boolean get() = this == FLOAT || this == DOUBLE
	val isUnsigned: Boolean get() = this == UBYTE || this == USHORT || this == UINT || this == ULONG
}

sealed interface KType

data class KPrimType(val prim: KPrim) : KType

data object KUnitType : KType

data object KNothingType : KType

/** A `@Struct` class: a plain C struct passed by value. */
data class KStructType(val struct: KStruct) : KType

// ==================
// MARK: Declarations
// ==================

class KModule(
	val units: List<KUnit>,
	val entry: KFunction?,
)

/** One generated `.h` / `.c` pair: a class, or the top-level declarations of one file. */
class KUnit(
	val pkg: String,
	val name: String,
) {
	val functions = mutableListOf<KFunction>()
	val globals = mutableListOf<KGlobal>()
	var struct: KStruct? = null

	/** Assigns the globals whose initializer is not a constant; run once before `main`, in unit order. */
	var initializer: KFunction? = null

	/** Path of the pair relative to the C output root, without extension. */
	val path: String get() = if (pkg.isEmpty()) name else pkg.replace('.', '/') + "/" + name
}

class KStruct(
	val cName: String,
	val unit: KUnit,
) {
	val fields = mutableListOf<KField>()
}

class KField(val cName: String, val type: KType)

class KFunction(
	val cName: String,
	val unit: KUnit,
	val params: List<KLocal>,
	val returnType: KType,
) {
	val body = mutableListOf<KStmt>()
}

class KLocal(val cName: String, val type: KType)

/** A top-level property with storage. [constInit] is set when the initializer is a literal. */
class KGlobal(val cName: String, val type: KType, val unit: KUnit) {
	var constInit: KConst? = null
}

// ==================
// MARK: Expressions
// ==================

enum class KBinaryOp { ADD, SUB, MUL, DIV, REM, AND, OR, XOR, SHL, SHR, USHR, LT, LE, GT, GE, EQ, NE, CMP }

enum class KUnaryOp { NEG, NOT, INV }

sealed interface KExpr {
	val type: KType
}

/**
 * [value] is a Kotlin `Boolean`, `Char` or `Number`. For unsigned types it holds the same bits
 * in the signed type of that width.
 */
class KConst(val value: Any, override val type: KPrimType) : KExpr

data object KUnitValue : KExpr {
	override val type: KType get() = KUnitType
}

class KRead(val local: KLocal) : KExpr {
	override val type: KType get() = local.type
}

/** Both operands have type [operandType]; comparisons yield Boolean, `CMP` yields Int. */
class KBinary(val op: KBinaryOp, val left: KExpr, val right: KExpr, val operandType: KPrim, override val type: KType) : KExpr

class KUnary(val op: KUnaryOp, val operand: KExpr, override val type: KPrimType) : KExpr

class KConvert(val operand: KExpr, val from: KPrim, val to: KPrim) : KExpr {
	override val type: KType get() = KPrimType(to)
}

/** `condition ? whenTrue : whenFalse` — only built when both arms are pure. */
class KSelect(val condition: KExpr, val whenTrue: KExpr, val whenFalse: KExpr, override val type: KType) : KExpr

class KFieldRead(val target: KExpr, val field: KField) : KExpr {
	override val type: KType get() = this.field.type
}

/** A struct value built from one expression per field, in declaration order. */
class KStructNew(val struct: KStruct, val values: List<KExpr>) : KExpr {
	override val type: KType get() = KStructType(struct)
}

class KGlobalRead(val global: KGlobal) : KExpr {
	override val type: KType get() = global.type
}

/** A call to a side-effect-free runtime helper, or (as a [KEval]) to a runtime check. */
class KIntrinsic(val name: String, val arguments: List<KExpr>, override val type: KType) : KExpr

/** Not pure: only appears as the whole right-hand side of [KLet], [KSet], [KReturn] or [KEval]. */
class KCall(val function: KFunction, val arguments: List<KExpr>) : KExpr {
	override val type: KType get() = function.returnType
}

// ==================
// MARK: Statements
// ==================

sealed interface KStmt

class KLet(val local: KLocal, val init: KExpr?) : KStmt

class KSet(val local: KLocal, val value: KExpr) : KStmt

class KGlobalSet(val global: KGlobal, val value: KExpr) : KStmt

class KEval(val expr: KExpr) : KStmt

class KIf(val condition: KExpr, val thenBlock: List<KStmt>, val elseBlock: List<KStmt>) : KStmt

/**
 * Infinite loop left by [KBreak]. [latch] runs after [body] on every iteration and is where
 * [KContinue] resumes.
 */
class KLoop(val id: Int, val body: List<KStmt>, val latch: List<KStmt>) : KStmt

class KBreak(val loopId: Int) : KStmt

class KContinue(val loopId: Int) : KStmt

class KReturn(val value: KExpr?) : KStmt

/** `print` / `println` of a sequence of literal and primitive parts. */
class KPrint(val parts: List<KPrintPart>, val newline: Boolean) : KStmt

sealed interface KPrintPart

class KPrintText(val text: String) : KPrintPart

class KPrintValue(val value: KExpr) : KPrintPart
