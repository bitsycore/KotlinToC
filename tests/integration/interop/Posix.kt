@file:OptIn(ExperimentalForeignApi::class)

package interop

import kotlinx.cinterop.*
import platform.posix.*

fun compareBytes(inLeft: COpaquePointer?, inRight: COpaquePointer?): Int =
	inLeft!!.reinterpret<ByteVar>().pointed.value - inRight!!.reinterpret<ByteVar>().pointed.value

fun main() {
	// Formatted output of the C library, on the same stream as println.
	printf("%s has %d letters, pi is %.3f\n", "interop", 7, 3.14159)
	fflush(stdout)
	println(strlen("hello, C"))
	println(sqrt(144.0))
	println(pow(2.0, 10.0))
	println(abs(-5) + atoi("37"))
	println(toupper('a'.code).toChar())
	println(isdigit('7'.code) != 0)

	memScoped {
		// Memory functions on Kotlin and C memory.
		val vBuffer = allocArray<ByteVar>(32)
		strcpy(vBuffer, "dcba")
		qsort(vBuffer, 4u, 1u, staticCFunction(::compareBytes))
		println(vBuffer.toKString())
		strcat(vBuffer, "-xyz")
		println(vBuffer.toKString())
		println(strcmp(vBuffer.toKString(), "abcd-xyz"))
		println(strchr(vBuffer.toKString(), 'x'.code) != null)
		memset(vBuffer, 'z'.code, 3u)
		println(vBuffer.toKString())
		snprintf(vBuffer, 32u, "%05d|%-4s|%x", 42, "ab", 255)
		println(vBuffer.toKString())

		// Numbers out of text, with an out parameter.
		val vEnd = alloc<CPointerVar<ByteVar>>()
		println(strtol("123abc", vEnd.ptr, 10))
		println(vEnd.value?.toKString())
		println(strtod("2.5e2", null))

		// A file written and read back.
		val vPath = "ktc2_posix_test.txt"
		val vFile = fopen(vPath, "w")
		fputs("first line\nsecond line\n", vFile)
		fprintf(vFile, "%d %s\n", 3, "third")
		fclose(vFile)
		val vInput = fopen(vPath, "r")
		val vLine = allocArray<ByteVar>(64)
		var vCount = 0
		while (fgets(vLine, 64, vInput) != null) {
			vCount++
			print("$vCount: ${vLine.toKString()}")
		}
		fclose(vInput)
		println(remove(vPath))
		println(fopen("no/such/dir/file", "r") == null)
		println(errno == ENOENT)
	}

	val vMemory = malloc(16u)!!.reinterpret<IntVar>()
	vMemory[0] = 11
	vMemory[3] = 44
	println(vMemory[0] + vMemory[3])
	free(vMemory)
	println(RAND_MAX > 0)
	println(EXIT_SUCCESS)
	println("done")
}
