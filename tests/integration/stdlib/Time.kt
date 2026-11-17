// kotlin.time: durations, and timing with the monotonic clock.

import ktc2.concurrent.sleep
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.TimeSource
import kotlin.time.measureTime
import kotlin.time.measureTimedValue

fun main() {
	val vShort = 1500.milliseconds
	val vLong = 2.hours + 30.minutes + 15.seconds
	println(vShort)
	println(vLong)
	println(vShort.inWholeMilliseconds.toString() + " " + vLong.inWholeSeconds + " " + vLong.inWholeMinutes)
	println(vLong - vShort)
	println(vShort * 4)
	println((vLong / 2).toString() + " " + (vLong / vShort))
	println(vShort < vLong)
	println(vShort.toDouble(DurationUnit.SECONDS))
	println(vLong.toString(DurationUnit.MINUTES, 2))
	println(Duration.ZERO.toString() + " " + Duration.INFINITE + " " + (-vShort))
	println(vLong.toComponents { vHours, vMinutes, vSeconds, _ -> "$vHours:$vMinutes:$vSeconds" })
	println(90.seconds.toIsoString() + " " + Duration.parse("1h 30m") + " " + Duration.parseOrNull("nonsense"))
	println(listOf(3.seconds, 1.seconds, 2.seconds).sorted())

	val vTook = measureTime { sleep(20) }
	println(vTook >= 15.milliseconds)
	println(vTook < 10.seconds)
	val vMark = TimeSource.Monotonic.markNow()
	sleep(10)
	val vElapsed = vMark.elapsedNow()
	println(vElapsed >= 8.milliseconds)
	println(vMark.hasPassedNow())
	val vLater = TimeSource.Monotonic.markNow()
	println(vLater > vMark)
	println((vLater - vMark) >= 8.milliseconds)
	val (vValue, vDuration) = measureTimedValue { (1..1000).sum() }
	println(vValue.toString() + " " + (vDuration >= Duration.ZERO))
}
