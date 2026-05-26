package test

import core.*
import shapes.*
import actors.*
import effects.*

fun checkDrawable(d: Drawable, expectedId: Int, expectedName: String) {
	val id = d.draw()
	val n = d.name()
	if (id != expectedId) error("FAIL draw: expected $expectedId got $id for $expectedName")
	if (n != expectedName) error("FAIL name: expected $expectedName got $n")
}

fun checkTickable(t: Tickable, dt: Float, expected: Float) {
	val result = t.tick(dt)
	if (result != expected) error("FAIL tick: expected $expected got $result")
}

fun sumDrawIds(a: Drawable, b: Drawable, c: Drawable): Int = a.draw() + b.draw() + c.draw()

fun tickAll(a: Tickable, b: Tickable, dt: Float): Float = a.tick(dt) + b.tick(dt)

fun main() {
	// --- By-value interface from different packages ---
	val sprite = Sprite(42, 1.0f, 2.0f)
	val circle = Circle(3.0f, 0.0f, 0.0f)
	val rect   = Rect(4.0f, 5.0f, 0.0f, 0.0f)
	val player = Player(50, "Hero", 10.0f)
	val enemy  = Enemy(7, 3.5f)
	val flash  = Flash(0.8, 255.toByte(), 0.toByte(), 128.toByte())
	val trail  = Trail(10, 3, 0.9f)

	// Test interface dispatch — Drawable from 5 packages
	checkDrawable(sprite, 42, "Sprite")
	checkDrawable(circle, 1, "Circle")
	checkDrawable(rect,   2, "Rect")
	checkDrawable(player, 150, "Hero")
	checkDrawable(enemy,  207, "Enemy")
	checkDrawable(flash,  204, "Flash")
	checkDrawable(trail,  30, "Trail")
	println("Drawable dispatch OK")

	// Test Tickable interface dispatch
	checkTickable(rect,   1.0f, 20.0f)
	checkTickable(player, 0.5f, 5.0f)
	checkTickable(enemy,  2.0f, 7.0f)
	checkTickable(trail,  0.0f, 0.9f)
	println("Tickable dispatch OK")

	// Test passing interface values across function calls
	val sum = sumDrawIds(sprite, circle, enemy)
	if (sum != 250) error("FAIL sumDrawIds: expected 250 got $sum")

	val tSum = tickAll(rect, player, 1.0f)
	if (tSum != 30.0f) error("FAIL tickAll: expected 30.0 got $tSum")
	println("Interface function args OK")

	// Test allocWith across packages (Allocator in ktc, Heap in ktc)
	val pCircle: @Ptr Circle = Circle(5.0f, 1.0f, 2.0f).allocWith(Heap)
	defer Heap.freeMem(pCircle)
	if (pCircle.radius != 5.0f) error("FAIL pCircle.radius")

	val pPlayer: @Ptr Player = Player(100, "Alloc", 20.0f).allocWith(Heap)
	defer Heap.freeMem(pPlayer)
	if (pPlayer.hp != 100) error("FAIL pPlayer.hp")
	println("AllocWith cross-pkg OK")

	// Test Arena allocator (cross-package: Arena in ktc.std, Allocator in ktc)
	val arenaBuf: @Ptr RawArray<Byte> = RawArray<Byte>(512).allocWith(Heap)
	defer Heap.freeMem(arenaBuf)
	val arena = Arena(arenaBuf, 512)
	val pEnemy: @Ptr Enemy = Enemy(99, 1.5f).allocWith(arena)
	if (pEnemy.kind != 99) error("FAIL pEnemy.kind")
	if (pEnemy.dmg != 1.5f) error("FAIL pEnemy.dmg")

	val pFlash: @Ptr Flash = Flash(0.5, 10.toByte(), 20.toByte(), 30.toByte()).allocWith(arena)
	if (pFlash.r != 10.toByte()) error("FAIL pFlash.r")
	arena.dispose()
	println("Arena cross-pkg alloc OK")

	// Test Particle (only Tickable, not Drawable — different interface combos)
	val particle = Particle(10.0f, 1.0f, 2.0f, 4.toByte())
	checkTickable(particle, 0.5f, 9.5f)
	println("Particle Tickable OK")

	println("All cross-package tests passed!")
}
