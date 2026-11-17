import kotlin.properties.Delegates
import kotlin.properties.ReadOnlyProperty
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

// Delegated properties as ordinary code writes them: properties read from a map and written to a
// mutable one, `lazy`, `observable` and `vetoable`, `notNull`, delegates of the program's own,
// delegation to another property, local delegated variables, and `provideDelegate`.

class Config(map: Map<String, Any?>) {
	val host: String by map
	val port: Int by map
	val debug: Boolean by map
	val tags: List<String> by map
}

class MutableConfig(val values: MutableMap<String, Any?>) {
	var name: String by values
	var retries: Int by values
}

class Trimmed : ReadWriteProperty<Any?, String> {
	private var value = ""
	override fun getValue(thisRef: Any?, property: KProperty<*>) = value
	override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) {
		this.value = value.trim()
	}
}

class Counted<T>(private val initial: T) {
	var reads = 0
	operator fun getValue(thisRef: Any?, property: KProperty<*>): T {
		reads++
		return initial
	}
}

class Logged(private val log: MutableList<String>) {
	operator fun provideDelegate(thisRef: Any?, property: KProperty<*>): ReadOnlyProperty<Any?, String> {
		log += "provide ${property.name}"
		return ReadOnlyProperty { _, p -> "value of ${p.name}" }
	}
}

class Form(log: MutableList<String>) {
	var title by Trimmed()
	val counted = Counted(5)
	val five by counted
	val first by Logged(log)
	val second by Logged(log)
	var changes = mutableListOf<String>()
	var status: String by Delegates.observable("new") { property, old, new -> changes += "${property.name}: $old -> $new" }
	var age: Int by Delegates.vetoable(0) { _, _, new -> new >= 0 }
	var late: String by Delegates.notNull()
	val expensive by lazy {
		changes += "computed"
		title.length * 2
	}
	var alias by this::status
}

class Settings {
	private val store = HashMap<String, Int>()
	var width: Int
		get() = store["width"] ?: 0
		set(value) {
			store["width"] = value
		}
	val area by lazy(LazyThreadSafetyMode.NONE) { width * width }
}

fun main() {
	val config = Config(mapOf("host" to "localhost", "port" to 8080, "debug" to true, "tags" to listOf("a", "b")))
	println("${config.host}:${config.port} ${config.debug} ${config.tags}")
	val broken = Config(mapOf("host" to "h"))
	println(runCatching { broken.port }.exceptionOrNull()?.message)

	val values = mutableMapOf<String, Any?>("name" to "first", "retries" to 1)
	val mutable = MutableConfig(values)
	mutable.name = "second"
	mutable.retries += 2
	println("${mutable.name} ${mutable.retries} $values")

	val log = mutableListOf<String>()
	val form = Form(log)
	println(log)
	form.title = "   padded   "
	println("[${form.title}]")
	println(form.five + form.five)
	println(form.counted.reads)
	println(form.first + ", " + form.second)
	form.status = "open"
	form.status = "closed"
	form.alias = "reopened"
	println(form.status + " " + form.changes)
	form.age = 30
	form.age = -1
	println(form.age)
	println(runCatching { form.late }.exceptionOrNull()?.message)
	form.late = "set"
	println(form.late)
	println(form.expensive + form.expensive)
	println(form.changes.count { it == "computed" })

	val settings = Settings()
	settings.width = 7
	println(settings.area)
	settings.width = 9
	println(settings.area)

	val local by lazy { "local lazy" }
	println(local)
	var observed by Delegates.observable(0) { _, old, new -> println("local $old -> $new") }
	observed = 3
	observed += 1
	val fromMap: String by mapOf("fromMap" to "found in map")
	println(fromMap)
}
