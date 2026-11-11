package shapes

import kotlin.math.roundToInt

// ==================
// MARK: Transforms
// ==================

/** What can be done to a shape. */
sealed class Transform {
	data class Scale(val factor: Double) : Transform()

	data class Grow(val amount: Double) : Transform()

	object Identity : Transform()
}

/** This shape after [transform]; the kind of shape stays what it was. */
fun Shape.transformed(transform: Transform): Shape = when (transform) {
	is Transform.Identity -> this
	is Transform.Scale -> resized { it * transform.factor }
	is Transform.Grow -> resized { it + transform.amount }
}

private fun Shape.resized(change: (Double) -> Double): Shape = when (this) {
	is Circle -> Circle(change(radius))
	is Square -> Square(change(width))
	is Rectangle -> Rectangle(change(width), change(height))
	is Triangle -> Triangle(change(a), change(b), change(c))
	else -> this
}

/** [value] with two digits after the point. */
fun rounded(value: Double): Double = (value * 100.0).roundToInt() / 100.0

/** One line about [shape]: what it is, its surface and its outline. */
fun describe(shape: Shape): String = "${shape.name}: area ${rounded(shape.area())}, perimeter ${rounded(shape.perimeter())}"

/** The shapes of [shapes] by name, each name with the sum of the areas of its shapes. */
fun areasByName(shapes: List<Shape>): Map<String, Double> {
	val vAreas = LinkedHashMap<String, Double>()
	for (vShape in shapes) vAreas[vShape.name] = rounded((vAreas[vShape.name] ?: 0.0) + vShape.area())
	return vAreas
}
