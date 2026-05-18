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
    val isOverride: Boolean = false,         // override modifier
    val isConstructorParam: Boolean = false, // true = declared in primary ctor
    val initExpr: Expr? = null,              // initializer expression (body props)
    val line: Int = 0                        // source line for error reporting
)

internal data class ClassInfo(
    val name: String,                                          // class simple name
    val isData: Boolean,                                       // data class flag
    override val properties: List<PropertyDef> = emptyList(), // all props: ctor + body
    val ctorPlainParams: List<PropertyDef> = emptyList(),      // non-val/var ctor params
    override val methods: MutableList<FunDecl> = mutableListOf(), // declared methods
    val initBlocks: List<Block> = emptyList(),                 // init { } blocks
    override val typeParams: List<String> = emptyList(),       // generic type parameters
    override var pkg: String = ""                              // set during collectDecls; mutable for post-construction assignment
) : TypeDef {
    override val baseName: String get() = name
    override val kind: KtcType.UserKind get() = if (isData) KtcType.UserKind.DataClass else KtcType.UserKind.Class
    override val superTypeDefs: List<TypeDef> get() = emptyList()

    /** ctor val/var props (subset of properties with isConstructorParam=true) */
    val ctorProps: List<PropertyDef> get() = properties.filter { it.isConstructorParam }

    /** body-declared props (subset of properties with isConstructorParam=false) */
    val bodyProps: List<PropertyDef> get() = properties.filter { !it.isConstructorParam }

    /** all props as name→typeRef pairs for combined iteration */
    val props: List<Pair<String, TypeRef>> get() = properties.map { it.name to it.typeRef }

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
    val name: String,            // enum name
    val entries: List<String>,   // enum entry names
    override var pkg: String = ""         // set during collectDecls
) : TypeDef {
    override val baseName: String get() = name
    override val kind: KtcType.UserKind get() = KtcType.UserKind.Enum
    override val methods: List<FunDecl> get() = emptyList()
    override val properties: List<PropertyDef> get() = emptyList()
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
    override var pkg: String = ""                                 // set during collectDecls
) : TypeDef {
    override val baseName: String get() = name
    override val kind: KtcType.UserKind get() = KtcType.UserKind.Interface
    override val superTypeDefs: List<TypeDef> get() = emptyList()

    /** TypeDef.properties: interface props not yet in PropertyDef form */
    override val properties: List<PropertyDef> get() = emptyList()
}

/** active inline lambda */
internal data class ActiveLambda(val expr: LambdaExpr, val paramTypes: List<String>, val returnType: String? = null)
/** iterator dispatch info */
internal data class IteratorInfo(val iterClass: String, val iterCType: String, val elemKtType: String, val isPointer: Boolean)
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
