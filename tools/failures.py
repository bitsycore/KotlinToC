"""Prints, for each failed program of the last test run, the first lines of why it failed."""
import glob
import html
import re

for vFile in glob.glob('build/test-results/test/*.xml'):
	vText = open(vFile, encoding='utf-8', errors='replace').read()
	for vCase in re.finditer(r'<testcase name="([^"]+)"[^>]*>\s*<failure message="(.*?)" type=', vText, flags=re.S):
		vName, vMessage = vCase.group(1), html.unescape(vCase.group(2))
		vLines = [vLine.strip() for vLine in vMessage.splitlines() if vLine.strip()]
		# The ktc2 errors, the C compiler's errors, or the first difference in the output.
		vKept = [vLine for vLine in vLines if re.search(r'ktc2: error|error:|Exception|expected:|leak|kotlinc failed|ktc2 failed', vLine)]
		print('==', vName)
		for vLine in (vKept or vLines)[:4]:
			print('   ', vLine[:230])
