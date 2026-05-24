@file:cInclude("SDL3/SDL.h")

package sdl3

/**
 * Run a delta-timed game loop capped at targetFps.
 * The block receives dt (seconds since last frame) and returns true to keep running.
 * Inline — the block lambda expands at the call site with no extra call overhead.
 */
inline fun gameLoop(targetFps: Int = 60, block: (Float) -> Boolean) {
    val frameMs = 1000L / targetFps.toLong()
    var lastTick = SDL3.ticks()
    var running  = true
    while (running) {
        val now  = SDL3.ticks()
        val dt   = (now - lastTick).toFloat() / 1000.0f
        lastTick = now
        running  = block(dt)
        val elapsed = SDL3.ticks() - now
        if (elapsed < frameMs) SDL3.delay((frameMs - elapsed).toInt())
    }
}
