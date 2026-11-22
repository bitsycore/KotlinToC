#!/bin/bash
# Builds each box test whose path matches the regex $1 (all without one) as a program with the box helpers and a main that
# prints box(), with the installed tool in a debug build (leak check), and prints what each gives.
cd /c/Dev/KotlinToC
for vFile in $(find tests/box -name "*.kt" -not -path "*/helpers/*" | grep -E "${1:-}" | sort); do
	vName=$(basename "$vFile" .kt)
	vDir=build/boxp/$(echo "$vFile" | sed 's|tests/box/||; s|/|_|g; s|\.kt$||')
	rm -rf "$vDir"; mkdir -p "$vDir/src"
	cp tests/box/helpers/*.kt "$vDir/src/"
	sed 's/OPTIONAL_JVM_INLINE_ANNOTATION/@kotlin.jvm.JvmInline/' "$vFile" > "$vDir/src/$vName.kt"
	vPackage=$(grep -m1 "^package " "$vDir/src/$vName.kt" | sed 's/package //')
	printf 'fun main() { println(%sbox()) }\n' "${vPackage:+$vPackage.}" > "$vDir/src/BoxMain.kt"
	vOut=$(KTC2_CYCLES=1 timeout 300 build/install/ktc2/bin/ktc2 "$vDir/src" -o "$vDir/out" --debug --run -q 2>&1 | grep -v "^\s*$" | head -${2:-6})
	echo "== $vFile"
	echo "$vOut" | cut -c1-300
done
