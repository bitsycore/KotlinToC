"""
Adjusts the programs copied from v1 (integration/v1) to standard Kotlin:
`fun main(): Int` becomes `mainImpl` with a plain `main` calling it, programs written in the
v1 dialect are removed, and missing imports are added.

Usage (from ktc2/): python tools/port_v1.py
"""
import glob
import io
import os
import re

# Programs that are not standard Kotlin (v1 dialect: Heap, dispose, @SimpleUnion, runes, erased type checks...).
vDialect = ['EnumOverrideTest', 'InitBlockTest', 'MemberInfixTest', 'ObjectAnyTest', 'SimpleUnionTest', 'SmartCastTest', 'UnionTest', 'Utf8Test']

for vPath in glob.glob('integration/v1/*.kt'):
	vName = os.path.splitext(os.path.basename(vPath))[0]
	if vName in vDialect:
		os.remove(vPath)
		continue
	vText = io.open(vPath, encoding='utf-8').read()
	vNew = vText
	if re.search(r'^fun main\(\)\s*:\s*Int\b', vNew, flags=re.M):
		vNew = re.sub(r'^fun main\(\)\s*:\s*Int\b', 'fun mainImpl(): Int', vNew, flags=re.M)
		vNew = vNew.rstrip('\n') + '\n\nfun main() {\n\tmainImpl()\n}\n'
	if vName == 'MathTest' and 'import kotlin.math' not in vNew:
		vMatch = re.search(r'^package .*\n', vNew, flags=re.M)
		vAt = vMatch.end() if vMatch else 0
		vNew = vNew[:vAt] + '\nimport kotlin.math.*\n' + vNew[vAt:]
	if vNew != vText:
		io.open(vPath, 'w', encoding='utf-8', newline='\n').write(vNew)
		print('adjusted', vName)
