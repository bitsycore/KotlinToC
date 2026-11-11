#!/bin/bash
# Usage: tools/retain_profile.sh <path under ktc2/integration, without .kt>
# Runs one program with every retain in the generated C counted by source line, and prints
# the lines that retained most, with their text. For finding what the ARC pass leaves behind.
cd "$(dirname "$0")/.." || exit 1
bash tools/try.sh "$1" > /dev/null 2>&1
cd build/try/c || exit 1
cat >> ktc2_core.h <<'EOF'
void ktc2_profile_site(const char* file, int line);
EOF
cat >> ktc2_core.c <<'EOF'
#define PROFILE_SITES 65536
static struct { const char* file; int line; long count; } profile_sites[PROFILE_SITES];
static void profile_report(void)
{
	for (int i = 0; i < PROFILE_SITES; i++) {
		if (profile_sites[i].count) fprintf(stderr, "site %ld %s %d\n", profile_sites[i].count, profile_sites[i].file, profile_sites[i].line);
	}
}
void ktc2_profile_site(const char* file, int line)
{
	static int registered;
	if (!registered) { registered = 1; atexit(profile_report); }
	unsigned slot = ((unsigned)(uintptr_t)file * 31u + (unsigned)line * 2654435761u) % PROFILE_SITES;
	while (profile_sites[slot].count && (profile_sites[slot].file != file || profile_sites[slot].line != line)) slot = (slot + 1) % PROFILE_SITES;
	profile_sites[slot].file = file;
	profile_sites[slot].line = line;
	profile_sites[slot].count++;
}
EOF
for vFile in $(grep -rl "ktc2_retain(" --include=*.c . | grep -v ktc2_core); do
	sed -i -E 's/ktc2_retain\(/(ktc2_profile_site(__FILE__, __LINE__), ktc2_retain)(/g' "$vFile"
done
gcc -std=c11 -O1 -w -pthread -I . $(grep "^cflag=" ../ktc2_build.txt | cut -d= -f2-) -o ../profile.exe $(grep "^c=" ../ktc2_build.txt | cut -d= -f2-) -lm $(grep "^ldflag=" ../ktc2_build.txt | cut -d= -f2-) || exit 1
../profile.exe 2>&1 >/dev/null | grep "^site " | sort -k2 -n -r | head -${2:-25} | while read -r _ vCount vFile vLine; do
	printf '%8s  %s:%s  %s\n' "$vCount" "$vFile" "$vLine" "$(sed -n "${vLine}p" "$vFile" | sed -E 's/^\s+//' | cut -c1-150)"
done
