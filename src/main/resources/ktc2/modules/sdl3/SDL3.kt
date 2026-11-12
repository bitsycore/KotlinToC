@file:OptIn(ExperimentalForeignApi::class)

package ktc2.sdl3

import kotlinx.cinterop.*
import kotlinx.cinterop.internal.cExpr
import kotlinx.cinterop.internal.cSizeOf
import ktc2.Struct
import sdl3.*

// ==================
// MARK: C memory for one call
// ==================

/**
 * A zeroed C variable in the frame of the function this is called in, gone when that function
 * returns. Used to hand SDL a struct or to receive a value from it without touching the heap.
 * Only for functions that return soon: called in a loop that never ends it would grow the stack.
 */
@PublishedApi
internal inline fun <reified T : CVariable> onStack(): T =
	cExpr("ktc2_stack_zeroed((size_t)$0)", cSizeOf<T>())

/** [inCount] zeroed C variables in the frame of the calling function. */
@PublishedApi
internal inline fun <reified T : CVariable> onStackArray(inCount: Int): CArrayPointer<T> =
	cExpr("ktc2_stack_zeroed((size_t)($0 * $1))", cSizeOf<T>(), inCount.toLong())

internal fun squareRoot(inValue: Float): Float = kotlin.math.sqrt(inValue)

/** What SDL reports for the last failure. */
internal fun lastError(): String = SDL_GetError()?.toKString() ?: "unknown error"

// ==================
// MARK: SDL3
// ==================

/** The objects of SDL and the values this module passes around. */
object SDL3 {

	// ============
	//  Handles

	class Window(val handle: CPointer<SDL_Window>) {
		constructor(title: String, width: Int, height: Int, flags: ULong = 0uL) : this(createWindow(title, width, height, flags))
	}

	class Renderer(val handle: CPointer<SDL_Renderer>) {
		constructor(window: Window) : this(createRenderer(window))
	}

	class Texture(val handle: CPointer<SDL_Texture>) {
		fun size(): FPoint {
			val vWidth = onStack<FloatVar>()
			val vHeight = onStack<FloatVar>()
			SDL_GetTextureSize(handle, vWidth.ptr, vHeight.ptr)
			return FPoint(vWidth.value, vHeight.value)
		}
	}

	class Cursor(val handle: CPointer<SDL_Cursor>)

	private fun createWindow(title: String, width: Int, height: Int, flags: ULong): CPointer<SDL_Window> =
		SDL_CreateWindow(title, width, height, flags) ?: error("SDL_CreateWindow failed: ${lastError()}")

	private fun createRenderer(window: Window): CPointer<SDL_Renderer> =
		SDL_CreateRenderer(window.handle, null) ?: error("SDL_CreateRenderer failed: ${lastError()}")

	// ============
	//  Values

	/*
	Plain values, passed around like numbers: none of them is ever allocated.
	*/

	@Struct
	data class FRect(val x: Float, val y: Float, val w: Float, val h: Float)

	@Struct
	data class Rect(val x: Int, val y: Int, val w: Int, val h: Int) {
		fun toFRect(): FRect = FRect(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat())
	}

	@Struct
	data class FPoint(val x: Float, val y: Float)

	@Struct
	data class Color(val r: UByte, val g: UByte, val b: UByte, val a: UByte) {
		/** From 0xRRGGBBAA. */
		constructor(color: UInt) : this(
			((color shr 24) and 0xFFu).toUByte(),
			((color shr 16) and 0xFFu).toUByte(),
			((color shr 8) and 0xFFu).toUByte(),
			(color and 0xFFu).toUByte(),
		)
	}

	@Struct
	data class FColor(val r: Float, val g: Float, val b: Float, val a: Float) {
		/** From 0xRRGGBBAA. */
		constructor(color: UInt) : this(
			((color shr 24) and 0xFFu).toInt() / 255.0f,
			((color shr 16) and 0xFFu).toInt() / 255.0f,
			((color shr 8) and 0xFFu).toInt() / 255.0f,
			(color and 0xFFu).toInt() / 255.0f,
		)
	}

	// ============
	//  Library

	fun initialize(flags: UInt = SDL_INIT_VIDEO) {
		if (!SDL_Init(flags)) error("SDL_Init failed: ${lastError()}")
	}

	fun quit() {
		SDL_Quit()
	}

	/** Milliseconds elapsed since SDL_Init. */
	fun ticks(): Long = SDL_GetTicks().toLong()

	/** Sleep for at least ms milliseconds. */
	fun delay(ms: Int) {
		SDL_Delay(ms.toUInt())
	}

	/** High-resolution counter value (use with performanceFrequency for timing). */
	fun performanceCounter(): Long = SDL_GetPerformanceCounter().toLong()

	/** Ticks per second of the high-resolution counter. */
	fun performanceFrequency(): Long = SDL_GetPerformanceFrequency().toLong()

	/** Show a simple modal message box. */
	fun showMessageBox(flags: UInt, title: String, message: String, window: Window) {
		SDL_ShowSimpleMessageBox(flags, title, message, window.handle)
	}

	/** Create a system cursor from a SystemCursor id. Destroy with destroy() when done. */
	fun createSystemCursor(id: UInt): Cursor =
		Cursor(SDL_CreateSystemCursor(id) ?: error("SDL_CreateSystemCursor failed: ${lastError()}"))

	/** Copy text to the system clipboard. */
	fun setClipboardText(text: String) {
		SDL_SetClipboardText(text)
	}

	/** True if the clipboard currently contains text. */
	fun hasClipboardText(): Boolean = SDL_HasClipboardText()

	// ============
	//  Constants

	// <constants>

	/** Subsystems for SDL3.initialize. */
	object InitFlags {
		const val Audio = SDL_INIT_AUDIO
		const val Video = SDL_INIT_VIDEO
		const val Joystick = SDL_INIT_JOYSTICK
		const val Haptic = SDL_INIT_HAPTIC
		const val Gamepad = SDL_INIT_GAMEPAD
		const val Events = SDL_INIT_EVENTS
		const val Sensor = SDL_INIT_SENSOR
		const val Camera = SDL_INIT_CAMERA
	}

	/** Flags for SDL3.Window. */
	object WindowFlags {
		const val Fullscreen = SDL_WINDOW_FULLSCREEN
		const val Resizable = SDL_WINDOW_RESIZABLE
		const val Borderless = SDL_WINDOW_BORDERLESS
		const val Hidden = SDL_WINDOW_HIDDEN
		const val Maximized = SDL_WINDOW_MAXIMIZED
		const val Minimized = SDL_WINDOW_MINIMIZED
		const val HighDPI = SDL_WINDOW_HIGH_PIXEL_DENSITY
		const val AlwaysOnTop = SDL_WINDOW_ALWAYS_ON_TOP
	}

	/** Event types: compare with the type of a polled event. */
	object Event {
		const val Quit = SDL_EVENT_QUIT
		const val Terminating = SDL_EVENT_TERMINATING
		const val LowMemory = SDL_EVENT_LOW_MEMORY
		const val WillEnterBackground = SDL_EVENT_WILL_ENTER_BACKGROUND
		const val DidEnterBackground = SDL_EVENT_DID_ENTER_BACKGROUND
		const val WillEnterForeground = SDL_EVENT_WILL_ENTER_FOREGROUND
		const val DidEnterForeground = SDL_EVENT_DID_ENTER_FOREGROUND
		const val LocaleChanged = SDL_EVENT_LOCALE_CHANGED
		const val SystemThemeChanged = SDL_EVENT_SYSTEM_THEME_CHANGED
		const val DisplayOrientation = SDL_EVENT_DISPLAY_ORIENTATION
		const val DisplayAdded = SDL_EVENT_DISPLAY_ADDED
		const val DisplayRemoved = SDL_EVENT_DISPLAY_REMOVED
		const val DisplayMoved = SDL_EVENT_DISPLAY_MOVED
		const val DisplayDesktopModeChanged = SDL_EVENT_DISPLAY_DESKTOP_MODE_CHANGED
		const val DisplayCurrentModeChanged = SDL_EVENT_DISPLAY_CURRENT_MODE_CHANGED
		const val DisplayContentScaleChanged = SDL_EVENT_DISPLAY_CONTENT_SCALE_CHANGED
		const val DisplayUsableBoundsChanged = SDL_EVENT_DISPLAY_USABLE_BOUNDS_CHANGED
		const val WindowShown = SDL_EVENT_WINDOW_SHOWN
		const val WindowHidden = SDL_EVENT_WINDOW_HIDDEN
		const val WindowExposed = SDL_EVENT_WINDOW_EXPOSED
		const val WindowMoved = SDL_EVENT_WINDOW_MOVED
		const val WindowResized = SDL_EVENT_WINDOW_RESIZED
		const val WindowPixelSizeChanged = SDL_EVENT_WINDOW_PIXEL_SIZE_CHANGED
		const val WindowMetalViewResized = SDL_EVENT_WINDOW_METAL_VIEW_RESIZED
		const val WindowMinimized = SDL_EVENT_WINDOW_MINIMIZED
		const val WindowMaximized = SDL_EVENT_WINDOW_MAXIMIZED
		const val WindowRestored = SDL_EVENT_WINDOW_RESTORED
		const val WindowMouseEnter = SDL_EVENT_WINDOW_MOUSE_ENTER
		const val WindowMouseLeave = SDL_EVENT_WINDOW_MOUSE_LEAVE
		const val WindowFocusGained = SDL_EVENT_WINDOW_FOCUS_GAINED
		const val WindowFocusLost = SDL_EVENT_WINDOW_FOCUS_LOST
		const val WindowCloseRequested = SDL_EVENT_WINDOW_CLOSE_REQUESTED
		const val WindowHitTest = SDL_EVENT_WINDOW_HIT_TEST
		const val WindowIccprofChanged = SDL_EVENT_WINDOW_ICCPROF_CHANGED
		const val WindowDisplayChanged = SDL_EVENT_WINDOW_DISPLAY_CHANGED
		const val WindowDisplayScaleChanged = SDL_EVENT_WINDOW_DISPLAY_SCALE_CHANGED
		const val WindowSafeAreaChanged = SDL_EVENT_WINDOW_SAFE_AREA_CHANGED
		const val WindowOccluded = SDL_EVENT_WINDOW_OCCLUDED
		const val WindowEnterFullscreen = SDL_EVENT_WINDOW_ENTER_FULLSCREEN
		const val WindowLeaveFullscreen = SDL_EVENT_WINDOW_LEAVE_FULLSCREEN
		const val WindowDestroyed = SDL_EVENT_WINDOW_DESTROYED
		const val WindowHdrStateChanged = SDL_EVENT_WINDOW_HDR_STATE_CHANGED
		const val KeyDown = SDL_EVENT_KEY_DOWN
		const val KeyUp = SDL_EVENT_KEY_UP
		const val TextEditing = SDL_EVENT_TEXT_EDITING
		const val TextInput = SDL_EVENT_TEXT_INPUT
		const val KeymapChanged = SDL_EVENT_KEYMAP_CHANGED
		const val KeyboardAdded = SDL_EVENT_KEYBOARD_ADDED
		const val KeyboardRemoved = SDL_EVENT_KEYBOARD_REMOVED
		const val TextEditingCandidates = SDL_EVENT_TEXT_EDITING_CANDIDATES
		const val ScreenKeyboardShown = SDL_EVENT_SCREEN_KEYBOARD_SHOWN
		const val ScreenKeyboardHidden = SDL_EVENT_SCREEN_KEYBOARD_HIDDEN
		const val MouseMotion = SDL_EVENT_MOUSE_MOTION
		const val MouseButtonDown = SDL_EVENT_MOUSE_BUTTON_DOWN
		const val MouseButtonUp = SDL_EVENT_MOUSE_BUTTON_UP
		const val MouseWheel = SDL_EVENT_MOUSE_WHEEL
		const val MouseAdded = SDL_EVENT_MOUSE_ADDED
		const val MouseRemoved = SDL_EVENT_MOUSE_REMOVED
		const val JoystickAxisMotion = SDL_EVENT_JOYSTICK_AXIS_MOTION
		const val JoystickBallMotion = SDL_EVENT_JOYSTICK_BALL_MOTION
		const val JoystickHatMotion = SDL_EVENT_JOYSTICK_HAT_MOTION
		const val JoystickButtonDown = SDL_EVENT_JOYSTICK_BUTTON_DOWN
		const val JoystickButtonUp = SDL_EVENT_JOYSTICK_BUTTON_UP
		const val JoystickAdded = SDL_EVENT_JOYSTICK_ADDED
		const val JoystickRemoved = SDL_EVENT_JOYSTICK_REMOVED
		const val JoystickBatteryUpdated = SDL_EVENT_JOYSTICK_BATTERY_UPDATED
		const val JoystickUpdateComplete = SDL_EVENT_JOYSTICK_UPDATE_COMPLETE
		const val GamepadAxisMotion = SDL_EVENT_GAMEPAD_AXIS_MOTION
		const val GamepadButtonDown = SDL_EVENT_GAMEPAD_BUTTON_DOWN
		const val GamepadButtonUp = SDL_EVENT_GAMEPAD_BUTTON_UP
		const val GamepadAdded = SDL_EVENT_GAMEPAD_ADDED
		const val GamepadRemoved = SDL_EVENT_GAMEPAD_REMOVED
		const val GamepadRemapped = SDL_EVENT_GAMEPAD_REMAPPED
		const val GamepadTouchpadDown = SDL_EVENT_GAMEPAD_TOUCHPAD_DOWN
		const val GamepadTouchpadMotion = SDL_EVENT_GAMEPAD_TOUCHPAD_MOTION
		const val GamepadTouchpadUp = SDL_EVENT_GAMEPAD_TOUCHPAD_UP
		const val GamepadSensorUpdate = SDL_EVENT_GAMEPAD_SENSOR_UPDATE
		const val GamepadUpdateComplete = SDL_EVENT_GAMEPAD_UPDATE_COMPLETE
		const val GamepadSteamHandleUpdated = SDL_EVENT_GAMEPAD_STEAM_HANDLE_UPDATED
		const val FingerDown = SDL_EVENT_FINGER_DOWN
		const val FingerUp = SDL_EVENT_FINGER_UP
		const val FingerMotion = SDL_EVENT_FINGER_MOTION
		const val FingerCanceled = SDL_EVENT_FINGER_CANCELED
		const val PinchBegin = SDL_EVENT_PINCH_BEGIN
		const val PinchUpdate = SDL_EVENT_PINCH_UPDATE
		const val PinchEnd = SDL_EVENT_PINCH_END
		const val ClipboardUpdate = SDL_EVENT_CLIPBOARD_UPDATE
		const val DropFile = SDL_EVENT_DROP_FILE
		const val DropText = SDL_EVENT_DROP_TEXT
		const val DropBegin = SDL_EVENT_DROP_BEGIN
		const val DropComplete = SDL_EVENT_DROP_COMPLETE
		const val DropPosition = SDL_EVENT_DROP_POSITION
		const val AudioDeviceAdded = SDL_EVENT_AUDIO_DEVICE_ADDED
		const val AudioDeviceRemoved = SDL_EVENT_AUDIO_DEVICE_REMOVED
		const val AudioDeviceFormatChanged = SDL_EVENT_AUDIO_DEVICE_FORMAT_CHANGED
		const val SensorUpdate = SDL_EVENT_SENSOR_UPDATE
		const val PenProximityIn = SDL_EVENT_PEN_PROXIMITY_IN
		const val PenProximityOut = SDL_EVENT_PEN_PROXIMITY_OUT
		const val PenDown = SDL_EVENT_PEN_DOWN
		const val PenUp = SDL_EVENT_PEN_UP
		const val PenButtonDown = SDL_EVENT_PEN_BUTTON_DOWN
		const val PenButtonUp = SDL_EVENT_PEN_BUTTON_UP
		const val PenMotion = SDL_EVENT_PEN_MOTION
		const val PenAxis = SDL_EVENT_PEN_AXIS
		const val CameraDeviceAdded = SDL_EVENT_CAMERA_DEVICE_ADDED
		const val CameraDeviceRemoved = SDL_EVENT_CAMERA_DEVICE_REMOVED
		const val CameraDeviceApproved = SDL_EVENT_CAMERA_DEVICE_APPROVED
		const val CameraDeviceDenied = SDL_EVENT_CAMERA_DEVICE_DENIED
		const val RenderTargetsReset = SDL_EVENT_RENDER_TARGETS_RESET
		const val RenderDeviceReset = SDL_EVENT_RENDER_DEVICE_RESET
		const val RenderDeviceLost = SDL_EVENT_RENDER_DEVICE_LOST
		const val User = SDL_EVENT_USER
	}

	/** Physical keys, for isKeyDown and key events. */
	object Scancode {
		const val Escape = SDL_SCANCODE_ESCAPE
		const val Return = SDL_SCANCODE_RETURN
		const val Space = SDL_SCANCODE_SPACE
		const val Backspace = SDL_SCANCODE_BACKSPACE
		const val Tab = SDL_SCANCODE_TAB
		const val Delete = SDL_SCANCODE_DELETE
		const val Insert = SDL_SCANCODE_INSERT
		const val Home = SDL_SCANCODE_HOME
		const val End = SDL_SCANCODE_END
		const val PageUp = SDL_SCANCODE_PAGEUP
		const val PageDown = SDL_SCANCODE_PAGEDOWN
		const val Left = SDL_SCANCODE_LEFT
		const val Right = SDL_SCANCODE_RIGHT
		const val Up = SDL_SCANCODE_UP
		const val Down = SDL_SCANCODE_DOWN
		const val LShift = SDL_SCANCODE_LSHIFT
		const val RShift = SDL_SCANCODE_RSHIFT
		const val LCtrl = SDL_SCANCODE_LCTRL
		const val RCtrl = SDL_SCANCODE_RCTRL
		const val LAlt = SDL_SCANCODE_LALT
		const val RAlt = SDL_SCANCODE_RALT
		const val LGui = SDL_SCANCODE_LGUI
		const val RGui = SDL_SCANCODE_RGUI
		const val CapsLock = SDL_SCANCODE_CAPSLOCK
		const val NumLock = SDL_SCANCODE_NUMLOCKCLEAR
		const val ScrollLock = SDL_SCANCODE_SCROLLLOCK
		const val A = SDL_SCANCODE_A
		const val B = SDL_SCANCODE_B
		const val C = SDL_SCANCODE_C
		const val D = SDL_SCANCODE_D
		const val E = SDL_SCANCODE_E
		const val F = SDL_SCANCODE_F
		const val G = SDL_SCANCODE_G
		const val H = SDL_SCANCODE_H
		const val I = SDL_SCANCODE_I
		const val J = SDL_SCANCODE_J
		const val K = SDL_SCANCODE_K
		const val L = SDL_SCANCODE_L
		const val M = SDL_SCANCODE_M
		const val N = SDL_SCANCODE_N
		const val O = SDL_SCANCODE_O
		const val P = SDL_SCANCODE_P
		const val Q = SDL_SCANCODE_Q
		const val R = SDL_SCANCODE_R
		const val S = SDL_SCANCODE_S
		const val T = SDL_SCANCODE_T
		const val U = SDL_SCANCODE_U
		const val V = SDL_SCANCODE_V
		const val W = SDL_SCANCODE_W
		const val X = SDL_SCANCODE_X
		const val Y = SDL_SCANCODE_Y
		const val Z = SDL_SCANCODE_Z
		const val Num0 = SDL_SCANCODE_0
		const val Num1 = SDL_SCANCODE_1
		const val Num2 = SDL_SCANCODE_2
		const val Num3 = SDL_SCANCODE_3
		const val Num4 = SDL_SCANCODE_4
		const val Num5 = SDL_SCANCODE_5
		const val Num6 = SDL_SCANCODE_6
		const val Num7 = SDL_SCANCODE_7
		const val Num8 = SDL_SCANCODE_8
		const val Num9 = SDL_SCANCODE_9
		const val F1 = SDL_SCANCODE_F1
		const val F2 = SDL_SCANCODE_F2
		const val F3 = SDL_SCANCODE_F3
		const val F4 = SDL_SCANCODE_F4
		const val F5 = SDL_SCANCODE_F5
		const val F6 = SDL_SCANCODE_F6
		const val F7 = SDL_SCANCODE_F7
		const val F8 = SDL_SCANCODE_F8
		const val F9 = SDL_SCANCODE_F9
		const val F10 = SDL_SCANCODE_F10
		const val F11 = SDL_SCANCODE_F11
		const val F12 = SDL_SCANCODE_F12
		const val Kp0 = SDL_SCANCODE_KP_0
		const val Kp1 = SDL_SCANCODE_KP_1
		const val Kp2 = SDL_SCANCODE_KP_2
		const val Kp3 = SDL_SCANCODE_KP_3
		const val Kp4 = SDL_SCANCODE_KP_4
		const val Kp5 = SDL_SCANCODE_KP_5
		const val Kp6 = SDL_SCANCODE_KP_6
		const val Kp7 = SDL_SCANCODE_KP_7
		const val Kp8 = SDL_SCANCODE_KP_8
		const val Kp9 = SDL_SCANCODE_KP_9
		const val KpEnter = SDL_SCANCODE_KP_ENTER
		const val KpPlus = SDL_SCANCODE_KP_PLUS
		const val KpMinus = SDL_SCANCODE_KP_MINUS
		const val KpMultiply = SDL_SCANCODE_KP_MULTIPLY
		const val KpDivide = SDL_SCANCODE_KP_DIVIDE
		const val KpPeriod = SDL_SCANCODE_KP_PERIOD
		const val Minus = SDL_SCANCODE_MINUS
		const val Equals = SDL_SCANCODE_EQUALS
		const val LeftBracket = SDL_SCANCODE_LEFTBRACKET
		const val RightBracket = SDL_SCANCODE_RIGHTBRACKET
		const val Backslash = SDL_SCANCODE_BACKSLASH
		const val Semicolon = SDL_SCANCODE_SEMICOLON
		const val Apostrophe = SDL_SCANCODE_APOSTROPHE
		const val Grave = SDL_SCANCODE_GRAVE
		const val Comma = SDL_SCANCODE_COMMA
		const val Period = SDL_SCANCODE_PERIOD
		const val Slash = SDL_SCANCODE_SLASH
	}

	/** Mouse buttons and wheel directions. */
	object Mouse {
		const val Left = SDL_BUTTON_LEFT
		const val Middle = SDL_BUTTON_MIDDLE
		const val Right = SDL_BUTTON_RIGHT
		const val X1 = SDL_BUTTON_X1
		const val X2 = SDL_BUTTON_X2
		const val WheelNormal = SDL_MOUSEWHEEL_NORMAL
		const val WheelFlipped = SDL_MOUSEWHEEL_FLIPPED
	}

	/** How drawing combines with what is already there. */
	object BlendMode {
		const val None = SDL_BLENDMODE_NONE
		const val Blend = SDL_BLENDMODE_BLEND
		const val Add = SDL_BLENDMODE_ADD
		const val Mod = SDL_BLENDMODE_MOD
		const val Mul = SDL_BLENDMODE_MUL
	}

	/** How a texture is going to be used. */
	object TextureAccess {
		const val Static = SDL_TEXTUREACCESS_STATIC
		const val Streaming = SDL_TEXTUREACCESS_STREAMING
		const val Target = SDL_TEXTUREACCESS_TARGET
	}

	/** Pixel layouts of textures. */
	object PixelFormat {
		const val RGBA8888 = SDL_PIXELFORMAT_RGBA8888
		const val ARGB8888 = SDL_PIXELFORMAT_ARGB8888
		const val RGB24 = SDL_PIXELFORMAT_RGB24
		const val RGBA32 = SDL_PIXELFORMAT_RGBA32
	}

	/** Mirroring for renderTextureRotated. */
	object Flip {
		const val None = SDL_FLIP_NONE
		const val Horizontal = SDL_FLIP_HORIZONTAL
		const val Vertical = SDL_FLIP_VERTICAL
	}

	/** Cursors the system provides. */
	object SystemCursor {
		const val Default = SDL_SYSTEM_CURSOR_DEFAULT
		const val Text = SDL_SYSTEM_CURSOR_TEXT
		const val Wait = SDL_SYSTEM_CURSOR_WAIT
		const val Crosshair = SDL_SYSTEM_CURSOR_CROSSHAIR
		const val Progress = SDL_SYSTEM_CURSOR_PROGRESS
		const val NwseResize = SDL_SYSTEM_CURSOR_NWSE_RESIZE
		const val NeswResize = SDL_SYSTEM_CURSOR_NESW_RESIZE
		const val EwResize = SDL_SYSTEM_CURSOR_EW_RESIZE
		const val NsResize = SDL_SYSTEM_CURSOR_NS_RESIZE
		const val Move = SDL_SYSTEM_CURSOR_MOVE
		const val NotAllowed = SDL_SYSTEM_CURSOR_NOT_ALLOWED
		const val Pointer = SDL_SYSTEM_CURSOR_POINTER
	}

	/** Kinds of message box. */
	object MessageBoxFlags {
		const val Error = SDL_MESSAGEBOX_ERROR
		const val Warning = SDL_MESSAGEBOX_WARNING
		const val Information = SDL_MESSAGEBOX_INFORMATION
	}

	// </constants>
}
