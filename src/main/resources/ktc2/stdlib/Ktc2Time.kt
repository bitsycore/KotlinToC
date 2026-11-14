@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "ACTUAL_ANNOTATIONS_NOT_MATCH_EXPECT", "ACTUAL_WITHOUT_EXPECT")

package kotlin.time

import kotlin.time.TimeSource.Monotonic.ValueTimeMark
import ktc2.internal.ticksNanos

/*
What the standard library's kotlin.time leaves to its platform: the units of a duration and
the conversions between them, how a number of seconds is written, and the clock behind
`TimeSource.Monotonic` and `measureTime`.
*/

// ==================
// MARK: Units
// ==================

public actual enum class DurationUnit(internal val nanoseconds: Long) {
	NANOSECONDS(1L),
	MICROSECONDS(1_000L),
	MILLISECONDS(1_000_000L),
	SECONDS(1_000_000_000L),
	MINUTES(60_000_000_000L),
	HOURS(3_600_000_000_000L),
	DAYS(86_400_000_000_000L),
}

internal actual fun convertDurationUnit(value: Double, sourceUnit: DurationUnit, targetUnit: DurationUnit): Double {
	val vSource = sourceUnit.nanoseconds
	val vTarget = targetUnit.nanoseconds
	return when {
		vSource > vTarget -> value * (vSource / vTarget)
		vSource < vTarget -> value / (vTarget / vSource)
		else -> value
	}
}

/** The conversion as plain arithmetic: a result too large wraps around. */
internal actual fun convertDurationUnitOverflow(value: Long, sourceUnit: DurationUnit, targetUnit: DurationUnit): Long {
	val vSource = sourceUnit.nanoseconds
	val vTarget = targetUnit.nanoseconds
	return when {
		vSource > vTarget -> value * (vSource / vTarget)
		vSource < vTarget -> value / (vTarget / vSource)
		else -> value
	}
}

/** The conversion with a result too large held at the largest or smallest Long. */
internal actual fun convertDurationUnit(value: Long, sourceUnit: DurationUnit, targetUnit: DurationUnit): Long {
	val vSource = sourceUnit.nanoseconds
	val vTarget = targetUnit.nanoseconds
	return when {
		vSource > vTarget -> {
			val vScale = vSource / vTarget
			val vResult = value * vScale
			when {
				vResult / vScale == value -> vResult
				value > 0 -> Long.MAX_VALUE
				else -> Long.MIN_VALUE
			}
		}
		vSource < vTarget -> value / (vTarget / vSource)
		else -> value
	}
}

// ==================
// MARK: Formatting
// ==================

internal actual val durationAssertionsEnabled: Boolean = false

/** [value] with exactly [decimals] digits after the point, the last one rounded half up. */
internal actual fun formatToExactDecimals(value: Double, decimals: Int): String {
	var vScale = 1L
	repeat(decimals) { vScale *= 10L }
	val vMagnitude = if (value < 0) -value else value
	if (vMagnitude >= 9.0e18) {
		// More than a Long holds: the digits the number is written with, then zeros.
		val vShown = vMagnitude.toString()
		val vDigits = vShown.substringBefore('E').replace(".", "")
		val vExponent = vShown.substringAfter('E', "0").toInt()
		val vLarge = StringBuilder()
		if (value < 0) vLarge.append('-')
		vLarge.append(vDigits)
		repeat(vExponent + 1 - vDigits.length) { vLarge.append('0') }
		if (decimals > 0) {
			vLarge.append('.')
			repeat(decimals) { vLarge.append('0') }
		}
		return vLarge.toString()
	}
	// The whole part and the decimals each by themselves: together they may be more than a Long holds.
	var vWhole = vMagnitude.toLong()
	var vDecimals = ((vMagnitude - vWhole) * vScale + 0.5).toLong()
	if (vDecimals >= vScale) {
		vDecimals -= vScale
		vWhole++
	}
	val vText = StringBuilder()
	if (value < 0 && (vWhole != 0L || vDecimals != 0L)) vText.append('-')
	vText.append(vWhole)
	if (decimals > 0) {
		vText.append('.')
		val vFraction = vDecimals.toString()
		repeat(decimals - vFraction.length) { vText.append('0') }
		vText.append(vFraction)
	}
	return vText.toString()
}

// ==================
// MARK: Clock
// ==================

internal actual typealias ValueTimeMarkReading = Long

/**
 * The clock of `TimeSource.Monotonic`. It reads the clock of the day, counted from the first
 * use: setting that clock while the program runs moves it.
 */
internal actual object MonotonicTimeSource : TimeSource.WithComparableMarks {
	private val mZero: Long = ticksNanos()

	private fun read(): Long = ticksNanos() - mZero

	override fun toString(): String = "TimeSource(System.nanoTime())"

	actual override fun markNow(): ValueTimeMark = ValueTimeMark(read())

	actual fun elapsedFrom(timeMark: ValueTimeMark): Duration =
		saturatingDiff(read(), timeMark.reading, DurationUnit.NANOSECONDS)

	actual fun differenceBetween(one: ValueTimeMark, another: ValueTimeMark): Duration =
		saturatingOriginsDiff(one.reading, another.reading, DurationUnit.NANOSECONDS)

	actual fun adjustReading(timeMark: ValueTimeMark, duration: Duration): ValueTimeMark =
		ValueTimeMark(saturatingAdd(timeMark.reading, DurationUnit.NANOSECONDS, duration))
}
