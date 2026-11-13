#!/bin/bash
# Usage: tools/native.sh [part of a path]
# Runs the test programs that are plain Kotlin (no `ktc2.` package, no C library, no expected
# file) with Kotlin/Native, and compares what each prints with what its C program printed in
# the last run of the suite, which is what the JVM printed. A program is meant to behave the
# same on the JVM, on Kotlin/Native and here.
# Needs a Kotlin/Native compiler: KTC2_KONAN names its directory, else the newest one under
# ~/.konan is used (Gradle puts them there for multiplatform projects). About 10 seconds a
# program, four at a time. The common tests (tests/*.kt) are run there too, with the `main`
# the suite wrote for each. The report is build/native/report.txt.
cd "$(dirname "$0")/.." || exit 1
vKonan=${KTC2_KONAN:-$(ls -d ~/.konan/kotlin-native-prebuilt-* 2>/dev/null | sort -V | tail -1)}
[ -x "$vKonan/bin/kotlinc-native" ] || { echo "no Kotlin/Native compiler: set KTC2_KONAN"; exit 1; }
mkdir -p build/native
rm -f build/native/*.result
one() {
	vFile=$1
	vName=$(basename "$vFile" .kt)
	vBase=${vFile%.kt}
	vOut=build/native/$vName
	vC=build/integration/$vName/program.exe
	[ -x "$vC" ] || { echo "skipped (no C program: run the suite first) $vFile" > "$vOut.result"; return; }
	# A program in a package has its `main` there.
	vPackage=$(grep -m1 "^package " "$vFile" | sed 's/^package *//; s/[; ]*$//')
	if ! "$2/bin/kotlinc-native" "$vFile" -o "$vOut" -e "${vPackage:+$vPackage.}main" > "$vOut.log" 2>&1; then
		echo "does not compile $vFile: $(grep -m1 "error:" "$vOut.log" | sed 's/^.*error: //' | cut -c1-150)" > "$vOut.result"
		return
	fi
	vInput=/dev/null
	[ -f "$vBase.in" ] && vInput=$vBase.in
	"$vOut.kexe" < "$vInput" > "$vOut.native.txt" 2>&1
	"$vC" < "$vInput" 2>/dev/null > "$vOut.c.txt"
	if cmp -s "$vOut.native.txt" "$vOut.c.txt"; then
		echo "same $vFile" > "$vOut.result"
	else
		echo "differs $vFile: $(diff "$vOut.c.txt" "$vOut.native.txt" | grep -c '^[<>]') lines (diff $vOut.c.txt $vOut.native.txt)" > "$vOut.result"
	fi
	rm -f "$vOut.kexe"
}
# A file of common tests (tests/*.kt): with the `main` the suite wrote for it, and Kotlin/Native's own kotlin.test.
common() {
	vFile=$1
	vName=$(basename "$vFile" .kt)
	vOut=build/native/test-$vName
	vMain=build/testmain/${vName}Main.kt
	vC=build/integration/test-$vName/program.exe
	[ -x "$vC" ] && [ -f "$vMain" ] || { echo "skipped (run the suite first) $vFile" > "$vOut.result"; return; }
	if ! "$2/bin/kotlinc-native" "$vFile" "$vMain" -o "$vOut" > "$vOut.log" 2>&1; then
		echo "does not compile $vFile: $(grep -m1 "error:" "$vOut.log" | sed 's/^.*error: //' | cut -c1-150)" > "$vOut.result"
		return
	fi
	"$vOut.kexe" > "$vOut.native.txt" 2>&1
	"$vC" 2>/dev/null > "$vOut.c.txt"
	if grep -q "^FAILED" "$vOut.native.txt"; then
		echo "differs $vFile: $(grep -c "^FAILED" "$vOut.native.txt") tests fail on Kotlin/Native: $(grep -m3 "^FAILED" "$vOut.native.txt" | cut -c1-120 | tr '\n' ';')" > "$vOut.result"
	elif cmp -s "$vOut.native.txt" "$vOut.c.txt"; then
		echo "same $vFile ($(grep -c "^ok" "$vOut.native.txt") tests)" > "$vOut.result"
	else
		echo "differs $vFile: (diff $vOut.c.txt $vOut.native.txt)" > "$vOut.result"
	fi
	rm -f "$vOut.kexe"
}
export -f one common
vFiles=$(for vFile in integration/basic/*.kt integration/objects/*.kt integration/generics/*.kt integration/stdlib/*.kt integration/ported/*.kt; do
	vBase=${vFile%.kt}
	[ -f "$vBase.expected" ] || [ -f "$vBase.def" ] || [ -f "$vBase.errors" ] && continue
	grep -q "^import ktc2\.\|kotlinx.cinterop\|platform.posix" "$vFile" && continue
	[[ "$vFile" == *"$1"* ]] && echo "$vFile"
done)
echo "$vFiles" | xargs -P 4 -I{} bash -c 'one "$1" "$2"' _ {} "$vKonan"
for vFile in tests/*.kt tests/kotlin/*.kt; do [[ "$vFile" == *"$1"* ]] && echo "$vFile"; done | xargs -P 4 -I{} bash -c 'common "$1" "$2"' _ {} "$vKonan"
cat build/native/*.result | sort > build/native/report.txt
echo "Kotlin/Native $(basename "$vKonan"): $(grep -c "^same" build/native/report.txt) the same, $(grep -c "^differs" build/native/report.txt) differ, $(grep -c "^does not compile" build/native/report.txt) do not compile there, $(grep -c "^skipped" build/native/report.txt) skipped"
grep -v "^same" build/native/report.txt
