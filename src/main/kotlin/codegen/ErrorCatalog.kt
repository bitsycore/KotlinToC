package com.bitsycore.ktc.codegen

object ErrorCatalog {

	data class Entry(val code: String, val title: String, val explanation: String)

	private val entries = listOf(
		// ── Type errors ──────────────────────────────────────────
		Entry("E001", "Unknown type",
			"""
			The type name used is not recognized. KTC supports primitives (Int, Long,
			Float, Double, Boolean, Byte, Short, Char, UInt, ULong, UByte, UShort),
			String, Array<T>, RawArray<T>, Ref<T>, and user-defined classes, enums,
			interfaces, and objects.

			Check spelling. If it's a class from another package, make sure the
			dependency is listed in module.ktc.toml.
			""".trimIndent()),
		Entry("E002", "Typealias cycle",
			"""
			A chain of typealias declarations forms a cycle:
			  typealias A = B
			  typealias B = A    // cycle

			Break the cycle by making one of the aliases point to a concrete type.
			""".trimIndent()),
		Entry("E003", "Duplicate typealias",
			"""
			The same typealias name is declared more than once with different target
			types. Each typealias name must resolve to exactly one target.
			""".trimIndent()),

		// ── Struct layout ────────────────────────────────────────
		Entry("E010", "Self-referential class layout",
			"""
			A class has a property of its own type, which would require infinite
			struct size in C:
			  class Node(val next: Node)  // error

			Use an indirection — Ref<Node>, Array<Node>, or RawArray<Node>:
			  class Node(val next: Ref<Node?>)  // OK
			""".trimIndent()),
		Entry("E011", "Raw array on class property",
			"""
			Class properties cannot have a bare RawArray<T> type because the class
			layout needs a known size. Use Ref<Array<T>> (heap-backed with length)
			or @Size(N) Array<T> (fixed-size, stack-allocated).
			""".trimIndent()),

		// ── Return type ──────────────────────────────────────────
		Entry("E020", "Cannot return value-type String",
			"""
			String is a { ptr, len } view struct whose buffer lives on the caller's
			stack (from alloca). Returning it from a non-inline function would leave
			the pointer dangling.

			Options:
			  - Mark the function 'inline' (body expands at the call site)
			  - Return Ref<String> (heap-allocated)
			  - Return @Size(N) String (fixed-size struct copy)
			  - Return a string literal (points at .rodata, safe)
			""".trimIndent()),
		Entry("E021", "Cannot return value-type Any",
			"""
			Any is a tagged union struct whose concrete data may reference stack
			memory. Returning it by value would dangle. Use Ref<Any> instead.
			""".trimIndent()),
		Entry("E022", "Cannot return raw array",
			"""
			Array<T> / IntArray / RawArray<T> are backed by stack or heap buffers.
			Returning a bare array from a non-inline function would dangle.

			Options:
			  - Return Ref<Array<T>> (heap-backed)
			  - Return @Size(N) Array<T> (fixed-size struct, returned by value)
			  - Mark the function 'inline'
			""".trimIndent()),
		Entry("E023", "Cannot return lambda from non-inline function",
			"""
			KTC lambdas are inline-only — there is no closure heap allocation. A
			function that returns a function type must be marked 'inline' so the
			body is expanded at the call site.
			""".trimIndent()),
		Entry("E024", "Return value from Unit function",
			"""
			The function has no declared return type (defaults to Unit) but a
			'return' statement provides a value. Either add a return type or use
			a bare 'return'.
			""".trimIndent()),

		// ── Value class ──────────────────────────────────────────
		Entry("E030", "Value class wrong property count",
			"""
			An inline value class must have exactly one val property in its primary
			constructor:
			  value class UserId(val raw: Int)  // OK
			  value class Bad(val a: Int, val b: Int)  // error
			""".trimIndent()),
		Entry("E031", "Value class body properties",
			"""
			Value classes cannot have properties declared in their body. The single
			constructor val is the only storage; the class is erased to that
			primitive at compile time.
			""".trimIndent()),

		// ── Access / visibility ──────────────────────────────────
		Entry("E040", "Private member access",
			"""
			The field, property, or method is declared 'private' and cannot be
			accessed from outside its declaring class/object.

			If you own the class, consider adding a public accessor or removing
			the 'private' modifier.
			""".trimIndent()),
		Entry("E041", "Val reassignment",
			"""
			A 'val' (immutable) variable or property cannot be reassigned after
			initialization. Use 'var' if mutation is intended.
			""".trimIndent()),
		Entry("E042", "Private setter",
			"""
			The property's setter is declared 'private set' and cannot be written
			from outside its declaring class.
			""".trimIndent()),
		Entry("E043", "Parameter reassignment",
			"""
			Function parameters are read-only in Kotlin — assignment to a parameter
			inside the function body is rejected. Introduce a local var if you need
			a mutable copy:
			  fun foo(x: Int) {
			      var v = x        // copy into a mutable local
			      v = 5
			  }
			""".trimIndent()),
		Entry("E044", "Internal visibility violation",
			"""
			A declaration marked `internal` is only accessible from within the
			same package. Accessing it from a different package is rejected:
			  package foo
			  internal class Secret(val x: Int)

			  package bar
			  val s = Secret(1)    // error E044 — Secret is internal to 'foo'

			If you intended the declaration to be accessible across packages,
			remove the `internal` modifier (it becomes public). If you intended
			the access to stay within `foo`, move the caller into `foo` as well.

			KTC enforces `internal` on a per-package boundary by default; this
			differs from Kotlin/JVM where the boundary is per Gradle module.
			""".trimIndent()),

		// ── Call errors ──────────────────────────────────────────
		Entry("E050", "Unknown method on receiver",
			"""
			No method, extension function, operator overload, or built-in with
			that name exists on the receiver type.

			Check:
			  - Spelling and case
			  - That the method is defined on the correct type
			  - That extension functions are in scope
			""".trimIndent()),
		Entry("E051", "Unresolved function call",
			"""
			No top-level function, method, extension, constructor, generic, or
			imported symbol with that name was found.
			""".trimIndent()),
		Entry("E052", "Missing required arguments",
			"""
			The function call is missing one or more required parameters that have
			no default value.
			""".trimIndent()),
		Entry("E053", "Too many arguments",
			"""
			The function call provides more arguments than the function accepts.
			Check the function signature for the expected parameter count.
			""".trimIndent()),

		// ── Nullable safety ──────────────────────────────────────
		Entry("E060", "Safe access required on nullable receiver",
			"""
			The receiver expression has a nullable type (T?). Direct member access
			is not allowed — use the safe-call operator '?.' instead:
			  p?.refValue     // OK
			  p.refValue      // error

			Or null-check first:
			  if (p != null) { p.refValue }   // smart-cast narrows to non-null
			""".trimIndent()),

		// ── Ref / pointer ────────────────────────────────────────
		Entry("E070", "Ref-value boundary mismatch",
			"""
			A variable or assignment crosses the Ref<T> ↔ T boundary without an
			explicit conversion:
			  var p: Ref<Int> = x      // error — need x.asRef()
			  var v: Int = p           // error — need p.refValue

			Conversions:
			  .asRef()     takes a reference:  T → Ref<T>
			  .refValue    dereferences:       Ref<T> → T
			""".trimIndent()),

		// ── Tailrec ──────────────────────────────────────────────
		Entry("E080", "Tailrec without self-call",
			"""
			The function is marked 'tailrec' but never calls itself. Either add
			the recursive call or remove the modifier.
			""".trimIndent()),
		Entry("E081", "Recursive call is not a tail call",
			"""
			The function is marked 'tailrec' but the recursive call is not in tail
			position. A tail call must be the very last action in its branch:
			  return factorial(n - 1, acc * n)  // tail position
			  return factorial(n - 1) + 1       // NOT tail position (+ 1 after)
			""".trimIndent()),

		// ── Control flow ─────────────────────────────────────────
		Entry("E090", "Break outside loop",
			"""
			A 'break' statement was found outside of a for/while/do-while loop.
			'break' can only be used inside loop bodies.
			""".trimIndent()),
		Entry("E091", "Continue outside loop",
			"""
			A 'continue' statement was found outside of a for/while/do-while loop.
			'continue' can only be used inside loop bodies.
			""".trimIndent()),

		// ── Declarations ──────────────────────────────────────────
		Entry("E012", "Invalid @Size value",
			"""
			The @Size(N) annotation requires a positive integer constant.
			Negative values, zero, or non-integer-literal arguments are rejected
			because the C struct layout that backs @Size(N) needs a concrete
			compile-time length:

			  @Size(0)  fun f(): IntArray   // error — empty array is UB in C
			  @Size(-3) fun g(): IntArray   // error — meaningless length
			""".trimIndent()),
		Entry("E013", "Duplicate enum entry",
			"""
			Two entries in the same enum class share the same identifier:

			  enum class Color { RED, RED }   // error

			Each entry name must be unique within the enum.
			""".trimIndent()),
		Entry("E014", "Duplicate parameter name",
			"""
			A function or constructor declares two parameters with the same name:

			  fun f(x: Int, x: Int)               // error
			  class P(val a: Int, val a: Int)      // error

			Rename one of the conflicting parameters.
			""".trimIndent()),

		// ── Memory safety ────────────────────────────────────────
		Entry("E120", "Returning a Ref to a frame-local",
			"""
			A 'return' expression yields a Ref<T> whose address points into the
			current function's frame — a local var, a by-value parameter, or an
			element of a stack array. The pointer will dangle the moment the
			function returns:

			  fun f(): Ref<Int> {
			      val x = 5
			      return x.asRef()    // E120 — &x dies on return
			  }

			  fun g(p: Foo): Ref<Foo> = p.asRef()   // E120 — p is a callee copy

			Options:
			  - Allocate on the heap and return that:
			      return Foo(...).allocWith(Heap)
			  - Take a Ref<T> as a parameter instead of returning one:
			      fun f(out: Ref<Int>) { out.refValue = 5 }
			  - Return the value itself (T) rather than Ref<T>.
			""".trimIndent()),

		// ── Interface / override ─────────────────────────────────
		Entry("E100", "Missing interface implementation",
			"""
			A class declares that it implements an interface but does not provide
			a concrete implementation for one or more required methods. Add the
			missing method(s) with the 'override' modifier.
			""".trimIndent()),
		Entry("E101", "Missing override modifier",
			"""
			A method in a class matches an interface method but is not marked
			'override'. Add the modifier:
			  override fun methodName(...) { ... }
			""".trimIndent()),

		// ── Warnings ─────────────────────────────────────────────
		Entry("W001", "Variable shadows class field",
			"""
			A local variable has the same name as a field in the enclosing class,
			which can cause confusion. Rename the local or use 'this.field' to
			access the class member explicitly.

			Suppress with: -Wno-shadow
			""".trimIndent()),
		Entry("W002", "Prefer Ref<T?> over Ref<T>?",
			"""
			Ref<T>? puts nullability on the outer wrapper. The idiomatic KTC form
			is Ref<T?> — nullability on the inner type — because the pointer itself
			is what can be NULL.

			Suppress with: -Wno-nullable-ref
			""".trimIndent()),
		Entry("W003", "tailrec ignored on inline",
			"""
			The function is both 'inline' and 'tailrec'. Since inline functions
			are expanded at the call site, the tailrec optimization has no effect.

			Suppress with: -Wno-tailrec-inline
			""".trimIndent()),
		Entry("W004", "Tail-recursive function suggestion",
			"""
			This function has recursive calls in tail position but is not marked
			'tailrec'. Adding the modifier enables the goto-loop optimization that
			avoids stack growth.

			Suppress with: -Wno-tailrec-suggestion
			""".trimIndent()),
		Entry("W005", "Condition is always true/false",
			"""
			The condition expression is a compile-time constant. The if/when branch
			will always (or never) execute. This may indicate dead code or a logic
			error.

			Suppress with: -Wno-const-condition
			""".trimIndent()),
		Entry("W006", "Non-exhaustive when on enum",
			"""
			A 'when' statement on an enum subject does not cover all enum entries
			and has no 'else' branch. If a new entry is added to the enum, this
			'when' will silently skip it. Add missing branches or an 'else'.

			Suppress with: -Wno-exhaustive-when
			""".trimIndent()),
		Entry("W007", "Null check on non-nullable type",
			"""
			Comparing a non-nullable type to null is always true (for != null) or
			always false (for == null). The check has no runtime effect.

			Suppress with: -Wno-null-check
			""".trimIndent()),
		Entry("W008", "Safe call on non-nullable type",
			"""
			Using the safe-call operator '?.' on a non-nullable receiver is
			redundant. The receiver can never be null, so plain '.' is equivalent
			and clearer.

			Suppress with: -Wno-safe-call
			""".trimIndent()),
		Entry("W009", "Out-of-bounds static index",
			"""
			An array or string index that is a literal integer falls outside the
			statically known length:
			  val s = "abc"
			  val c = s[5]    // W009 — string length is 3

			The runtime bounds check (default on) will catch the same access; this
			warning surfaces it at transpile time so it can be fixed sooner.

			Suppress with: -Wno-bounds
			""".trimIndent()),
		Entry("W010", "Implicit @Size truncation",
			"""
			Assigning an array of unknown compile-time size into a @Size(N)
			variable cannot be statically proven to fit, so an implicit
			.copyOf(N) is inserted to clamp the source to exactly N elements.

			Add .copyOf(N) explicitly to silence the warning and document the
			truncation.

			Suppress with: -Wno-sized-array-truncate
			""".trimIndent()),
		Entry("W011", "Empty control-flow body",
			"""
			An if / else / when / while / for body has no statements. Either the
			intent was to leave a TODO (use a comment), the surrounding logic
			is dead, or the branch was added by mistake.

			  if (cond) {
			      // empty — does nothing
			  }

			Suppress with: -Wno-empty-body
			""".trimIndent()),
		Entry("W012", "!! on literal null",
			"""
			Applying '!!' to a literal null aborts unconditionally — the value is
			always null and the assertion always fires:

			  val x = null!!    // W012 — always throws NullPointerException

			This is almost always a typo or stub left over from refactoring.
			Replace with a real value or throw an explicit error.

			Suppress with: -Wno-redundant-bang
			""".trimIndent()),
		Entry("W014", "Self-comparison",
			"""
			A comparison operator is applied to the same variable on both sides:

			  if (x == x) { ... }    // W014 — always true
			  if (i != i) { ... }    // W014 — always false

			Usually a typo or a leftover from a refactor. Float / Double are
			excluded because NaN != NaN is intentional.

			Suppress with: -Wno-self-compare
			""".trimIndent()),
		Entry("W015", "Identical if/else branches",
			"""
			The 'then' and 'else' branches of an 'if' contain the same
			statements, so the condition has no observable effect:

			  if (cond) { x = 1 } else { x = 1 }    // W015

			Remove the conditional or fix one of the branches.

			Suppress with: -Wno-identical-branches
			""".trimIndent()),
		Entry("W024", "Unreachable code",
			"""
			A statement follows an unconditional exit (`return`, `break`, or
			`continue`) in the same block — the C compiler will never reach it:

			  fun f() {
			      return
			      println("never runs")   // W024
			  }

			Most often this is intentional during debugging (early-return to
			bisect a function); the warning surfaces it without breaking the
			build. Either remove the dead code or move the early-exit.

			Suppress with: -Wno-unreachable
			""".trimIndent()),
		Entry("W018", "Discarded allocator result",
			"""
			An allocator call (allocWith / copyWith / resizeWith) appears as an
			expression statement — its returned pointer is dropped immediately.
			That's a guaranteed memory leak:

			  Foo(...).allocWith(Heap)         // W018 — pointer dropped
			  arr.copyWith(Heap)               // W018 — new buffer never used

			Bind the result to a variable, return it, or pass it along:

			  val p = Foo(...).allocWith(Heap)
			  return arr.copyWith(Heap)

			Suppress with: -Wno-discarded-alloc
			""".trimIndent()),
		Entry("W025", "Unused local variable",
			"""
			A 'val' or 'var' is declared in a function body but is never read.
			Assignment-only counts as unused — the value is computed, stored,
			then never observed:

			  val x = compute()    // W025 — x never used

			Either delete the declaration, use the value, or rename to '_' to
			signal an intentionally ignored binding.

			Suppress with: -Wno-unused-local
			""".trimIndent()),
		Entry("W028", "var never reassigned — could be val",
			"""
			A local 'var' is initialized but never reassigned. Prefer 'val' for
			immutable locals; the compiler can reason more aggressively about
			values that can't change:

			  var x = 5            // W028 — never reassigned
			  println(x)

			Suppress with: -Wno-could-be-val
			""".trimIndent()),
		Entry("W033", "Side-effect-free expression statement",
			"""
			An expression statement evaluates an expression and discards the
			result, but the expression has no side effects — no call, no
			assignment. The whole statement is dead code, usually a typo:

			  a + b;          // W033 — result discarded, no effect
			  x == 5;         // W033 — comparison result thrown away

			Suppress with: -Wno-no-effect-expr
			""".trimIndent()),
		Entry("W013", "Self-assignment",
			"""
			An assignment statement reads and writes the same simple variable
			or property without using it on the right-hand side:

			  x = x          // W013 — no effect

			This usually indicates a typo (the intended source name differs from
			the target) or stale code left over from a refactor.

			Suppress with: -Wno-self-assign
			""".trimIndent()),
	)

	private val byCode = entries.associateBy { it.code }

	fun lookup(inCode: String): Entry? = byCode[inCode.uppercase()]

	fun allCodes(): List<Entry> = entries
}
