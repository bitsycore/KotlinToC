#!/usr/bin/env python3
"""
run_tests.py — Build the transpiler, then run unit + integration tests.

Single cross-platform replacement for run_tests.ps1 / run_tests.sh.

Usage:
    python3 run_tests.py                            # Full suite (build + unit + integration)
    python3 run_tests.py --run HashMapTest          # Single test, verbose
    python3 run_tests.py --run Test1,Test2          # Multiple tests, verbose
    python3 run_tests.py --skip unit                # Skip unit tests
    python3 run_tests.py --list                     # Print all discovered tests and exit
    python3 run_tests.py --filter "stdlib/*"        # Run only tests matching glob
    python3 run_tests.py --exclude "Sdl3*"          # Exclude tests matching glob
    python3 run_tests.py --fail-fast                # Stop after first failure
    python3 run_tests.py --run game --gui           # Open window for interactive test
    python3 run_tests.py --run game --mem-track     # Pass --mem-track to transpiler
    python3 run_tests.py --run game --ast
    python3 run_tests.py --run game --dump-semantics
    python3 run_tests.py --run game --disposed ASSERT|LOG|NO
    python3 run_tests.py --run game --double-dispose ASSERT|LOG|NO
    python3 run_tests.py --clean                    # Remove all integration/*/out/ directories
    python3 run_tests.py --rebuild                  # Force clean rebuild of the JAR
    python3 run_tests.py --compiler clang           # Override C compiler (gcc/clang/cl/cc)
    python3 run_tests.py --cc-args "-O2 -g"
    python3 run_tests.py --cmake-args "-DFOO=BAR"
    python3 run_tests.py --cfg Debug                # CMake build type
    python3 run_tests.py --static-libc              # -DKTC_STATIC_LIBC=ON
    python3 run_tests.py --build jar|gradle|proguard
"""

import argparse
import fnmatch
import os
import re
import shutil
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field
from pathlib import Path

# ==================
# MARK: Constants
# ==================

kRoot       = Path(__file__).resolve().parent
kJar        = kRoot / "build" / "libs" / "KotlinToC-1.0-SNAPSHOT.jar"
kReleaseJar = kRoot / "build" / "libs" / "KotlinToC-1.0-SNAPSHOT-release.jar"
kTestsDir   = kRoot / "integration"
kIsWindows  = os.name == "nt"
kGradlew    = kRoot / ("gradlew.bat" if kIsWindows else "gradlew")
kExeSuffix  = ".exe" if kIsWindows else ""

# Enable ANSI on Windows 10+ terminals (Windows Terminal handles it automatically;
# legacy conhost needs the VT processing flag — harmless when already set).
if kIsWindows:
	try:
		import ctypes
		vKernel = ctypes.windll.kernel32
		vKernel.SetConsoleMode(vKernel.GetStdHandle(-11), 7)
	except Exception:
		pass

# Force UTF-8 on stdout/stderr — Windows defaults to cp1252 which can't encode
# many characters the transpiler emits (Unicode arrows, replacement chars, etc).
for vStream in (sys.stdout, sys.stderr):
	try:
		vStream.reconfigure(encoding="utf-8", errors="replace")
	except (AttributeError, ValueError):
		pass

# ANSI colors. Disabled when NO_COLOR env var is set or stdout is not a tty.
_gNoColor = os.environ.get("NO_COLOR") is not None or not sys.stdout.isatty()
kRed     = "" if _gNoColor else "\033[31m"
kGreen   = "" if _gNoColor else "\033[32m"
kYellow  = "" if _gNoColor else "\033[33m"
kDYellow = "" if _gNoColor else "\033[33m"
kCyan    = "" if _gNoColor else "\033[36m"
kGray    = "" if _gNoColor else "\033[90m"
kWhite   = "" if _gNoColor else "\033[97m"
kRst     = "" if _gNoColor else "\033[0m"

# ==================
# MARK: Print helpers
# ==================

def pwrite(inMsg: str = "") -> None:
	# Plain stdout writer with a trailing newline. Keeps output consistent
	# whether or not the caller already includes ANSI escapes.
	print(inMsg, flush=True)

def ppass(inMsg: str) -> None:
	pwrite(f"  {kGreen}PASS{kRst} {inMsg}")

def pfail(inMsg: str) -> None:
	pwrite(f"  {kRed}FAIL{kRst} {inMsg}")

def pskip(inMsg: str) -> None:
	pwrite(f"  {kYellow}SKIP{kRst} {inMsg}")

def pinfo(inMsg: str) -> None:
	pwrite(f"  {kCyan}----{kRst} {inMsg}")

def psection(inMsg: str) -> None:
	pwrite(f"\n{kYellow}=== {inMsg} ==={kRst}")

def pcmd(inMsg: str) -> None:
	pwrite(f"  {kDYellow}${kRst} {kWhite}{inMsg}{kRst}")

def format_ms(inMs: int) -> str:
	# "123ms" under a second, "1.23s" otherwise.
	if inMs < 1000:
		return f"{inMs}ms"
	return f"{inMs / 1000.0:.2f}s"

# ==================
# MARK: Test discovery
# ==================

@dataclass
class TestDir:
	# Discovered integration test directory.
	name:    str   # leaf directory name
	relPath: str   # path relative to integration/, slash-normalized
	fullPath: Path # absolute filesystem path

def find_test_dirs() -> list[TestDir]:
	# Walks integration/ and returns one entry per directory that contains a
	# module.ktc.toml. The results are sorted by relPath for stable order.
	vResults: list[TestDir] = []
	if not kTestsDir.is_dir():
		return vResults
	for vToml in kTestsDir.rglob("module.ktc.toml"):
		vDir = vToml.parent
		vRel = vDir.relative_to(kTestsDir).as_posix()
		vResults.append(TestDir(name=vDir.name, relPath=vRel, fullPath=vDir))
	vResults.sort(key=lambda inT: inT.relPath)
	return vResults

# ==================
# MARK: module.ktc.toml mini-parser
# ==================

@dataclass
class ModuleConfig:
	# Subset of module.ktc.toml that the runner cares about. Anything else
	# is ignored — full TOML parsing isn't needed for execution.
	executable:  str  = ""
	interactive: bool = False
	args:        str  = ""

# Regex for `key = "value"` and `key = true|false|N`. Lenient on whitespace.
kReStr  = re.compile(r'^\s*(?P<k>\w+)\s*=\s*"(?P<v>[^"]*)"')
kReBool = re.compile(r'^\s*(?P<k>\w+)\s*=\s*(?P<v>true|false)\b')

def read_module_toml(inPath: Path) -> ModuleConfig:
	# Parses the subset of module.ktc.toml fields used at runtime. Missing
	# file → default config. Quotes are required for string values.
	vCfg = ModuleConfig()
	if not inPath.is_file():
		return vCfg
	for vLine in inPath.read_text(encoding="utf-8", errors="replace").splitlines():
		vM = kReStr.match(vLine)
		if vM:
			vK, vV = vM.group("k"), vM.group("v")
			if   vK == "executable": vCfg.executable = vV
			elif vK == "args":       vCfg.args       = vV
			continue
		vM = kReBool.match(vLine)
		if vM and vM.group("k") == "interactive":
			vCfg.interactive = (vM.group("v") == "true")
	return vCfg

# ==================
# MARK: Compiler / cmake detection
# ==================

def find_c_compiler(inPreferred: str | None) -> str | None:
	# Returns inPreferred if it's on PATH, otherwise the first of the platform's
	# usual suspects. None if nothing is found.
	if inPreferred:
		return inPreferred if shutil.which(inPreferred) else None
	vCandidates = ["gcc", "clang", "cl"] if kIsWindows else ["gcc", "clang", "cc"]
	for vC in vCandidates:
		if shutil.which(vC):
			return vC
	return None

def find_cmake() -> str | None:
	return "cmake" if shutil.which("cmake") else None

def find_ccache() -> str | None:
	return "ccache" if shutil.which("ccache") else None

def find_ninja() -> str | None:
	# On Windows the Visual Studio generator is the default and handles the
	# Windows SDK automatically; Ninja would bypass that and pick gcc/clang
	# which can break complex projects (SDL3 etc.). Only auto-enable Ninja
	# on POSIX where it replaces the slower Makefile generator.
	if kIsWindows:
		return None
	return "ninja" if shutil.which("ninja") else None

# ==================
# MARK: Subprocess helpers
# ==================

@dataclass
class RunResult:
	# Captured result of a child process invocation. stdout and stderr are kept
	# separate so failure reporting can show normal output (e.g. test "ok"
	# markers) distinctly from error messages and stack traces — the latter get
	# colored red and printed after stdout in the suite output.
	exit:   int
	stdout: str
	stderr: str = ""
	ms:     int = 0

def run_capture(inCmd: list[str], inCwd: Path | None = None) -> RunResult:
	# Runs a command and captures stdout and stderr as separate strings. Python
	# uses internal threads to drain both pipes concurrently so we don't
	# deadlock on processes that write heavily to one stream.
	vStart = time.monotonic()
	vP = subprocess.run(
		inCmd,
		cwd=str(inCwd) if inCwd else None,
		stdout=subprocess.PIPE,
		stderr=subprocess.PIPE,
		encoding="utf-8",
		errors="replace",
	)
	vMs = int((time.monotonic() - vStart) * 1000)
	return RunResult(exit=vP.returncode, stdout=vP.stdout or "", stderr=vP.stderr or "", ms=vMs)

def run_streamed(inCmd: list[str], inCwd: Path | None = None) -> int:
	# Runs a command, streaming child stdout/stderr directly to ours. Used when
	# we want the user to see live output (GUI mode for interactive tests).
	vP = subprocess.run(inCmd, cwd=str(inCwd) if inCwd else None)
	return vP.returncode

def run_streamed_split(
	inCmd:    list[str],
	inCwd:    Path | None = None,
	inIndent: str         = "  ",
) -> RunResult:
	# Streams stdout live to the console (default color) while accumulating
	# stderr silently. The caller decides when/how to surface the stderr block
	# — typically AFTER stdout finishes, in red. This ordering avoids the
	# "error message at the top of the dump" issue caused by stderr being
	# unbuffered while stdout is block-buffered: live-printing both would let
	# the error appear before the success markers that ran earlier in time.
	# Two reader threads drain the pipes concurrently to avoid deadlocks.
	import threading as _th
	vStart = time.monotonic()
	vP = subprocess.Popen(
		inCmd,
		cwd=str(inCwd) if inCwd else None,
		stdout=subprocess.PIPE,
		stderr=subprocess.PIPE,
		encoding="utf-8",
		errors="replace",
		bufsize=1,
	)
	vOutLines: list[str] = []
	vErrLines: list[str] = []
	vLock = _th.Lock()
	def drain_live(inPipe, inSink: list[str]) -> None:
		# Stdout: print as each line arrives (preserves interactivity).
		for vRaw in inPipe:
			vLine = vRaw.rstrip("\r\n")
			inSink.append(vLine)
			with vLock:
				pwrite(f"{inIndent}{vLine}")
	def drain_silent(inPipe, inSink: list[str]) -> None:
		# Stderr: accumulate only; caller renders after stdout completes.
		for vRaw in inPipe:
			inSink.append(vRaw.rstrip("\r\n"))
	assert vP.stdout is not None and vP.stderr is not None
	vTOut = _th.Thread(target=drain_live,   args=(vP.stdout, vOutLines), daemon=True)
	vTErr = _th.Thread(target=drain_silent, args=(vP.stderr, vErrLines), daemon=True)
	vTOut.start(); vTErr.start()
	vP.wait()
	vTOut.join(); vTErr.join()
	vMs = int((time.monotonic() - vStart) * 1000)
	return RunResult(exit=vP.returncode, stdout="\n".join(vOutLines), stderr="\n".join(vErrLines), ms=vMs)

def run_streamed_capture(
	inCmd:        list[str],
	inCwd:        Path | None = None,
	inIndent:     str         = "  ",
	inColorWarn:  bool        = False,
	inDimAll:     bool        = False,
) -> RunResult:
	# Streams a command's combined stdout/stderr line-by-line to the console
	# while also accumulating it for post-hoc inspection. Used by the verbose
	# (--run) path so interactive tests show output as it happens instead of
	# only at exit, and so crashes leave the partial output on screen.
	#  inColorWarn: tint lines containing "warning:" yellow.
	#  inDimAll:    print every line in gray (used for tool output like cmake).
	vStart = time.monotonic()
	vP = subprocess.Popen(
		inCmd,
		cwd=str(inCwd) if inCwd else None,
		stdout=subprocess.PIPE,
		stderr=subprocess.STDOUT,
		encoding="utf-8",
		errors="replace",
		bufsize=1,  # line-buffered
	)
	vLines: list[str] = []
	assert vP.stdout is not None
	for vRawLine in vP.stdout:
		vLine = vRawLine.rstrip("\r\n")
		if   inColorWarn and "warning:" in vLine: pwrite(f"{inIndent}{kYellow}{vLine}{kRst}")
		elif inDimAll:                            pwrite(f"{inIndent}{kGray}{vLine}{kRst}")
		else:                                      pwrite(f"{inIndent}{vLine}")
		vLines.append(vLine)
	vP.wait()
	vMs = int((time.monotonic() - vStart) * 1000)
	return RunResult(exit=vP.returncode, stdout="\n".join(vLines), ms=vMs)

# ==================
# MARK: Clean
# ==================

# Per-clean failure list. Populated by the rmtree exception handler with
# (path, reason) tuples for each file/dir that couldn't be removed even after
# the read-only retry. cmd_clean() reports the contents at the end of its run.
_gCleanFailures: list[tuple[str, str]] = []

def _force_writable_and_retry(inFunc, inPath: str, inExc) -> None:
	# rmtree exception handler for Windows: cmake FetchContent dumps git pack
	# files (*.idx, *.pack) into _cmake/_deps/ that are marked read-only, so
	# os.unlink raises WinError 5 (Access denied). Strip the read-only bit and
	# retry the failing operation. Anything still failing after the retry is
	# recorded so cmd_clean can surface it instead of silently leaving residue.
	import stat
	try:
		os.chmod(inPath, stat.S_IWRITE)
		inFunc(inPath)
		return
	except Exception as vE:
		# inExc on Python 3.12+ is the exception object; on older versions it
		# was the (exc_type, exc_value, tb) triple. Render whichever cleanly.
		vOrig = inExc[1] if isinstance(inExc, tuple) and len(inExc) >= 2 else inExc
		_gCleanFailures.append((inPath, f"{type(vOrig).__name__}: {vOrig}  (retry: {vE})"))

def cmd_clean() -> int:
	# Removes the out/ directory inside every integration test directory.
	pwrite(f"{kCyan}Cleaning per-test output directories...{kRst}")
	vCleaned = 0
	# Python 3.12 renamed the rmtree callback from `onerror` to `onexc`; pick
	# whichever the current interpreter supports so the script works on both.
	vKw = "onexc" if sys.version_info >= (3, 12) else "onerror"
	_gCleanFailures.clear()
	for vT in find_test_dirs():
		vOut = vT.fullPath / "out"
		if not vOut.is_dir():
			continue
		vBefore = len(_gCleanFailures)
		shutil.rmtree(vOut, **{vKw: _force_writable_and_retry})
		if len(_gCleanFailures) == vBefore and not vOut.exists():
			pwrite(f"{kGray}  removed {vOut}{kRst}")
			vCleaned += 1
		else:
			# Something inside vOut couldn't be removed. Report it explicitly so
			# the user knows the test wasn't fully cleaned.
			pwrite(f"{kYellow}  partial  {vOut}{kRst}  {kGray}(see errors below){kRst}")
	pwrite(f"{kGreen}Cleaned {vCleaned} test output directories.{kRst}")
	if _gCleanFailures:
		pwrite("")
		pwrite(f"{kRed}{len(_gCleanFailures)} path(s) could not be removed:{kRst}")
		# Cap at 20 to keep the report bounded for catastrophic cases.
		for vPath, vReason in _gCleanFailures[:20]:
			pwrite(f"{kRed}  - {vPath}{kRst}")
			pwrite(f"{kGray}      {vReason}{kRst}")
		if len(_gCleanFailures) > 20:
			pwrite(f"{kGray}    ... and {len(_gCleanFailures) - 20} more{kRst}")
		pwrite("")
		pwrite(f"{kYellow}Hint:{kRst} a process may be holding the file open (running .exe,"
		       f" antivirus, indexer). Close it and retry, or delete the directory by hand.")
		return 1
	return 0

def cmd_init(inName: str) -> int:
	# Scaffold a new integration test directory with module.ktc.toml and a starter .kt file.
	# Place under integration/intrinsic/ by default; user can move it afterwards.
	vTestDir = kTestsDir / "intrinsic" / inName
	if vTestDir.exists():
		pwrite(f"{kRed}ERROR: test directory already exists: {vTestDir}{kRst}")
		return 1
	vTestDir.mkdir(parents=True)
	vToml = vTestDir / "module.ktc.toml"
	vToml.write_text("autoFindMain = true\n")
	vKt = vTestDir / f"{inName}.kt"
	vKt.write_text(
		f"package {inName}\n"
		f"\n"
		f"fun main() {{\n"
		f"\tprintln(\"Hello from {inName}\")\n"
		f"}}\n"
	)
	ppass(f"Created {vTestDir.relative_to(kRoot)}")
	pinfo(f"{vToml.relative_to(kRoot)}")
	pinfo(f"{vKt.relative_to(kRoot)}")
	return 0

def cmd_completions(inShell: str) -> int:
	# Generate a shell completion script for test names.
	vTests = find_test_dirs()
	vNames = " ".join(t.name for t in vTests)
	if inShell == "bash":
		print(f"""\
_run_tests_completions() {{
    local cur opts tests
    COMPREPLY=()
    cur="${{COMP_WORDS[COMP_CWORD]}}"
    opts="--run --skip --list --filter --exclude --fail-fast --build --rebuild --clean --compiler --cc-args --cmake-args --cfg --static-libc --gui --mem-track --ast --dump-semantics --strict --disposed --double-dispose --transpiler-args --bench -n --completions init"
    tests="{vNames}"
    case "${{COMP_WORDS[COMP_CWORD-1]}}" in
        --run|--bench|--filter|--exclude)
            COMPREPLY=( $(compgen -W "$tests" -- "$cur") )
            return 0 ;;
        --skip)
            COMPREPLY=( $(compgen -W "unit" -- "$cur") )
            return 0 ;;
        --build)
            COMPREPLY=( $(compgen -W "jar gradle proguard" -- "$cur") )
            return 0 ;;
        --completions)
            COMPREPLY=( $(compgen -W "bash zsh fish" -- "$cur") )
            return 0 ;;
    esac
    COMPREPLY=( $(compgen -W "$opts" -- "$cur") )
}}
complete -F _run_tests_completions run_tests.py
complete -F _run_tests_completions python3 run_tests.py""")
	elif inShell == "zsh":
		vTestList = " ".join(f"'{t.name}'" for t in vTests)
		print(f"""\
#compdef run_tests.py
_run_tests() {{
    local -a tests opts
    tests=({vTestList})
    opts=(--run --skip --list --filter --exclude --fail-fast --build --rebuild --clean --compiler --cc-args --cmake-args --cfg --static-libc --gui --mem-track --ast --dump-semantics --strict --disposed --double-dispose --transpiler-args --bench -n --completions init)
    case "$words[CURRENT-1]" in
        --run|--bench|--filter|--exclude) _describe 'test' tests ;;
        --skip) compadd unit ;;
        --build) compadd jar gradle proguard ;;
        --completions) compadd bash zsh fish ;;
        *) compadd "${{opts[@]}}" ;;
    esac
}}
compdef _run_tests run_tests.py""")
	elif inShell == "fish":
		print("# Fish completions for run_tests.py")
		print("complete -c run_tests.py -l run -x -a '(run_tests.py --list 2>/dev/null)'")
		print("complete -c run_tests.py -l bench -x -a '(run_tests.py --list 2>/dev/null)'")
		print("complete -c run_tests.py -l skip -x -a 'unit'")
		print("complete -c run_tests.py -l build -x -a 'jar gradle proguard'")
		print("complete -c run_tests.py -l completions -x -a 'bash zsh fish'")
		for opt in ["list", "fail-fast", "rebuild", "clean", "static-libc", "gui",
					"mem-track", "ast", "dump-semantics", "strict"]:
			print(f"complete -c run_tests.py -l {opt}")
	return 0

def cmd_bench(inTestQuery: str, inN: int, inAllTests: list, inOpts) -> int:
	# Run a test N times and report min/median/p95 timing stats.
	import statistics
	vMatches = resolve_test_query(inTestQuery, inAllTests)
	if not vMatches:
		pwrite(f"{kRed}ERROR: test not found: {inTestQuery}{kRst}")
		return 1
	vT = vMatches[0]
	pwrite(f"\n{kCyan}Benchmarking {vT.relPath} ({inN} iterations){kRst}")
	invoke_build(inOpts.buildMode, False)
	vKtcTimes  = []
	vCompTimes = []
	vRunTimes  = []
	for vI in range(inN):
		vR = invoke_test_verbose(vT, inOpts)
		if vR.status == "fail":
			pwrite(f"{kRed}  Iteration {vI + 1} FAILED — aborting benchmark{kRst}")
			return 1
		vKtcTimes.append(vR.ktcMs)
		vCompTimes.append(vR.compileMs)
		vRunTimes.append(vR.runMs)
		pwrite(f"  [{vI + 1}/{inN}] ktc: {format_ms(vR.ktcMs)}  comp: {format_ms(vR.compileMs)}  run: {format_ms(vR.runMs)}")
	def stats(inLabel: str, inVals: list[int]) -> None:
		vSorted = sorted(inVals)
		vMin    = vSorted[0]
		vMedian = statistics.median(vSorted)
		vP95Idx = max(0, int(len(vSorted) * 0.95) - 1)
		vP95    = vSorted[vP95Idx]
		vMean   = statistics.mean(vSorted)
		pwrite(f"  {inLabel:8s}  min: {format_ms(vMin):>8s}  median: {format_ms(int(vMedian)):>8s}  p95: {format_ms(vP95):>8s}  mean: {format_ms(int(vMean)):>8s}")
	pwrite(f"\n{kCyan}Results ({inN} iterations):{kRst}")
	stats("ktc",     vKtcTimes)
	stats("compile", vCompTimes)
	stats("run",     vRunTimes)
	return 0

def snapshot_kt_mtimes() -> dict[str, float]:
	# Collect mtime of every .kt file under src/ and integration/.
	vResult: dict[str, float] = {}
	for vDir in [kRoot / "src", kTestsDir]:
		if not vDir.is_dir():
			continue
		for vKt in vDir.rglob("*.kt"):
			vResult[str(vKt)] = vKt.stat().st_mtime
	return vResult

def cmd_watch(inRunFn) -> int:
	# Poll for .kt changes and re-run tests.
	vPrev = snapshot_kt_mtimes()
	pwrite(f"{kCyan}Watching for .kt changes (Ctrl+C to stop)...{kRst}")
	try:
		while True:
			time.sleep(1)
			vCurr = snapshot_kt_mtimes()
			vChanged = []
			for vPath, vMt in vCurr.items():
				if vPath not in vPrev or vPrev[vPath] != vMt:
					vChanged.append(Path(vPath).name)
			if vChanged:
				pwrite(f"\n{kYellow}Changed: {', '.join(vChanged[:5])}{kRst}")
				inRunFn()
				vPrev = snapshot_kt_mtimes()
	except KeyboardInterrupt:
		pwrite(f"\n{kCyan}Watch stopped.{kRst}")
	return 0

# ==================
# MARK: Build (Gradle / ProGuard)
# ==================

def invoke_build(inBuildMode: str, inRebuild: bool) -> None:
	# Runs the Gradle wrapper to produce the JAR (or ProGuard release JAR).
	# The "gradle" mode skips the JAR build entirely — `gradle run` will be
	# used at transpile time instead.
	if inBuildMode == "gradle":
		return
	if inBuildMode == "proguard":
		vTask = ["clean", "proguard"] if inRebuild else ["proguard"]
		vLabel = "ProGuard release JAR"
		vExpected = kReleaseJar
	else:
		vTask = ["clean", "jar"] if inRebuild else ["jar"]
		vLabel = "transpiler JAR"
		vExpected = kJar
	psection(f"Building {vLabel}")
	pcmd(f"{kGradlew.name} {' '.join(vTask)}")
	vRes = run_streamed([str(kGradlew), *vTask], inCwd=kRoot)
	if vRes != 0 or not vExpected.is_file():
		pwrite(f"{kRed}ERROR: build failed{kRst}")
		sys.exit(1)
	ppass(f"Built {vExpected}")

# ==================
# MARK: File-system helpers for the run loop
# ==================

def prepare_out_dir(inOut: Path) -> None:
	# Ensures out/ exists. We no longer wipe it pre-transpile: the transpiler
	# itself writes-only-on-change (preserving mtime for C compiler caching)
	# and prunes its own stale outputs at the end of each run.
	inOut.mkdir(parents=True, exist_ok=True)

def collect_kt_files(inDir: Path) -> list[Path]:
	# All .kt files under inDir, sorted for deterministic transpile order.
	return sorted(inDir.rglob("*.kt"))

def collect_c_sources(inOut: Path) -> list[Path]:
	# Discovers .c files in the transpile output. ktc.core/ktc_core.c goes first
	# (the runtime), then ktc/** sorted, then everything else (user package code)
	# sorted. Matches the ordering the PowerShell/bash versions used so direct
	# gcc invocations stay reproducible.
	vSources: list[Path] = []
	vKtcDir = inOut / "ktc"
	vCoreDir = inOut / "ktc.core"
	vCore = vCoreDir / "ktc_core.c"
	if vCore.is_file():
		vSources.append(vCore)
	if vKtcDir.is_dir():
		vSources.extend(sorted(vKtcDir.rglob("*.c")))
	for vC in sorted(inOut.rglob("*.c")):
		if vKtcDir in vC.parents or vCoreDir in vC.parents:
			continue
		vSources.append(vC)
	return vSources

def cmake_generator_matches(inBld: Path, inNinja: str | None) -> bool:
	# Check if the cached cmake generator matches the requested one.
	vCache = inBld / "CMakeCache.txt"
	if not vCache.is_file():
		return True
	vWant = "Ninja" if inNinja else None
	for vLine in vCache.read_text(errors="replace").splitlines():
		if vLine.startswith("CMAKE_GENERATOR:"):
			vCached = vLine.split("=", 1)[1].strip()
			if vWant and vWant not in vCached:
				return False
			if not vWant and "Ninja" in vCached:
				return False
			return True
	return True

def has_user_cmake(inOut: Path) -> bool:
	# True when this test needs cmake rather than direct gcc (presence of either
	# ktc_user.cmake or ktc_modules.cmake from the transpiler).
	return (inOut / "ktc_user.cmake").is_file() or (inOut / "ktc_modules.cmake").is_file()

def find_built_exe(inOut: Path, inExeName: str) -> Path | None:
	# After a CMake build, prefer <exeName>(.exe), then fall back to any
	# executable file directly under inOut that isn't a known artifact.
	vExpected = inOut / f"{inExeName}{kExeSuffix}"
	if vExpected.is_file():
		return vExpected
	for vCand in inOut.iterdir():
		if not vCand.is_file():
			continue
		vN = vCand.name
		if vN.startswith("_ktcrun") or vN in {"ktc_user.cmake", "ktc_modules.cmake"}:
			continue
		if vN.endswith((".dll", ".so", ".dylib", ".c", ".h", ".obj", ".o")):
			continue
		if kIsWindows:
			if vN.endswith(".exe"):
				return vCand
		else:
			if os.access(vCand, os.X_OK):
				return vCand
	return None

# ==================
# MARK: Runner options
# ==================

@dataclass
class RunOptions:
	# Aggregated CLI options that flow through the build/run pipeline.
	buildMode:   str        = "jar"         # jar | gradle | proguard
	compiler:    str        = ""
	ccArgs:      str        = ""
	cmakeArgs:   str        = ""
	cfg:         str        = "Release"
	staticLibc:  bool       = False
	gui:         bool       = False
	extraArgs:   list[str]  = field(default_factory=list)  # transpiler flags
	cc:          str        = ""                            # resolved compiler binary
	cmake:       str | None = None                          # resolved cmake binary or None
	ccache:      str | None = None                          # resolved ccache binary or None
	ninja:       str | None = None                          # resolved ninja binary or None

def transpile_cmd(inOpts: RunOptions, inKts: list[Path], inOut: Path, inExeName: str) -> list[str]:
	# Builds the argv for the transpile step. Two backends: the fat JAR
	# (default and proguard) or `gradle run --args=...` for in-place dev.
	if inOpts.buildMode == "gradle":
		vArgs = " ".join([*(str(k) for k in inKts), "-o", str(inOut), "--name", inExeName, *inOpts.extraArgs])
		return [str(kGradlew), "run", "--quiet", f"--args={vArgs}"]
	vJar = kReleaseJar if inOpts.buildMode == "proguard" else kJar
	return ["java", "-jar", str(vJar), *(str(k) for k in inKts), "-o", str(inOut), "--name", inExeName, *inOpts.extraArgs]

# ==================
# MARK: Single test (verbose, used by --run)
# ==================

@dataclass
class TestOutcome:
	# Outcome of one test in the suite.
	#  pass     — transpile + compile + run all succeeded.
	#  skip     — required toolchain/library missing (e.g. cmake or a system lib).
	#  fail     — any step failed.
	name:      str
	status:    str
	timing:    str   = ""
	warning:   str   = ""
	ktcMs:     int   = 0
	compileMs: int   = 0
	runMs:     int   = 0

def invoke_test_verbose(
	inTest:   TestDir,
	inOpts:   RunOptions,
) -> TestOutcome:
	# Runs one test with full per-step output. Used by --run mode. Returns the
	# outcome so the caller can compute the right exit code.
	vName = inTest.relPath
	vSrc  = inTest.fullPath
	vOut  = vSrc / "out"
	vCfg  = read_module_toml(vSrc / "module.ktc.toml")
	vExe  = vCfg.executable or inTest.name

	vKts = collect_kt_files(vSrc)
	if not vKts:
		pfail(f"{vName} — no .kt files in {vSrc}")
		return TestOutcome(name=vName, status="fail")
	prepare_out_dir(vOut)

	pinfo(f"Input:      {' '.join(k.name for k in vKts)}")
	pinfo(f"Output dir: {vOut}")

	# ── Transpile ─────────────────────────────────────────────────
	psection("Transpile")
	vCmd = transpile_cmd(inOpts, vKts, vOut, vExe)
	# Friendly cmd line: shortened JAR name and just .kt leaf names.
	vJarLbl = "KotlinToC-release.jar" if inOpts.buildMode == "proguard" else "KotlinToC.jar"
	vSuf = (" " + " ".join(inOpts.extraArgs)) if inOpts.extraArgs else ""
	if inOpts.buildMode == "gradle":
		pcmd(f"gradlew run --args=\"{' '.join(k.name for k in vKts)} -o {vOut} --name {vExe}{vSuf}\"")
	else:
		pcmd(f"java -jar {vJarLbl} {' '.join(k.name for k in vKts)} -o {vOut} --name {vExe}{vSuf}")
	pwrite()
	vTr = run_streamed_capture(vCmd, inCwd=kRoot, inColorWarn=True)
	pwrite()
	if vTr.exit != 0:
		pfail(f"Transpilation failed (exit {vTr.exit})")
		return TestOutcome(name=vName, status="fail")
	pwrite(f"  {kGreen}PASS{kRst} Transpilation succeeded  {kGray}(ktc: {format_ms(vTr.ms)}){kRst}")

	# Carry ktc_user.cmake from src to out (preserved across rebuilds).
	vSrcUserCmake = vSrc / "ktc_user.cmake"
	if vSrcUserCmake.is_file():
		shutil.copy2(vSrcUserCmake, vOut / "ktc_user.cmake")

	vCSrcs = collect_c_sources(vOut)
	if not vCSrcs and not has_user_cmake(vOut):
		pfail("No .c files generated")
		return TestOutcome(name=vName, status="fail")

	# ── Compile ───────────────────────────────────────────────────
	vUseCmake = has_user_cmake(vOut)
	if vUseCmake and not inOpts.cmake:
		pskip(f"{vName} — ktc_user.cmake/ktc_modules.cmake requires cmake (not on PATH)")
		return TestOutcome(name=vName, status="skip")

	vExePath: Path
	vCompMs = 0
	if vUseCmake:
		# Two-step CMake: configure (-B _cmake) then build (--build _cmake).
		vBld = vOut / "_cmake"
		if not cmake_generator_matches(vBld, inOpts.ninja):
			shutil.rmtree(vBld, onerror=_force_writable_and_retry)
		vCfgArgs = ["-B", str(vBld), "-S", str(vOut), f"-DCMAKE_BUILD_TYPE={inOpts.cfg}"]
		if inOpts.ninja:
			vCfgArgs.extend(["-G", "Ninja"])
		if inOpts.compiler:
			vCfgArgs.append(f"-DCMAKE_C_COMPILER={inOpts.compiler}")
		elif inOpts.ninja and inOpts.cc:
			vCfgArgs.append(f"-DCMAKE_C_COMPILER={inOpts.cc}")
		if inOpts.ccache:
			vCfgArgs.append(f"-DCMAKE_C_COMPILER_LAUNCHER={inOpts.ccache}")
		if inOpts.staticLibc:
			vCfgArgs.append("-DKTC_STATIC_LIBC=ON")
		if inOpts.cmakeArgs:
			vCfgArgs.extend(inOpts.cmakeArgs.split())
		# CMake Configure: capture silently — the "-- Configuring done" boilerplate
		# is noise on success. Dump it only when configure fails so the user can
		# diagnose missing-library / wrong-flag errors.
		psection("CMake Configure")
		pcmd(f"cmake {' '.join(vCfgArgs)}")
		vConfig = run_capture([inOpts.cmake, "--log-level=WARNING", *vCfgArgs])
		if vConfig.exit != 0:
			pwrite()
			for vLine in vConfig.stdout.splitlines():
				pwrite(f"  {kGray}{vLine}{kRst}")
			for vLine in vConfig.stderr.splitlines():
				pwrite(f"  {kRed}{vLine}{kRst}")
			pwrite()
			vCfgAll = vConfig.stdout + "\n" + vConfig.stderr
			if re.search(r"Could not find|not found|NOTFOUND", vCfgAll, re.IGNORECASE):
				pskip(f"{vName} — required library not found")
				return TestOutcome(name=vName, status="skip")
			pfail(f"CMake configure failed (exit {vConfig.exit})")
			return TestOutcome(name=vName, status="fail")
		pwrite(f"  {kGreen}PASS{kRst} CMake configure OK  {kGray}(cfg: {format_ms(vConfig.ms)}){kRst}")

		# CMake Build: stream so the user sees per-file progress on long builds
		# (Sdl3Test in particular). `--parallel` activates ninja/make's parallel
		# build; cmake's own progress prefix ("[ 12/47] Building C object …")
		# gives a clear visual of how much is left.
		psection("CMake Build")
		vJobs = max(1, (os.cpu_count() or 4))
		vBuildCmd = [inOpts.cmake, "--build", str(vBld), "--config", inOpts.cfg, "--parallel", str(vJobs)]
		pcmd(f"cmake --build {vBld.name} --config {inOpts.cfg} --parallel {vJobs}")
		pwrite()
		vBuild = run_streamed_capture(vBuildCmd, inDimAll=True)
		pwrite()
		if vBuild.exit != 0:
			pfail(f"CMake build failed (exit {vBuild.exit})")
			return TestOutcome(name=vName, status="fail")
		vFound = find_built_exe(vOut, vExe)
		if not vFound:
			pfail("No executable found after cmake build")
			return TestOutcome(name=vName, status="fail")
		vExePath = vFound
		vCompMs = vConfig.ms + vBuild.ms
		pwrite(f"  {kGreen}PASS{kRst} CMake build OK -> {vExePath}  {kGray}(build: {format_ms(vBuild.ms)}){kRst}")
	else:
		# Direct compile with collected .c sources.
		vExeName = f"{vExe}{kExeSuffix}"
		vExePath = vOut / vExeName
		# On Unix the binary name may collide with a same-named package dir;
		# CMake-less builds emit straight into the dir, so add .out as fallback.
		if not kIsWindows and (vOut / vExe).is_dir():
			vExePath = vOut / f"{vExe}.out"
		psection("Compile")
		vCArgs = ["-std=c11", "-iquote", str(vOut), "-o", str(vExePath)]
		if inOpts.ccArgs:
			vCArgs.extend(inOpts.ccArgs.split())
		vCArgs.extend(str(c) for c in vCSrcs)
		vCcCmd = [inOpts.ccache, inOpts.cc] if inOpts.ccache else [inOpts.cc]
		pcmd(f"{' '.join(vCcCmd)} -std=c11{(' ' + inOpts.ccArgs) if inOpts.ccArgs else ''} -o {vExePath} {' '.join(c.name for c in vCSrcs)}")
		pwrite()
		vCo = run_streamed_capture([*vCcCmd, *vCArgs], inDimAll=True)
		pwrite()
		if vCo.exit != 0:
			pfail(f"Compilation failed (exit {vCo.exit})")
			return TestOutcome(name=vName, status="fail")
		vCompMs = vCo.ms
		pwrite(f"  {kGreen}PASS{kRst} Compilation succeeded -> {vExePath}  {kGray}(comp: {format_ms(vCo.ms)}){kRst}")

	# ── Generated files listing ──────────────────────────────────
	# Lists transpiler output and the final executable — skips the _cmake/
	# build cache (FetchContent deps like SDL3 dump thousands of files there
	# that aren't useful to surface in test output).
	psection("Generated Files")
	for vFile in sorted(vOut.rglob("*")):
		if not vFile.is_file():
			continue
		vRel = vFile.relative_to(vOut)
		if vRel.parts and vRel.parts[0] == "_cmake":
			continue
		vSz = vFile.stat().st_size
		vSize = f"{vSz / 1024:.1f} KB" if vSz >= 1024 else f"{vSz} B"
		pinfo(f"{str(vRel):<30} {vSize:>10}")

	# ── Run ──────────────────────────────────────────────────────
	psection("Run")
	pcmd(str(vExePath))
	pwrite()
	vRunArgs: list[str] = []
	if vCfg.interactive and not inOpts.gui:
		vRunArgs = ["--skip-interaction"]
	elif vCfg.args:
		vRunArgs = vCfg.args.split()
	if vRunArgs:
		pinfo(f"Test args: {' '.join(vRunArgs)}")

	if inOpts.gui and vCfg.interactive:
		# Full passthrough: child owns the terminal. Used for interactive windows.
		vStart = time.monotonic()
		vRExit = run_streamed([str(vExePath), *vRunArgs])
		vRMs = int((time.monotonic() - vStart) * 1000)
		vCaptured = ""
	else:
		# Stream stdout live; buffer stderr and render it (red) AFTER stdout
		# completes. stderr is unbuffered and stdout is block-buffered when
		# piped, so live-printing both would surface the error message above
		# the success markers that actually ran first — confusing on a fail.
		vRunRes   = run_streamed_split([str(vExePath), *vRunArgs])
		vRExit    = vRunRes.exit
		vRMs      = vRunRes.ms
		vCaptured = vRunRes.stdout + "\n" + vRunRes.stderr
		# Stderr block: print after stdout, in red. Preserves the chronological
		# order of execution (stdout first → stderr at exit).
		for vELine in vRunRes.stderr.splitlines():
			if vELine.strip():
				pwrite(f"  {kRed}{vELine}{kRst}")
		pwrite()

	if vRExit != 0:
		pfail(f"Runtime error (exit {vRExit})")
		return TestOutcome(name=vName, status="fail")

	vLeak = "leaked" in vCaptured
	if vLeak:
		pwrite(f"  {kDYellow}PASS{kRst} Program exited - memory leaks detected  {kRed}LEAK{kRst}  {kGray}(run: {format_ms(vRMs)}){kRst}")
	else:
		pwrite(f"  {kGreen}PASS{kRst} Program exited successfully (code 0)  {kGray}(run: {format_ms(vRMs)}){kRst}")
	return TestOutcome(name=vName, status="pass", ktcMs=vTr.ms, compileMs=vCompMs, runMs=vRMs)

# ==================
# MARK: Single test (concise, used by parallel suite)
# ==================

# Number of lines kept from the tail of a captured output stream when surfacing
# a build/transpile failure. Generous enough that an error and a short stack
# trace both fit, even when the tool printed routine status lines afterwards.
kFailTailLines = 30

# Cap for full-output dumps (runtime errors). Sanity bound so a runaway test
# that printed thousands of lines doesn't flood the suite output.
kFailFullCap   = 250

def _tail(inText: str, inLines: int = kFailTailLines) -> list[str]:
	vAll = inText.splitlines()
	return vAll[-inLines:] if len(vAll) > inLines else vAll

def _full(inText: str, inCap: int = kFailFullCap) -> list[str]:
	# Returns all captured lines up to inCap, with a marker noting any elision.
	# Used for runtime-error reporting where the actual error message can appear
	# ANYWHERE in the stream — typically near the top when error() printed to
	# stderr but stdout was block-buffered and flushed at exit.
	vAll = inText.splitlines()
	if len(vAll) <= inCap:
		return vAll
	return [f"[+{len(vAll) - inCap} earlier line(s) elided]"] + vAll[-inCap:]

# ==================
# MARK: Live progress renderer
# ==================

import threading  # noqa: E402

class LiveProgress:
	# Thread-safe live multi-line renderer for the parallel suite.
	#
	# Maintains two areas on screen:
	#  - History (scrollback): completed test lines with their [N/total]
	#    counter. Printed once and never re-touched.
	#  - Live area: currently-running tests, one row each, redrawn in place
	#    as their phase timings come in (ktc, comp, run).
	#
	# Rendering uses ANSI cursor moves so it only works on a real tty;
	# when stdout is piped/redirected the class still records state but
	# only emits the final per-test line (no in-place updates).

	def __init__(self, inTotal: int) -> None:
		self.total       = inTotal
		self.completed   = 0
		self.active:     dict[str, str] = {}  # ordered name → current status text
		self.lineCount   = 0                  # rows currently drawn in the live area
		self.lock        = threading.Lock()
		self.isLive      = sys.stdout.isatty() and not _gNoColor
		self.width       = len(str(inTotal))

	def _erase_active(self) -> None:
		# Moves the cursor back to the start of the live area and clears
		# everything from there to the end of the screen. Only meaningful
		# when we've drawn rows since the last clear.
		if self.lineCount > 0:
			sys.stdout.write(f"\033[{self.lineCount}F\033[J")
			sys.stdout.flush()
			self.lineCount = 0

	def _draw_active(self) -> None:
		# Re-emits every active row. Called after _erase_active or after
		# a completed line has been pushed into history. Active rows are
		# capped to roughly half the terminal height so the live area
		# can't overflow and break cursor positioning.
		try:
			vTermH = max(8, shutil.get_terminal_size((80, 24)).lines)
		except Exception:
			vTermH = 24
		vCap = max(4, vTermH // 2)
		vItems  = list(self.active.items())
		vShown  = vItems[:vCap]
		vHidden = len(vItems) - len(vShown)
		for vName, vStatus in vShown:
			sys.stdout.write(f"  {kGray}...{kRst} {vName}{vStatus}\n")
		if vHidden > 0:
			sys.stdout.write(f"  {kGray}... and {vHidden} more running{kRst}\n")
		sys.stdout.flush()
		self.lineCount = len(vShown) + (1 if vHidden > 0 else 0)

	def start(self, inName: str) -> None:
		# Called by a worker when it begins a test. Adds a "(starting)" row.
		with self.lock:
			self.active[inName] = f"  {kGray}(starting){kRst}"
			if self.isLive:
				self._erase_active()
				self._draw_active()

	def update(self, inName: str, inStatus: str) -> None:
		# Called as each phase finishes. inStatus is the trailing portion
		# of the row (e.g. "  ktc: 597ms  comp: 4.41s") — the name and
		# RUN/PASS marker are added by the renderer.
		with self.lock:
			if inName not in self.active:
				return
			self.active[inName] = inStatus
			if self.isLive:
				self._erase_active()
				self._draw_active()

	def finish(self, inName: str, inLines: list[str]) -> None:
		# Called when a test completes (pass/fail/skip). inLines is one or
		# more lines (first is the result line, rest are warnings / tail).
		# The first line gets the [N/total] counter prefix.
		with self.lock:
			self.active.pop(inName, None)
			self.completed += 1
			vCounter = f"{kGray}[{self.completed:>{self.width}}/{self.total}]{kRst}"
			if self.isLive:
				self._erase_active()
			vFirst = True
			for vLine in inLines:
				if vFirst:
					sys.stdout.write(f"{vCounter} {vLine.lstrip()}\n")
					vFirst = False
				else:
					sys.stdout.write(f"{vLine}\n")
			sys.stdout.flush()
			if self.isLive:
				self._draw_active()

	def done(self) -> None:
		# Final cleanup — clears any residual live area so the summary
		# section starts on a clean line.
		with self.lock:
			if self.isLive:
				self._erase_active()

# ==================
# MARK: Test worker (parallel)
# ==================

def invoke_test_concise(inTest: TestDir, inOpts: RunOptions, inProgress: LiveProgress) -> TestOutcome:
	# Worker for the parallel suite. Reports phase-by-phase progress to
	# inProgress (so the live area updates as ktc/comp/run timings arrive),
	# and posts the final result line through finish(). On failure the tail
	# of the relevant tool output is included under the FAIL line.
	vName  = inTest.relPath
	vSrc   = inTest.fullPath
	vOut   = vSrc / "out"
	vCfg   = read_module_toml(vSrc / "module.ktc.toml")
	vExe   = vCfg.executable or inTest.name

	inProgress.start(vName)

	def fail(inReason: str, inOut: str = "", inErr: str = "", inFullDump: bool = False) -> TestOutcome:
		# Emits the FAIL line plus the captured tool output. stdout is rendered
		# gray (normal output, "ok" markers, etc.) and stderr is rendered red
		# below it (error messages, stack traces, compiler diagnostics). Runtime
		# errors get a full dump because the actual error can be anywhere in the
		# stream; build/transpile failures fail-fast so a tail is enough.
		vLines  = [f"  {kRed}FAIL{kRst} {vName} ({inReason})"]
		vBodyFn = _full if inFullDump else _tail
		for vLine in vBodyFn(inOut):
			if vLine.strip():
				vLines.append(f"       {kGray}{vLine}{kRst}")
		for vLine in vBodyFn(inErr):
			if vLine.strip():
				vLines.append(f"       {kRed}{vLine}{kRst}")
		inProgress.finish(vName, vLines)
		return TestOutcome(name=vName, status="fail")

	def skip(inReason: str) -> TestOutcome:
		inProgress.finish(vName, [f"  {kDYellow}SKIP{kRst} {vName}  ({inReason})"])
		return TestOutcome(name=vName, status="skip")

	def status_line(inTimings: str, inRun: bool = True) -> str:
		# Tail of an active row: name + accumulated timings so far.
		vMarker = f"{kYellow}RUN{kRst} " if inRun else ""
		return f"  {vMarker}{vName}  {kGray}{inTimings}{kRst}"

	vKts = collect_kt_files(vSrc)
	if not vKts:
		return fail("no .kt files")
	prepare_out_dir(vOut)

	# ── Transpile ────────────────────────────────────────────────
	vTr = run_capture(transpile_cmd(inOpts, vKts, vOut, vExe), inCwd=kRoot)
	if vTr.exit != 0:
		return fail("transpile failed", vTr.stdout, vTr.stderr)
	# Transpiler warnings can land in either stream depending on how the JVM
	# routes them — search both so they always surface under PASS.
	vWarnings = [k for k in (vTr.stdout + "\n" + vTr.stderr).splitlines() if "warning:" in k]
	inProgress.update(vName, status_line(f"ktc: {format_ms(vTr.ms)}"))

	vSrcUserCmake = vSrc / "ktc_user.cmake"
	if vSrcUserCmake.is_file():
		shutil.copy2(vSrcUserCmake, vOut / "ktc_user.cmake")

	vCSrcs = collect_c_sources(vOut)
	if not vCSrcs and not has_user_cmake(vOut):
		return fail("no .c files generated")

	vUseCmake = has_user_cmake(vOut)
	if vUseCmake and not inOpts.cmake:
		return skip("ktc_user.cmake present but cmake not found")

	# ── Compile ──────────────────────────────────────────────────
	vExePath: Path
	vCompMs  = 0
	if vUseCmake:
		vBld = vOut / "_cmake"
		if not cmake_generator_matches(vBld, inOpts.ninja):
			shutil.rmtree(vBld, onerror=_force_writable_and_retry)
		vCfgArgs = ["-B", str(vBld), "-S", str(vOut), f"-DCMAKE_BUILD_TYPE={inOpts.cfg}"]
		if inOpts.ninja:
			vCfgArgs.extend(["-G", "Ninja"])
		if inOpts.compiler:
			vCfgArgs.append(f"-DCMAKE_C_COMPILER={inOpts.compiler}")
		elif inOpts.ninja and inOpts.cc:
			vCfgArgs.append(f"-DCMAKE_C_COMPILER={inOpts.cc}")
		if inOpts.ccache:
			vCfgArgs.append(f"-DCMAKE_C_COMPILER_LAUNCHER={inOpts.ccache}")
		if inOpts.staticLibc:
			vCfgArgs.append("-DKTC_STATIC_LIBC=ON")
		if inOpts.cmakeArgs:
			vCfgArgs.extend(inOpts.cmakeArgs.split())
		vConfig = run_capture([inOpts.cmake, "--log-level=WARNING", *vCfgArgs])
		if vConfig.exit != 0:
			# Library-not-found indicators can land on either stream depending
			# on the cmake version.
			vCfgAll = vConfig.stdout + "\n" + vConfig.stderr
			if re.search(r"Could not find|not found|NOTFOUND", vCfgAll, re.IGNORECASE):
				return skip("required library not found")
			return fail("cmake configure failed", vConfig.stdout, vConfig.stderr)
		vJobs  = max(1, (os.cpu_count() or 4))
		vBuild = run_capture([inOpts.cmake, "--build", str(vBld), "--config", inOpts.cfg, "--parallel", str(vJobs)])
		if vBuild.exit != 0:
			return fail("cmake build failed", vBuild.stdout, vBuild.stderr)
		vFound = find_built_exe(vOut, vExe)
		if not vFound:
			return fail("no executable after cmake build")
		vExePath = vFound
		vCompMs = vConfig.ms + vBuild.ms
	else:
		vExeName = f"{vExe}{kExeSuffix}"
		vExePath = vOut / vExeName
		if not kIsWindows and (vOut / vExe).is_dir():
			vExePath = vOut / f"{vExe}.out"
		vCcCmd = [inOpts.ccache, inOpts.cc] if inOpts.ccache else [inOpts.cc]
		vCArgs = ["-std=c11", "-iquote", str(vOut), "-o", str(vExePath)]
		if inOpts.ccArgs:
			vCArgs.extend(inOpts.ccArgs.split())
		vCArgs.extend(str(c) for c in vCSrcs)
		vCo = run_capture([*vCcCmd, *vCArgs])
		if vCo.exit != 0:
			return fail("compile failed", vCo.stdout, vCo.stderr)
		vCompMs = vCo.ms
	inProgress.update(vName, status_line(f"ktc: {format_ms(vTr.ms)}  comp: {format_ms(vCompMs)}"))

	# ── Run (always headless in suite mode) ──────────────────────
	vRunArgs = ["--skip-interaction"] if vCfg.interactive else (vCfg.args.split() if vCfg.args else [])
	vRunRes  = run_capture([str(vExePath), *vRunArgs])
	if vRunRes.exit != 0:
		return fail(f"runtime error, exit {vRunRes.exit}", vRunRes.stdout, vRunRes.stderr, inFullDump=True)

	vTiming = f"{kGray}ktc: {format_ms(vTr.ms)}  comp: {format_ms(vCompMs)}  run: {format_ms(vRunRes.ms)}{kRst}"
	# Leak-tracker output can hit either stream; check both.
	vLeak   = "leaked" in vRunRes.stdout or "leaked" in vRunRes.stderr
	if vLeak:
		vFinal = f"  {kDYellow}PASS{kRst} {kDYellow}{vName}{kRst}  {kRed}LEAK{kRst}  {vTiming}"
	else:
		vFinal = f"  {kGreen}PASS{kRst} {kGreen}{vName}{kRst}  {vTiming}"
	vLines = [vFinal] + [f"       {kYellow}{vW}{kRst}" for vW in vWarnings]
	inProgress.finish(vName, vLines)
	return TestOutcome(name=vName, status="pass", timing=vTiming,
		ktcMs=vTr.ms, compileMs=vCompMs, runMs=vRunRes.ms)

# ==================
# MARK: Suite
# ==================

@dataclass
class SuiteResult:
	# Aggregated counts and lists from a suite run.
	passed:       int       = 0
	failed:       int       = 0
	skipped:      int       = 0
	failedNames:  list[str] = field(default_factory=list)
	skippedNames: list[str] = field(default_factory=list)

def run_unit_tests() -> bool:
	# Returns True on success. Streams gradle output directly.
	psection("Unit Tests (gradlew test)")
	vRes = run_streamed([str(kGradlew), "test", "--quiet"], inCwd=kRoot)
	if vRes == 0:
		ppass("All unit tests passed")
		return True
	pfail("Unit tests had failures")
	return False

def run_suite(inTests: list[TestDir], inOpts: RunOptions, inSkipUnit: bool, inFailFast: bool = False) -> SuiteResult:
	# Optionally runs unit tests then dispatches integration tests in parallel.
	# Workers update a LiveProgress instance as their phases complete so the
	# user sees per-test progress (RUN → ktc → comp → run → PASS) in real time
	# below the scrollback of already-completed tests.
	vRes = SuiteResult()
	if not inSkipUnit:
		if not run_unit_tests():
			vRes.failed += 1
			vRes.failedNames.append("unit-tests")
			if inFailFast:
				return vRes
	psection("Integration Tests")
	if not inTests:
		pinfo("No tests to run.")
		return vRes

	vProgress = LiveProgress(len(inTests))
	vWorkers  = max(1, (os.cpu_count() or 4))
	vAbort    = False
	with ThreadPoolExecutor(max_workers=vWorkers) as vEx:
		vFutures = [vEx.submit(invoke_test_concise, vT, inOpts, vProgress) for vT in inTests]
		for vF in as_completed(vFutures):
			vOut = vF.result()
			if   vOut.status == "pass": vRes.passed += 1
			elif vOut.status == "skip": vRes.skipped += 1; vRes.skippedNames.append(vOut.name)
			else:                       vRes.failed  += 1; vRes.failedNames.append(vOut.name)
			if inFailFast and vRes.failed > 0 and not vAbort:
				vAbort = True
				for vPending in vFutures:
					vPending.cancel()
	vProgress.done()
	return vRes

def show_summary(inRes: SuiteResult) -> int:
	# Prints a final tally and returns the appropriate exit code (0 = all pass).
	psection("Summary")
	vTotal = inRes.passed + inRes.failed + inRes.skipped
	vParts = [f"Total: {vTotal}", f"{kGreen}Passed: {inRes.passed}{kRst}"]
	if inRes.skipped:
		vParts.append(f"{kYellow}Skipped: {inRes.skipped}{kRst}")
	if inRes.failed:
		vParts.append(f"{kRed}Failed: {inRes.failed}{kRst}")
	else:
		vParts.append(f"{kGreen}Failed: 0{kRst}")
	pwrite("  " + "  |  ".join(vParts))
	if inRes.failedNames:
		pwrite("")
		pwrite(f"  {kRed}Failed tests:{kRst}")
		for vN in inRes.failedNames:
			pwrite(f"    {kRed}- {vN}{kRst}")
	if inRes.skippedNames:
		pwrite("")
		pwrite(f"  {kYellow}Skipped tests (missing library):{kRst}")
		for vN in inRes.skippedNames:
			pwrite(f"    {kYellow}- {vN}{kRst}")
	return 1 if inRes.failed else 0

# ==================
# MARK: Test matching
# ==================

def resolve_test_query(inQuery: str, inAll: list[TestDir]) -> list[TestDir]:
	# Matches a CLI test query against the discovered tests. Accepts either the
	# leaf name (`PointerTest`) or the slash-normalized relative path
	# (`intrinsic/PointerTest`). Returns all matches.
	vQ = inQuery.strip()
	if not vQ:
		return []
	return [t for t in inAll if t.name == vQ or t.relPath == vQ]

def filter_tests(inTests: list[TestDir], inFilter: str, inExclude: str) -> list[TestDir]:
	vResult = inTests
	if inFilter:
		vResult = [t for t in vResult if fnmatch.fnmatch(t.relPath, inFilter) or fnmatch.fnmatch(t.name, inFilter)]
	if inExclude:
		vResult = [t for t in vResult if not fnmatch.fnmatch(t.relPath, inExclude) and not fnmatch.fnmatch(t.name, inExclude)]
	return vResult

# ==================
# MARK: CLI
# ==================

def build_arg_parser() -> argparse.ArgumentParser:
	# Mirrors the flags from run_tests.ps1 / run_tests.sh. Short aliases match
	# the bash flavor for muscle-memory carryover (--skip-unit / --run / ...).
	vP = argparse.ArgumentParser(
		prog="run_tests.py",
		description="Build the KotlinToC transpiler and run unit + integration tests.",
		formatter_class=argparse.RawDescriptionHelpFormatter,
		epilog=__doc__,
	)
	# Selection
	vP.add_argument("--run",  default="", help="Run named test(s) (comma-separated)")
	vP.add_argument("--skip", default="", choices=["", "unit"], help="Skip a phase ('unit' to skip unit tests)")
	vP.add_argument("--skip-unit", action="store_true", help="Alias for --skip unit")
	vP.add_argument("--list", action="store_true", help="Print all discovered tests and exit")
	vP.add_argument("--filter", default="", help="Glob pattern to select tests (e.g. 'stdlib/*')")
	vP.add_argument("--exclude", default="", help="Glob pattern to exclude tests")
	vP.add_argument("--fail-fast", action="store_true", help="Stop after the first failure")
	# Build
	vP.add_argument("--build", default="jar", choices=["jar", "gradle", "proguard"], help="Build mode")
	vP.add_argument("--rebuild", action="store_true", help="Force clean rebuild of the JAR")
	vP.add_argument("--clean",   action="store_true", help="Remove all integration/*/out/ directories")
	# Compile
	vP.add_argument("--compiler",   default="", help="C compiler (gcc/clang/cl/cc) — auto-detected if omitted")
	vP.add_argument("--cc-args",    default="", help="Extra C compiler flags (single quoted string)")
	vP.add_argument("--cmake-args", default="", help="Extra cmake -D flags")
	vP.add_argument("--cfg",        default="Release", help="CMake build type")
	vP.add_argument("--static-libc", action="store_true", help="Pass -DKTC_STATIC_LIBC=ON to cmake")
	# Run
	vP.add_argument("--gui", action="store_true", help="Open window for interactive tests (no --skip-interaction)")
	# Transpiler-flag toggles
	vP.add_argument("--mem-track",       action="store_true", help="Pass --mem-track to the transpiler")
	vP.add_argument("--ast",             action="store_true", help="Pass --ast to the transpiler")
	vP.add_argument("--dump-semantics",  action="store_true", help="Pass --dump-semantics to the transpiler")
	vP.add_argument("--strict",          action="store_true", help="Pass --strict to the transpiler (warnings become errors)")
	vP.add_argument("--disposed",         default="NO", choices=["NO", "ASSERT", "LOG"], help="Use-after-dispose behavior")
	vP.add_argument("--double-dispose",   default="NO", choices=["NO", "ASSERT", "LOG"], help="Double-dispose behavior")
	vP.add_argument("--transpiler-args",  default="", help="Extra raw transpiler args appended at the end")
	# Subcommands
	vP.add_argument("--bench", default="", help="Run a test N times and report timing stats (e.g. --bench TestName [-n 10])")
	vP.add_argument("-n", type=int, default=5, help="Number of iterations for --bench (default: 5)")
	vP.add_argument("--completions", default="", choices=["", "bash", "zsh", "fish"], help="Print shell completion script and exit")
	vP.add_argument("--watch", action="store_true", help="Re-run tests when .kt files change")
	vP.add_argument("command", nargs="?", default=None, help="Subcommand: 'init <name>' scaffolds a new test")
	vP.add_argument("init_name", nargs="?", default=None, help="Name for the new test (used with 'init')")
	return vP

def build_extra_args(inNs: argparse.Namespace) -> list[str]:
	# Translates the transpiler-flag namespace into a flat argv list. Empty
	# strings are dropped so the final argv has no stray "" tokens.
	vExtras: list[str] = []
	if inNs.mem_track:      vExtras.append("--mem-track")
	if inNs.ast:            vExtras.append("--ast")
	if inNs.dump_semantics: vExtras.append("--dump-semantics")
	if inNs.strict:         vExtras.append("--strict")
	if inNs.disposed       != "NO": vExtras.append(f"--disposed={inNs.disposed}")
	if inNs.double_dispose != "NO": vExtras.append(f"--double-dispose={inNs.double_dispose}")
	if inNs.transpiler_args:
		vExtras.extend(inNs.transpiler_args.split())
	return vExtras

# Options whose value is a free-form string of compiler/tool flags. argparse
# refuses to consume a dash-prefixed token as the value of these, so users have
# to write `--cc-args=-g` instead of the more natural `--cc-args "-g"`. The
# preprocessor below normalizes the latter into the former before argparse
# sees the argv.
kStringValueOpts = {"--cc-args", "--cmake-args", "--transpiler-args", "--run", "--compiler", "--cfg", "--skip", "--filter", "--exclude", "--bench"}

def normalize_argv(inArgv: list[str]) -> list[str]:
	# Walks the argv and folds `--cc-args VALUE` into `--cc-args=VALUE` when
	# VALUE starts with '-'. argparse only stumbles when the value starts with
	# a dash and would otherwise be mistaken for another option — equals-form
	# is unambiguous.
	vOut: list[str] = []
	vI = 0
	while vI < len(inArgv):
		vTok = inArgv[vI]
		if vTok in kStringValueOpts and vI + 1 < len(inArgv) and inArgv[vI + 1].startswith("-"):
			vOut.append(f"{vTok}={inArgv[vI + 1]}")
			vI += 2
			continue
		vOut.append(vTok)
		vI += 1
	return vOut

def main(inArgv: list[str]) -> int:
	vNs = build_arg_parser().parse_args(normalize_argv(inArgv))

	# ── init subcommand ──────────────────────────────────────────
	if vNs.command == "init":
		vName = vNs.init_name
		if not vName:
			pwrite(f"{kRed}ERROR: usage: run_tests.py init <TestName>{kRst}")
			return 1
		return cmd_init(vName)

	# ── --completions: print shell completion script ──────────────
	if vNs.completions:
		return cmd_completions(vNs.completions)

	if vNs.clean:
		return cmd_clean()

	vAllTests = find_test_dirs()

	# ── --list: print all discovered tests and exit ───────────────
	if vNs.list:
		vFiltered = filter_tests(vAllTests, vNs.filter, vNs.exclude)
		for vT in vFiltered:
			pwrite(vT.relPath)
		return 0

	vCC = find_c_compiler(vNs.compiler or None)
	if not vCC:
		pwrite(f"{kRed}ERROR: No C compiler found (tried gcc, clang, {'cl' if kIsWindows else 'cc'}). Install one and add it to PATH.{kRst}")
		return 1
	vCmake  = find_cmake()
	vCcache = find_ccache()
	vNinja  = find_ninja()

	vOpts = RunOptions(
		buildMode  = vNs.build,
		compiler   = vNs.compiler,
		ccArgs     = vNs.cc_args,
		cmakeArgs  = vNs.cmake_args,
		cfg        = vNs.cfg,
		staticLibc = vNs.static_libc,
		gui        = vNs.gui,
		extraArgs  = build_extra_args(vNs),
		cc         = vCC,
		cmake      = vCmake,
		ccache     = vCcache,
		ninja      = vNinja,
	)

	vAllTests = filter_tests(vAllTests, vNs.filter, vNs.exclude)
	vCompInfo = f"Using C compiler: {vCC}"
	if vCcache: vCompInfo += f" (via {vCcache})"
	if vNinja:  vCompInfo += f" [ninja]"
	pinfo(vCompInfo)

	# ── --bench: benchmark a test ─────────────────────────────────
	if vNs.bench:
		return cmd_bench(vNs.bench, vNs.n, vAllTests, vOpts)

	# ── --run: explicit selection (verbose) ───────────────────────
	if vNs.run:
		invoke_build(vOpts.buildMode, vNs.rebuild)
		vQueries = [k.strip() for k in vNs.run.split(",") if k.strip()]
		vAnyFail = False
		for vQ in vQueries:
			vMatches = resolve_test_query(vQ, vAllTests)
			if not vMatches:
				pwrite(f"{kRed}ERROR: test not found: {vQ}{kRst}")
				pwrite(f"{kYellow}Available tests:{kRst}")
				for vT in vAllTests:
					pwrite(f"  - {vT.relPath}")
				vAnyFail = True
				continue
			for vT in vMatches:
				if invoke_test_verbose(vT, vOpts).status == "fail":
					vAnyFail = True
					if vNs.fail_fast:
						return 1
		return 1 if vAnyFail else 0

	# ── Default: full suite ───────────────────────────────────────
	vSkipUnit = vNs.skip_unit or vNs.skip == "unit"

	def run_once():
		invoke_build(vOpts.buildMode, False)
		vRes = run_suite(vAllTests, vOpts, vSkipUnit, vNs.fail_fast)
		show_summary(vRes)

	if vNs.watch:
		invoke_build(vOpts.buildMode, vNs.rebuild)
		vRes = run_suite(vAllTests, vOpts, vSkipUnit, vNs.fail_fast)
		show_summary(vRes)
		return cmd_watch(run_once)

	invoke_build(vOpts.buildMode, vNs.rebuild)
	vRes      = run_suite(vAllTests, vOpts, vSkipUnit, vNs.fail_fast)
	return show_summary(vRes)

if __name__ == "__main__":
	sys.exit(main(sys.argv[1:]))
