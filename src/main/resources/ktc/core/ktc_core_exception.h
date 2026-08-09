/* ktc_core_exception.h - lightweight Kotlin-style exceptions over setjmp/longjmp
 *
 * Model (derived from the XCEP library, adapted to KTC objects):
 *
 *   - Each `try` pushes one ktc_ExcFrame (jmp_buf + state flags) onto a
 *     thread-local stack of active frames. Frames live on the C stack -
 *     zero heap per try.
 *
 *   - `throw` deep-copies the exception object PLUS its message bytes into a
 *     growable thread-local arena (one allocation per thread, realloc-grown,
 *     reused by every subsequent throw), then longjmps to the innermost frame.
 *     The copy is what lets the exception survive the jump: the throwing
 *     frame's locals are gone after longjmp.
 *
 *   - A matched `catch` that uses its binding copies the exception back OUT
 *     of the arena onto the catching frame (the object as a plain local, the
 *     message bytes into an alloca'd buffer). The arena is then immediately
 *     reusable, so a catch body can rethrow or throw a new exception.
 *
 *   - An uncaught exception prints a Kotlin-style stack trace (message +
 *     throw-site file:line) to stderr and exits with EXIT_FAILURE.
 *
 *   - Type matching is done by the transpiler, not at runtime: each KTC_CATCH
 *     receives a condition over KTC_EXC_TYPE_ID() enumerating the TYPE_IDs of
 *     every known class matching the caught class/interface.
 *
 * Control-flow notes (mirrors XCEP's for/do scheme):
 *
 *   KTC_TRY(f) { body }            for (frame f; !f.runOnce; f.runOnce=1, endTry(&f))
 *   KTC_CATCH(f, cond) { ... }     do { if (push f, setjmp == 0) { body }
 *   KTC_FINALLY(f) { ... }              else if (!f.handled && cond && (f.handled=1)) { ... }
 *   KTC_END_TRY;                        if (f.inFinally = 1) { ... } } while (0);
 *
 *   - throw with no active frame        → uncaught: print + exit
 *   - throw inside a catch block        → flags thrownInCatch, longjmps to the
 *     SAME frame: catches are skipped (handled is already set), finally runs
 *     once, then the end-of-try pop propagates outward.
 *   - throw inside a finally block      → pops the frame and propagates
 *     immediately (the new exception replaces the in-flight one; the finally
 *     does NOT re-run - no infinite loop).
 *   - `return` out of a try/catch       → the transpiler emits KTC_TRY_LEAVE
 *     for each enclosing frame (plus the finally bodies) before the return.
 *
 * Standard-C caveat inherited from setjmp/longjmp: locals of the function
 * containing the try that are modified inside the try body and read in a
 * catch are formally indeterminate unless volatile. The frame's own state
 * flags are volatile, so the mechanism itself is safe.
 */

#ifndef KTC_CORE_EXCEPTION_H
#define KTC_CORE_EXCEPTION_H

#include <setjmp.h>

/* Provides ktc_Int/ktc_UInt, ktc_String, ktc_Bool, ktc_core_tls and
   ktc_core_stacktrace_print. ktc_core.h re-includes this header at its end
   (same pragma-once dance as ktc_core_fs.h), so the types below are always
   defined by the time this block is compiled. */
#include "ktc_core.h"

#if defined(_MSC_VER)
    #define KTC_EXC_NORETURN __declspec(noreturn)
#else
    #define KTC_EXC_NORETURN _Noreturn
#endif

/* Functions containing a try block are emitted with this attribute. Locals
   modified between setjmp and longjmp and read after the jump are formally
   indeterminate (and ARE clobbered in practice at -O2: they live in registers
   that longjmp rolls back). Kotlin code can't be asked to write `volatile`,
   so the transpiler disables optimization for just those functions - the cost
   stays confined to functions that lexically contain a `try`.
   MSVC has no per-function equivalent; its setjmp intrinsic is more
   conservative, but high /O2 builds of try-heavy code remain at-your-own-risk
   there. */
#if defined(__clang__)
    #define KTC_TRY_FN __attribute__((optnone))
#elif defined(__GNUC__)
    #define KTC_TRY_FN __attribute__((optimize("-O0")))
#else
    #define KTC_TRY_FN
#endif

/* ==================
 * MARK: Types
 * ================== */

/* One active `try` scope. Stack-allocated by KTC_TRY, linked through prev.
   State flags are volatile: they are written between setjmp and longjmp and
   read after the jump, which requires volatile for defined behavior. */
typedef struct ktc_ExcFrame
{
    jmp_buf                  env;            /* longjmp target for this try */
    struct ktc_ExcFrame     *prev;           /* enclosing frame (or NULL)   */
    volatile ktc_Bool        runOnce;        /* for-loop ran its single pass */
    volatile ktc_Bool        thrown;         /* setjmp returned via longjmp  */
    volatile ktc_Bool        handled;        /* a catch clause matched       */
    volatile ktc_Bool        thrownInCatch;  /* throw escaped a catch body   */
    volatile ktc_Bool        inFinally;      /* executing the finally block  */
} ktc_ExcFrame;

/* Per-thread exception state: the frame stack, the arena, and the in-flight
   exception (valid from a throw until the program leaves the matching catch). */
typedef struct ktc_ExcState
{
    ktc_ExcFrame *stack;      /* innermost active try frame, or NULL          */
    char         *arena;      /* TLS arena holding the in-flight exception    */
    ktc_Int       arenaCap;   /* current arena capacity in bytes              */
    void         *obj;        /* exception object (points into arena)         */
    ktc_Int       size;       /* sizeof of the concrete exception struct      */
    ktc_UInt      typeId;     /* concrete class TYPE_ID (for catch matching)  */
    ktc_Int       msgOffset;  /* offset of the `message` ktc_String field in
                                 the object, or -1 when message is a getter   */
    const char   *msg;        /* message bytes (in arena, NUL-terminated)     */
    ktc_Int       msgLen;     /* message length (excluding the NUL)           */
    const char   *typeName;   /* Kotlin class name - static literal           */
    const char   *file;       /* throw site file - static literal             */
    ktc_Int       line;       /* throw site line                              */
} ktc_ExcState;

extern ktc_core_tls ktc_ExcState ktc_core_exc;

/* ==================
 * MARK: Functions
 * ================== */

/** Throw: copy the exception object and its message bytes into the TLS arena
 * (growing it if too small), patch the object's message field to point at the
 * arena copy, then longjmp to the innermost frame - or print an uncaught-
 * exception stack trace and exit when no frame is active.
 * inObj/inMsgPtr must NOT point into the arena itself (the transpiler always
 * passes frame-local storage: a fresh object, or a catch-taken copy). */
KTC_EXC_NORETURN void ktc_core_exc_throw(
    const void *inObj,        /* exception object to copy                    */
    ktc_Int     inSize,       /* sizeof(concrete struct)                     */
    ktc_UInt    inTypeId,     /* concrete class TYPE_ID                      */
    ktc_Int     inMsgOffset,  /* offsetof(struct, message), or -1            */
    const char *inMsgPtr,     /* message bytes (may be NULL when empty)      */
    ktc_Int     inMsgLen,     /* message length                              */
    const char *inTypeName,   /* Kotlin class name literal                   */
    const char *inFile,       /* throw site file literal                     */
    ktc_Int     inLine);      /* throw site line                             */

/** Copy the in-flight exception out of the arena onto the catching frame:
 * the object into inDst, the message bytes (NUL-terminated) into inMsgBuf
 * (which must hold KTC_EXC_MSG_LEN() + 1 bytes - typically alloca'd), and
 * re-patch the copied object's message field to inMsgBuf. After this call the
 * arena may be reused by a new throw from inside the catch body. */
void ktc_core_exc_take(void *inDst, char *inMsgBuf);

/** End-of-try: pop the frame, then propagate to the enclosing frame when the
 * exception was not handled (or was rethrown from a catch). Called from the
 * KTC_TRY for-loop increment, after body/catches/finally have run. */
void ktc_core_exc_end_try(ktc_ExcFrame *inFrame);

/** Free the calling thread's exception arena. Called by the KTC thread
 * trampoline when a thread's entry function returns (the main thread's arena
 * is reclaimed by the OS at process exit). */
void ktc_core_exc_thread_cleanup(void);

/* ==================
 * MARK: Builtin exception types (runtime checks)
 * ================== */

/* The C runtime cannot name generated TYPE_IDs, so the generated main()
 * registers the stdlib exception types the runtime checks throw
 * (IndexOutOfBoundsException for bounds, NullPointerException for null-deref).
 * Unregistered (library builds, unit-test transpiles without a main): the
 * checks fall back to the classic print-stack-trace-and-exit behavior. */
typedef struct ktc_ExcBuiltin
{
    ktc_UInt    typeId;     /* concrete class TYPE_ID (0 = unregistered)   */
    ktc_Int     size;       /* sizeof(concrete struct)                     */
    ktc_Int     msgOffset;  /* offsetof(struct, message)                   */
    const char *typeName;   /* Kotlin class name literal                   */
} ktc_ExcBuiltin;

extern ktc_ExcBuiltin ktc_core_exc_oob;   /* IndexOutOfBoundsException */
extern ktc_ExcBuiltin ktc_core_exc_npe;   /* NullPointerException      */

/** Register one builtin exception type (called from the generated main()). */
void ktc_core_exc_register(ktc_ExcBuiltin *outSlot, ktc_UInt inTypeId,
                           ktc_Int inSize, ktc_Int inMsgOffset, const char *inTypeName);

/** Throw the registered builtin exception (a zeroed instance with [inMsg]
 * patched in) - or, when the type was never registered, print a Kotlin-style
 * stack trace and exit. Either way this never returns. */
KTC_EXC_NORETURN void ktc_core_exc_throw_builtin(
    const ktc_ExcBuiltin *inBuiltin,
    const char *inMsg, ktc_Int inMsgLen,
    const char *inFile, ktc_Int inLine);

/* ==================
 * MARK: Try / Catch / Finally macros
 * ================== */

/* The transpiler passes a unique frame NAME per try so nested tries and the
   `return`-inside-try cleanup (KTC_TRY_LEAVE) can address frames directly. */

/** Open a try scope: declare the frame, push it, arm setjmp, run the body
 * when entering normally (setjmp == 0). */
#define KTC_TRY(NAME) \
    for (ktc_ExcFrame NAME = {0}; !NAME.runOnce; NAME.runOnce = true, ktc_core_exc_end_try(&NAME)) \
    do { \
        if ((NAME.prev = ktc_core_exc.stack, \
             ktc_core_exc.stack = &NAME, \
             NAME.thrown = (ktc_Bool)(setjmp(NAME.env) != 0)) == false)

/** Catch clause: runs when the try body threw, no earlier clause matched, and
 * COND (a transpiler-built TYPE_ID test) holds. Marks the frame handled. */
#define KTC_CATCH(NAME, COND) \
        else if (!NAME.handled && (COND) && (NAME.handled = true))

/** Finally clause: always runs (normal completion, after a catch, or while an
 * unhandled exception is propagating). Marks the frame so a throw from inside
 * the finally pops it and propagates instead of re-running the finally. */
#define KTC_FINALLY(NAME) \
        if ((NAME.inFinally = true))

/** Close the try construct (the KTC_TRY do-while; pop happens in the for). */
#define KTC_END_TRY \
    } while (0)

/** Pop NAME's frame without running ktc_core_exc_end_try - emitted by the
 * transpiler before a `return` that lexically exits the try construct
 * (followed by the finally body, which the transpiler re-emits). */
#define KTC_TRY_LEAVE(NAME) \
    (ktc_core_exc.stack = NAME.prev)

/* ==================
 * MARK: In-flight exception accessors (used in generated catch code)
 * ================== */

#define KTC_EXC_TYPE_ID() (ktc_core_exc.typeId)   /* concrete class TYPE_ID  */
#define KTC_EXC_MSG_LEN() (ktc_core_exc.msgLen)   /* message byte length     */
#define KTC_EXC_SIZE()    (ktc_core_exc.size)     /* concrete struct size    */

#endif /* KTC_CORE_EXCEPTION_H */
