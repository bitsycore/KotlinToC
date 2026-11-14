package com.bitsycore.ktc2

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.PrintStream
import java.lang.management.ManagementFactory
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom

/*
A compilation spends most of its time warming up: the Java machine starts, loads the Kotlin
compiler and runs it for the first time. A process that stays does that once. `ktc2` therefore
hands its command to a process it starts the first time and that waits for the next ones: the
compiler kept running.

The process listens on a port of this machine only. `~/.ktc2/daemon.txt` holds the port, a
secret a command has to show, and a key made of what the process was started with: the
program files of ktc2, the Java machine, and the environment variables ktc2 reads. A command
whose key differs stops the process and starts another, so a rebuilt ktc2 or another
`KTC2_CC` never meets a process that knows the old one. Without commands for
[kIdleMinutes] minutes the process ends.

`KTC2_DAEMON=0` runs every command in its own process, as before; `ktc2 --stop-daemon` ends
the process.
*/

// ==================
// MARK: What is said over the connection
// ==================

/*
The command sends the secret, its working directory and its arguments. The process answers
with parts, each a byte saying what it is: text for standard output or for standard error (a
length and that many bytes), a program for the command to run in the process's place (its
path and arguments, for `--run`: the program has to have the terminal of the command), or the
status the command exits with, which ends the answer.
*/
private const val kOut = 1
private const val kErr = 2
private const val kExit = 3
private const val kRun = 4

/** `ktc2 --stop-daemon`. */
const val kStopDaemon = "--stop-daemon"

/** What the process itself is started with, followed by its key. */
const val kServeDaemon = "--serve-daemon"

private const val kIdleMinutes = 30

/** Where the process says how to reach it. */
private fun stateFile(): File = File(File(System.getProperty("user.home"), ".ktc2"), "daemon.txt")

/**
 * Leaves [inFile] to its owner alone, where the system has such rights (on Windows the home
 * directory is the user's already). The state file holds the secret: whoever can read it can
 * have the process compile and run tools as the user who started it.
 */
private fun ownerOnly(inFile: File) {
	inFile.setReadable(false, false)
	inFile.setWritable(false, false)
	inFile.setExecutable(false, false)
	inFile.setReadable(true, true)
	inFile.setWritable(true, true)
	if (inFile.isDirectory) inFile.setExecutable(true, true)
}

/** What a process has to have been started with to serve this command. */
private fun daemonKey(): String {
	val vText = StringBuilder()
	for (vEntry in System.getProperty("java.class.path").split(File.pathSeparator)) {
		val vFile = File(vEntry)
		vText.append(vFile.absolutePath).append('@').append(vFile.lastModified()).append(';')
	}
	vText.append(System.getProperty("java.home")).append(';')
	System.getProperties().stringPropertyNames().filter { it.startsWith("ktc2.") }.sorted().forEach { vText.append(it).append('=').append(System.getProperty(it)).append(';') }
	System.getenv().filterKeys { it.startsWith("KTC2_") || it.equals("PATH", ignoreCase = true) }.toSortedMap().forEach { (vName, vValue) -> vText.append(vName).append('=').append(vValue).append(';') }
	return MessageDigest.getInstance("SHA-256").digest(vText.toString().toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
}

/** The process as its state file describes it. */
private class State(val key: String, val port: Int, val secret: String)

private fun readState(): State? {
	val vLines = try {
		stateFile().readLines()
	} catch (vError: IOException) {
		return null
	}
	val vPort = vLines.getOrNull(1)?.toIntOrNull() ?: return null
	return State(vLines[0], vPort, vLines.getOrNull(2) ?: return null)
}

// ==================
// MARK: The command's side
// ==================

/**
 * Hands the command [inArguments] to the process that stays, starting it when there is none
 * or when it was started with something else.
 *
 * @return what the command exits with, or null when no process could be reached: the caller then runs the command itself
 */
fun runThroughDaemon(inArguments: Array<String>): Int? {
	if (inArguments.firstOrNull() == kStopDaemon) {
		readState()?.let { send(it, arrayOf(kStopDaemon)) }
		return 0
	}
	if (System.getenv("KTC2_DAEMON") == "0") return null
	return try {
		val vKey = daemonKey()
		var vState = readState()
		if (vState != null && vState.key != vKey) {
			send(vState, arrayOf(kStopDaemon))
			vState = null
		}
		(vState?.let { send(it, inArguments) }) ?: start(vKey)?.let { send(it, inArguments) }
	} catch (vError: IOException) {
		null
	}
}

/** Starts the process and waits until it says where it listens; null when it does not come up. */
private fun start(inKey: String): State? {
	val vFile = stateFile()
	vFile.parentFile.mkdirs()
	ownerOnly(vFile.parentFile)
	vFile.delete()
	val vLog = File(vFile.parentFile, "daemon.log")
	vLog.createNewFile()
	ownerOnly(vLog)
	val vJava = File(System.getProperty("java.home"), "bin/java").path
	// As this process was started, but compiling at full speed: it runs long enough to gain from it.
	val vOptions = ManagementFactory.getRuntimeMXBean().inputArguments.filter { !it.startsWith("-XX:TieredStopAtLevel") && !it.startsWith("-agentlib") && !it.startsWith("-javaagent") }
	val vCommand = listOf(vJava) + vOptions + listOf("-cp", System.getProperty("java.class.path"), "com.bitsycore.ktc2.MainKt", kServeDaemon, inKey)
	val vIsWindows = "win" in System.getProperty("os.name").lowercase()
	// Nothing of the command's terminal: the process outlives the command.
	ProcessBuilder(vCommand).redirectInput(ProcessBuilder.Redirect.from(File(if (vIsWindows) "NUL" else "/dev/null")))
		.redirectOutput(vLog).redirectErrorStream(true).start()
	repeat(200) {
		Thread.sleep(25)
		readState()?.takeIf { it.key == inKey }?.let { return it }
	}
	return null
}

/**
 * Sends [inArguments] to the process, and copies what it prints until it says what the command
 * exits with. Null when the process is not there (anymore) or did not take the command.
 */
private fun send(inState: State, inArguments: Array<String>): Int? {
	val vSocket = try {
		Socket(InetAddress.getLoopbackAddress(), inState.port)
	} catch (vError: IOException) {
		return null
	}
	var vRun: List<String>? = null
	var vStatus: Int? = null
	var vGotAnswer = false
	try {
		vSocket.use {
			val vTo = DataOutputStream(vSocket.getOutputStream().buffered())
			vTo.writeUTF(inState.secret)
			vTo.writeUTF(File("").absolutePath)
			vTo.writeInt(inArguments.size)
			inArguments.forEach { vTo.writeUTF(it) }
			vTo.flush()
			val vFrom = DataInputStream(vSocket.getInputStream().buffered())
			while (vStatus == null) {
				val vKind = vFrom.readByte().toInt()
				vGotAnswer = true
				when (vKind) {
					kOut, kErr -> {
						val vBytes = ByteArray(vFrom.readInt())
						vFrom.readFully(vBytes)
						val vStream = if (vKind == kOut) System.out else System.err
						vStream.write(vBytes)
						vStream.flush()
					}
					kRun -> vRun = List(vFrom.readInt()) { vFrom.readUTF() }
					kExit -> vStatus = vFrom.readInt()
					else -> throw IOException("unexpected answer")
				}
			}
		}
	} catch (vError: IOException) {
		// Nothing was done yet: the caller runs the command itself. Otherwise the command failed half-way.
		if (!vGotAnswer) return null
		System.err.println("ktc2: the compiler process went away (${vError.message}); KTC2_DAEMON=0 runs without it")
		return 1
	}
	val vProgram = vRun ?: return vStatus
	if (vStatus != 0) return vStatus
	return ProcessBuilder(vProgram).inheritIO().start().waitFor()
}

// ==================
// MARK: The process's side
// ==================

/** Set in the process that stays: what a tool it starts prints goes to the command, not to a terminal. */
var gIsDaemon = false
	private set

/** Text printed by the command being served, sent as parts of kind [mKind]. */
private class PartStream(private val mTo: DataOutputStream, private val mKind: Int) : OutputStream() {
	override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

	override fun write(b: ByteArray, off: Int, len: Int) {
		if (len == 0) return
		synchronized(mTo) {
			mTo.writeByte(mKind)
			mTo.writeInt(len)
			mTo.write(b, off, len)
			mTo.flush()
		}
	}
}

/**
 * The process that stays: takes one command after the other, each run by [inRun] with the
 * directory it was given in, until none comes for [kIdleMinutes] minutes or one says stop.
 *
 * @param inRun runs a command; what it returns is the status and, for `--run`, the program the command is to start
 */
fun serveDaemon(inKey: String, inRun: (Array<String>, File) -> Pair<Int, List<String>?>) {
	gIsDaemon = true
	val vSecret = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
	val vServer = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
	vServer.soTimeout = kIdleMinutes * 60 * 1000
	val vFile = stateFile()
	// Closed to the others before the secret is in it.
	vFile.delete()
	vFile.createNewFile()
	ownerOnly(vFile)
	vFile.writeText("$inKey\n${vServer.localPort}\n$vSecret\n")
	val vOut = System.out
	val vErr = System.err
	try {
		while (true) {
			val vSocket = try {
				vServer.accept()
			} catch (vError: SocketTimeoutException) {
				break
			}
			var vStops = false
			try {
				vSocket.use {
					val vFrom = DataInputStream(vSocket.getInputStream().buffered())
					val vTo = DataOutputStream(vSocket.getOutputStream().buffered())
					if (vFrom.readUTF() != vSecret) return@use
					val vDirectory = File(vFrom.readUTF())
					val vArguments = Array(vFrom.readInt()) { vFrom.readUTF() }
					if (vArguments.firstOrNull() == kStopDaemon) {
						vStops = true
						vTo.writeByte(kExit)
						vTo.writeInt(0)
						vTo.flush()
						return@use
					}
					System.setOut(PrintStream(PartStream(vTo, kOut), true))
					System.setErr(PrintStream(PartStream(vTo, kErr), true))
					val (vStatus, vRun) = try {
						inRun(vArguments, vDirectory)
					} catch (vError: Throwable) {
						if (vError is IOException && vSocket.isClosed) throw vError
						vError.printStackTrace()
						1 to null
					} finally {
						System.out.flush()
						System.err.flush()
						System.setOut(vOut)
						System.setErr(vErr)
					}
					synchronized(vTo) {
						if (vRun != null) {
							vTo.writeByte(kRun)
							vTo.writeInt(vRun.size)
							vRun.forEach { vTo.writeUTF(it) }
						}
						vTo.writeByte(kExit)
						vTo.writeInt(vStatus)
						vTo.flush()
					}
				}
			} catch (vError: IOException) {
				// The command went away: on to the next one.
				System.setOut(vOut)
				System.setErr(vErr)
			}
			if (vStops) break
		}
	} finally {
		// Only its own entry: another process may have taken over meanwhile.
		if (readState()?.secret == vSecret) vFile.delete()
		vServer.close()
	}
}

/**
 * Starts the tool [inCommand] (a C compiler, CMake) in [inDirectory] and waits for it. What it
 * prints goes where this process prints: straight to the terminal, or, in the process that
 * stays, to the command being served.
 *
 * @return its exit status
 * @throws IOException when it cannot be started
 */
/**
 * [inCommand] as it can be started: a command line has a longest length (32767 characters on Windows, which a
 * program of a few hundred C files passes), so the arguments of a long one go to a file in [inDirectory] that
 * the compiler reads (`@file`, which gcc and clang take).
 */
fun startable(inCommand: List<String>, inDirectory: File?): List<String> {
	if (inDirectory == null || inCommand.sumOf { it.length + 3 } < 30000 || File(inCommand[0]).nameWithoutExtension == "cl") return inCommand
	val vArguments = File(inDirectory, "ktc2_arguments.txt")
	vArguments.writeText(inCommand.drop(1).joinToString("\n") { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" })
	return listOf(inCommand[0], "@" + vArguments.name)
}

fun runTool(inTyped: List<String>, inDirectory: File?): Int {
	val inCommand = startable(inTyped, inDirectory)
	val vBuilder = ProcessBuilder(inCommand)
	inDirectory?.let { vBuilder.directory(it) }
	if (!gIsDaemon) return vBuilder.inheritIO().start().waitFor()
	val vProcess = vBuilder.redirectErrorStream(true).redirectInput(ProcessBuilder.Redirect.from(File(if ("win" in System.getProperty("os.name").lowercase()) "NUL" else "/dev/null"))).start()
	val vBuffer = ByteArray(8192)
	val vFrom = vProcess.inputStream
	while (true) {
		val vCount = vFrom.read(vBuffer)
		if (vCount < 0) break
		System.err.write(vBuffer, 0, vCount)
		System.err.flush()
	}
	return vProcess.waitFor()
}
