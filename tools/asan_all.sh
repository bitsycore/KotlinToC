#!/bin/bash
# Usage: tools/asan_all.sh [part of a name]
# After a run of the suite: builds the generated C of every program under build/integration
# with AddressSanitizer (tools/asan.sh) and runs it. Fails when one of them does not end with
# exit code 0, or when the sanitizer or the runtime reported something. Slow: each program is
# compiled again; KTC2_ASAN_JOBS of them at once (default: the number of processors), each run
# limited to KTC2_ASAN_TIMEOUT seconds (default 300).
cd "$(dirname "$0")/.." || exit 1

# Builds and runs one program; writes "ok" or what went wrong to <dir>/asan.result.
check() {
	local vDir=$1
	local vStart=$SECONDS
	local vName
	vName=$(basename "$vDir")
	# The standard input of the test, when it has one.
	local vInput
	vInput=$(find integration -name "$vName.in" | head -1)
	# A file of tests that builds cycles on purpose (tests/README.md) leaves them allocated: not looked for there.
	local vLeaks="AddressSanitizer|LeakSanitizer|ktc2: leak|ktc2: .*freed"
	local vOptions=${ASAN_OPTIONS:-}
	if [ -n "$(find tests -name "${vName#test-}.cycles" | head -1)" ]; then vLeaks="AddressSanitizer|ktc2: .*freed"; vOptions="detect_leaks=0:$vOptions"; fi
	local vLimit=()
	command -v timeout > /dev/null && vLimit=(timeout "${KTC2_ASAN_TIMEOUT:-300}")
	local vResult
	vResult=$(ASAN_OPTIONS=$vOptions "${vLimit[@]}" tools/asan.sh "$vDir" ${vInput:+"$vInput"} 2>&1)
	local vCode=$?
	# What a program writes to standard error itself (an exception in a thread) is not a failure.
	# The line that says how the program ended; the C compiler may have said something before it (a note of gcc's).
	local vEnded
	vEnded=$(echo "$vResult" | grep -m1 "^exit ")
	if [ "$vCode" = 124 ]; then
		{ echo "== $vName"; echo "timed out"; echo "$vResult" | head -20; } > "$vDir/asan.result"
	elif [ -z "$vEnded" ] || [ "$(echo "$vEnded" | cut -d' ' -f2)" != "0" ] || grep -qE "$vLeaks" "$vDir/asan.err" 2>/dev/null; then
		{ echo "== $vName"; echo "$vResult" | head -20; } > "$vDir/asan.result"
	else
		echo ok > "$vDir/asan.result"
	fi
	# One line a program as it ends, so the log of a slow or stuck run says where it is.
	local vState=ok
	[ "$(cat "$vDir/asan.result")" = ok ] || vState=FAILED
	echo "$vState $vName ($((SECONDS - vStart)) s)"
}
export -f check

vDirs=()
for vDir in build/integration/*/; do
	vDir=${vDir%/}
	[ -f "$vDir/ktc2_build.txt" ] || continue
	case "$(basename "$vDir")" in *"$1"*) ;; *) continue ;; esac
	rm -f "$vDir/asan.result"
	vDirs+=("$vDir")
done

vJobs=${KTC2_ASAN_JOBS:-$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)}
echo "${#vDirs[@]} programs, $vJobs at once"
[ ${#vDirs[@]} -gt 0 ] && printf '%s\0' "${vDirs[@]}" | xargs -0 -n 1 -P "$vJobs" bash -c 'check "$1"' _

echo
vFailed=0
for vDir in "${vDirs[@]}"; do
	if [ "$(cat "$vDir/asan.result" 2>/dev/null)" != ok ]; then
		vFailed=$((vFailed + 1))
		cat "$vDir/asan.result" 2>/dev/null || echo "== $(basename "$vDir"): no result"
	fi
done
echo "${#vDirs[@]} programs, $vFailed failed"
[ "$vFailed" -eq 0 ]
