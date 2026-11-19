#!/bin/bash
# Usage: tools/msvc_all.sh [part of a name]
# After a run of the suite on Windows: compiles the generated C of every program under
# build/integration with MSVC, as one file (ktc2_all.c) and the runtime (ktc2_core.c), runs it, and compares what it prints
# with what the program the suite built (gcc) prints. Fails when one does not compile, does not
# end as the other does, or prints something else.
# Needs `cl` on the path: a "Developer Command Prompt", or after vcvars64.bat, as in
#   cmd //c "call \"<Visual Studio>/VC/Auxiliary/Build/vcvars64.bat\" && bash tools/msvc_all.sh"
# Where the programs are not there, only their C (a CI job given what the suite's job generated),
# what each printed is in its program.out, written by the suite; a file of tests the suite did not
# compare with the JVM (`self-checking`) has to end well, its own assertions deciding. KTC2_SHARD=i/n takes the i-th
# of n shares of the programs (from 0), for several machines at once.
cd "$(dirname "$0")/.." || exit 1
command -v cl > /dev/null || { echo "cl is not on the path"; exit 1; }
echo "$(ls build/integration | wc -l) entries under build/integration, $(ls build/integration/*/program.exe 2>/dev/null | wc -l) with a program, $(ls build/integration/*/c/ktc2_all.c 2>/dev/null | wc -l) with the C as one file"
vFailed=0
vCount=0
vKnown=0
vNames=
vSkipped=0
for vDir in build/integration/*/; do
	vDir=${vDir%/}
	vName=$(basename "$vDir")
	case "$vName" in *"$1"*) ;; *) continue ;; esac
	vIndex=$((${vIndex:--1} + 1))
	if [ -n "$KTC2_SHARD" ] && [ $((vIndex % ${KTC2_SHARD#*/})) -ne "${KTC2_SHARD%/*}" ]; then continue; fi
	if [ ! -f "$vDir/c/ktc2_all.c" ] || { [ ! -f "$vDir/program.exe" ] && [ ! -f "$vDir/program.out" ] && [ ! -f "$vDir/self-checking" ]; }; then
		# Said, not passed over in silence: a run that compares nothing must not look like one that found nothing.
		vSkipped=$((vSkipped + 1)); [ "$vSkipped" -le 3 ] && echo "skipped $vName: $(ls "$vDir" | tr '
' ' ')"
		continue
	fi
	# What is known to differ with MSVC, with the reason: tools/msvc_known.txt.
	if grep -q "^$vName:" tools/msvc_known.txt 2>/dev/null; then vKnown=$((vKnown + 1)); continue; fi
	vCount=$((vCount + 1))
	vNames="$vNames $vName"
	# A file of tests that builds cycles on purpose (tests/README.md) leaves them allocated.
	vLeaks="ktc2: leak\|ktc2: .*freed"
	[ -f "$vDir/cycles-allowed" ] && vLeaks="ktc2: .*freed"
	# The standard input of the test, when it has one.
	vInput=
	for vFile in tests/integration/*/"$vName.in"; do [ -f "$vFile" ] && vInput=$vFile; done
	vOptions=$(grep "^cflag=" "$vDir/ktc2_build.txt" | cut -d= -f2- | tr '\n' ' ')
	# The libraries a program links with, as gcc names them (-ladvapi32), are MSVC's .lib files (advapi32.lib).
	vLibraries=$(grep "^ldflag=-l" "$vDir/ktc2_build.txt" | cut -d= -f2- | sed 's/^-l\(.*\)$/\1.lib/' | tr '\n' ' ')
	# cl takes its options with a dash too; MSYS must not take /Fe and the like for paths.
	if ! (cd "$vDir/c" && MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' cl -nologo -std:c11 -O1 -w -DKTC2_LEAK_CHECK -I . $vOptions ktc2_all.c ktc2_core.c $vLibraries -Fe:../msvc.exe -Fo:../ > ../msvc.log 2>&1); then
		vFailed=$((vFailed + 1)); echo "== $vName: does not compile"; grep -m 6 "error" "$vDir/msvc.log" | cut -c1-240
		continue
	fi
	vSelfChecking=
	# A group of tests (tests/ktc2, tests/stdlib) checks itself, and prints times: only how it ends is compared.
	if [ -f "$vDir/self-checking" ]; then
		vSelfChecking=1; vExpected=0
	elif [ -f "$vDir/program.exe" ]; then
		"$vDir/program.exe" < "${vInput:-/dev/null}" > "$vDir/gcc.out" 2> "$vDir/gcc.err"; vExpected=$?
	elif [ ! -f "$vDir/program.out" ]; then
		vSelfChecking=1; vExpected=0
	else
		# The suite's run of the program ended with 0, or the suite failed.
		cp "$vDir/program.out" "$vDir/gcc.out"; vExpected=0
	fi
	"$vDir/msvc.exe" < "${vInput:-/dev/null}" > "$vDir/msvc.out" 2> "$vDir/msvc.err"; vActual=$?
	# Nothing to compare with: what it prints is its own, and only how it ends is checked.
	[ -n "$vSelfChecking" ] && cp "$vDir/msvc.out" "$vDir/gcc.out"
	# The lines a file of tests says differ on every run (`<Name>.vary`: a time, an address) are not compared.
	for vVary in tests/integration/*/"$vName.vary"; do
		[ -f "$vVary" ] || continue
		while IFS= read -r vPrefix || [ -n "$vPrefix" ]; do
			vPrefix=${vPrefix%$'\r'}
			case "$vPrefix" in ""|"#"*) continue ;; esac
			for vOut in "$vDir/gcc.out" "$vDir/msvc.out"; do
				awk -v p="$vPrefix" 'index($0, p) != 1' "$vOut" > "$vOut.kept" && mv "$vOut.kept" "$vOut"
			done
		done < "$vVary"
	done
	# program.out has Unix line ends: the two are compared without carriage returns.
	for vOut in "$vDir/gcc.out" "$vDir/msvc.out"; do tr -d '\r' < "$vOut" > "$vOut.lf" && mv "$vOut.lf" "$vOut"; done
	if [ "$vExpected" != "$vActual" ] || ! cmp -s "$vDir/gcc.out" "$vDir/msvc.out" || grep -q "$vLeaks" "$vDir/msvc.err"; then
		vFailed=$((vFailed + 1)); echo "== $vName: exit $vActual (gcc: $vExpected)"
		diff "$vDir/gcc.out" "$vDir/msvc.out" | head -6 | cut -c1-240
		grep -m 6 "^FAILED" "$vDir/msvc.out" | cut -c1-240
		grep -m 4 "ktc2:" "$vDir/msvc.err" | grep -v "ktc2: arc" | cut -c1-240
	fi
done
# Launched from cmd, a tool of the same name as a Unix one may be Windows' own (find, sort): none is used above.
echo "compared:$vNames" | cut -c1-600
echo "$vCount programs, $vFailed failed, $vKnown left out as known, $vSkipped directories without a program"
# Nearly every directory has a program after a run of the suite: many without one means the comparison did not happen.
[ "$vFailed" -eq 0 ] && [ "$vCount" -gt 100 -o -n "$1" ]
