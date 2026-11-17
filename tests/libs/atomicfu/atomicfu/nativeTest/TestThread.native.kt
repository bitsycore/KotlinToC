package kotlinx.atomicfu.locks

// ktc2's own, in the place of atomicfu's nativeTest/TestThread.native.kt, which starts a
// kotlin.native.concurrent.Worker for each thread (ktc2 has no Worker yet): a thread of
// ktc2.concurrent, as the JVM's helper has one of kotlin.concurrent.

internal actual class TestThread actual constructor(toDo: () -> Unit) {
	private val th = ktc2.concurrent.thread { toDo() }
	actual fun join() = th.join()
}

actual fun sleepMillis(millis: Long) = ktc2.concurrent.sleep(millis)
