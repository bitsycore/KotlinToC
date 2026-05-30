package TemplateEvalTest

// Regression test for B8: a side-effecting interpolation `${f()}` must run f() exactly once.
// Before the fix the two-pass (count+fill) template lowering emitted each append twice, and the
// nullable append embedded the value twice (tag check + value), so f() ran 2–4×.
// main returns a non-zero exit code if any check fails.

var counter = 0

fun nextInt(): Int { counter = counter + 1; return counter }

fun nextOpt(): Int? { counter = counter + 1; return counter }

fun nextStr(): String { counter = counter + 1; return "s" }

fun main(): Int {
	// Nullable interpolation in a String template (nullable double-embed path).
	counter = 0
	val a = "x=${nextOpt()}"
	if (counter != 1) { println("FAIL nullable-template: $counter"); return 1 }

	// Two nullable interpolations (forces the two-pass count/fill fallback).
	counter = 0
	val b = "p=${nextOpt()} q=${nextOpt()}"
	if (counter != 2) { println("FAIL two-pass: $counter"); return 2 }

	// println template path (uses StrBuf because of the nullable part).
	counter = 0
	println("r=${nextOpt()}")
	if (counter != 1) { println("FAIL println-template: $counter"); return 3 }

	// Non-null Int interpolation (single-pass computed-size path).
	counter = 0
	val c = "n=${nextInt()}"
	if (counter != 1) { println("FAIL int-template: $counter"); return 4 }

	// String interpolation (computed-size path with runtime .len).
	counter = 0
	val d = "v=${nextStr()}!"
	if (counter != 1) { println("FAIL str-template: $counter"); return 5 }

	// Sanity-check the produced text too.
	if (a != "x=1") { println("FAIL text a=$a"); return 6 }
	if (b != "p=1 q=2") { println("FAIL text b=$b"); return 7 }

	println("TemplateEvalTest OK ($a | $b | $c | $d)")
	return 0
}
