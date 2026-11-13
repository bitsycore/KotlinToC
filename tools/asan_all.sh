#!/bin/bash
# Usage: tools/asan_all.sh [part of a name]
# After a run of the suite: builds the generated C of every program under build/integration
# with AddressSanitizer (tools/asan.sh) and runs it. Fails when one of them does not end with
# exit code 0, or when the sanitizer or the runtime reported something. Slow: each program is compiled again, one after
# another.
cd "$(dirname "$0")/.." || exit 1
vFailed=0
vCount=0
for vDir in build/integration/*/; do
	vDir=${vDir%/}
	vName=$(basename "$vDir")
	[ -f "$vDir/ktc2_build.txt" ] || continue
	case "$vName" in *"$1"*) ;; *) continue ;; esac
	# The standard input of the test, when it has one.
	vInput=$(find integration -name "$vName.in" | head -1)
	vResult=$(tools/asan.sh "$vDir" ${vInput:+"$vInput"} 2>&1)
	vCount=$((vCount + 1))
	# What a program writes to standard error itself (an exception in a thread) is not a failure.
	# The line that says how the program ended; the C compiler may have said something before it (a note of gcc's).
	vEnded=$(echo "$vResult" | grep -m1 "^exit ")
	if [ -z "$vEnded" ] || [ "$(echo "$vEnded" | cut -d' ' -f2)" != "0" ] || grep -qE "AddressSanitizer|LeakSanitizer|ktc2: leak|ktc2: .*freed" "$vDir/asan.err" 2>/dev/null; then
		vFailed=$((vFailed + 1)); echo "== $vName"; echo "$vResult" | head -20
	fi
done
echo "$vCount programs, $vFailed failed"
[ "$vFailed" -eq 0 ]
