// kotlinx.coroutines' CombineStressTest.testCancellation: flatMapLatest cancels the combine of the value before
// each time a value comes, on the threads of Dispatchers.Default. A reference not counted there used a freed object.

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@OptIn(ExperimentalCoroutinesApi::class)
fun main() = runBlocking {
	var vLast = 0
	repeat(30) {
		withContext(Dispatchers.Default) {
			flow {
				repeat(1_000) { emit(it) }
			}.flatMapLatest {
				combine(flowOf(it), flowOf(it)) { vLeft, vRight -> vLeft + vRight }
			}.collect { vLast = it }
		}
	}
	println("last: $vLast")
}
