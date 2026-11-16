@file:OptIn(ExperimentalForeignApi::class)

package showcase.gfx

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import ktc2.sdl3.*
import sdl3.SDL_FColor
import sdl3.SDL_PIXELFORMAT_ABGR8888
import sdl3.SDL_RenderGeometryRaw
import sdl3.SDL_SCALEMODE_NEAREST
import sdl3.SDL_SetTextureScaleMode
import sdl3.SDL_UpdateTexture

// ==================
// MARK: Textures drawn by the program
// ==================

/**
 * A texture of [inWidth] x [inHeight] pixels whose color at each pixel [inPixel] gives, as 0xAABBGGRR
 * (the bytes R, G, B, A in memory). No image file: the showcase draws its own.
 */
fun SDL3.Renderer.paintedTexture(inWidth: Int, inHeight: Int, inSmooth: Boolean = false, inPixel: (Int, Int) -> Int): SDL3.Texture {
	val vPixels = IntArray(inWidth * inHeight)
	for (vY in 0 until inHeight) for (vX in 0 until inWidth) vPixels[vY * inWidth + vX] = inPixel(vX, vY)
	val vTexture = streamingTexture(inWidth, inHeight, inSmooth)
	vTexture.upload(vPixels, inWidth)
	vTexture.setBlendMode(SDL3.BlendMode.Blend)
	return vTexture
}

/** A texture whose pixels the program sends each frame ([upload]), as 0xAABBGGRR. */
fun SDL3.Renderer.streamingTexture(inWidth: Int, inHeight: Int, inSmooth: Boolean = false): SDL3.Texture {
	val vTexture = SDL3.Texture(sdl3.SDL_CreateTexture(handle, SDL_PIXELFORMAT_ABGR8888, SDL3.TextureAccess.Streaming.value, inWidth, inHeight) ?: error("SDL_CreateTexture failed"))
	if (!inSmooth) SDL_SetTextureScaleMode(vTexture.handle, SDL_SCALEMODE_NEAREST)
	return vTexture
}

/** Sends [inPixels], rows of [inWidth] pixels, to the texture. */
fun SDL3.Texture.upload(inPixels: IntArray, inWidth: Int) {
	SDL_UpdateTexture(handle, null, inPixels.refTo(0), inWidth * 4)
}

/** A pixel of the given channels, 0 to 255, as [paintedTexture] takes it. */
fun rgba(inRed: Int, inGreen: Int, inBlue: Int, inAlpha: Int = 255): Int =
	(inAlpha.coerceIn(0, 255) shl 24) or (inBlue.coerceIn(0, 255) shl 16) or (inGreen.coerceIn(0, 255) shl 8) or inRed.coerceIn(0, 255)

/** A bunny of 16 x 16 pixels: ears, a head, a body, a tail, an eye. */
fun SDL3.Renderer.bunnyTexture(): SDL3.Texture {
	val vShape = listOf(
		"...##....##.....",
		"...#%#..#%#.....",
		"...#%#..#%#.....",
		"...#%#..#%#.....",
		"....##..##......",
		"...########.....",
		"..##########....",
		"..###o##o###....",
		"..##########....",
		"...###%%###.....",
		"....######......",
		"...########.....",
		"..##########.##.",
		"..###########%#.",
		"..##########.##.",
		"...##....##.....",
	)
	return paintedTexture(16, 16) { vX, vY ->
		when (vShape[vY][vX]) {
			'#' -> rgba(255, 255, 255)
			'%' -> rgba(255, 190, 200)
			'o' -> rgba(30, 30, 40)
			else -> 0
		}
	}
}

/** A soft round spot: white in the middle, fading out to the edge. For particles, drawn added. */
fun SDL3.Renderer.glowTexture(inSize: Int = 32): SDL3.Texture = paintedTexture(inSize, inSize, inSmooth = true) { vX, vY ->
	val vDx = (vX + 0.5f) / inSize * 2.0f - 1.0f
	val vDy = (vY + 0.5f) / inSize * 2.0f - 1.0f
	val vFall = (1.0f - (vDx * vDx + vDy * vDy)).coerceIn(0.0f, 1.0f)
	rgba(255, 255, 255, (vFall * vFall * 255).toInt())
}

// ==================
// MARK: Batches
// ==================

/**
 * Many copies of one texture, drawn by few calls to SDL: the corners of [kCapacity] rectangles at a
 * time in one array, their texture coordinates and the order of their corners made once, and one
 * color for the whole call (a color array of stride 0). [add] the rectangles, [flush] at the end.
 */
class SpriteBatch(private val mRenderer: SDL3.Renderer, private val mTexture: SDL3.Texture) {
	companion object {
		/** Rectangles in one call. */
		const val kCapacity = 16384
	}

	private val mCorners = FloatArray(kCapacity * 8)
	private val mTexturePlaces = FloatArray(kCapacity * 8)
	private val mOrder = IntArray(kCapacity * 6)
	private val mColor: SDL_FColor = nativeHeap.alloc()
	private var mCount = 0

	/** How many calls to SDL the last frame made, for the bar at the top. */
	var calls = 0
		private set

	init {
		for (vIndex in 0 until kCapacity) {
			val vUv = vIndex * 8
			mTexturePlaces[vUv] = 0.0f; mTexturePlaces[vUv + 1] = 0.0f
			mTexturePlaces[vUv + 2] = 1.0f; mTexturePlaces[vUv + 3] = 0.0f
			mTexturePlaces[vUv + 4] = 1.0f; mTexturePlaces[vUv + 5] = 1.0f
			mTexturePlaces[vUv + 6] = 0.0f; mTexturePlaces[vUv + 7] = 1.0f
			val vFirst = vIndex * 4
			val vAt = vIndex * 6
			mOrder[vAt] = vFirst; mOrder[vAt + 1] = vFirst + 1; mOrder[vAt + 2] = vFirst + 2
			mOrder[vAt + 3] = vFirst; mOrder[vAt + 4] = vFirst + 2; mOrder[vAt + 5] = vFirst + 3
		}
		color(1.0f, 1.0f, 1.0f, 1.0f)
	}

	/** The color the next rectangles are drawn in (multiplied with the texture): flushes those before. */
	fun color(inRed: Float, inGreen: Float, inBlue: Float, inAlpha: Float) {
		flush()
		mColor.r = inRed
		mColor.g = inGreen
		mColor.b = inBlue
		mColor.a = inAlpha
	}

	/** Starts a frame's counts. */
	fun begin() {
		calls = 0
	}

	/** A rectangle at ([inX], [inY]), [inWidth] x [inHeight]. */
	fun add(inX: Float, inY: Float, inWidth: Float, inHeight: Float) {
		if (mCount == kCapacity) flush()
		val vAt = mCount * 8
		val vRight = inX + inWidth
		val vBottom = inY + inHeight
		mCorners[vAt] = inX; mCorners[vAt + 1] = inY
		mCorners[vAt + 2] = vRight; mCorners[vAt + 3] = inY
		mCorners[vAt + 4] = vRight; mCorners[vAt + 5] = vBottom
		mCorners[vAt + 6] = inX; mCorners[vAt + 7] = vBottom
		mCount++
	}

	/** Draws what was added since the last call. */
	fun flush() {
		if (mCount == 0) return
		SDL_RenderGeometryRaw(mRenderer.handle, mTexture.handle, mCorners.refTo(0), 8, mColor.ptr, 0, mTexturePlaces.refTo(0), 8, mCount * 4, mOrder.refTo(0), mCount * 6, 4)
		calls++
		mCount = 0
	}
}
