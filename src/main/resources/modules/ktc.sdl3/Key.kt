@file:cInclude("SDL3/SDL.h")
package ktc.sdl3

// All inline - zero-overhead integer constants at use site.

// ==================
// Navigation & editing
inline val SDL3.Scancode.Escape    get() = C.SDL_SCANCODE_ESCAPE
inline val SDL3.Scancode.Return    get() = C.SDL_SCANCODE_RETURN
inline val SDL3.Scancode.Space     get() = C.SDL_SCANCODE_SPACE
inline val SDL3.Scancode.Backspace get() = C.SDL_SCANCODE_BACKSPACE
inline val SDL3.Scancode.Tab       get() = C.SDL_SCANCODE_TAB
inline val SDL3.Scancode.Delete    get() = C.SDL_SCANCODE_DELETE
inline val SDL3.Scancode.Insert    get() = C.SDL_SCANCODE_INSERT
inline val SDL3.Scancode.Home      get() = C.SDL_SCANCODE_HOME
inline val SDL3.Scancode.End       get() = C.SDL_SCANCODE_END
inline val SDL3.Scancode.PageUp    get() = C.SDL_SCANCODE_PAGEUP
inline val SDL3.Scancode.PageDown  get() = C.SDL_SCANCODE_PAGEDOWN

// ==================
// Arrow keys
inline val SDL3.Scancode.Left  get() = C.SDL_SCANCODE_LEFT
inline val SDL3.Scancode.Right get() = C.SDL_SCANCODE_RIGHT
inline val SDL3.Scancode.Up    get() = C.SDL_SCANCODE_UP
inline val SDL3.Scancode.Down  get() = C.SDL_SCANCODE_DOWN

// ==================
// Modifier keys
inline val SDL3.Scancode.LShift get() = C.SDL_SCANCODE_LSHIFT
inline val SDL3.Scancode.RShift get() = C.SDL_SCANCODE_RSHIFT
inline val SDL3.Scancode.LCtrl  get() = C.SDL_SCANCODE_LCTRL
inline val SDL3.Scancode.RCtrl  get() = C.SDL_SCANCODE_RCTRL
inline val SDL3.Scancode.LAlt   get() = C.SDL_SCANCODE_LALT
inline val SDL3.Scancode.RAlt   get() = C.SDL_SCANCODE_RALT
inline val SDL3.Scancode.LGui   get() = C.SDL_SCANCODE_LGUI
inline val SDL3.Scancode.RGui   get() = C.SDL_SCANCODE_RGUI
inline val SDL3.Scancode.CapsLock   get() = C.SDL_SCANCODE_CAPSLOCK
inline val SDL3.Scancode.NumLock    get() = C.SDL_SCANCODE_NUMLOCKCLEAR
inline val SDL3.Scancode.ScrollLock get() = C.SDL_SCANCODE_SCROLLLOCK

// ==================
// Letter keys A–Z
inline val SDL3.Scancode.A get() = C.SDL_SCANCODE_A
inline val SDL3.Scancode.B get() = C.SDL_SCANCODE_B
inline val SDL3.Scancode.C get() = C.SDL_SCANCODE_C
inline val SDL3.Scancode.D get() = C.SDL_SCANCODE_D
inline val SDL3.Scancode.E get() = C.SDL_SCANCODE_E
inline val SDL3.Scancode.F get() = C.SDL_SCANCODE_F
inline val SDL3.Scancode.G get() = C.SDL_SCANCODE_G
inline val SDL3.Scancode.H get() = C.SDL_SCANCODE_H
inline val SDL3.Scancode.I get() = C.SDL_SCANCODE_I
inline val SDL3.Scancode.J get() = C.SDL_SCANCODE_J
inline val SDL3.Scancode.K get() = C.SDL_SCANCODE_K
inline val SDL3.Scancode.L get() = C.SDL_SCANCODE_L
inline val SDL3.Scancode.M get() = C.SDL_SCANCODE_M
inline val SDL3.Scancode.N get() = C.SDL_SCANCODE_N
inline val SDL3.Scancode.O get() = C.SDL_SCANCODE_O
inline val SDL3.Scancode.P get() = C.SDL_SCANCODE_P
inline val SDL3.Scancode.Q get() = C.SDL_SCANCODE_Q
inline val SDL3.Scancode.R get() = C.SDL_SCANCODE_R
inline val SDL3.Scancode.S get() = C.SDL_SCANCODE_S
inline val SDL3.Scancode.T get() = C.SDL_SCANCODE_T
inline val SDL3.Scancode.U get() = C.SDL_SCANCODE_U
inline val SDL3.Scancode.V get() = C.SDL_SCANCODE_V
inline val SDL3.Scancode.W get() = C.SDL_SCANCODE_W
inline val SDL3.Scancode.X get() = C.SDL_SCANCODE_X
inline val SDL3.Scancode.Y get() = C.SDL_SCANCODE_Y
inline val SDL3.Scancode.Z get() = C.SDL_SCANCODE_Z

// ==================
// Number row 0–9
inline val SDL3.Scancode.Num0 get() = C.SDL_SCANCODE_0
inline val SDL3.Scancode.Num1 get() = C.SDL_SCANCODE_1
inline val SDL3.Scancode.Num2 get() = C.SDL_SCANCODE_2
inline val SDL3.Scancode.Num3 get() = C.SDL_SCANCODE_3
inline val SDL3.Scancode.Num4 get() = C.SDL_SCANCODE_4
inline val SDL3.Scancode.Num5 get() = C.SDL_SCANCODE_5
inline val SDL3.Scancode.Num6 get() = C.SDL_SCANCODE_6
inline val SDL3.Scancode.Num7 get() = C.SDL_SCANCODE_7
inline val SDL3.Scancode.Num8 get() = C.SDL_SCANCODE_8
inline val SDL3.Scancode.Num9 get() = C.SDL_SCANCODE_9

// ==================
// Function keys F1–F12
inline val SDL3.Scancode.F1  get() = C.SDL_SCANCODE_F1
inline val SDL3.Scancode.F2  get() = C.SDL_SCANCODE_F2
inline val SDL3.Scancode.F3  get() = C.SDL_SCANCODE_F3
inline val SDL3.Scancode.F4  get() = C.SDL_SCANCODE_F4
inline val SDL3.Scancode.F5  get() = C.SDL_SCANCODE_F5
inline val SDL3.Scancode.F6  get() = C.SDL_SCANCODE_F6
inline val SDL3.Scancode.F7  get() = C.SDL_SCANCODE_F7
inline val SDL3.Scancode.F8  get() = C.SDL_SCANCODE_F8
inline val SDL3.Scancode.F9  get() = C.SDL_SCANCODE_F9
inline val SDL3.Scancode.F10 get() = C.SDL_SCANCODE_F10
inline val SDL3.Scancode.F11 get() = C.SDL_SCANCODE_F11
inline val SDL3.Scancode.F12 get() = C.SDL_SCANCODE_F12

// ==================
// Numpad
inline val SDL3.Scancode.Kp0        get() = C.SDL_SCANCODE_KP_0
inline val SDL3.Scancode.Kp1        get() = C.SDL_SCANCODE_KP_1
inline val SDL3.Scancode.Kp2        get() = C.SDL_SCANCODE_KP_2
inline val SDL3.Scancode.Kp3        get() = C.SDL_SCANCODE_KP_3
inline val SDL3.Scancode.Kp4        get() = C.SDL_SCANCODE_KP_4
inline val SDL3.Scancode.Kp5        get() = C.SDL_SCANCODE_KP_5
inline val SDL3.Scancode.Kp6        get() = C.SDL_SCANCODE_KP_6
inline val SDL3.Scancode.Kp7        get() = C.SDL_SCANCODE_KP_7
inline val SDL3.Scancode.Kp8        get() = C.SDL_SCANCODE_KP_8
inline val SDL3.Scancode.Kp9        get() = C.SDL_SCANCODE_KP_9
inline val SDL3.Scancode.KpEnter    get() = C.SDL_SCANCODE_KP_ENTER
inline val SDL3.Scancode.KpPlus     get() = C.SDL_SCANCODE_KP_PLUS
inline val SDL3.Scancode.KpMinus    get() = C.SDL_SCANCODE_KP_MINUS
inline val SDL3.Scancode.KpMultiply get() = C.SDL_SCANCODE_KP_MULTIPLY
inline val SDL3.Scancode.KpDivide   get() = C.SDL_SCANCODE_KP_DIVIDE
inline val SDL3.Scancode.KpPeriod   get() = C.SDL_SCANCODE_KP_PERIOD

// ==================
// Punctuation / symbols
inline val SDL3.Scancode.Minus        get() = C.SDL_SCANCODE_MINUS
inline val SDL3.Scancode.Equals       get() = C.SDL_SCANCODE_EQUALS
inline val SDL3.Scancode.LeftBracket  get() = C.SDL_SCANCODE_LEFTBRACKET
inline val SDL3.Scancode.RightBracket get() = C.SDL_SCANCODE_RIGHTBRACKET
inline val SDL3.Scancode.Backslash    get() = C.SDL_SCANCODE_BACKSLASH
inline val SDL3.Scancode.Semicolon    get() = C.SDL_SCANCODE_SEMICOLON
inline val SDL3.Scancode.Apostrophe   get() = C.SDL_SCANCODE_APOSTROPHE
inline val SDL3.Scancode.Grave        get() = C.SDL_SCANCODE_GRAVE
inline val SDL3.Scancode.Comma        get() = C.SDL_SCANCODE_COMMA
inline val SDL3.Scancode.Period       get() = C.SDL_SCANCODE_PERIOD
inline val SDL3.Scancode.Slash        get() = C.SDL_SCANCODE_SLASH
