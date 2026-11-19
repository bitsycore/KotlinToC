// removeFirst and removeLast called on an ArrayList itself (not through MutableList): the JDK's ArrayList has them as
// members since Java 21, ktc2's ArrayList had no counterpart, and Kotlin's compiler stopped inside ktc2
// ("No override for ... removeFirst").

package ktc2test.regressions.arraylistremovefirst

import kotlin.test.Test

@Test
fun main() {
	val vList = ArrayList(listOf(1, 2, 3, 4))
	println(vList.removeFirst())
	println(vList.removeLast())
	println(vList)
	val vNames = arrayListOf("a", "b")
	println(vNames.removeFirstOrNull() + vNames.removeLastOrNull())
	println(vNames.isEmpty())
}
