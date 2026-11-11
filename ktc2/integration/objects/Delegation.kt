package objects

import kotlin.properties.Delegates
import kotlin.reflect.KProperty

class Config {
	val expensive: String by lazy {
		println("computing")
		"value"
	}
	var watched: Int by Delegates.observable(0) { _, vOld, vNew -> println("$vOld -> $vNew") }
}

class Upper {
	private var mText = ""
	operator fun getValue(thisRef: Any?, property: KProperty<*>): String = mText.uppercase()
	operator fun setValue(thisRef: Any?, property: KProperty<*>, value: String) { mText = value }
}

val gTable: List<Int> by lazy { listOf(1, 2, 3) }

class Holder { var name: String by Upper() }

fun main() {
	val vConfig = Config()
	println("before")
	println(vConfig.expensive)
	println(vConfig.expensive)
	vConfig.watched = 5
	vConfig.watched = 7
	println(vConfig.watched)
	println(gTable.sum())
	val vLocal by lazy { 40 + 2 }
	println(vLocal)
	val vHolder = Holder()
	vHolder.name = "abc"
	println(vHolder.name)
	lateinit var vLate: String
	vLate = "late"
	println(vLate)
}
