package ktc.std

@Namespace
object Time {

	inline fun currentMs(): Long = c.ktc_core_time_ms()
	inline fun currentSeconds(): Double = c.ktc_core_time_seconds()

	inline fun sleepMs(ms: Long) {
		c.ktc_core_time_sleep_ms(ms)
	}

	inline fun sleepSeconds(seconds: Double) {
		c.ktc_core_time_sleep_seconds(seconds)
	}

}