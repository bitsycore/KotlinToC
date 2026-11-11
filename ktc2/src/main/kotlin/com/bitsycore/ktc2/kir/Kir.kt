package com.bitsycore.ktc2.kir

/*
KIR — the ktc2 intermediate representation.

Typed and statement-oriented. Expressions (KExpr) are pure with respect to evaluation order:
anything with a side effect (a call, an allocation, an assignment, control flow) is a
statement, and its result, if any, is named by a local. This is the form ownership analysis
needs, and it makes C emission a direct print. KIR does not depend on the Kotlin compiler.

The importer produces KIR without any reference counting. `ArcInsert` then adds KRetain /
KRelease and the scope-exit releases.
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

/** A nullable primitive (`Int?`): a value holding a presence flag and the primitive. No heap. */
data class KOptType(val prim: KPrim) : KType

/** A `@Struct` class: a plain C struct passed by value. */
data class KStructType(val struct: KStruct) : KType

/**
 * A counted reference: a C pointer to an object starting with `ktc2_Obj`. Nullability is not
 * part of the type — a nullable reference is the same pointer, possibly NULL.
 */
sealed interface KRefType : KType

data object KStringType : KRefType

/** A reference statically known to be [cls] or a subclass. */
data class KClassType(val cls: KClass) : KRefType

/** `Any` or an interface: any object. */
data object KObjType : KRefType

/** `Array<T>` or a primitive array, by element type. */
data class KArrayType(val element: KType) : KRefType

// ==================
// MARK: Declarations
// ==================

class KModule(
	val units: List<KUnit>,
	val entry: KFunction?,
	val classes: List<KClass>,
)

/** One generated `.h` / `.c` pair: a class, or the top-level declarations of one file. */
class KUnit(
	val pkg: String,
	val name: String,
) {
	val functions = mutableListOf<KFunction>()
	val globals = mutableListOf<KGlobal>()
	val statics = mutableListOf<KStatic>()
	var struct: KStruct? = null
	var cls: KClass? = null

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

/**
 * [owner] is the class declaring the field, or null for a `@Struct` field. [isImmutable] is set
 * for a field that is never reassigned once its object is constructed (a `val`).
 */
class KField(val cName: String, val type: KType, val owner: KClass? = null, val isImmutable: Boolean = false) {
	/**
	 * Inline storage: the object (`@Value`, [inlineSize] = 0) or the array of that many elements
	 * (`@Size(N)`) is embedded in the owner instead of being referred to. -1 for an ordinary field.
	 */
	var inlineSize: Int = -1

	val isInline: Boolean get() = inlineSize >= 0
}

/**
 * A reference class. Its C struct starts with the superclass struct (or `ktc2_Obj`), so a
 * pointer to it is also a pointer to every ancestor.
 */
class KClass(
	val cName: String,
	val unit: KUnit,
	val superClass: KClass?,
	/** Kotlin simple name, used by the default `toString` and the leak report. */
	val name: String,
) {
	val fields = mutableListOf<KField>()
	val interfaces = LinkedHashSet<KInterface>()

	/** Set when the program constructs this class; only such classes get a type id. */
	var isInstantiated = false

	/** Set when a `@Value` field holds this class inline: it then gets copy and assign helpers. */
	var isUsedInline = false

	fun isSubclassOf(inOther: KClass): Boolean = this === inOther || superClass?.isSubclassOf(inOther) == true

	fun implements(inInterface: KInterface): Boolean =
		interfaces.any { it === inInterface || inInterface in it.supers } || superClass?.implements(inInterface) == true
}

/** A static, immortal instance of [cls]: an `object`, or an enum entry. Defined in [unit]. */
class KStatic(val cName: String, val cls: KClass, val unit: KUnit)

/** An interface, identified for `is` checks. [supers] are the interfaces it extends, transitively. */
class KInterface(val name: String) {
	val supers = LinkedHashSet<KInterface>()
}

class KFunction(
	val cName: String,
	val unit: KUnit,
	val params: List<KLocal>,
	val returnType: KType,
) {
	val body = mutableListOf<KStmt>()

	/** Implemented in the C runtime; only called, never emitted. A reference result is owned by the caller. */
	var isExternal = false

	/**
	 * When non-null this function is a virtual dispatcher: it switches on the type of its first
	 * parameter and calls the implementation for that class. [dispatchDefault] handles the rest.
	 */
	var dispatch: MutableList<Pair<KClass, KFunction>>? = null
	var dispatchDefault: KFunction? = null
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

class KNull(override val type: KType) : KExpr

/** A string literal: a static, immortal string object. */
class KStringLit(val text: String) : KExpr {
	override val type: KType get() = KStringType
}

class KRead(val local: KLocal) : KExpr {
	override val type: KType get() = local.type
}

class KGlobalRead(val global: KGlobal) : KExpr {
	override val type: KType get() = global.type
}

/** A static instance (an `object` or an enum entry). */
class KStaticRead(val static: KStatic) : KExpr {
	override val type: KType get() = KClassType(static.cls)
}

/** Both operands have type [operandType]; comparisons yield Boolean, `CMP` yields Int. */
class KBinary(val op: KBinaryOp, val left: KExpr, val right: KExpr, val operandType: KPrim, override val type: KType) : KExpr

/** Pointer identity of two references (`===`, or `==` against null). */
class KRefEquals(val left: KExpr, val right: KExpr, val negated: Boolean = false) : KExpr {
	override val type: KType get() = KPrimType(KPrim.BOOLEAN)
}

class KUnary(val op: KUnaryOp, val operand: KExpr, override val type: KPrimType) : KExpr

class KConvert(val operand: KExpr, val from: KPrim, val to: KPrim) : KExpr {
	override val type: KType get() = KPrimType(to)
}

/** Wraps a primitive into its nullable form. */
class KOptSome(val operand: KExpr, override val type: KOptType) : KExpr

/** True when a nullable primitive holds a value. */
class KOptHas(val operand: KExpr) : KExpr {
	override val type: KType get() = KPrimType(KPrim.BOOLEAN)
}

/** The primitive inside a nullable primitive known to hold one. */
class KOptValue(val operand: KExpr) : KExpr {
	override val type: KType get() = KPrimType((operand.type as KOptType).prim)
}

/** `condition ? whenTrue : whenFalse` — only built when both arms are pure. */
class KSelect(val condition: KExpr, val whenTrue: KExpr, val whenFalse: KExpr, override val type: KType) : KExpr

/** Reads a field of a struct value or of the object a class reference points to. */
class KFieldRead(val target: KExpr, val field: KField) : KExpr {
	/**
	 * For an inline field the read yields a pointer into the owner, valid while the owner lives.
	 * `EscapeAnalysis` sets this when that reference is kept beyond its block: the value is then
	 * copied to the heap (the "autobox" of an inline value).
	 */
	var needsCopy: Boolean = false

	override val type: KType get() = this.field.type
}

/** A struct value built from one expression per field, in declaration order. */
class KStructNew(val struct: KStruct, val values: List<KExpr>) : KExpr {
	override val type: KType get() = KStructType(struct)
}

/** Reinterprets a reference as another reference type (upcast, or a checked downcast). */
class KRefCast(val operand: KExpr, override val type: KType) : KExpr

/** What an `is` check tests against. */
sealed interface KTypeTarget

class KClassTarget(val cls: KClass) : KTypeTarget

class KInterfaceTarget(val iface: KInterface) : KTypeTarget

data object KStringTarget : KTypeTarget

/** A boxed primitive (`x is Int` on an `Any`). */
class KBoxTarget(val prim: KPrim) : KTypeTarget

/** `operand is target`; false for null. */
class KInstanceOf(val operand: KExpr, val target: KTypeTarget) : KExpr {
	override val type: KType get() = KPrimType(KPrim.BOOLEAN)
}

/** `array[index]`, bounds-checked. */
class KIndexRead(val array: KExpr, val index: KExpr, override val type: KType) : KExpr

/**
 * A call to a runtime helper. Pure ones may appear anywhere; an impure one (allocation, check,
 * I/O) only as the whole right-hand side of a statement, like [KCall], and a reference it
 * returns is owned by the caller.
 */
class KIntrinsic(val name: String, val arguments: List<KExpr>, override val type: KType, val isPure: Boolean = true) : KExpr

/**
 * Allocates a zeroed array of [length] elements. Not pure; the caller owns the result.
 * [onStack] is set by `EscapeAnalysis` for a small array of constant size that cannot outlive its block.
 */
class KArrayNew(val length: KExpr, override val type: KArrayType) : KExpr {
	var onStack: Boolean = false

	/** Source position ("File.kt:12"), for the heap report. */
	var origin: String? = null
}

/**
 * Allocates a zeroed instance of [cls]. Not pure; the caller owns the result.
 * [onStack] is set by `EscapeAnalysis` when the object cannot outlive the block it is created
 * in: it then lives in that block's stack frame instead of the heap.
 */
class KAlloc(val cls: KClass) : KExpr {
	var onStack: Boolean = false

	/** Source position ("File.kt:12"), for the heap report. */
	var origin: String? = null
	override val type: KType get() = KClassType(cls)
}

/** Not pure: only appears as the whole right-hand side of a statement. A reference result is owned by the caller. */
class KCall(val function: KFunction, val arguments: List<KExpr>) : KExpr {
	override val type: KType get() = function.returnType
}

/** Added by `ArcInsert`: increments the count of [operand] (null-safe) and yields it. */
class KRetain(val operand: KExpr) : KExpr {
	override val type: KType get() = operand.type
}

/** True for the expressions that may only be the whole right-hand side of a statement. */
fun KExpr.isImpure(): Boolean = when (this) {
	is KCall, is KAlloc, is KArrayNew -> true
	is KIntrinsic -> !isPure
	is KRefCast -> operand.isImpure()
	is KFieldRead -> needsCopy
	else -> false
}

// ==================
// MARK: Statements
// ==================

sealed interface KStmt

class KLet(val local: KLocal, val init: KExpr?) : KStmt

/** [releasesOld] is set by `ArcInsert` when the previous reference in the slot must be released. */
class KSet(val local: KLocal, val value: KExpr, val releasesOld: Boolean = false) : KStmt

class KGlobalSet(val global: KGlobal, val value: KExpr, val releasesOld: Boolean = false) : KStmt

class KFieldSet(val target: KExpr, val field: KField, val value: KExpr, val releasesOld: Boolean = false) : KStmt

class KIndexSet(val array: KExpr, val index: KExpr, val value: KExpr, val releasesOld: Boolean = false) : KStmt

class KEval(val expr: KExpr) : KStmt

class KIf(val condition: KExpr, val thenBlock: List<KStmt>, val elseBlock: List<KStmt>) : KStmt

/**
 * Infinite loop left by [KBreak]. [latch] runs after [body] on every iteration and is where
 * [KContinue] resumes. Body and latch form one scope.
 */
class KLoop(val id: Int, val body: List<KStmt>, val latch: List<KStmt>) : KStmt

class KBreak(val loopId: Int) : KStmt

class KContinue(val loopId: Int) : KStmt

class KReturn(val value: KExpr?) : KStmt

/** `print` / `println` of a sequence of literal and value parts (primitives or strings). */
class KPrint(val parts: List<KPrintPart>, val newline: Boolean) : KStmt

/**
 * Declares [target] and assigns it a new string made of [parts].
 * [onStack] is set by `EscapeAnalysis` when the string cannot outlive its block: it is then
 * built in a buffer of that block when it fits.
 */
class KConcat(val target: KLocal, val parts: List<KPrintPart>) : KStmt {
	var onStack: Boolean = false

	/** Source position ("File.kt:12"), for the heap report. */
	var origin: String? = null
}

/** Added by `ArcInsert`: decrements the count of [operand] (null-safe), destroying it at zero. */
class KRelease(val operand: KExpr) : KStmt

sealed interface KPrintPart

class KPrintText(val text: String) : KPrintPart

class KPrintValue(val value: KExpr) : KPrintPart
