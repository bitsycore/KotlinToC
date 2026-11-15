/* What ktc2's part of atomicfu needs of the system: a lock with a condition (atomicfu.def). */
#ifndef ATOMICFU_H
#define ATOMICFU_H

#include <stdint.h>
#include <stdlib.h>
#ifdef _WIN32
#include "atomicfu_win32.h"
#else
#include <errno.h>
#include <pthread.h>
#include <time.h>
#endif

/** A lock and a condition to wait on while holding it. */
typedef struct atomicfu_Monitor {
#ifdef _WIN32
	void* lock;
	void* condition;
#else
	pthread_mutex_t lock;
	pthread_cond_t condition;
#endif
} atomicfu_Monitor;

/** A new monitor, from malloc; NULL when there is no memory. */
static inline atomicfu_Monitor* atomicfu_new(void)
{
	atomicfu_Monitor* monitor = (atomicfu_Monitor*)calloc(1, sizeof(atomicfu_Monitor));
	if (monitor == NULL) return NULL;
#ifdef _WIN32
	InitializeSRWLock(&monitor->lock);
	InitializeConditionVariable(&monitor->condition);
#else
	pthread_mutex_init(&monitor->lock, NULL);
	pthread_cond_init(&monitor->condition, NULL);
#endif
	return monitor;
}

static inline void atomicfu_free(atomicfu_Monitor* monitor)
{
#ifndef _WIN32
	pthread_cond_destroy(&monitor->condition);
	pthread_mutex_destroy(&monitor->lock);
#endif
	free(monitor);
}

static inline void atomicfu_lock(atomicfu_Monitor* monitor)
{
#ifdef _WIN32
	AcquireSRWLockExclusive(&monitor->lock);
#else
	pthread_mutex_lock(&monitor->lock);
#endif
}

static inline void atomicfu_unlock(atomicfu_Monitor* monitor)
{
#ifdef _WIN32
	ReleaseSRWLockExclusive(&monitor->lock);
#else
	pthread_mutex_unlock(&monitor->lock);
#endif
}

/** Waits on the condition, the lock held, until woken (or for no reason, as conditions may). */
static inline void atomicfu_wait(atomicfu_Monitor* monitor)
{
#ifdef _WIN32
	SleepConditionVariableSRW(&monitor->condition, &monitor->lock, 0xFFFFFFFFu, 0);
#else
	pthread_cond_wait(&monitor->condition, &monitor->lock);
#endif
}

/** As atomicfu_wait, for at most [nanos] nanoseconds. */
static inline void atomicfu_wait_nanos(atomicfu_Monitor* monitor, int64_t nanos)
{
	if (nanos <= 0) return;
#ifdef _WIN32
	int64_t milliseconds = (nanos + 999999) / 1000000;
	if (milliseconds >= 0xFFFFFFFFLL) milliseconds = 0xFFFFFFFELL;
	SleepConditionVariableSRW(&monitor->condition, &monitor->lock, (unsigned long)milliseconds, 0);
#else
	struct timespec until;
	clock_gettime(CLOCK_REALTIME, &until);
	int64_t seconds = nanos / 1000000000;
	if (seconds > INT32_MAX) seconds = INT32_MAX;
	until.tv_sec += (time_t)seconds;
	until.tv_nsec += (long)(nanos % 1000000000);
	if (until.tv_nsec >= 1000000000) {
		until.tv_sec += 1;
		until.tv_nsec -= 1000000000;
	}
	pthread_cond_timedwait(&monitor->condition, &monitor->lock, &until);
#endif
}

static inline void atomicfu_notify(atomicfu_Monitor* monitor)
{
#ifdef _WIN32
	WakeConditionVariable(&monitor->condition);
#else
	pthread_cond_signal(&monitor->condition);
#endif
}

#endif
