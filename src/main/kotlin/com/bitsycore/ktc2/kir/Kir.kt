package com.bitsycore.ktc2.kir

/*
KIR - the ktc2 intermediate representation.

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

/**
 * A C address (`void*`): what `kotlinx.cinterop` pointers and the objects they point at are.
 * Not counted, never owned; null is NULL.
 */
data object KPtrType : KType

/** A `@Struct` class: a plain C struct passed by value. */
data class KStructType(val struct: KStruct) : KType

/**
 * A counted reference: a C pointer to an object starting with `ktc2_Obj`. Nullability is not
 * part of the type - a nullable reference is the same pointer, possibly NULL.
 */
sealed interface KRefType : KType

data object KStringType : KRefType

/** A reference statically known to be [cls] or a subclass. */
data class KClassType(val cls: KClass) : KRefType

/** `Any` or an interface: any object. */
data object KObjType : KRefType

/** `Array<T>` or a primitive array, by element type. */
data class KArrayType(val element: KType) : KRefType

/** True for a type whose values are counted: a reference, or a struct that holds some. */
fun KType.isCounted(): Boolean = this is KRefType || (this as? KStructType)?.struct?.holdsReferences == true

// ==================
// MARK: Declarations
// ==================

class KModule(
	val units: List<KUnit>,
	val entry: KFunction?,
	val classes: List<KClass>,
) {
	/** Set when the program can start a thread: the runtime then counts references atomically. */
	var usesThreads: Boolean = false

	/**
	 * The platform layer's functions that throw the exceptions of the language's own checks
	 * (index out of bounds, division by zero), by name. Empty when the program has no such
	 * check or is built with `--checks=abort`: those failures then end the program.
	 */
	var throwers: Map<String, KFunction> = emptyMap()
}

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

	/**
	 * True when a value of this struct carries references (a String, an object, an array),
	 * directly or in a struct inside it. Such a value owns what it refers to: copying it
	 * retains, dropping it releases.
	 */
	val holdsReferences: Boolean
		get() = fields.any { vField -> vField.type is KRefType || (vField.type as? KStructType)?.struct?.holdsReferences == true }

	/**
	 * The class a value of this struct is wrapped in where an object is required: seen as
	 * `Any` or an interface, or nullable. One field, `value`. Null until the program needs it.
	 */
	var box: KClass? = null
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

	/**
	 * Set for a `@Weak` property, to the type the program sees. The field itself ([type] is then
	 * the plain object type) holds the weak cell of the target, not the target.
	 */
	var weakType: KType? = null

	/** Set for an `@Acyclic` property: the cycle lint does not follow it. */
	var isAcyclic: Boolean = false
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

	/**
	 * Set by `ArcInsert` on the `__m` form of a dispatcher (see `BorrowedReturns`): the result of
	 * [dispatchDefault] is owned, and is also stored through the last parameter, the address of
	 * the variable of the caller that is to release it.
	 */
	var keepsDefault = false
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

/** `condition ? whenTrue : whenFalse` - only built when both arms are pure. */
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

/** The zero value of [type]: what a function returns when it leaves with an exception. */
class KZero(override val type: KType) : KExpr

/** `array[index]`, bounds-checked unless [isChecked] says the index was tested before the statement. */
class KIndexRead(val array: KExpr, val index: KExpr, override val type: KType) : KExpr {
	var isChecked: Boolean = false
}

/**
 * A call to a runtime helper. Pure ones may appear anywhere; an impure one (allocation, check,
 * I/O) only as the whole right-hand side of a statement, like [KCall], and a reference it
 * returns is owned by the caller.
 */
class KIntrinsic(val name: String, val arguments: List<KExpr>, override val type: KType, val isPure: Boolean = true) : KExpr {
	/**
	 * C interop: when set, the C text to emit instead of a call to [name], with `$0`, `$1`…
	 * standing for the arguments.
	 */
	var template: String? = null

	/** C interop: the headers the text needs, as written after `#include`. */
	var headers: List<String> = emptyList()

	/** C interop: generated functions the text names, whose declarations it needs. */
	var functions: List<KFunction> = emptyList()

	/** C interop: the C function the text names, to be written into the file that uses it. */
	var trampoline: KTrampoline? = null
}

/**
 * A C function that C code calls with structs by value, standing in front of [target], which
 * takes and returns them as `CValue`s: it copies each struct argument into a `CValue` for the
 * call, and the returned `CValue` out into a struct.
 *
 * @property paramSpellings for each parameter, how C names its struct type; null for a parameter passed as it is
 * @property resultSpelling the same for the result
 */
class KTrampoline(val name: String, val target: KFunction, val paramSpellings: List<String?>, val resultSpelling: String?)

/**
 * Allocates a zeroed array of [length] elements. Not pure; the caller owns the result.
 * [onStack] is set by `EscapeAnalysis` for an array that cannot outlive its block: a small one of
 * constant size, or one whose size is known only at run time (it then goes to the heap when it is large).
 */
class KArrayNew(val length: KExpr, override val type: KArrayType) : KExpr {
	var onStack: Boolean = false

	/** The annotation (`@Size`) by which the program requires this array to be on the stack; null when it is the compiler's choice. */
	var forcedBy: String? = null

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

	/** The annotation (`@Value`) by which the program requires this object to be on the stack; null when it is the compiler's choice. */
	var forcedBy: String? = null

	/** Source position ("File.kt:12"), for the heap report. */
	var origin: String? = null
	override val type: KType get() = KClassType(cls)
}

/** Not pure: only appears as the whole right-hand side of a statement. A reference result is owned by the caller. */
class KCall(var function: KFunction, var arguments: List<KExpr>) : KExpr {
	override val type: KType get() = function.returnType
}

/**
 * Room for an instance of [cls] in the stack frame of the enclosing block, zeroed and with
 * its header set: what a caller hands to a function that builds its result in place. Added by
 * `EscapeAnalysis`.
 */
class KStackSlot(val cls: KClass) : KExpr {
	override val type: KType get() = KClassType(cls)
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

/** The expressions [this] is made of, in evaluation order. */
fun KExpr.children(): List<KExpr> = when (this) {
	is KBinary -> listOf(left, right)
	is KRefEquals -> listOf(left, right)
	is KUnary -> listOf(operand)
	is KConvert -> listOf(operand)
	is KOptSome -> listOf(operand)
	is KOptHas -> listOf(operand)
	is KOptValue -> listOf(operand)
	is KSelect -> listOf(condition, whenTrue, whenFalse)
	is KFieldRead -> listOf(target)
	is KStructNew -> values
	is KRefCast -> listOf(operand)
	is KInstanceOf -> listOf(operand)
	is KIndexRead -> listOf(array, index)
	is KIntrinsic -> arguments
	is KArrayNew -> listOf(length)
	is KCall -> arguments
	is KRetain -> listOf(operand)
	else -> emptyList()
}

/** True when [inTest] holds for this expression or one inside it. */
fun KExpr.anyNode(inTest: (KExpr) -> Boolean): Boolean = inTest(this) || children().any { it.anyNode(inTest) }

/**
 * True for an operation Kotlin defines to fail with an exception: indexing an array or a
 * string, and an integer division whose divisor is not a constant other than zero.
 */
fun KExpr.isRuntimeCheck(): Boolean = when (this) {
	is KIndexRead -> !isChecked
	is KBinary -> (op == KBinaryOp.DIV || op == KBinaryOp.REM) && !operandType.isFloating &&
		(right as? KConst)?.value.let { it == null || (it as? Number)?.toLong() == 0L }
	is KIntrinsic -> name == "ktc2_str_get"
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

class KIndexSet(val array: KExpr, val index: KExpr, val value: KExpr, val releasesOld: Boolean = false) : KStmt {
	/** Set when the index was tested before the statement: the store is then emitted unchecked. */
	var isChecked: Boolean = false
}

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

/** `throw value`. Replaced by `ExceptionLowering`. */
class KThrow(val value: KExpr) : KStmt

/**
 * The protected part of a `try`: when an exception is thrown inside [body], control continues
 * after the region with the exception parked in the runtime. Replaced by `ExceptionLowering`.
 */
class KTryRegion(val id: Int, val body: List<KStmt>) : KStmt

/** Added by `ArcInsert`: decrements the count of [operand] (null-safe), destroying it at zero. */
class KRelease(val operand: KExpr) : KStmt

/** The expressions a statement evaluates itself (not those of the blocks inside it), in order. */
fun KStmt.ownExpressions(): List<KExpr> = when (this) {
	is KLet -> listOfNotNull(init)
	is KSet -> listOf(value)
	is KGlobalSet -> listOf(value)
	is KFieldSet -> listOf(target, value)
	is KIndexSet -> listOf(array, index, value)
	is KEval -> listOf(expr)
	is KIf -> listOf(condition)
	is KReturn -> listOfNotNull(value)
	is KPrint -> parts.mapNotNull { (it as? KPrintValue)?.value }
	is KConcat -> parts.mapNotNull { (it as? KPrintValue)?.value }
	is KThrow -> listOf(value)
	is KRelease -> listOf(operand)
	is KLoop, is KBreak, is KContinue, is KTryRegion -> emptyList()
}

/** True when [inTest] holds for a statement of [this] block or of a block nested in it. */
fun List<KStmt>.anyStatement(inTest: (KStmt) -> Boolean): Boolean = any { vStatement ->
	inTest(vStatement) || when (vStatement) {
		is KIf -> vStatement.thenBlock.anyStatement(inTest) || vStatement.elseBlock.anyStatement(inTest)
		is KLoop -> vStatement.body.anyStatement(inTest) || vStatement.latch.anyStatement(inTest)
		is KTryRegion -> vStatement.body.anyStatement(inTest)
		else -> false
	}
}

sealed interface KPrintPart

class KPrintText(val text: String) : KPrintPart

class KPrintValue(val value: KExpr) : KPrintPart
