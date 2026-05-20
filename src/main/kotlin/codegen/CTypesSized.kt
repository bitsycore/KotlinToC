package com.bitsycore.ktc.codegen

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.types.KtcType

// Sized-array/string type helpers and printf format/arg helpers.

// ═══════════════════════════ type predicates ══════════════════════

/* True if the internal type name represents an array. Strips pointer/nullable suffixes. */
internal fun isArrayType(inTypeName: String): Boolean =
	inTypeName.removeSuffix("?").removeSuffix("*").endsWith("Array")

/* True if this TypeRef is a raw Array<T> or primitive array — not @Ptr, not @Size. */
internal fun TypeRef.isRawArray(): Boolean {
	if (hasSizeAnnotation()) return false
	if (annotations.any { it.name == "Ptr" }) return false
	return name == "Array" || name in primitiveArraySet
	}

/* True if this TypeRef is a @Size(N)-annotated array — fixed-size ABI. */
internal fun TypeRef.isSizedArray(): Boolean {
	if (!hasSizeAnnotation()) return false
	return name == "Array" || name in primitiveArraySet
	}

/* Primitive C types defined in ktc_macro.h — no user package owns them. */
private val kPrimitiveCTypes = setOf(
	"ktc_Byte",  "ktc_Short", "ktc_Int",   "ktc_Long",
	"ktc_Float", "ktc_Double","ktc_Bool",  "ktc_Char",   "ktc_Rune",
	"ktc_UByte", "ktc_UShort","ktc_UInt",  "ktc_ULong"
	)

/* True when inCTypeName is a user type defined in the package currently being compiled. */
internal fun SymbolReader.isCurrentPkgUserType(inCTypeName: String): Boolean =
	classes.values.any    { it.flatName == inCTypeName }
	|| objects.values.any    { it.flatName == inCTypeName }
	|| enums.values.any      { it.flatName == inCTypeName }
	|| interfaces.values.any { it.flatName == inCTypeName }

// ═══════════════════════════ sized-array registration ════════════

/*
Registers KTC_DEFINE_ARRAY(T, N) and returns the C struct type name.
- T from current package  → sizedArrayDecls        (emitted once, no guard)
- T primitive / external  → sizedArrayGuardedDecls  (emitted with #ifndef guard)
*/
internal fun CCodeGen.sizedArrayCTypeName(inElemCType: String, inSize: Int): String {
	val vPair = Pair(inElemCType, inSize)
	if (isCurrentPkgUserType(inElemCType)) sizedArrayDecls.add(vPair)
	else sizedArrayGuardedDecls.add(vPair)
	return "ktc_Array_${inElemCType}_$inSize"
	}

/* Returns the C struct type name for a @Size(N) String and registers it for emission. */
internal fun CCodeGen.sizedStringCTypeName(inSize: Int): String {
	sizedStringDecls.add(inSize)
	return "ktc_String_$inSize"
	}

/* Returns the C struct type name for a @Size(N) Array<T> WITHOUT registering it (use at call sites). */
internal fun sizedArrayCTypeRef(inElemCType: String, inSize: Int): String =
	"ktc_Array_${inElemCType}_$inSize"

/* Returns the C struct type name for a @Size(N) String WITHOUT registering it (use at call sites). */
internal fun sizedStringCTypeRef(inSize: Int): String =
	"ktc_String_$inSize"

// ═══════════════════════════ printf helpers ═══════════════════════

internal fun SymbolReader.printfFmt(ktc: KtcType): String = when (ktc) {
	is KtcType.Prim -> when (ktc.kind) {
		KtcType.PrimKind.Byte    -> "%\" PRId8 \""
		KtcType.PrimKind.Short   -> "%\" PRId16 \""
		KtcType.PrimKind.Int     -> "%\" PRId32 \""
		KtcType.PrimKind.Long    -> "%\" PRId64 \""
		KtcType.PrimKind.Float   -> "%f"
		KtcType.PrimKind.Double  -> "%f"
		KtcType.PrimKind.Boolean -> "%s"
		KtcType.PrimKind.Char    -> "%c"
		KtcType.PrimKind.Rune    -> "%\" PRId32 \""
		KtcType.PrimKind.UByte   -> "%\" PRIu8 \""
		KtcType.PrimKind.UShort  -> "%\" PRIu16 \""
		KtcType.PrimKind.UInt    -> "%\" PRIu32 \""
		KtcType.PrimKind.ULong   -> "%\" PRIu64 \""
		}
	is KtcType.Str      -> "%.*s"
	is KtcType.Ptr      -> "%p"
	is KtcType.Nullable -> if (ktc.inner is KtcType.Ptr) "%p" else printfFmt(ktc.inner)
	is KtcType.User     -> "%.*s"
	else                -> "%.*s"
	}

internal fun SymbolReader.printfArg(expr: String, ktc: KtcType): String = when (ktc) {
	is KtcType.Prim if ktc.kind == KtcType.PrimKind.Boolean -> "($expr) ? \"true\" : \"false\""
	is KtcType.Str -> "(ktc_Int)($expr).len, ($expr).ptr"
	is KtcType.User if ktc.kind == KtcType.UserKind.Enum -> {
		val cName = typeFlatName(ktc.baseName)
		"(ktc_Int)${cName}_names[($expr)].len, ${cName}_names[($expr)].ptr"
		}
	else -> expr
	}
