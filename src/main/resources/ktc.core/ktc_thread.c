/* ktc_thread.c — ktc thread utility definitions */
#include "ktc_thread.h"

/* ==================
 * MARK: POSIX
 * ================== */

#ifndef _WIN32

/* TLS pointer used by ktc_thread_once_trampoline to recover fn on each thread. */
ktc_core_tls ktc_thread_once_t *ktc_thread_once_current = NULL;

#endif
