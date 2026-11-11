@file:OptIn(ExperimentalForeignApi::class)

package interop

import geometry.*
import kotlinx.cinterop.*

class Tally {
	var visited = 0
	var total = 0
}

fun visitPoint(inPoint: CPointer<point>?, inUserData: COpaquePointer?): Int {
	val vTally = inUserData!!.asStableRef<Tally>().get()
	val vPoint = inPoint!!.pointed
	vTally.visited++
	vTally.total += vPoint.x * vPoint.y
	return vPoint.x
}

fun main() {
	// Macros and enum constants are plain Kotlin constants.
	println("$GEOMETRY_NAME v$GEOMETRY_VERSION, up to $GEOMETRY_MAX_POINTS points, epsilon $GEOMETRY_EPSILON")
	println(GEOMETRY_FLAG_CLOSED + 1u)
	println("$SHAPE_POINT $SHAPE_SEGMENT $SHAPE_POLYGON $SHAPE_UNKNOWN")
	val vKind: shape_kind = SHAPE_POLYGON
	println(geometry_kind_name(vKind)?.toKString())
	println(geometry_kind_name(SHAPE_UNKNOWN)?.toKString())

	memScoped {
		// A struct with nested structs, a string and a small integer.
		val vRect = alloc<rect_t>()
		vRect.size.x = 6
		vRect.size.y = 7
		vRect.flags = 5u
		println(geometry_area(vRect.ptr))
		geometry_move(vRect.origin.ptr, 3, -4)
		geometry_move(vRect.origin.ptr, 1, 1)
		println("${vRect.origin.x},${vRect.origin.y} flags=${vRect.flags}")
		println(vRect.label == null)
		println(sizeOf<rect_t>() >= 24)

		// Strings go to C as they are.
		println(geometry_label_length("corner"))
		println(geometry_label_length(null))

		// A Kotlin array read by C.
		val vValues = intArrayOf(1, 2, 3, 40)
		println(geometry_sum(vValues.refTo(0), 4u))
		println(geometry_sum(vValues.refTo(2), 2u))

		// Arrays inside a struct, a pointer to the same struct, and a callback with user data.
		val vPolygon = alloc<polygon_t>()
		vPolygon.kind = SHAPE_POLYGON
		vPolygon.count = 3u
		for (vIndex in 0 until 3) {
			vPolygon.points[vIndex].x = vIndex + 1
			vPolygon.points[vIndex].y = 10
		}
		vPolygon.weights[1] = 0.5
		vPolygon.next = vPolygon.ptr
		println(vPolygon.next?.pointed?.count)
		println(vPolygon.weights[0] + vPolygon.weights[1])
		val vTally = Tally()
		val vRef = StableRef.create(vTally)
		println(geometry_visit(vPolygon.ptr, staticCFunction(::visitPoint), vRef.asCPointer()))
		vRef.dispose()
		println("${vTally.visited} ${vTally.total}")
		// A lambda that uses nothing around it is a C function too.
		println(geometry_visit(vPolygon.ptr, staticCFunction { inPoint: CPointer<point>?, _: COpaquePointer? -> inPoint!!.pointed.y }, null))

		// A variadic function writing into C memory.
		val vBuffer = allocArray<ByteVar>(64)
		val vWritten = geometry_format(vBuffer, 64u, "%s=%d (%.2f) %c%lld", "answer", 42, 1.5, 'x'.code, 5000000000L)
		println("${vBuffer.toKString()} [$vWritten]")
	}

	// Structs passed and returned by value.
	val vFirst = geometry_point(3, 4)
	val vSecond = cValue<point> {
		x = 10
		y = 20
	}
	val vSum = geometry_add(vFirst, vSecond)
	println(vSum.useContents { "$x,$y" })
	val vRect = geometry_rect(vSum, geometry_point(2, 5), "box")
	println(vRect.useContents { "${origin.x},${origin.y} ${size.x}x${size.y} ${label?.toKString()}" })
	println(geometry_area(vRect))
	val vMoved = vSum.copy { x = -1 }
	println("${vMoved.useContents { x }} ${vSum.useContents { x }}")
	memScoped {
		val vPlace = alloc<point>()
		vPlace.x = 7
		vPlace.y = 8
		val vSnapshot = vPlace.readValue()
		vPlace.x = 0
		println(vSnapshot.useContents { x + y })
		vFirst.place(vPlace.ptr)
		println("${vPlace.x},${vPlace.y}")
	}

	// A C function called through its pointer, values passed by address, and a C variable.
	val vScale = geometry_scaler()!!
	println(vScale(4.0, 0.5f))
	println(geometry_sum(cValuesOf(7, 8, 9), 3u))
	geometry_counter = 5
	geometry_counter += 2
	println(geometry_counter)

	// A type known only by name, and a function of the C library itself.
	val vCanvas = geometry_canvas_new(4, 4)
	println(geometry_canvas_plot(vCanvas, 1, 1) + geometry_canvas_plot(vCanvas, 9, 9) + geometry_canvas_plot(vCanvas, 3, 0))
	println(geometry_canvas_count(vCanvas))
	geometry_canvas_free(vCanvas)
	println(geometry_scale(2.0, 1.5f))
	println(abs(-17) + atoi("25"))
	println("done")
}
