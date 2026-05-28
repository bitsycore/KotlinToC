package ktc.std

@Namespace
object Time {

	inline fun currentMs(): Long = C.ktc_core_time_ms()
	inline fun currentSeconds(): Double = C.ktc_core_time_seconds()

	inline fun sleepMs(ms: Long) {
		C.ktc_core_time_sleep_ms(ms)
	}

	inline fun sleepSeconds(seconds: Double) {
		C.ktc_core_time_sleep_seconds(seconds)
	}

}