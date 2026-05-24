@file:cInclude("SDL3/SDL.h")

package sdl3

object SDL3 {

    // ==================
    // Window

    class Window(val handle: @Ptr c.SDL_Window) {
        constructor(title: String, width: Int, height: Int, flags: Int = 0)
                : this(createWindow(title, width, height, flags))
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

        val x get() = sdl.x
        val y get() = sdl.y
        val w get() = sdl.w
        val h get() = sdl.h

        constructor(x: Float, y: Float, w: Float, h: Float) : this(c.SDL_FRect(x, y, w, h))

        override fun toString(): String {
            return "FRect(x=${sdl.x}, y=${sdl.y}, w=${sdl.w}, h=${sdl.h})"
        }

        override fun equals(other: @Ptr Any?): Boolean {
            if (this === other) return true
            if (other !is FRect) return false
            return sdl.x == other.sdl.x
                    && sdl.y == other.sdl.y
                    && sdl.w == other.sdl.w
                    && sdl.h == other.sdl.h
        }

        override fun hashCode(): Int {
            var result = sdl.x.hashCode()
            result = 31 * result + sdl.y.hashCode()
            result = 31 * result + sdl.w.hashCode()
            result = 31 * result + sdl.h.hashCode()
            return result
        }

        fun copy(x: Float = this.sdl.x, y: Float = this.sdl.y, w: Float = this.sdl.w, h: Float = this.sdl.h): FRect =
            FRect(x, y, w, h)
    }

    // ===================
    // FPoint

    class FPoint(val sdl: c.SDL_FPoint) {

        val x get() = sdl.x
        val y get() = sdl.y

        constructor(x: Float, y: Float) : this(c.SDL_FPoint(x, y))

        override fun toString(): String = "FPoint(x=${sdl.x}, y=${sdl.y})"

        override fun equals(other: @Ptr Any?): Boolean {
            if (this === other) return true
            if (other !is FPoint) return false
            return sdl.x == other.sdl.x && sdl.y == other.sdl.y
        }

        override fun hashCode(): Int {
            var result = sdl.x.hashCode()
            result = 31 * result + sdl.y.hashCode()
            return result
        }
    }

    // ===================
    // Color

    class Color(val sdl: c.SDL_Color) {

        val r get() = sdl.r
        val g get() = sdl.g
        val b get() = sdl.b
        val a get() = sdl.a

        constructor(r: UByte, g: UByte, b: UByte, a: UByte) : this(c.SDL_Color(r, g, b, a))

        constructor(color: UInt) : this(
            c.SDL_Color(
                ((color shr 24) and 0xFFu).toUByte(),
                ((color shr 16) and 0xFFu).toUByte(),
                ((color shr 8) and 0xFFu).toUByte(),
                (color and 0xFFu).toUByte()
            )
        )

        override fun toString(): String {
            return "Color(r=${sdl.r}, g=${sdl.g}, b=${sdl.b}, a=${sdl.a})"
        }

        override fun equals(other: @Ptr Any?): Boolean {
            if (this === other) return true
            if (other !is Color) return false
            return sdl.r == other.sdl.r
                    && sdl.g == other.sdl.g
                    && sdl.b == other.sdl.b
                    && sdl.a == other.sdl.a
        }

        override fun hashCode(): Int {
            var result = sdl.r.hashCode()
            result = 31 * result + sdl.g.hashCode()
            result = 31 * result + sdl.b.hashCode()
            result = 31 * result + sdl.a.hashCode()
            return result
        }
    }

    // ===================
    // FColor

    class FColor(val sdl: c.SDL_FColor) {

        val r get() = sdl.r
        val g get() = sdl.g
        val b get() = sdl.b
        val a get() = sdl.a

        constructor(r: Float, g: Float, b: Float, a: Float) : this(c.SDL_FColor(r, g, b, a))

        constructor(color: UInt) : this(
            c.SDL_FColor(
                ((color shr 24) and 0xFFu).toUByte() / 255.0f,
                ((color shr 16) and 0xFFu).toUByte() / 255.0f,
                ((color shr 8) and 0xFFu).toUByte() / 255.0f,
                (color and 0xFFu).toUByte() / 255.0f
            )
        )

        override fun toString(): String {
            return "FColor(r=${sdl.r}, g=${sdl.g}, b=${sdl.b}, a=${sdl.a})"
        }

        override fun equals(other: @Ptr Any?): Boolean {
            if (this === other) return true
            if (other !is FColor) return false
            return sdl.r == other.sdl.r
                    && sdl.g == other.sdl.g
                    && sdl.b == other.sdl.b
                    && sdl.a == other.sdl.a
        }

        override fun hashCode(): Int {
            var result = sdl.r.hashCode()
            result = 31 * result + sdl.g.hashCode()
            result = 31 * result + sdl.b.hashCode()
            result = 31 * result + sdl.a.hashCode()
            return result
        }
    }

    // ==================
    // Lib init

    fun initialize(flags: Int = c.SDL_INIT_VIDEO) {
        if (!c.SDL_Init(flags)) error("SDL_Init failed: ${c.SDL_GetError()}")
    }

    fun quit() { c.SDL_Quit() }

    /** Milliseconds elapsed since SDL_Init. */
    fun ticks(): Long = c.SDL_GetTicks()

    /** Sleep for at least ms milliseconds. */
    fun delay(ms: Int) { c.SDL_Delay(ms) }

    object Event
    object Scancode
    object BlendMode
    object Mouse
}

// ==================
// MARK: Window
// ==================

inline fun SDL3.Window.destroy() { c.SDL_DestroyWindow(this.handle) }

inline fun SDL3.Window.setTitle(title: String) { c.SDL_SetWindowTitle(this.handle, title.ptr) }

// ==================
// MARK: Renderer
// ==================

inline fun SDL3.Renderer.destroy() { c.SDL_DestroyRenderer(this.handle) }

/** Set the draw colour for subsequent rendering calls. */
inline fun SDL3.Renderer.setDrawColor(r: Int, g: Int, b: Int, a: Int) {
    c.SDL_SetRenderDrawColor(this.handle, r, g, b, a)
}

inline fun SDL3.Renderer.setDrawColor(color: SDL3.Color) {
    c.SDL_SetRenderDrawColor(this.handle, color.r, color.g, color.b, color.a)
}

/** Clear the render target with the current draw colour. */
inline fun SDL3.Renderer.clear() { c.SDL_RenderClear(this.handle) }

/** Fill a rectangle on the render target. */
inline fun SDL3.Renderer.fillRect(inRect: SDL3.FRect) {
    c.SDL_RenderFillRect(this.handle, c.addr(inRect.sdl))
}

/** Draw the outline of a rectangle on the render target. */
inline fun SDL3.Renderer.drawRect(inRect: SDL3.FRect) {
    c.SDL_RenderRect(this.handle, c.addr(inRect.sdl))
}

/** Draw a line segment on the render target. */
inline fun SDL3.Renderer.drawLine(x1: Float, y1: Float, x2: Float, y2: Float) {
    c.SDL_RenderLine(this.handle, x1, y1, x2, y2)
}

/** Draw a single point on the render target. */
inline fun SDL3.Renderer.drawPoint(x: Float, y: Float) {
    c.SDL_RenderPoint(this.handle, x, y)
}

inline fun SDL3.Renderer.drawPoint(point: SDL3.FPoint) {
    c.SDL_RenderPoint(this.handle, point.x, point.y)
}

/** Set blend mode for subsequent draw calls. */
inline fun SDL3.Renderer.setBlendMode(mode: Int) {
    c.SDL_SetRenderDrawBlendMode(this.handle, mode)
}

/** Set the drawing scale (zoom) factor. */
inline fun SDL3.Renderer.setScale(scaleX: Float, scaleY: Float) {
    c.SDL_SetRenderScale(this.handle, scaleX, scaleY)
}

/** Present the rendered frame to the screen. */
inline fun SDL3.Renderer.present() { c.SDL_RenderPresent(this.handle) }
