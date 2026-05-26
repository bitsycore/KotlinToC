# KTC Transpiler Architecture

This document covers **transpiler internals**: architecture, type system, how to add
features, and evolution rules. For the supported Kotlin subset see [KOTLIN_TO_C.md](KOTLIN_TO_C.md).
For writing tests see [AGENTS.md](../AGENTS.md).

KTC translates a subset of Kotlin source to C11. The pipeline is:

```
Kotlin source → Lexer → Parser → AST → CCodeGen → .h + .c files
```

---

## File Map

Source files live in `src/main/kotlin/` organized by sub-package:

**`com.bitsycore.ast`** — `ast/`

| File       | Role                                                      |
|------------|-----------------------------------------------------------|
| `Ast.kt`   | All AST node types (`TypeRef`, `Decl`, `Expr`, `Stmt`, …) |
| `Token.kt` | Token enum and type definitions                           |

**`com.bitsycore.parser`** — `parser/`

| File        | Role                                    |
|-------------|-----------------------------------------|
| `Lexer.kt`  | Tokenizes source text into `Token` list |
| `Parser.kt` | Tokens → `KtFile` AST                   |

**`com.bitsycore.types`** — `types/`

| File           | Role                                                          |
|----------------|---------------------------------------------------------------|
| `CoreTypes.kt` | Type system: `KtcType` sealed hierarchy + `TypeDef` interface |

**`com.bitsycore.codegen`** — `codegen/`

| File                    | Role                                                             |
|-------------------------|------------------------------------------------------------------|
| `Structures.kt`         | Symbol table data classes (`ClassInfo`, `ObjInfo`, `IfaceInfo`, …) |
| `CCodeGen.kt`           | Orchestrator: all shared state, main entry point                 |
| `CCodeGenCollect.kt`    | Declaration collection: `collectDecls()`, scans all file symbols |
| `CCodeGenGenerate.kt`   | Code generation: `generate()`, header assembly, emit dispatch    |
| `CTypes.kt`             | Type resolution: `resolveTypeName`, `cTypeStr`, `parseResolvedTypeName` |
| `CTypesParams.kt`       | Parameter expansion: `expandParams` and helpers                  |
| `CTypesSized.kt`        | Sized array/string type helpers                                  |
| `TypeInfer.kt`          | Expression type inference: `inferExprType`                       |
| `TypeInferCall.kt`      | Call return-type inference: `inferCallType`                      |
| `TypeInferDot.kt`       | Dot expression type inference: `inferDotType`                    |
| `ScanClasses.kt`        | Generic class instantiation scanning                             |
| `ScanFunctions.kt`      | Generic function instantiation scanning                          |
| `ScanSubst.kt`          | Type substitution helpers                                        |
| `ArraysMapping.kt`      | Array type name mapping utilities                                |
| `Utils.kt`              | Misc helpers (tmp vars, Malloc/Calloc, etc.)                     |
| `BannerHelper.kt`       | Source banner comment generation                                 |

**`com.bitsycore.codegen.emit`** — `codegen/emit/`

| File                    | Role                                                  |
|-------------------------|-------------------------------------------------------|
| `EmitClass.kt`          | Non-generic class struct/ctor emission                |
| `EmitClassGeneric.kt`   | Monomorphized generic class emission                  |
| `EmitClassMethods.kt`   | Method emission (class + object methods)              |
| `EmitClassAny.kt`       | `as_Any` cast methods                                 |
| `EmitEnum.kt`           | Enum struct + value emission                          |
| `EmitFun.kt`            | Top-level function emission                            |
| `EmitFunGeneric.kt`     | Monomorphized generic function emission               |
| `EmitInterface.kt`      | Interface vtable + union emission                     |
| `EmitObject.kt`         | Object/singleton emission                             |
| `EmitVtable.kt`         | Vtable instance generation for interface implementors  |

**`com.bitsycore.codegen.expression`** — `codegen/expr/`

| File                       | Role                                               |
|----------------------------|----------------------------------------------------|
| `Expr.kt`                  | Expression dispatch: `genExpr`, `genStmt`          |
| `ExprBinary.kt`            | Binary/arithmetic expressions                      |
| `ExprCall.kt`              | Function call dispatch                             |
| `ExprCallArgs.kt`          | Argument expansion (array/nullable/vararg/ptr)     |
| `ExprCallBuiltins.kt`      | Built-in calls (arrayOf, StringBuffer)             |
| `ExprCallMethod.kt`        | Method/extension call dispatch                     |
| `ExprCallMethodBuiltins.kt`| Built-in method calls (size, get, set, ptr, etc.)  |
| `ExprCallSafe.kt`          | Safe call (?.) generation                          |
| `ExprCallAlloc.kt`         | allocWith generation                               |
| `ExprCollections.kt`       | arrayOf generation                                 |
| `ExprControl.kt`           | if/when/for/while/return generation                |
| `ExprDot.kt`               | Dot expression, not-null assertion, etc.           |
| `ExprName.kt`              | Name expression (variable reference)               |
| `StmtsAssign.kt`           | Assignment statement generation                    |
| `StmtsFor.kt`              | For-loop generation                                |
| `StmtsInline.kt`           | Inline function expansion                          |
| `StmtsVar.kt`              | Variable declaration generation                    |
| `StmtsVarHelpers.kt`       | Variable init helpers (heap alloc, sized arrays)   |

**`com.bitsycore`** — `Main.kt` (CLI entry point)

All `CCodeGen*.kt` files are **extension functions on `CCodeGen`** — they share all state
through the single class instance without passing it as a parameter.
`internal` is module-scoped in Kotlin, so all sub-packages see each other's `internal` members.

---

## Pipeline Phases

`generate()` runs these steps in order:

1. **`collectDecls()`** — populate every symbol table (`classes`, `objects`, `enums`,
   `interfaces`, `funSigs`, `funNames`, …). Assigns `pkg` on each `TypeDef`.

2. **`scanForClassArrayTypes()`** — find class types used in `Array<T>` fields; emit
   `KT_ARRAY_DEF` trampolines for them.

3. **`scanForGenericInstantiations()`** — walk all function bodies looking for
   constructor calls and type arguments; record every concrete instantiation of
   generic classes.

4. **`materializeGenericInstantiations()`** — for each recorded instantiation, clone
   the generic `ClassDecl` and create a new `ClassInfo` with concrete type parameters
   substituted throughout.

5. **`scanForGenericFunCalls()`** / **`scanGenericFunBodiesForInstantiations()`** /
   **`scanGenericClassMethodBodiesForInstantiations()`** — same discovery loop for
   generic functions; runs twice to pick up transitive instantiations inside newly
   materialized generic class methods.

6. **`computeGenericFunConcreteReturns()`** — when a generic function's declared
   return type is an interface but the body returns a concrete class, record that
   mapping so callers can stack-allocate the result.

7. **Emit declarations** — classes, enums, objects, interfaces, top-level functions,
   vtables, extension functions.

8. **Assembly** — concatenate `hdr` and `impl` builders into a `COutput(header, source)`.

---

## Type System

### `TypeRef` (AST layer — `Ast.kt`)

Raw parsed type from source. Names are unresolved Kotlin identifiers.

```kotlin
data class TypeRef(
    val name: String,
    val nullable: Boolean,
    val typeArgs: List<TypeRef>,
    val funcParams: List<TypeRef>?,  // non-null = function type
    val funcReturn: TypeRef?,
    val funcReceiver: TypeRef?,      // T in T.() -> R
    val annotations: List<Annotation>
)
```

`TypeRef` is a pure syntax node — it has no dependencies on resolution or code gen state.

### `KtcType` (type system layer — `CoreTypes.kt`)

Resolved, structured type. The sealed hierarchy:

```
KtcType
├── Prim(kind: PrimKind)        — Int, Long, Double, Boolean, Char, …
├── Str                          — String / ktc_String
├── Void                         — Unit / void
├── User(decl: TypeDef, typeArgs) — any class, object, enum, interface
├── Arr(elem, sized?)            — Array<T>, IntArray, @Size(N) T[]
├── Ptr(inner)                   — Ref<T> (raw C pointer, no $len)
├── Nullable(inner)              — T? (Optional struct or NULL)
└── Func(params, ret, receiver?) — function / lambda type
```

`KtcType.User` holds a **reference to a `TypeDef`** — it never duplicates `pkg`, `baseName`,
or `kind`. Those come from `decl`.

### `TypeDef` (resolved declaration — `CoreTypes.kt` + `Structures.kt`)

Interface implemented by every resolved symbol-table entry:

```kotlin
interface TypeDef {
    val baseName: String   // "Vec2"
    val pkg: String        // "game_"
    val kind: KtcType.UserKind
    val flatName: String   // "game_Vec2" — the C type name
    val methods: List<FunDecl>
    val properties: List<PropertyDef>
    val typeParams: List<String>
    val superTypeDefs: List<TypeDef>
}
```

Implementations: `ClassInfo`, `ObjInfo`, `EnumInfo`, `IfaceInfo`, `BuiltinTypeDef`.

### Resolution chain

```
TypeRef  →  resolveTypeName(TypeRef): KtcType
KtcType  →  cTypeStr(KtcType): String            (C type string for emission)
TypeDef  →  .flatName                             (C struct/typedef name)
```

`resolveTypeName` is the **only** entry point from the AST into the type system.
`cTypeStr` is the **only** exit point to a C string.

---

## Key Invariants

### No string-based type dispatch

Type decisions must be made through `KtcType`, not by inspecting type strings.

| Do this                                  | Not this                                   |
|------------------------------------------|--------------------------------------------|
| `vKtc.isArrayLike`                       | `isArrayType(resolveTypeNameStr(t))`       |
| `vKtc is KtcType.Func`                   | `isFuncType(resolveTypeNameStr(t))`        |
| `vKtc is KtcType.Ptr`                    | `t.endsWith("*")`                          |
| `vKtc is KtcType.Nullable`               | `t.endsWith("?")`                          |
| `vKtc.asArr?.elem`                       | `arrayElementCType(resolveTypeNameStr(t))` |
| `cTypeStr(resolveTypeName(t))`           | `cTypeStr(resolveTypeNameStr(t))`          |
| `defineVarKtc(name, resolveTypeName(t))` | `defineVar(name, resolveTypeNameStr(t))`   |

### `KtcType.User` carries its `TypeDef` — use it

When you have a `KtcType.User`, look up methods and properties through `decl`:

```kotlin
val vCi = classInfoFor(vKtc)   // ClassInfo? — null if not a class
val vIi = ifaceInfoFor(vKtc)   // IfaceInfo?
val vOi = objInfoFor(vKtc)     // ObjInfo?
val vEi = enumInfoFor(vKtc)    // EnumInfo?
```

Prefer these over `classes[name]`, `interfaces[name]` etc. when you already have
a `KtcType`.

### C names come from `TypeDef.flatName` / `typeFlatName()` / `funCName()`

Never construct C names by concatenating strings with prefix guesses.

| Source                           | C name                                  |
|----------------------------------|-----------------------------------------|
| Class/object/enum/interface type | `vKtc.flatName` or `typeFlatName(name)` |
| Top-level function               | `funCName(name)`                        |
| Method on class `ci`             | `"${ci.flatName}_${methodName}"`        |
| Package prefix only              | `pfx(name)` — last resort fallback      |

### Scopes store `KtcType`

Variable types are stored as `KtcType` in `scopes`:

```kotlin
defineVarKtc(name, resolveTypeName(typeRef))   // primary API
lookupVarKtc(name): KtcType?                   // primary API
defineVar(name, string)                         // legacy bridge (parses string → KtcType)
lookupVar(name): String?                        // legacy bridge (KtcType → toInternalStr)
```

New code always uses the `Ktc` variants.

### The internal string format (legacy bridge)

`KtcType.toInternalStr` produces a canonical string that the legacy string utilities
understand. Use it **only** when passing to functions that still require strings
(e.g. `ensurePairType`, `mangledGenericName`, type inference return values):

```
Prim(Int)          → "Int"
Str                → "String"
Void               → "Unit"
User(Vec2)         → "Vec2"
Arr(Prim(Int))     → "IntArray"
Ptr(Arr(Prim(Int))) → "IntArray"   (typed-array pointer)
Ptr(User(Vec2))    → "Vec2*"
Nullable(Prim(Int)) → "Int?"
Func([Prim(Int)], Str, receiver=User(T)) → "Fun(T|Int)->String"
```

The `|` separates the receiver from parameters in function type strings.

---

## Naming Conventions

### C identifiers

```
package game             →  game_ClassName, game_funcName
package com.foo.bar      →  com_foo_bar_ClassName
(no package)             →  bare name
fun main()               →  int main(void)   (never prefixed)
ktc_* intrinsics         →  never re-prefixed (early-return in pfx/typeFlatName/funCName)
nested class Outer$Inner →  flatName of Outer + "$Inner"
```

### Kotlin source conventions

```
Local variables     →  vParts, vResult, vFoundTorrents  (v prefix)
Parameters          →  inPath, inType, inName           (in prefix)
Class members       →  fField, fItems                   (f prefix)
Constants           →  kMaxSize, kDefaultVal            (k prefix, camelCase)
Indentation         →  tabs
Comments            →  /* … */ block style, not Javadoc
```

---

## Adding a New Kotlin Feature

### 1. Add AST nodes (`Ast.kt`)

Add the new `Expr`, `Stmt`, or `Decl` subclass. `TypeRef` rarely needs changes;
add fields only for genuinely new type-level constructs.

### 2. Teach the parser (`Parser.kt`)

Parse the new syntax and construct the AST nodes.

### 3. Declare symbol table entries if needed (`Structures.kt`)

If the feature introduces a new kind of declaration, add a data class implementing
`TypeDef` (or extend an existing one).

### 4. Register the declaration (`CCodeGen.kt` → `collectDecls()`)

Walk the new `Decl` node and populate the relevant maps. Set `pkg` on every `TypeDef`.
Register type IDs with `getTypeId(name)` for any type that participates in `is`/`as`.

### 5. Resolve types in `CTypes.kt`

If the feature introduces new type syntax, extend `resolveTypeName` and/or
`parseResolvedTypeName`. Always return a `KtcType`, never a raw string.

### 6. Infer expression types in `TypeInfer.kt`

For new `Expr` subtypes, add a branch in `inferExprType`. Return the internal string
(e.g. `"Int"`, `"Vec2"`, `"IntArray?"`). This function still returns `String?` — Phase 4.4
(return `KtcType?`) is the next evolution step.

### 7. Emit declarations in `codegen/emit/`

Add a new `emit*` function. Follow the existing pattern:

```kotlin
// header: forward declare / typedef
hdr.appendLine("...")
// impl: function body
impl.appendLine("...")
```

Call it from the main emit loop in `generate()`.

### 8. Generate statements in `codegen/expr/Stmts*.kt`

Add a branch in `emitStmt` for the new `Stmt` subtype.

### 9. Generate expressions in `codegen/expr/Expr.kt`

Add a branch in `genExpr` for the new `Expr` subtype.

### 10. Write a unit test

Add a `*UnitTest.kt` in `src/test/kotlin/com/bitsycore/`. Use `TranspilerTestBase`:

```kotlin
class MyFeatureUnitTest : TranspilerTestBase() {
    @Test
    fun myFeature() {
        val vSrc = """
            fun main() {
                // Kotlin source using the new feature
            }
        """.trimIndent()
        val vOut = compile(vSrc)
        sourceContains(vOut, "// expected C fragment")
    }
}
```

Run all 541 tests with `./gradlew test`.

---

## Generics

Generics are handled by **monomorphization** — each concrete instantiation gets its
own set of C declarations.

```
Kotlin: class MyList<T>(val data: T)
usage:  MyList<Int>(42), MyList<Vec2>(v)

C output:
  typedef struct { ... } MyList_Int;
  typedef struct { ... } MyList_Vec2;
```

The mangled name is `baseName + "_" + typeArgs.joinToString("_")`.

To add a new generic feature:

1. Store the template in `genericClassDecls` / `genericFunDecls` during `collectDecls`.
2. Teach the scan phase to record instantiations with `recordGenericInstantiation`.
3. In the emit phase, iterate `genericInstantiations[name]` and re-emit with
   `typeSubst` set to the concrete binding map.
4. Use `substituteTypeParams(typeRef)` before resolving any type inside the generic body.

---

## Interfaces and Vtables

Each interface gets a vtable struct:

```c
typedef struct {
    RetType (*methodName)(SelfType*, Params...);
} IfaceName_vtable;
```

Each implementing class gets:
- A `_as_IfaceName(self*)` wrapper that fills the vtable.
- `classInterfaces[className]` lists which interfaces it implements.
- `interfaceImplementors[ifaceName]` lists which classes implement it.

For `is IfaceName` checks, the emitter walks `interfaceImplementors` and ORs the
`__type_id` comparisons.

---

## Nullable Handling

Three representations depending on the type:

| Kotlin type               | C representation                        | Null sentinel  |
|---------------------------|-----------------------------------------|----------------|
| `T?` (primitive / struct) | `ktc_T$Optional { has, value }`         | `has == 0`     |
| `Ref<T?>`                 | `T*`                                    | `NULL`         |
| `Array<T>?`               | `ktc_VarArr_T` with `.ptr == NULL`      | `ptr == NULL`  |
| `Any?`                    | `ktc_Any` trampoline                    | `data == NULL` |

Predicates: `isValueNullableType(str)`, `KtcType.Nullable`.
Emit helpers: `optCTypeName(base)`, `optNone(cType)`, `optSome(cType, expr)`.

---

## Arrays

| Kotlin                    | C representation                                  |
|---------------------------|---------------------------------------------------|
| `IntArray` / `Array<Int>` | `ktc_VarArr_ktc_Int` struct `{ ptr, len }`        |
| `@Size(N) IntArray`       | `ktc_Int[N]` on the stack, no `.len`              |
| `Ref<IntArray>`           | `ktc_VarArr_ktc_Int` (same struct, value-copy)    |
| `Array<Vec2>`             | `ktc_VarArr_game_Vec2` struct `{ Vec2*, len }`    |
| `RawArray<T>`             | `T*` (no length tracking at all)                  |

Typed arrays (`IntArray`, `Array<T>`, `Ref<Array<T>>`) all use `ktc_VarArr_T`
structs with `.ptr` and `.len` fields. Array return values use the VarArr struct
directly — no out-parameter needed.

---

## Inline Functions

`inline fun` bodies are expanded at call sites as C blocks with `goto`:

```c
/* inline receiver.apply(block = Fun(T|)->Unit): T */
{
    // inlined body
    $result = receiver;
    goto $end_ir_0;
$end_ir_0:;
}
```

- Lambda parameters become `activeLambdas` entries expanded at their call sites.
- `inlineReturnVar` / `inlineEndLabel` carry the current expansion context.
- `inlineCounter` ensures unique label names across nested inline calls.

---

## Evolution Rules

### What must never happen

- **String-based type branching**: no `if (type.endsWith("Array"))`, `startsWith("Pair_")`,
  `== "String"`, etc. in new code. Use `KtcType` pattern matching.
- **Constructing C names by string concatenation**: never `"${prefix}${name}"` directly.
  Use `typeFlatName`, `funCName`, or `ci.flatName`.
- **Writing to `classes[name]` pkg after collectDecls**: `pkg` must be set during
  `collectDecls` before any code generation reads it.
- **Calling `resolveTypeNameStr` outside `CTypes.kt`**: it is a legacy bridge
  that lives only inside that file's own implementation.

### Planned next phase (Phase 4.4)

`inferExprType` currently returns `String?`. The next evolution is to return `KtcType?`
instead, which will allow full elimination of:

- `isFuncType(String)`, `parseFuncType(String)`, `cFuncPtrDecl(String, String)`
- `isArrayType(String)`, `arrayElementCType(String)`, `arrayElementKtType(String)`
- `isValueNullableType(String)`, `pointerClassName(String)`
- `resolveTypeNameStr`, `resolveTypeNameInnerStr` (the bridge functions themselves)
- `cTypeStr(String)` (the string overload)

Until that migration is complete, the above functions remain as bridges and may be
called from `TypeInfer.kt` and from string-returning helpers only.

### Adding a string utility is a regression

If you find yourself writing a new `fun isXyzType(t: String): Boolean` or similar,
stop and express the check as a `KtcType` query instead. String utilities accumulate
technical debt that makes future refactors harder.

---

## Testing

Tests live in `src/test/kotlin/com/bitsycore/`. Each test class extends `TranspilerTestBase`.

```kotlin
// Check the generated C contains a fragment
sourceContains(output, "expected C fragment")

// Check it does NOT contain something
sourceNotContains(output, "wrong C fragment")

// Compile and run the C output, check stdout
val result = compileAndRun(output)
assertEquals("expected output\n", result)
```

Run: `./gradlew test` — currently 577 tests, all must pass before merging.

To diagnose a test failure, use `output.source` and `output.header` to inspect the
full generated C, or add `println(output.source)` temporarily.
