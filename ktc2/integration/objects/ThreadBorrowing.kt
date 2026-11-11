// A reference read from a field must stay alive when another thread replaces the field:
// read before the thread starts, or read under the lock the other thread also takes.

import kotlin.concurrent.thread

class Note(val text: String)

class Board(var note: Note) {
	val seen = ArrayList<Note>()
}

fun firstOf(inBoard: Board): Note = inBoard.seen[0]

fun main() {
	// Read, then a thread replaces the field and ends.
	val vBoard = Board(Note("first"))
	val vBefore = vBoard.note
	thread { vBoard.note = Note("second") }.join()
	println(vBefore.text + " then " + vBoard.note.text)

	// Read under the lock, used after it, while the other thread replaces under the same lock.
	val vLock = Note("lock")
	val vUnderLock = synchronized(vLock) { vBoard.note }
	thread { synchronized(vLock) { vBoard.note = Note("third") } }.join()
	println(vUnderLock.text + " then " + vBoard.note.text)

	// An element handed out by a list another thread then empties.
	vBoard.seen.add(Note("kept"))
	val vElement = firstOf(vBoard)
	val vSame = vBoard.seen[0]
	val vWorker = thread { synchronized(vLock) { vBoard.seen.clear() } }
	vWorker.join()
	println(vElement.text + " " + vSame.text + " " + vBoard.seen.size)

	// Many rounds of the same, for the counts to be right under load.
	var vTotal = 0
	for (vRound in 0 until 200) {
		val vHeld = synchronized(vLock) { vBoard.note }
		val vOther = thread { synchronized(vLock) { vBoard.note = Note("round $vRound") } }
		vTotal += vHeld.text.length
		vOther.join()
		vTotal += vHeld.text.length
	}
	println(vTotal)
}
