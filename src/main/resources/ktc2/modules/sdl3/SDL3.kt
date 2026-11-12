@file:OptIn(ExperimentalForeignApi::class)

package ktc2.sdl3

import kotlinx.cinterop.*
import kotlinx.cinterop.internal.cExpr
import kotlinx.cinterop.internal.cSizeOf
import ktc2.EnumValue
import ktc2.SimpleEnum
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

	/** Create one of the cursors the system provides. Destroy with destroy() when done. */
	fun createSystemCursor(id: SystemCursor): Cursor =
		Cursor(SDL_CreateSystemCursor(id.value) ?: error("SDL_CreateSystemCursor failed: ${lastError()}"))

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

	/** The kinds of events: what `kind` of a polled event is. A C `enum` with SDL's own numbers. */
	@SimpleEnum
	enum class Event(@EnumValue val value: UInt) {
		/** Any other one. */
		Unknown(SDL_EVENT_FIRST),
		Quit(SDL_EVENT_QUIT),
		Terminating(SDL_EVENT_TERMINATING),
		LowMemory(SDL_EVENT_LOW_MEMORY),
		WillEnterBackground(SDL_EVENT_WILL_ENTER_BACKGROUND),
		DidEnterBackground(SDL_EVENT_DID_ENTER_BACKGROUND),
		WillEnterForeground(SDL_EVENT_WILL_ENTER_FOREGROUND),
		DidEnterForeground(SDL_EVENT_DID_ENTER_FOREGROUND),
		LocaleChanged(SDL_EVENT_LOCALE_CHANGED),
		SystemThemeChanged(SDL_EVENT_SYSTEM_THEME_CHANGED),
		DisplayOrientation(SDL_EVENT_DISPLAY_ORIENTATION),
		DisplayAdded(SDL_EVENT_DISPLAY_ADDED),
		DisplayRemoved(SDL_EVENT_DISPLAY_REMOVED),
		DisplayMoved(SDL_EVENT_DISPLAY_MOVED),
		DisplayDesktopModeChanged(SDL_EVENT_DISPLAY_DESKTOP_MODE_CHANGED),
		DisplayCurrentModeChanged(SDL_EVENT_DISPLAY_CURRENT_MODE_CHANGED),
		DisplayContentScaleChanged(SDL_EVENT_DISPLAY_CONTENT_SCALE_CHANGED),
		DisplayUsableBoundsChanged(SDL_EVENT_DISPLAY_USABLE_BOUNDS_CHANGED),
		WindowShown(SDL_EVENT_WINDOW_SHOWN),
		WindowHidden(SDL_EVENT_WINDOW_HIDDEN),
		WindowExposed(SDL_EVENT_WINDOW_EXPOSED),
		WindowMoved(SDL_EVENT_WINDOW_MOVED),
		WindowResized(SDL_EVENT_WINDOW_RESIZED),
		WindowPixelSizeChanged(SDL_EVENT_WINDOW_PIXEL_SIZE_CHANGED),
		WindowMetalViewResized(SDL_EVENT_WINDOW_METAL_VIEW_RESIZED),
		WindowMinimized(SDL_EVENT_WINDOW_MINIMIZED),
		WindowMaximized(SDL_EVENT_WINDOW_MAXIMIZED),
		WindowRestored(SDL_EVENT_WINDOW_RESTORED),
		WindowMouseEnter(SDL_EVENT_WINDOW_MOUSE_ENTER),
		WindowMouseLeave(SDL_EVENT_WINDOW_MOUSE_LEAVE),
		WindowFocusGained(SDL_EVENT_WINDOW_FOCUS_GAINED),
		WindowFocusLost(SDL_EVENT_WINDOW_FOCUS_LOST),
		WindowCloseRequested(SDL_EVENT_WINDOW_CLOSE_REQUESTED),
		WindowHitTest(SDL_EVENT_WINDOW_HIT_TEST),
		WindowIccprofChanged(SDL_EVENT_WINDOW_ICCPROF_CHANGED),
		WindowDisplayChanged(SDL_EVENT_WINDOW_DISPLAY_CHANGED),
		WindowDisplayScaleChanged(SDL_EVENT_WINDOW_DISPLAY_SCALE_CHANGED),
		WindowSafeAreaChanged(SDL_EVENT_WINDOW_SAFE_AREA_CHANGED),
		WindowOccluded(SDL_EVENT_WINDOW_OCCLUDED),
		WindowEnterFullscreen(SDL_EVENT_WINDOW_ENTER_FULLSCREEN),
		WindowLeaveFullscreen(SDL_EVENT_WINDOW_LEAVE_FULLSCREEN),
		WindowDestroyed(SDL_EVENT_WINDOW_DESTROYED),
		WindowHdrStateChanged(SDL_EVENT_WINDOW_HDR_STATE_CHANGED),
		KeyDown(SDL_EVENT_KEY_DOWN),
		KeyUp(SDL_EVENT_KEY_UP),
		TextEditing(SDL_EVENT_TEXT_EDITING),
		TextInput(SDL_EVENT_TEXT_INPUT),
		KeymapChanged(SDL_EVENT_KEYMAP_CHANGED),
		KeyboardAdded(SDL_EVENT_KEYBOARD_ADDED),
		KeyboardRemoved(SDL_EVENT_KEYBOARD_REMOVED),
		TextEditingCandidates(SDL_EVENT_TEXT_EDITING_CANDIDATES),
		ScreenKeyboardShown(SDL_EVENT_SCREEN_KEYBOARD_SHOWN),
		ScreenKeyboardHidden(SDL_EVENT_SCREEN_KEYBOARD_HIDDEN),
		MouseMotion(SDL_EVENT_MOUSE_MOTION),
		MouseButtonDown(SDL_EVENT_MOUSE_BUTTON_DOWN),
		MouseButtonUp(SDL_EVENT_MOUSE_BUTTON_UP),
		MouseWheel(SDL_EVENT_MOUSE_WHEEL),
		MouseAdded(SDL_EVENT_MOUSE_ADDED),
		MouseRemoved(SDL_EVENT_MOUSE_REMOVED),
		JoystickAxisMotion(SDL_EVENT_JOYSTICK_AXIS_MOTION),
		JoystickBallMotion(SDL_EVENT_JOYSTICK_BALL_MOTION),
		JoystickHatMotion(SDL_EVENT_JOYSTICK_HAT_MOTION),
		JoystickButtonDown(SDL_EVENT_JOYSTICK_BUTTON_DOWN),
		JoystickButtonUp(SDL_EVENT_JOYSTICK_BUTTON_UP),
		JoystickAdded(SDL_EVENT_JOYSTICK_ADDED),
		JoystickRemoved(SDL_EVENT_JOYSTICK_REMOVED),
		JoystickBatteryUpdated(SDL_EVENT_JOYSTICK_BATTERY_UPDATED),
		JoystickUpdateComplete(SDL_EVENT_JOYSTICK_UPDATE_COMPLETE),
		GamepadAxisMotion(SDL_EVENT_GAMEPAD_AXIS_MOTION),
		GamepadButtonDown(SDL_EVENT_GAMEPAD_BUTTON_DOWN),
		GamepadButtonUp(SDL_EVENT_GAMEPAD_BUTTON_UP),
		GamepadAdded(SDL_EVENT_GAMEPAD_ADDED),
		GamepadRemoved(SDL_EVENT_GAMEPAD_REMOVED),
		GamepadRemapped(SDL_EVENT_GAMEPAD_REMAPPED),
		GamepadTouchpadDown(SDL_EVENT_GAMEPAD_TOUCHPAD_DOWN),
		GamepadTouchpadMotion(SDL_EVENT_GAMEPAD_TOUCHPAD_MOTION),
		GamepadTouchpadUp(SDL_EVENT_GAMEPAD_TOUCHPAD_UP),
		GamepadSensorUpdate(SDL_EVENT_GAMEPAD_SENSOR_UPDATE),
		GamepadUpdateComplete(SDL_EVENT_GAMEPAD_UPDATE_COMPLETE),
		GamepadSteamHandleUpdated(SDL_EVENT_GAMEPAD_STEAM_HANDLE_UPDATED),
		FingerDown(SDL_EVENT_FINGER_DOWN),
		FingerUp(SDL_EVENT_FINGER_UP),
		FingerMotion(SDL_EVENT_FINGER_MOTION),
		FingerCanceled(SDL_EVENT_FINGER_CANCELED),
		PinchBegin(SDL_EVENT_PINCH_BEGIN),
		PinchUpdate(SDL_EVENT_PINCH_UPDATE),
		PinchEnd(SDL_EVENT_PINCH_END),
		ClipboardUpdate(SDL_EVENT_CLIPBOARD_UPDATE),
		DropFile(SDL_EVENT_DROP_FILE),
		DropText(SDL_EVENT_DROP_TEXT),
		DropBegin(SDL_EVENT_DROP_BEGIN),
		DropComplete(SDL_EVENT_DROP_COMPLETE),
		DropPosition(SDL_EVENT_DROP_POSITION),
		AudioDeviceAdded(SDL_EVENT_AUDIO_DEVICE_ADDED),
		AudioDeviceRemoved(SDL_EVENT_AUDIO_DEVICE_REMOVED),
		AudioDeviceFormatChanged(SDL_EVENT_AUDIO_DEVICE_FORMAT_CHANGED),
		SensorUpdate(SDL_EVENT_SENSOR_UPDATE),
		PenProximityIn(SDL_EVENT_PEN_PROXIMITY_IN),
		PenProximityOut(SDL_EVENT_PEN_PROXIMITY_OUT),
		PenDown(SDL_EVENT_PEN_DOWN),
		PenUp(SDL_EVENT_PEN_UP),
		PenButtonDown(SDL_EVENT_PEN_BUTTON_DOWN),
		PenButtonUp(SDL_EVENT_PEN_BUTTON_UP),
		PenMotion(SDL_EVENT_PEN_MOTION),
		PenAxis(SDL_EVENT_PEN_AXIS),
		CameraDeviceAdded(SDL_EVENT_CAMERA_DEVICE_ADDED),
		CameraDeviceRemoved(SDL_EVENT_CAMERA_DEVICE_REMOVED),
		CameraDeviceApproved(SDL_EVENT_CAMERA_DEVICE_APPROVED),
		CameraDeviceDenied(SDL_EVENT_CAMERA_DEVICE_DENIED),
		RenderTargetsReset(SDL_EVENT_RENDER_TARGETS_RESET),
		RenderDeviceReset(SDL_EVENT_RENDER_DEVICE_RESET),
		RenderDeviceLost(SDL_EVENT_RENDER_DEVICE_LOST),
		User(SDL_EVENT_USER),
	}

	/** Physical keys, for isKeyDown and for `key.code` of a key event. */
	@SimpleEnum
	enum class Scancode(@EnumValue val value: UInt) {
		/** Any other one. */
		Unknown(SDL_SCANCODE_UNKNOWN),
		Escape(SDL_SCANCODE_ESCAPE),
		Return(SDL_SCANCODE_RETURN),
		Space(SDL_SCANCODE_SPACE),
		Backspace(SDL_SCANCODE_BACKSPACE),
		Tab(SDL_SCANCODE_TAB),
		Delete(SDL_SCANCODE_DELETE),
		Insert(SDL_SCANCODE_INSERT),
		Home(SDL_SCANCODE_HOME),
		End(SDL_SCANCODE_END),
		PageUp(SDL_SCANCODE_PAGEUP),
		PageDown(SDL_SCANCODE_PAGEDOWN),
		Left(SDL_SCANCODE_LEFT),
		Right(SDL_SCANCODE_RIGHT),
		Up(SDL_SCANCODE_UP),
		Down(SDL_SCANCODE_DOWN),
		LShift(SDL_SCANCODE_LSHIFT),
		RShift(SDL_SCANCODE_RSHIFT),
		LCtrl(SDL_SCANCODE_LCTRL),
		RCtrl(SDL_SCANCODE_RCTRL),
		LAlt(SDL_SCANCODE_LALT),
		RAlt(SDL_SCANCODE_RALT),
		LGui(SDL_SCANCODE_LGUI),
		RGui(SDL_SCANCODE_RGUI),
		CapsLock(SDL_SCANCODE_CAPSLOCK),
		NumLock(SDL_SCANCODE_NUMLOCKCLEAR),
		ScrollLock(SDL_SCANCODE_SCROLLLOCK),
		A(SDL_SCANCODE_A),
		B(SDL_SCANCODE_B),
		C(SDL_SCANCODE_C),
		D(SDL_SCANCODE_D),
		E(SDL_SCANCODE_E),
		F(SDL_SCANCODE_F),
		G(SDL_SCANCODE_G),
		H(SDL_SCANCODE_H),
		I(SDL_SCANCODE_I),
		J(SDL_SCANCODE_J),
		K(SDL_SCANCODE_K),
		L(SDL_SCANCODE_L),
		M(SDL_SCANCODE_M),
		N(SDL_SCANCODE_N),
		O(SDL_SCANCODE_O),
		P(SDL_SCANCODE_P),
		Q(SDL_SCANCODE_Q),
		R(SDL_SCANCODE_R),
		S(SDL_SCANCODE_S),
		T(SDL_SCANCODE_T),
		U(SDL_SCANCODE_U),
		V(SDL_SCANCODE_V),
		W(SDL_SCANCODE_W),
		X(SDL_SCANCODE_X),
		Y(SDL_SCANCODE_Y),
		Z(SDL_SCANCODE_Z),
		Num0(SDL_SCANCODE_0),
		Num1(SDL_SCANCODE_1),
		Num2(SDL_SCANCODE_2),
		Num3(SDL_SCANCODE_3),
		Num4(SDL_SCANCODE_4),
		Num5(SDL_SCANCODE_5),
		Num6(SDL_SCANCODE_6),
		Num7(SDL_SCANCODE_7),
		Num8(SDL_SCANCODE_8),
		Num9(SDL_SCANCODE_9),
		F1(SDL_SCANCODE_F1),
		F2(SDL_SCANCODE_F2),
		F3(SDL_SCANCODE_F3),
		F4(SDL_SCANCODE_F4),
		F5(SDL_SCANCODE_F5),
		F6(SDL_SCANCODE_F6),
		F7(SDL_SCANCODE_F7),
		F8(SDL_SCANCODE_F8),
		F9(SDL_SCANCODE_F9),
		F10(SDL_SCANCODE_F10),
		F11(SDL_SCANCODE_F11),
		F12(SDL_SCANCODE_F12),
		Kp0(SDL_SCANCODE_KP_0),
		Kp1(SDL_SCANCODE_KP_1),
		Kp2(SDL_SCANCODE_KP_2),
		Kp3(SDL_SCANCODE_KP_3),
		Kp4(SDL_SCANCODE_KP_4),
		Kp5(SDL_SCANCODE_KP_5),
		Kp6(SDL_SCANCODE_KP_6),
		Kp7(SDL_SCANCODE_KP_7),
		Kp8(SDL_SCANCODE_KP_8),
		Kp9(SDL_SCANCODE_KP_9),
		KpEnter(SDL_SCANCODE_KP_ENTER),
		KpPlus(SDL_SCANCODE_KP_PLUS),
		KpMinus(SDL_SCANCODE_KP_MINUS),
		KpMultiply(SDL_SCANCODE_KP_MULTIPLY),
		KpDivide(SDL_SCANCODE_KP_DIVIDE),
		KpPeriod(SDL_SCANCODE_KP_PERIOD),
		Minus(SDL_SCANCODE_MINUS),
		Equals(SDL_SCANCODE_EQUALS),
		LeftBracket(SDL_SCANCODE_LEFTBRACKET),
		RightBracket(SDL_SCANCODE_RIGHTBRACKET),
		Backslash(SDL_SCANCODE_BACKSLASH),
		Semicolon(SDL_SCANCODE_SEMICOLON),
		Apostrophe(SDL_SCANCODE_APOSTROPHE),
		Grave(SDL_SCANCODE_GRAVE),
		Comma(SDL_SCANCODE_COMMA),
		Period(SDL_SCANCODE_PERIOD),
		Slash(SDL_SCANCODE_SLASH),
	}

	/** The buttons of a mouse: what `button.pressed` of a mouse button event is. */
	@SimpleEnum
	enum class MouseButton(@EnumValue val number: Int) {
		Left(SDL_BUTTON_LEFT),
		Middle(SDL_BUTTON_MIDDLE),
		Right(SDL_BUTTON_RIGHT),
		X1(SDL_BUTTON_X1),
		X2(SDL_BUTTON_X2),
	}

	/** The mouse: its position, its buttons (`SDL3.Mouse.isButtonDown`), and the directions of its wheel. */
	object Mouse {
		const val WheelNormal = SDL_MOUSEWHEEL_NORMAL
		const val WheelFlipped = SDL_MOUSEWHEEL_FLIPPED
	}

	/** How drawing combines with what is already there. */
	@SimpleEnum
	enum class BlendMode(@EnumValue val value: UInt) {
		None(SDL_BLENDMODE_NONE),
		Blend(SDL_BLENDMODE_BLEND),
		Add(SDL_BLENDMODE_ADD),
		Mod(SDL_BLENDMODE_MOD),
		Mul(SDL_BLENDMODE_MUL),
	}

	/** How a texture is going to be used. */
	@SimpleEnum
	enum class TextureAccess(@EnumValue val value: UInt) {
		Static(SDL_TEXTUREACCESS_STATIC),
		Streaming(SDL_TEXTUREACCESS_STREAMING),
		Target(SDL_TEXTUREACCESS_TARGET),
	}

	/** Pixel layouts of textures. */
	@SimpleEnum
	enum class PixelFormat(@EnumValue val value: UInt) {
		RGBA8888(SDL_PIXELFORMAT_RGBA8888),
		ARGB8888(SDL_PIXELFORMAT_ARGB8888),
		RGB24(SDL_PIXELFORMAT_RGB24),
		RGBA32(SDL_PIXELFORMAT_RGBA32),
	}

	/** Mirroring for renderTextureRotated. */
	@SimpleEnum
	enum class Flip(@EnumValue val value: UInt) {
		None(SDL_FLIP_NONE),
		Horizontal(SDL_FLIP_HORIZONTAL),
		Vertical(SDL_FLIP_VERTICAL),
	}

	/** Cursors the system provides. */
	@SimpleEnum
	enum class SystemCursor(@EnumValue val value: UInt) {
		Default(SDL_SYSTEM_CURSOR_DEFAULT),
		Text(SDL_SYSTEM_CURSOR_TEXT),
		Wait(SDL_SYSTEM_CURSOR_WAIT),
		Crosshair(SDL_SYSTEM_CURSOR_CROSSHAIR),
		Progress(SDL_SYSTEM_CURSOR_PROGRESS),
		NwseResize(SDL_SYSTEM_CURSOR_NWSE_RESIZE),
		NeswResize(SDL_SYSTEM_CURSOR_NESW_RESIZE),
		EwResize(SDL_SYSTEM_CURSOR_EW_RESIZE),
		NsResize(SDL_SYSTEM_CURSOR_NS_RESIZE),
		Move(SDL_SYSTEM_CURSOR_MOVE),
		NotAllowed(SDL_SYSTEM_CURSOR_NOT_ALLOWED),
		Pointer(SDL_SYSTEM_CURSOR_POINTER),
	}

	/** Kinds of message box. */
	object MessageBoxFlags {
		const val Error = SDL_MESSAGEBOX_ERROR
		const val Warning = SDL_MESSAGEBOX_WARNING
		const val Information = SDL_MESSAGEBOX_INFORMATION
	}

	// </constants>
}
