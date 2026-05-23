@file:cInclude("SDL3/SDL.h")

package sdl3

object SDL3 {

    // ==================
    // Window

    data class Window(val handle: @Ptr c.SDL_Window) {
        constructor(title: String, width: Int, height: Int, flags: Int = 0) :
                this(createWindow(title, width, height, flags))
    }

    private inline fun createWindow(title: String, width: Int, height: Int, flags: Int = 0): @Ptr c.SDL_Window {
        val vHandle: @Ptr c.SDL_Window = c.SDL_CreateWindow(title.ptr, width, height, flags)
        if (!vHandle) error("SDL_CreateWindow failed: ${c.SDL_GetError()}")
        return vHandle
    }

    // ==================
    // Renderer

    data class Renderer(val handle: @Ptr c.SDL_Renderer) {
        constructor(window: Window) : this(window.createRenderer())
    }

    private inline fun Window.createRenderer(): @Ptr c.SDL_Renderer {
        val vHandle: @Ptr c.SDL_Renderer = c.SDL_CreateRenderer(this.handle, c.NULL)
        if (!vHandle) error("SDL_CreateRenderer failed: ${c.SDL_GetError()}")
        return vHandle
    }

    // ===================
    // FRect

    class FRect(val sdl: c.SDL_FRect) {
        constructor(x: Float, y: Float, w: Float, h: Float) : this(c.SDL_FRect(x, y, w, h))

        override fun toString(): String {
            return "FRect(x=${sdl.x}, y=${sdl.y}, w=${sdl.w}, h=${sdl.h})"
        }

        override fun equals(other: @Ptr Any?): Boolean {
            if (other !is FRect) return false
            return sdl.x == other.sdl.x
                    && sdl.y == other.sdl.y
                    && sdl.w == other.sdl.w
                    && sdl.h == other.sdl.h
        }
    }

    // ==================
    // Lib init

    fun initialize(flags: Int = c.SDL_INIT_VIDEO) {
        if (c.SDL_Init(flags) < 0) error("SDL_Init failed: ${c.SDL_GetError()}")
    }

    fun quit() {
        c.SDL_Quit()
    }

}

// ==================
// MARK: Window
// ==================

fun SDL3.Window.destroy() {
    c.SDL_DestroyWindow(this.handle)
}

// ==================
// MARK: Renderer
// ==================

/** Cleanup SDL_Window handle */
fun SDL3.Renderer.destroy() {
    c.SDL_DestroyRenderer(this.handle)
}

/** Set the draw colour for subsequent rendering calls. */
fun SDL3.Renderer.setDrawColor(r: Int, g: Int, b: Int, a: Int) {
    c.SDL_SetRenderDrawColor(this.handle, r, g, b, a)
}

/** Clear the render target with the current draw colour. */
fun SDL3.Renderer.clear() {
    c.SDL_RenderClear(this.handle)
}

/** Fill a rectangle on the render target.
inRect must be a c.SDL_FRect variable so its address can be taken. */
fun SDL3.Renderer.fillRect(inRect: c.SDL_FRect) {
    c.SDL_RenderFillRect(this.handle, c.addr(inRect))
}

/** Present the rendered frame to the screen. */
fun SDL3.Renderer.present() {
    c.SDL_RenderPresent(this.handle)
}