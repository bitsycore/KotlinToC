"""
Compares the reference-count statistics of two test runs (build/integration/arc-stats.txt,
which each run of the suite writes): totals, and the programs that changed most.

Usage (from ktc2/): python tools/arc_compare.py <before.txt> <after.txt>
"""
import re
import sys


def load(inPath):
	"""Program name -> {allocs, retains, releases, arena}."""
	vResult = {}
	for vLine in open(inPath, encoding='utf-8'):
		vName, _, vRest = vLine.partition(':')
		vResult[vName.strip()] = {vKey: int(vValue) for vKey, vValue in re.findall(r'(\w+)=(\d+)', vRest)}
	return vResult


vBefore, vAfter = load(sys.argv[1]), load(sys.argv[2])
vShared = sorted(set(vBefore) & set(vAfter))
for vKey in ('allocs', 'retains', 'releases'):
	vOld = sum(vBefore[vName][vKey] for vName in vShared)
	vNew = sum(vAfter[vName][vKey] for vName in vShared)
	print('%-9s %9d -> %9d  (%+.1f%%)' % (vKey, vOld, vNew, 100.0 * (vNew - vOld) / max(vOld, 1)))
vChanged = sorted(vShared, key=lambda vName: vAfter[vName]['retains'] - vBefore[vName]['retains'])
for vName in vChanged[:12] + vChanged[-3:]:
	print('  %-22s retains %7d -> %7d   releases %7d -> %7d' % (
		vName, vBefore[vName]['retains'], vAfter[vName]['retains'], vBefore[vName]['releases'], vAfter[vName]['releases']))
