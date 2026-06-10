/* ktc_thread.c — ktc thread utility definitions */
#include "ktc_thread.h"
#include <stdlib.h>

/* From ktc_core_exception.h — declared here to avoid pulling all of
   ktc_core.h into this TU (ktc_core.h includes ktc_thread.h mid-file). */
void ktc_core_exc_thread_cleanup(void);

#ifdef _WIN32
    #include <process.h>   /* _beginthreadex */
#else
    #include <time.h>      /* nanosleep */
    #include <sched.h>     /* sched_yield */
#endif

/* ==================
 * MARK: call-once TLS (POSIX)
 * ================== */

#ifndef _WIN32

/* TLS pointer used by ktc_thread_once_trampoline to recover fn on each thread. */
ktc_core_tls ktc_thread_once_t *ktc_thread_once_current = NULL;

#endif

/* ==================
 * MARK: Thread control block
 * ================== */

/* Heap-allocated control block: the entry + arg plus the platform thread handle.
   The trampoline adapts the platform thread signature to ktc_thread_fn_t. */
typedef struct
{
#ifdef _WIN32
    HANDLE          handle;
#else
    pthread_t       thread;
#endif
    ktc_thread_fn_t fn;
    void           *arg;
} ktc_thread_block_t;

/* ==================
 * MARK: Windows
 * ================== */

#ifdef _WIN32

static unsigned __stdcall ktc_thread_trampoline(void *param)
{
    ktc_thread_block_t *block = (ktc_thread_block_t *)param;
    block->fn(block->arg);
    ktc_core_exc_thread_cleanup();   /* free this thread's exception arena */
    return 0;
}

void *ktc_core_thread_start(ktc_thread_fn_t fn, void *arg)
{
    ktc_thread_block_t *block = (ktc_thread_block_t *)malloc(sizeof(*block));
    if (!block) return NULL;
    block->fn  = fn;
    block->arg = arg;
    block->handle = (HANDLE)_beginthreadex(NULL, 0, ktc_thread_trampoline, block, 0, NULL);
    if (!block->handle) { free(block); return NULL; }
    return block;
}

int ktc_core_thread_join(void *handle)
{
    ktc_thread_block_t *block = (ktc_thread_block_t *)handle;
    if (!block) return 1;
    WaitForSingleObject(block->handle, INFINITE);
    CloseHandle(block->handle);
    free(block);
    return 0;
}

void ktc_core_thread_sleep_ms(int ms) { if (ms > 0) Sleep((DWORD)ms); }
void ktc_core_thread_yield(void)      { SwitchToThread(); }

void *ktc_core_mutex_create(void)
{
    CRITICAL_SECTION *mutex = (CRITICAL_SECTION *)malloc(sizeof(*mutex));
    if (mutex) InitializeCriticalSection(mutex);
    return mutex;
}

void ktc_core_mutex_lock(void *mutex)    { if (mutex) EnterCriticalSection((CRITICAL_SECTION *)mutex); }
void ktc_core_mutex_unlock(void *mutex)  { if (mutex) LeaveCriticalSection((CRITICAL_SECTION *)mutex); }
void ktc_core_mutex_destroy(void *mutex)
{
    if (mutex) { DeleteCriticalSection((CRITICAL_SECTION *)mutex); free(mutex); }
}

/* ==================
 * MARK: POSIX
 * ================== */

#else

static void *ktc_thread_trampoline(void *param)
{
    ktc_thread_block_t *block = (ktc_thread_block_t *)param;
    block->fn(block->arg);
    ktc_core_exc_thread_cleanup();   /* free this thread's exception arena */
    return NULL;
}

void *ktc_core_thread_start(ktc_thread_fn_t fn, void *arg)
{
    ktc_thread_block_t *block = (ktc_thread_block_t *)malloc(sizeof(*block));
    if (!block) return NULL;
    block->fn  = fn;
    block->arg = arg;
    if (pthread_create(&block->thread, NULL, ktc_thread_trampoline, block) != 0) { free(block); return NULL; }
    return block;
}

int ktc_core_thread_join(void *handle)
{
    ktc_thread_block_t *block = (ktc_thread_block_t *)handle;
    if (!block) return 1;
    int rc = pthread_join(block->thread, NULL);
    free(block);
    return rc;
}

void ktc_core_thread_sleep_ms(int ms)
{
    if (ms <= 0) return;
    struct timespec ts;
    ts.tv_sec  = ms / 1000;
    ts.tv_nsec = (long)(ms % 1000) * 1000000L;
    nanosleep(&ts, NULL);
}

void ktc_core_thread_yield(void) { sched_yield(); }

void *ktc_core_mutex_create(void)
{
    pthread_mutex_t *mutex = (pthread_mutex_t *)malloc(sizeof(*mutex));
    if (mutex) pthread_mutex_init(mutex, NULL);
    return mutex;
}

void ktc_core_mutex_lock(void *mutex)    { if (mutex) pthread_mutex_lock((pthread_mutex_t *)mutex); }
void ktc_core_mutex_unlock(void *mutex)  { if (mutex) pthread_mutex_unlock((pthread_mutex_t *)mutex); }
void ktc_core_mutex_destroy(void *mutex)
{
    if (mutex) { pthread_mutex_destroy((pthread_mutex_t *)mutex); free(mutex); }
}

#endif
