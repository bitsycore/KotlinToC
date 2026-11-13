#!/bin/bash
# Usage: tools/jvmdiff.sh <Name>
# After a run of the suite that kept build/integration/<Name>: the difference between what the
# C program prints and what the JVM prints for the same program ("<" is C, ">" the JVM).
cd "$(dirname "$0")/../build/integration/$1" || exit 1
vJar=$(ls ~/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/*/*/kotlin-stdlib-[0-9]*.jar | grep -v sources | tail -1)
vMain=$(grep "^jvmMain=" ktc2_build.txt | cut -d= -f2-)
# The standard exceptions are classes of java.lang and java.util on the JVM, of kotlin here: either name is the same.
vSame='s/java\.\(lang\|util\)\.\([A-Z]\)/kotlin.\2/g'
diff <(./program.exe 2>&1 | grep -v "^ktc2: arc:" | sed "$vSame") <(java -Dstdout.encoding=UTF-8 -Duser.language=en -Duser.country=US -cp "jvm:$vJar:$(dirname "$0")/../../install/ktc2/lib/*" "$vMain" 2>&1 | sed "$vSame") | cut -c1-400
