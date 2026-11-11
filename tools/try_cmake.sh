#!/bin/bash
# Usage: tools/try_cmake.sh <path under integration, without .kt> [arguments for cmake's configure step]
# Transpiles one program, builds it through the CMakeLists.txt ktc2 writes, and runs it.
# Uses the installed tool: run `./gradlew installDist` first, and again after a change.
# Without arguments CMake picks its default generator and compiler (Visual Studio and MSVC on
# Windows); `-G Ninja -DCMAKE_C_COMPILER=clang` and the like choose another.
cd "$(dirname "$0")/.." || exit 1
vName=$1
shift
vOut=build/try_cmake
rm -rf "$vOut"
build/install/ktc2/bin/ktc2 "integration/$vName.kt" -o "$vOut" 2>&1 | grep -v "WARNING\|^warning" | head -20
[ -f "$vOut/CMakeLists.txt" ] || exit 1
cmake -S "$vOut" -B "$vOut/cmake" -DKTC2_DEBUG=ON "$@" > "$vOut/configure.log" 2>&1 || { tail -5 "$vOut/configure.log"; exit 1; }
cmake --build "$vOut/cmake" --config Release > "$vOut/build.log" 2>&1 || { grep -m 12 "error" "$vOut/build.log" | cut -c1-240; exit 1; }
vExe=$(find "$vOut/cmake" -maxdepth 2 -name "*.exe" -o -maxdepth 2 -type f -perm -u+x ! -name "*.*" | grep -v CMakeFiles | head -1)
"$vExe"
echo "exit $?"
