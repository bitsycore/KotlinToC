package values

import ktc2.Struct

class Account(val owner: String) {
	var balance = 0

	override fun toString(): String = "Account($owner)"
}

/** A value with references in it: a name, an object, and a nested value. */
@Struct
data class Tag(val name: String, val weight: Int)

@Struct
data class Entry(val key: String, val account: Account, val tag: Tag) {
	fun describe(): String = "$key:${account.owner}:${tag.name}"

	fun renamed(inKey: String): Entry = Entry(inKey, account, tag)
}

value class Email(val address: String) {
	fun domain(): String = address.substringAfter('@')
}

value class Handle(val account: Account) {
	fun deposit(inAmount: Int): Handle {
		account.balance += inAmount
		return this
	}
}

class Registry {
	var current: Entry = Entry("none", Account("nobody"), Tag("empty", 0))
	val history = ArrayList<Entry>()

	fun replace(inEntry: Entry): Entry {
		val vOld = current
		history.add(vOld)
		current = inEntry
		return vOld
	}
}

fun makeEntry(inIndex: Int): Entry = Entry("key$inIndex", Account("owner$inIndex"), Tag("tag$inIndex", inIndex))

fun longestKey(inEntries: List<Entry>): Entry {
	var vBest = inEntries[0]
	for (vEntry in inEntries) if (vEntry.key.length > vBest.key.length) vBest = vEntry
	return vBest
}

fun pickEntry(inFirst: Entry, inSecond: Entry, inUseFirst: Boolean): Entry = if (inUseFirst) inFirst else inSecond

fun main() {
	val vTag = Tag("blue", 3)
	val vAccount = Account("ann")
	val vEntry = Entry("first", vAccount, vTag)
	println(vEntry)
	println(vEntry.describe())
	println(vEntry == Entry("first", vAccount, Tag("blue", 3)))
	println(vEntry.tag == vTag)

	// Copies share what they refer to; each keeps it alive on its own.
	var vCopy = vEntry
	vCopy = vCopy.renamed("second")
	vCopy.account.balance += 10
	println("${vEntry.account.balance} ${vCopy.key} ${vEntry.key}")
	val (vKey, vOwner, vInner) = vCopy
	println("$vKey ${vOwner.owner} ${vInner.weight}")

	// Built in a loop, returned, selected, overwritten.
	var vLast = makeEntry(0)
	for (vIndex in 1..5) {
		val vNext = makeEntry(vIndex)
		vLast = pickEntry(vLast, vNext, vIndex % 2 == 0)
	}
	println(vLast.describe())

	// Stored in an object, replaced, collected.
	val vRegistry = Registry()
	println(vRegistry.replace(vEntry).key)
	println(vRegistry.replace(makeEntry(42)).key)
	println(vRegistry.current.describe())
	println(vRegistry.history.map { it.key })
	val vEntries = listOf(makeEntry(1), makeEntry(100), vEntry)
	println(longestKey(vEntries).describe())
	println(vEntries.filter { it.tag.weight > 0 }.size)
	val vByKey = HashMap<String, Entry>()
	for (vItem in vEntries) vByKey[vItem.key] = vItem
	println(vByKey["key100"]?.account?.owner)
	println(vByKey["missing"])
	val vAny: Any = vEntry
	println(vAny is Entry)
	println((vAny as Entry).tag.name)
	val vMaybe: Entry? = if (vEntries.size > 5) vEntry else null
	println(vMaybe?.key)

	// Value classes that wrap a reference are values too.
	val vMail = Email("ann@example.org")
	println(vMail.domain())
	println(vMail == Email("ann@example.org"))
	println(listOf(vMail, Email("bob@test.net")).map { it.domain() })
	val vHandle = Handle(vAccount).deposit(5).deposit(7)
	println(vHandle.account.balance)
	println(vHandle)
	println("done")
}
