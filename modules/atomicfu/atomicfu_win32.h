/* The few functions of Windows atomicfu.h calls, declared here rather than by <windows.h>. */
#ifndef ATOMICFU_WIN32_H
#define ATOMICFU_WIN32_H

#ifndef _WINDOWS_
void __stdcall InitializeSRWLock(void** lock);
void __stdcall AcquireSRWLockExclusive(void** lock);
void __stdcall ReleaseSRWLockExclusive(void** lock);
void __stdcall InitializeConditionVariable(void** condition);
int __stdcall SleepConditionVariableSRW(void** condition, void** lock, unsigned long milliseconds, unsigned long flags);
void __stdcall WakeConditionVariable(void** condition);
#endif

#endif
