// Functions called from C that take and return structs by value.

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

import callbacks.*
import kotlinx.cinterop.*

class Collected {
	val seen = ArrayList<String>()
}

fun swapped(inValue: CValue<vec>): CValue<vec> = inValue.useContents {
	val vX = x
	val vY = y
	cValue<vec> {
		x = vY
		y = vX
	}
}

fun main() {
	// A lambda: struct in, struct out.
	val vDoubled = map_all(staticCFunction { vValue: CValue<vec> ->
		vValue.copy {
			x *= 2
			y *= 2
		}
	}, 4)
	println(vDoubled.useContents { "$x,$y" })

	// A top-level function, by reference.
	println(map_all(staticCFunction(::swapped), 3).useContents { "$x,$y" })

	// A struct and a number in, a number out.
	val vPoint = cValue<vec> {
		x = 3
		y = 4
	}
	println(weigh(staticCFunction { vValue: CValue<vec>, vScale: Int -> vValue.useContents { (x + y) * vScale } }, vPoint, 10))

	// Two structs in, a larger struct out, with a struct inside.
	val vSpan = make_span(staticCFunction { vFrom: CValue<vec>, vTo: CValue<vec> ->
		val vLeft = vFrom.useContents { x.toDouble() }
		val vRight = vTo.useContents { x.toDouble() }
		val vDown = vTo.useContents { y } - vFrom.useContents { y }
		cValue<span_t> {
			left = vLeft
			right = vRight + 0.5
			offset.x = (vRight - vLeft).toInt()
			offset.y = vDown
		}
	}, 6)
	println(vSpan.useContents { "$left $right ${offset.x} ${offset.y}" })

	// A struct in, nothing out, with something of the program's passed through C.
	val vCollected = Collected()
	val vRef = StableRef.create(vCollected)
	feed(staticCFunction { vValue: CValue<vec>, vData: COpaquePointer? ->
		val vTarget = vData!!.asStableRef<Collected>().get()
		vTarget.seen += vValue.useContents { "$x/$y" }
	}, vRef.asCPointer(), 4)
	vRef.dispose()
	println(vCollected.seen)
}
