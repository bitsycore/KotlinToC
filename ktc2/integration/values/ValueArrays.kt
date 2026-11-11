package values

import ktc2.Struct
import ktc2.ValueArray

@Struct
data class Pixel(val red: Int, val green: Int, val blue: Int) {
	fun brightness(): Int = (red + green + blue) / 3

	fun dimmed(): Pixel = Pixel(red / 2, green / 2, blue / 2)
}

value class Celsius(val degrees: Double)

class Sensor(val name: String) {
	var reading = 0
}

fun brightest(inPixels: ValueArray<Pixel>): Pixel {
	var vBest = inPixels[0]
	for (vPixel in inPixels) if (vPixel.brightness() > vBest.brightness()) vBest = vPixel
	return vBest
}

fun main() {
	// Structs side by side.
	val vImage = ValueArray(16) { Pixel(it * 10, it * 5, 255 - it) }
	println(vImage.size)
	println(vImage[3])
	vImage[3] = vImage[3].dimmed()
	println(vImage[3])
	println(brightest(vImage))
	var vTotal = 0
	for (vIndex in vImage.indices) vTotal += vImage[vIndex].brightness()
	println(vTotal)
	println(vImage.map { it.red }.take(4))
	println(vImage.count { it.blue > 250 })
	println(vImage.contains(Pixel(0, 0, 255)))

	// Numbers and value classes.
	val vCounts = ValueArray(5) { it * it }
	vCounts[4] = -1
	println(vCounts.toList())
	val vTemperatures = ValueArray(3) { Celsius(20.0 + it) }
	println(vTemperatures.sumOf { it.degrees })
	println(vTemperatures[2])

	// Ordinary objects are held by reference, as in any array.
	val vSensors = ValueArray(2) { Sensor("s$it") }
	vSensors[1].reading = 7
	val vSame = vSensors[1]
	vSame.reading++
	println("${vSensors[1].name} ${vSensors[1].reading}")
	vSensors[0] = vSame
	println(vSensors[0] === vSensors[1])
	try {
		println(vImage[16])
	} catch (vError: IndexOutOfBoundsException) {
		println("out of bounds")
	}
	println("done")
}
