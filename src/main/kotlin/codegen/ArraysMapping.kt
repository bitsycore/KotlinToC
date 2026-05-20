package com.bitsycore.ktc.codegen

import com.bitsycore.ktc.types.KtcType

val primitiveArraySet = setOf(
    "ByteArray",
    "ShortArray",
    "IntArray",
    "LongArray",
    "FloatArray",
    "DoubleArray",
    "BooleanArray",
    "CharArray",
    "UByteArray",
    "UShortArray",
    "UIntArray",
    "ULongArray",
    "StringArray"
)

internal fun CCodeGen.primitiveToArrayType(vElem: String): String = when (vElem) {
    "Byte" -> "ByteArray"
    "Short" -> "ShortArray"
    "Int" -> "IntArray"
    "Long" -> "LongArray"
    "Float" -> "FloatArray"
    "Double" -> "DoubleArray"
    "Boolean" -> "BooleanArray"
    "Char" -> "CharArray"
    "UByte" -> "UByteArray"
    "UShort" -> "UShortArray"
    "UInt" -> "UIntArray"
    "ULong" -> "ULongArray"
    "String" -> "StringArray"
    else -> {
        classArrayTypes.add(vElem)
        "${vElem}Array"
    }
}

internal fun CCodeGen.primitiveToArrayOptionalType(vElem: String): String = when (vElem) {
    "Byte" -> "ByteOptArray"
    "Short" -> "ShortOptArray"
    "Int" -> "IntOptArray"
    "Long" -> "LongOptArray"
    "Float" -> "FloatOptArray"
    "Double" -> "DoubleOptArray"
    "Boolean" -> "BooleanOptArray"
    "Char" -> "CharOptArray"
    "UByte" -> "UByteOptArray"
    "UShort" -> "UShortOptArray"
    "UInt" -> "UIntOptArray"
    "ULong" -> "ULongOptArray"
    "String" -> "StringOptArray"
    else -> {
        classArrayTypes.add(vElem)
        "${vElem}OptArray"
    }
}

// ══ KtcType overloads ═══════════════════════════════════════════════

/** Extract element C type from an array KtcType: Ptr<Arr<Int>> → "ktc_Int". */
internal fun arrayElementCTypeKtc(arrKtc: KtcType): String = arrKtc.asArr?.elem?.toCType() ?: "ktc_Int"

/** Extract element internal type from an array KtcType: Ptr<Arr<Int>> → "Int". */
internal fun arrayElementKtTypeKtc(arrKtc: KtcType): String = arrKtc.asArr?.elem?.toInternalStr ?: "Int"