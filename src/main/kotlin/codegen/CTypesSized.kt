package com.bitsycore.ktc.codegen

import com.bitsycore.ktc.ast.*

// Sized-array/string type registration and predicates.
// Printf helpers (printfFmt / printfArg) live in CTypes.kt alongside other KtcType→C mappers.

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

/* Sanitize a C type string into a valid C identifier suffix (replaces $, *, space). */
internal fun sanitizeForVarArrName(inCType: String): String =
	inCType.replace('$', '_').replace('*', 'p').replace(' ', '_')

/* Registers KTC_DECL_VAR_ARR and returns the typedef name for a typed array with element type inElemCType. */
internal fun CCodeGen.varArrTypeName(inElemCType: String): String {
	if (isCurrentPkgUserType(inElemCType)) varArrDecls.add(inElemCType)
	else varArrGuardedDecls.add(inElemCType)
	return "ktc_VarArr_${sanitizeForVarArrName(inElemCType)}"
	}

/* Returns the VarArr typedef name WITHOUT registering it (for use at call sites). */
internal fun varArrTypeRef(inElemCType: String): String =
	"ktc_VarArr_${sanitizeForVarArrName(inElemCType)}"

