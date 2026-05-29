package com.bitsycore.ktc.codegen

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.types.KtcType
import com.bitsycore.ktc.types.TypeDef

/**
 * Symbol Table Data Classes
 *
 * Data structures used throughout the transpiler pipeline to represent
 * Kotlin declarations during code generation. All are internal to the
 * com.bitsycore package.
 *
 * Types:
 * PropertyDef   — a unified property descriptor (ctor prop, body prop, or iface prop)
 * ClassInfo     — aggregated class metadata (props, methods, type params)
 * EnumInfo      — enum name + entry list
 * ObjInfo       — object properties + methods
 * FunSig        — function parameter types + return type
 * IfaceInfo     — interface methods + properties + super-interfaces
 * ActiveLambda  — lambda expression being expanded inside an inline call
 * IteratorInfo  — result of findOperatorIterator() lookup
 * COutput       — result of code generation (.h + per-declaration .c files)
 */

// ═══════════════════════════════════════════════════════════════════

/*
Metadata for a single local variable in scope.
Bundles all per-variable state that was previously tracked across four parallel scope stacks.
arraySize: compile-time-known element count; null when dynamic or non-array.
cName: C access expression override (e.g. "$self.fField", "Obj.fProp"); null = use bare variable name.
*/
internal data class LocalVar(
	val ktc:       KtcType,        // variable's resolved type
	val mutable:   Boolean = false, // true for var, false for val
	val optional:  Boolean = false, // true when stored as Optional struct (value-nullable T?)
	val arraySize: Int?    = null,  // compile-time element count for array variables
	val cName:     String? = null,  // C expression to read this symbol; null means use the variable name
	val isParam:   Boolean = false  // true when this is a function parameter (Kotlin params are read-only)
	)

// ═══════════════════════════════════════════════════════════════════

/**
Unified property descriptor replacing Pair<String,TypeRef>+BodyProp.
isConstructorParam=true for ctor val/var props; false for body props.
 */
internal data class PropertyDef(
    val name: String,                        // property name
    val typeRef: TypeRef,                    // declared type reference
    val isVal: Boolean,                      // true = val (immutable), false = var
    val isPrivate: Boolean = false,          // private visibility
    val isPrivateSet: Boolean = false,       // private set modifier
    val isInternal: Boolean = false,         // internal visibility (cross-package)
    val isOverride: Boolean = false,         // override modifier
    val isConstructorParam: Boolean = false, // true = declared in primary ctor
    val initExpr: Expr? = null,              // initializer expression (body props)
    val line: Int = 0,                       // source line for error reporting
    val getter: Expr? = null,                // custom get() = expr
    val setterParam: String? = null,         // set(param) param name
    val setterBody: Block? = null            // set(param) { body }
)

internal data class ClassInfo(
    val name: String,                                          // class simple name
    val isData: Boolean,                                       // data class flag
    override val properties: List<PropertyDef> = emptyList(), // all props: ctor + body
    val ctorPlainParams: List<PropertyDef> = emptyList(),      // non-val/var ctor params
    override val methods: MutableList<FunDecl> = mutableListOf(), // declared methods
    val initBlocks: List<Block> = emptyList(),                 // init { } blocks
    override val typeParams: List<String> = emptyList(),       // generic type parameters
    override var pkg: String = "",                             // set during collectDecls; mutable for post-construction assignment
    val isValue: Boolean = false,                              // inline value class flag
    val isInternal: Boolean = false,                           // internal visibility (cross-package access blocked)
    val isSealed: Boolean = false                              // sealed class — closed hierarchy, enables exhaustive `when`
) : TypeDef {
    override val baseName: String get() = name
    override val kind: KtcType.UserKind get() = when {
        isValue -> KtcType.UserKind.ValueClass
        isData  -> KtcType.UserKind.DataClass
        else    -> KtcType.UserKind.Class
    }
    override val superTypeDefs: List<TypeDef> get() = emptyList()

    /** ctor val/var props (subset of properties with isConstructorParam=true) */
    val ctorProps: List<PropertyDef> get() = properties.filter { it.isConstructorParam }

    /** body-declared props (subset of properties with isConstructorParam=false) */
    val bodyProps: List<PropertyDef> get() = properties.filter { !it.isConstructorParam }

    /** all props as name→typeRef pairs for combined iteration */
    val props: List<Pair<String, TypeRef>> get() = properties.map { it.name to it.typeRef }

    /** props with backing storage (excludes computed getters) */
    val storedProps: List<Pair<String, TypeRef>> get() = properties.filter { it.getter == null }.map { it.name to it.typeRef }

    /* Stored props that participate in structural equals AND hashCode.
       Interface-Ref fields are excluded: equals can't compare them by value and
       hashing a ktc_IfacePtr struct as an integer is invalid C. Both Any-protocol
       emitters MUST iterate this same set, or equals/hashCode desync (broken contract). */
    fun hashEqProps(inInterfaceNames: Set<String>): List<Pair<String, TypeRef>> =
        storedProps.filter { (_, type) ->
            val innerName = if (type.name == "Ref" && type.typeArgs.isNotEmpty()) type.typeArgs[0].name else type.name
            !(type.isRefType() && innerName in inInterfaceNames)
            }

    /** names of private properties */
    val privateProps: Set<String> get() = properties.filter { it.isPrivate }.map { it.name }.toSet()

    /** names of constructor val props (cannot be reassigned) */
    val valCtorProps: Set<String>
        get() = properties.filter { it.isConstructorParam && it.isVal }.map { it.name }.toSet()

    /** names of private-set props */
    val privateSetProps: Set<String> get() = properties.filter { it.isPrivateSet }.map { it.name }.toSet()
    val isGeneric: Boolean get() = typeParams.isNotEmpty()

    /** true if the named property is a val (immutable) */
    fun isValProp(inName: String): Boolean =
        inName in valCtorProps || bodyProps.any { it.name == inName && it.isVal }
}

internal data class EnumInfo(
    val name: String,                                              // enum name
    val entries: List<String>,                                     // enum entry names (in declaration order)
    override var pkg: String = "",                                 // set during collectDecls
    val ctorParams: List<PropertyDef> = emptyList(),               // primary ctor val/var props (empty for simple enums)
    val entryArgs: Map<String, List<Expr>> = emptyMap(),           // per-entry ctor argument expressions (empty for simple enums)
    val bodyProps: List<PropertyDef> = emptyList(),                // body-declared properties (val/var) on the enum
    val enumMethods: MutableList<FunDecl> = mutableListOf(),       // body-declared methods on the enum
    val entryOverrides: Map<String, List<FunDecl>> = emptyMap(),   // per-entry method overrides (Phase 3)
    val isSimple: Boolean = true                                   // true → C-int representation; false → struct representation
) : TypeDef {
    /** Names of body methods that have at least one per-entry override — these dispatch through the entry vtable. */
    val virtualMethodNames: Set<String> get() = entryOverrides.values.flatten().map { it.name }.toSet()
    override val baseName: String get() = name
    override val kind: KtcType.UserKind get() = KtcType.UserKind.Enum
    override val methods: List<FunDecl> get() = enumMethods
    override val properties: List<PropertyDef> get() = ctorParams + bodyProps
    override val typeParams: List<String> get() = emptyList()
    override val superTypeDefs: List<TypeDef> get() = emptyList()
}

internal data class ObjInfo(
    val name: String,                                             // object simple name
    override val properties: List<PropertyDef> = emptyList(),    // object properties
    override val methods: MutableList<FunDecl> = mutableListOf(), // object methods
    override var pkg: String = ""                                 // set during collectDecls
) : TypeDef {
    override val baseName: String get() = name
    override val kind: KtcType.UserKind get() = KtcType.UserKind.Object
    override val typeParams: List<String> get() = emptyList()
    override val superTypeDefs: List<TypeDef> get() = emptyList()

    /** all props as name→typeRef pairs */
    val props: List<Pair<String, TypeRef>> get() = properties.map { it.name to it.typeRef }

    /** names of private properties */
    val privateProps: Set<String> get() = properties.filter { it.isPrivate }.map { it.name }.toSet()
}

internal data class FunSig(val params: List<Param>, val returnType: TypeRef?) // function signature

internal data class IfaceInfo(
    val name: String,                                             // interface simple name
    override val methods: List<FunDecl>,                          // declared methods
    val propDecls: List<PropDecl> = emptyList(),                  // declared properties (AST PropDecl form)
    override val typeParams: List<String> = emptyList(),          // generic type parameters
    val superInterfaces: List<TypeRef> = emptyList(),             // super interface refs
    override var pkg: String = "",                                // set during collectDecls
    val isSealed: Boolean = false                                 // sealed interface — closed hierarchy, enables exhaustive `when`
) : TypeDef {
    override val baseName: String get() = name
    override val kind: KtcType.UserKind get() = KtcType.UserKind.Interface
    override val superTypeDefs: List<TypeDef> get() = emptyList()

    /** TypeDef.properties: interface props not yet in PropertyDef form */
    override val properties: List<PropertyDef> get() = emptyList()
}

/* Active inline lambda being expanded at a call site. */
internal data class ActiveLambda(val expr: LambdaExpr, val paramTypes: List<KtcType>, val returnType: KtcType? = null)
/* Iterator dispatch info resolved for a collection type. */
internal data class IteratorInfo(val iterClass: String, val iterCType: String, val elemKtType: KtcType, val isPointer: Boolean)
/*
A single generated .c file: its text content and the routing package that
determines which output subdirectory it goes into.
routingPkg is the original dot-separated Kotlin package (e.g. "ktc.std", "com.example").
It may differ from the current package when a generic class is instantiated by another
package — the instantiation is routed to the template's package directory.
*/
data class SourceFile(val content: String, val routingPkg: String)

/*
Output of code generation.
header  — the single package .h file content (structs, typedefs, prototypes).
sources — map of filename → SourceFile for each generated .c file.
          Key is the simple filename (e.g. "Point.c", "ktc_std.c").
          One entry per class/object/enum; top-level functions in "$pkgName.c".
          routingPkg inside each SourceFile determines the target subdirectory.
*/
data class COutput(val header: String, val sources: Map<String, SourceFile>)

/*
Output buffer state for a single package's code generation pass.
All mutable output state that was formerly on CCodeGen lives here, making it
possible to construct a CCodeGen without output side-effects and to test emit
functions with a controlled CodeBuilder instance.
*/
internal class CodeBuilder
	{
	val hdr     = StringBuilder()                                         // .h forward decls & typedefs
	var impl    = StringBuilder()                                         // active .c impl target (swapped per-decl)
	var implFwd = StringBuilder()                                         // active .c private-fwd target (swapped per-decl)
	val perDeclImpl    = mutableMapOf<String, StringBuilder>()            // per-decl impl content, keyed by decl name
	val perDeclImplFwd = mutableMapOf<String, StringBuilder>()            // per-decl implFwd content
	val deferredAsCalls = mutableMapOf<String, StringBuilder>()           // as_* cast functions, keyed by root decl name
	val deferredObjIfaceMethods = mutableMapOf<Pair<String, String>, StringBuilder>() // (objectName, ifaceName) → impl content

	/*
	Routes impl and implFwd to the per-decl buffers for inKey, runs inBlock,
	then restores the previous impl/implFwd.
	inKey must already be resolved to the root declaration key by the caller.
	*/
	fun captureForDecl(
		inKey: String,         // already-resolved root declaration key; "" for top-level functions
		inBlock: () -> Unit
		) {
		val vSavedImpl    = impl                                          // save current target
		val vSavedImplFwd = implFwd
		impl    = perDeclImpl.getOrPut(inKey)    { StringBuilder() }     // swap to per-decl buffer
		implFwd = perDeclImplFwd.getOrPut(inKey) { StringBuilder() }
		inBlock()
		impl    = vSavedImpl                                              // restore previous target
		implFwd = vSavedImplFwd
		}
	}

/*
Live state for the function currently being emitted.
Replaces the 14 scattered currentFn* / currentClass / selfIsPointer / currentExtRecvType fields
on CCodeGen. CCodeGen holds a single var fnCtx: FunctionContext and exposes delegate properties
so all call sites in emit/expr files stay unchanged.
Saved and restored by saveFunState/restoreFunState for nested function emission (e.g. inner classes).
*/
internal data class FunctionContext(
	var returnsNullable: Boolean   = false,   // true when function returns a nullable type
	var returnsArray: Boolean      = false,   // true when function returns a variable-length array
	var returnsSizedArray: Boolean = false,   // true when function returns @Size(N) array struct
	var sizedArraySize: Int        = 0,       // N for @Size(N) array return
	var sizedArrayElemType: KtcType? = null,  // element KtcType for @Size(N) array return (null when not a sized-array return)
	var returnsSizedString: Boolean = false,  // true when function returns @Size(N) String struct
	var sizedStringSize: Int       = 0,       // N for @Size(N) String return
	var returnType: String         = "",      // C return type string
	var returnKtcType: KtcType?    = null,    // KtcType of return (for pattern matching)
	var optReturnCTypeName: String = "",      // Optional C type for nullable returns
	var isMain: Boolean            = false,   // true when emitting main() (affects void vs int)
	var klass: String?             = null,    // current class context (name of class being emitted)
	var selfPtr: Boolean           = true,    // true when $self is a pointer
	var extRecvType: String?       = null,    // extension receiver type name

	// Per-function mutable state (formerly on CCodeGen, now auto-saved/restored)
	var trampolinedParams: MutableSet<String>        = mutableSetOf(),  // array param names trampolined to local pointers
	var sizedArrayTrampolinedParams: MutableSet<String> = mutableSetOf(), // @Size array param names (subset of trampolined)
	var deferStack: MutableList<Block>               = mutableListOf(),  // LIFO stack of deferred blocks
	var currentObject: String?                        = null,            // current object singleton context
	var loopDepth: Int                                = 0,               // active for/while/do-while nesting depth
	var tailrecFnName: String?                        = null,            // non-null inside a tailrec function body
	var tailrecParams: List<Param>                    = emptyList(),     // params to reassign on tail call
	var tailrecHasReceiver: Boolean                   = false,           // true when function has a $self to reassign
	var tailrecSelfCType: String?                     = null,            // C type of $self for temp declarations
	) {
	    /** Deep-copies mutable collections so save/restore is isolated. */
	    fun deepCopy(): FunctionContext = copy(
	        trampolinedParams           = trampolinedParams.toMutableSet(),
	        sizedArrayTrampolinedParams = sizedArrayTrampolinedParams.toMutableSet(),
	        deferStack                  = deferStack.toMutableList(),
	    )
	}

/*
Read-only view of the symbol tables and core name-resolution helpers used across
codegen modules. Implemented by CCodeGen; can also be implemented by test stubs.
Extension functions in CTypes.kt and (eventually) TypeInfer.kt take SymbolReader
as their receiver, decoupling them from the full mutable CCodeGen instance.
*/
internal interface SymbolReader
	{
	/* Symbol maps */
	val classes: Map<String, ClassInfo>                            // class name → ClassInfo
	val interfaces: Map<String, IfaceInfo>                         // interface name → IfaceInfo
	val enums: Map<String, EnumInfo>                               // enum name → EnumInfo
	val objects: Map<String, ObjInfo>                              // object name → ObjInfo
	val classInterfaces: Map<String, List<String>>                 // class name → implemented iface names
	val interfaceImplementors: Map<String, List<String>>           // iface name → implementor class names
	val classArrayTypes: Set<String>                               // class/enum names used as Array element types
	val genericClassDecls: Map<String, ClassDecl>                  // generic class name → original ClassDecl
	val genericIfaceDecls: Map<String, InterfaceDecl>              // generic iface name → original InterfaceDecl
	val allGenericTypeParamNames: Set<String>                      // all known type-param names (T, U, …)
	val typeSubst: Map<String, String>                             // active type-param substitution
	val prefix: String                                             // package C prefix (e.g. "game_")
	val mangledComponents: Map<String, Pair<String, List<String>>> // mangled name → (baseName, typeArgs)

	/* Core name-resolution methods */
	fun typeFlatName(inName: String): String                       // Kotlin type name → C flat name
	fun optCTypeName(internalType: String): String                 // internal type → Optional C name
	fun isFuncType(inT: String): Boolean                           // true if "Fun(…)->…" string
	fun parseFuncType(inT: String): Pair<List<String>, String>     // parse "Fun(P1,P2)->R" → (params, ret)
	fun mangledGenericName(inBaseName: String, inTypeArgs: List<String>): String  // make mangled name
	fun recordGenericInstantiation(inBaseName: String, inTypeArgs: List<String>): String // record + mangle

	/* Error reporting */
	fun codegenError(inMsg: String): Nothing                       // throw a fatal codegen error
	fun codegenError(inCode: String, inMsg: String): Nothing       // throw with stable error code
	fun codegenWarning(inMsg: String)                              // emit a non-fatal warning
	fun codegenWarning(inName: String, inMsg: String)              // emit a named warning (can be toggled with -Wno-<name>)
	}
