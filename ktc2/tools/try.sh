#!/bin/bash
# Usage: tools/try.sh <path under ktc2/integration, without .kt> [extra ktc2 arguments]
# Transpiles one program, builds it with gcc the way the tests do, and runs it.
cd "$(dirname "$0")/../.." || exit 1
vName=$1
shift
vOut=ktc2/build/try
rm -rf "$vOut"
./gradlew :ktc2:run -q --args="$(pwd -W)/ktc2/integration/$vName.kt -o $(pwd -W)/$vOut $*" 2>&1 | grep -v "^$" | grep -v "^\*\|^>\|FAILURE\|BUILD" | cut -c1-260 | head -40
[ -f "$vOut/ktc2_build.txt" ] || exit 1
(cd "$vOut/c" && gcc -std=c11 -O1 -DKTC2_LEAK_CHECK -DKTC2_ARC_STATS -Wall -Werror -Wno-unused-variable -Wno-unused-but-set-variable -pthread -I . $(grep "^cflag=" ../ktc2_build.txt | cut -d= -f2-) -o ../program.exe $(grep "^c=" ../ktc2_build.txt | cut -d= -f2-) -lm $(grep "^ldflag=" ../ktc2_build.txt | cut -d= -f2-) 2>&1 | head -30)
"$vOut/program.exe"
echo "exit $?"
