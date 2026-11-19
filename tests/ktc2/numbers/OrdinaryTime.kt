package ktc2test.numbers.ordinarytime

import kotlin.test.Test
import kotlin.time.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

// `kotlin.time` as ordinary code uses it: durations built, added, scaled, compared, shown,
// parsed and taken apart; a test clock; marks of a time source; timing a block. Only what does
// not depend on the real time is printed.

fun describe(d: Duration) = d.toComponents { hours, minutes, seconds, nanoseconds -> "${hours}h ${minutes}m ${seconds}s ${nanoseconds}ns" }

@Test
fun main() {
	val d = 1.hours + 30.minutes + 15.seconds + 250.milliseconds
	println(d)
	println(d.toIsoString())
	println(d.inWholeSeconds)
	println(d.inWholeMilliseconds)
	println(d.toDouble(DurationUnit.MINUTES))
	println(d.toString(DurationUnit.HOURS, 3))
	println(describe(d))
	println(d * 2)
	println(d / 4)
	println(d / 30.minutes)
	println(-d)
	println(d.absoluteValue == (-d).absoluteValue)
	println(listOf(3.seconds, 500.milliseconds, 1.minutes, 2.5.seconds).sorted())
	println(listOf(1.days, 36.hours).maxOrNull())
	println(1500.microseconds)
	println(123456789.nanoseconds)
	println(0.seconds)
	println(Duration.ZERO == 0.milliseconds)
	println(Duration.INFINITE)
	println(-Duration.INFINITE)
	println(Duration.INFINITE.isInfinite())
	println(Duration.INFINITE + 1.days)
	println((5.seconds).isPositive().toString() + " " + (-5).seconds.isNegative())
	println(1.5.days)
	println(0.001.seconds)
	println(1.seconds / 3)
	println(10.seconds.coerceIn(1.seconds, 5.seconds))
	println(90.seconds.inWholeMinutes)
	println(Duration.parse("1h 30m"))
	println(Duration.parse("PT2H3M4.5S"))
	println(Duration.parseOrNull("nonsense"))
	println(Duration.parseIsoString("P1DT2H"))
	println(runCatching { Duration.parse("1x") }.exceptionOrNull()?.let { it::class.simpleName })
	println(2.hours.toComponents { days, hours, minutes, seconds, nanoseconds -> listOf(days, hours, minutes, seconds, nanoseconds) })
	println(5.minutes.toInt(DurationUnit.SECONDS))
	println(3.seconds.toLong(DurationUnit.MILLISECONDS))
	println(7.days.toString(DurationUnit.DAYS))
	println(Long.MAX_VALUE.nanoseconds)
	println((Long.MAX_VALUE / 2).milliseconds)
	println(1.seconds.compareTo(1000.milliseconds))
	println(DurationUnit.entries)

	// A clock moved by hand.
	val clock = TestTimeSource()
	val start = clock.markNow()
	clock += 2.seconds
	val middle = clock.markNow()
	clock += 500.milliseconds
	println(start.elapsedNow())
	println(middle - start)
	println(start < middle)
	println((start + 3.seconds).hasPassedNow())
	println((start + 2.seconds).hasPassedNow())
	println(middle.elapsedNow())
	val (value, took) = clock.measureTimedValue {
		clock += 42.milliseconds
		"computed"
	}
	println("$value in $took")
	println(clock.measureTime { clock += 1.minutes })

	// The real clock: only what must hold.
	val mark = TimeSource.Monotonic.markNow()
	var sum = 0L
	for (i in 1..100_000) sum += i
	val elapsed = mark.elapsedNow()
	println("$sum ${elapsed >= Duration.ZERO} ${mark.hasPassedNow() || elapsed == Duration.ZERO}")
	val timed = measureTimedValue { (1..10).sum() }
	println("${timed.value} ${timed.duration >= Duration.ZERO}")
	println(measureTime { }.isNegative())
}
