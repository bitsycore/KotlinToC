package kotlinx.coroutines

// ktc2: what the library's common code calls of the reaper of jobs that ended with an exception (Ktc2Reaper.kt).

/** [job] ended with an exception: the reaper of this thread checks it once nothing else holds any of what it left. */
internal expect fun ktc2Register(job: Any)

/** Frees what the jobs of this thread that ended with an exception left that nothing holds any more. */
internal expect fun ktc2Sweep()
