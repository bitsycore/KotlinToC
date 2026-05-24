@file:cInclude("SDL3/SDL.h")

package sdl3

// Application events
inline val SDL3.Event.Quit                     get() = c.SDL_EVENT_QUIT
inline val SDL3.Event.Terminating              get() = c.SDL_EVENT_TERMINATING
inline val SDL3.Event.LowMemory               get() = c.SDL_EVENT_LOW_MEMORY
inline val SDL3.Event.WillEnterBackground     get() = c.SDL_EVENT_WILL_ENTER_BACKGROUND
inline val SDL3.Event.DidEnterBackground      get() = c.SDL_EVENT_DID_ENTER_BACKGROUND
inline val SDL3.Event.WillEnterForeground     get() = c.SDL_EVENT_WILL_ENTER_FOREGROUND
inline val SDL3.Event.DidEnterForeground      get() = c.SDL_EVENT_DID_ENTER_FOREGROUND
inline val SDL3.Event.LocaleChanged           get() = c.SDL_EVENT_LOCALE_CHANGED
inline val SDL3.Event.SystemThemeChanged      get() = c.SDL_EVENT_SYSTEM_THEME_CHANGED

// Display events
inline val SDL3.Event.DisplayOrientation            get() = c.SDL_EVENT_DISPLAY_ORIENTATION
inline val SDL3.Event.DisplayAdded                  get() = c.SDL_EVENT_DISPLAY_ADDED
inline val SDL3.Event.DisplayRemoved                get() = c.SDL_EVENT_DISPLAY_REMOVED
inline val SDL3.Event.DisplayMoved                  get() = c.SDL_EVENT_DISPLAY_MOVED
inline val SDL3.Event.DisplayDesktopModeChanged     get() = c.SDL_EVENT_DISPLAY_DESKTOP_MODE_CHANGED
inline val SDL3.Event.DisplayCurrentModeChanged     get() = c.SDL_EVENT_DISPLAY_CURRENT_MODE_CHANGED
inline val SDL3.Event.DisplayContentScaleChanged    get() = c.SDL_EVENT_DISPLAY_CONTENT_SCALE_CHANGED
inline val SDL3.Event.DisplayUsableBoundsChanged    get() = c.SDL_EVENT_DISPLAY_USABLE_BOUNDS_CHANGED

// Window events
inline val SDL3.Event.WindowShown               get() = c.SDL_EVENT_WINDOW_SHOWN
inline val SDL3.Event.WindowHidden              get() = c.SDL_EVENT_WINDOW_HIDDEN
inline val SDL3.Event.WindowExposed             get() = c.SDL_EVENT_WINDOW_EXPOSED
inline val SDL3.Event.WindowMoved               get() = c.SDL_EVENT_WINDOW_MOVED
inline val SDL3.Event.WindowResized             get() = c.SDL_EVENT_WINDOW_RESIZED
inline val SDL3.Event.WindowPixelSizeChanged    get() = c.SDL_EVENT_WINDOW_PIXEL_SIZE_CHANGED
inline val SDL3.Event.WindowMetalViewResized    get() = c.SDL_EVENT_WINDOW_METAL_VIEW_RESIZED
inline val SDL3.Event.WindowMinimized           get() = c.SDL_EVENT_WINDOW_MINIMIZED
inline val SDL3.Event.WindowMaximized           get() = c.SDL_EVENT_WINDOW_MAXIMIZED
inline val SDL3.Event.WindowRestored            get() = c.SDL_EVENT_WINDOW_RESTORED
inline val SDL3.Event.WindowMouseEnter          get() = c.SDL_EVENT_WINDOW_MOUSE_ENTER
inline val SDL3.Event.WindowMouseLeave          get() = c.SDL_EVENT_WINDOW_MOUSE_LEAVE
inline val SDL3.Event.WindowFocusGained         get() = c.SDL_EVENT_WINDOW_FOCUS_GAINED
inline val SDL3.Event.WindowFocusLost           get() = c.SDL_EVENT_WINDOW_FOCUS_LOST
inline val SDL3.Event.WindowCloseRequested      get() = c.SDL_EVENT_WINDOW_CLOSE_REQUESTED
inline val SDL3.Event.WindowHitTest             get() = c.SDL_EVENT_WINDOW_HIT_TEST
inline val SDL3.Event.WindowIccprofChanged      get() = c.SDL_EVENT_WINDOW_ICCPROF_CHANGED
inline val SDL3.Event.WindowDisplayChanged      get() = c.SDL_EVENT_WINDOW_DISPLAY_CHANGED
inline val SDL3.Event.WindowDisplayScaleChanged get() = c.SDL_EVENT_WINDOW_DISPLAY_SCALE_CHANGED
inline val SDL3.Event.WindowSafeAreaChanged     get() = c.SDL_EVENT_WINDOW_SAFE_AREA_CHANGED
inline val SDL3.Event.WindowOccluded            get() = c.SDL_EVENT_WINDOW_OCCLUDED
inline val SDL3.Event.WindowEnterFullscreen     get() = c.SDL_EVENT_WINDOW_ENTER_FULLSCREEN
inline val SDL3.Event.WindowLeaveFullscreen     get() = c.SDL_EVENT_WINDOW_LEAVE_FULLSCREEN
inline val SDL3.Event.WindowDestroyed           get() = c.SDL_EVENT_WINDOW_DESTROYED
inline val SDL3.Event.WindowHdrStateChanged     get() = c.SDL_EVENT_WINDOW_HDR_STATE_CHANGED
inline val SDL3.Event.WindowSettingsChanged     get() = c.SDL_EVENT_WINDOW_SETTINGS_CHANGED

// Keyboard events
inline val SDL3.Event.KeyDown                   get() = c.SDL_EVENT_KEY_DOWN
inline val SDL3.Event.KeyUp                     get() = c.SDL_EVENT_KEY_UP
inline val SDL3.Event.TextEditing               get() = c.SDL_EVENT_TEXT_EDITING
inline val SDL3.Event.TextInput                 get() = c.SDL_EVENT_TEXT_INPUT
inline val SDL3.Event.KeymapChanged             get() = c.SDL_EVENT_KEYMAP_CHANGED
inline val SDL3.Event.KeyboardAdded             get() = c.SDL_EVENT_KEYBOARD_ADDED
inline val SDL3.Event.KeyboardRemoved           get() = c.SDL_EVENT_KEYBOARD_REMOVED
inline val SDL3.Event.TextEditingCandidates     get() = c.SDL_EVENT_TEXT_EDITING_CANDIDATES
inline val SDL3.Event.ScreenKeyboardShown       get() = c.SDL_EVENT_SCREEN_KEYBOARD_SHOWN
inline val SDL3.Event.ScreenKeyboardHidden      get() = c.SDL_EVENT_SCREEN_KEYBOARD_HIDDEN

// Mouse events
inline val SDL3.Event.MouseMotion               get() = c.SDL_EVENT_MOUSE_MOTION
inline val SDL3.Event.MouseButtonDown           get() = c.SDL_EVENT_MOUSE_BUTTON_DOWN
inline val SDL3.Event.MouseButtonUp             get() = c.SDL_EVENT_MOUSE_BUTTON_UP
inline val SDL3.Event.MouseWheel                get() = c.SDL_EVENT_MOUSE_WHEEL
inline val SDL3.Event.MouseAdded                get() = c.SDL_EVENT_MOUSE_ADDED
inline val SDL3.Event.MouseRemoved              get() = c.SDL_EVENT_MOUSE_REMOVED

// Joystick events
inline val SDL3.Event.JoystickAxisMotion        get() = c.SDL_EVENT_JOYSTICK_AXIS_MOTION
inline val SDL3.Event.JoystickBallMotion        get() = c.SDL_EVENT_JOYSTICK_BALL_MOTION
inline val SDL3.Event.JoystickHatMotion         get() = c.SDL_EVENT_JOYSTICK_HAT_MOTION
inline val SDL3.Event.JoystickButtonDown        get() = c.SDL_EVENT_JOYSTICK_BUTTON_DOWN
inline val SDL3.Event.JoystickButtonUp          get() = c.SDL_EVENT_JOYSTICK_BUTTON_UP
inline val SDL3.Event.JoystickAdded             get() = c.SDL_EVENT_JOYSTICK_ADDED
inline val SDL3.Event.JoystickRemoved           get() = c.SDL_EVENT_JOYSTICK_REMOVED
inline val SDL3.Event.JoystickBatteryUpdated    get() = c.SDL_EVENT_JOYSTICK_BATTERY_UPDATED
inline val SDL3.Event.JoystickUpdateComplete    get() = c.SDL_EVENT_JOYSTICK_UPDATE_COMPLETE

// Gamepad events
inline val SDL3.Event.GamepadAxisMotion         get() = c.SDL_EVENT_GAMEPAD_AXIS_MOTION
inline val SDL3.Event.GamepadButtonDown         get() = c.SDL_EVENT_GAMEPAD_BUTTON_DOWN
inline val SDL3.Event.GamepadButtonUp           get() = c.SDL_EVENT_GAMEPAD_BUTTON_UP
inline val SDL3.Event.GamepadAdded              get() = c.SDL_EVENT_GAMEPAD_ADDED
inline val SDL3.Event.GamepadRemoved            get() = c.SDL_EVENT_GAMEPAD_REMOVED
inline val SDL3.Event.GamepadRemapped           get() = c.SDL_EVENT_GAMEPAD_REMAPPED
inline val SDL3.Event.GamepadTouchpadDown       get() = c.SDL_EVENT_GAMEPAD_TOUCHPAD_DOWN
inline val SDL3.Event.GamepadTouchpadMotion     get() = c.SDL_EVENT_GAMEPAD_TOUCHPAD_MOTION
inline val SDL3.Event.GamepadTouchpadUp         get() = c.SDL_EVENT_GAMEPAD_TOUCHPAD_UP
inline val SDL3.Event.GamepadSensorUpdate       get() = c.SDL_EVENT_GAMEPAD_SENSOR_UPDATE
inline val SDL3.Event.GamepadUpdateComplete     get() = c.SDL_EVENT_GAMEPAD_UPDATE_COMPLETE
inline val SDL3.Event.GamepadSteamHandleUpdated get() = c.SDL_EVENT_GAMEPAD_STEAM_HANDLE_UPDATED
inline val SDL3.Event.GamepadCapsenseTouch      get() = c.SDL_EVENT_GAMEPAD_CAPSENSE_TOUCH
inline val SDL3.Event.GamepadCapsenseRelease    get() = c.SDL_EVENT_GAMEPAD_CAPSENSE_RELEASE

// Touch events
inline val SDL3.Event.FingerDown      get() = c.SDL_EVENT_FINGER_DOWN
inline val SDL3.Event.FingerUp        get() = c.SDL_EVENT_FINGER_UP
inline val SDL3.Event.FingerMotion    get() = c.SDL_EVENT_FINGER_MOTION
inline val SDL3.Event.FingerCanceled  get() = c.SDL_EVENT_FINGER_CANCELED

// Pinch events
inline val SDL3.Event.PinchBegin  get() = c.SDL_EVENT_PINCH_BEGIN
inline val SDL3.Event.PinchUpdate get() = c.SDL_EVENT_PINCH_UPDATE
inline val SDL3.Event.PinchEnd    get() = c.SDL_EVENT_PINCH_END

// Clipboard events
inline val SDL3.Event.ClipboardUpdate get() = c.SDL_EVENT_CLIPBOARD_UPDATE

// Drag and drop events
inline val SDL3.Event.DropFile     get() = c.SDL_EVENT_DROP_FILE
inline val SDL3.Event.DropText     get() = c.SDL_EVENT_DROP_TEXT
inline val SDL3.Event.DropBegin    get() = c.SDL_EVENT_DROP_BEGIN
inline val SDL3.Event.DropComplete get() = c.SDL_EVENT_DROP_COMPLETE
inline val SDL3.Event.DropPosition get() = c.SDL_EVENT_DROP_POSITION

// Audio events
inline val SDL3.Event.AudioDeviceAdded         get() = c.SDL_EVENT_AUDIO_DEVICE_ADDED
inline val SDL3.Event.AudioDeviceRemoved        get() = c.SDL_EVENT_AUDIO_DEVICE_REMOVED
inline val SDL3.Event.AudioDeviceFormatChanged  get() = c.SDL_EVENT_AUDIO_DEVICE_FORMAT_CHANGED

// Sensor events
inline val SDL3.Event.SensorUpdate get() = c.SDL_EVENT_SENSOR_UPDATE

// Pen events
inline val SDL3.Event.PenProximityIn  get() = c.SDL_EVENT_PEN_PROXIMITY_IN
inline val SDL3.Event.PenProximityOut get() = c.SDL_EVENT_PEN_PROXIMITY_OUT
inline val SDL3.Event.PenDown         get() = c.SDL_EVENT_PEN_DOWN
inline val SDL3.Event.PenUp           get() = c.SDL_EVENT_PEN_UP
inline val SDL3.Event.PenButtonDown   get() = c.SDL_EVENT_PEN_BUTTON_DOWN
inline val SDL3.Event.PenButtonUp     get() = c.SDL_EVENT_PEN_BUTTON_UP
inline val SDL3.Event.PenMotion       get() = c.SDL_EVENT_PEN_MOTION
inline val SDL3.Event.PenAxis         get() = c.SDL_EVENT_PEN_AXIS

// Camera events
inline val SDL3.Event.CameraDeviceAdded    get() = c.SDL_EVENT_CAMERA_DEVICE_ADDED
inline val SDL3.Event.CameraDeviceRemoved  get() = c.SDL_EVENT_CAMERA_DEVICE_REMOVED
inline val SDL3.Event.CameraDeviceApproved get() = c.SDL_EVENT_CAMERA_DEVICE_APPROVED
inline val SDL3.Event.CameraDeviceDenied   get() = c.SDL_EVENT_CAMERA_DEVICE_DENIED

// Render events
inline val SDL3.Event.RenderTargetsReset get() = c.SDL_EVENT_RENDER_TARGETS_RESET
inline val SDL3.Event.RenderDeviceReset  get() = c.SDL_EVENT_RENDER_DEVICE_RESET
inline val SDL3.Event.RenderDeviceLost   get() = c.SDL_EVENT_RENDER_DEVICE_LOST

// User / last
inline val SDL3.Event.User get() = c.SDL_EVENT_USER
