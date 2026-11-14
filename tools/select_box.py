"""Copies the box tests of a directory that every back end runs, Native's included, unchanged.
Usage: python -I select_box.py <source dir> <target dir>"""
import os, re, shutil, sys
vSource, vTarget = sys.argv[1], sys.argv[2]
vTaken, vLeft = 0, {}
def leave(inWhy):
	vLeft[inWhy] = vLeft.get(inWhy, 0) + 1
for vRoot, _, vFiles in os.walk(vSource):
	for vFile in sorted(vFiles):
		if not vFile.endswith(".kt"):
			continue
		vPath = os.path.join(vRoot, vFile)
		vText = open(vPath, encoding="utf-8").read()
		if re.search(r"(?m)^// (FILE|MODULE):", vText): leave("several files"); continue
		vTarget_ = re.search(r"(?m)^// TARGET_BACKEND: (.*)$", vText)
		if vTarget_ and "NATIVE" not in vTarget_.group(1): leave("one back end"); continue
		if re.search(r"(?m)^// (IGNORE_BACKEND|IGNORE_BACKEND_K2|IGNORE_NATIVE)[A-Z_]*: .*(NATIVE|ANY)", vText): leave("ignored on Native"); continue
		if re.search(r"(?m)^// (DISABLE_NATIVE|LANGUAGE|FULL_JDK|WITH_REFLECT|JVM_TARGET|API_VERSION)", vText): leave("directive"); continue
		if re.search(r"TailCallOptimizationChecker|kotlin\.coroutines\.jvm|\bjava\.|@JvmStatic|@JvmField|@JvmOverloads|@JvmName", vText): leave("JVM"); continue
		vRelative = os.path.relpath(vPath, vSource)
		vOut = os.path.join(vTarget, vRelative)
		os.makedirs(os.path.dirname(vOut), exist_ok=True)
		shutil.copyfile(vPath, vOut)
		vTaken += 1
print("taken", vTaken, "left", vLeft)
