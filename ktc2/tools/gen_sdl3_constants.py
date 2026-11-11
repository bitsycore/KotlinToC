"""
Fills the constant tables of ktc2/src/main/resources/ktc2/modules/sdl3/SDL3.kt (between its `// <constants>` and
`// </constants>` lines) from those of the v1 module (src/main/resources/modules/ktc.sdl3):
each `inline val SDL3.Group.Name get() = C.SDL_X` becomes `const val Name = SDL_X` inside
`object Group`, nested in `object SDL3`.

Usage (from ktc2/): python tools/gen_sdl3_constants.py <generated sdl3.kt>
The generated bindings are used to leave out constants the installed SDL3 does not have.
"""
import io
import re
import sys
from collections import OrderedDict

vV1 = '../src/main/resources/modules/ktc.sdl3/'
vFiles = ['InitFlags.kt', 'WindowFlags.kt', 'Event.kt', 'Key.kt', 'Mouse.kt', 'Blend.kt', 'Texture.kt', 'Cursor.kt', 'MessageBox.kt']
vKnown = set(re.findall(r'^public (?:const )?val (\w+)', io.open(sys.argv[1], encoding='utf-8').read(), flags=re.M))

vGroups = OrderedDict()
vMissing = []
for vFile in vFiles:
	for vLine in io.open(vV1 + vFile, encoding='utf-8'):
		vMatch = re.match(r'\s*inline val SDL3\.(\w+)\.(\w+)\s+get\(\)\s*=\s*C\.(\w+)', vLine)
		if not vMatch:
			continue
		vGroup, vName, vC = vMatch.groups()
		if vC in vKnown:
			vGroups.setdefault(vGroup, []).append((vName, vC))
		else:
			vMissing.append(vC)

vTitles = {
	'InitFlags': 'Subsystems for SDL3.initialize',
	'WindowFlags': 'Flags for SDL3.Window',
	'Event': 'Event types: compare with the type of a polled event',
	'Scancode': 'Physical keys, for isKeyDown and key events',
	'Mouse': 'Mouse buttons and wheel directions',
	'BlendMode': 'How drawing combines with what is already there',
	'TextureAccess': 'How a texture is going to be used',
	'PixelFormat': 'Pixel layouts of textures',
	'Flip': 'Mirroring for renderTextureRotated',
	'SystemCursor': 'Cursors the system provides',
	'MessageBoxFlags': 'Kinds of message box',
}
vOut = ''
for vGroup, vEntries in vGroups.items():
	vOut += '\n\t/** %s. */\n\tobject %s {\n' % (vTitles.get(vGroup, vGroup), vGroup)
	for vName, vC in vEntries:
		vOut += '\t\tconst val %s = %s\n' % (vName, vC)
	vOut += '\t}\n'

vPath = 'src/main/resources/ktc2/modules/sdl3/SDL3.kt'
vText = io.open(vPath, encoding='utf-8').read()
vStart = vText.index('// <constants>') + len('// <constants>')
vEnd = vText.index('\t// </constants>')
io.open(vPath, 'w', encoding='utf-8', newline='\n').write(vText[:vStart] + '\n' + vOut + '\n' + vText[vEnd:])
print('groups:', len(vGroups), 'constants:', sum(len(v) for v in vGroups.values()), 'missing:', vMissing)
