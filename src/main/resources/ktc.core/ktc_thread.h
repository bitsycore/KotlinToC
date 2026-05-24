/* ktc_thread.h — portable one-time init (call-once) */
#pragma once

/*
 * ktc_core_tls is normally defined by ktc_core.h before this header is included.
 * The #ifndef guard allows ktc_thread.h to be included standalone as well.
 */
#ifndef ktc_core_tls
    #if defined(__STDC_VERSION__) && (__STDC_VERSION__ >= 201112L)
        #define ktc_core_tls _Thread_local
    #elif defined(__cplusplus) && (__cplusplus >= 201103L)
        #define ktc_core_tls thread_local
    #elif defined(_MSC_VER)
        #define ktc_core_tls __declspec(thread)
    #elif defined(__GNUC__) || defined(__clang__) || defined(__INTEL_COMPILER)
        #define ktc_core_tls __thread
    #else
        #define ktc_core_tls
        #warning "Thread-local storage not supported on this compiler."
    #endif
#endif

#ifdef _WIN32

    #define WIN32_LEAN_AND_MEAN
    #include <windows.h>

#else

    #include <pthread.h>

#endif


typedef void (*ktc_thread_once_func_t)(void);


/* ==================
 * MARK: Windows
 * ================== */

#ifdef _WIN32

    typedef INIT_ONCE ktc_thread_once_t;

    #define KTC_THREAD_ONCE_INIT \
        INIT_ONCE_STATIC_INIT

    typedef struct {
        ktc_thread_once_func_t fn;
    } ktc_thread_once_ctx_t;

    static BOOL CALLBACK ktc_thread_once_callback(
        PINIT_ONCE init_once,
        PVOID      parameter,
        PVOID     *context)
    {
        (void)init_once;
        (void)context;
        ((ktc_thread_once_ctx_t *)parameter)->fn();
        return TRUE;
    }

    static inline void ktc_thread_call_once(
        ktc_thread_once_t      *once,
        ktc_thread_once_func_t  fn)
    {
        ktc_thread_once_ctx_t ctx;
        ctx.fn = fn;
        InitOnceExecuteOnce(once, ktc_thread_once_callback, &ctx, NULL);
    }

/* ==================
 * MARK: POSIX
 * ==================
 *
 * pthread_once only accepts void (*)(void), so fn is routed through
 * a thread-local pointer — each calling thread sets its own TLS slot
 * before pthread_once, then the trampoline reads from TLS.
 *
 * Note: once->fn is written by every thread that calls ktc_thread_call_once.
 * This is formally a data race when multiple threads race, but benign in
 * practice because fn is always the same compile-time constant per once object.
 * ================== */

#else

    typedef struct {
        pthread_once_t         once;
        ktc_thread_once_func_t fn;
    } ktc_thread_once_t;

    #define KTC_THREAD_ONCE_INIT \
        {                        \
            PTHREAD_ONCE_INIT,   \
            NULL                 \
        }

    /* Defined in ktc_thread.c — one definition per linked binary. */
    extern ktc_core_tls ktc_thread_once_t *ktc_thread_once_current;

    static void ktc_thread_once_trampoline(void)
    {
        ktc_thread_once_current->fn();
    }

    static inline void ktc_thread_call_once(
        ktc_thread_once_t      *once,
        ktc_thread_once_func_t  fn)
    {
        once->fn                 = fn;
        ktc_thread_once_current  = once;
        pthread_once(&once->once, ktc_thread_once_trampoline);
    }

#endif
