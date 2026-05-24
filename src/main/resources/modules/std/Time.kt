package ktc.std

object Time {

	fun currentMs(): Long {
		return c.ktc_core_time_ms()
	}

	fun currentSeconds(): Double {
		return c.ktc_core_time_seconds()
	}

	fun sleepMs(ms: Long) {
		c.ktc_core_time_sleep_ms(ms)
	}

	fun sleepSeconds(seconds: Double) {
		c.ktc_core_time_sleep_seconds(seconds)
	}

}