#ifndef GEOMETRY_H
#define GEOMETRY_H

#include <stddef.h>
#include <stdint.h>

#define GEOMETRY_VERSION 3
#define GEOMETRY_NAME "geometry"
#define GEOMETRY_EPSILON 0.001
#define GEOMETRY_MAX_POINTS (1 << 4)
#define GEOMETRY_FLAG_CLOSED 0x01u

typedef enum shape_kind {
	SHAPE_POINT,
	SHAPE_SEGMENT,
	SHAPE_POLYGON = 10,
	SHAPE_UNKNOWN = -1
} shape_kind;

struct point {
	int32_t x;
	int32_t y;
};

typedef struct rect {
	struct point origin;
	struct point size;
	const char* label;
	uint8_t flags;
} rect_t;

typedef struct polygon {
	shape_kind kind;
	size_t count;
	struct point points[GEOMETRY_MAX_POINTS];
	struct polygon* next;
	double weights[2];
} polygon_t;

/* Known only by name: used through pointers. */
typedef struct canvas canvas_t;

typedef int (*point_visitor)(const struct point* point, void* user_data);

typedef double (*scaler)(double value, float factor);

/* A variable of the library. */
extern int geometry_counter;

/*
A type with a private name declared before its public one, a variable of that type, and a
macro of the name of the variable: what the GNU C library does with FILE and stdout.
*/
struct _geometry_log;
typedef struct _geometry_log __geometry_log;
typedef struct _geometry_log geometry_log;
struct _geometry_log {
	int lines;
};
extern geometry_log* geometry_main_log;
#define geometry_main_log geometry_main_log

/* The functions are defined in Geometry.def, as a library with inline functions would. */

#endif
