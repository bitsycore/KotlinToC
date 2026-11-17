#!/usr/bin/env python3
"""
Builds the programs of benchmarks/ with ktc2, Kotlin/Native and the JVM, and compares them.

Usage: python tools/compare.py [name part...] [--targets ktc2,native,jvm] [--runs N] [--save file]

For each program and each target, four measures:
- compile time: ktc2 to C, cold (a process of its own, KTC2_DAEMON=0) and warm (the compiler
  process ktc2 keeps, on its second command), then the C compiler and linker (-O2, LTO);
  kotlinc-native -opt, cold; kotlinc to a jar, cold;
- size: the executable, as built and stripped; for the JVM the program's jar and the Kotlin
  library's, and once the JDK's runtime cut to java.base (jlink), which a jar needs to run;
- memory: the peak resident memory of the process (Windows: PeakWorkingSetSize; elsewhere
  ru_maxrss), best of the runs;
- speed: the time of the whole process, best and median of N runs (7 by default).

The three must print the same: that checks Kotlin/Native and the JVM against ktc2 too. A program
that uses the `ktc2.` package is compiled on the JVM with src/main/kotlin/ktc2 and on Kotlin/Native
with benchmarks/native/ktc2, the same API over what each has.

What it needs:
- ktc2 installed (./gradlew installDist; KTC2_TOOL names another one) and the C compiler (KTC2_CC);
- Kotlin/Native: KTC2_KONAN names its directory; otherwise the one of the Kotlin version of
  gradle/libs.versions.toml under ~/.konan (`./gradlew fetchKotlinNative` puts it there), else the
  newest there, which the report then names;
- the JVM's compiler: the one ktc2 runs (`./gradlew compareClasspath` writes its class path), and
  the JDK this script finds (JAVA_HOME, else java on the path).

Run it on a quiet machine: not while the suite runs. The report is printed and written to
build/compare/compare.txt (--save names another file).
"""
import os
import re
import shutil
import statistics
import subprocess
import sys
import time

kRoot = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
kOut = os.path.join(kRoot, "build", "compare")
kIsWindows = os.name == "nt"
kExe = ".exe" if kIsWindows else ""


def fail(inMessage):
	"""Prints [inMessage] and ends with an error."""
	sys.stderr.write("compare: " + inMessage + "\n")
	sys.exit(1)


# ==================
# MARK: Processes
# ==================

def peakOfHandle(inHandle):
	"""The peak working set of the Windows process [inHandle], in bytes."""
	import ctypes
	from ctypes import wintypes

	class Counters(ctypes.Structure):
		_fields_ = [("cb", wintypes.DWORD), ("PageFaultCount", wintypes.DWORD), ("PeakWorkingSetSize", ctypes.c_size_t),
			("WorkingSetSize", ctypes.c_size_t), ("QuotaPeakPagedPoolUsage", ctypes.c_size_t), ("QuotaPagedPoolUsage", ctypes.c_size_t),
			("QuotaPeakNonPagedPoolUsage", ctypes.c_size_t), ("QuotaNonPagedPoolUsage", ctypes.c_size_t),
			("PagefileUsage", ctypes.c_size_t), ("PeakPagefileUsage", ctypes.c_size_t)]

	vCounters = Counters()
	vCounters.cb = ctypes.sizeof(Counters)
	vPsapi = ctypes.WinDLL("psapi")
	vPsapi.GetProcessMemoryInfo.argtypes = [wintypes.HANDLE, ctypes.POINTER(Counters), wintypes.DWORD]
	if not vPsapi.GetProcessMemoryInfo(int(inHandle), ctypes.byref(vCounters), vCounters.cb):
		return 0
	return vCounters.PeakWorkingSetSize


def run(inCommand, inCwd=None, inEnv=None):
	"""Runs [inCommand]; returns (exit code, stdout, stderr, seconds, peak resident bytes)."""
	vStart = time.perf_counter()
	vProcess = subprocess.Popen(inCommand, cwd=inCwd, env=inEnv, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
	if kIsWindows:
		vOut, vErr = vProcess.communicate()
		vSeconds = time.perf_counter() - vStart
		vPeak = peakOfHandle(vProcess._handle)
	else:
		# wait4 gives the process's own resource use: read its output first, then reap it.
		vOut = vProcess.stdout.read()
		vErr = vProcess.stderr.read()
		_, vStatus, vUsage = os.wait4(vProcess.pid, 0)
		vSeconds = time.perf_counter() - vStart
		vProcess.returncode = os.waitstatus_to_exitcode(vStatus)
		# Linux counts kilobytes, macOS bytes.
		vPeak = vUsage.ru_maxrss if sys.platform == "darwin" else vUsage.ru_maxrss * 1024
	return vProcess.returncode, vOut.decode("utf-8", "replace"), vErr.decode("utf-8", "replace"), vSeconds, vPeak


def checked(inWhat, inCommand, inCwd=None, inEnv=None):
	"""[run], failing with the output when the command does not end well."""
	vResult = run(inCommand, inCwd, inEnv)
	if vResult[0] != 0:
		fail("%s failed (%d):\n%s\n%s" % (inWhat, vResult[0], vResult[1][-3000:], vResult[2][-3000:]))
	return vResult


def stripped(inPath):
	"""The size of [inPath] once stripped of its symbols (a copy; the size as it is when there is no strip)."""
	vStrip = shutil.which("strip")
	if not vStrip:
		return os.path.getsize(inPath)
	vCopy = inPath + ".stripped"
	shutil.copyfile(inPath, vCopy)
	subprocess.run([vStrip, vCopy], capture_output=True)
	return os.path.getsize(vCopy)


# ==================
# MARK: Tools
# ==================

def kotlinVersion():
	"""The Kotlin version of gradle/libs.versions.toml."""
	with open(os.path.join(kRoot, "gradle", "libs.versions.toml"), encoding="utf-8") as vFile:
		vMatch = re.search(r'(?m)^kotlin\s*=\s*"([^"]+)"', vFile.read())
	return vMatch.group(1)


def konan():
	"""The directory of Kotlin/Native's compiler, and its version."""
	vNamed = os.environ.get("KTC2_KONAN")
	if vNamed:
		return vNamed, versionOf(os.path.basename(vNamed.rstrip("/\\")))
	vHome = os.path.join(os.path.expanduser("~"), ".konan")
	vAll = [vName for vName in os.listdir(vHome) if vName.startswith("kotlin-native-prebuilt-")] if os.path.isdir(vHome) else []
	if not vAll:
		fail("no Kotlin/Native compiler under ~/.konan: ./gradlew fetchKotlinNative, or KTC2_KONAN")
	vWanted = kotlinVersion()
	vExact = [vName for vName in vAll if vName.endswith("-" + vWanted)]

	def version(inName):
		return [int(vPart) if vPart.isdigit() else -1 for vPart in re.split(r"[.-]", versionOf(inName))]

	vName = vExact[0] if vExact else max(vAll, key=version)
	return os.path.join(vHome, vName), versionOf(vName)


def versionOf(inName):
	"""The version in the name of a Kotlin/Native directory (`...-macos-aarch64-2.5.0-RC`: 2.5.0-RC)."""
	vMatch = re.search(r"-(\d+\.\d+[^/\\]*)$", inName)
	return vMatch.group(1) if vMatch else inName.split("-")[-1]


def javaHome():
	"""The JDK that runs the JVM side."""
	vHome = os.environ.get("JAVA_HOME")
	if vHome and os.path.isfile(os.path.join(vHome, "bin", "java" + kExe)):
		return vHome
	vJava = shutil.which("java")
	if not vJava:
		fail("no JDK: set JAVA_HOME")
	return os.path.dirname(os.path.dirname(os.path.realpath(vJava)))


def kotlincClasspath():
	"""The class path of the JVM's Kotlin compiler and the Kotlin library's jar, as `./gradlew compareClasspath` wrote them."""
	vFile = os.path.join(kOut, "kotlinc.classpath")
	if not os.path.isfile(vFile):
		fail("run ./gradlew compareClasspath first (the class path of the JVM's compiler)")
	with open(vFile, encoding="utf-8") as vIn:
		vLines = vIn.read().splitlines()
	return vLines[0], vLines[1]


def jlinkSize(inJavaHome):
	"""The size of the JDK's runtime cut to java.base: what a jar needs besides itself. Made once."""
	vDirectory = os.path.join(kOut, "jlink")
	if not os.path.isdir(vDirectory):
		vJlink = os.path.join(inJavaHome, "bin", "jlink" + kExe)
		checked("jlink", [vJlink, "--add-modules", "java.base", "--strip-debug", "--no-header-files", "--no-man-pages", "--output", vDirectory])
	return sum(os.path.getsize(os.path.join(vDir, vName)) for vDir, _, vNames in os.walk(vDirectory) for vName in vNames)


def ktc2Sources(inSource, inTarget):
	"""The sources of [inSource] for [inTarget]: with the `ktc2.` package of that target when the program uses it."""
	with open(inSource, encoding="utf-8") as vFile:
		vUses = re.search(r"(?m)^import ktc2\.", vFile.read()) is not None
	if not vUses or inTarget == "ktc2":
		return [inSource]
	vDirectory = os.path.join(kRoot, "src", "main", "kotlin", "ktc2") if inTarget == "jvm" else os.path.join(kRoot, "benchmarks", "native", "ktc2")
	return [inSource] + sorted(os.path.join(vDir, vName) for vDir, _, vNames in os.walk(vDirectory) for vName in vNames if vName.endswith(".kt"))


# ==================
# MARK: Targets
# ==================

def buildKtc2(inSource, inDirectory):
	"""Translates with ktc2 cold and warm, builds the C; returns (compile times, the command, sizes)."""
	vTool = os.environ.get("KTC2_TOOL") or os.path.join(kRoot, "build", "install", "ktc2", "bin", "ktc2" + (".bat" if kIsWindows else ""))
	if not os.path.isfile(vTool):
		fail("no installed tool: run ./gradlew installDist")
	vShell = ["cmd", "/c", vTool] if kIsWindows else [vTool]
	vCold = dict(os.environ, KTC2_DAEMON="0")
	vColdTime = checked("ktc2 (cold)", vShell + [inSource, "-o", inDirectory, "--no-warn-cycles"], inEnv=vCold)[3]
	# The compiler process is started by a first command, then measured on the next one.
	checked("ktc2", vShell + [inSource, "-o", inDirectory, "--no-warn-cycles"])
	vWarmTime = checked("ktc2 (warm)", vShell + [inSource, "-o", inDirectory, "--no-warn-cycles"])[3]
	vManifest = {}
	with open(os.path.join(inDirectory, "ktc2_build.txt"), encoding="utf-8") as vFile:
		for vLine in vFile:
			vKey, _, vValue = vLine.rstrip("\n").partition("=")
			vManifest.setdefault(vKey, []).append(vValue)
	vExe = os.path.join(inDirectory, "program" + kExe)
	vCompiler = os.environ.get("KTC2_CC", "gcc")
	vCommand = [vCompiler, "-std=c11", "-w", "-O2", "-flto", "-ffp-contract=off"] + ([] if kIsWindows else ["-pthread"])
	vCommand += ["-I", ".", "-o", vExe] + vManifest.get("cflag", []) + vManifest.get("c", []) + ([] if kIsWindows else ["-lm"]) + vManifest.get("ldflag", [])
	vCTime = checked("the C compiler", vCommand, inCwd=os.path.join(inDirectory, "c"))[3]
	vLines = sum(1 for vName in vManifest.get("c", []) for _ in open(os.path.join(inDirectory, "c", vName), encoding="utf-8", errors="replace"))
	return {"cold": vColdTime, "warm": vWarmTime, "c": vCTime}, [vExe], {"built": os.path.getsize(vExe), "stripped": stripped(vExe), "lines of C": vLines}


def buildNative(inSource, inDirectory, inKonan):
	"""Compiles with kotlinc-native -opt; returns (compile times, the command, sizes)."""
	vCompiler = os.path.join(inKonan, "bin", "kotlinc-native" + (".bat" if kIsWindows else ""))
	vShell = ["cmd", "/c", vCompiler] if kIsWindows else [vCompiler]
	vOutput = os.path.join(inDirectory, "program")
	vTime = checked("kotlinc-native", vShell + ktc2Sources(inSource, "native") + ["-opt", "-o", vOutput])[3]
	vExe = vOutput + (".exe" if kIsWindows else ".kexe")
	return {"cold": vTime}, [vExe], {"built": os.path.getsize(vExe), "stripped": stripped(vExe)}


def buildJvm(inSource, inDirectory, inJavaHome):
	"""Compiles with kotlinc to a jar, run with the Kotlin library's; returns (compile times, the command, sizes)."""
	vJava = os.path.join(inJavaHome, "bin", "java" + kExe)
	vJar = os.path.join(inDirectory, "program.jar")
	vCompilerPath, vStdlib = kotlincClasspath()
	vCompiler = [vJava, "-cp", vCompilerPath, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler"]
	vOptions = ["-d", vJar, "-no-stdlib", "-no-reflect", "-cp", vStdlib, "-jvm-target", "21"]
	vTime = checked("kotlinc", vCompiler + ktc2Sources(inSource, "jvm") + vOptions)[3]
	vMain = os.path.splitext(os.path.basename(inSource))[0] + "Kt"
	# The size of what the program is: its jar and the Kotlin library's, which it needs (the runtime is in the header).
	return {"cold": vTime}, [vJava, "-cp", vJar + os.pathsep + vStdlib, vMain], {"built": os.path.getsize(vJar) + os.path.getsize(vStdlib)}


# ==================
# MARK: Report
# ==================

def measureRuns(inCommand, inRuns, inName):
	"""Runs [inCommand] [inRuns] times; returns (what it printed, best seconds, median seconds, best peak bytes)."""
	vTimes = []
	vPeaks = []
	vPrinted = None
	for _ in range(inRuns):
		vCode, vOut, vErr, vSeconds, vPeak = run(inCommand)
		if vCode != 0:
			fail("%s ended with %d:\n%s" % (inName, vCode, vErr[-2000:]))
		if vPrinted is not None and vOut != vPrinted:
			fail("%s prints something else from one run to the next" % inName)
		vPrinted = vOut
		vTimes.append(vSeconds)
		vPeaks.append(vPeak)
	return vPrinted.replace("\r\n", "\n"), min(vTimes), statistics.median(vTimes), min(vPeaks)


def megabytes(inBytes):
	"""[inBytes] in MB, to one decimal."""
	return "%.1f MB" % (inBytes / 1048576.0)


def ratio(inMine, inOther):
	"""How many times [inOther] is [inMine]: the factor ktc2 is better (>1) or worse (<1) by."""
	if not inMine or not inOther:
		return "-"
	return "%.2fx" % (inOther / inMine)


def main():
	vFilters = []
	vTargets = ["ktc2", "native", "jvm"]
	vRuns = 7
	vSave = os.path.join(kOut, "compare.txt")
	vArguments = sys.argv[1:]
	while vArguments:
		vArgument = vArguments.pop(0)
		if vArgument == "--targets":
			vTargets = [vTarget for vTarget in vArguments.pop(0).split(",") if vTarget]
		elif vArgument == "--runs":
			vRuns = int(vArguments.pop(0))
		elif vArgument == "--save":
			vSave = vArguments.pop(0)
		else:
			vFilters.append(vArgument)
	vDirectory = os.path.join(kRoot, "benchmarks")
	vSources = sorted(vName for vName in os.listdir(vDirectory) if vName.endswith(".kt"))
	vSources = [vName for vName in vSources if not vFilters or any(vFilter in vName for vFilter in vFilters)]
	os.makedirs(kOut, exist_ok=True)

	vKonan, vNativeVersion = konan() if "native" in vTargets else (None, None)
	vJavaHome = javaHome() if "jvm" in vTargets else None
	vHeader = ["ktc2 %s; C compiler %s" % (kotlinVersion(), os.environ.get("KTC2_CC", "gcc"))]
	if vKonan:
		vHeader.append("Kotlin/Native %s%s" % (vNativeVersion, "" if vNativeVersion == kotlinVersion() else " (not the version ktc2 follows, %s)" % kotlinVersion()))
	if vJavaHome:
		vHeader.append("JVM: %s; its runtime cut to java.base (jlink): %s" % (vJavaHome, megabytes(jlinkSize(vJavaHome))))
	vHeader.append("%d runs of each; times of the whole process, memory the peak resident size" % vRuns)
	vLines = list(vHeader)
	for vLine in vHeader:
		print(vLine)

	for vSource in vSources:
		vName = vSource[:-3]
		vPath = os.path.join(vDirectory, vSource)
		vResults = {}
		for vTarget in vTargets:
			vOutDir = os.path.join(kOut, vName, vTarget)
			shutil.rmtree(vOutDir, ignore_errors=True)
			os.makedirs(vOutDir)
			if vTarget == "ktc2":
				vCompile, vCommand, vSizes = buildKtc2(vPath, vOutDir)
			elif vTarget == "native":
				vCompile, vCommand, vSizes = buildNative(vPath, vOutDir, vKonan)
			else:
				vCompile, vCommand, vSizes = buildJvm(vPath, vOutDir, vJavaHome)
			vPrinted, vBest, vMedian, vPeak = measureRuns(vCommand, vRuns, "%s on %s" % (vName, vTarget))
			vResults[vTarget] = (vCompile, vSizes, vBest, vMedian, vPeak, vPrinted)
		vPrints = {vTarget: vResult[5] for vTarget, vResult in vResults.items()}
		vSame = len(set(vPrints.values())) == 1
		vBlock = ["", "== %s%s" % (vName, "" if vSame else "   (they do not print the same: " + ", ".join(sorted(vPrints)) + ")")]
		vBlock.append("%-8s %10s %10s %10s %12s %12s %10s %10s" % ("target", "compile", "warm", "C", "size", "stripped", "best", "median") + "  peak memory")
		for vTarget, (vCompile, vSizes, vBest, vMedian, vPeak, _) in vResults.items():
			vBlock.append("%-8s %9.2fs %10s %10s %12s %12s %9.3fs %9.3fs  %s" % (
				vTarget, vCompile["cold"],
				"%.2fs" % vCompile["warm"] if "warm" in vCompile else "-",
				"%.2fs" % vCompile["c"] if "c" in vCompile else "-",
				megabytes(vSizes["built"]), megabytes(vSizes["stripped"]) if "stripped" in vSizes else "-",
				vBest, vMedian, megabytes(vPeak)))
		if "ktc2" in vResults:
			vMine = vResults["ktc2"]
			for vOther in [vTarget for vTarget in vResults if vTarget != "ktc2"]:
				vTheirs = vResults[vOther]
				vMineCompile = vMine[0]["warm"] + vMine[0]["c"]
				vBlock.append("ktc2 against %s: speed %s, memory %s, size %s, compile (warm + C) %s" % (
					vOther, ratio(vMine[2], vTheirs[2]), ratio(vMine[4], vTheirs[4]),
					ratio(vMine[1].get("stripped", vMine[1]["built"]), vTheirs[1].get("stripped", vTheirs[1]["built"])),
					ratio(vMineCompile, vTheirs[0]["cold"])))
		for vLine in vBlock:
			print(vLine, flush=True)
		vLines += vBlock
	vLines.append("")
	vLines.append("A ratio is how many times the other target's figure is ktc2's: above 1, ktc2 is faster, smaller or lighter.")
	with open(vSave, "w", encoding="utf-8") as vFile:
		vFile.write("\n".join(vLines) + "\n")
	print("\nwritten to " + vSave)


main()
