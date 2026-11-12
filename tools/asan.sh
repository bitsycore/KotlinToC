#!/bin/bash
# Usage: tools/asan.sh <build dir> [input file]
# Builds the generated C of a program (build/try, or build/integration/<Name> after a suite run:
# a directory with c/ and ktc2_build.txt) with AddressSanitizer and without the pool, and runs it.
# Prints the exit code and the first lines of what went to standard error; the program's
# output is in <build dir>/asan.out. A use of freed memory that a green run hides shows here.
vOut=$1
(cd "$vOut/c" && ${KTC2_CC:-gcc} -std=c11 -O1 -ffp-contract=off -fsanitize=address -g -DKTC2_NO_POOL -DKTC2_LEAK_CHECK -w -Wno-unused-but-set-variable -I . $(grep "^cflag=" ../ktc2_build.txt | cut -d= -f2-) -o ../asan.exe $(grep "^c=" ../ktc2_build.txt | cut -d= -f2-) -pthread -lm $(grep "^ldflag=" ../ktc2_build.txt | cut -d= -f2-) 2>&1 | head -20)
"$vOut/asan.exe" > "$vOut/asan.out" 2> "$vOut/asan.err" < "${2:-/dev/null}"
echo "exit $? $(grep -c . "$vOut/asan.err") stderr lines"; head -12 "$vOut/asan.err"
