@file:cInclude("SDL3/SDL.h")

package ktc.sdl3

// ==================
// MARK: TextureAccess
// ==================

inline val SDL3.TextureAccess.Static    get() = C.SDL_TEXTUREACCESS_STATIC
inline val SDL3.TextureAccess.Streaming get() = C.SDL_TEXTUREACCESS_STREAMING
inline val SDL3.TextureAccess.Target    get() = C.SDL_TEXTUREACCESS_TARGET

// ==================
// MARK: PixelFormat
// ==================

inline val SDL3.PixelFormat.RGBA8888 get() = C.SDL_PIXELFORMAT_RGBA8888
inline val SDL3.PixelFormat.ARGB8888 get() = C.SDL_PIXELFORMAT_ARGB8888
inline val SDL3.PixelFormat.RGB24    get() = C.SDL_PIXELFORMAT_RGB24
inline val SDL3.PixelFormat.RGBA32   get() = C.SDL_PIXELFORMAT_RGBA32

// ==================
// MARK: Flip
// ==================

inline val SDL3.Flip.None       get() = C.SDL_FLIP_NONE
inline val SDL3.Flip.Horizontal get() = C.SDL_FLIP_HORIZONTAL
inline val SDL3.Flip.Vertical   get() = C.SDL_FLIP_VERTICAL

// ==================
// MARK: Texture
// ==================

inline fun SDL3.Texture.destroy() { C.SDL_DestroyTexture(this.handle) }

inline fun SDL3.Texture.setBlendMode(mode: Int) {
    C.SDL_SetTextureBlendMode(this.handle, mode)
}

/** Alpha multiplier applied when rendering; 0 = transparent, 255 = opaque. */
inline fun SDL3.Texture.setAlphaMod(alpha: Int) {
    C.SDL_SetTextureAlphaMod(this.handle, alpha)
}

inline fun SDL3.Texture.setColorMod(r: Int, g: Int, b: Int) {
    C.SDL_SetTextureColorMod(this.handle, r, g, b)
}

// ==================
// MARK: Renderer texture ops
// ==================

/** Render texture stretched to fill the entire render target. */
inline fun SDL3.Renderer.renderTexture(texture: SDL3.Texture) {
    C.SDL_RenderTexture(this.handle, texture.handle, C.NULL, C.NULL)
}

/** Render texture at a destination rect, sampling the full texture. */
inline fun SDL3.Renderer.renderTexture(texture: SDL3.Texture, dst: SDL3.FRect) {
    C.SDL_RenderTexture(this.handle, texture.handle, C.NULL, C.addr(dst.sdl))
}

/** Render a cropped source region of texture at a destination rect. */
inline fun SDL3.Renderer.renderTexture(texture: SDL3.Texture, src: SDL3.FRect, dst: SDL3.FRect) {
    C.SDL_RenderTexture(this.handle, texture.handle, C.addr(src.sdl), C.addr(dst.sdl))
}

/** Render texture with clockwise rotation in degrees and flip flags. */
inline fun SDL3.Renderer.renderTextureRotated(texture: SDL3.Texture, dst: SDL3.FRect, angle: Float, flip: Int) {
    C.SDL_RenderTextureRotated(this.handle, texture.handle, C.NULL, C.addr(dst.sdl), angle.toDouble(), C.NULL, flip)
}

/** Render texture with rotation around a custom pivot point. */
inline fun SDL3.Renderer.renderTextureRotated(texture: SDL3.Texture, dst: SDL3.FRect, angle: Float, center: SDL3.FPoint, flip: Int) {
    C.SDL_RenderTextureRotated(this.handle, texture.handle, C.NULL, C.addr(dst.sdl), angle.toDouble(), C.addr(center.sdl), flip)
}

/** Redirect all rendering to a texture (must have TextureAccess.Target). */
inline fun SDL3.Renderer.setTarget(texture: SDL3.Texture) {
    C.SDL_SetRenderTarget(this.handle, texture.handle)
}

/** Reset render target to the window. */
inline fun SDL3.Renderer.clearTarget() {
    C.SDL_SetRenderTarget(this.handle, C.NULL)
}

/** Create a blank texture. */
fun SDL3.Renderer.createTexture(width: Int, height: Int, format: Int, access: Int): SDL3.Texture {
    val vHandle: Ref<C.SDL_Texture> = C.SDL_CreateTexture(this.handle, format, access, width, height)
    if (!vHandle) error("SDL_CreateTexture failed: ${C.SDL_GetError()}")
    return SDL3.Texture(vHandle)
}

/** Load a BMP file and upload it as a static texture. */
fun SDL3.Renderer.loadTextureBmp(path: String): SDL3.Texture {
    val vSurface: Ref<C.SDL_Surface> = C.SDL_LoadBMP(path.ptr)
    if (!vSurface) error("SDL_LoadBMP failed: ${C.SDL_GetError()}")
    val vHandle: Ref<C.SDL_Texture> = C.SDL_CreateTextureFromSurface(this.handle, vSurface)
    C.SDL_DestroySurface(vSurface)
    if (!vHandle) error("SDL_CreateTextureFromSurface failed: ${C.SDL_GetError()}")
    return SDL3.Texture(vHandle)
}
