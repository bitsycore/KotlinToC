package com.bitsycore.ktc.types

import com.bitsycore.ktc.ast.FunDecl
import com.bitsycore.ktc.ast.IntLit
import com.bitsycore.ktc.ast.TypeRef
import com.bitsycore.ktc.codegen.PropertyDef

/**
 * Typed representation of Kotlin-to-C types.
 * Replaces ad-hoc string-based type handling (endsWith("Array"), startsWith("Pair_"), etc.)
 * with structured, pattern-matchable types.
 *
 * During migration, string compatibility is maintained via:
 *   - `KtcType.from(typeRef, resolve)` to construct from TypeRef
 *   - `KtcType.toCType()` to get the C type string (replaces cTypeStr)
 *   - Queries like `isArray`, `isPointer`, `isNullable`, `elementType` replace string checks
 */
/*
Describes a resolved Kotlin declaration (class, object, enum, or interface).
Carries the identity needed to compute a flat C name (pkg + baseName = flatName).
*/
internal interface TypeDef {
    val baseName: String              // simple declaration name, e.g. "Vec2"
    val pkg: String                   // C prefix, e.g. "game_" or "ktc_std_"
    val kind: KtcType.UserKind        // Class/DataClass/Object/Interface/Enum
    val flatName: String get() = "$pkg$baseName"  // full C identifier
    val methods: List<FunDecl>        // declared methods
    val properties: List<PropertyDef> // declared properties
    val typeParams: List<String>      // generic type parameters
    val superTypeDefs: List<TypeDef>  // resolved super-interfaces
}

/*
TypeDef for intrinsic types (Pair, Triple, Any, ktc_StrBuf) that have no ClassDecl.
*/
internal data class BuiltinTypeDef(
    override val baseName: String,                               // builtin type name
    override val pkg: String = "ktc_",                          // always ktc_ prefix
    override val kind: KtcType.UserKind = KtcType.UserKind.Class // always Class kind
) : TypeDef {
    override val methods: List<FunDecl> get() = emptyList()
    override val properties: List<PropertyDef> get() = emptyList()
    override val typeParams: List<String> get() = emptyList()
    override val superTypeDefs: List<TypeDef> get() = emptyList()
}

internal sealed class KtcType {

    // ── Primitive types ──────────────────────────────────────────────

    data class Prim(val kind: PrimKind) : KtcType() {
        override fun toCType(): String = when (kind) {
            PrimKind.Byte -> "ktc_Byte"
            PrimKind.Short -> "ktc_Short"
            PrimKind.Int -> "ktc_Int"
            PrimKind.Long -> "ktc_Long"
            PrimKind.UByte -> "ktc_UByte"
            PrimKind.UShort -> "ktc_UShort"
            PrimKind.UInt -> "ktc_UInt"
            PrimKind.ULong -> "ktc_ULong"
            PrimKind.Float -> "ktc_Float"
            PrimKind.Double -> "ktc_Double"
            PrimKind.Boolean -> "ktc_Bool"
            PrimKind.Char -> "ktc_Char"
            PrimKind.Rune -> "ktc_Rune"
        }
    }

    enum class PrimKind { Byte, Short, Int, Long, UByte, UShort, UInt, ULong, Float, Double, Boolean, Char, Rune }

    // ── String ───────────────────────────────────────────────────────

    object Str : KtcType() {
        override fun toCType() = "ktc_String"
    }

    // ── void / Nothing ───────────────────────────────────────────────

    object Void : KtcType() {
        override fun toCType() = "void"
    }

    // ── Any (top type) ─────────────────────────────────────────────────
    // At runtime, Any is ktc_Any (a type-id + data pointer trampoline).
    // Narrowed / guaranteed subtype is tracked via scopes, not stored here.
    object Any : KtcType() {
        override fun toCType() = "ktc_Any"
    }

    // ── User-defined class / interface / enum / object ──────────────────

    enum class UserKind { Class, DataClass, Object, Interface, Enum }

    data class User(
        val decl: TypeDef,                         // backing declaration descriptor
        val typeArgs: List<KtcType> = emptyList()  // concrete type arguments
    ) : KtcType() {
        val baseName: String get() = decl.baseName
        val kind: UserKind get() = decl.kind
        val pkg: String get() = decl.pkg
        override fun toCType(): String = decl.flatName
    }

    // ── Array types ──────────────────────────────────────────────────

    data class Arr(
        val elem: KtcType,
        val sized: Int? = null         // @Size(N) Array<T> → T[N]
    ) : KtcType() {
        override fun toCType(): String = when {
            sized != null -> "${elem.toCType()}[${sized}]"
            else -> "ktc_ArrayTrampoline"
        }
    }

    // ── Raw pointer ──────────────────────────────────────────────────

    /** A pointer type. When [inner] is [Arr], this represents a **typed array**
     * (e.g. `IntArray`, `ByteArray`) — the trampoline is a pointer to the array data.
     * When [inner] is [User], this is a `@Ptr`-annotated class pointer (e.g. `Vec2*`).
     * Use `isArrayLike` to cover both [Arr] and `Ptr(Arr)`. */
    data class Ptr(val inner: KtcType) : KtcType() {
        override fun toCType() = "${inner.toCType()}*"
    }

    // ── Nullable wrapper ─────────────────────────────────────────────

    data class Nullable(val inner: KtcType) : KtcType() {
        override fun toCType(): String {
            val c = inner.toCType()
            return if (inner is Ptr) c else "${c}\$Opt"
        }
    }

    // ── Function type ────────────────────────────────────────────────

    data class Func(
        val params: List<KtcType>,      // non-receiver parameters
        val ret: KtcType,               // return type
        val receiver: KtcType? = null   // extension receiver (T in T.() -> R), null for plain lambdas
    ) : KtcType() {
        override fun toCType() = "void*"
    }

    // ── Abstract methods ─────────────────────────────────────────────

    abstract fun toCType(): String

    // ── Queries (replace string checks) ──────────────────────────────

    /* True for both Arr and Ptr(Arr): covers typed arrays (IntArray) and @Ptr Array<T>.
    This replaces the string-based `isArrayType()` check. */
    val isArrayLike: Boolean get() = this is Arr || (this is Ptr && inner is Arr)

    /* Extract the Arr node from Arr or Ptr(Arr), null otherwise. */
    val asArr: Arr?
        get() = when (this) {
            is Arr -> this
            is Ptr -> inner as? Arr
            else -> null
        }

    /*
    Convert to the internal scope string format used by string-based type tracking.
    This is the inverse of stringToKtc — produces the same strings that resolveTypeName
    would return. Used as a compat bridge during Phase 4 migration.
    Examples: Prim(Int) → "Int", Nullable(User(Vec2)) → "Vec2?", Ptr(Arr(Prim(Int))) → "IntArray"
    */
    val toInternalStr: String
        get() = when (this) {
            is Prim -> kind.name                             // "Int", "Boolean", etc.
            is Str -> "String"
            is Void -> "Unit"
            is Any -> "Any"
            is User -> baseName                              // bare class name, no pkg
            is Func -> {
                val vReceiverStr = receiver?.let { it.toInternalStr + "|" } ?: ""  // "T|" or ""
                val vParams = params.joinToString(",") { it.toInternalStr }         // param strings
                "Fun($vReceiverStr$vParams)->${ret.toInternalStr}"
            }

            is Arr -> "${elem.toInternalStr}Array"          // "IntArray", "Vec2Array"
            is Ptr -> when (val vInner = inner) {
                is Arr -> when (val vElem = vInner.elem) {
                    // Typed arrays: Ptr(Arr(Int)) → "IntArray", Ptr(Arr(Nullable(Int))) → "IntOptArray"
                    is Nullable -> "${vElem.inner.toInternalStr}OptArray"
                    else -> "${vElem.toInternalStr}Array"
                }

                else -> "${vInner.toInternalStr}*"                      // "Vec2*"
            }

            is Nullable -> "${inner.toInternalStr}?"             // "Int?", "Vec2?", "Vec2*?"
        }

    val nullable: KtcType get() = Nullable(this)

    // ── Static builder from TypeRef ──────────────────────────────────

    companion object {
        /**
         * Build a KtcType from a TypeRef, using resolveName to resolve type names.
         * resolveName: (String, List<String>) → String (calls resolveTypeName under the hood)
         */
        fun from(typeRef: TypeRef, resolveName: (String) -> String): KtcType {
            val resolved = resolveName(typeRef.name)
            val base = if (typeRef.typeArgs.isNotEmpty()) resolved else typeRef.name
            val isPtr = typeRef.annotations.any { it.name == "Ptr" }
            val sizeAnn = typeRef.annotations.find { it.name == "Size" }?.args?.getOrNull(0) as? IntLit

            val inner: KtcType = when {
                base == "String" -> Str
                base == "void" || base == "Nothing" -> Void
                base in primitiveNames -> Prim(PrimKind.valueOf(base))
                base == "StringBuffer" -> User(BuiltinTypeDef("ktc_StrBuf", pkg = ""))
                base == "RawArray" && typeRef.typeArgs.isNotEmpty() ->
                    Ptr(from(typeRef.typeArgs[0], resolveName))

                base == "Array" && typeRef.typeArgs.isNotEmpty() -> {
                    val elem = from(typeRef.typeArgs[0], resolveName)
                    val arr = Arr(elem, sized = sizeAnn?.value?.toInt())
                    if (isPtr) Ptr(arr) else arr
                }

                base.endsWith("Array") && base !in setOf("Array", "RawArray") -> {
                    // IntArray, ByteArray, UIntArray, etc.
                    val elemName = base.removeSuffix("Array")
                    val elem = from(TypeRef(elemName), resolveName)
                    val arr = Arr(elem, sized = sizeAnn?.value?.toInt())
                    if (isPtr) Ptr(arr) else arr
                }

                base == "Any" -> Any
                else -> User(BuiltinTypeDef(resolved, pkg = ""))
            }

            return if (isPtr && inner !is Arr) Ptr(inner)
            else if (typeRef.nullable) Nullable(inner)
            else inner
        }

        private val primitiveNames = PrimKind.entries.map { it.name }.toSet()
    }
}
