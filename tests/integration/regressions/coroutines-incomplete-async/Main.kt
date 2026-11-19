// kotlinx.coroutines' AsyncTest.testIncompleteAsyncState: a job whose result is one of its own handlers (the
// DisposableHandle its block returns) keeps that node boxed as its state, and the node names the job. The module
// declares that cycle (JobSupport._state -> IncompleteStateBox.state -> JobNode.job); it was not freed.

import kotlinx.coroutines.*

@OptIn(ExperimentalCoroutinesApi::class)
fun main() = runBlocking {
	val vDeferred = async {
		coroutineContext[Job]!!.invokeOnCompletion { }
	}
	vDeferred.await().dispose()
	println(vDeferred.getCompleted() is DisposableHandle)
	println(vDeferred.isCompleted)
}
