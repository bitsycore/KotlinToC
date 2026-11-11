@file:Suppress("UNUSED_PARAMETER", "UNCHECKED_CAST", "NOTHING_TO_INLINE", "FINAL_UPPER_BOUND", "UNUSED_TYPEALIAS_PARAMETER")
@file:kotlinx.cinterop.internal.CHeaders("<stdlib.h>", "<string.h>")

package kotlinx.cinterop

import kotlinx.cinterop.internal.CType
import kotlinx.cinterop.internal.cAlignOf
import kotlinx.cinterop.internal.cCall
import kotlinx.cinterop.internal.cExpr
import kotlinx.cinterop.internal.cFunction
import kotlinx.cinterop.internal.cSizeOf

/*
kotlinx.cinterop as Kotlin/Native defines it, for ktc2.

Every type below that stands for a C address — a pointer, or the variable or struct it points
at — is a bare `void*` in the generated C: no object, no reference count. The functions are
written with `cExpr`, so `p.pointed.value` is a plain C load.
*/

// ==================
// MARK: Opt-in markers
// ==================

/** Kotlin/Native asks code using C interop to opt in; here the marker only has to exist. */
@RequiresOptIn
@Retention(AnnotationRetention.BINARY)
public annotation class ExperimentalForeignApi

@RequiresOptIn
@Retention(AnnotationRetention.BINARY)
public annotation class BetaInteropApi

// ==================
// MARK: Addresses
// ==================

/** A raw address. */
public class NativePtr private constructor()

public inline val nativeNullPtr: NativePtr get() = cExpr("NULL")

public inline fun NativePtr.toLong(): Long = cExpr("(int64_t)(intptr_t)$0", this)

/** Something that lives at an address. */
public open class NativePointed internal constructor()

public inline val NativePointed.rawPtr: NativePtr get() = cExpr("$0", this)

/** A C value seen through a pointer. */
public abstract class CPointed : NativePointed()

/** A C type that is not known, or not complete: only pointers to it are used. */
public abstract class COpaque : CPointed()

/** A C variable: a location holding a value of a known size. */
public abstract class CVariable : CPointed()

public abstract class CPrimitiveVar : CVariable()

public abstract class CStructVar : CVariable()

/** A C function, as the target of a function pointer. */
public class CFunction<T : Function<*>> private constructor() : CPointed()

/** What a C function accepts for a pointer parameter: a pointer, or values to pass by address. */
public abstract class CValuesRef<T : CPointed> internal constructor()

/** C values located somewhere for the time of a call (a string's bytes, an array's elements). */
public abstract class CValues<T : CVariable> internal constructor() : CValuesRef<T>()

/**
 * A C struct held by value: what a C function taking or returning a struct exchanges with
 * Kotlin. It is an ordinary counted object containing the bytes of the struct.
 */
public class CValue<T : CVariable> private constructor() : CValues<T>()

/** A C pointer. */
public class CPointer<T : CPointed> private constructor() : CValuesRef<T>()

public typealias COpaquePointer = CPointer<out CPointed>

public typealias CArrayPointer<T> = CPointer<T>

public typealias COpaquePointerVar = CPointerVarOf<COpaquePointer>

public inline val CPointer<*>.rawValue: NativePtr get() = cExpr("$0", this)

public inline fun <T : CPointed> CPointer<T>?.toLong(): Long = cExpr("(int64_t)(intptr_t)$0", this)

public inline fun <T : CPointed> Long.toCPointer(): CPointer<T>? = cExpr("(void*)(intptr_t)$0", this)

/** The address of this variable or struct. */
public inline val <T : CPointed> T.ptr: CPointer<T> get() = cExpr("$0", this)

/** What this pointer points at. */
public inline val <T : CPointed> CPointer<T>.pointed: T get() = cExpr("$0", this)

public inline fun <T : CPointed> CPointer<*>.reinterpret(): CPointer<T> = cExpr("$0", this)

public inline fun <T : NativePointed> NativePointed.reinterpret(): T = cExpr("$0", this)

public fun <T : NativePointed> interpretPointed(ptr: NativePtr): T = cExpr("$0", ptr)

public fun <T : CPointed> interpretCPointer(rawValue: NativePtr): CPointer<T>? = cExpr("$0", rawValue)

// ==================
// MARK: Sizes and indexing
// ==================

public inline fun <reified T : CVariable> sizeOf(): Long = cSizeOf<T>()

public inline fun <reified T : CVariable> alignOf(): Int = cAlignOf<T>()

/** Element [index] of the C array this pointer is the start of. */
@JvmName("get1")
public inline operator fun <reified T : CVariable> CPointer<T>.get(index: Int): T =
	cExpr("(void*)((char*)$0 + $1 * $2)", this, index.toLong(), cSizeOf<T>())

@JvmName("get2")
public inline operator fun <reified T : CVariable> CPointer<T>.get(index: Long): T =
	cExpr("(void*)((char*)$0 + $1 * $2)", this, index, cSizeOf<T>())

@JvmName("plus3")
public inline operator fun <reified T : CVariable> CPointer<T>?.plus(index: Long): CPointer<T>? =
	if (this == null) null else cExpr("(void*)((char*)$0 + $1 * $2)", this, index, cSizeOf<T>())

@JvmName("plus4")
public inline operator fun <reified T : CVariable> CPointer<T>?.plus(index: Int): CPointer<T>? = this + index.toLong()

// ==================
// MARK: Variables
// ==================

@CType("bool")
public class BooleanVarOf<T : Boolean> private constructor() : CPrimitiveVar()

public typealias BooleanVar = BooleanVarOf<Boolean>

public inline var <T : Boolean> BooleanVarOf<T>.value: T
	get() = cExpr("*(bool*)$0", this)
	set(value) {
		cExpr<Unit>("*(bool*)$0 = $1", this, value)
	}

@JvmName("get5")
public inline operator fun <T : Boolean> CPointer<BooleanVarOf<T>>.get(index: Int): T = cExpr("((bool*)$0)[$1]", this, index)

@JvmName("set6")
public inline operator fun <T : Boolean> CPointer<BooleanVarOf<T>>.set(index: Int, value: T) {
	cExpr<Unit>("((bool*)$0)[$1] = $2", this, index, value)
}

@JvmName("get7")
public inline operator fun <T : Boolean> CPointer<BooleanVarOf<T>>.get(index: Long): T = cExpr("((bool*)$0)[$1]", this, index)

@JvmName("set8")
public inline operator fun <T : Boolean> CPointer<BooleanVarOf<T>>.set(index: Long, value: T) {
	cExpr<Unit>("((bool*)$0)[$1] = $2", this, index, value)
}

@CType("int8_t")
public class ByteVarOf<T : Byte> private constructor() : CPrimitiveVar()

public typealias ByteVar = ByteVarOf<Byte>

public inline var <T : Byte> ByteVarOf<T>.value: T
	get() = cExpr("*(int8_t*)$0", this)
	set(value) {
		cExpr<Unit>("*(int8_t*)$0 = $1", this, value)
	}

@JvmName("get9")
public inline operator fun <T : Byte> CPointer<ByteVarOf<T>>.get(index: Int): T = cExpr("((int8_t*)$0)[$1]", this, index)

@JvmName("set10")
public inline operator fun <T : Byte> CPointer<ByteVarOf<T>>.set(index: Int, value: T) {
	cExpr<Unit>("((int8_t*)$0)[$1] = $2", this, index, value)
}

@JvmName("get11")
public inline operator fun <T : Byte> CPointer<ByteVarOf<T>>.get(index: Long): T = cExpr("((int8_t*)$0)[$1]", this, index)

@JvmName("set12")
public inline operator fun <T : Byte> CPointer<ByteVarOf<T>>.set(index: Long, value: T) {
	cExpr<Unit>("((int8_t*)$0)[$1] = $2", this, index, value)
}

@CType("int16_t")
public class ShortVarOf<T : Short> private constructor() : CPrimitiveVar()

public typealias ShortVar = ShortVarOf<Short>

public inline var <T : Short> ShortVarOf<T>.value: T
	get() = cExpr("*(int16_t*)$0", this)
	set(value) {
		cExpr<Unit>("*(int16_t*)$0 = $1", this, value)
	}

@JvmName("get13")
public inline operator fun <T : Short> CPointer<ShortVarOf<T>>.get(index: Int): T = cExpr("((int16_t*)$0)[$1]", this, index)

@JvmName("set14")
public inline operator fun <T : Short> CPointer<ShortVarOf<T>>.set(index: Int, value: T) {
	cExpr<Unit>("((int16_t*)$0)[$1] = $2", this, index, value)
}

@JvmName("get15")
public inline operator fun <T : Short> CPointer<ShortVarOf<T>>.get(index: Long): T = cExpr("((int16_t*)$0)[$1]", this, index)

@JvmName("set16")
public inline operator fun <T : Short> CPointer<ShortVarOf<T>>.set(index: Long, value: T) {
	cExpr<Unit>("((int16_t*)$0)[$1] = $2", this, index, value)
}

@CType("int32_t")
public class IntVarOf<T : Int> private constructor() : CPrimitiveVar()

public typealias IntVar = IntVarOf<Int>

public inline var <T : Int> IntVarOf<T>.value: T
	get() = cExpr("*(int32_t*)$0", this)
	set(value) {
		cExpr<Unit>("*(int32_t*)$0 = $1", this, value)
	}

@JvmName("get17")
public inline operator fun <T : Int> CPointer<IntVarOf<T>>.get(index: Int): T = cExpr("((int32_t*)$0)[$1]", this, index)

@JvmName("set18")
public inline operator fun <T : Int> CPointer<IntVarOf<T>>.set(index: Int, value: T) {
	cExpr<Unit>("((int32_t*)$0)[$1] = $2", this, index, value)
}

@JvmName("get19")
public inline operator fun <T : Int> CPointer<IntVarOf<T>>.get(index: Long): T = cExpr("((int32_t*)$0)[$1]", this, index)

@JvmName("set20")
public inline operator fun <T : Int> CPointer<IntVarOf<T>>.set(index: Long, value: T) {
	cExpr<Unit>("((int32_t*)$0)[$1] = $2", this, index, value)
}

@CType("int64_t")
public class LongVarOf<T : Long> private constructor() : CPrimitiveVar()

public typealias LongVar = LongVarOf<Long>

public inline var <T : Long> LongVarOf<T>.value: T
	get() = cExpr("*(int64_t*)$0", this)
	set(value) {
		cExpr<Unit>("*(int64_t*)$0 = $1", this, value)
	}

@JvmName("get21")
public inline operator fun <T : Long> CPointer<LongVarOf<T>>.get(index: Int): T = cExpr("((int64_t*)$0)[$1]", this, index)

@JvmName("set22")
public inline operator fun <T : Long> CPointer<LongVarOf<T>>.set(index: Int, value: T) {
	cExpr<Unit>("((int64_t*)$0)[$1] = $2", this, index, value)
}

@JvmName("get23")
public inline operator fun <T : Long> CPointer<LongVarOf<T>>.get(index: Long): T = cExpr("((int64_t*)$0)[$1]", this, index)

@JvmName("set24")
public inline operator fun <T : Long> CPointer<LongVarOf<T>>.set(index: Long, value: T) {
	cExpr<Unit>("((int64_t*)$0)[$1] = $2", this, index, value)
}

@CType("uint8_t")
public class UByteVarOf<T : UByte> private constructor() : CPrimitiveVar()

public typealias UByteVar = UByteVarOf<UByte>

public inline var <T : UByte> UByteVarOf<T>.value: T
	get() = cExpr("*(uint8_t*)$0", this)
	set(value) {
		cExpr<Unit>("*(uint8_t*)$0 = $1", this, value)
	}

@JvmName("get25")
public inline operator fun <T : UByte> CPointer<UByteVarOf<T>>.get(index: Int): T = cExpr("((uint8_t*)$0)[$1]", this, index)

@JvmName("set26")
public inline operator fun <T : UByte> CPointer<UByteVarOf<T>>.set(index: Int, value: T) {
	cExpr<Unit>("((uint8_t*)$0)[$1] = $2", this, index, value)
}

@JvmName("get27")
public inline operator fun <T : UByte> CPointer<UByteVarOf<T>>.get(index: Long): T = cExpr("((uint8_t*)$0)[$1]", this, index)

@JvmName("set28")
public inline operator fun <T : UByte> CPointer<UByteVarOf<T>>.set(index: Long, value: T) {
	cExpr<Unit>("((uint8_t*)$0)[$1] = $2", this, index, value)
}

@CType("uint16_t")
public class UShortVarOf<T : UShort> private constructor() : CPrimitiveVar()

public typealias UShortVar = UShortVarOf<UShort>

public inline var <T : UShort> UShortVarOf<T>.value: T
	get() = cExpr("*(uint16_t*)$0", this)
	set(value) {
		cExpr<Unit>("*(uint16_t*)$0 = $1", this, value)
	}

@JvmName("get29")
public inline operator fun <T : UShort> CPointer<UShortVarOf<T>>.get(index: Int): T = cExpr("((uint16_t*)$0)[$1]", this, index)

@JvmName("set30")
public inline operator fun <T : UShort> CPointer<UShortVarOf<T>>.set(index: Int, value: T) {
	cExpr<Unit>("((uint16_t*)$0)[$1] = $2", this, index, value)
}

@JvmName("get31")
public inline operator fun <T : UShort> CPointer<UShortVarOf<T>>.get(index: Long): T = cExpr("((uint16_t*)$0)[$1]", this, index)

@JvmName("set32")
public inline operator fun <T : UShort> CPointer<UShortVarOf<T>>.set(index: Long, value: T) {
	cExpr<Unit>("((uint16_t*)$0)[$1] = $2", this, index, value)
}

@CType("uint32_t")
public class UIntVarOf<T : UInt> private constructor() : CPrimitiveVar()

public typealias UIntVar = UIntVarOf<UInt>

public inline var <T : UInt> UIntVarOf<T>.value: T
	get() = cExpr("*(uint32_t*)$0", this)
	set(value) {
		cExpr<Unit>("*(uint32_t*)$0 = $1", this, value)
	}

@JvmName("get33")
public inline operator fun <T : UInt> CPointer<UIntVarOf<T>>.get(index: Int): T = cExpr("((uint32_t*)$0)[$1]", this, index)

@JvmName("set34")
public inline operator fun <T : UInt> CPointer<UIntVarOf<T>>.set(index: Int, value: T) {
	cExpr<Unit>("((uint32_t*)$0)[$1] = $2", this, index, value)
}

@JvmName("get35")
public inline operator fun <T : UInt> CPointer<UIntVarOf<T>>.get(index: Long): T = cExpr("((uint32_t*)$0)[$1]", this, index)

@JvmName("set36")
public inline operator fun <T : UInt> CPointer<UIntVarOf<T>>.set(index: Long, value: T) {
	cExpr<Unit>("((uint32_t*)$0)[$1] = $2", this, index, value)
}

@CType("uint64_t")
public class ULongVarOf<T : ULong> private constructor() : CPrimitiveVar()

public typealias ULongVar = ULongVarOf<ULong>

public inline var <T : ULong> ULongVarOf<T>.value: T
	get() = cExpr("*(uint64_t*)$0", this)
	set(value) {
		cExpr<Unit>("*(uint64_t*)$0 = $1", this, value)
	}

@JvmName("get37")
public inline operator fun <T : ULong> CPointer<ULongVarOf<T>>.get(index: Int): T = cExpr("((uint64_t*)$0)[$1]", this, index)

@JvmName("set38")
public inline operator fun <T : ULong> CPointer<ULongVarOf<T>>.set(index: Int, value: T) {
	cExpr<Unit>("((uint64_t*)$0)[$1] = $2", this, index, value)
}

@JvmName("get39")
public inline operator fun <T : ULong> CPointer<ULongVarOf<T>>.get(index: Long): T = cExpr("((uint64_t*)$0)[$1]", this, index)

@JvmName("set40")
public inline operator fun <T : ULong> CPointer<ULongVarOf<T>>.set(index: Long, value: T) {
	cExpr<Unit>("((uint64_t*)$0)[$1] = $2", this, index, value)
}

@CType("float")
public class FloatVarOf<T : Float> private constructor() : CPrimitiveVar()

public typealias FloatVar = FloatVarOf<Float>

public inline var <T : Float> FloatVarOf<T>.value: T
	get() = cExpr("*(float*)$0", this)
	set(value) {
		cExpr<Unit>("*(float*)$0 = $1", this, value)
	}

@JvmName("get41")
public inline operator fun <T : Float> CPointer<FloatVarOf<T>>.get(index: Int): T = cExpr("((float*)$0)[$1]", this, index)

@JvmName("set42")
public inline operator fun <T : Float> CPointer<FloatVarOf<T>>.set(index: Int, value: T) {
	cExpr<Unit>("((float*)$0)[$1] = $2", this, index, value)
}

@JvmName("get43")
public inline operator fun <T : Float> CPointer<FloatVarOf<T>>.get(index: Long): T = cExpr("((float*)$0)[$1]", this, index)

@JvmName("set44")
public inline operator fun <T : Float> CPointer<FloatVarOf<T>>.set(index: Long, value: T) {
	cExpr<Unit>("((float*)$0)[$1] = $2", this, index, value)
}

@CType("double")
public class DoubleVarOf<T : Double> private constructor() : CPrimitiveVar()

public typealias DoubleVar = DoubleVarOf<Double>

public inline var <T : Double> DoubleVarOf<T>.value: T
	get() = cExpr("*(double*)$0", this)
	set(value) {
		cExpr<Unit>("*(double*)$0 = $1", this, value)
	}

@JvmName("get45")
public inline operator fun <T : Double> CPointer<DoubleVarOf<T>>.get(index: Int): T = cExpr("((double*)$0)[$1]", this, index)

@JvmName("set46")
public inline operator fun <T : Double> CPointer<DoubleVarOf<T>>.set(index: Int, value: T) {
	cExpr<Unit>("((double*)$0)[$1] = $2", this, index, value)
}

@JvmName("get47")
public inline operator fun <T : Double> CPointer<DoubleVarOf<T>>.get(index: Long): T = cExpr("((double*)$0)[$1]", this, index)

@JvmName("set48")
public inline operator fun <T : Double> CPointer<DoubleVarOf<T>>.set(index: Long, value: T) {
	cExpr<Unit>("((double*)$0)[$1] = $2", this, index, value)
}

@CType("void*")
public class CPointerVarOf<T : CPointer<*>> private constructor() : CVariable()

public typealias CPointerVar<T> = CPointerVarOf<CPointer<T>>

public inline var <P : CPointer<*>> CPointerVarOf<P>.value: P?
	get() = cExpr("*(void**)$0", this)
	set(value) {
		cExpr<Unit>("*(void**)$0 = $1", this, value)
	}

/** What the pointer held by this variable points at. */
public inline val <T : CPointed, P : CPointer<T>> CPointerVarOf<P>.pointed: T? get() = cExpr("*(void**)$0", this)

@JvmName("get49")
public operator fun <T : CPointer<*>> CPointer<CPointerVarOf<T>>.get(index: Int): T? = cExpr("((void**)$0)[$1]", this, index)

@JvmName("set50")
public operator fun <T : CPointer<*>> CPointer<CPointerVarOf<T>>.set(index: Int, value: T?) {
	cExpr<Unit>("((void**)$0)[$1] = $2", this, index, value)
}

// ==================
// MARK: Allocation
// ==================

/** Somewhere C memory can be taken from. */
public interface NativePlacement {
	/** [size] zeroed bytes. */
	public fun alloc(size: Long, align: Int): NativePointed
}

public interface NativeFreeablePlacement : NativePlacement {
	public fun free(mem: NativePtr)
}

/** `malloc` and `free`. */
public object nativeHeap : NativeFreeablePlacement {
	override fun alloc(size: Long, align: Int): NativePointed {
		val vMemory: NativePtr = cExpr("calloc(1, (size_t)$0)", if (size > 0L) size else 1L)
		if (vMemory == nativeNullPtr) throw Error("unable to allocate $size bytes")
		return cExpr("$0", vMemory)
	}

	override fun free(mem: NativePtr) {
		cExpr<Unit>("free($0)", mem)
	}
}

public fun NativeFreeablePlacement.free(pointer: CPointer<*>) {
	free(pointer.rawValue)
}

public fun NativeFreeablePlacement.free(pointed: NativePointed) {
	free(pointed.rawPtr)
}

public inline fun <reified T : CVariable> NativePlacement.alloc(): T = cExpr("$0", alloc(cSizeOf<T>(), cAlignOf<T>()))

public inline fun <reified T : CVariable> NativePlacement.alloc(initialize: T.() -> Unit): T {
	val vResult = alloc<T>()
	vResult.initialize()
	return vResult
}

public inline fun <reified T : CVariable> NativePlacement.allocArray(length: Long): CArrayPointer<T> =
	cExpr("$0", alloc(cSizeOf<T>() * length, cAlignOf<T>()))

public inline fun <reified T : CVariable> NativePlacement.allocArray(length: Int): CArrayPointer<T> = allocArray(length.toLong())

public inline fun <reified T : CVariable> NativePlacement.allocArray(length: Int, initializer: T.(index: Int) -> Unit): CArrayPointer<T> {
	val vResult = allocArray<T>(length)
	for (vIndex in 0 until length) vResult[vIndex].initializer(vIndex)
	return vResult
}

public inline fun <reified T : CPointed> NativePlacement.allocPointerTo(): CPointerVar<T> = alloc()

/** Memory that is freed when the scope ends: what `memScoped { }` gives its block. */
public open class MemScope : NativePlacement {

	/** The last block allocated; each block starts with the address of the one before. */
	private var mHead: NativePtr = nativeNullPtr

	public val memScope: MemScope get() = this

	override fun alloc(size: Long, align: Int): NativePointed {
		val vBlock: NativePtr = cExpr("ktc2_scope_alloc($0, $1)", mHead, size)
		mHead = vBlock
		return cExpr("(void*)((char*)$0 + 16)", vBlock)
	}

	/** Frees everything allocated in this scope. */
	public fun clearImpl() {
		cExpr<Unit>("ktc2_scope_free($0)", mHead)
		mHead = nativeNullPtr
	}

	/** Where the values are, for the rest of the scope. */
	public val <T : CVariable> CValues<T>.ptr: CPointer<T> get() = cExpr("$0", this@ptr)
}

public typealias AutofreeScope = MemScope

/** Runs [block] with a scope whose allocations are freed when it ends. */
public inline fun <R> memScoped(block: MemScope.() -> R): R {
	val vScope = MemScope()
	try {
		return vScope.block()
	} finally {
		vScope.clearImpl()
	}
}

public fun <T : CVariable> CValues<T>.getPointer(scope: AutofreeScope): CPointer<T> = cExpr("$0", this)

// ==================
// MARK: Structs by value
// ==================

/** A zeroed struct value. */
public inline fun <reified T : CStructVar> cValue(): CValue<T> = cExpr("ktc2_array_new((int32_t)$0, 1, KTC2_TYPE_ARRAY_BYTE)", cSizeOf<T>())

/** A struct value filled in by [initialize]. */
public inline fun <reified T : CStructVar> cValue(initialize: T.() -> Unit): CValue<T> {
	val vValue = cValue<T>()
	vValue.useContents(initialize)
	return vValue
}

/**
 * Runs [block] on the struct inside this value. Unlike Kotlin/Native, which hands the block a
 * temporary copy, the block sees the value itself: what it writes stays.
 */
public inline fun <reified T : CStructVar, R> CValue<T>.useContents(block: T.() -> R): R =
	cExpr<T>("ktc2_cvalue_data($0)", this).block()

/** A copy, as a value, of the struct at this location. */
public inline fun <reified T : CStructVar> T.readValue(): CValue<T> = cExpr("ktc2_cvalue_copy($0, $1)", this, cSizeOf<T>())

/** A copy of this value changed by [modify]. */
public inline fun <reified T : CStructVar> CValue<T>.copy(modify: T.() -> Unit): CValue<T> {
	val vCopy: CValue<T> = cExpr("ktc2_cvalue_copy(ktc2_cvalue_data($0), $1)", this, cSizeOf<T>())
	vCopy.useContents(modify)
	return vCopy
}

/** Copies the struct to [placement] and returns it. */
public inline fun <reified T : CStructVar> CValue<T>.place(placement: CPointer<T>): CPointer<T> {
	cExpr<Unit>("memcpy($0, ktc2_cvalue_data($1), (size_t)$2)", placement, this, cSizeOf<T>())
	return placement
}

/** Copies the struct to [location]. */
public inline fun <reified T : CStructVar> CValue<T>.write(location: NativePtr) {
	cExpr<Unit>("memcpy($0, ktc2_cvalue_data($1), (size_t)$2)", location, this, cSizeOf<T>())
}

// ==================
// MARK: Values for a call
// ==================

/*
C values to pass by address — `sum(cValuesOf(1, 2, 3), 3)`. Each is a counted copy of the
elements, alive for as long as the Kotlin code holds it.
*/

public fun cValuesOf(vararg elements: Byte): CValue<ByteVar> = cExpr("ktc2_cvalue_copy((ktc2_Array*)$0 + 1, (int64_t)$1 * 1)", elements, elements.size)

public fun ByteArray.toCValues(): CValue<ByteVar> = cExpr("ktc2_cvalue_copy((ktc2_Array*)$0 + 1, (int64_t)$1 * 1)", this, size)

public fun cValuesOf(vararg elements: Short): CValue<ShortVar> = cExpr("ktc2_cvalue_copy((ktc2_Array*)$0 + 1, (int64_t)$1 * 2)", elements, elements.size)

public fun ShortArray.toCValues(): CValue<ShortVar> = cExpr("ktc2_cvalue_copy((ktc2_Array*)$0 + 1, (int64_t)$1 * 2)", this, size)

public fun cValuesOf(vararg elements: Int): CValue<IntVar> = cExpr("ktc2_cvalue_copy((ktc2_Array*)$0 + 1, (int64_t)$1 * 4)", elements, elements.size)

public fun IntArray.toCValues(): CValue<IntVar> = cExpr("ktc2_cvalue_copy((ktc2_Array*)$0 + 1, (int64_t)$1 * 4)", this, size)

public fun cValuesOf(vararg elements: Long): CValue<LongVar> = cExpr("ktc2_cvalue_copy((ktc2_Array*)$0 + 1, (int64_t)$1 * 8)", elements, elements.size)

public fun LongArray.toCValues(): CValue<LongVar> = cExpr("ktc2_cvalue_copy((ktc2_Array*)$0 + 1, (int64_t)$1 * 8)", this, size)

public fun cValuesOf(vararg elements: Float): CValue<FloatVar> = cExpr("ktc2_cvalue_copy((ktc2_Array*)$0 + 1, (int64_t)$1 * 4)", elements, elements.size)

public fun FloatArray.toCValues(): CValue<FloatVar> = cExpr("ktc2_cvalue_copy((ktc2_Array*)$0 + 1, (int64_t)$1 * 4)", this, size)

public fun cValuesOf(vararg elements: Double): CValue<DoubleVar> = cExpr("ktc2_cvalue_copy((ktc2_Array*)$0 + 1, (int64_t)$1 * 8)", elements, elements.size)

public fun DoubleArray.toCValues(): CValue<DoubleVar> = cExpr("ktc2_cvalue_copy((ktc2_Array*)$0 + 1, (int64_t)$1 * 8)", this, size)

// ==================
// MARK: Strings
// ==================

/** The UTF-8 bytes of this string, NUL-terminated, as C reads them. Valid while the string is. */
public inline val String.cstr: CValues<ByteVar> get() = cExpr("$0", this)

public val String.utf8: CValues<ByteVar> get() = cExpr("$0", this)

/** A String from the NUL-terminated UTF-8 text this points at. */
public fun CPointer<ByteVar>.toKString(): String = cExpr("ktc2_string_of_cstr((const char*)$0)", this)

public fun CPointer<ByteVar>.toKStringFromUtf8(): String = toKString()

// ==================
// MARK: Kotlin arrays and objects seen from C
// ==================

/** An object whose address was given to C; nothing moves objects here, so this only names the intent. */
public class Pinned<out T : Any> @PublishedApi internal constructor(private val mObject: T) {
	public fun get(): T = mObject

	public fun unpin() {}
}

public fun <T : Any> T.pin(): Pinned<T> = Pinned(this)

public inline fun <T : Any, R> T.usePinned(block: (Pinned<T>) -> R): R = block(Pinned(this))

/** The address of element [index] of this array, for a C function that reads or fills it during the call. */
public fun ByteArray.refTo(index: Int): CValuesRef<ByteVar> = cExpr("(void*)((char*)((ktc2_Array*)$0 + 1) + (size_t)$1 * 1)", this, index)

@JvmName("addressOf51")
public fun Pinned<ByteArray>.addressOf(index: Int): CPointer<ByteVar> = cExpr("(void*)((char*)((ktc2_Array*)$0 + 1) + (size_t)$1 * 1)", get(), index)

/** The address of element [index] of this array, for a C function that reads or fills it during the call. */
public fun ShortArray.refTo(index: Int): CValuesRef<ShortVar> = cExpr("(void*)((char*)((ktc2_Array*)$0 + 1) + (size_t)$1 * 2)", this, index)

@JvmName("addressOf52")
public fun Pinned<ShortArray>.addressOf(index: Int): CPointer<ShortVar> = cExpr("(void*)((char*)((ktc2_Array*)$0 + 1) + (size_t)$1 * 2)", get(), index)

/** The address of element [index] of this array, for a C function that reads or fills it during the call. */
public fun IntArray.refTo(index: Int): CValuesRef<IntVar> = cExpr("(void*)((char*)((ktc2_Array*)$0 + 1) + (size_t)$1 * 4)", this, index)

@JvmName("addressOf53")
public fun Pinned<IntArray>.addressOf(index: Int): CPointer<IntVar> = cExpr("(void*)((char*)((ktc2_Array*)$0 + 1) + (size_t)$1 * 4)", get(), index)

/** The address of element [index] of this array, for a C function that reads or fills it during the call. */
public fun LongArray.refTo(index: Int): CValuesRef<LongVar> = cExpr("(void*)((char*)((ktc2_Array*)$0 + 1) + (size_t)$1 * 8)", this, index)

@JvmName("addressOf54")
public fun Pinned<LongArray>.addressOf(index: Int): CPointer<LongVar> = cExpr("(void*)((char*)((ktc2_Array*)$0 + 1) + (size_t)$1 * 8)", get(), index)

/** The address of element [index] of this array, for a C function that reads or fills it during the call. */
public fun UByteArray.refTo(index: Int): CValuesRef<UByteVar> = cExpr("(void*)((char*)((ktc2_Array*)$0 + 1) + (size_t)$1 * 1)", this, index)

@JvmName("addressOf55")
public fun Pinned<UByteArray>.addressOf(index: Int): CPointer<UByteVar> = cExpr("(void*)((char*)((ktc2_Array*)$0 + 1) + (size_t)$1 * 1)", get(), index)

/** The address of element [index] of this array, for a C function that reads or fills it during the call. */
public fun UShortArray.refTo(index: Int): CValuesRef<UShortVar> = cExpr("(void*)((char*)((ktc2_Array*)$0 + 1) + (size_t)$1 * 2)", this, index)

@JvmName("addressOf56")
public fun Pinned<UShortArray>.addressOf(index: Int): CPointer<UShortVar> = cExpr("(void*)((char*)((ktc2_Array*)$0 + 1) + (size_t)$1 * 2)", get(), index)

/** The address of element [index] of this array, for a C function that reads or fills it during the call. */
public fun UIntArray.refTo(index: Int): CValuesRef<UIntVar> = cExpr("(void*)((char*)((ktc2_Array*)$0 + 1) + (size_t)$1 * 4)", this, index)

@JvmName("addressOf57")
public fun Pinned<UIntArray>.addressOf(index: Int): CPointer<UIntVar> = cExpr("(void*)((char*)((ktc2_Array*)$0 + 1) + (size_t)$1 * 4)", get(), index)

/** The address of element [index] of this array, for a C function that reads or fills it during the call. */
public fun ULongArray.refTo(index: Int): CValuesRef<ULongVar> = cExpr("(void*)((char*)((ktc2_Array*)$0 + 1) + (size_t)$1 * 8)", this, index)

@JvmName("addressOf58")
public fun Pinned<ULongArray>.addressOf(index: Int): CPointer<ULongVar> = cExpr("(void*)((char*)((ktc2_Array*)$0 + 1) + (size_t)$1 * 8)", get(), index)

/** The address of element [index] of this array, for a C function that reads or fills it during the call. */
public fun FloatArray.refTo(index: Int): CValuesRef<FloatVar> = cExpr("(void*)((char*)((ktc2_Array*)$0 + 1) + (size_t)$1 * 4)", this, index)

@JvmName("addressOf59")
public fun Pinned<FloatArray>.addressOf(index: Int): CPointer<FloatVar> = cExpr("(void*)((char*)((ktc2_Array*)$0 + 1) + (size_t)$1 * 4)", get(), index)

/** The address of element [index] of this array, for a C function that reads or fills it during the call. */
public fun DoubleArray.refTo(index: Int): CValuesRef<DoubleVar> = cExpr("(void*)((char*)((ktc2_Array*)$0 + 1) + (size_t)$1 * 8)", this, index)

@JvmName("addressOf60")
public fun Pinned<DoubleArray>.addressOf(index: Int): CPointer<DoubleVar> = cExpr("(void*)((char*)((ktc2_Array*)$0 + 1) + (size_t)$1 * 8)", get(), index)

/**
 * A reference to a Kotlin object that C code can keep: the object stays alive until
 * [dispose], however long C holds the pointer.
 */
public class StableRef<out T : Any> private constructor() {

	public companion object {
		public fun <T : Any> create(any: T): StableRef<T> = cExpr("ktc2_retain($0)", any)
	}

	public fun asCPointer(): COpaquePointer = cExpr("$0", this)

	public fun get(): T = cExpr("(ktc2_Obj*)ktc2_retain($0)", this)

	/** Gives the reference up; the pointer must not be used afterwards. */
	public fun dispose() {
		cExpr<Unit>("ktc2_release($0)", this)
	}
}

public inline fun <T : Any> CPointer<*>.asStableRef(): StableRef<T> = cExpr("$0", this)

// ==================
// MARK: Functions
// ==================

/*
Calling a C function through its pointer. The pointer is cast to the C function type that the
Kotlin types of the call give — Int is int32_t, a pointer is void* — which is the same call
for every C type of the same size and kind.
*/

public inline operator fun <R> CPointer<CFunction<() -> R>>.invoke(): R = cCall(this)

public inline operator fun <P1, R> CPointer<CFunction<(P1) -> R>>.invoke(p1: P1): R = cCall(this, p1)

public inline operator fun <P1, P2, R> CPointer<CFunction<(P1, P2) -> R>>.invoke(p1: P1, p2: P2): R = cCall(this, p1, p2)

public inline operator fun <P1, P2, P3, R> CPointer<CFunction<(P1, P2, P3) -> R>>.invoke(p1: P1, p2: P2, p3: P3): R = cCall(this, p1, p2, p3)

public inline operator fun <P1, P2, P3, P4, R> CPointer<CFunction<(P1, P2, P3, P4) -> R>>.invoke(p1: P1, p2: P2, p3: P3, p4: P4): R = cCall(this, p1, p2, p3, p4)

public inline operator fun <P1, P2, P3, P4, P5, R> CPointer<CFunction<(P1, P2, P3, P4, P5) -> R>>.invoke(p1: P1, p2: P2, p3: P3, p4: P4, p5: P5): R = cCall(this, p1, p2, p3, p4, p5)

public inline operator fun <P1, P2, P3, P4, P5, P6, R> CPointer<CFunction<(P1, P2, P3, P4, P5, P6) -> R>>.invoke(p1: P1, p2: P2, p3: P3, p4: P4, p5: P5, p6: P6): R = cCall(this, p1, p2, p3, p4, p5, p6)

public inline operator fun <P1, P2, P3, P4, P5, P6, P7, R> CPointer<CFunction<(P1, P2, P3, P4, P5, P6, P7) -> R>>.invoke(p1: P1, p2: P2, p3: P3, p4: P4, p5: P5, p6: P6, p7: P7): R = cCall(this, p1, p2, p3, p4, p5, p6, p7)

public inline operator fun <P1, P2, P3, P4, P5, P6, P7, P8, R> CPointer<CFunction<(P1, P2, P3, P4, P5, P6, P7, P8) -> R>>.invoke(p1: P1, p2: P2, p3: P3, p4: P4, p5: P5, p6: P6, p7: P7, p8: P8): R = cCall(this, p1, p2, p3, p4, p5, p6, p7, p8)

/**
 * The C function pointer of a top-level Kotlin function, given as `::name`, or of a lambda
 * that uses nothing from around it.
 */
public fun <R> staticCFunction(function: () -> R): CPointer<CFunction<() -> R>> = cFunction(function)

public fun <P1, R> staticCFunction(function: (P1) -> R): CPointer<CFunction<(P1) -> R>> = cFunction(function)

public fun <P1, P2, R> staticCFunction(function: (P1, P2) -> R): CPointer<CFunction<(P1, P2) -> R>> = cFunction(function)

public fun <P1, P2, P3, R> staticCFunction(function: (P1, P2, P3) -> R): CPointer<CFunction<(P1, P2, P3) -> R>> = cFunction(function)

public fun <P1, P2, P3, P4, R> staticCFunction(function: (P1, P2, P3, P4) -> R): CPointer<CFunction<(P1, P2, P3, P4) -> R>> = cFunction(function)

public fun <P1, P2, P3, P4, P5, R> staticCFunction(function: (P1, P2, P3, P4, P5) -> R): CPointer<CFunction<(P1, P2, P3, P4, P5) -> R>> = cFunction(function)

public fun <P1, P2, P3, P4, P5, P6, R> staticCFunction(function: (P1, P2, P3, P4, P5, P6) -> R): CPointer<CFunction<(P1, P2, P3, P4, P5, P6) -> R>> = cFunction(function)

public fun <P1, P2, P3, P4, P5, P6, P7, R> staticCFunction(function: (P1, P2, P3, P4, P5, P6, P7) -> R): CPointer<CFunction<(P1, P2, P3, P4, P5, P6, P7) -> R>> = cFunction(function)

public fun <P1, P2, P3, P4, P5, P6, P7, P8, R> staticCFunction(function: (P1, P2, P3, P4, P5, P6, P7, P8) -> R): CPointer<CFunction<(P1, P2, P3, P4, P5, P6, P7, P8) -> R>> = cFunction(function)
