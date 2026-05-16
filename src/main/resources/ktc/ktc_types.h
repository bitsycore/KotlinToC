#ifndef KTC_TYPES_H
#define KTC_TYPES_H

#include "ktc_mangle.h"


/* ========================================================= */
/* Optional type definition                                   */
/* ========================================================= */

/* T? — requires ktc_OptionalTag to be declared (from ktc_core.h) */
#define KTC_DEFINE_OPT(T)                 \
	typedef struct KTC_OPT_TYPE(T) {  \
		ktc_OptionalTag tag;              \
		T value;                          \
	} KTC_OPT_TYPE(T)


/* ========================================================= */
/* Fixed array definition                                     */
/* ========================================================= */

/* @Size(N) Array<T> */
#define KTC_DEFINE_ARRAY(T, N)        \
	typedef struct KTC_ARRAY_TYPE(T, N) { \
		T arr[N];                     \
	} KTC_ARRAY_TYPE(T, N)


/* ========================================================= */
/* Optional fixed array definition                            */
/* ========================================================= */

/* @Size(N) Array<T>? — KTC_DEFINE_ARRAY(T, N) must come first */
#define KTC_DEFINE_OPT_ARRAY(T, N)                 \
	typedef struct KTC_OPT_ARRAY_TYPE(T, N) {   \
		ktc_OptionalTag tag;                       \
		KTC_ARRAY_TYPE(T, N) value;                \
	} KTC_OPT_ARRAY_TYPE(T, N)


#endif
