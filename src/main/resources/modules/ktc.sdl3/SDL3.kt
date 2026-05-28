@file:cInclude("SDL3/SDL.h")

package ktc.sdl3

object SDL3 {

    // ==================
    // Window

    class Window(val handle: Ref<C.SDL_Window>) {
        constructor(title: String, width: Int, height: Int, flags: Int = 0)
                : this(createWindow(title, width, height, flags))
    }

    private inline fun createWindow(title: String, width: Int, height: Int, flags: Int = 0): Ref<C.SDL_Window> {
        val vHandle: Ref<C.SDL_Window> = C.SDL_CreateWindow(title.ptr, width, height, flags)
        if (!vHandle) error("SDL_CreateWindow failed: ${C.SDL_GetError()}")
        return vHandle
    }

    // ==================
    // Renderer

    data class Renderer(val handle: Ref<C.SDL_Renderer>) {
        constructor(window: Window) : this(window.createRenderer())
    }

    private inline fun Window.createRenderer(): Ref<C.SDL_Renderer> {
        val vHandle: Ref<C.SDL_Renderer> = C.SDL_CreateRenderer(this.handle, C.NULL)
        if (!vHandle) error("SDL_CreateRenderer failed: ${C.SDL_GetError()}")
        return vHandle
    }

    // ===================
    // FRect

    class FRect(val sdl: C.SDL_FRect) {

        val x get() = sdl.x
        val y get() = sdl.y
        val w get() = sdl.w
        val h get() = sdl.h

        constructor(x: Float, y: Float, w: Float, h: Float) : this(C.SDL_FRect(x, y, w, h))

        override fun toString(): String {
            return "FRect(x=${sdl.x}, y=${sdl.y}, w=${sdl.w}, h=${sdl.h})"
        }

        override fun equals(other: Ref<Any?>): Boolean {
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
    // Rect (integer)

    class Rect(val sdl: C.SDL_Rect) {

        val x get() = sdl.x
        val y get() = sdl.y
        val w get() = sdl.w
        val h get() = sdl.h

        constructor(x: Int, y: Int, w: Int, h: Int) : this(C.SDL_Rect(x, y, w, h))

        override fun toString(): String = "Rect(x=${sdl.x}, y=${sdl.y}, w=${sdl.w}, h=${sdl.h})"

        fun toFRect(): FRect = FRect(sdl.x.toFloat(), sdl.y.toFloat(), sdl.w.toFloat(), sdl.h.toFloat())

        override fun equals(other: Ref<Any?>): Boolean {
            if (this === other) return true
            if (other !is Rect) return false
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
    }

    // ===================
    // FPoint

    class FPoint(val sdl: C.SDL_FPoint) {

        val x get() = sdl.x
        val y get() = sdl.y

        constructor(x: Float, y: Float) : this(C.SDL_FPoint(x, y))

        override fun toString(): String = "FPoint(x=${sdl.x}, y=${sdl.y})"

        override fun equals(other: Ref<Any?>): Boolean {
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

    class Color(val sdl: C.SDL_Color) {

        val r get() = sdl.r
        val g get() = sdl.g
        val b get() = sdl.b
        val a get() = sdl.a

        constructor(r: UByte, g: UByte, b: UByte, a: UByte) : this(C.SDL_Color(r, g, b, a))

        constructor(color: UInt) : this(
            C.SDL_Color(
                ((color shr 24) and 0xFFu).toUByte(),
                ((color shr 16) and 0xFFu).toUByte(),
                ((color shr 8) and 0xFFu).toUByte(),
                (color and 0xFFu).toUByte()
            )
        )

        override fun toString(): String {
            return "Color(r=${sdl.r}, g=${sdl.g}, b=${sdl.b}, a=${sdl.a})"
        }

        override fun equals(other: Ref<Any?>): Boolean {
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

    class FColor(val sdl: C.SDL_FColor) {

        val r get() = sdl.r
        val g get() = sdl.g
        val b get() = sdl.b
        val a get() = sdl.a

        constructor(r: Float, g: Float, b: Float, a: Float) : this(C.SDL_FColor(r, g, b, a))

        constructor(color: UInt) : this(
            C.SDL_FColor(
                ((color shr 24) and 0xFFu).toUByte() / 255.0f,
                ((color shr 16) and 0xFFu).toUByte() / 255.0f,
                ((color shr 8) and 0xFFu).toUByte() / 255.0f,
                (color and 0xFFu).toUByte() / 255.0f
            )
        )

        override fun toString(): String {
            return "FColor(r=${sdl.r}, g=${sdl.g}, b=${sdl.b}, a=${sdl.a})"
        }

        override fun equals(other: Ref<Any?>): Boolean {
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

    // ===================
    // Texture

    class Texture(val handle: Ref<C.SDL_Texture>) {
        fun size(): FPoint {
            var w = 0.0f
            var h = 0.0f
            C.SDL_GetTextureSize(this.handle, C.addr(w), C.addr(h))
            return FPoint(w, h)
        }
    }

    // ==================
    // Cursor

    class Cursor(val handle: Ref<C.SDL_Cursor>)

    // ==================
    // Lib init

    fun initialize(flags: Int = C.SDL_INIT_VIDEO) {
        if (!C.SDL_Init(flags)) error("SDL_Init failed: ${C.SDL_GetError()}")
    }

    fun quit() { C.SDL_Quit() }

    /** Milliseconds elapsed since SDL_Init. */
    fun ticks(): Long = C.SDL_GetTicks()

    /** Sleep for at least ms milliseconds. */
    fun delay(ms: Int) { C.SDL_Delay(ms) }

    /** High-resolution counter value (use with performanceFrequency for timing). */
    fun performanceCounter(): Long = C.SDL_GetPerformanceCounter()

    /** Ticks per second of the high-resolution counter. */
    fun performanceFrequency(): Long = C.SDL_GetPerformanceFrequency()

    /** Show a simple modal message box. */
    fun showMessageBox(flags: Int, title: String, message: String, window: Window) {
        C.SDL_ShowSimpleMessageBox(flags, title.ptr, message.ptr, window.handle)
    }

    @Namespace object Event
    @Namespace object Scancode
    @Namespace object BlendMode
    @Namespace object Mouse
    @Namespace object TextureAccess
    @Namespace object PixelFormat
    @Namespace object Flip
    @Namespace object WindowFlags
    @Namespace object InitFlags
    @Namespace object SystemCursor
    @Namespace object MessageBoxFlags
}
