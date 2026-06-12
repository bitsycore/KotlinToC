/* ktc_core_exception.c — TLS arena + throw/take/end-try for KTC exceptions */
#include "ktc_core.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* The arena is an intentional per-thread cache (freed by the thread
   trampoline, reclaimed by the OS for the main thread) — keep it out of the
   KTC_MEM_TRACK leak report. */
#ifdef KTC_MEM_TRACK
    #undef realloc
    #undef free
#endif

/* ==================
 * MARK: TLS state
 * ================== */

ktc_core_tls ktc_ExcState ktc_core_exc = {0};

/* Arena sizing: never shrink, start at a cacheline-friendly minimum and grow
   by doubling so repeated big throws settle quickly. */
#define KTC_EXC_ARENA_MIN 256

/* ==================
 * MARK: Internals
 * ================== */

/* Print "Uncaught exception <Type>: <message>" with a Kotlin-style stack
   trace pointing at the throw site, then exit. Never returns. */
static KTC_EXC_NORETURN void ktc_exc_uncaught(void)
{
    char vMsg[512];                                            /* formatted headline buffer */
    int  vLen = snprintf(vMsg, sizeof(vMsg), "Uncaught exception %s: %.*s",
                         ktc_core_exc.typeName ? ktc_core_exc.typeName : "Throwable",
                         (int)ktc_core_exc.msgLen,
                         ktc_core_exc.msg ? ktc_core_exc.msg : "");
    if (vLen < 0 || vLen >= (int)sizeof(vMsg)) vLen = (int)sizeof(vMsg) - 1;
    ktc_core_stacktrace_print(vMsg, vLen,
                              ktc_core_exc.file, ktc_core_exc.file ? (int)strlen(ktc_core_exc.file) : 0,
                              (int)ktc_core_exc.line);
    exit(EXIT_FAILURE);
}

/* Ensure the arena holds at least inNeeded bytes. The arena is one block per
   thread, grown with realloc (old contents are dead at this point — a new
   throw fully overwrites it) and reused for every subsequent throw. */
static void ktc_exc_arena_reserve(ktc_Int inNeeded)
{
    if (ktc_core_exc.arenaCap >= inNeeded) return;
    ktc_Int vCap = ktc_core_exc.arenaCap > 0 ? ktc_core_exc.arenaCap : KTC_EXC_ARENA_MIN;
    while (vCap < inNeeded) vCap *= 2;
    char *vNew = (char *)realloc(ktc_core_exc.arena, (size_t)vCap);
    if (!vNew) {
        static const char kOom[] = "ExceptionArenaOutOfMemory: cannot grow the exception arena";
        ktc_core_stacktrace_print(kOom, (int)(sizeof(kOom) - 1), NULL, 0, 0);
        exit(EXIT_FAILURE);
    }
    ktc_core_exc.arena    = vNew;
    ktc_core_exc.arenaCap = vCap;
}

/* ==================
 * MARK: Throw
 * ================== */

KTC_EXC_NORETURN void ktc_core_exc_throw(
    const void *inObj,
    ktc_Int     inSize,
    ktc_UInt    inTypeId,
    ktc_Int     inMsgOffset,
    const char *inMsgPtr,
    ktc_Int     inMsgLen,
    const char *inTypeName,
    const char *inFile,
    ktc_Int     inLine)
{
    ktc_ExcFrame *vFrame = ktc_core_exc.stack;                 /* innermost active try frame */

    if (inMsgPtr == NULL) inMsgLen = 0;

    /* Layout in the arena: [object bytes][message bytes][NUL]. inObj/inMsgPtr
       are frame-local by construction (see header), so plain memcpy is safe
       even right after a realloc moved the arena. */
    ktc_exc_arena_reserve(inSize + inMsgLen + 1);
    char *vObj = ktc_core_exc.arena;                           /* arena copy of the object   */
    char *vMsg = ktc_core_exc.arena + inSize;                  /* arena copy of the message  */
    memcpy(vObj, inObj, (size_t)inSize);
    memcpy(vMsg, inMsgPtr, (size_t)inMsgLen);
    vMsg[inMsgLen] = '\0';

    /* Re-point the copied object's `message` field at the arena bytes so the
       object stays self-consistent across the longjmp. */
    if (inMsgOffset >= 0)
        *(ktc_String *)(vObj + inMsgOffset) = (ktc_String){vMsg, inMsgLen};

    ktc_core_exc.obj       = vObj;
    ktc_core_exc.size      = inSize;
    ktc_core_exc.typeId    = inTypeId;
    ktc_core_exc.msgOffset = inMsgOffset;
    ktc_core_exc.msg       = vMsg;
    ktc_core_exc.msgLen    = inMsgLen;
    ktc_core_exc.typeName  = inTypeName;
    ktc_core_exc.file      = inFile;
    ktc_core_exc.line      = inLine;

    if (vFrame != NULL && vFrame->inFinally) {
        /* Throw from a finally block: this frame is done (re-entering it would
           re-run the finally forever) — pop it and propagate outward. The new
           exception replaces the in-flight one, matching Kotlin semantics. */
        ktc_core_exc.stack = vFrame->prev;
        vFrame = vFrame->prev;
    } else if (vFrame != NULL && vFrame->handled) {
        /* Throw from a catch block: longjmp back to the SAME frame. Catches
           are skipped (handled is set), finally runs, then the end-of-try pop
           propagates the new exception outward. */
        vFrame->thrownInCatch = true;
    }

    if (vFrame != NULL)
        longjmp(vFrame->env, 1);

    ktc_exc_uncaught();
}

/* ==================
 * MARK: Catch take
 * ================== */

void ktc_core_exc_take(void *inDst, char *inMsgBuf)
{
    memcpy(inDst, ktc_core_exc.obj, (size_t)ktc_core_exc.size);
    memcpy(inMsgBuf, ktc_core_exc.msg, (size_t)ktc_core_exc.msgLen);
    inMsgBuf[ktc_core_exc.msgLen] = '\0';
    if (ktc_core_exc.msgOffset >= 0)
        *(ktc_String *)((char *)inDst + ktc_core_exc.msgOffset) =
            (ktc_String){inMsgBuf, ktc_core_exc.msgLen};
}

/* ==================
 * MARK: End of try
 * ================== */

void ktc_core_exc_end_try(ktc_ExcFrame *inFrame)
{
    ktc_core_exc.stack = inFrame->prev;

    /* Propagate when the exception was never handled, or when a catch body
       threw (rethrow or a brand-new exception). */
    ktc_Bool vPropagate = (inFrame->thrown && !inFrame->handled) || inFrame->thrownInCatch;
    if (!vPropagate) return;

    if (ktc_core_exc.stack != NULL)
        longjmp(ktc_core_exc.stack->env, 1);

    ktc_exc_uncaught();
}

/* ==================
 * MARK: Builtin exception types (runtime checks)
 * ================== */

ktc_ExcBuiltin ktc_core_exc_oob = {0};
ktc_ExcBuiltin ktc_core_exc_npe = {0};

void ktc_core_exc_register(ktc_ExcBuiltin *outSlot, ktc_UInt inTypeId,
                           ktc_Int inSize, ktc_Int inMsgOffset, const char *inTypeName)
{
    outSlot->typeId    = inTypeId;
    outSlot->size      = inSize;
    outSlot->msgOffset = inMsgOffset;
    outSlot->typeName  = inTypeName;
}

KTC_EXC_NORETURN void ktc_core_exc_throw_builtin(
    const ktc_ExcBuiltin *inBuiltin,
    const char *inMsg, ktc_Int inMsgLen,
    const char *inFile, ktc_Int inLine)
{
    if (inBuiltin->size > 0) {
        /* A zeroed instance is a valid exception object: every stdlib exception
           stores only `message`, which the throw patches at msgOffset. */
        void *vObj = ktc_core_alloca((size_t)inBuiltin->size);
        memset(vObj, 0, (size_t)inBuiltin->size);
        ktc_core_exc_throw(vObj, inBuiltin->size, inBuiltin->typeId, inBuiltin->msgOffset,
                           inMsg, inMsgLen, inBuiltin->typeName, inFile, inLine);
    }
    /* Unregistered (no generated main ran): classic hard exit. */
    ktc_core_stacktrace_print(inMsg, (int)inMsgLen,
                              inFile, inFile ? (int)strlen(inFile) : 0, (int)inLine);
    exit(EXIT_FAILURE);
}

/* ==================
 * MARK: Thread cleanup
 * ================== */

void ktc_core_exc_thread_cleanup(void)
{
    free(ktc_core_exc.arena);
    ktc_core_exc.arena    = NULL;
    ktc_core_exc.arenaCap = 0;
    ktc_core_exc.stack    = NULL;
}
