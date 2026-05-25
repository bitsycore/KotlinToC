#!/usr/bin/env bash
#
# run_tests.sh — Build the transpiler, then run all integration tests.
#
# Usage:
#   ./run_tests.sh                            # Run all tests
#   ./run_tests.sh --interactive              # Interactive TUI: pick tests and options
#   ./run_tests.sh --run HashMapTest          # Run a single test (verbose)
#   ./run_tests.sh --run "Test1,Test2"        # Run multiple tests
#   ./run_tests.sh --skip-unit                # Skip unit tests
#   ./run_tests.sh --run game --mem-track          # With --mem-track
#   ./run_tests.sh --run game --ast                # With --ast
#   ./run_tests.sh --run game --dump-semantics
#   ./run_tests.sh --run game --disposed=assert         # Use-after-dispose: abort
#   ./run_tests.sh --run game --disposed=log             # Use-after-dispose: log and continue
#   ./run_tests.sh --run game --double-dispose=assert    # Double-dispose: abort
#   ./run_tests.sh --run game --double-dispose=log       # Double-dispose: log and continue
#   ./run_tests.sh --run game --gui                       # With GUI window open
#   ./run_tests.sh --run game --static-libc               # Link C runtime statically
#   ./run_tests.sh --clean                    # Remove all test out/ directories
#   ./run_tests.sh --rebuild                  # Force clean rebuild of JAR
#   ./run_tests.sh --compiler clang           # Override C compiler
#   ./run_tests.sh --cc-args "-j14 -O2"       # Extra C compiler flags
#   ./run_tests.sh --cmake-args "-DFOO=BAR"   # Extra CMake configure flags
#   ./run_tests.sh --cfg Debug                # CMake build type (Debug, Release, RelWithDebInfo, MinSizeRel)
#   ./run_tests.sh --build jar                # Build fat JAR (default)
#   ./run_tests.sh --build gradle             # Use gradle run (no JAR)
#   ./run_tests.sh --build proguard           # ProGuard-optimized JAR
#
set -euo pipefail

# ==================
# MARK: Setup
# ==================

ROOT="$(cd "$(dirname "$0")" && pwd)"
JAR="$ROOT/build/libs/KotlinToC-1.0-SNAPSHOT.jar"
RELEASE_JAR="$ROOT/build/libs/KotlinToC-1.0-SNAPSHOT-release.jar"
TESTS_DIR="$ROOT/integration"

SKIP_UNIT=false
RUN_TEST=""
EXTRA_ARGS=""
COMPILER=""
CC_ARGS=""
CMAKE_ARGS=""
CFG="Release"
BUILD="jar"
CLEAN=false
REBUILD=false
INTERACTIVE=false
GUI_MODE=""
STATIC_LIBC=""

while [[ $# -gt 0 ]]; do
	case "$1" in
		--help)           sed -n '/^# Usage:/,/^$/p' "$0" | sed 's/^# //'; exit 0 ;;
		--interactive|-I|-i)    INTERACTIVE=true; shift ;;
		--skip-unit)      SKIP_UNIT=true; shift ;;
		--run)            RUN_TEST="$2"; shift 2 ;;
		--mem-track)          EXTRA_ARGS="$EXTRA_ARGS --mem-track"; shift ;;
		--ast)                EXTRA_ARGS="$EXTRA_ARGS --ast"; shift ;;
		--dump-semantics)     EXTRA_ARGS="$EXTRA_ARGS --dump-semantics"; shift ;;
		--disposed=*)      EXTRA_ARGS="$EXTRA_ARGS --disposed=${1#*=}"; shift ;;
		--double-dispose=*) EXTRA_ARGS="$EXTRA_ARGS --double-dispose=${1#*=}"; shift ;;
		--gui)            GUI_MODE=true; shift ;;
		--static-libc)    STATIC_LIBC=true; shift ;;
		--args)           EXTRA_ARGS="$EXTRA_ARGS $2"; shift 2 ;;
		--compiler)       COMPILER="$2"; shift 2 ;;
		--cc-args)        CC_ARGS="$2"; shift 2 ;;
		--cmake-args)     CMAKE_ARGS="$2"; shift 2 ;;
		--cfg)            CFG="$2"; shift 2 ;;
		--build)          BUILD="$2"; shift 2 ;;
		--clean)          CLEAN=true; shift ;;
		--rebuild)        REBUILD=true; shift ;;
		*)                echo "Unknown option: $1 (use --help)"; exit 1 ;;
	esac
done

BUILD="$(echo "$BUILD" | tr '[:upper:]' '[:lower:]')"
GRADLE_JAR="$([ "$REBUILD" = true ] && echo "clean jar" || echo "jar")"
GRADLE_PRO="$([ "$REBUILD" = true ] && echo "clean proguard" || echo "proguard")"

# ==================
# MARK: Clean
# ==================

if [[ "$CLEAN" == true ]]; then
	echo "Cleaning per-test output directories..."
	vCleaned=0
	for d in "$TESTS_DIR"/*/; do
		[[ -d "$d" ]] || continue
		if [[ -d "$d/out" ]]; then
			rm -rf "$d/out"
			echo "  removed $d/out"
			((vCleaned++)) || true
		fi
	done
	echo "Cleaned $vCleaned test output directories."
	exit 0
fi

# ==================
# MARK: Output helpers
# ==================

RED='\033[0;31m';    GREEN='\033[0;32m';  YELLOW='\033[1;33m'
CYAN='\033[0;36m';   GRAY='\033[0;90m';   WHITE='\033[1;37m'
DYELLOW='\033[0;33m'; NC='\033[0m'

pass()    { printf "  ${GREEN}PASS${NC} %s\n" "$1"; }
fail()    { printf "  ${RED}FAIL${NC} %s\n" "$1"; }
info()    { printf "  ${CYAN}----%s${NC} %s\n" "" "$1"; }
section() { printf "\n${YELLOW}=== %s ===${NC}\n" "$1"; }
showcmd() { printf "  ${DYELLOW}\$ ${WHITE}%s${NC}\n" "$1"; }

# Returns terminal dimensions as "rows cols" using ioctl (stty) which queries
# the actual window size. tput is unreliable on macOS where terminfo has
# hardcoded lines#24 which ignores the real terminal dimensions.
get_term_size() {
	local s
	s=$(stty size 2>/dev/null) && [[ -n "$s" ]] && { echo "$s"; return; }
	local h w
	h=$(tput lines 2>/dev/null || true)
	w=$(tput cols 2>/dev/null || true)
	[[ -n "$h" && "$h" =~ ^[0-9]+$ && $h -gt 0 ]] && [[ -n "$w" && "$w" =~ ^[0-9]+$ && $w -gt 0 ]] && { echo "$h $w"; return; }
	[[ -n "${LINES:-}" && "${LINES:-}" =~ ^[0-9]+$ && "${LINES:-}" -gt 0 ]] && { echo "${LINES} ${COLUMNS:-80}"; return; }
	echo "24 80"
}

# Returns current epoch time in milliseconds.
now_ms() {
	local ms
	ms=$(date +%s%3N 2>/dev/null)
	[[ "$ms" =~ ^[0-9]+$ ]] && echo "$ms" && return
	ms=$(python3 -c "import time; print(int(time.time()*1000))" 2>/dev/null)
	[[ "$ms" =~ ^[0-9]+$ ]] && echo "$ms" && return
	echo $(($(date +%s) * 1000))
}

# Formats milliseconds as "123ms" or "1.23s".
format_ms() {
	local ms=$1
	if [[ $ms -lt 1000 ]]; then echo "${ms}ms"
	else awk "BEGIN { printf \"%.2fs\", $ms / 1000 }"; fi
}

# Parses a config.ktc.toml file and prints the value for the given key.
# Returns empty string if not found.
read_config_toml() {
	local inPath="$1"
	local inKey="$2"
	[[ -f "$inPath" ]] || { echo ""; return; }
	case "$inKey" in
		gui)   grep -qE '^[[:space:]]*gui[[:space:]]*=[[:space:]]*true' "$inPath" 2>/dev/null && echo "true" || echo "false" ;;
		executable|name|author|version|info|args)
			sed -nE 's/^[[:space:]]*'"$inKey"'[[:space:]]*=[[:space:]]*"([^"]*)".*/\1/p' "$inPath" 2>/dev/null | head -1 || echo "" ;;
		timeout)
			sed -nE 's/^[[:space:]]*timeout[[:space:]]*=[[:space:]]*([0-9]+).*/\1/p' "$inPath" 2>/dev/null | head -1 || echo "" ;;
		*) echo "" ;;
	esac
}

# Check if ktc_user.cmake or ktc_modules.cmake exists in the output dir (CMake needed).
has_user_cmake() {
	local inDir="$1"
	[[ -f "$inDir/ktc_user.cmake" || -f "$inDir/ktc_modules.cmake" ]]
}

# ==================
# MARK: Compiler detection
# ==================

if [[ -n "$COMPILER" ]]; then
	CC="$COMPILER"
else
	CC=""
	for vCand in gcc clang cc; do
		if command -v "$vCand" &>/dev/null; then CC="$vCand"; break; fi
	done
fi
if [[ -z "$CC" ]]; then
	echo "ERROR: No C compiler found (tried gcc, clang, cc). Install one and add to PATH."
	exit 1
fi

# CMake detection
if command -v cmake &>/dev/null; then
	CMAKE_BIN="cmake"
else
	CMAKE_BIN=""
fi

# ==================
# MARK: Build
# ==================

# Builds the transpiler JAR (or ProGuard release JAR) via Gradle. Exits on failure.
invoke_build() {
	[[ "$BUILD" == "gradle" ]] && return
	if [[ "$BUILD" == "proguard" ]]; then
		section "Building ProGuard release JAR"
		showcmd "gradlew $GRADLE_PRO"
		"$ROOT/gradlew" $GRADLE_PRO --quiet 2>&1
		[[ -f "$RELEASE_JAR" ]] || { echo "ERROR: ProGuard build failed"; exit 1; }
		pass "Built $RELEASE_JAR"
	else
		section "Building transpiler JAR"
		showcmd "gradlew $GRADLE_JAR"
		"$ROOT/gradlew" $GRADLE_JAR --quiet 2>&1
		[[ -f "$JAR" ]] || { echo "ERROR: JAR build failed"; exit 1; }
		pass "Built $JAR"
	fi
}

# ==================
# MARK: Test execution (verbose, single-test mode)
# ==================

# Transpiles, compiles, and runs one test directory with verbose step-by-step output.
# Returns 0 on success, 1 on failure. Used by --run mode and interactive single-test selection.
invoke_test() {
	local inName="$1"
	local inSrcDir="$2"
	local inOutDir="$3"
	local inExtraArgs="${4:-}"
	local inGuiMode="${5:-}"

	local vTMs=0 vCMs=0 vRMs=0
	local vTs vTe

	# Collect .kt files recursively
	local vKtFiles=()
	while IFS= read -r -d '' f; do
		vKtFiles+=("$f")
	done < <(find "$inSrcDir" -name "*.kt" -print0 2>/dev/null | sort -z)
	if [[ ${#vKtFiles[@]} -eq 0 ]]; then fail "$inName — no .kt files in $inSrcDir"; return 1; fi

	# Read config.ktc.toml for executable name
	local vCfgPath="$inSrcDir/config.ktc.toml"
	local vExeName; vExeName="$(read_config_toml "$vCfgPath" executable)"
	[[ -z "$vExeName" ]] && vExeName="$inName"

	# Selective cleanup: preserve _cmake/, ktc_user.cmake, *.dll across runs
	if [[ -d "$inOutDir" ]]; then
		find "$inOutDir" -mindepth 1 -maxdepth 1 -not -name "_cmake" -not -name "ktc_user.cmake" -not -name "*.dll" -exec rm -rf {} + 2>/dev/null || true
	fi
	mkdir -p "$inOutDir"

	local vKtNames=""
	for f in "${vKtFiles[@]}"; do vKtNames+="$(basename "$f") "; done
	info "Input:      $vKtNames"
	info "Output dir: $inOutDir"

	# ── Transpile ────────────────────────────────────────────────
	section "Transpile"
	local vSuffix="${inExtraArgs:+ $inExtraArgs}"
	case "$BUILD" in
		gradle) showcmd "gradlew run --args=\"$vKtNames -o $inOutDir --name $vExeName$vSuffix\"" ;;
		*)
			local vLabel; [[ "$BUILD" == "proguard" ]] && vLabel="KotlinToC-release.jar" || vLabel="KotlinToC.jar"
			showcmd "java -jar $vLabel $vKtNames -o $inOutDir --name $vExeName$vSuffix" ;;
	esac
	echo ""
	set +e
	local vTOut
	vTs=$(now_ms)
	case "$BUILD" in
		gradle)
			vTOut=$("$ROOT/gradlew" run --quiet --args="${vKtFiles[*]} -o $inOutDir --name $vExeName $inExtraArgs" 2>&1) ;;
		*)
			local vActive; [[ "$BUILD" == "proguard" ]] && vActive="$RELEASE_JAR" || vActive="$JAR"
			vTOut=$(java -jar "$vActive" "${vKtFiles[@]}" -o "$inOutDir" --name "$vExeName" $inExtraArgs 2>&1) ;;
	esac
	local vTExit=$?
	vTe=$(now_ms); vTMs=$((vTe - vTs))
	set -e
	while IFS= read -r vLine; do
		if echo "$vLine" | grep -q 'warning:'; then printf "  ${YELLOW}%s${NC}\n" "$vLine"
		else printf "  %s\n" "$vLine"; fi
	done <<< "$vTOut"
	echo ""
	if [[ $vTExit -ne 0 ]]; then fail "Transpilation failed (exit $vTExit)"; return 1; fi
	printf "  ${GREEN}PASS${NC} Transpilation succeeded  ${GRAY}(ktc: %s)${NC}\n" "$(format_ms $vTMs)"

	# Copy ktc_user.cmake from source dir if present
	local vUserCmakeSrc="$inSrcDir/ktc_user.cmake"
	if [[ -f "$vUserCmakeSrc" ]]; then cp "$vUserCmakeSrc" "$inOutDir/ktc_user.cmake"; fi

	# ── Discover .c files recursively ────────────────────────────
	local vKtcDir="$inOutDir/ktc"
	local vCoreDir="$inOutDir/ktc.core"
	local vCSrcs=()

	# Core runtime first
	local vCoreFile="$vCoreDir/ktc_core.c"
	[[ -f "$vCoreFile" ]] && vCSrcs+=("$vCoreFile")

	# All .c files under ktc/ recursively
	if [[ -d "$vKtcDir" ]]; then
		while IFS= read -r -d '' f; do
			vCSrcs+=("$f")
		done < <(find "$vKtcDir" -name "*.c" -print0 2>/dev/null | sort -z)
	fi

	# All .c files in output dir recursively, excluding ktc/ and ktc.core/
	while IFS= read -r -d '' f; do
		[[ "$f" == "$vKtcDir"* ]] && continue
		[[ "$f" == "$vCoreDir"* ]] && continue
		vCSrcs+=("$f")
	done < <(find "$inOutDir" -name "*.c" -print0 2>/dev/null | sort -z)

	if [[ ${#vCSrcs[@]} -eq 0 ]]; then fail "No .c files generated"; return 1; fi

	# ── Compile (cmake if ktc_user.cmake/ktc_modules.cmake present, direct otherwise) ──
	local vHasUserCmake
	vHasUserCmake=$(has_user_cmake "$inOutDir" && echo true || echo false)

	if [[ "$vHasUserCmake" == true && -z "$CMAKE_BIN" ]]; then
		printf "  ${YELLOW}SKIP${NC} %s — ktc_user.cmake/ktc_modules.cmake requires cmake, but cmake was not found on PATH (skipped)\n" "$inName"
		return 2  # special skip code
	fi

	local vUseCmake=false
	local vExe
	[[ "$vHasUserCmake" == true && -n "$CMAKE_BIN" ]] && vUseCmake=true

	if [[ "$vUseCmake" == true ]]; then
		# ── CMake configure ─────────────────────────────────────
		section "CMake Configure"
		local vCmakeBuildDir="$inOutDir/_cmake"
		showcmd "$CMAKE_BIN -B _cmake -S . -DCMAKE_BUILD_TYPE=$CFG${COMPILER:+ -DCMAKE_C_COMPILER=$COMPILER}${STATIC_LIBC:+ -DKTC_STATIC_LIBC=ON}${CMAKE_ARGS:+ $CMAKE_ARGS}"
		echo ""
		local vCfgOut vCfgExit
		set +e
		vTs=$(now_ms)
		local vCfgArgs=(-B "$vCmakeBuildDir" -S "$inOutDir" "-DCMAKE_BUILD_TYPE=$CFG")
		[[ -n "$COMPILER" ]] && vCfgArgs+=("-DCMAKE_C_COMPILER=$COMPILER")
		[[ "$STATIC_LIBC" == true ]] && vCfgArgs+=("-DKTC_STATIC_LIBC=ON")
		[[ -n "$CMAKE_ARGS" ]] && vCfgArgs+=($CMAKE_ARGS)
		vCfgOut=$("$CMAKE_BIN" "${vCfgArgs[@]}" 2>&1)
		vCfgExit=$?
		vTe=$(now_ms); local vCMs=$(( vTe - vTs ))
		set -e
		while IFS= read -r vLine; do printf "  ${GRAY}%s${NC}\n" "$vLine"; done <<< "$vCfgOut"
		echo ""
		if [[ $vCfgExit -ne 0 ]]; then
			if echo "$vCfgOut" | grep -qiE 'Could not find|not found|NOTFOUND'; then
				printf "  ${YELLOW}SKIP${NC} %s — required library not found (skipped)\n" "$inName"
				return 2
			fi
			fail "CMake configure failed (exit $vCfgExit)"; return 1
		fi
		printf "  ${GREEN}PASS${NC} CMake configure OK  ${GRAY}(cfg: %s)${NC}\n" "$(format_ms $vCMs)"

		# ── CMake build ─────────────────────────────────────────
		section "CMake Build"
		showcmd "$CMAKE_BIN --build _cmake --config $CFG"
		echo ""
		set +e
		vTs=$(now_ms)
		local vBldOut
		vBldOut=$("$CMAKE_BIN" --build "$vCmakeBuildDir" --config "$CFG" 2>&1)
		local vBldExit=$?
		vTe=$(now_ms); vCMs=$(( vTe - vTs ))
		set -e
		[[ -n "$vBldOut" ]] && while IFS= read -r vLine; do printf "  ${GRAY}%s${NC}\n" "$vLine"; done <<< "$vBldOut" && echo ""
		if [[ $vBldExit -ne 0 ]]; then fail "CMake build failed (exit $vBldExit)"; return 1; fi
		# Find the built executable — CMake places it in the source dir.
		# On Unix the exe name may collide with a package dir; look for the name first,
		# then fall back to any executable.
		local vExpected="$inOutDir/$vExeName"
		if [[ -f "$vExpected" && -x "$vExpected" ]]; then vExe="$vExpected"
		else
			local vFoundExe
			vFoundExe=$(find "$inOutDir" -maxdepth 1 -type f -perm +111 ! -path "*/_cmake/*" ! -name "_ktcrun*" ! -name "*.dll" ! -name "*.dylib" 2>/dev/null | head -1)
			if [[ -z "$vFoundExe" ]]; then fail "No executable found after cmake build"; return 1; fi
			vExe="$vFoundExe"
		fi
		printf "  ${GREEN}PASS${NC} CMake build OK -> %s  ${GRAY}(build: %s)${NC}\n" "$vExe" "$(format_ms $vCMs)"
	else
		# ── Direct compile ───────────────────────────────────────
		# On Unix, the exe name may collide with a package directory
		# (e.g. out/ForLoopTest vs out/ForLoopTest/). Use a non-colliding suffix.
		local vExeRaw="$inOutDir/$vExeName"
		if [[ -d "$vExeRaw" ]]; then
			vExe="$vExeRaw.out"
		else
			vExe="$vExeRaw"
		fi
		section "Compile"
		local vCArgs=(-std=c11 -iquote "$inOutDir" -o "$vExe")
		[[ -n "$CC_ARGS" ]] && vCArgs+=($CC_ARGS)
		vCArgs+=("${vCSrcs[@]}")
		showcmd "$CC -std=c11${CC_ARGS:+ $CC_ARGS} -o $vExe ${vCSrcs[*]}"
		echo ""
		set +e
		local vCOut
		vTs=$(now_ms)
		vCOut=$("$CC" "${vCArgs[@]}" 2>&1)
		local vCExit=$?
		vTe=$(now_ms); vCMs=$(( vTe - vTs ))
		set -e
		[[ -n "$vCOut" ]] && while IFS= read -r vLine; do printf "  ${GRAY}%s${NC}\n" "$vLine"; done <<< "$vCOut" && echo ""
		if [[ $vCExit -ne 0 ]]; then fail "Compilation failed (exit $vCExit)"; return 1; fi
		printf "  ${GREEN}PASS${NC} Compilation succeeded -> %s  ${GRAY}(comp: %s)${NC}\n" "$vExe" "$(format_ms $vCMs)"
	fi

	# Generated files listing (recursive)
	section "Generated Files"
	while IFS= read -r -d '' f; do
		local vRel="${f#$inOutDir/}"
		local vSize
		if [[ "$(uname)" == "Darwin" ]]; then
			vSize="$(stat -f%z "$f" 2>/dev/null || echo "?")"
		else
			vSize="$(stat -c%s "$f" 2>/dev/null || echo "?")"
		fi
		if [[ "$vSize" =~ ^[0-9]+$ ]] && [[ $vSize -ge 1024 ]]; then
			printf "  ${CYAN}----%s${NC} %-30s %10s\n" "" "$vRel" "$(echo "scale=1; $vSize / 1024" | bc 2>/dev/null || awk "BEGIN { printf \"%.1f\", $vSize / 1024 }") KB"
		else
			printf "  ${CYAN}----%s${NC} %-30s %10s\n" "" "$vRel" "${vSize} B"
		fi
	done < <(find "$inOutDir" -type f -print0 2>/dev/null | sort -z)

	# ── Run ──────────────────────────────────────────────────────
	section "Run"
	showcmd "$vExe"
	echo ""

	local vIsGui; vIsGui="$(read_config_toml "$vCfgPath" gui)"
	local vCfgArgs; vCfgArgs="$(read_config_toml "$vCfgPath" args)"
	local vRunArgs=""
	if [[ "$vIsGui" == true && "$inGuiMode" != true ]]; then
		vRunArgs="--skip-gui"
	else
		vRunArgs="$vCfgArgs"
	fi
	[[ -n "$vRunArgs" ]] && info "Test args: $vRunArgs"

	set +e
	local vROut
	vTs=$(now_ms)
	if [[ -n "$vRunArgs" ]]; then
		vROut=$("$vExe" $vRunArgs 2>&1)
	else
		vROut=$("$vExe" 2>&1)
	fi
	local vRExit=$?
	vTe=$(now_ms); vRMs=$(( vTe - vTs ))
	set -e
	while IFS= read -r vLine; do printf "  %s\n" "$vLine"; done <<< "$vROut"
	echo ""
	if [[ $vRExit -ne 0 ]]; then fail "Runtime error (exit $vRExit)"; return 1; fi

	local vLeak=false
	echo "$vROut" | grep -q 'leaked' && vLeak=true || true
	if [[ "$vLeak" == true ]]; then
		printf "  ${DYELLOW}PASS${NC} Program exited - memory leaks detected  ${RED}LEAK${NC}  ${GRAY}(run: %s)${NC}\n" "$(format_ms $vRMs)"
	else
		printf "  ${GREEN}PASS${NC} Program exited successfully (code 0)  ${GRAY}(run: %s)${NC}\n" "$(format_ms $vRMs)"
	fi
	return 0
}

# ==================
# MARK: Suite (parallel)
# ==================

# Runs a list of test names in parallel, prints results.
# Sets global SUITE_PASSED, SUITE_FAILED, SUITE_FAILED_NAMES.
run_suite() {
	local inSkipUnit="${1:-false}"
	shift
	local inTestNames=("$@")

	SUITE_PASSED=0; SUITE_FAILED=0; SUITE_FAILED_NAMES=()

	# Unit tests
	if [[ "$inSkipUnit" == false ]]; then
		section "Unit Tests (gradlew test)"
		if "$ROOT/gradlew" test --quiet 2>&1; then
			pass "All unit tests passed"
		else
			fail "Unit tests had failures"
			SUITE_FAILED_NAMES+=("unit-tests")
			((SUITE_FAILED++)) || true
		fi
	fi

	section "Integration Tests"
	if [[ ${#inTestNames[@]} -eq 0 ]]; then info "No tests to run."; return; fi

	local vTmpDir
	vTmpDir="$(mktemp -d)"

	# Run tests in parallel background jobs
	local vPids=()
	for vDirName in "${inTestNames[@]}"; do
		local vDir="$TESTS_DIR/$vDirName"
		(
			# Collect .kt files recursively
			local vKtFiles=()
			while IFS= read -r -d '' f; do
				vKtFiles+=("$f")
			done < <(find "$vDir" -name "*.kt" -print0 2>/dev/null)
			if [[ ${#vKtFiles[@]} -eq 0 ]]; then
				printf "  ${RED}FAIL${NC} %s (no .kt files)\n" "$vDirName"
				echo 1 > "$vTmpDir/$vDirName.code"; exit 0
			fi
			local vOut="$vDir/out"

			# Read config.ktc.toml for executable name
			local vCfgPath="$vDir/config.ktc.toml"
			local vExeName; vExeName="$(read_config_toml "$vCfgPath" executable)"
			[[ -z "$vExeName" ]] && vExeName="$vDirName"

			# Selective cleanup
			if [[ -d "$vOut" ]]; then
				find "$vOut" -mindepth 1 -maxdepth 1 -not -name "_cmake" -not -name "ktc_user.cmake" -not -name "*.dll" -exec rm -rf {} + 2>/dev/null || true
			fi
			mkdir -p "$vOut"

			local vTs vTe vTMs=0 vCMs=0 vRMs=0

			# Transpile
			set +e
			local vTOut
			vTs=$(now_ms)
			case "$BUILD" in
				gradle)
					vTOut=$("$ROOT/gradlew" run --quiet --args="${vKtFiles[*]} -o $vOut --name $vExeName $EXTRA_ARGS" 2>&1) ;;
				*)
					local vActive; [[ "$BUILD" == "proguard" ]] && vActive="$RELEASE_JAR" || vActive="$JAR"
					vTOut=$(java -jar "$vActive" "${vKtFiles[@]}" -o "$vOut" --name "$vExeName" $EXTRA_ARGS 2>&1) ;;
			esac
			local vTExit=$?
			vTe=$(now_ms); vTMs=$(( vTe - vTs ))
			set -e
			local vWarnings; vWarnings=$(echo "$vTOut" | grep 'warning:' || true)
			if [[ $vTExit -ne 0 ]]; then
				printf "  ${RED}FAIL${NC} %s (transpile failed)\n" "$vDirName"
				echo 1 > "$vTmpDir/$vDirName.code"; exit 0
			fi

			# Copy ktc_user.cmake from source dir if present
			local vUserCmakeSrc="$vDir/ktc_user.cmake"
			[[ -f "$vUserCmakeSrc" ]] && cp "$vUserCmakeSrc" "$vOut/ktc_user.cmake"

			# Discover .c files recursively
			local vKtcDir="$vOut/ktc"; local vCoreDir="$vOut/ktc.core"; local vCSrcs=()
			local vCoreFile="$vCoreDir/ktc_core.c"
			[[ -f "$vCoreFile" ]] && vCSrcs+=("$vCoreFile")
			if [[ -d "$vKtcDir" ]]; then
				while IFS= read -r -d '' f; do
					vCSrcs+=("$f")
				done < <(find "$vKtcDir" -name "*.c" -print0 2>/dev/null | sort -z)
			fi
			while IFS= read -r -d '' f; do
				[[ "$f" == "$vKtcDir"* ]] && continue
				[[ "$f" == "$vCoreDir"* ]] && continue
				vCSrcs+=("$f")
			done < <(find "$vOut" -name "*.c" -print0 2>/dev/null | sort -z)
			if [[ ${#vCSrcs[@]} -eq 0 ]]; then
				printf "  ${RED}FAIL${NC} %s (no .c files generated)\n" "$vDirName"
				echo 1 > "$vTmpDir/$vDirName.code"; exit 0
			fi

			# Compile — cmake if ktc_user.cmake/ktc_modules.cmake present, direct otherwise
			local vExe
			local vHasUCmake
			vHasUCmake=$(has_user_cmake "$vOut" && echo true || echo false)

			if [[ "$vHasUCmake" == true && -z "$CMAKE_BIN" ]]; then
				printf "  ${DYELLOW}SKIP${NC} %s  (ktc_user.cmake present but cmake not found)\n" "$vDirName"
				echo 2 > "$vTmpDir/$vDirName.code"; exit 0
			fi

			if [[ "$vHasUCmake" == true ]]; then
				local vCmkBld="$vOut/_cmake"
				set +e
				local vCfgArgs=(-B "$vCmkBld" -S "$vOut" "-DCMAKE_BUILD_TYPE=$CFG")
				[[ -n "$COMPILER" ]] && vCfgArgs+=("-DCMAKE_C_COMPILER=$COMPILER")
				[[ "$STATIC_LIBC" == true ]] && vCfgArgs+=("-DKTC_STATIC_LIBC=ON")
				[[ -n "$CMAKE_ARGS" ]] && vCfgArgs+=($CMAKE_ARGS)
				local vCfgOut; vCfgOut=$("$CMAKE_BIN" "${vCfgArgs[@]}" 2>&1)
				local vCfgEx=$?
				if [[ $vCfgEx -ne 0 ]]; then
					if echo "$vCfgOut" | grep -qiE 'Could not find|not found|NOTFOUND'; then
						printf "  ${DYELLOW}SKIP${NC} %s  (required library not found)\n" "$vDirName"
						echo 2 > "$vTmpDir/$vDirName.code"; exit 0
					fi
					printf "  ${RED}FAIL${NC} %s (cmake configure failed)\n" "$vDirName"
					echo 1 > "$vTmpDir/$vDirName.code"; exit 0
				fi
				"$CMAKE_BIN" --build "$vCmkBld" --config "$CFG" 2>/dev/null
				local vBldEx=$?
				vTe=$(now_ms); vCMs=$(( vTe - vTs ))
				if [[ $vBldEx -ne 0 ]]; then
					printf "  ${RED}FAIL${NC} %s (cmake build failed)\n" "$vDirName"
					echo 1 > "$vTmpDir/$vDirName.code"; exit 0
				fi
				local vExpected="$vOut/$vExeName"
				if [[ -f "$vExpected" && -x "$vExpected" ]]; then vExe="$vExpected"
				else
					local vFoundExe
					vFoundExe=$(find "$vOut" -maxdepth 1 -type f -perm +111 ! -path "*/_cmake/*" ! -name "_ktcrun*" ! -name "*.dll" ! -name "*.dylib" 2>/dev/null | head -1)
					if [[ -z "$vFoundExe" ]]; then
						printf "  ${RED}FAIL${NC} %s (no executable after cmake build)\n" "$vDirName"
						echo 1 > "$vTmpDir/$vDirName.code"; exit 0
					fi
					vExe="$vFoundExe"
				fi
			else
				# On Unix, exe name may collide with a package dir; use non-colliding suffix
				local vExeRaw="$vOut/$vExeName"
				if [[ -d "$vExeRaw" ]]; then vExe="$vExeRaw.out"; else vExe="$vExeRaw"; fi
				set +e
				vTs=$(now_ms)
				"$CC" -std=c11 -iquote "$vOut" $CC_ARGS -o "$vExe" "${vCSrcs[@]}" 2>/dev/null
				local vCExit=$?
				vTe=$(now_ms); vCMs=$(( vTe - vTs ))
				set -e
				if [[ $vCExit -ne 0 ]]; then
					printf "  ${RED}FAIL${NC} %s (compile failed)\n" "$vDirName"
					echo 1 > "$vTmpDir/$vDirName.code"; exit 0
				fi
			fi

			# Run
			local vCfgArgsRun=""; local vIsGui=""
			vCfgArgsRun="$(read_config_toml "$vCfgPath" args)"
			vIsGui="$(read_config_toml "$vCfgPath" gui)"
			local vRunArgs=""
			[[ "$vIsGui" == true ]] && vRunArgs="--skip-gui" || vRunArgs="$vCfgArgsRun"

			set +e
			local vROut
			vTs=$(now_ms)
			if [[ -n "$vRunArgs" ]]; then
				vROut=$("$vExe" $vRunArgs 2>&1)
			else
				vROut=$("$vExe" 2>&1)
			fi
			local vRExit=$?
			vTe=$(now_ms); vRMs=$(( vTe - vTs ))
			set -e
			if [[ $vRExit -ne 0 ]]; then
				printf "  ${RED}FAIL${NC} %s (runtime error, exit %d)\n" "$vDirName" "$vRExit"
				echo 1 > "$vTmpDir/$vDirName.code"; exit 0
			fi

			local vLeak=false
			echo "$vROut" | grep -q 'leaked' && vLeak=true || true
			if [[ "$vLeak" == true ]]; then
				printf "  ${DYELLOW}PASS${NC} ${DYELLOW}%s${NC}  ${RED}LEAK${NC}  ${GRAY}ktc: %s  comp: %s  run: %s${NC}\n" \
					"$vDirName" "$(format_ms $vTMs)" "$(format_ms $vCMs)" "$(format_ms $vRMs)"
			else
				printf "  ${GREEN}PASS${NC} ${GREEN}%s${NC}  ${GRAY}ktc: %s  comp: %s  run: %s${NC}\n" \
					"$vDirName" "$(format_ms $vTMs)" "$(format_ms $vCMs)" "$(format_ms $vRMs)"
			fi
			[[ -n "$vWarnings" ]] && while IFS= read -r w; do
				[[ -n "$w" ]] && printf "       ${YELLOW}%s${NC}\n" "$w"
			done <<< "$vWarnings"
			echo 0 > "$vTmpDir/$vDirName.code"
		) &
		vPids+=($!)
	done
	for vPid in "${vPids[@]}"; do wait "$vPid" || true; done

	SUITE_SKIPPED_COUNT=0; SUITE_SKIPPED_NAMES_ARR=()
	for vDirName in "${inTestNames[@]}"; do
		local vCode; vCode=$(cat "$vTmpDir/$vDirName.code" 2>/dev/null || echo 1)
		if [[ "$vCode" == "0" ]]; then
			((SUITE_PASSED++)) || true
		elif [[ "$vCode" == "2" ]]; then
			((SUITE_SKIPPED_COUNT++)) || true
			SUITE_SKIPPED_NAMES_ARR+=("$vDirName")
		else
			((SUITE_FAILED++)) || true
			SUITE_FAILED_NAMES+=("$vDirName")
		fi
	done
	rm -rf "$vTmpDir"
}

# Prints the final pass/fail summary and exits with the appropriate code.
show_summary() {
	section "Summary"
	local vTotal=$((SUITE_PASSED + SUITE_FAILED + SUITE_SKIPPED_COUNT))
	printf "  Total: %d  |  ${GREEN}Passed: %d${NC}" "$vTotal" "$SUITE_PASSED"
	if [[ ${SUITE_SKIPPED_COUNT:-0} -gt 0 ]]; then
		printf "  |  ${YELLOW}Skipped: %d${NC}" "${SUITE_SKIPPED_COUNT}"
	fi
	if [[ $SUITE_FAILED -gt 0 ]]; then
		printf "  |  ${RED}Failed: %d${NC}\n" "$SUITE_FAILED"
		echo ""
		printf "  ${RED}Failed tests:${NC}\n"
		for vName in "${SUITE_FAILED_NAMES[@]}"; do printf "    ${RED}- %s${NC}\n" "$vName"; done
		if [[ ${SUITE_SKIPPED_COUNT:-0} -gt 0 ]]; then
			echo ""
			printf "  ${YELLOW}Skipped tests (missing library):${NC}\n"
			for vName in "${SUITE_SKIPPED_NAMES_ARR[@]:-}"; do printf "    ${YELLOW}- %s${NC}\n" "$vName"; done
		fi
		exit 1
	else
		printf "  |  ${GREEN}Failed: 0${NC}\n"
		if [[ ${SUITE_SKIPPED_COUNT:-0} -gt 0 ]]; then
			echo ""
			printf "  ${YELLOW}Skipped tests (missing library):${NC}\n"
			for vName in "${SUITE_SKIPPED_NAMES_ARR[@]:-}"; do printf "    ${YELLOW}- %s${NC}\n" "$vName"; done
		fi
		exit 0
	fi
}

# ==================
# MARK: Interactive TUI
# ==================

# macOS Bash 3.2 doesn't support fractional read -t timeout
_KT_TMOUT="-t0.1"; [[ ${BASH_VERSINFO[0]:-0} -lt 4 ]] && _KT_TMOUT="-t1"

# Reads a single keypress and outputs a canonical name: up | down | right | left | space | enter | esc | char:X
tui_read_key() {
	local vKey
	IFS= read -rsn1 vKey
	if [[ "$vKey" == $'\x1b' ]]; then
		local vSeq
		IFS= read -rsn2 $_KT_TMOUT vSeq 2>/dev/null || true
		case "$vSeq" in "[A") echo "up" ;; "[B") echo "down" ;; "[C") echo "right" ;; "[D") echo "left" ;; *) echo "esc" ;; esac
	elif [[ "$vKey" == " " ]];     then echo "space"
	elif [[ "$vKey" == "" ]];      then echo "enter"
	elif [[ "$vKey" == $'\r' ]];   then echo "enter"
	elif [[ "$vKey" == $'\x03' ]]; then echo "esc"
	else echo "char:${vKey}"; fi
}

# Full-screen interactive TUI for selecting tests, options, build mode, and compiler.
# Sets TUI_TESTS, TUI_SKIP_UNIT, TUI_EXTRA_ARGS, TUI_BUILD, TUI_COMPILER, TUI_CC_ARGS on exit.
# Returns 0 if user confirmed, 1 if cancelled.
run_interactive() {
	local vTestNames=()
	for d in "$TESTS_DIR"/*/; do [[ -d "$d" ]] && vTestNames+=("$(basename "$d")"); done
	IFS=$'\n' vTestNames=($(sort <<< "${vTestNames[*]}")); unset IFS

	local vCount=${#vTestNames[@]}

	# State arrays (0=off 1=on)
	local vTestOn=(); for ((i=0; i<vCount; i++)); do vTestOn[$i]=1; done
	local vOptOn=(0 0 0 0 0 0 0 0)   # SkipUnit MemTrack Ast DumpSemantics StaticLibc DisposedAssert DisposedLog DoubleDisposeAssert DoubleDisposeLog
	local vBuildOn=(1 0 0)           # jar gradle proguard
	local vOptLabels=("Skip Unit Tests      (--skip-unit)" "Memory Tracking      (--mem-track)" "Dump AST             (--ast)" "Dump Semantics       (--dump-semantics)" "Static Libc          (--static-libc)" "Disposed ASSERT      (--disposed=ASSERT)" "Disposed LOG         (--disposed=LOG)" "Double-Dispose ASSERT  (--double-dispose=ASSERT)" "Double-Dispose LOG     (--double-dispose=LOG)")
	local vBuildLabels=("JAR (default)" "Gradle" "ProGuard")
	local vBuildVals=("jar" "gradle" "proguard")
	local vCompiler="$CC"    # editable compiler override
	local vCcArgs="$CC_ARGS" # editable extra CC flags

	local vSection="tests"   # active section: tests | opts | build | compiler
	local vIdx=0              # cursor within active section
	local vViewOff=0          # first visible test row

	tput civis 2>/dev/null || true
	tput smcup 2>/dev/null || true

	render_tui() {
		local vTermH vW
		read vTermH vW <<< "$(get_term_size)"
		(( vW > 64 )) && vW=62 || vW=$(( vW - 2 ))
		# Fixed 27 lines: title(1) dsep(1) tests-hdr(1) sep(1) sum1(1) sum2(1) blank(1)=7
		# opts-hdr(1) sep(1) 9opts(9) blank(1)=12  build-hdr(1) sep(1) 3builds(3) blank(1)=5
		# compiler-hdr(1) sep(1) 2fields(2) blank(1)=5  dsep(1) hints(1)=2  total ≈ 35

		# Guard: minimum rows required
		local kMinH=35
		if (( vTermH < kMinH )); then
			local vRows vCols; read vRows vCols <<< "$(get_term_size)"
			(( vCols > 64 )) && vCols=62 || vCols=$(( vCols - 2 ))
			(( vCols < 1 )) && vCols=1
			local vBlank; printf -v vBlank '%*s' "$vCols" ''; vBlank="${vBlank// / }"
			printf '\033[H\033[2J'
			printf " ${YELLOW}Terminal too small — need at least %d rows (current: %d)${NC}\n" "$kMinH" "$vTermH"
			local vi; for (( vi=1; vi<vRows; vi++ )); do printf '%s\n' "$vBlank"; done
			printf '%s' "$vBlank"
			return
		fi

		# Move to top-left; clear-to-end erases sub-screen residue when switching panels
		printf '\033[H\033[J'
		local vSep; printf -v vSep '%*s' "$vW" ''; vSep="${vSep// /─}"
		local vDSep; printf -v vDSep '%*s' "$vW" ''; vDSep="${vDSep// /═}"

		local vSel=0
		for ((i=0; i<vCount; i++)); do [[ ${vTestOn[$i]} -eq 1 ]] && ((vSel++)) || true; done

		printf " ${CYAN}KotlinToC Test Runner  ─  Interactive Mode  [%s]${NC}\n" "$vCompiler"
		printf " ${GRAY}%s${NC}\n" "$vDSep"
		# TESTS header with Space=select hint when active
		local vTPtr=" "; [[ "$vSection" == "tests" ]] && vTPtr="►"
		local vTHdr; printf -v vTHdr " %s TESTS (%d found, %d selected)" "$vTPtr" "$vCount" "$vSel"
		[[ "$vSection" == "tests" ]] && vTHdr+="   Space=select"
		printf "${YELLOW}%s${NC}\n" "$vTHdr"
		printf " ${GRAY}%s${NC}\n" "$vSep"
		# Compact 2-line test summary
		local vMaxSumW=$(( vW - 2 ))
		local vSelNames=()
		for ((i=0; i<vCount; i++)); do [[ ${vTestOn[$i]} -eq 1 ]] && vSelNames+=("${vTestNames[$i]}"); done
		local vSumFull
		if [[ ${#vSelNames[@]} -eq 0 ]]; then
			vSumFull="(none selected)"
		elif [[ ${#vSelNames[@]} -eq $vCount ]]; then
			vSumFull="All $vCount tests selected"
		else
			vSumFull="$(IFS=', '; echo "${vSelNames[*]}")"
		fi
		local vSumLine1="" vSumLine2=""
		if [[ ${#vSumFull} -le $vMaxSumW ]]; then
			vSumLine1="$vSumFull"
		else
			local vCut=$vMaxSumW
			while (( vCut >= 2 )); do
				[[ "${vSumFull:$(( vCut - 2 )):2}" == ", " ]] && break
				(( vCut-- )) || true
			done
			(( vCut < 2 )) && vCut=$vMaxSumW
			vSumLine1="${vSumFull:0:$vCut}"
			local vRest="${vSumFull:$(( vCut + 2 ))}"
			if [[ ${#vRest} -le $vMaxSumW ]]; then
				vSumLine2="$vRest"
			else
				vSumLine2="${vRest:0:$(( vMaxSumW - 3 ))}..."
			fi
		fi
		printf " ${GRAY}  %s${NC}\n" "$vSumLine1"
		printf " ${GRAY}  %s${NC}\n" "$vSumLine2"

		printf "\n"
		printf " ${YELLOW}OPTIONS${NC}\n"
		printf " ${GRAY}%s${NC}\n" "$vSep"
		local vOptCount=${#vOptLabels[@]}
		for ((i=0; i<vOptCount; i++)); do
			local vPtr=" "; local vFg="$GRAY"
			[[ "$vSection" == "opts" && $i -eq $vIdx ]] && vPtr="►" && vFg="$WHITE"
			local vBox="[ ]"; [[ ${vOptOn[$i]} -eq 1 ]] && vBox="[✓]"
			printf " ${vFg}%s %s %s${NC}\n" "$vPtr" "$vBox" "${vOptLabels[$i]}"
		done

		printf "\n"
		printf " ${YELLOW}BUILD MODE${NC}\n"
		printf " ${GRAY}%s${NC}\n" "$vSep"
		for ((i=0; i<3; i++)); do
			local vPtr=" "; local vFg="$GRAY"
			[[ "$vSection" == "build" && $i -eq $vIdx ]] && vPtr="►" && vFg="$WHITE"
			local vBox="( )"; [[ ${vBuildOn[$i]} -eq 1 ]] && vBox="(•)"
			printf " ${vFg}%s %s %s${NC}\n" "$vPtr" "$vBox" "${vBuildLabels[$i]}"
		done

		printf "\n"
		printf " ${YELLOW}COMPILER & FLAGS${NC}\n"
		printf " ${GRAY}%s${NC}\n" "$vSep"
		local vFieldLabels=("Compiler" "CC Args ")
		local vFieldVals=("$vCompiler" "$vCcArgs")
		for ((i=0; i<2; i++)); do
			local vPtr=" "; local vFg="$GRAY"
			[[ "$vSection" == "compiler" && $i -eq $vIdx ]] && vPtr="►" && vFg="$WHITE"
			local vVal="${vFieldVals[$i]}"; [[ -z "$vVal" ]] && vVal="(none)"
			local vHint=""; [[ "$vSection" == "compiler" && $i -eq $vIdx ]] && vHint="  Space=edit"
			printf " ${vFg}%s  %s:  %s%s${NC}\n" "$vPtr" "${vFieldLabels[$i]}" "$vVal" "$vHint"
		done

		# Hints bar — truncated to terminal width to prevent wrapping/scroll corruption
		local vHints="↑↓ Move   ◄► Panel   Space Toggle/Edit   A All   N None   Enter Run   Q Quit"
		local vMaxW=$(( vW - 2 ))
		(( ${#vHints} > vMaxW )) && vHints="${vHints:0:$vMaxW}" || true
		printf "\n"
		printf " ${GRAY}%s${NC}\n" "$vDSep"
		printf " ${GRAY}%s${NC}\n" "$vHints"
		# Fill rows below the fixed lines with blanks
		local vBlankRow; printf -v vBlankRow '%*s' "$vW" ''; vBlankRow="${vBlankRow// / }"
		local vFixedLines=34
		local vFillCount=$(( vTermH - vFixedLines - 1 ))
		(( vFillCount < 0 )) && vFillCount=0 || true
		local vi; for (( vi=0; vi < vFillCount; vi++ )); do printf '%s\n' "$vBlankRow"; done
		printf '%s' "$vBlankRow"
		printf '\033[J'
	}

	adjust_view() { return; }  # no-op: tests are now in a separate sub-screen

	# Inline alt-screen field editor. ESC cancels, Enter confirms.
	# Sets vEditResult on confirm. Shows CC Args cheatsheet when inShowCheat=true.
	edit_field_inline() {
		local inLabel="$1"
		local inCurrent="$2"
		local inShowCheat="${3:-false}"
		local vBuf="$inCurrent"

		while true; do
			printf '\033[H\033[2J'
			printf " ${CYAN}Edit: %s${NC}\n" "$inLabel"
			printf " ${GRAY}Current: %s${NC}\n\n" "$inCurrent"
			printf " ${WHITE}> %s${GRAY}_%s\n\n" "$vBuf" "${NC}"
			printf " ${GRAY}Enter=confirm   Esc=cancel   Backspace=delete${NC}\n"

			if [[ "$inShowCheat" == true ]]; then
				printf "\n"
				printf " ${YELLOW}CC ARGS CHEATSHEET${NC}\n"
				printf " ${GRAY}  -g                  debug symbols${NC}\n"
				printf " ${GRAY}  -O0                 no optimization${NC}\n"
				printf " ${GRAY}  -O2 / -O3           optimize binary${NC}\n"
				printf " ${GRAY}  -Wall               enable warnings${NC}\n"
				printf " ${GRAY}  -fsanitize=address  AddressSanitizer${NC}\n"
				printf " ${GRAY}  -DDEBUG             debug define${NC}\n"
			fi

			local vRaw
			IFS= read -rsn1 vRaw

			if [[ "$vRaw" == $'\x1b' ]]; then
				local vSeq; IFS= read -rsn2 $_KT_TMOUT vSeq 2>/dev/null || true
				[[ -z "$vSeq" ]] && return 1 || true  # pure ESC = cancel; arrow keys ignored
			elif [[ "$vRaw" == "" || "$vRaw" == $'\r' ]]; then
				vEditResult="$vBuf"; return 0
			elif [[ "$vRaw" == $'\x7f' || "$vRaw" == $'\x08' ]]; then
				[[ ${#vBuf} -gt 0 ]] && vBuf="${vBuf%?}" || true
			elif [[ "$vRaw" == $'\x03' ]]; then
				return 1
			else
				local vOrd; vOrd=$(printf '%d' "'$vRaw" 2>/dev/null || echo 0)
				(( vOrd >= 32 )) && vBuf+="$vRaw" || true
			fi
		done
	}

	# Full-screen test selector sub-screen. Space=toggle, A/N=all/none, Enter/Esc=back.
	select_tests_screen() {
		local vSIdx=0 vSOff=0
		while true; do
			local vTermH vW
			read vTermH vW <<< "$(get_term_size)"
			(( vW > 64 )) && vW=62 || vW=$(( vW - 2 ))
			# Fixed: title(1) sep(1) scroll(1) blank(1) dsep(1) hints(1) = 6
			local vViewH=$(( vTermH - 6 ))
			(( vViewH < 1 )) && vViewH=1
			(( vSIdx < vSOff )) && vSOff=$vSIdx
			(( vSIdx >= vSOff + vViewH )) && vSOff=$(( vSIdx - vViewH + 1 )) || true

			local vSel=0
			for ((i=0; i<vCount; i++)); do [[ ${vTestOn[$i]} -eq 1 ]] && ((vSel++)) || true; done
			local vSep; printf -v vSep '%*s' "$vW" ''; vSep="${vSep// /─}"
			local vDSep; printf -v vDSep '%*s' "$vW" ''; vDSep="${vDSep// /═}"

			printf '\033[H\033[2J'
			printf " ${CYAN}SELECT TESTS  (%d found, %d selected)${NC}\n" "$vCount" "$vSel"
			printf " ${GRAY}%s${NC}\n" "$vSep"
			local vEnd=$(( vSOff + vViewH ))
			[[ $vEnd -gt $vCount ]] && vEnd=$vCount
			for ((i=vSOff; i<vEnd; i++)); do
				local vPtr=" "; local vFg="$GRAY"
				[[ $i -eq $vSIdx ]] && vPtr="►" && vFg="$WHITE"
				local vBox="[ ]"; [[ ${vTestOn[$i]} -eq 1 ]] && vBox="[✓]"
				printf " ${vFg}%s %s %s${NC}\n" "$vPtr" "$vBox" "${vTestNames[$i]}"
			done
			printf " ${GRAY}  ↕ %d–%d / %d${NC}\n" "$(( vSOff + 1 ))" "$vEnd" "$vCount"
			printf "\n"
			printf " ${GRAY}%s${NC}\n" "$vDSep"
			local vHints=" ↑↓ Move   Space Toggle   A All   N None   Enter/Esc Back"
			local vMaxW=$(( vW - 2 ))
			(( ${#vHints} > vMaxW )) && vHints="${vHints:0:$vMaxW}" || true
			printf " ${GRAY}%s${NC}\n" "$vHints"
			printf '\033[J'

			local vKey; vKey=$(tui_read_key)
			case "$vKey" in
				up)    (( vSIdx > 0 ))          && (( vSIdx-- )) || true ;;
				down)  (( vSIdx < vCount - 1 )) && (( vSIdx++ )) || true ;;
				space) [[ ${vTestOn[$vSIdx]} -eq 1 ]] && vTestOn[$vSIdx]=0 || vTestOn[$vSIdx]=1 ;;
				enter|esc) return ;;
				"char:a"|"char:A") for ((i=0; i<vCount; i++)); do vTestOn[$i]=1; done ;;
				"char:n"|"char:N") for ((i=0; i<vCount; i++)); do vTestOn[$i]=0; done ;;
			esac
		done
	}

	# Clamps cursor to valid range when switching into a new section.
	clamp_idx() {
		case "$vSection" in
			tests)    vIdx=0 ;;
			opts)     (( vIdx >= 9 )) && vIdx=8 || true ;;
			build)    (( vIdx >= 3 )) && vIdx=2 || true ;;
			compiler) (( vIdx >= 2 )) && vIdx=1 || true ;;
		esac
	}

	render_tui
	local vResult=1

	while true; do
		local vKey; vKey=$(tui_read_key)
		case "$vKey" in
			up)
				case "$vSection" in
					tests)    vSection="compiler"; vIdx=1 ;;
					opts)
						if [[ $vIdx -gt 0 ]]; then ((vIdx--))
						else vSection="tests"; vIdx=0; fi ;;
					build)
						if [[ $vIdx -gt 0 ]]; then ((vIdx--))
						else vSection="opts"; vIdx=8; fi ;;
					compiler)
						if [[ $vIdx -gt 0 ]]; then ((vIdx--))
						else vSection="build"; vIdx=2; fi ;;
				esac
				adjust_view; render_tui ;;
			down)
				case "$vSection" in
					tests)    vSection="opts"; vIdx=0 ;;
					opts)
						if (( vIdx < 8 )); then ((vIdx++))
						else vSection="build"; vIdx=0; fi ;;
					build)
						if (( vIdx < 2 )); then ((vIdx++))
						else vSection="compiler"; vIdx=0; fi ;;
					compiler)
						if (( vIdx < 1 )); then ((vIdx++))
						else vSection="tests"; vIdx=0; fi ;;
				esac
				adjust_view; render_tui ;;
			right)
				case "$vSection" in
					tests)    vSection="opts" ;;
					opts)     vSection="build" ;;
					build)    vSection="compiler" ;;
					compiler) vSection="tests" ;;
				esac
				clamp_idx; adjust_view; render_tui ;;
			left)
				case "$vSection" in
					tests)    vSection="compiler" ;;
					opts)     vSection="tests" ;;
					build)    vSection="opts" ;;
					compiler) vSection="build" ;;
				esac
				clamp_idx; adjust_view; render_tui ;;
		space)
			case "$vSection" in
				tests) select_tests_screen; render_tui ;;
				opts)  [[ ${vOptOn[$vIdx]}  -eq 1 ]] && vOptOn[$vIdx]=0  || vOptOn[$vIdx]=1; render_tui ;;
				build) vBuildOn=(0 0 0); vBuildOn[$vIdx]=1; render_tui ;;
				compiler)
					local vEditResult=""
					if [[ $vIdx -eq 0 ]]; then
						edit_field_inline "Compiler" "$vCompiler" false && vCompiler="$vEditResult"
					else
						edit_field_inline "CC Args" "$vCcArgs" true && vCcArgs="$vEditResult"
					fi
					render_tui ;;
			esac ;;
			enter) vResult=0; break ;;
			esc)   vResult=1; break ;;
			"char:a"|"char:A") for ((i=0; i<vCount; i++)); do vTestOn[$i]=1; done; render_tui ;;
			"char:n"|"char:N") for ((i=0; i<vCount; i++)); do vTestOn[$i]=0; done; render_tui ;;
			"char:q"|"char:Q") vResult=1; break ;;
		esac
	done

	tput rmcup 2>/dev/null || true
	tput cnorm 2>/dev/null || true

	if [[ $vResult -ne 0 ]]; then return 1; fi

	TUI_TESTS=()
	for ((i=0; i<vCount; i++)); do
		[[ ${vTestOn[$i]} -eq 1 ]] && TUI_TESTS+=("${vTestNames[$i]}")
	done
	if [[ ${#TUI_TESTS[@]} -eq 0 ]]; then return 1; fi

	TUI_SKIP_UNIT="${vOptOn[0]}"
	TUI_EXTRA_ARGS=""
	[[ ${vOptOn[1]} -eq 1 ]] && TUI_EXTRA_ARGS+=" --mem-track"
	[[ ${vOptOn[2]} -eq 1 ]] && TUI_EXTRA_ARGS+=" --ast"
	[[ ${vOptOn[3]} -eq 1 ]] && TUI_EXTRA_ARGS+=" --dump-semantics"
	[[ ${vOptOn[4]} -eq 1 ]] && TUI_STATIC_LIBC=true || TUI_STATIC_LIBC=""
	if [[ ${vOptOn[5]} -eq 1 ]]; then TUI_EXTRA_ARGS+=" --disposed=ASSERT"
	elif [[ ${vOptOn[6]} -eq 1 ]]; then TUI_EXTRA_ARGS+=" --disposed=LOG"; fi
	if [[ ${vOptOn[7]} -eq 1 ]]; then TUI_EXTRA_ARGS+=" --double-dispose=ASSERT"
	elif [[ ${vOptOn[8]} -eq 1 ]]; then TUI_EXTRA_ARGS+=" --double-dispose=LOG"; fi

	for ((i=0; i<3; i++)); do
		[[ ${vBuildOn[$i]} -eq 1 ]] && TUI_BUILD="${vBuildVals[$i]}" && break
	done
	TUI_COMPILER="$vCompiler"
	TUI_CC_ARGS="$vCcArgs"
	return 0
}

# ==================
# MARK: Entry point
# ==================

info "Using C compiler: $CC"

# ── Interactive mode ─────────────────────────────────────────────
if [[ "$INTERACTIVE" == true ]]; then
	TUI_TESTS=(); TUI_SKIP_UNIT=0; TUI_EXTRA_ARGS=""; TUI_BUILD="jar"; TUI_COMPILER=""; TUI_CC_ARGS=""; TUI_STATIC_LIBC=""
	if ! run_interactive; then
		echo " No tests selected."; exit 0
	fi
	BUILD="$TUI_BUILD"
	EXTRA_ARGS="$TUI_EXTRA_ARGS"
	CC="$TUI_COMPILER"
	CC_ARGS="$TUI_CC_ARGS"
	STATIC_LIBC="$TUI_STATIC_LIBC"
	GRADLE_JAR="$([ "$REBUILD" = true ] && echo "clean jar" || echo "jar")"
	GRADLE_PRO="$([ "$REBUILD" = true ] && echo "clean proguard" || echo "proguard")"

	echo ""
	invoke_build

	if [[ ${#TUI_TESTS[@]} -eq 1 ]]; then
		vName="${TUI_TESTS[0]}"
		vSrc="$TESTS_DIR/$vName"
		if [[ ! -d "$vSrc" ]]; then echo "ERROR: test directory not found: $vSrc"; exit 1; fi
		invoke_test "$vName" "$vSrc" "$vSrc/out" "$EXTRA_ARGS" "$GUI_MODE"
		exit $?
	fi

	SUITE_PASSED=0; SUITE_FAILED=0; SUITE_FAILED_NAMES=(); SUITE_SKIPPED_COUNT=0; SUITE_SKIPPED_NAMES_ARR=()
	run_suite "$([ "$TUI_SKIP_UNIT" -eq 1 ] && echo true || echo false)" "${TUI_TESTS[@]}"
	show_summary
fi

# ── Single / multi-test run mode ─────────────────────────────────
if [[ -n "$RUN_TEST" ]]; then
	invoke_build

	IFS=',' read -ra vRunNames <<< "$RUN_TEST"
	vAnyFailed=false
	for vName in "${vRunNames[@]}"; do
		vName="${vName// /}"
		[[ -z "$vName" ]] && continue
		vSrc="$TESTS_DIR/$vName"
		if [[ ! -d "$vSrc" ]]; then
			echo "ERROR: test directory not found: $vSrc"
			echo "Available tests:"
			for d in "$TESTS_DIR"/*/; do [[ -d "$d" ]] && echo "  - $(basename "$d")"; done
			vAnyFailed=true; continue
		fi
		invoke_test "$vName" "$vSrc" "$vSrc/out" "$EXTRA_ARGS" "$GUI_MODE" || vAnyFailed=true
	done
	[[ "$vAnyFailed" == true ]] && exit 1 || exit 0
fi

# ── Full suite mode ───────────────────────────────────────────────
invoke_build

vAllNames=()
for d in "$TESTS_DIR"/*/; do [[ -d "$d" ]] && vAllNames+=("$(basename "$d")"); done
IFS=$'\n' vAllNames=($(sort <<< "${vAllNames[*]}")); unset IFS

SUITE_PASSED=0; SUITE_FAILED=0; SUITE_FAILED_NAMES=(); SUITE_SKIPPED_COUNT=0; SUITE_SKIPPED_NAMES_ARR=()
run_suite "$SKIP_UNIT" "${vAllNames[@]}"
show_summary
