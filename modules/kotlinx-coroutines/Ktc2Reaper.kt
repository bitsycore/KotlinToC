package kotlinx.coroutines

/*
 * ktc2: the reaper of the jobs that ended with an exception (`PLAN.md`, "Who answers a cycle", rule 3).
 *
 * Such a job may be left in cycles with what it ended with: its final state names the exception, which names the job, and
 * the job's channel keeps the same exception as its cause of closing. The checks a release makes start from the object
 * whose count came down, while something (a test, a caller) still holds part of the group; nothing comes down later. So
 * the job is given to the runtime's reaper when it ends (`JobSupport.kt.edit`), which keeps it through its weak cell and
 * keeps nothing alive; the loops of `runBlocking` reap between events and when they end (`Builders.native.kt.edit`), and
 * the program once more when `main` returns. A reap checks each job with its group (the runtime's check of declared
 * cycles and rings) and frees it once nothing outside holds any of it; one still held waits for the next.
 *
 * It answers only the library's own objects: the group the runtime checks is made of the classes the module declares
 * (`cycles`, `rings`), and a cycle through an object of the program is never cut.
 */

internal actual fun ktc2Register(job: Any) = ktc2.internal.reapLater(job)

internal actual fun ktc2Sweep() = ktc2.internal.reap()
