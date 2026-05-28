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
		C.ktc_core_srand(state.asRef(), inc.asRef(), C.time(C.NULL))
	}

	/**
	Returns a non-negative random Int when called with no argument (until <= 0),
	or a random Int in [0, until) when until > 0.
	Matches Kotlin's Random.nextInt() and Random.nextInt(until: Int).
	*/
	fun nextInt(until: Int = 0): Int {
		if (until <= 0) return C.ktc_core_rand(state.asRef(), inc.asRef())
		return C.ktc_core_rand(state.asRef(), inc.asRef()) % until
	}

	/**
	Returns a random Int in [from, until).
	Matches Kotlin's Random.nextInt(from: Int, until: Int).
	*/
	fun nextInt(from: Int, until: Int): Int {
		return from + C.ktc_core_rand(state.asRef(), inc.asRef()) % (until - from)
	}

	/**
	Returns a non-negative random Long when called with no argument (until <= 0L),
	or a random Long in [0, until) when until > 0L.
	Two rand() calls are combined so the result spans at least 30 bits even
	on platforms where KTC_RAND_MAX is only 32767.
	Matches Kotlin's Random.nextLong() and Random.nextLong(until: Long).
	*/
	fun nextLong(until: Long = 0L): Long {
		val vA: Long = C.ktc_core_rand(state.asRef(), inc.asRef()).toLong()
		val vB: Long = C.ktc_core_rand(state.asRef(), inc.asRef()).toLong()
		val vRaw: Long = vA * (C.KTC_RAND_MAX.toLong() + 1L) + vB
		if (until <= 0L) return vRaw
		return vRaw % until
	}

	/**
	Returns a random Long in [from, until).
	Matches Kotlin's Random.nextLong(from: Long, until: Long).
	*/
	fun nextLong(from: Long, until: Long): Long {
		val vA: Long = C.ktc_core_rand(state.asRef(), inc.asRef()).toLong()
		val vB: Long = C.ktc_core_rand(state.asRef(), inc.asRef()).toLong()
		val vRaw: Long = vA * (C.KTC_RAND_MAX.toLong() + 1L) + vB
		return from + vRaw % (until - from)
	}

	/**
	Returns a random Float uniformly distributed in [0.0, 1.0).
	Matches Kotlin's Random.nextFloat().
	*/
	fun nextFloat(): Float {
		return C.ktc_core_rand(state.asRef(), inc.asRef()).toFloat() / (C.KTC_RAND_MAX.toFloat() + 1.0f)
	}

	/**
	Returns a random Double uniformly distributed in [0.0, 1.0).
	Matches Kotlin's Random.nextDouble().
	*/
	fun nextDouble(): Double {
		return C.ktc_core_rand(state.asRef(), inc.asRef()).toDouble() / (C.KTC_RAND_MAX.toDouble() + 1.0)
	}

	/**
	Returns a random Double in [from, until).
	Matches Kotlin's Random.nextDouble(from: Double, until: Double).
	*/
	fun nextDouble(from: Double, until: Double): Double {
		val vRaw: Double = C.ktc_core_rand(state.asRef(), inc.asRef()).toDouble() / (C.KTC_RAND_MAX.toDouble() + 1.0)
		return from + vRaw * (until - from)
	}

	/**
	Returns a random Boolean.
	Matches Kotlin's Random.nextBoolean().
	*/
	fun nextBoolean(): Boolean {
		return C.ktc_core_rand(state.asRef(), inc.asRef()) % 2 != 0
	}
}
