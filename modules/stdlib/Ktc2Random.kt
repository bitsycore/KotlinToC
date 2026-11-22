@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "ACTUAL_ANNOTATIONS_NOT_MATCH_EXPECT", "ACTUAL_WITHOUT_EXPECT")
@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
@file:kotlinx.cinterop.internal.CHeaders("<time.h>")

package kotlin.random

import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.updateAndFetch
import kotlin.native.concurrent.ThreadLocal
import kotlinx.cinterop.internal.cExpr

/*
What kotlin.random leaves to its platform, as Kotlin/Native has it (kotlin-native/runtime/src/main/kotlin/kotlin/random/
Random.kt): `Random.Default` is a generator of each thread, the JVM's linear congruential one, seeded from a sequence
shared by the threads that starts from the clock.
*/

/** `Random.Default`: one for each thread, so that threads never share its state. */
@ThreadLocal
internal actual val defaultRandom: Random = NativeRandom()

internal actual fun doubleFromParts(hi26: Int, low27: Int): Double =
	((hi26.toLong() shl 27) + low27) / (1L shl 53).toDouble()

/** Seeds the calling thread's `Random.Default` (Kotlin/Native's tests use it). */
internal fun overrideNativeRandomSeed(seed: Long) = (defaultRandom as NativeRandom).overrideSeed(seed)

private class NativeRandom : Random() {
	private var mState = stateFromSeed(SeedAllocator.nextSeed())

	override fun nextBits(bitCount: Int): Int {
		val vNext = nextState(mState)
		mState = vNext
		return nextBitsFromState(vNext, bitCount)
	}

	fun overrideSeed(inSeed: Long) {
		mState = stateFromSeed(inSeed)
	}

	companion object {
		private const val kMultiplier = 0x5deece66dL
		private const val kIncrement = 0xbL
		private const val kModulus = 48
		private const val kMask = (1L shl kModulus) - 1

		fun stateFromSeed(inSeed: Long): Long = (inSeed xor kMultiplier) and kMask
		fun nextState(inState: Long): Long = (inState * kMultiplier + kIncrement) and kMask
		fun nextBitsFromState(inState: Long, inBitCount: Int): Int = (inState ushr (kModulus - inBitCount)).toInt()
	}
}

/** The seeds of the threads' generators: a sequence of their own, from the clock of when the program started. */
private object SeedAllocator {
	private val mState = AtomicLong(NativeRandom.stateFromSeed(cExpr<Long>("(int64_t)time(NULL)") * 1_000_000_000L + ktc2.internal.ticksNanos()))

	fun nextSeed(): Long = nextBits(32).toLong().shl(32) + nextBits(32)

	private fun nextBits(inBitCount: Int): Int = NativeRandom.nextBitsFromState(mState.updateAndFetch { NativeRandom.nextState(it) }, inBitCount)
}
