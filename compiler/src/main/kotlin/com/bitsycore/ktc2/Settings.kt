package com.bitsycore.ktc2

import java.io.File

/**
 * ktc2's settings, each taken from the first of these that gives it: the command (`--set key=value`, or the option
 * that stands for it), the environment (`KTC2_...`), the configuration file, ktc2's default.
 *
 * The configuration file is `KTC2_CONFIG` when that is set, else `config/ktc2.toml` next to ktc2's `bin` directory
 * (the release has one, with every setting in it as a comment). Its strings may say `${env:NAME}`.
 */
object Settings {
	/** Where a setting's value came from. */
	enum class Level(val text: String) { Default("default"), Config("config"), Environment("environment"), Command("command") }

	/** What kind of value a setting holds. */
	enum class Kind { Text, Flag, List }

	/**
	 * A setting: its key in the configuration file and for `--set`, the environment variable that gives it, its
	 * default, and what it is for. [inSplit] cuts the text of the environment variable into a list's items.
	 * The items of an [isAdded] list are those of every level, not of the first one that gives it (options of the C
	 * compiler: the configuration's and the command's).
	 */
	class Setting(
		val key: String,
		val environment: String?,
		val kind: Kind,
		val default: Any,
		val doc: String,
		val option: String? = null,
		val isAdded: Boolean = false,
		val split: (String) -> List<String> = { it.split(',').map(String::trim).filter(String::isNotEmpty) },
	)

	/** The text of `KTC2_CMAKE_ARGS`: arguments separated by spaces before a `-`, so that a value may hold spaces. */
	private val kSplitArguments: (String) -> List<String> = { it.split(Regex("\\s+(?=-)")).map(String::trim).filter(String::isNotEmpty) }

	/** Every setting, in the order `--settings` shows them. */
	val kAll: List<Setting> = listOf(
		Setting("cache", "KTC2_CACHE", Kind.Text, "~/.ktc2/cache", "where ktc2 keeps the sources it fetches"),
		Setting("maven", "KTC2_MAVEN", Kind.List, listOf("https://repo1.maven.org/maven2"),
			"the Maven repositories sources are fetched from, in order; in the file `[[maven]]` tables, each with a `url` and, when it needs them, `user` and `password` or `token`"),
		Setting("build.cc", "KTC2_CC", Kind.Text, "", "the C compiler (default: the first of gcc, clang, cc on the path)"),
		Setting("build.cmake", "KTC2_CMAKE", Kind.Flag, false, "build with CMake, as --cmake", option = "--cmake"),
		Setting("build.cmakeArgs", "KTC2_CMAKE_ARGS", Kind.List, emptyList<String>(), "more arguments for CMake's configuration", split = kSplitArguments),
		Setting("build.lto", "KTC2_LTO", Kind.Text, "auto", "link-time optimization of a release: true, false, or auto (on, but with clang on Windows only when lld-link is there)"),
		Setting("build.macosArch", "KTC2_MACOS_ARCH", Kind.List, emptyList<String>(),
			"on macOS, the architectures the program is built for: arm64, x86_64, or both for a universal program (--universal); none: the machine's own", option = "--universal"),
		Setting("build.debug", "KTC2_BUILD_DEBUG", Kind.Flag, false, "build without optimization, with debug information and the leak report", option = "--debug"),
		Setting("build.compilerOpts", "KTC2_COPT", Kind.List, emptyList<String>(), "options for the C compiler", option = "-copt", isAdded = true, split = kSplitArguments),
		Setting("build.linkerOpts", "KTC2_LOPT", Kind.List, emptyList<String>(), "options for the linker", option = "-lopt", isAdded = true, split = kSplitArguments),
		Setting("checks.assertions", "KTC2_ASSERTIONS", Kind.Flag, false, "assert() is checked (as Kotlin/Native's -enable-assertions)", option = "-ea"),
		Setting("checks.failure", "KTC2_CHECKS", Kind.Text, "throw", "an index out of bounds or a division by zero: throw, or abort the program", option = "--checks="),
		Setting("warnings.heap", "KTC2_WARN_HEAP", Kind.Flag, false, "list every allocation left on the heap, with the reason", option = "--warn-heap"),
		Setting("warnings.cycles", "KTC2_WARN_CYCLES", Kind.Flag, true, "report classes that can form reference cycles", option = "--no-warn-cycles"),
		Setting("output.quiet", "KTC2_QUIET", Kind.Flag, false, "no line for each step", option = "-q"),
		Setting("daemon.enabled", "KTC2_DAEMON", Kind.Flag, true, "hand commands to a compiler process that stays between them"),
		Setting("daemon.keepLibrary", "KTC2_KEEP_LIBRARY", Kind.Text, "auto", "keep the analysed library between compilations: true, false, or auto (in the process that stays)"),
		Setting("debug.dumpIr", "KTC2_DUMP_IR", Kind.Flag, false, "also write the IR of the Kotlin compiler and ktc2's after each pass (kir/)", option = "--dump-ir"),
		Setting("debug.dumpKir", "KTC2_DUMP_KIR", Kind.Text, "", "the functions whose KIR is written, by a part of their C name (all: every one)"),
		Setting("debug.skip", "KTC2_SKIP", Kind.List, emptyList<String>(),
			"optimizations left out: devirtualize, copies, listloops, ranges, handover, brief, walk, owned, slots, rooms, inline"),
		Setting("debug.timing", "KTC2_TIMING", Kind.Flag, false, "print where compile time goes"),
		Setting("debug.borrow", "KTC2_DEBUG_BORROW", Kind.Flag, false, "explain reference-count decisions"),
		Setting("debug.cycles", "KTC2_DEBUG_CYCLES", Kind.Flag, false, "report the cycles of the library's own classes too"),
		Setting("debug.passes", "KTC2_DEBUG_PASSES", Kind.Flag, false, "print how many calls were made direct and array accesses proven in range"),
	)

	private val kByKey = kAll.associateBy { it.key }

	/** The environment of the command being served by the process that stays; null: this process's own. */
	@Volatile
	var commandEnvironment: Map<String, String>? = null

	/** The environment variable [inName] of the command. */
	fun environment(inName: String): String? {
		val vCommand = commandEnvironment ?: return System.getenv(inName)
		return vCommand[inName]
	}

	/** A Maven repository, and what it is entered with. */
	class Repository(val url: String, val user: String? = null, val password: String? = null, val token: String? = null) {
		/** Its address as it is compared with another one's: no `/` at its end. */
		val key: String get() = url.trimEnd('/')

		val hasCredentials: Boolean get() = !user.isNullOrEmpty() || !token.isNullOrEmpty()

		override fun toString(): String = url + when {
			!token.isNullOrEmpty() -> " (token)"
			!user.isNullOrEmpty() -> " (user $user)"
			else -> ""
		}

		companion object {
			/** The repository a value of TOML describes: an address, or a table with `url`. Null when it is neither. */
			fun of(inValue: Any?): Repository? = when (inValue) {
				is String -> Repository(expandEnvironment(inValue))
				is Map<*, *> -> (inValue["url"] as? String)?.let { vUrl ->
					fun text(inKey: String) = (inValue[inKey] as? String)?.let(::expandEnvironment)
					Repository(expandEnvironment(vUrl), text("user"), text("password"), text("token"))
				}
				else -> null
			}
		}
	}

	/** The configuration file read for this command, if there is one. */
	var configFile: File? = null
		private set

	/** The text of [configFile], as read: part of what decides which compiler process serves a command. */
	var configText: String = ""
		private set

	private var mConfig: Map<String, Any> = emptyMap()

	/** What the command set (`--set`, or an option), by key: text, flag or list. */
	private val mCommand = HashMap<String, Any>()

	/** The configuration file this ktc2 reads: `KTC2_CONFIG`, else `config/ktc2.toml` of its installation. */
	private fun findConfig(): File? {
		environment("KTC2_CONFIG")?.takeIf { it.isNotEmpty() }?.let { return File(it) }
		// The tool is `<installation>/lib/ktc2-<version>.jar`.
		val vJar = runCatching { File(Settings::class.java.protectionDomain.codeSource.location.toURI()) }.getOrNull() ?: return null
		val vLib = vJar.parentFile ?: return null
		if (vLib.name != "lib") return null
		return File(vLib.parentFile, "config/ktc2.toml").takeIf { it.isFile }
	}

	/**
	 * Reads the configuration again and forgets what the last command set: done before each command.
	 *
	 * @return null, or what is wrong with the configuration file
	 */
	fun reset(): String? {
		mCommand.clear()
		mConfig = emptyMap()
		configText = ""
		configFile = findConfig()
		val vFile = configFile ?: return null
		if (!vFile.isFile) return "no configuration file '$vFile' (KTC2_CONFIG)"
		configText = vFile.readText()
		mConfig = try {
			Toml.parse(configText, vFile.path)
		} catch (vError: Toml.Error) {
			return "error: ${vError.message}"
		}
		return unknownKeys(mConfig, "").firstOrNull()?.let { "error: ${vFile.path}: no setting '$it' (ktc2 --settings lists them)" }
	}

	/** The keys of [inTable] that are no setting, with their table's [inPrefix]. */
	private fun unknownKeys(inTable: Map<String, Any>, inPrefix: String): List<String> = inTable.flatMap { (vKey, vValue) ->
		val vFull = inPrefix + vKey
		when {
			vFull in kByKey -> emptyList()
			vValue is Map<*, *> && kAll.any { it.key.startsWith("$vFull.") } -> @Suppress("UNCHECKED_CAST") unknownKeys(vValue as Map<String, Any>, "$vFull.")
			else -> listOf(vFull)
		}
	}

	/** The value of [inSetting] in the configuration file, if it has one. */
	private fun configValue(inSetting: Setting): Any? {
		var vValue: Any? = mConfig
		for (vPart in inSetting.key.split('.')) vValue = (vValue as? Map<*, *>)?.get(vPart) ?: return null
		return vValue
	}

	/** [inValue] of the configuration file or of `--set` as [inSetting]'s kind; null after reporting that it is not. */
	private fun convert(inSetting: Setting, inValue: Any, inWhere: String): Any? {
		fun wrong(): Nothing? {
			System.err.println("ktc2: warning: $inWhere: '${inSetting.key}' takes ${when (inSetting.kind) {
				Kind.Text -> "a text"
				Kind.Flag -> "true or false"
				Kind.List -> "a list"
			}}; '$inValue' is left out")
			return null
		}
		return when (inSetting.kind) {
			Kind.Text -> when (inValue) {
				is String -> expandEnvironment(inValue)
				is Boolean, is Long -> inValue.toString()
				else -> wrong()
			}
			Kind.Flag -> when (inValue) {
				is Boolean -> inValue
				is String -> flagOf(inValue) ?: wrong()
				else -> wrong()
			}
			Kind.List -> when (inValue) {
				is List<*> -> inValue.map { if (it is String) expandEnvironment(it) else it }
				is String -> listOf(expandEnvironment(inValue))
				else -> wrong()
			}
		}
	}

	/** A flag's text: `1`, `true`, `yes`, `on` or the opposite; null for anything else. */
	private fun flagOf(inText: String): Boolean? = when (inText.trim().lowercase()) {
		"1", "true", "yes", "on" -> true
		"0", "false", "no", "off" -> false
		else -> null
	}

	/** The value of [inSetting] the environment gives, if it gives one. */
	private fun environmentValue(inSetting: Setting): Any? {
		val vText = environment(inSetting.environment ?: return null) ?: return null
		return when (inSetting.kind) {
			Kind.Text -> vText
			// A debugging switch was on as soon as its variable was there (`KTC2_TIMING=` included).
			Kind.Flag -> flagOf(vText) ?: true
			Kind.List -> inSetting.split(vText)
		}
	}

	/** The value of the setting [inKey] and where it came from. */
	fun resolve(inKey: String): Pair<Any, Level> {
		val vSetting = kByKey[inKey] ?: error("no setting '$inKey'")
		val vCommand = mCommand[inKey]
		val vEnvironment = environmentValue(vSetting)
		val vConfig = configValue(vSetting)?.let { convert(vSetting, it, configFile?.path ?: "configuration") }
		if (vSetting.isAdded) {
			val vAll = listOfNotNull(vConfig, vEnvironment, vCommand).flatMap { it as List<*> }.map { it.toString() }
			val vLevel = when {
				vCommand != null -> Level.Command
				vEnvironment != null -> Level.Environment
				vConfig != null -> Level.Config
				else -> Level.Default
			}
			return (if (vLevel == Level.Default) vSetting.default else vAll) to vLevel
		}
		return when {
			vCommand != null -> vCommand to Level.Command
			vEnvironment != null -> vEnvironment to Level.Environment
			vConfig != null -> vConfig to Level.Config
			else -> vSetting.default to Level.Default
		}
	}

	fun text(inKey: String): String = resolve(inKey).first as String

	fun flag(inKey: String): Boolean = resolve(inKey).first as Boolean

	@Suppress("UNCHECKED_CAST")
	fun list(inKey: String): List<String> = (resolve(inKey).first as List<Any>).map { it.toString() }

	/** True when the setting [inKey] is the text `true`, false for `false`, null for `auto` or anything else. */
	fun choice(inKey: String): Boolean? = flagOf(text(inKey))

	/** Sets [inKey] for this command; false after reporting a key or a value that is wrong. */
	fun set(inKey: String, inValue: Any): Boolean {
		val vSetting = kByKey[inKey] ?: run {
			System.err.println("ktc2: no setting '$inKey' (ktc2 --settings lists them)")
			return false
		}
		val vValue = when {
			// `--set build.compilerOpts=-O3` adds one option, as -copt does; `--set maven=a,b` gives a list.
			vSetting.kind == Kind.List && inValue is String -> if (vSetting.isAdded) listOf(inValue) else vSetting.split(inValue)
			else -> convert(vSetting, inValue, "--set") ?: return false
		}
		mCommand[inKey] = if (vSetting.isAdded) ((mCommand[inKey] as? List<*>) ?: emptyList<Any>()) + (vValue as List<*>) else vValue
		return true
	}

	/** Where ktc2 keeps what it fetches. */
	fun cacheDirectory(): File {
		val vPath = text("cache")
		return if (vPath.startsWith("~/") || vPath == "~") File(System.getProperty("user.home"), vPath.removePrefix("~").removePrefix("/")) else File(vPath)
	}

	/**
	 * The Maven repositories, in order. Those the environment or the command give are addresses only: what they are
	 * entered with comes from the configuration file's entry of the same address ([credentialsFor]).
	 */
	fun repositories(): List<Repository> {
		val (vValue, vLevel) = resolve("maven")
		val vList = (vValue as List<*>).mapNotNull { Repository.of(it) }
		return if (vLevel == Level.Config) vList else vList.map { credentialsFor(it) }
	}

	/** [inRepository] with what the configuration file says it is entered with, when it says nothing itself. */
	fun credentialsFor(inRepository: Repository): Repository {
		if (inRepository.hasCredentials) return inRepository
		val vConfigured = (configValue(kByKey.getValue("maven")) as? List<*>)?.mapNotNull { Repository.of(it) } ?: return inRepository
		return vConfigured.firstOrNull { it.key == inRepository.key && it.hasCredentials } ?: inRepository
	}

	/** Every setting and where its value came from, as `--settings` prints them. */
	fun describe(): String = buildString {
		appendLine("configuration file: ${configFile?.path ?: "none (KTC2_CONFIG, or config/ktc2.toml next to bin/)"}")
		val vWidth = kAll.maxOf { it.key.length }
		for (vSetting in kAll) {
			val (vValue, vLevel) = resolve(vSetting.key)
			val vText = when {
				vSetting.key == "maven" -> repositories().joinToString(", ")
				vValue is List<*> -> vValue.joinToString(" ").ifEmpty { "(none)" }
				vValue == "" -> "(none)"
				else -> vValue.toString()
			}
			append(vSetting.key.padEnd(vWidth)).append("  ").append(vText.padEnd(32)).append("  ").append(vLevel.text)
			vSetting.environment?.let { append(", ").append(it) }
			vSetting.option?.let { append(", ").append(it) }
			appendLine()
		}
	}

	/** Every setting that changes the C a translation makes, with its value: part of what decides whether to make it again. */
	fun stamp(): List<String> = kAll.filter { !it.key.startsWith("output.") && !it.key.startsWith("daemon.") && it.key != "maven" && it.key != "cache" }
		.map { "${it.key}=${resolve(it.key).first}" }
}
