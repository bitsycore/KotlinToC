@file:cInclude("SDL3/SDL.h")
package sdl3

// All inline — zero-overhead integer constants at use site.

// ==================
// Navigation & editing
inline val SDL3.Scancode.Escape    get() = c.SDL_SCANCODE_ESCAPE
inline val SDL3.Scancode.Return    get() = c.SDL_SCANCODE_RETURN
inline val SDL3.Scancode.Space     get() = c.SDL_SCANCODE_SPACE
inline val SDL3.Scancode.Backspace get() = c.SDL_SCANCODE_BACKSPACE
inline val SDL3.Scancode.Tab       get() = c.SDL_SCANCODE_TAB
inline val SDL3.Scancode.Delete    get() = c.SDL_SCANCODE_DELETE
inline val SDL3.Scancode.Insert    get() = c.SDL_SCANCODE_INSERT
inline val SDL3.Scancode.Home      get() = c.SDL_SCANCODE_HOME
inline val SDL3.Scancode.End       get() = c.SDL_SCANCODE_END
inline val SDL3.Scancode.PageUp    get() = c.SDL_SCANCODE_PAGEUP
inline val SDL3.Scancode.PageDown  get() = c.SDL_SCANCODE_PAGEDOWN

// ==================
// Arrow keys
inline val SDL3.Scancode.Left  get() = c.SDL_SCANCODE_LEFT
inline val SDL3.Scancode.Right get() = c.SDL_SCANCODE_RIGHT
inline val SDL3.Scancode.Up    get() = c.SDL_SCANCODE_UP
inline val SDL3.Scancode.Down  get() = c.SDL_SCANCODE_DOWN

// ==================
// Modifier keys
inline val SDL3.Scancode.LShift get() = c.SDL_SCANCODE_LSHIFT
inline val SDL3.Scancode.RShift get() = c.SDL_SCANCODE_RSHIFT
inline val SDL3.Scancode.LCtrl  get() = c.SDL_SCANCODE_LCTRL
inline val SDL3.Scancode.RCtrl  get() = c.SDL_SCANCODE_RCTRL
inline val SDL3.Scancode.LAlt   get() = c.SDL_SCANCODE_LALT
inline val SDL3.Scancode.RAlt   get() = c.SDL_SCANCODE_RALT
inline val SDL3.Scancode.LGui   get() = c.SDL_SCANCODE_LGUI
inline val SDL3.Scancode.RGui   get() = c.SDL_SCANCODE_RGUI
inline val SDL3.Scancode.CapsLock   get() = c.SDL_SCANCODE_CAPSLOCK
inline val SDL3.Scancode.NumLock    get() = c.SDL_SCANCODE_NUMLOCKCLEAR
inline val SDL3.Scancode.ScrollLock get() = c.SDL_SCANCODE_SCROLLLOCK

// ==================
// Letter keys A–Z
inline val SDL3.Scancode.A get() = c.SDL_SCANCODE_A
inline val SDL3.Scancode.B get() = c.SDL_SCANCODE_B
inline val SDL3.Scancode.C get() = c.SDL_SCANCODE_C
inline val SDL3.Scancode.D get() = c.SDL_SCANCODE_D
inline val SDL3.Scancode.E get() = c.SDL_SCANCODE_E
inline val SDL3.Scancode.F get() = c.SDL_SCANCODE_F
inline val SDL3.Scancode.G get() = c.SDL_SCANCODE_G
inline val SDL3.Scancode.H get() = c.SDL_SCANCODE_H
inline val SDL3.Scancode.I get() = c.SDL_SCANCODE_I
inline val SDL3.Scancode.J get() = c.SDL_SCANCODE_J
inline val SDL3.Scancode.K get() = c.SDL_SCANCODE_K
inline val SDL3.Scancode.L get() = c.SDL_SCANCODE_L
inline val SDL3.Scancode.M get() = c.SDL_SCANCODE_M
inline val SDL3.Scancode.N get() = c.SDL_SCANCODE_N
inline val SDL3.Scancode.O get() = c.SDL_SCANCODE_O
inline val SDL3.Scancode.P get() = c.SDL_SCANCODE_P
inline val SDL3.Scancode.Q get() = c.SDL_SCANCODE_Q
inline val SDL3.Scancode.R get() = c.SDL_SCANCODE_R
inline val SDL3.Scancode.S get() = c.SDL_SCANCODE_S
inline val SDL3.Scancode.T get() = c.SDL_SCANCODE_T
inline val SDL3.Scancode.U get() = c.SDL_SCANCODE_U
inline val SDL3.Scancode.V get() = c.SDL_SCANCODE_V
inline val SDL3.Scancode.W get() = c.SDL_SCANCODE_W
inline val SDL3.Scancode.X get() = c.SDL_SCANCODE_X
inline val SDL3.Scancode.Y get() = c.SDL_SCANCODE_Y
inline val SDL3.Scancode.Z get() = c.SDL_SCANCODE_Z

// ==================
// Number row 0–9
inline val SDL3.Scancode.Num0 get() = c.SDL_SCANCODE_0
inline val SDL3.Scancode.Num1 get() = c.SDL_SCANCODE_1
inline val SDL3.Scancode.Num2 get() = c.SDL_SCANCODE_2
inline val SDL3.Scancode.Num3 get() = c.SDL_SCANCODE_3
inline val SDL3.Scancode.Num4 get() = c.SDL_SCANCODE_4
inline val SDL3.Scancode.Num5 get() = c.SDL_SCANCODE_5
inline val SDL3.Scancode.Num6 get() = c.SDL_SCANCODE_6
inline val SDL3.Scancode.Num7 get() = c.SDL_SCANCODE_7
inline val SDL3.Scancode.Num8 get() = c.SDL_SCANCODE_8
inline val SDL3.Scancode.Num9 get() = c.SDL_SCANCODE_9

// ==================
// Function keys F1–F12
inline val SDL3.Scancode.F1  get() = c.SDL_SCANCODE_F1
inline val SDL3.Scancode.F2  get() = c.SDL_SCANCODE_F2
inline val SDL3.Scancode.F3  get() = c.SDL_SCANCODE_F3
inline val SDL3.Scancode.F4  get() = c.SDL_SCANCODE_F4
inline val SDL3.Scancode.F5  get() = c.SDL_SCANCODE_F5
inline val SDL3.Scancode.F6  get() = c.SDL_SCANCODE_F6
inline val SDL3.Scancode.F7  get() = c.SDL_SCANCODE_F7
inline val SDL3.Scancode.F8  get() = c.SDL_SCANCODE_F8
inline val SDL3.Scancode.F9  get() = c.SDL_SCANCODE_F9
inline val SDL3.Scancode.F10 get() = c.SDL_SCANCODE_F10
inline val SDL3.Scancode.F11 get() = c.SDL_SCANCODE_F11
inline val SDL3.Scancode.F12 get() = c.SDL_SCANCODE_F12

// ==================
// Numpad
inline val SDL3.Scancode.Kp0        get() = c.SDL_SCANCODE_KP_0
inline val SDL3.Scancode.Kp1        get() = c.SDL_SCANCODE_KP_1
inline val SDL3.Scancode.Kp2        get() = c.SDL_SCANCODE_KP_2
inline val SDL3.Scancode.Kp3        get() = c.SDL_SCANCODE_KP_3
inline val SDL3.Scancode.Kp4        get() = c.SDL_SCANCODE_KP_4
inline val SDL3.Scancode.Kp5        get() = c.SDL_SCANCODE_KP_5
inline val SDL3.Scancode.Kp6        get() = c.SDL_SCANCODE_KP_6
inline val SDL3.Scancode.Kp7        get() = c.SDL_SCANCODE_KP_7
inline val SDL3.Scancode.Kp8        get() = c.SDL_SCANCODE_KP_8
inline val SDL3.Scancode.Kp9        get() = c.SDL_SCANCODE_KP_9
inline val SDL3.Scancode.KpEnter    get() = c.SDL_SCANCODE_KP_ENTER
inline val SDL3.Scancode.KpPlus     get() = c.SDL_SCANCODE_KP_PLUS
inline val SDL3.Scancode.KpMinus    get() = c.SDL_SCANCODE_KP_MINUS
inline val SDL3.Scancode.KpMultiply get() = c.SDL_SCANCODE_KP_MULTIPLY
inline val SDL3.Scancode.KpDivide   get() = c.SDL_SCANCODE_KP_DIVIDE
inline val SDL3.Scancode.KpPeriod   get() = c.SDL_SCANCODE_KP_PERIOD

// ==================
// Punctuation / symbols
inline val SDL3.Scancode.Minus        get() = c.SDL_SCANCODE_MINUS
inline val SDL3.Scancode.Equals       get() = c.SDL_SCANCODE_EQUALS
inline val SDL3.Scancode.LeftBracket  get() = c.SDL_SCANCODE_LEFTBRACKET
inline val SDL3.Scancode.RightBracket get() = c.SDL_SCANCODE_RIGHTBRACKET
inline val SDL3.Scancode.Backslash    get() = c.SDL_SCANCODE_BACKSLASH
inline val SDL3.Scancode.Semicolon    get() = c.SDL_SCANCODE_SEMICOLON
inline val SDL3.Scancode.Apostrophe   get() = c.SDL_SCANCODE_APOSTROPHE
inline val SDL3.Scancode.Grave        get() = c.SDL_SCANCODE_GRAVE
inline val SDL3.Scancode.Comma        get() = c.SDL_SCANCODE_COMMA
inline val SDL3.Scancode.Period       get() = c.SDL_SCANCODE_PERIOD
inline val SDL3.Scancode.Slash        get() = c.SDL_SCANCODE_SLASH
