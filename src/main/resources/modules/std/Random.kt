package ktc.std

@Tls
object Random {

	private var state: ULong = 0x853c49e6748fea9bUL;
	private var inc: ULong = 0xda3e39cb94b95bdbUL;

    /**
	Seeded once at program start via srand(time(NULL)).
	All methods share this thread-local PRNG state.te.
	*/
	init {
		c.ktc_core_srand(state.ptr(), inc.ptr(), c.time(c.NULL))
	}

	/**
	Returns a non-negative random Int when called with no argument (until <= 0),
	or a random Int in [0, until) when until > 0.
	Matches Kotlin's Random.nextInt() and Random.nextInt(until: Int).
	*/
	fun nextInt(until: Int = 0): Int {
		if (until <= 0) return c.ktc_core_rand(state.ptr(), inc.ptr())
		return c.ktc_core_rand(state.ptr(), inc.ptr()) % until
	}

	/**
	Returns a random Int in [from, until).
	Matches Kotlin's Random.nextInt(from: Int, until: Int).
	*/
	fun nextInt(from: Int, until: Int): Int {
		return from + c.ktc_core_rand(state.ptr(), inc.ptr()) % (until - from)
	}

	/**
	Returns a non-negative random Long when called with no argument (until <= 0L),
	or a random Long in [0, until) when until > 0L.
	Two rand() calls are combined so the result spans at least 30 bits even
	on platforms where KTC_RAND_MAX is only 32767.
	Matches Kotlin's Random.nextLong() and Random.nextLong(until: Long).
	*/
	fun nextLong(until: Long = 0L): Long {
		val vA: Long = c.ktc_core_rand(state.ptr(), inc.ptr()).toLong()
		val vB: Long = c.ktc_core_rand(state.ptr(), inc.ptr()).toLong()
		val vRaw: Long = vA * (c.KTC_RAND_MAX.toLong() + 1L) + vB
		if (until <= 0L) return vRaw
		return vRaw % until
	}

	/**
	Returns a random Long in [from, until).
	Matches Kotlin's Random.nextLong(from: Long, until: Long).
	*/
	fun nextLong(from: Long, until: Long): Long {
		val vA: Long = c.ktc_core_rand(state.ptr(), inc.ptr()).toLong()
		val vB: Long = c.ktc_core_rand(state.ptr(), inc.ptr()).toLong()
		val vRaw: Long = vA * (c.KTC_RAND_MAX.toLong() + 1L) + vB
		return from + vRaw % (until - from)
	}

	/**
	Returns a random Float uniformly distributed in [0.0, 1.0).
	Matches Kotlin's Random.nextFloat().
	*/
	fun nextFloat(): Float {
		return c.ktc_core_rand(state.ptr(), inc.ptr()).toFloat() / (c.KTC_RAND_MAX.toFloat() + 1.0f)
	}

	/**
	Returns a random Double uniformly distributed in [0.0, 1.0).
	Matches Kotlin's Random.nextDouble().
	*/
	fun nextDouble(): Double {
		return c.ktc_core_rand(state.ptr(), inc.ptr()).toDouble() / (c.KTC_RAND_MAX.toDouble() + 1.0)
	}

	/**
	Returns a random Double in [from, until).
	Matches Kotlin's Random.nextDouble(from: Double, until: Double).
	*/
	fun nextDouble(from: Double, until: Double): Double {
		val vRaw: Double = c.ktc_core_rand(state.ptr(), inc.ptr()).toDouble() / (c.KTC_RAND_MAX.toDouble() + 1.0)
		return from + vRaw * (until - from)
	}

	/**
	Returns a random Boolean.
	Matches Kotlin's Random.nextBoolean().
	*/
	fun nextBoolean(): Boolean {
		return c.ktc_core_rand(state.ptr(), inc.ptr()) % 2 != 0
	}
}
