#!/bin/bash
# Usage: tools/asan.sh <build dir> [input file]
# Builds the generated C of a program (build/try, or build/integration/<Name> after a suite run:
# a directory with c/ and ktc2_build.txt) with AddressSanitizer and without the pool, and runs it.
# Prints the exit code and the first lines of what went to standard error; the program's
# output is in <build dir>/asan.out. A use of freed memory that a green run hides shows here.
# On Windows the compiler is clang unless KTC2_CC names one: mingw's gcc has no AddressSanitizer.
vOut=$1
case "$(uname -s)" in
	MINGW*|MSYS*|CYGWIN*) vCompiler=${KTC2_CC:-clang}; vLibs="" ;;
	*) vCompiler=${KTC2_CC:-gcc}; vLibs="-pthread -lm" ;;
esac
# Each C file to an object of its own, KTC2_ASAN_COMPILE_JOBS at once (default: the processors), then linked: a group of
# tests has thousands of files. Each step says what it does and how long it took (KTC2_ASAN_QUIET=1: nothing).
vName=$(basename "$vOut")
vJobs=${KTC2_ASAN_COMPILE_JOBS:-$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)}
say() { [ -n "$KTC2_ASAN_QUIET" ] || echo "asan: $vName: $*" >&2; }
# -g1: the lines, which is what a report needs; full debug information made gcc track the variables of the Unicode
# tables' functions, most of the time of a large program.
vFlags="-std=c11 -O1 -ffp-contract=off -fsanitize=address -g1 -DKTC2_NO_POOL -DKTC2_LEAK_CHECK -w -Wno-unused-but-set-variable -I . $(grep "^cflag=" "$vOut/ktc2_build.txt" | cut -d= -f2- | tr '\n' ' ')"
vFiles=$(grep -c "^c=" "$vOut/ktc2_build.txt")
vStep=$SECONDS
say "compiling $vFiles C files, $vJobs at once"
rm -rf "$vOut/asan-obj" && mkdir -p "$vOut/asan-obj"
(cd "$vOut/c" && grep "^c=" ../ktc2_build.txt | cut -d= -f2- | \
	VFLAGS="$vFlags" VCC="$vCompiler" xargs -P "$vJobs" -I{} sh -c 'o="../asan-obj/$(echo "$1" | tr "/\\\\" "__").o"; $VCC $VFLAGS -c "$1" -o "$o" 2>&1 | head -5' _ {} | head -20)
say "compiled in $((SECONDS - vStep)) s; linking"
vStep=$SECONDS
(cd "$vOut/c" && $vCompiler -fsanitize=address -g -o ../asan.exe ../asan-obj/*.o $vLibs $(grep "^ldflag=" ../ktc2_build.txt | cut -d= -f2-) 2>&1 | head -20)
say "linked in $((SECONDS - vStep)) s; running"
vStep=$SECONDS
# clang on Windows links the sanitizer as a library of its own, which is not on the path.
vRuntime=$(dirname "$($vCompiler -print-file-name=clang_rt.asan_dynamic-x86_64.dll 2>/dev/null)" 2>/dev/null)
[ -d "$vRuntime" ] || vRuntime=$(dirname "$($vCompiler -print-runtime-dir 2>/dev/null | tr '\\' '/')" 2>/dev/null)/windows
[ -d "$vRuntime" ] && PATH="$PATH:$(cygpath -u "$vRuntime" 2>/dev/null || echo "$vRuntime")"
"$vOut/asan.exe" > "$vOut/asan.out" 2> "$vOut/asan.err" < "${2:-/dev/null}"
vCode=$?
say "ran in $((SECONDS - vStep)) s"
echo "exit $vCode $(grep -c . "$vOut/asan.err") stderr lines"; head -12 "$vOut/asan.err"
