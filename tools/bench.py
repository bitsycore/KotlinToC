#!/usr/bin/env python3
"""
Builds and times the programs of benchmarks/.

Usage: python tools/bench.py [name part...] [--runs N] [--copt option]... [--save file] [--compare file]

Each program is translated with the installed tool (run `./gradlew installDist` first; KTC2_TOOL
names another one), built
twice with the C compiler (KTC2_CC, else gcc): once as a release build (`-O2`) that is timed,
best of N runs, and once with KTC2_ARC_STATS for the counts of allocations, retains and releases.
The table is printed and written to build/bench/results.txt (or the file --save names);
--compare prints the change against a table saved before.
"""
import os
import re
import shutil
import subprocess
import sys
import time

kRoot = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
kOut = os.path.join(kRoot, "build", "bench")
kIsWindows = os.name == "nt"


def fail(inMessage):
	"""Prints [inMessage] and ends with an error."""
	sys.stderr.write("bench: " + inMessage + "\n")
	sys.exit(1)


def manifest(inDirectory, inKey):
	"""The values of [inKey] in the build list ktc2 wrote into [inDirectory]."""
	with open(os.path.join(inDirectory, "ktc2_build.txt"), encoding="utf-8") as vFile:
		return [vLine.split("=", 1)[1].rstrip("\n") for vLine in vFile if vLine.startswith(inKey + "=")]


def build(inDirectory, inName, inOptions):
	"""Compiles the C of one translated program into `<inName>` and returns its path."""
	vCompiler = os.environ.get("KTC2_CC", "gcc")
	vExe = os.path.join(inDirectory, inName + (".exe" if kIsWindows else ""))
	vLibs = [] if kIsWindows else ["-lm"]
	vThreads = [] if kIsWindows else ["-pthread"]
	vCommand = [vCompiler, "-std=c11", "-w", "-ffp-contract=off"] + inOptions + vThreads + ["-I", ".", "-o", vExe]
	vCommand += manifest(inDirectory, "cflag") + manifest(inDirectory, "c") + vLibs + manifest(inDirectory, "ldflag")
	vResult = subprocess.run(vCommand, cwd=os.path.join(inDirectory, "c"), capture_output=True, text=True)
	if vResult.returncode != 0:
		fail("the C compiler failed on %s:\n%s" % (inName, vResult.stderr[:4000]))
	return vExe


def measure(inSource, inRuns, inOptions):
	"""Translates, builds and runs one benchmark; returns (seconds, allocs, retains, releases, bytes of the program)."""
	vName = os.path.splitext(os.path.basename(inSource))[0]
	vDirectory = os.path.join(kOut, vName)
	shutil.rmtree(vDirectory, ignore_errors=True)
	# KTC2_TOOL names another installed tool, to measure an earlier state of the sources.
	vTool = os.environ.get("KTC2_TOOL") or os.path.join(kRoot, "build", "install", "ktc2", "bin", "ktc2.bat" if kIsWindows else "ktc2")
	if not os.path.isfile(vTool):
		fail("no installed tool: run ./gradlew installDist")
	vResult = subprocess.run([vTool, inSource, "-o", vDirectory, "--no-warn-cycles"], capture_output=True, text=True, shell=kIsWindows)
	if vResult.returncode != 0 or not os.path.isfile(os.path.join(vDirectory, "ktc2_build.txt")):
		fail("ktc2 failed on %s:\n%s%s" % (vName, vResult.stdout[-3000:], vResult.stderr[-3000:]))

	vCounted = build(vDirectory, "counted", ["-O2", "-DKTC2_ARC_STATS"] + inOptions)
	vRun = subprocess.run([vCounted], capture_output=True, text=True, encoding="utf-8")
	if vRun.returncode != 0:
		fail("%s ended with %d:\n%s" % (vName, vRun.returncode, vRun.stderr[-2000:]))
	vMatch = re.search(r"allocs=(\d+) retains=(\d+) releases=(\d+)", vRun.stderr)
	vCounts = tuple(int(vMatch.group(vIndex)) for vIndex in (1, 2, 3)) if vMatch else (0, 0, 0)
	vExpected = vRun.stdout

	vRelease = build(vDirectory, "release", ["-O2"] + inOptions)
	vBest = None
	for _ in range(inRuns):
		vStart = time.perf_counter()
		vRun = subprocess.run([vRelease], capture_output=True, text=True, encoding="utf-8")
		vElapsed = time.perf_counter() - vStart
		if vRun.returncode != 0 or vRun.stdout != vExpected:
			fail("%s: the release build does not print what the counted build prints" % vName)
		vBest = vElapsed if vBest is None else min(vBest, vElapsed)
	return (vBest,) + vCounts + (os.path.getsize(vRelease),)


def load(inPath):
	"""A table saved before, by benchmark name."""
	vTable = {}
	with open(inPath, encoding="utf-8") as vFile:
		for vLine in vFile:
			vParts = vLine.split()
			if len(vParts) == 6 and vParts[0] != "benchmark":
				vTable[vParts[0]] = (float(vParts[1]),) + tuple(int(vPart) for vPart in vParts[2:])
	return vTable


def change(inNow, inBefore):
	"""The difference of two figures as a signed percentage."""
	if not inBefore:
		return "     -"
	return "%+5.0f%%" % ((inNow - inBefore) * 100.0 / inBefore)


def main():
	vFilters = []
	vRuns = 5
	vOptions = []
	vSave = os.path.join(kOut, "results.txt")
	vCompare = None
	vArguments = sys.argv[1:]
	while vArguments:
		vArgument = vArguments.pop(0)
		if vArgument == "--runs":
			vRuns = int(vArguments.pop(0))
		elif vArgument == "--copt":
			vOptions.append(vArguments.pop(0))
		elif vArgument == "--save":
			vSave = vArguments.pop(0)
		elif vArgument == "--compare":
			vCompare = vArguments.pop(0)
		else:
			vFilters.append(vArgument)
	vBefore = load(vCompare) if vCompare else {}
	vDirectory = os.path.join(kRoot, "benchmarks")
	vSources = sorted(vName for vName in os.listdir(vDirectory) if vName.endswith(".kt"))
	vSources = [vName for vName in vSources if not vFilters or any(vFilter in vName for vFilter in vFilters)]
	os.makedirs(kOut, exist_ok=True)
	vLines = ["%-12s %8s %10s %10s %10s %9s" % ("benchmark", "seconds", "allocs", "retains", "releases", "bytes")]
	print(vLines[0])
	for vSource in vSources:
		vName = vSource[:-3]
		vRow = measure(os.path.join(vDirectory, vSource), vRuns, vOptions)
		vLine = "%-12s %8.3f %10d %10d %10d %9d" % ((vName,) + vRow)
		vLines.append(vLine)
		if vName in vBefore:
			vOld = vBefore[vName]
			vLine += "   time %s  allocs %s  retains %s  bytes %s" % (change(vRow[0], vOld[0]), change(vRow[1], vOld[1]), change(vRow[2], vOld[2]), change(vRow[4], vOld[4]))
		print(vLine, flush=True)
	with open(vSave, "w", encoding="utf-8") as vFile:
		vFile.write("\n".join(vLines) + "\n")


main()
