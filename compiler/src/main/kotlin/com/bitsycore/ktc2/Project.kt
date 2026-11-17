package com.bitsycore.ktc2

import java.io.File

/** Name of the file that describes a module, in its directory. */
const val kModuleFileName = "module.ktc2.toml"

// ==================
// MARK: Module file
// ==================

/**
 * A module: a directory with a `module.ktc2.toml`.
 *
 * ```toml
 * name         = "demo"
 * executable   = "demo"                  # the program built; default: the name
 * main         = "app.start"             # the function the program starts with; default: the one `main`
 * sources      = ["src"]                 # files and directories; default: the module directory
 * dependencies = ["sdl3", "../shared",   # a module shipped with ktc2, a path to one,
 *                 "https://github.com/user/repo.git#modules/ui@v1.2"]   # or one in a git repository
 * fetch        = ["SDL3=https://github.com/libsdl-org/SDL.git@release-3.4.8"]   # C sources to have at hand
 * compilerOpts = ["-I${SDL3}/include"]   # for the C compiler; ${NAME} is where a fetched repository is
 * linkerOpts   = ["-L/opt/sdl/lib"]      # for the linker, when the program is built without CMake
 * cmake        = "module.cmake"          # included by the generated CMakeLists.txt
 * weak         = ["kotlinx.io.Segment.prev"]   # properties of its sources that are @Weak
 * acyclic      = ["kotlinx.io.Segment.next"]   # and those that are @Acyclic
 * optIn        = ["kotlinx.cinterop.ExperimentalForeignApi"]   # what its sources are compiled opted in to
 * version      = "0.9.3"                 # the version of the library it takes as it is
 * upstream     = ["core = org.jetbrains.kotlinx:kotlinx-io-core",   # where that library's sources come from
 *                 "src = git:https://github.com/user/lib.git@v${version}"]
 * jvm          = ["org.jetbrains.kotlinx:kotlinx-io-core-jvm"]   # its jars, for the suite's JVM side
 *
 * [[maven]]                              # a repository its upstream is fetched from, before those of the settings
 * url          = "https://maven.example.com/releases"
 * user         = "${env:EXAMPLE_USER}"   # and password, or token; from the environment, or from the
 * password     = "${env:EXAMPLE_TOKEN}"  # configuration's entry of the same url
 * ```
 *
 * A module made of a library's sources taken as they are (kotlinx-io, kotlinx.serialization...) says
 * where they come from: each line of `upstream` is a directory and what fills it, the source jar of a
 * Maven artifact (`group:artifact`, at `version` unless it names its own) or a git repository
 * (`git:url@revision`); `${version}` is the module's version, there and in `fetch`. ktc2 fetches them once, into the cache
 * (`<cache>/modules/<name>@<version>`), and the module's `files.txt` lists the files of them it
 * compiles, by their paths there: a path, or a pattern, where `**` stands for any number of directories,
 * `*` for any name or part of one in one directory and `?` for one character (a directory and then `**`
 * takes all of it), which takes the `.kt` and `.def` files it matches; a line `!` and a path or a pattern
 * leaves out what the lines before it took, in order, as in a `.gitignore`. Any of them may be for some
 * systems only (`windows: ` before it). files.txt of kotlinx-io shows them. A program may ask for another version of such a module, `name@0.10.0`
 * in its `dependencies`: the files of `files.txt` and the module's own were written for its `version`,
 * so ktc2 says so when it takes another.
 *
 * `weak` and `acyclic` are for sources the module takes as they are (a library written for the
 * JVM or Kotlin/Native, where a collector frees cycles): the properties they name are as if
 * annotated `@Weak` (they hold their object weakly, and form no cycle that is never freed) or
 * `@Acyclic` (the cycle lint does not follow them: they cannot close a cycle).
 * The reference of an `inner` object to its outer one is named as the JVM names it:
 * `Outer.Inner.this$0`.
 *
 * A git dependency is `url[#directory][@branch or tag]`. It, and what `fetch` names, is cloned
 * once into the cache (`~/.ktc2/cache`, or the directory `KTC2_CACHE` names).
 *
 * A module shipped with ktc2 lists its files with `files = [...]`, since resources cannot be
 * enumerated; it is copied to `<out>/modules/<name>` when a program depends on it.
 *
 * A file of `files` or of `files.txt` may be for some systems only, as a library's source sets
 * are: `windows: path`, `linux, macos: path`. The program is compiled with those of the system
 * ktc2 runs on ([kHostSystem]), as its bindings are made from that system's headers.
 */
class ModuleFile(val directory: File, inText: String) {
	/** The files its lists name for other systems than this one: not part of the program here (paths relative to [directory]). */
	val elsewhere: Set<String> by lazy {
		val vListed = File(directory, "files.txt").takeIf { it.isFile }?.readLines() ?: emptyList()
		(files + vListed).map { ListedFile.of(it) }.filter { it != null && !it.isHere }.map { it!!.path }.toSet()
	}

	private val mValues = HashMap<String, List<String>>()

	val name: String get() = mValues["name"]?.firstOrNull() ?: directory.name
	val executable: String? get() = mValues["executable"]?.firstOrNull()
	val main: String? get() = mValues["main"]?.firstOrNull()
	val sources: List<String> get() = mValues["sources"] ?: listOf(".")
	val dependencies: List<String> get() = mValues["dependencies"] ?: emptyList()
	val fetch: List<String> get() = mValues["fetch"] ?: emptyList()
	val compilerOpts: List<String> get() = mValues["compilerOpts"] ?: emptyList()
	val linkerOpts: List<String> get() = mValues["linkerOpts"] ?: emptyList()
	val cmake: String? get() = mValues["cmake"]?.firstOrNull()
	val files: List<String> get() = mValues["files"] ?: emptyList()
	val weak: List<String> get() = mValues["weak"] ?: emptyList()
	val acyclic: List<String> get() = mValues["acyclic"] ?: emptyList()
	val optIn: List<String> get() = mValues["optIn"] ?: emptyList()
	val version: String? get() = mValues["version"]?.firstOrNull()
	val upstream: List<String> get() = mValues["upstream"] ?: emptyList()
	val jvm: List<String> get() = mValues["jvm"] ?: emptyList()

	/**
	 * Changes made to files of its upstream when they are fetched: `path = file`, the file of its own holding them, each
	 * `after: <a line of the upstream file>` followed by the lines to put after that line (it must be there once).
	 */
	val edits: List<String> get() = mValues["edits"] ?: emptyList()

	/** The version of its upstream that is taken: the one a program asked for, else [version]. */
	var chosenVersion: String? = null

	/** True for a module shipped with ktc2 ([bundledModule]). */
	var isBundled = false

	/** The lines of its `files.txt`: the files of its upstream it compiles (of its own directory when it has none). */
	val listed: List<ListedFile> by lazy {
		File(directory, "files.txt").takeIf { it.isFile }?.readLines()?.mapNotNull { ListedFile.of(it) } ?: emptyList()
	}

	/**
	 * The Maven repositories its `upstream` is fetched from before those of ktc2's settings: `[[maven]]` tables with a
	 * `url` and, when it needs them, `user` and `password` or `token` (as `${env:NAME}`: a module file is shared, a
	 * secret is not); what it says nothing of comes from the entry of ktc2's configuration with the same address.
	 */
	val repositories: List<Settings.Repository>

	init {
		val vTable = Toml.parse(inText, File(directory, kModuleFileName).path)
		for ((vKey, vValue) in vTable) {
			if (vKey == "maven") continue
			mValues[vKey] = when (vValue) {
				is List<*> -> vValue.map { it.toString() }
				else -> listOf(vValue.toString())
			}
		}
		val vMaven = vTable["maven"]
		repositories = (if (vMaven is List<*>) vMaven else listOfNotNull(vMaven)).mapNotNull { vEntry ->
			Settings.Repository.of(vEntry)?.also { vRepository ->
				val vRaw = vEntry as? Map<*, *>
				if (vRaw != null && listOf("password", "token").any { (vRaw[it] as? String)?.let { vText -> vText.isNotEmpty() && "\${env:" !in vText } == true })
					System.err.println("ktc2: warning: module '${vTable["name"] ?: directory.name}': the secret of ${vRepository.url} is written in its module file; `\${env:NAME}` takes it from the environment")
			}?.let(Settings::credentialsFor)
		}
	}
}

// ==================
// MARK: Git
// ==================

/** A place in a git repository: `url[#directory][@branch or tag]`. */
class GitSource(inText: String) {
	val url: String
	val directory: String
	val revision: String?

	init {
		var vRest = inText
		// A revision comes last, after the last `/`: the `@` of `git@host:…` is not one.
		val vAt = vRest.lastIndexOf('@')
		revision = if (vAt > 0 && vAt > vRest.lastIndexOf('/') && vAt > vRest.lastIndexOf(':')) vRest.substring(vAt + 1).also { vRest = vRest.substring(0, vAt) } else null
		val vHash = vRest.indexOf('#')
		directory = if (vHash >= 0) vRest.substring(vHash + 1).also { vRest = vRest.substring(0, vHash) } else ""
		url = vRest
	}

	/**
	 * The repository at this revision, cloned into the cache the first time it is asked for.
	 *
	 * @return its directory, or null after reporting why it could not be cloned
	 */
	fun checkout(): File? {
		val vCache = cacheDirectory()
		val vName = url.removeSuffix(".git").replace(Regex("^[a-z+]+://"), "").replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_')
		val vTarget = File(vCache, vName + "@" + (revision ?: "default"))
		// A clone that was interrupted has no marker: it is made again.
		val vMarker = File(vTarget, ".ktc2-complete")
		if (vMarker.isFile) return vTarget
		vTarget.parentFile.mkdirs()
		// Several ktc2 at once: one clones, the others wait for it.
		return withFileLock(File(vTarget.parentFile, vTarget.name + ".lock")) { if (vMarker.isFile) vTarget else clone(vTarget, vMarker) }
	}

	/** Clones the repository into [inTarget], marking it complete with [inMarker]. */
	private fun clone(inTarget: File, inMarker: File): File? {
		val vTarget = inTarget
		val vMarker = inMarker
		vTarget.deleteRecursively()
		Progress.interrupt { System.err.println("ktc2: fetching $url${revision?.let { " at $it" } ?: ""}") }
		val vCommand = listOf("git", "clone", "--depth", "1", "-c", "advice.detachedHead=false") +
			(revision?.let { listOf("--branch", it) } ?: emptyList()) + listOf(url, vTarget.absolutePath)
		val vExit = try {
			ProcessBuilder(vCommand).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor()
		} catch (vError: java.io.IOException) {
			System.err.println("ktc2: error: git could not be run: ${vError.message}")
			return null
		}
		if (vExit != 0) {
			System.err.println("ktc2: error: could not clone $url${revision?.let { " at $it" } ?: ""} (git exited with $vExit)")
			vTarget.deleteRecursively()
			return null
		}
		vMarker.writeText("")
		return vTarget
	}

	companion object {
		/** True when [inText] names a git repository rather than a directory or a module of ktc2. */
		fun isGit(inText: String): Boolean = "://" in inText || inText.startsWith("git@") || inText.substringBefore('#').substringBefore('@').endsWith(".git")
	}
}

// ==================
// MARK: Upstream
// ==================

/** Where ktc2 keeps what it fetches: the setting `cache` (`KTC2_CACHE`), `~/.ktc2/cache` by default. */
fun cacheDirectory(): File = Settings.cacheDirectory()

/**
 * The sources of the library [inModule] takes as they are (its `upstream`), at its chosen version, fetched into the
 * cache the first time: the directory its `files.txt` paths are from. Null after reporting why it could not be had.
 */
fun upstreamOf(inModule: ModuleFile): File? {
	val vVersion = versionOf(inModule)
	if (vVersion == null) {
		System.err.println("ktc2: error: module '${inModule.name}' has an upstream and no version")
		return null
	}
	val vTarget = File(cacheDirectory(), "modules/${inModule.name}@$vVersion")
	// One fetched only in part has no marker, one made with other edits another: it is fetched again.
	val vEdits = inModule.edits.map { vLine -> vLine to File(inModule.directory, vLine.substringAfter('=').trim()).takeIf { it.isFile }?.readText() }
	val vStamp = vEdits.joinToString("\n") { (vLine, vText) -> vLine + ":" + (vText?.hashCode() ?: 0) }
	fun isReady(): Boolean = File(vTarget, ".ktc2-complete").let { it.isFile && it.readText() == vStamp }
	if (isReady()) return vTarget
	// Several ktc2 at once (the suite's processes, two builds): one fetches, the others wait for it and take what it made.
	vTarget.parentFile.mkdirs()
	return withFileLock(File(vTarget.parentFile, vTarget.name + ".lock")) {
		if (isReady()) return@withFileLock vTarget
		// Made apart and put in place whole: nothing else ever sees it half made.
		val vMaking = File(vTarget.parentFile, vTarget.name + ".making")
		vMaking.deleteRecursively()
		vMaking.mkdirs()
		for (vLine in inModule.upstream) {
			val vEquals = vLine.indexOf('=')
			if (vEquals <= 0) {
				System.err.println("ktc2: error: module '${inModule.name}': upstream wants `directory = source`, not '$vLine'")
				return@withFileLock null
			}
			val vDirectory = File(vMaking, vLine.substring(0, vEquals).trim()).canonicalFile
			val vSource = vLine.substring(vEquals + 1).trim().replace("\${version}", vVersion)
			if (vSource.startsWith("git:")) {
				val vGit = GitSource(vSource.removePrefix("git:"))
				val vCheckout = vGit.checkout() ?: return@withFileLock null
				File(vCheckout, vGit.directory).copyRecursively(vDirectory, overwrite = true)
			} else if (!fetchSources(vSource, vVersion, vDirectory, inModule.repositories)) {
				return@withFileLock null
			}
		}
		for ((vLine, vText) in vEdits) {
			if (vText == null || !applyEdits(File(vMaking, vLine.substringBefore('=').trim()), vText)) {
				System.err.println("ktc2: error: module '${inModule.name}': the edit '$vLine' does not apply")
				return@withFileLock null
			}
		}
		File(vMaking, ".ktc2-complete").writeText(vStamp)
		vTarget.deleteRecursively()
		if (!vMaking.renameTo(vTarget)) {
			System.err.println("ktc2: error: could not put the sources of module '${inModule.name}' into ${vTarget.path}")
			return@withFileLock null
		}
		vTarget
	}
}

/** Runs [inBlock] holding the lock of [inFile], which another process takes too: one at a time. */
internal fun <T> withFileLock(inFile: File, inBlock: () -> T): T = synchronized(kCacheLock) {
	java.io.RandomAccessFile(inFile, "rw").use { vFile -> vFile.channel.lock().use { inBlock() } }
}

/** One thread of this process at a time in the cache (a lock of a file is the process's). */
private val kCacheLock = Any()

/** The version of [inModule]'s upstream that is taken: `${kotlin}` stands for the Kotlin of ktc2's compiler. */
fun versionOf(inModule: ModuleFile): String? =
	(inModule.chosenVersion ?: inModule.version)?.replace("\${kotlin}", org.jetbrains.kotlin.config.KotlinCompilerVersion.VERSION)

/** Puts the lines of each `after:` block of [inEdits] after its line in [inFile]: false when one is not there once. */
private fun applyEdits(inFile: File, inEdits: String): Boolean {
	if (!inFile.isFile) return false
	val vLines = inFile.readLines().toMutableList()
	var vAfter: String? = null
	val vInsert = ArrayList<String>()
	fun flush(): Boolean {
		val vAnchor = vAfter ?: return true
		val vAt = vLines.indices.filter { vLines[it] == vAnchor }.singleOrNull() ?: return false
		vLines.addAll(vAt + 1, vInsert)
		vInsert.clear()
		return true
	}
	for (vLine in inEdits.lines().dropLastWhile { it.isEmpty() }) {
		if (vLine.startsWith("after: ")) {
			if (!flush()) return false
			vAfter = vLine.removePrefix("after: ")
		} else {
			vInsert += vLine
		}
	}
	if (!flush()) return false
	inFile.writeText(vLines.joinToString("\n", postfix = "\n"))
	return true
}

/**
 * Fetches the source jar of the Maven artifact [inCoordinate] (`group:artifact`, at [inVersion], or
 * `group:artifact:version`) and unpacks it into [inInto]: from the first of [inModuleRepositories], then of the
 * repositories of ktc2's settings, that has it.
 */
private fun fetchSources(inCoordinate: String, inVersion: String, inInto: File, inModuleRepositories: List<Settings.Repository>): Boolean {
	val vParts = inCoordinate.split(':')
	if (vParts.size !in 2..3) {
		System.err.println("ktc2: error: '$inCoordinate' is not a Maven artifact (group:artifact or group:artifact:version)")
		return false
	}
	val (vGroup, vArtifact) = vParts
	val vVersion = vParts.getOrNull(2) ?: inVersion
	val vPath = "${vGroup.replace('.', '/')}/$vArtifact/$vVersion/$vArtifact-$vVersion-sources.jar"
	val vRepositories = (inModuleRepositories + Settings.repositories()).distinctBy { it.key }
	Progress.interrupt { System.err.println("ktc2: fetching $vGroup:$vArtifact:$vVersion (sources)") }
	val vFailures = ArrayList<String>()
	for (vRepository in vRepositories) {
		val vUrl = "${vRepository.key}/$vPath"
		try {
			val vStream = openFollowingRedirects(vUrl, vRepository)
			if (vStream == null) {
				vFailures += "$vUrl: not there"
				continue
			}
			vStream.use { unzipInto(it, inInto) }
			return true
		} catch (vError: java.io.IOException) {
			vFailures += "$vUrl: ${vError.message}"
		}
	}
	System.err.println("ktc2: error: could not fetch the sources of $vGroup:$vArtifact:$vVersion")
	vFailures.forEach { System.err.println("  $it") }
	return false
}

/**
 * The content at [inUrl], asked for with what [inRepository] is entered with; null when it is not there (404).
 * A redirection to another host is followed without the credentials: they are only for the repository's own.
 */
private fun openFollowingRedirects(inUrl: String, inRepository: Settings.Repository): java.io.InputStream? {
	val vHost = java.net.URI(inRepository.url).host
	var vUrl = inUrl
	repeat(10) {
		val vConnection = java.net.URI(vUrl).toURL().openConnection() as java.net.HttpURLConnection
		vConnection.instanceFollowRedirects = false
		vConnection.connectTimeout = 30_000
		vConnection.readTimeout = 60_000
		vConnection.setRequestProperty("User-Agent", "ktc2")
		if (java.net.URI(vUrl).host == vHost) {
			when {
				!inRepository.token.isNullOrEmpty() -> vConnection.setRequestProperty("Authorization", "Bearer ${inRepository.token}")
				!inRepository.user.isNullOrEmpty() -> vConnection.setRequestProperty("Authorization", "Basic " +
					java.util.Base64.getEncoder().encodeToString("${inRepository.user}:${inRepository.password ?: ""}".toByteArray()))
			}
		}
		when (val vStatus = vConnection.responseCode) {
			in 200..299 -> return vConnection.inputStream
			301, 302, 303, 307, 308 -> {
				val vLocation = vConnection.getHeaderField("Location") ?: throw java.io.IOException("a redirection without a place")
				vUrl = java.net.URI(vUrl).resolve(vLocation).toString()
				vConnection.disconnect()
			}
			404 -> return null
			401, 403 -> throw java.io.IOException("refused ($vStatus)${if (inRepository.hasCredentials) "" else ": it may need a user and a password, or a token"}")
			else -> throw java.io.IOException("HTTP $vStatus")
		}
	}
	throw java.io.IOException("too many redirections")
}

/** Unpacks the zip of [inStream] into [inInto]; nothing of it is written outside that directory. */
private fun unzipInto(inStream: java.io.InputStream, inInto: File) {
	inInto.mkdirs()
	java.util.zip.ZipInputStream(inStream).use { vZip ->
		val vRoot = inInto.canonicalFile
		while (true) {
			val vEntry = vZip.nextEntry ?: break
			val vFile = File(vRoot, vEntry.name).canonicalFile
			if (!vFile.path.startsWith(vRoot.path + File.separator)) continue
			if (vEntry.isDirectory) vFile.mkdirs() else {
				vFile.parentFile.mkdirs()
				vFile.outputStream().use { vZip.copyTo(it) }
			}
		}
	}
}

/**
 * The files a module compiles: those of its `sources` (its own directory by default), and, when it takes a
 * library as it is, the files of `files.txt` from that library's sources ([upstreamOf]). Null after reporting
 * what is missing.
 */
fun filesOf(inModule: ModuleFile): List<File>? {
	val vFiles = ArrayList<File>()
	for (vEntry in inModule.sources) {
		val vFile = File(inModule.directory, vEntry)
		when {
			vFile.isFile -> vFiles += vFile
			vFile.isDirectory -> vFile.walkTopDown()
				// Build output and the examples of a module are not part of it, nor is a module inside it.
				.onEnter { it == vFile || (it.name != "build" && it.name != "out" && it.name != "example" && it.name != "examples" && !File(it, kModuleFileName).isFile) }
				.filter { it.isFile && (it.extension == "kt" || it.extension == "def") }
				.filter { it.relativeTo(inModule.directory).invariantSeparatorsPath !in inModule.elsewhere }
				.sortedBy { it.path }
				.forEach { vFiles += it }
			else -> {
				System.err.println("ktc2: error: module '${inModule.name}': no such source '$vEntry'")
				return null
			}
		}
	}
	if (inModule.upstream.isNotEmpty()) {
		val vUpstream = upstreamOf(inModule) ?: return null
		// A bare name is a file of the module itself, which its sources already take.
		vFiles += listedFiles(inModule, vUpstream, inModule.listed.filter { it.isHere && '/' in it.path.removePrefix("!") }) ?: return null
	}
	return vFiles
}

/**
 * The files of [inUpstream] (the sources [inModule] takes as they are) that [inListed] names, each a path, a pattern or
 * a `!` that leaves out what came before; null after reporting a path or a pattern that is not there.
 */
fun listedFiles(inModule: ModuleFile, inUpstream: File, inListed: List<ListedFile>): List<File>? {
	val vUpstream = inUpstream
	val vVersion = versionOf(inModule)
	// Every file of the upstream that a pattern may take, by its path there: its Kotlin sources and C definitions.
	val vAll by lazy {
		vUpstream.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "def") }
			.map { it.relativeTo(vUpstream).invariantSeparatorsPath }.sorted().toList()
	}
	val vTaken = LinkedHashSet<String>()
	for (vListed in inListed) {
		val vExcluded = vListed.path.startsWith("!")
		val vPath = vListed.path.removePrefix("!").trim()
		val vMatches = if (isPattern(vPath)) {
			val vRegex = patternRegex(vPath)
			vAll.filter { vRegex.matches(it) }
		} else {
			listOf(vPath).filter { File(vUpstream, it).isFile }
		}
		if (vMatches.isEmpty() && !vExcluded) {
			val vWhat = if (isPattern(vPath)) "nothing matches '$vPath'" else "no '$vPath'"
			System.err.println("ktc2: error: module '${inModule.name}': in its upstream at $vVersion, $vWhat (files.txt)")
			return null
		}
		if (vExcluded) vTaken -= vMatches.toSet() else vTaken += vMatches
	}
	return vTaken.map { File(vUpstream, it) }
}

// ==================
// MARK: Project
// ==================

/** What a module and the modules it depends on add up to. */
class Project(val name: String, val executable: String, val main: String?) {
	val sources = LinkedHashSet<File>()
	val compilerOpts = ArrayList<String>()

	/** The properties the modules name in `weak` and `acyclic`: `@Weak` and `@Acyclic` as if annotated. */
	val weak = ArrayList<String>()
	val acyclic = ArrayList<String>()

	/** The opt-ins the modules name in `optIn`, as their builds give them to the compiler (`-opt-in`). */
	val optIn = ArrayList<String>()

	/**
	 * Options for the linker: [linkerOpts] when the program is built with CMake, where a module
	 * that has a CMake file does its own linking, [directLinkerOpts] when the C compiler is called.
	 */
	val linkerOpts = ArrayList<String>()
	val directLinkerOpts = ArrayList<String>()

	/** True when a module brings a CMake file: the program is then built with CMake. */
	val needsCMake: Boolean get() = cmakeFiles.isNotEmpty()

	/** The CMake files of the modules, in the order of the dependencies. */
	val cmakeFiles = ArrayList<File>()

	/** The C sources fetched for the modules: the name each is known by, and where it is. */
	val fetched = LinkedHashMap<String, File>()

	/** The modules the program depends on, as a command shows them: each name, with the version of its library. */
	val modules = ArrayList<String>()

	/** The files each module shipped with ktc2 brought, by its name: on the JVM such a module is its library's jars. */
	val bundledFiles = LinkedHashMap<String, List<File>>()
}

/**
 * Collects the sources and options of the module in [inDirectory] and of every module it
 * depends on, each once.
 *
 * @param inOutDir where the modules shipped with ktc2 are copied
 * @return the project, or null after reporting what is wrong
 */
fun loadProject(inDirectory: File, inOutDir: File): Project? {
	val vRoot = ModuleFile(inDirectory, File(inDirectory, kModuleFileName).readText())
	val vProject = Project(vRoot.name, vRoot.executable ?: vRoot.name, vRoot.main)
	val vSeen = HashSet<String>()

	fun fail(inMessage: String): Boolean {
		System.err.println("ktc2: error: $inMessage")
		return false
	}

	fun add(inModule: ModuleFile): Boolean {
		if (!vSeen.add(inModule.directory.canonicalPath)) return true
		val vFiles = filesOf(inModule) ?: return false
		if (inModule !== vRoot) vProject.modules += inModule.name + (versionOf(inModule)?.let { " $it" } ?: "")
		vProject.sources += vFiles
		if (inModule.isBundled) vProject.bundledFiles[inModule.name] = vFiles
		for (vEntry in inModule.fetch) {
			val vEquals = vEntry.indexOf('=')
			if (vEquals <= 0) return fail("module '${inModule.name}': fetch wants NAME=url, not '$vEntry'")
			val vName = vEntry.substring(0, vEquals).trim()
			val vSource = GitSource(vEntry.substring(vEquals + 1).trim().replace("\${version}", versionOf(inModule) ?: ""))
			val vCheckout = vSource.checkout() ?: return false
			vProject.fetched[vName] = File(vCheckout, vSource.directory)
		}
		// `${NAME}` in an option is where the repository fetched under that name is.
		fun expanded(inOption: String): String =
			vProject.fetched.entries.fold(inOption) { vText, (vName, vPlace) -> vText.replace("\${$vName}", vPlace.absoluteFile.invariantSeparatorsPath) }
		vProject.compilerOpts += inModule.compilerOpts.map { expanded(it) }
		vProject.weak += inModule.weak
		vProject.acyclic += inModule.acyclic
		vProject.optIn += inModule.optIn
		val vCMake = inModule.cmake?.let { File(inModule.directory, it) }
		if (vCMake != null) {
			if (!vCMake.isFile) return fail("module '${inModule.name}': no such CMake file '${inModule.cmake}'")
			vProject.cmakeFiles += vCMake
		} else {
			vProject.linkerOpts += inModule.linkerOpts.map { expanded(it) }
		}
		vProject.directLinkerOpts += inModule.linkerOpts.map { expanded(it) }
		for (vDependency in inModule.dependencies) {
			val vModule = when {
				GitSource.isGit(vDependency) -> {
					val vSource = GitSource(vDependency)
					val vPlace = File(vSource.checkout() ?: return false, vSource.directory)
					if (!File(vPlace, kModuleFileName).isFile) return fail("module '${inModule.name}': '$vDependency' has no $kModuleFileName")
					ModuleFile(vPlace, File(vPlace, kModuleFileName).readText())
				}
				File(File(inModule.directory, vDependency), kModuleFileName).isFile -> {
					val vLocal = File(inModule.directory, vDependency)
					ModuleFile(vLocal, File(vLocal, kModuleFileName).readText())
				}
				else -> {
					// `name@version`: a module of ktc2 with another version of the library it takes as it is.
					val vName = vDependency.substringBefore('@')
					val vVersion = vDependency.substringAfter('@', "").takeIf { it.isNotEmpty() }
					bundledModule(vName, inOutDir)?.also { vBundled ->
						if (vVersion != null && vVersion != vBundled.version) {
							System.err.println("ktc2: warning: module '$vName' at $vVersion: its files were written for ${vBundled.version ?: "no version"}")
							vBundled.chosenVersion = vVersion
						}
					} ?: return fail("module '${inModule.name}': no module '$vDependency' (neither a directory with a $kModuleFileName, nor a git repository, nor a module of ktc2)")
				}
			}
			if (!add(vModule)) return false
		}
		return true
	}

	return if (add(vRoot)) vProject else null
}

/**
 * The module [inName] shipped with ktc2, copied to `<inOutDir>/modules/<inName>`: its module file, its list and its own
 * files (the library it takes as it is comes from the cache, [upstreamOf]); null when there is none.
 */
fun bundledModule(inName: String, inOutDir: File): ModuleFile? {
	fun resource(inFile: String): ByteArray? = Project::class.java.getResourceAsStream("/ktc2/modules/$inName/$inFile")?.use { it.readBytes() }
	val vText = resource(kModuleFileName)?.decodeToString() ?: return null
	val vDirectory = File(inOutDir, "modules/$inName")
	vDirectory.mkdirs()
	val vModule = ModuleFile(vDirectory, vText)
	val vFiles = vModule.files.mapNotNull { ListedFile.of(it) }
	val vEdits = vModule.edits.map { it.substringAfter('=').trim() }
	// A bare name in its list is a file of the module itself (the stdlib's platform layer), not of its upstream.
	val vOwnListed = (resource("files.txt")?.decodeToString()?.lines() ?: emptyList()).mapNotNull { ListedFile.of(it) }
		.filter { it.isHere && '/' !in it.path }.map { it.path }
	for (vFile in vFiles.filter { it.isHere }.map { it.path } + kModuleFileName + "files.txt" + listOfNotNull(vModule.cmake) + vEdits + vOwnListed + "hidden.txt") {
		val vBytes = resource(vFile) ?: continue
		val vTarget = File(vDirectory, vFile)
		vTarget.parentFile.mkdirs()
		if (!vTarget.isFile || !vTarget.readBytes().contentEquals(vBytes)) vTarget.writeBytes(vBytes)
	}
	// Its directory holds what it is made of now and nothing else (its whole directory is its sources): not what another
	// system's compilation or an older ktc2 left there. What is unchanged keeps its time, for a build that follows them.
	val vKept = (vFiles.filter { it.isHere }.map { it.path } + kModuleFileName + "files.txt" + listOfNotNull(vModule.cmake) + vEdits + vOwnListed + "hidden.txt").toSet()
	vDirectory.walkBottomUp().filter { it != vDirectory }.forEach { vFile ->
		val vPath = vFile.relativeTo(vDirectory).invariantSeparatorsPath
		if (vFile.isFile && vPath !in vKept) vFile.delete()
		if (vFile.isDirectory && vFile.list().isNullOrEmpty()) vFile.delete()
	}
	return ModuleFile(vDirectory, vText).also { it.isBundled = true }
}

/** True when a path of a list is a pattern: `*` (in one directory), `**` (any depth) or `?` (one character). */
private fun isPattern(inPath: String): Boolean = inPath.any { it == '*' || it == '?' }

/** The paths [inPattern] takes: `**` any number of directories, `*` any name or part of one, `?` one character. */
private fun patternRegex(inPattern: String): Regex {
	val vText = StringBuilder()
	var vIndex = 0
	while (vIndex < inPattern.length) {
		val vChar = inPattern[vIndex]
		when {
			inPattern.startsWith("**/", vIndex) -> {
				vText.append("(?:.*/)?")
				vIndex += 3
				continue
			}
			inPattern.startsWith("**", vIndex) -> {
				vText.append(".*")
				vIndex += 2
				continue
			}
			vChar == '*' -> vText.append("[^/]*")
			vChar == '?' -> vText.append("[^/]")
			else -> vText.append(Regex.escape(vChar.toString()))
		}
		vIndex++
	}
	return Regex(vText.toString())
}

/** The system ktc2 runs on, as a module's lists name it: `windows`, `macos` or `linux`. */
val kHostSystem: String = System.getProperty("os.name").lowercase().let {
	when {
		"win" in it -> "windows"
		"mac" in it || "darwin" in it -> "macos"
		else -> "linux"
	}
}

/** A line of a module's list of files: `path`, or `windows: path`, `linux, macos: path` for a file of those systems only. */
class ListedFile(val path: String, val systems: Set<String>?) {
	val isHere: Boolean get() = systems == null || kHostSystem in systems

	companion object {
		private val kForSystems = Regex("^([a-z]+(?:\\s*,\\s*[a-z]+)*)\\s*:\\s*(.+)$")

		/** The file of [inLine]; null for an empty line or a comment. */
		fun of(inLine: String): ListedFile? {
			val vLine = inLine.trim()
			if (vLine.isEmpty() || vLine.startsWith("#")) return null
			val vMatch = kForSystems.find(vLine) ?: return ListedFile(vLine, null)
			return ListedFile(vMatch.groupValues[2].trim(), vMatch.groupValues[1].split(',').map { it.trim() }.toSet())
		}
	}
}

// ==================
// MARK: Building
// ==================

/** The lines of the build manifest in [inOutDir] that start with `<inKey>=`, without that. */
private fun manifestValues(inOutDir: File, inKey: String): List<String> =
	File(inOutDir, com.bitsycore.ktc2.frontend.kManifestName).readLines().filter { it.startsWith("$inKey=") }.map { it.substringAfter('=') }

private val kIsWindows = "win" in System.getProperty("os.name").lowercase()

/**
 * The architectures a program for macOS is built for (`build.macosArch`): `-arch` for each, one program holding them
 * all (a universal one for `arm64` and `x86_64`); empty elsewhere, or when the setting names none (the machine's own).
 */
fun macosArchitectures(): List<String> {
	if (kHostSystem != "macos") return emptyList()
	val vArchitectures = Settings.list("build.macosArch").distinct()
	vArchitectures.firstOrNull { it !in setOf("arm64", "x86_64", "arm64e") }?.let {
		System.err.println("ktc2: warning: build.macosArch: '$it' is not arm64 or x86_64")
	}
	return vArchitectures
}

/**
 * The C compiler to call: the one `KTC2_CC` names, else the first of clang, gcc and cc there is.
 * clang first (the owner, 2026-11-17): the same compiler on every system, and its ThinLTO links in
 * parallel by itself (the showcase on Windows: 13 s, where gcc's LTO took 215 s without `make`).
 */
fun cCompiler(): String {
	Settings.text("build.cc").takeIf { it.isNotEmpty() }?.let { return it }
	for (vName in listOf("clang", "gcc", "cc")) {
		if (isOnPath(vName)) return vName
	}
	return "cc"
}

/** Whether a program called [inName] is in one of the directories of `PATH`. */
private fun isOnPath(inName: String): Boolean =
	(System.getenv("PATH") ?: "").split(File.pathSeparator).any { File(it, inName).isFile || File(it, "$inName.exe").isFile }

/**
 * Writes `<inOutDir>/CMakeLists.txt` for the C that was written under `<inOutDir>/c`: the
 * program, what the bindings ask of the compiler and the linker, and the CMake file of each
 * module, which sees the program as `${KTC2_TARGET}` and each fetched repository as
 * `${KTC2_FETCH_<NAME>}`.
 */
fun writeCMake(inOutDir: File, inExecutable: String, inProject: Project?) {
	fun quoted(inText: String): String = "\"" + inText.replace("\\", "/").replace("\"", "\\\"") + "\""
	val vText = buildString {
		append("# Generated by ktc2. Do not edit: it is written again by each compilation.\n")
		append("cmake_minimum_required(VERSION 3.20)\n")
		append("project(${inExecutable.replace(Regex("[^A-Za-z0-9_]"), "_")} C)\n\n")
		append("set(CMAKE_C_STANDARD 11)\nset(CMAKE_C_STANDARD_REQUIRED ON)\n")
		append("if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)\n\tset(CMAKE_BUILD_TYPE Release)\nendif()\n\n")
		append("add_executable($inExecutable\n")
		manifestValues(inOutDir, "c").forEach { append("\tc/${it.replace('\\', '/')}\n") }
		append(")\n")
		append("target_include_directories($inExecutable PRIVATE c)\n")
		append("# With KTC2_DEBUG the program reports the objects it leaves behind when it ends.\n")
		append("if(KTC2_DEBUG)\n\ttarget_compile_definitions($inExecutable PRIVATE KTC2_LEAK_CHECK)\nendif()\n")
		append("# Kotlin rounds after every floating-point operation: none is fused with the next.\n")
		append("if(NOT MSVC)\n\ttarget_compile_options($inExecutable PRIVATE -ffp-contract=off)\nendif()\n")
		append("# A release build is optimized as one program where the compiler can: -DKTC2_LTO=OFF to do without.\n")
		append("option(KTC2_LTO \"Optimize the release build as a whole program\" ON)\n")
		append("if(KTC2_LTO AND NOT KTC2_DEBUG)\n\tinclude(CheckIPOSupported)\n\tcheck_ipo_supported(RESULT KTC2_HAS_LTO OUTPUT KTC2_LTO_OUTPUT LANGUAGES C)\n")
		append("\tif(KTC2_HAS_LTO)\n\t\tset_property(TARGET $inExecutable PROPERTY INTERPROCEDURAL_OPTIMIZATION_RELEASE TRUE)\n\tendif()\nendif()\n")
		val vCompilerOpts = manifestValues(inOutDir, "cflag")
		if (vCompilerOpts.isNotEmpty()) append("target_compile_options($inExecutable PRIVATE ${vCompilerOpts.joinToString(" ") { quoted(it) }})\n")
		if (manifestValues(inOutDir, "threads").isNotEmpty()) {
			append("find_package(Threads REQUIRED)\ntarget_link_libraries($inExecutable PRIVATE Threads::Threads)\n")
		}
		append("if(NOT WIN32)\n\ttarget_link_libraries($inExecutable PRIVATE m)\nendif()\n")
		// What the .def files and the modules ask of the linker; a module with a CMake file links there instead.
		val vLinkerOpts = manifestValues(inOutDir, "ldflag")
		if (vLinkerOpts.isNotEmpty()) append("target_link_options($inExecutable PRIVATE ${vLinkerOpts.joinToString(" ") { quoted(it) }})\n")
		if (inProject != null && (inProject.cmakeFiles.isNotEmpty() || inProject.fetched.isNotEmpty())) {
			append("\n# The modules.\nset(KTC2_TARGET $inExecutable)\n")
			for ((vName, vPlace) in inProject.fetched) append("set(KTC2_FETCH_${vName.uppercase()} ${quoted(vPlace.absolutePath)})\n")
			for (vFile in inProject.cmakeFiles) append("include(${quoted(vFile.absolutePath)})\n")
		}
	}
	val vFile = File(inOutDir, "CMakeLists.txt")
	if (!vFile.isFile || vFile.readText() != vText) vFile.writeText(vText)
}

/**
 * Builds the program from the C in `<inOutDir>/c` with the C compiler: each file into an object of its own (in
 * `<inOutDir>/obj`, as many at once as the machine has processors), then the link. An object is made again only when
 * its file, a header it includes or the options changed (what the compiler said it includes: `-MMD`), so that a
 * program whose translation changed a few files is rebuilt in the time of those. Null after reporting what failed.
 */
fun buildProgram(inOutDir: File, inName: String, inIsDebug: Boolean): File? {
	val vProgram = File(inOutDir, if (kIsWindows) "$inName.exe" else inName)
	val vCompiler = cCompiler()
	val vIsClang = isClang(vCompiler)
	// A release build is optimized as one program, so that small functions are inlined across the
	// files of their classes. `KTC2_LTO=0` turns that off, `KTC2_LTO=1` forces it. With clang on
	// Windows it needs LLVM's own linker, so it is used by default only when that one is there.
	val vNeedsLld = kIsWindows && vIsClang
	val vWholeProgram = !inIsDebug && (Settings.choice("build.lto") ?: (!vNeedsLld || isOnPath("lld-link")))
	// Floating-point operations are never fused (see ktc2_core.h): Kotlin rounds after each one. With LTO the objects
	// hold the compiler's own code, which the link optimizes as one program on several threads (ThinLTO with clang).
	val vLto = when {
		!vWholeProgram -> emptyList()
		vIsClang -> listOf("-flto=thin")
		else -> listOf("-flto")
	}
	val vMode = listOf("-ffp-contract=off") + when {
		inIsDebug -> listOf("-O0", "-g", "-DKTC2_LEAK_CHECK")
		else -> listOf("-O2") + vLto
	}
	// On Windows the runtime uses the system's own threads and the math functions are in the C
	// library every program links: naming a thread or a math library there only breaks the
	// compilers that have none (clang with the MSVC libraries).
	val vThreads = if (!kIsWindows && manifestValues(inOutDir, "threads").isNotEmpty()) listOf("-pthread") else emptyList()
	val vMath = if (kIsWindows) emptyList() else listOf("-lm")
	val vArch = macosArchitectures().flatMap { listOf("-arch", it) }
	val vCompile = listOf(vCompiler, "-std=c11") + vArch + vMode + vThreads + listOf("-I", ".") + manifestValues(inOutDir, "cflag")
	val vCDir = File(inOutDir, "c")
	val vObjDir = File(inOutDir, "obj")
	val vSources = manifestValues(inOutDir, "c")
	val vOptions = vCompile.joinToString(" ")
	val vDigests = java.util.concurrent.ConcurrentHashMap<String, String>()
	fun digestOf(inFile: File): String = vDigests.getOrPut(inFile.canonicalPath) {
		if (!inFile.isFile) "" else java.security.MessageDigest.getInstance("SHA-256").digest(inFile.readBytes()).joinToString("") { "%02x".format(it) }
	}
	fun objectOf(inSource: String): File = File(vObjDir, inSource.replace(Regex("[:\\\\]"), "_").removePrefix("/").removeSuffix(".c") + ".o")
	/** True when the object of [inSource] was made with these options from what its files hold now. */
	fun isUpToDate(inObject: File): Boolean {
		val vStamp = File(inObject.path + ".stamp")
		if (!inObject.isFile || !vStamp.isFile) return false
		val vLines = vStamp.readLines()
		if (vLines.firstOrNull() != vOptions) return false
		return vLines.drop(1).all { vLine -> digestOf(File(vLine.substringBeforeLast(' '))) == vLine.substringAfterLast(' ') }
	}

	Progress.begin("C → objects", "${vSources.size} files")
	val vDone = java.util.concurrent.atomic.AtomicInteger()
	val vCompiled = java.util.concurrent.atomic.AtomicInteger()
	val vFailures = java.util.concurrent.ConcurrentLinkedQueue<String>()
	val vPool = java.util.concurrent.Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors().coerceAtLeast(1))
	for (vSource in vSources) {
		vPool.execute {
			val vObject = objectOf(vSource)
			if (!isUpToDate(vObject)) {
				vObject.parentFile.mkdirs()
				val vDepends = File(vObject.path + ".d")
				val (vExit, vOutput) = runCaptured(vCompile + listOf("-MMD", "-MF", vDepends.absolutePath, "-c", vSource, "-o", vObject.absolutePath), vCDir)
				if (vExit != 0) {
					vObject.delete()
					vFailures += vOutput.ifBlank { "the C compiler failed on $vSource (exit $vExit)" }
				} else {
					// What the object is made of: its file and the headers it includes, each with what it holds now.
					val vFiles = dependenciesOf(vDepends, vCDir).ifEmpty { listOf(File(vCDir, vSource)) }
					File(vObject.path + ".stamp").writeText((listOf(vOptions) + vFiles.map { "${it.path} ${digestOf(it)}" }).joinToString("\n"))
					vCompiled.incrementAndGet()
				}
			}
			// The count and its line together: one thread at a time, so that a count is never shown after a larger one.
			synchronized(vDone) { Progress.update("[${vDone.incrementAndGet()}/${vSources.size}] ${vCompiled.get()} compiled") }
		}
	}
	vPool.shutdown()
	vPool.awaitTermination(1, java.util.concurrent.TimeUnit.DAYS)
	if (vFailures.isNotEmpty()) {
		Progress.fail()
		vFailures.take(3).forEach { System.err.print(it.trimEnd() + "\n") }
		System.err.println("ktc2: error: the C compiler failed on ${vFailures.size} file(s)")
		return null
	}
	val vUnchanged = vSources.size - vCompiled.get()
	Progress.end("${vSources.size} files" + if (vUnchanged > 0) ", ${vCompiled.get()} compiled, $vUnchanged unchanged" else "")

	Progress.begin("link", vProgram.name)
	val vLinkLto = when {
		vLto.isEmpty() -> emptyList()
		vIsClang -> vLto + if (vNeedsLld) listOf("-fuse-ld=lld") else emptyList()
		else -> listOf("-flto=auto")
	}
	val vLink = listOf(vCompiler) + vArch + vMode.filter { !it.startsWith("-flto") } + vLinkLto + vThreads + listOf("-o", vProgram.absolutePath) +
		vSources.map { objectOf(it).absolutePath } + vMath + manifestValues(inOutDir, "ldflag")
	// No object made again, the same command, the program there: the program is what the link would make.
	val vLinkStamp = File(vObjDir, "link.stamp")
	if (vCompiled.get() == 0 && vProgram.isFile && vLinkStamp.isFile && vLinkStamp.readText() == vLink.joinToString("\n")) {
		Progress.end("up to date")
		return vProgram
	}
	vLinkStamp.delete()
	val (vExit, vOutput) = try {
		runCaptured(vLink, vCDir)
	} catch (vError: java.io.IOException) {
		Progress.fail()
		System.err.println("ktc2: error: the C compiler could not be run: ${vError.message}")
		return null
	}
	if (vExit != 0) {
		Progress.fail()
		System.err.print(vOutput)
		System.err.println("ktc2: error: the link failed: ${vLink.take(6).joinToString(" ")} …")
		return null
	}
	if (vOutput.isNotBlank()) Progress.interrupt { System.err.print(vOutput) }
	vLinkStamp.writeText(vLink.joinToString("\n"))
	Progress.end(vProgram.name)
	return vProgram
}

/** True when [inCompiler] is clang (on macOS, `gcc` is): asked once of the compiler itself. */
private fun isClang(inCompiler: String): Boolean = kClang.getOrPut(inCompiler) {
	try {
		"clang" in runCaptured(listOf(inCompiler, "--version"), null).second
	} catch (vError: java.io.IOException) {
		false
	}
}

private val kClang = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

/** Runs [inCommand] in [inDirectory] and gives what it exits with and what it printed. */
private fun runCaptured(inCommand: List<String>, inDirectory: File?): Pair<Int, String> {
	val vBuilder = ProcessBuilder(startable(inCommand, inDirectory)).redirectErrorStream(true)
		.redirectInput(ProcessBuilder.Redirect.from(File(if (kIsWindows) "NUL" else "/dev/null")))
	inDirectory?.let { vBuilder.directory(it) }
	val vProcess = vBuilder.start()
	val vOutput = vProcess.inputStream.readBytes().decodeToString()
	return vProcess.waitFor() to vOutput
}

/** The files a dependency file of the C compiler ([inFile], `-MMD`) names, relative to [inDirectory] unless absolute. */
private fun dependenciesOf(inFile: File, inDirectory: File): List<File> {
	if (!inFile.isFile) return emptyList()
	val vText = inFile.readText().replace("\\\r\n", " ").replace("\\\n", " ")
	return vText.substringAfter(": ").split(Regex("(?<!\\\\)\\s+")).filter { it.isNotBlank() }.map { it.replace("\\ ", " ") }
		.map { File(it).let { vFile -> if (vFile.isAbsolute) vFile else File(inDirectory, it) } }
}

/**
 * Builds the program with CMake, from the `CMakeLists.txt` [writeCMake] wrote: configured in
 * `<inOutDir>/cmake`, with the arguments `KTC2_CMAKE_ARGS` holds (a generator, a toolchain
 * file…) and the compiler `KTC2_CC` names when it names one.
 *
 * @return the program, or null after reporting the failure
 */
fun buildWithCMake(inOutDir: File, inName: String, inIsDebug: Boolean): File? {
	val vBuildDir = File(inOutDir, "cmake")
	val vExtra = Settings.list("build.cmakeArgs")
	val vConfiguration = if (inIsDebug) "Debug" else "Release"
	val vConfigure = listOf("cmake", "-S", inOutDir.absolutePath, "-B", vBuildDir.absolutePath, "-DCMAKE_BUILD_TYPE=$vConfiguration") +
		(if (inIsDebug) listOf("-DKTC2_DEBUG=ON") else listOf("-DKTC2_DEBUG=OFF")) +
		(Settings.text("build.cc").takeIf { it.isNotEmpty() }?.let { listOf("-DCMAKE_C_COMPILER=$it") } ?: emptyList()) +
		(macosArchitectures().takeIf { it.isNotEmpty() }?.let { listOf("-DCMAKE_OSX_ARCHITECTURES=${it.joinToString(";")}") } ?: emptyList()) + vExtra
	// What CMake prints is kept, and shown when it fails; meanwhile the step's line shows how far the build is.
	fun run(inStep: String, inCommand: List<String>): Boolean {
		Progress.begin(inStep)
		val vOutput = StringBuilder()
		val vPercent = Regex("^\\[\\s*(\\d+)%\\]")
		val vExit = try {
			val vProcess = ProcessBuilder(inCommand).redirectErrorStream(true)
				.redirectInput(ProcessBuilder.Redirect.from(File(if (kIsWindows) "NUL" else "/dev/null"))).start()
			vProcess.inputStream.bufferedReader().forEachLine { vLine ->
				vOutput.append(vLine).append('\n')
				vPercent.find(vLine)?.let { Progress.update("${it.groupValues[1]}%") }
			}
			vProcess.waitFor()
		} catch (vError: java.io.IOException) {
			Progress.fail()
			System.err.println("ktc2: error: cmake could not be run: ${vError.message}")
			return false
		}
		if (vExit != 0) {
			Progress.fail()
			System.err.print(vOutput)
			System.err.println("ktc2: error: cmake failed: ${inCommand.take(6).joinToString(" ")} …")
			return false
		}
		Progress.end()
		return true
	}
	// Configured once: the build itself configures again when CMakeLists.txt changes, and configuring each time makes it
	// link again for nothing. A command with other options configures again.
	val vConfigured = File(vBuildDir, "ktc2_configure.txt")
	if (!File(vBuildDir, "CMakeCache.txt").isFile || !vConfigured.isFile || vConfigured.readText() != vConfigure.joinToString("\n")) {
		vConfigured.delete()
		if (!run("CMake", vConfigure)) return null
		vConfigured.writeText(vConfigure.joinToString("\n"))
	}
	if (!run("C → program", listOf("cmake", "--build", vBuildDir.absolutePath, "--config", vConfiguration, "--target", inName, "--parallel"))) return null
	// A generator with several configurations puts the program in a directory named after the one built.
	val vFileName = if (kIsWindows) "$inName.exe" else inName
	return listOf(File(vBuildDir, vFileName), File(vBuildDir, "$vConfiguration/$vFileName")).firstOrNull { it.isFile }
		?: run {
			System.err.println("ktc2: error: cmake built no $vFileName in $vBuildDir")
			null
		}
}
