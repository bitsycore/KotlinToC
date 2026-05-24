#!/usr/bin/env pwsh
#
# run_tests.ps1 — Build the transpiler, then run all integration tests.
#
# Usage:
#   .\run_tests.ps1                            # Run all tests
#   .\run_tests.ps1 -Interactive               # Interactive TUI: pick tests and options
#   .\run_tests.ps1 -Run HashMapTest           # Run a single test (verbose)
#   .\run_tests.ps1 -Run "Test1,Test2"         # Run multiple tests
#   .\run_tests.ps1 -Skip unit                 # Skip unit tests
#   .\run_tests.ps1 -Run game -Gui                # Run with GUI window open (real-time output)
#   .\run_tests.ps1 -Run game -MemTrack           # With --mem-track
#   .\run_tests.ps1 -Run game -Ast                # With --ast
#   .\run_tests.ps1 -Run game -DumpSemantics      # With --dump-semantics
#   .\run_tests.ps1 -Run game -Disposed ASSERT        # Use-after-dispose: abort
#   .\run_tests.ps1 -Run game -Disposed LOG            # Use-after-dispose: log and continue
#   .\run_tests.ps1 -Run game -DoubleDispose ASSERT    # Double-dispose: abort
#   .\run_tests.ps1 -Run game -DoubleDispose LOG       # Double-dispose: log and continue
#   .\run_tests.ps1 -Clean                     # Remove all test out/ directories
#   .\run_tests.ps1 -Rebuild                   # Force clean rebuild of JAR
#   .\run_tests.ps1 -Compiler clang            # Override C compiler
#   .\run_tests.ps1 -CCArgs "-j14 -O2"         # Extra C compiler flags
#   .\run_tests.ps1 -Build Jar                 # Build fat JAR (default)
#   .\run_tests.ps1 -Build Gradle              # Use gradle run (no JAR)
#   .\run_tests.ps1 -Build Proguard            # ProGuard-optimized JAR
#

param(
	[string]$Run            = "",
	[string]$Skip           = "",
	[string]$TranspilerArgs = "",
	[string]$Compiler       = "",
	[string]$CCArgs         = "",
	[string]$Build          = "Jar",
	[string]$Cfg            = "Release",  # CMake build type (Debug, Release, RelWithDebInfo, MinSizeRel)
	[switch]$Interactive,
	[switch]$Gui,
	[switch]$MemTrack,
	[switch]$Ast,
	[switch]$DumpSemantics,
	[string]$Disposed      = "NO",   # ASSERT | LOG | NO
	[string]$DoubleDispose = "NO",   # ASSERT | LOG | NO
	[switch]$Clean,
	[switch]$Rebuild,
	[switch]$Help,
	[Parameter(ValueFromRemainingArguments=$true)]
	[string[]]$vUnknownArgs = @()
)

# ==================
# MARK: Setup
# ==================

function Show-Help {
	$vInUsage = $false
	Get-Content $PSCommandPath | ForEach-Object {
		$vLine = $_.TrimStart()
		if ($vLine -match '^# Usage:')                { $vInUsage = $true }
		elseif ($vInUsage -and $vLine -notmatch '^#') { $vInUsage = $false; return }
		if ($vInUsage) { Write-Host ($vLine -replace '^# ?', '') }
	}
}

if ($vUnknownArgs.Count -gt 0) {
	Write-Host "Unknown parameter(s): $($vUnknownArgs -join ', ')" -ForegroundColor Red
	Write-Host ""
	Show-Help
	exit 1
}

if ($Help) {
	Show-Help
	exit 0
}

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$vRoot       = $PSScriptRoot                                             # project root
$vJar        = "$vRoot\build\libs\KotlinToC-1.0-SNAPSHOT.jar"          # standard fat JAR
$vReleaseJar = "$vRoot\build\libs\KotlinToC-1.0-SNAPSHOT-release.jar"  # ProGuard JAR
$vTestsDir   = "$vRoot\integration"                                            # integration tests root
$vBuildMode  = $Build.ToLowerInvariant()                                # normalized build mode string
$vGradleJar  = if ($Rebuild) { "clean jar" } else { "jar" }            # gradle JAR task
$vGradlePro  = if ($Rebuild) { "clean proguard" } else { "proguard" }  # gradle ProGuard task

# ==================
# MARK: Output helpers
# ==================

function Write-Pass { param($msg) Write-Host "  PASS " -ForegroundColor Green      -NoNewline; Write-Host $msg }
function Write-Fail { param($msg) Write-Host "  FAIL " -ForegroundColor Red        -NoNewline; Write-Host $msg }
function Write-Info { param($msg) Write-Host "  ---- " -ForegroundColor Cyan       -NoNewline; Write-Host $msg }
function Write-Sec  { param($msg) Write-Host "`n=== $msg ===" -ForegroundColor Yellow }
function Write-Cmd  { param($msg) Write-Host "  `$ "   -ForegroundColor DarkYellow -NoNewline; Write-Host $msg -ForegroundColor White }

<#
Formats elapsed milliseconds as "123ms" or "1.23s".
#>
function Format-Ms {
	param([long]$inMs)
	if ($inMs -lt 1000) { return "${inMs}ms" }
	return ("{0:N2}s" -f ($inMs / 1000.0))
}

# ==================
# MARK: Clean
# ==================

if ($Clean) {
	Write-Host "Cleaning per-test output directories..." -ForegroundColor Cyan
	$vCleaned = 0
	Get-ChildItem $vTestsDir -Directory | ForEach-Object {
		$vOut = Join-Path $_.FullName "out"
		if (Test-Path $vOut) {
			Remove-Item $vOut -Recurse -Force
			Write-Host "  removed $vOut" -ForegroundColor DarkGray
			$vCleaned++
		}
	}
	Write-Host "Cleaned $vCleaned test output directories." -ForegroundColor Green
	exit 0
}

# ==================
# MARK: Compiler detection
# ==================

<#
Returns $inPreferred if set, otherwise the first of gcc/clang/cl found on PATH.
#>
function Find-CCompiler {
	param([string]$inPreferred)
	if ($inPreferred -ne "") { return $inPreferred }
	foreach ($vCand in @("gcc", "clang", "cl")) {
		if (Get-Command $vCand -ErrorAction SilentlyContinue) { return $vCand }
	}
	return $null
}

<#
Returns "cmake" if cmake is on PATH, otherwise $null.
#>
function Find-CMake {
	if (Get-Command cmake -ErrorAction SilentlyContinue) { return "cmake" }
	return $null
}

# ==================
# MARK: Build
# ==================

<#
Builds the transpiler JAR (or ProGuard release JAR) via Gradle. Exits on failure.
#>
function Invoke-Build {
	if ($vBuildMode -eq "gradle") { return }
	if ($vBuildMode -eq "proguard") {
		Write-Sec "Building ProGuard release JAR"
		Write-Cmd "gradlew $vGradlePro"
		& "$vRoot\gradlew.bat" $vGradlePro.Split(' ') 2>&1
		if ($LASTEXITCODE -ne 0 -or -not (Test-Path $vReleaseJar)) {
			Write-Host "ERROR: ProGuard build failed" -ForegroundColor Red; exit 1
		}
		Write-Pass "Built $vReleaseJar"
	} else {
		Write-Sec "Building transpiler JAR"
		Write-Cmd "gradlew $vGradleJar"
		& "$vRoot\gradlew.bat" $vGradleJar.Split(' ') 2>&1
		if ($LASTEXITCODE -ne 0 -or -not (Test-Path $vJar)) {
			Write-Host "ERROR: JAR build failed" -ForegroundColor Red; exit 1
		}
		Write-Pass "Built $vJar"
	}
}

# ==================
# MARK: Test execution (verbose, single-test mode)
# ==================

<#
Transpiles, compiles, and runs one test directory with verbose step-by-step output.
Returns $true on success. Used by -Run mode and interactive single-test selection.
#>
function Invoke-Test {
	param(
		[string]$inName,
		[string]$inSrcDir,
		[string]$inOutDir,
		[string]$inExtraArgs = "",
		[bool]$inGuiMode = $false
	)

	$vSw = [Diagnostics.Stopwatch]::StartNew()

	# Collect .kt sources
	$vKtFiles = @(Get-ChildItem "$inSrcDir\*.kt" -Recurse -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName)
	if ($vKtFiles.Count -eq 0) { Write-Fail "$inName — no .kt files in $inSrcDir"; return "fail" }
	# Preserve _cmake/ build cache, ktc_user.cmake and *.dll across runs
	if (Test-Path $inOutDir) {
		Get-ChildItem $inOutDir -Directory -Exclude "_cmake" -ErrorAction SilentlyContinue | Remove-Item -Recurse -Force
		Get-ChildItem $inOutDir -File -Exclude "ktc_user.cmake", "*.dll" -ErrorAction SilentlyContinue | Remove-Item -Force
	}
	New-Item $inOutDir -ItemType Directory -Force | Out-Null
	Write-Info "Input:      $(($vKtFiles | ForEach-Object { Split-Path $_ -Leaf }) -join ' ')"
	Write-Info "Output dir: $inOutDir"

	# ── Transpile ────────────────────────────────────────────────
	Write-Sec "Transpile"
	$vKtNames = ($vKtFiles | ForEach-Object { Split-Path $_ -Leaf }) -join ' '
	$vSuffix  = if ($inExtraArgs) { " $inExtraArgs" } else { "" }
	if ($vBuildMode -eq "gradle") {
		$vKtStr = ($vKtFiles -join " ") + " -o $inOutDir$vSuffix"
		Write-Cmd "gradlew run --args=`"$vKtNames -o $inOutDir$vSuffix`""
		$vTOut  = & "$vRoot\gradlew.bat" run --quiet --args="$vKtStr" 2>&1
	} else {
		$vActive = if ($vBuildMode -eq "proguard") { $vReleaseJar } else { $vJar }
		$vTArgs  = @("-jar", $vActive) + $vKtFiles + @("-o", $inOutDir)
		if ($inExtraArgs) { $vTArgs += ($inExtraArgs -split '\s+') }
		$vLabel  = if ($vBuildMode -eq "proguard") { "KotlinToC-release.jar" } else { "KotlinToC.jar" }
		Write-Cmd "java -jar $vLabel $vKtNames -o $inOutDir$vSuffix"
		$vTOut  = & java @vTArgs 2>&1
	}
	$vTExit = $LASTEXITCODE;  $vTMs = $vSw.ElapsedMilliseconds;  $vSw.Restart()
	Write-Host ""
	foreach ($vLine in $vTOut) {
		if ("$vLine" -match 'warning:') { Write-Host "  $vLine" -ForegroundColor Yellow }
		else                            { Write-Host "  $vLine" }
	}
	Write-Host ""
	if ($vTExit -ne 0) { Write-Fail "Transpilation failed (exit $vTExit)"; return "fail" }
	Write-Host "  PASS " -ForegroundColor Green -NoNewline; Write-Host "Transpilation succeeded  " -NoNewline
	Write-Host "(ktc: $(Format-Ms $vTMs))" -ForegroundColor DarkGray

	# Copy ktc_user.cmake from source dir if present — src dir is not wiped on rebuild
	$vUserCmakeSrc = "$inSrcDir\ktc_user.cmake"
	if (Test-Path $vUserCmakeSrc) { Copy-Item $vUserCmakeSrc "$inOutDir\ktc_user.cmake" -Force }

	# ── Discover .c files ────────────────────────────────────────
	$vKtcDir  = "$inOutDir\ktc"
	$vCoreDir = "$vKtcDir\core"
	$vCSrcs   = @()
	if (Test-Path $vKtcDir -PathType Container) {
		$vCore = "$vCoreDir\ktc_core.c"
		if (Test-Path $vCore) { $vCSrcs += $vCore }
		Get-ChildItem "$vKtcDir" -Recurse -Filter "*.c" -ErrorAction SilentlyContinue |
			Where-Object { $_.Name -ne "ktc_core.c" } | Sort-Object FullName |
			ForEach-Object { $vCSrcs += $_.FullName }
	}
	Get-ChildItem "$inOutDir" -Recurse -Filter "*.c" -ErrorAction SilentlyContinue |
		Where-Object { $_.FullName -notlike "$vKtcDir*" } | Sort-Object FullName |
		ForEach-Object { $vCSrcs += $_.FullName }
	if ($vCSrcs.Count -eq 0) { Write-Fail "No .c files generated"; return "fail" }

	# ── Compile (cmake if ktc_user.cmake present, direct gcc otherwise) ──
	$vExe         = "$inOutDir\$inName.exe"
	$vHasUserCmake = Test-Path "$inOutDir\ktc_user.cmake"
	if ($vHasUserCmake -and -not $vCmake) {
		Write-Host "  SKIP " -ForegroundColor Yellow -NoNewline
		Write-Host "$inName — ktc_user.cmake requires cmake, but cmake was not found on PATH (skipped)"
		return "skip"
	}
	$vUseCmake = $vHasUserCmake

	if ($vUseCmake) {
		# ── CMake configure ──────────────────────────────────────
		Write-Sec "CMake Configure"
		$vCmakeBuildDir = "$inOutDir\_cmake"
		if ($Compiler) { Write-Cmd "cmake -B _cmake -S . -DCMAKE_BUILD_TYPE=$Cfg -DCMAKE_C_COMPILER=$Compiler" } else { Write-Cmd "cmake -B _cmake -S . -DCMAKE_BUILD_TYPE=$Cfg" }
		Write-Host ""
		$vCfgArgs = @("-B", $vCmakeBuildDir, "-S", $inOutDir, "-DCMAKE_BUILD_TYPE=$Cfg"); if ($Compiler) { $vCfgArgs += "-DCMAKE_C_COMPILER=$Compiler" }; $vCfgOut  = & $vCmake @vCfgArgs 2>&1
		$vCfgExit = $LASTEXITCODE;  $vCMs = $vSw.ElapsedMilliseconds;  $vSw.Restart()
		foreach ($vLine in $vCfgOut) { Write-Host "  $vLine" -ForegroundColor DarkGray }
		Write-Host ""
		if ($vCfgExit -ne 0) {
			$vNotFound = ($vCfgOut | Where-Object { "$_" -match 'Could not find|not found|NOTFOUND' }).Count -gt 0
			if ($vNotFound) {
				Write-Host "  SKIP " -ForegroundColor Yellow -NoNewline
				Write-Host "$inName — required library not found (skipped)"
				return "skip"
			}
			Write-Fail "CMake configure failed (exit $vCfgExit)"; return "fail"
		}
		Write-Host "  PASS " -ForegroundColor Green -NoNewline; Write-Host "CMake configure OK  " -NoNewline
		Write-Host "(cfg: $(Format-Ms $vCMs))" -ForegroundColor DarkGray

		# ── CMake build ───────────────────────────────────────────
		Write-Sec "CMake Build"
		Write-Cmd "cmake --build _cmake --config $Cfg"
		Write-Host ""
		$vBldOut  = & $vCmake --build $vCmakeBuildDir --config $Cfg 2>&1
		$vBldExit = $LASTEXITCODE;  $vCMs = $vSw.ElapsedMilliseconds;  $vSw.Restart()
		if ($vBldOut) { foreach ($vLine in $vBldOut) { Write-Host "  $vLine" -ForegroundColor DarkGray }; Write-Host "" }
		if ($vBldExit -ne 0) { Write-Fail "CMake build failed (exit $vBldExit)"; return "fail" }
		# CMakeLists.txt sets CMAKE_RUNTIME_OUTPUT_DIRECTORY to CMAKE_SOURCE_DIR (= $inOutDir)
		$vFoundExe = Get-ChildItem $inOutDir -Filter "*.exe" -ErrorAction SilentlyContinue |
			Where-Object { $_.FullName -notlike "*\_cmake*" -and $_.Name -ne "_ktcrun.exe" } | Select-Object -First 1
		if (-not $vFoundExe) { Write-Fail "No .exe found after cmake build"; return "fail" }
		$vExe = $vFoundExe.FullName
		Write-Host "  PASS " -ForegroundColor Green -NoNewline; Write-Host "CMake build OK -> $vExe  " -NoNewline
		Write-Host "(build: $(Format-Ms $vCMs))" -ForegroundColor DarkGray

	} else {
		# ── Direct compile ────────────────────────────────────────
		Write-Sec "Compile"
		$vCArgs = @("-std=c11", "-iquote", $inOutDir, "-o", $vExe)
		if ($CCArgs) { $vCArgs += ($CCArgs -split '\s+') }
		$vCArgs += $vCSrcs
		Write-Cmd "$vCC -std=c11$(if ($CCArgs) { " $CCArgs" }) -o $vExe $($vCSrcs -join ' ')"
		Write-Host ""
		$vCOut  = & $vCC @vCArgs 2>&1
		$vCExit = $LASTEXITCODE;  $vCMs = $vSw.ElapsedMilliseconds;  $vSw.Restart()
		if ($vCOut) { foreach ($vLine in $vCOut) { Write-Host "  $vLine" -ForegroundColor DarkGray }; Write-Host "" }
		if ($vCExit -ne 0) { Write-Fail "Compilation failed (exit $vCExit)"; return "fail" }
		Write-Host "  PASS " -ForegroundColor Green -NoNewline; Write-Host "Compilation succeeded -> $vExe  " -NoNewline
		Write-Host "(comp: $(Format-Ms $vCMs))" -ForegroundColor DarkGray
	}

	# Generated files listing
	Write-Sec "Generated Files"
	Get-ChildItem $inOutDir -Recurse -File | Sort-Object FullName | ForEach-Object {
		$vRel  = $_.FullName.Substring($inOutDir.Length + 1)
		$vSize = if ($_.Length -ge 1024) { "{0:N1} KB" -f ($_.Length / 1024) } else { "$($_.Length) B" }
		Write-Info ("{0,-30} {1,10}" -f $vRel, $vSize)
	}

	# ── Run ──────────────────────────────────────────────────────
	Write-Sec "Run"
	Write-Cmd $vExe; Write-Host ""
	# _test_args.txt: "gui" marks a test that opens a window.
	# In automated mode the script injects --skip-gui; with -Gui the window opens for real.
	$vTestArgsFile = "$inSrcDir\_test_args.txt"
	$vIsGuiTest    = $false
	$vRunArgs      = ""
	if (Test-Path $vTestArgsFile) {
		$vFileContent = ((Get-Content $vTestArgsFile) -join ' ').Trim()
		if ($vFileContent -eq "gui") { $vIsGuiTest = $true }
		else                         { $vRunArgs = $vFileContent }
	}
	if ($vIsGuiTest -and -not $inGuiMode) { $vRunArgs = "--skip-gui" }
	if ($vRunArgs) { Write-Info "Test args: $vRunArgs" }

	# UseShellExecute=false uses CreateProcess — no Windows auto-elevation.
	$vPsi = [System.Diagnostics.ProcessStartInfo]::new($vExe)
	if ($vRunArgs) { $vPsi.Arguments = $vRunArgs }
	$vPsi.UseShellExecute = $false

	$vCaptured = @()
	if ($inGuiMode -and $vIsGuiTest) {
		# GUI mode: stdout flows directly to the console in real time — no redirect.
		$vP = [System.Diagnostics.Process]::Start($vPsi)
		$vP.WaitForExit()
		$vRExit = $vP.ExitCode;  $vRMs = $vSw.ElapsedMilliseconds;  $vSw.Stop()
	} else {
		# Headless mode: capture output, print after exit.
		$vPsi.RedirectStandardOutput = $true
		$vPsi.RedirectStandardError  = $true
		$vP = [System.Diagnostics.Process]::Start($vPsi)
		$vRawOut = $vP.StandardOutput.ReadToEnd()
		$vRawErr = $vP.StandardError.ReadToEnd()
		$vP.WaitForExit()
		$vRExit = $vP.ExitCode;  $vRMs = $vSw.ElapsedMilliseconds;  $vSw.Stop()
		$vCaptured = (($vRawOut + $vRawErr) -split "`r?`n")
		$vCaptured | ForEach-Object { Write-Host "  $_" }
		Write-Host ""
	}
	if ($vRExit -ne 0) { Write-Fail "Runtime error (exit $vRExit)"; return "fail" }

	$vLeak = ($vCaptured | Where-Object { "$_" -match 'leaked\s+:' }).Count -gt 0
	if ($vLeak) {
		Write-Host "  PASS " -ForegroundColor DarkYellow -NoNewline
		Write-Host "Program exited - memory leaks detected  " -NoNewline
		Write-Host "LEAK  " -ForegroundColor Red -NoNewline
	} else {
		Write-Host "  PASS " -ForegroundColor Green -NoNewline
		Write-Host "Program exited successfully (code 0)  " -NoNewline
	}
	Write-Host "(run: $(Format-Ms $vRMs))" -ForegroundColor DarkGray
	return "pass"
}

# ==================
# MARK: Suite (parallel)
# ==================

<#
Runs a list of tests in parallel, prints results, returns summary object.
NOTE: ForEach-Object -Parallel cannot call outer-scope functions,
so the per-test logic is inlined in the parallel scriptblock below.
#>
function Run-Suite {
	param(
		[string[]]$inTestNames,  # list of test directory names to run
		[string]$inExtraArgs  = "",
		[bool]$inSkipUnit     = $false
	)

	$vPassed      = 0   # number of passing integration tests
	$vFailed      = 0   # number of failing tests (unit + integration)
	$vSkipped     = 0   # number of skipped tests (missing external library)
	$vFailedNames  = @()
	$vSkippedNames = @()

	# Unit tests
	if (-not $inSkipUnit) {
		Write-Sec "Unit Tests (gradlew test)"
		& "$vRoot\gradlew.bat" test --quiet 2>&1
		if ($LASTEXITCODE -eq 0) { Write-Pass "All unit tests passed" }
		else { Write-Fail "Unit tests had failures"; $vFailed++; $vFailedNames += "unit-tests" }
	}

	# Integration tests
	Write-Sec "Integration Tests"
	if ($inTestNames.Count -eq 0) { Write-Info "No tests to run."; return }

	$vDirs     = @($inTestNames | ForEach-Object { Get-Item "$vTestsDir\$_" })
	$vThrottle = [Math]::Max(1, [Environment]::ProcessorCount)

	$vResults = $vDirs | ForEach-Object -Parallel {
		$vDir   = $_                  # current test directory
		$vName  = $vDir.Name          # test name
		$vOut   = "$($vDir.FullName)\out"
		$vBm    = $using:vBuildMode
		$vJar   = $using:vJar
		$vRJ    = $using:vReleaseJar
		$vRt    = $using:vRoot
		$vCC    = $using:vCC
		$vCmk   = $using:vCmake
		$vCCa   = $using:CCArgs
		$vExA   = $using:inExtraArgs

		function fmtMs([long]$ms) {
			if ($ms -lt 1000) { "${ms}ms" } else { "{0:N2}s" -f ($ms / 1000.0) }
		}

		$vSw  = [Diagnostics.Stopwatch]::StartNew()

		# Collect .kt files
		$vKts = @(Get-ChildItem "$($vDir.FullName)\*.kt" -Recurse -ErrorAction SilentlyContinue | ForEach-Object FullName)
		if ($vKts.Count -eq 0) {
			Write-Host "  FAIL $vName (no .kt files)" -ForegroundColor Red
			return @{ Name = $vName; Passed = $false }
		}
		if (Test-Path $vOut) {
			Get-ChildItem $vOut -Directory -Exclude "_cmake" -ErrorAction SilentlyContinue | Remove-Item -Recurse -Force
			Get-ChildItem $vOut -File -Exclude "ktc_user.cmake", "*.dll" -ErrorAction SilentlyContinue | Remove-Item -Force
		}
		New-Item $vOut -ItemType Directory -Force | Out-Null

		# Transpile
		if ($vBm -eq "gradle") {
			$vExAStr = if ($vExA) { " $vExA" } else { "" }
			$vAs  = ($vKts -join " ") + " -o $vOut$vExAStr"
			$vTO  = & "$vRt\gradlew.bat" run --quiet --args="$vAs" 2>&1
		} else {
			$vAJ  = if ($vBm -eq "proguard") { $vRJ } else { $vJar }
			$vTA  = @("-jar", $vAJ) + $vKts + @("-o", $vOut)
			if ($vExA) { $vTA += $vExA -split '\s+' }
			$vTO  = & java @vTA 2>&1
		}
		$vTEx = $LASTEXITCODE;  $vTMs = $vSw.ElapsedMilliseconds;  $vSw.Restart()
		$vWrn = @($vTO | Where-Object { "$_" -match 'warning:' })
		if ($vTEx -ne 0) {
			Write-Host "  FAIL $vName (transpile failed)" -ForegroundColor Red
			return @{ Name = $vName; Passed = $false; Skipped = $false }
		}

		# Copy ktc_user.cmake from source dir if present
		$vUCmakeSrc = "$($vDir.FullName)\ktc_user.cmake"
		if (Test-Path $vUCmakeSrc) { Copy-Item $vUCmakeSrc "$vOut\ktc_user.cmake" -Force }

		# Discover .c files
		$vKD  = "$vOut\ktc";  $vCS = @()
		if (Test-Path $vKD -PathType Container) {
			$vCr = "$vKD\core\ktc_core.c"
			if (Test-Path $vCr) { $vCS += $vCr }
			Get-ChildItem "$vKD" -Recurse -Filter "*.c" -ErrorAction SilentlyContinue |
				Where-Object { $_.Name -ne "ktc_core.c" } | Sort-Object FullName |
				ForEach-Object { $vCS += $_.FullName }
		}
		Get-ChildItem "$vOut" -Recurse -Filter "*.c" -ErrorAction SilentlyContinue |
			Where-Object { $_.FullName -notlike "$vKD*" } | Sort-Object FullName |
			ForEach-Object { $vCS += $_.FullName }
		if ($vCS.Count -eq 0) {
			Write-Host "  FAIL $vName (no .c files generated)" -ForegroundColor Red
			return @{ Name = $vName; Passed = $false; Skipped = $false }
		}

		# Compile — cmake if ktc_user.cmake present, direct gcc otherwise
		$vExe       = "$vOut\$vName.exe"
		$vHasUCmake = Test-Path "$vOut\ktc_user.cmake"
		if ($vHasUCmake -and -not $vCmk) {
			$e = [char]27
			Write-Host "  ${e}[33mSKIP $vName${e}[0m  (ktc_user.cmake present but cmake not found)"
			return @{ Name = $vName; Passed = $false; Skipped = $true }
		}
		if ($vHasUCmake) {
			$vCmkBld = "$vOut\_cmake"
			$vCfgArgs = @("-B", $vCmkBld, "-S", $vOut, "-DCMAKE_BUILD_TYPE=$using:Cfg"); if ($using:Compiler) { $vCfgArgs += "-DCMAKE_C_COMPILER=$using:Compiler" }; $vCfgOut = & $vCmk @vCfgArgs 2>&1
			$vCfgEx  = $LASTEXITCODE
			if ($vCfgEx -ne 0) {
				$e  = [char]27
				$vNF = ($vCfgOut | Where-Object { "$_" -match 'Could not find|not found|NOTFOUND' }).Count -gt 0
				if ($vNF) {
					Write-Host "  ${e}[33mSKIP $vName${e}[0m  (required library not found)"
					return @{ Name = $vName; Passed = $false; Skipped = $true }
				}
				Write-Host "  FAIL $vName (cmake configure failed)" -ForegroundColor Red
				return @{ Name = $vName; Passed = $false; Skipped = $false }
			}
			& $vCmk --build $vCmkBld --config $using:Cfg 2>&1 | Out-Null
			$vCMs = $vSw.ElapsedMilliseconds;  $vSw.Restart()
			if ($LASTEXITCODE -ne 0) {
				Write-Host "  FAIL $vName (cmake build failed)" -ForegroundColor Red
				return @{ Name = $vName; Passed = $false; Skipped = $false }
			}
			$vFound = Get-ChildItem $vOut -Filter "*.exe" -ErrorAction SilentlyContinue |
				Where-Object { $_.FullName -notlike "*\_cmake*" -and $_.Name -ne "_ktcrun.exe" } | Select-Object -First 1
			if (-not $vFound) {
				Write-Host "  FAIL $vName (no .exe after cmake build)" -ForegroundColor Red
				return @{ Name = $vName; Passed = $false; Skipped = $false }
			}
			$vExe = $vFound.FullName
		} else {
			$vCA  = @("-std=c11", "-iquote", $vOut, "-o", $vExe)
			if ($vCCa) { $vCA += $vCCa -split '\s+' }
			$vCA += $vCS
			& $vCC @vCA 2>&1 | Out-Null
			$vCMs = $vSw.ElapsedMilliseconds;  $vSw.Restart()
			if ($LASTEXITCODE -ne 0) {
				Write-Host "  FAIL $vName (compile failed)" -ForegroundColor Red
				return @{ Name = $vName; Passed = $false; Skipped = $false }
			}
		}

		# Run — "gui" in _test_args.txt marks a windowed test; always headless in suite mode.
		$vTAFile  = "$($vDir.FullName)\_test_args.txt"
		$vRunArgs = ""
		if (Test-Path $vTAFile) {
			$vFC = ((Get-Content $vTAFile) -join ' ').Trim()
			if ($vFC -eq "gui") { $vRunArgs = "--skip-gui" } else { $vRunArgs = $vFC }
		}
		$vPsi = [System.Diagnostics.ProcessStartInfo]::new($vExe)
		if ($vRunArgs) { $vPsi.Arguments = $vRunArgs }
		$vPsi.RedirectStandardOutput = $true
		$vPsi.RedirectStandardError  = $true
		$vPsi.UseShellExecute        = $false
		$vP = [System.Diagnostics.Process]::Start($vPsi)
		$vRawOut = $vP.StandardOutput.ReadToEnd()
		$vRawErr = $vP.StandardError.ReadToEnd()
		$vP.WaitForExit()
		$vREx = $vP.ExitCode;  $vRMs = $vSw.ElapsedMilliseconds;  $vSw.Stop()
		$vCapt = (($vRawOut + $vRawErr) -split "`r?`n")
		if ($vREx -ne 0) {
			Write-Host "  FAIL $vName (runtime error, exit $vREx)" -ForegroundColor Red
			return @{ Name = $vName; Passed = $false; Skipped = $false }
		}

		$vLk  = @($vCapt | Where-Object { "$_" -match 'leaked\s+:' }).Count -gt 0
		$e    = [char]27
		$vTim = "${e}[90mktc: $(fmtMs $vTMs)  comp: $(fmtMs $vCMs)  run: $(fmtMs $vRMs)${e}[0m"
		if ($vLk) { Write-Host "  ${e}[33mPASS $vName${e}[0m  ${e}[31mLEAK${e}[0m  $vTim" }
		else      { Write-Host "  ${e}[32mPASS $vName${e}[0m  $vTim" }
		foreach ($vW in $vWrn) { Write-Host "       $vW" -ForegroundColor Yellow }
		return @{ Name = $vName; Passed = $true; Skipped = $false }

	} -ThrottleLimit $vThrottle

	foreach ($vR in $vResults) {
		if     ($vR.Skipped) { $vSkipped++; $vSkippedNames += $vR.Name }
		elseif ($vR.Passed)  { $vPassed++ }
		else                 { $vFailed++;  $vFailedNames  += $vR.Name }
	}

	return [PSCustomObject]@{
		Passed       = $vPassed
		Failed       = $vFailed
		Skipped      = $vSkipped
		FailedNames  = $vFailedNames
		SkippedNames = $vSkippedNames
	}
}

<#
Prints the final pass/fail summary and exits with the appropriate code.
#>
function Show-Summary {
	param([PSCustomObject]$inResult)
	Write-Sec "Summary"
	$vTotal = $inResult.Passed + $inResult.Failed + $inResult.Skipped
	Write-Host "  Total: $vTotal  |  " -NoNewline
	Write-Host "Passed: $($inResult.Passed)" -ForegroundColor Green -NoNewline
	Write-Host "  |  " -NoNewline
	if ($inResult.Skipped -gt 0) {
		Write-Host "Skipped: $($inResult.Skipped)" -ForegroundColor Yellow -NoNewline
		Write-Host "  |  " -NoNewline
	}
	if ($inResult.Failed -gt 0) {
		Write-Host "Failed: $($inResult.Failed)" -ForegroundColor Red
		Write-Host ""
		Write-Host "  Failed tests:" -ForegroundColor Red
		foreach ($vName in $inResult.FailedNames) { Write-Host "    - $vName" -ForegroundColor Red }
		if ($inResult.Skipped -gt 0) {
			Write-Host ""
			Write-Host "  Skipped tests (missing library):" -ForegroundColor Yellow
			foreach ($vName in $inResult.SkippedNames) { Write-Host "    - $vName" -ForegroundColor Yellow }
		}
		exit 1
	} else {
		Write-Host "Failed: 0" -ForegroundColor Green
		if ($inResult.Skipped -gt 0) {
			Write-Host ""
			Write-Host "  Skipped tests (missing library):" -ForegroundColor Yellow
			foreach ($vName in $inResult.SkippedNames) { Write-Host "    - $vName" -ForegroundColor Yellow }
		}
		exit 0
	}
}

# ==================
# MARK: Interactive TUI
# ==================

<#
Interactive checkbox TUI for selecting tests, options, and build mode.
Sections: TESTS | OPTIONS | BUILD | COMPILER — navigate with ◄► arrows.
Uses alternate screen buffer and a single buffered write to avoid flicker.
Returns a selection object or $null on cancel.
#>
class TuiRunner {

	[object[]] $Tests      # array of { Name, On }
	[object[]] $Opts       # array of { Label, Key, On }
	[object[]] $Builds     # array of { Label, Value, On }
	[object[]] $Fields     # array of { Label, Key, Value } — editable text fields
	[string]   $Section    # active section: "tests" | "opts" | "build" | "compiler"
	[int]      $Idx        # cursor position within the active section
	[int]      $ViewOff    # first visible test index (scroll offset)

	TuiRunner([string[]]$inNames, [string]$inCC) {
		$this.Tests  = @($inNames | ForEach-Object { [PSCustomObject]@{ Name = $_; On = $true } })
		$this.Opts   = @(
			[PSCustomObject]@{ Label = "Skip Unit Tests      (-Skip unit)";        Key = "SkipUnit";            On = $false },
			[PSCustomObject]@{ Label = "Memory Tracking      (--mem-track)";       Key = "MemTrack";            On = $false },
			[PSCustomObject]@{ Label = "Dump AST             (--ast)";             Key = "Ast";                 On = $false },
			[PSCustomObject]@{ Label = "Dump Semantics       (--dump-semantics)";  Key = "DumpSemantics";       On = $false },
			[PSCustomObject]@{ Label = "Disposed ASSERT      (--disposed=ASSERT)"; Key = "DisposedAssert";      On = $false },
			[PSCustomObject]@{ Label = "Disposed LOG         (--disposed=LOG)";    Key = "DisposedLog";         On = $false },
			[PSCustomObject]@{ Label = "Double-Dispose ASSERT  (--double-dispose=ASSERT)"; Key = "DoubleDisposeAssert"; On = $false },
			[PSCustomObject]@{ Label = "Double-Dispose LOG     (--double-dispose=LOG)";    Key = "DoubleDisposeLog";    On = $false }
		)
		$this.Builds = @(
			[PSCustomObject]@{ Label = "JAR (default)"; Value = "jar";      On = $true  },
			[PSCustomObject]@{ Label = "Gradle";        Value = "gradle";   On = $false },
			[PSCustomObject]@{ Label = "ProGuard";      Value = "proguard"; On = $false }
		)
		$this.Fields = @(
			[PSCustomObject]@{ Label = "Compiler"; Key = "Compiler"; Value = $inCC },
			[PSCustomObject]@{ Label = "CC Args "; Key = "CcArgs";   Value = "" }
		)
		$this.Section = "tests"
		$this.Idx     = 0
		$this.ViewOff = 0
	}

	# Clamps cursor index when switching into a new section.
	[void] ClampIdx() {
		$vMax = switch ($this.Section) {
			"tests"    { 0 }  # tests panel is a single entry — sub-screen opened via Space
			"opts"     { $this.Opts.Count   - 1 }
			"build"    { $this.Builds.Count - 1 }
			"compiler" { $this.Fields.Count - 1 }
		}
		if ($this.Idx -gt $vMax) { $this.Idx = $vMax }
	}

	# Renders the full TUI as a single buffered write to eliminate flicker.
	# Fixed height: 27 lines total — no variable test rows in main screen.
	[void] Render() {
		$e    = [char]27
		$kCy  = "${e}[36m";  $kYl = "${e}[33m";  $kGr  = "${e}[90m"
		$kWh  = "${e}[97m";  $kRst = "${e}[0m"
		$vW     = [Math]::Min(62, [Console]::WindowWidth - 2)
		$vTermH = [Console]::WindowHeight

		# Guard: 31 fixed lines + 2-line margin = 33 minimum rows required
		$kMinH = 33
		if ($vTermH -lt $kMinH) {
			$vSb = [System.Text.StringBuilder]::new(512)
			[void]$vSb.Append("${e}[H")
			$vMsg = " Terminal too small — need at least $kMinH rows (current: $vTermH)"
			[void]$vSb.Append($kYl + $vMsg.PadRight($vW + 2) + $kRst + "`n")
			for ($vi = 1; $vi -lt $vTermH; $vi++) { [void]$vSb.Append("".PadRight($vW + 2) + "`n") }
			[Console]::Write($vSb.ToString())
			return
		}

		$vSep = "─" * $vW
		$vDSp = "═" * $vW
		$vSel = @($this.Tests | Where-Object { $_.On }).Count
		$vCompiler = ($this.Fields | Where-Object { $_.Key -eq "Compiler" }).Value

		# Build compact 2-line test summary (split at last ", " boundary, truncate with "...")
		$vMaxSumW  = $vW - 2
		$vSelNames = @($this.Tests | Where-Object { $_.On } | ForEach-Object { $_.Name })
		$vSumFull  = if ($vSelNames.Count -eq 0)               { "(none selected)" }
		             elseif ($vSelNames.Count -eq $this.Tests.Count) { "All $($this.Tests.Count) tests selected" }
		             else                                        { $vSelNames -join ", " }
		$vSumLine1 = ""; $vSumLine2 = ""
		if ($vSumFull.Length -le $vMaxSumW) {
			$vSumLine1 = $vSumFull
		} else {
			$vCutStr   = $vSumFull.Substring(0, [Math]::Min($vSumFull.Length, $vMaxSumW + 1))
			$vCut      = $vCutStr.LastIndexOf(", ")
			if ($vCut -le 0) { $vCut = $vMaxSumW }
			$vSumLine1 = $vSumFull.Substring(0, $vCut)
			$vSumRest  = $vSumFull.Substring([Math]::Min($vCut + 2, $vSumFull.Length)).Trim()
			$vSumLine2 = if ($vSumRest.Length -le $vMaxSumW) { $vSumRest }
			             else { $vSumRest.Substring(0, $vMaxSumW - 3) + "..." }
		}

		$vSb = [System.Text.StringBuilder]::new(4096)
		[void]$vSb.Append("${e}[H")    # move to top-left; blank-fill below replaces [2J without flicker

		# Title
		[void]$vSb.Append($kCy + (" KotlinToC Test Runner  ─  Interactive Mode  [$vCompiler]").PadRight($vW + 2) + $kRst + "`n")
		[void]$vSb.Append($kGr + (" $vDSp") + $kRst + "`n")

		# TESTS — compact 2-line summary; Space opens the full selector sub-screen
		$vTActive = ($this.Section -eq "tests")
		$vTPtr    = if ($vTActive) { "►" } else { " " }
		$vTHdr    = " $vTPtr TESTS ($($this.Tests.Count) found, $vSel selected)"
		if ($vTActive) { $vTHdr += "   Space=select" }
		[void]$vSb.Append($kYl + $vTHdr.PadRight($vW + 2) + $kRst + "`n")
		[void]$vSb.Append($kGr + (" $vSep") + $kRst + "`n")
		[void]$vSb.Append($kGr + ("  $vSumLine1").PadRight($vW + 2) + $kRst + "`n")
		[void]$vSb.Append($kGr + ("  $vSumLine2").PadRight($vW + 2) + $kRst + "`n")

		# OPTIONS
		[void]$vSb.Append("".PadRight($vW + 2) + "`n")
		[void]$vSb.Append($kYl + (" OPTIONS").PadRight($vW + 2) + $kRst + "`n")
		[void]$vSb.Append($kGr + (" $vSep") + $kRst + "`n")
		for ($vi = 0; $vi -lt $this.Opts.Count; $vi++) {
			$vO   = $this.Opts[$vi]
			$vCur = ($this.Section -eq "opts" -and $vi -eq $this.Idx)
			$vFg  = if ($vCur) { $kWh } else { $kGr }
			$vPtr = if ($vCur) { "►" } else { " " }
			$vBox = if ($vO.On) { "[✓]" } else { "[ ]" }
			[void]$vSb.Append($vFg + (" $vPtr $vBox $($vO.Label)").PadRight($vW + 2) + $kRst + "`n")
		}

		# BUILD MODE
		[void]$vSb.Append("".PadRight($vW + 2) + "`n")
		[void]$vSb.Append($kYl + (" BUILD MODE").PadRight($vW + 2) + $kRst + "`n")
		[void]$vSb.Append($kGr + (" $vSep") + $kRst + "`n")
		for ($vi = 0; $vi -lt $this.Builds.Count; $vi++) {
			$vB   = $this.Builds[$vi]
			$vCur = ($this.Section -eq "build" -and $vi -eq $this.Idx)
			$vFg  = if ($vCur) { $kWh } else { $kGr }
			$vPtr = if ($vCur) { "►" } else { " " }
			$vBox = if ($vB.On) { "(•)" } else { "( )" }
			[void]$vSb.Append($vFg + (" $vPtr $vBox $($vB.Label)").PadRight($vW + 2) + $kRst + "`n")
		}

		# COMPILER & FLAGS
		[void]$vSb.Append("".PadRight($vW + 2) + "`n")
		[void]$vSb.Append($kYl + (" COMPILER & FLAGS").PadRight($vW + 2) + $kRst + "`n")
		[void]$vSb.Append($kGr + (" $vSep") + $kRst + "`n")
		for ($vi = 0; $vi -lt $this.Fields.Count; $vi++) {
			$vF   = $this.Fields[$vi]
			$vCur = ($this.Section -eq "compiler" -and $vi -eq $this.Idx)
			$vFg  = if ($vCur) { $kWh } else { $kGr }
			$vPtr = if ($vCur) { "►" } else { " " }
			$vVal = if ($vF.Value -ne "") { $vF.Value } else { "(none)" }
			$vHint = if ($vCur) { "  Space=edit" } else { "" }
			[void]$vSb.Append($vFg + (" $vPtr  $($vF.Label):  $vVal$vHint").PadRight($vW + 2) + $kRst + "`n")
		}

		# Hints bar — truncated to terminal width to prevent wrapping/scroll corruption
		$vHints = " ↑↓ Move   ◄► Panel   Space Toggle/Edit   A All   N None   Enter Run   Q Quit"
		$vHints = $vHints.Substring(0, [Math]::Min($vHints.Length, [Console]::WindowWidth - 1))
		[void]$vSb.Append("".PadRight($vW + 2) + "`n")
		[void]$vSb.Append($kGr + (" $vDSp") + $kRst + "`n")
		[void]$vSb.Append($kGr + $vHints.PadRight($vW + 2) + $kRst + "`n")
		# Fill rows below the 27 fixed lines with blanks — overwrites sub-screen residue without [2J flicker.
		# Use $vTermH-28 lines with newlines + 1 line without: total newlines = $vTermH-1, no terminal scroll.
		$vBlankLine = "".PadRight($vW + 2) + "`n"
		$vFillCount = [Math]::Max(0, $vTermH - 32)
		for ($vi = 0; $vi -lt $vFillCount; $vi++) { [void]$vSb.Append($vBlankLine) }
		[void]$vSb.Append("".PadRight($vW + 2))

		[Console]::Write($vSb.ToString())
	}

	# Inline alt-screen editor. ESC cancels (returns inCurrent), Enter confirms.
	# Shows CC Args cheatsheet when inShowCheat is true.
	[string] EditField([string]$inPrompt, [string]$inCurrent, [bool]$inShowCheat) {
		$e   = [char]27
		$kCy = "${e}[36m"; $kGr = "${e}[90m"; $kWh = "${e}[97m"
		$kYl = "${e}[33m"; $kRst = "${e}[0m"
		$vBuf = $inCurrent  # current input buffer

		while ($true) {
			$vSb = [System.Text.StringBuilder]::new(2048)
			[void]$vSb.Append("${e}[H${e}[2J")
			[void]$vSb.Append($kCy + " Edit: $inPrompt" + $kRst + "`n")
			[void]$vSb.Append($kGr + " Current: $inCurrent" + $kRst + "`n`n")
			[void]$vSb.Append($kWh + " > $vBuf" + $kGr + "_" + $kRst + "`n`n")
			[void]$vSb.Append($kGr + " Enter=confirm   Esc=cancel   Backspace=delete" + $kRst + "`n")
			if ($inShowCheat) {
				[void]$vSb.Append("`n")
				[void]$vSb.Append($kYl + " CC ARGS CHEATSHEET" + $kRst + "`n")
				[void]$vSb.Append($kGr + "   -g                  debug symbols" + $kRst + "`n")
				[void]$vSb.Append($kGr + "   -O0                 no optimization" + $kRst + "`n")
				[void]$vSb.Append($kGr + "   -O2 / -O3           optimize binary" + $kRst + "`n")
				[void]$vSb.Append($kGr + "   -Wall               enable warnings" + $kRst + "`n")
				[void]$vSb.Append($kGr + "   -fsanitize=address  AddressSanitizer" + $kRst + "`n")
				[void]$vSb.Append($kGr + "   -DDEBUG             debug define" + $kRst + "`n")
			}
			[Console]::Write($vSb.ToString())

			$vKey = [Console]::ReadKey($true)
			switch ($vKey.Key) {
				([ConsoleKey]::Escape)    { return $inCurrent }
				([ConsoleKey]::Enter)     { return $vBuf }
				([ConsoleKey]::Backspace) {
					if ($vBuf.Length -gt 0) { $vBuf = $vBuf.Substring(0, $vBuf.Length - 1) }
				}
				default {
					if ($vKey.KeyChar -ge ' ') { $vBuf += $vKey.KeyChar }
				}
			}
		}
		return $inCurrent
	}

	# Full-screen test selector sub-screen. Modifies Tests in place. Space=toggle, Enter/Esc=back.
	[void] SelectTests() {
		$e    = [char]27
		$kCy  = "${e}[36m"; $kGr = "${e}[90m"; $kWh = "${e}[97m"; $kRst = "${e}[0m"
		$vIdx = 0; $vOff = 0

		while ($true) {
			$vTermH = [Console]::WindowHeight
			$vW     = [Math]::Min(62, [Console]::WindowWidth - 2)
			# Fixed: title(1) sep(1) scroll(1) blank(1) dsep(1) hints(1) = 6
			$vViewH = [Math]::Max(1, $vTermH - 6)
			if ($vIdx -lt $vOff)           { $vOff = $vIdx }
			if ($vIdx -ge $vOff + $vViewH) { $vOff = $vIdx - $vViewH + 1 }

			$vSel = @($this.Tests | Where-Object { $_.On }).Count
			$vSep = "─" * $vW; $vDSp = "═" * $vW

			$vSb = [System.Text.StringBuilder]::new(4096)
			[void]$vSb.Append("${e}[H${e}[2J")
			[void]$vSb.Append($kCy + (" SELECT TESTS  ($($this.Tests.Count) found, $vSel selected)").PadRight($vW + 2) + $kRst + "`n")
			[void]$vSb.Append($kGr + (" $vSep") + $kRst + "`n")

			$vEnd = [Math]::Min($vOff + $vViewH, $this.Tests.Count)
			for ($vi = $vOff; $vi -lt $vEnd; $vi++) {
				$vT   = $this.Tests[$vi]
				$vCur = ($vi -eq $vIdx)
				$vFg  = if ($vCur) { $kWh } else { $kGr }
				$vPtr = if ($vCur) { "►" } else { " " }
				$vBox = if ($vT.On) { "[✓]" } else { "[ ]" }
				[void]$vSb.Append($vFg + (" $vPtr $vBox $($vT.Name)").PadRight($vW + 2) + $kRst + "`n")
			}
			[void]$vSb.Append($kGr + ("  ↕ $($vOff + 1)–$vEnd / $($this.Tests.Count)").PadRight($vW + 2) + $kRst + "`n")
			[void]$vSb.Append("".PadRight($vW + 2) + "`n")
			[void]$vSb.Append($kGr + (" $vDSp") + $kRst + "`n")
			$vHints = " ↑↓ Move   Space Toggle   A All   N None   Enter/Esc Back"
			$vHints = $vHints.Substring(0, [Math]::Min($vHints.Length, [Console]::WindowWidth - 1))
			[void]$vSb.Append($kGr + $vHints.PadRight($vW + 2) + $kRst + "`n")
			[Console]::Write($vSb.ToString())

			$vKey = [Console]::ReadKey($true)
			switch ($vKey.Key) {
				([ConsoleKey]::UpArrow)   { if ($vIdx -gt 0) { $vIdx-- } }
				([ConsoleKey]::DownArrow) { if ($vIdx -lt $this.Tests.Count - 1) { $vIdx++ } }
				([ConsoleKey]::Spacebar)  { $this.Tests[$vIdx].On = -not $this.Tests[$vIdx].On }
				([ConsoleKey]::Enter)     { return }
				([ConsoleKey]::Escape)    { return }
			}
			switch ([char]::ToUpper($vKey.KeyChar)) {
				'A' { $this.Tests | ForEach-Object { $_.On = $true  } }
				'N' { $this.Tests | ForEach-Object { $_.On = $false } }
			}
		}
	}

	# Main key loop. Returns a selection object on Enter, or $null on Q/Escape.
	[object] Loop() {
		[Console]::CursorVisible = $false
		[Console]::Write("$([char]27)[?1049h")   # enter alternate screen buffer
		try {
			$this.Render()
			while ($true) {
				$vKey = [Console]::ReadKey($true)
				switch ($vKey.Key) {
					([ConsoleKey]::UpArrow) {
						switch ($this.Section) {
							"tests"    { $this.Section = "compiler"; $this.Idx = $this.Fields.Count - 1 }
							"opts"     {
								if ($this.Idx -gt 0) { $this.Idx-- }
								else { $this.Section = "tests"; $this.Idx = 0 }
							}
							"build"    {
								if ($this.Idx -gt 0) { $this.Idx-- }
								else { $this.Section = "opts"; $this.Idx = $this.Opts.Count - 1 }
							}
							"compiler" {
								if ($this.Idx -gt 0) { $this.Idx-- }
								else { $this.Section = "build"; $this.Idx = $this.Builds.Count - 1 }
							}
						}
						$this.Render()
					}
					([ConsoleKey]::DownArrow) {
						switch ($this.Section) {
							"tests"    { $this.Section = "opts"; $this.Idx = 0 }
							"opts"     {
								if ($this.Idx -lt $this.Opts.Count - 1) { $this.Idx++ }
								else { $this.Section = "build"; $this.Idx = 0 }
							}
							"build"    {
								if ($this.Idx -lt $this.Builds.Count - 1) { $this.Idx++ }
								else { $this.Section = "compiler"; $this.Idx = 0 }
							}
							"compiler" {
							if ($this.Idx -lt $this.Fields.Count - 1) { $this.Idx++ }
							else { $this.Section = "tests"; $this.Idx = 0 }
						}
						}
						$this.Render()
					}
					([ConsoleKey]::RightArrow) {
						$this.Section = switch ($this.Section) { "tests" { "opts" } "opts" { "build" } "build" { "compiler" } "compiler" { "tests" } }
						$this.ClampIdx(); $this.Render()
					}
					([ConsoleKey]::LeftArrow) {
						$this.Section = switch ($this.Section) { "tests" { "compiler" } "opts" { "tests" } "build" { "opts" } "compiler" { "build" } }
						$this.ClampIdx(); $this.Render()
					}
					([ConsoleKey]::Spacebar) {
						switch ($this.Section) {
							"tests"    { $this.SelectTests() }  # open full-screen test selector
							"opts"     { $this.Opts[$this.Idx].On  = -not $this.Opts[$this.Idx].On  }
							"build"    {
								$this.Builds | ForEach-Object { $_.On = $false }
								$this.Builds[$this.Idx].On = $true
							}
							"compiler" {
								$vF = $this.Fields[$this.Idx]
								$vF.Value = $this.EditField($vF.Label, $vF.Value, ($vF.Key -eq "CcArgs"))
							}
						}
						$this.Render()
					}
					([ConsoleKey]::Enter) {
						return $this.BuildResult()
					}
					([ConsoleKey]::Escape) { return $null }
				}
				switch ([char]::ToUpper($vKey.KeyChar)) {
					'A' { $this.Tests | ForEach-Object { $_.On = $true  }; $this.Render() }
					'N' { $this.Tests | ForEach-Object { $_.On = $false }; $this.Render() }
					'Q' { return $null }
				}
			}
		} finally {
			[Console]::Write("$([char]27)[?1049l")   # exit alternate screen buffer
			[Console]::CursorVisible = $true
		}
		return $null
	}

	# Collects current checkbox/radio/field state into a result object.
	[object] BuildResult() {
		$vSelected = @($this.Tests | Where-Object { $_.On } | ForEach-Object { $_.Name })
		if ($vSelected.Count -eq 0) { return $null }
		$vDispAssert = ($this.Opts | Where-Object { $_.Key -eq "DisposedAssert" }).On   # disposed ASSERT checked
		$vDispLog    = ($this.Opts | Where-Object { $_.Key -eq "DisposedLog"   }).On   # disposed LOG checked
		$vDDAssert   = ($this.Opts | Where-Object { $_.Key -eq "DoubleDisposeAssert" }).On  # double-dispose ASSERT
		$vDDLog      = ($this.Opts | Where-Object { $_.Key -eq "DoubleDisposeLog"   }).On  # double-dispose LOG
		return [PSCustomObject]@{
			Tests         = $vSelected
			SkipUnit      = ($this.Opts   | Where-Object { $_.Key -eq "SkipUnit"      }).On
			MemTrack      = ($this.Opts   | Where-Object { $_.Key -eq "MemTrack"      }).On
			Ast           = ($this.Opts   | Where-Object { $_.Key -eq "Ast"           }).On
			DumpSemantics = ($this.Opts   | Where-Object { $_.Key -eq "DumpSemantics" }).On
			Disposed      = if ($vDispAssert) { "ASSERT" } elseif ($vDispLog) { "LOG" } else { "NO" }
			DoubleDispose = if ($vDDAssert)   { "ASSERT" } elseif ($vDDLog)   { "LOG" } else { "NO" }
			Build         = ($this.Builds | Where-Object { $_.On                      }).Value
			Compiler      = ($this.Fields | Where-Object { $_.Key -eq "Compiler"      }).Value
			CcArgs        = ($this.Fields | Where-Object { $_.Key -eq "CcArgs"        }).Value
		}
	}
}

# ==================
# MARK: Entry point
# ==================

$vCC    = Find-CCompiler $Compiler
$vCmake = Find-CMake
if (-not $vCC) {
	Write-Host "ERROR: No C compiler found (tried gcc, clang, cl). Install one and add it to PATH." -ForegroundColor Red
	exit 1
}

# ── Interactive mode ─────────────────────────────────────────────
if ($Interactive) {
	$vNames = @(Get-ChildItem $vTestsDir -Directory | Sort-Object Name | ForEach-Object Name)
	$vTui   = [TuiRunner]::new($vNames, $vCC)
	$vSel   = $vTui.Loop()
	Write-Host ""
	if (-not $vSel -or $vSel.Tests.Count -eq 0) {
		Write-Host " No tests selected." -ForegroundColor DarkGray; exit 0
	}

	$script:vBuildMode = $vSel.Build
	$vCC    = $vSel.Compiler
	$CCArgs = $vSel.CcArgs
	$vArgsStr = (@(
		if ($vSel.MemTrack)                    { "--mem-track" }
		if ($vSel.Ast)                         { "--ast" }
		if ($vSel.DumpSemantics)               { "--dump-semantics" }
		if ($vSel.Disposed      -ne "NO") { "--disposed=$($vSel.Disposed)" }
		if ($vSel.DoubleDispose -ne "NO") { "--double-dispose=$($vSel.DoubleDispose)" }
	) -join " ")

	Write-Info "Using C compiler: $vCC"
	Invoke-Build

	if ($vSel.Tests.Count -eq 1) {
		$vName = $vSel.Tests[0]
		$vSrc  = "$vTestsDir\$vName"
		if (-not (Test-Path $vSrc -PathType Container)) {
			Write-Host "ERROR: test directory not found: $vSrc" -ForegroundColor Red; exit 1
		}
		$vRes = Invoke-Test -inName $vName -inSrcDir $vSrc -inOutDir "$vSrc\out" -inExtraArgs $vArgsStr
		exit $(if ($vRes -eq "fail") { 1 } else { 0 })
	}

	$vResult = Run-Suite -inTestNames $vSel.Tests -inExtraArgs $vArgsStr -inSkipUnit $vSel.SkipUnit
	Show-Summary $vResult
}

# ── Single / multi-test run mode ─────────────────────────────────
if ($Run -ne "") {
	Write-Info "Using C compiler: $vCC"
	Invoke-Build

	$vArgsStr = (@(
		if ($MemTrack)                    { "--mem-track" }
		if ($Ast)                         { "--ast" }
		if ($DumpSemantics)               { "--dump-semantics" }
		if ($Disposed      -ne "NO") { "--disposed=$($Disposed.ToUpper())" }
		if ($DoubleDispose -ne "NO") { "--double-dispose=$($DoubleDispose.ToUpper())" }
		if ($TranspilerArgs -ne "")       { $TranspilerArgs }
	) -join " ")

	$vRunNames  = $Run -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne "" }
	$vAnyFailed = $false
	foreach ($vName in $vRunNames) {
		$vSrc = "$vTestsDir\$vName"
		if (-not (Test-Path $vSrc -PathType Container)) {
			Write-Host "ERROR: test directory not found: $vSrc" -ForegroundColor Red
			Write-Host "Available tests:" -ForegroundColor Yellow
			Get-ChildItem $vTestsDir -Directory | ForEach-Object { Write-Host "  - $($_.Name)" }
			$vAnyFailed = $true; continue
		}
		if ((Invoke-Test -inName $vName -inSrcDir $vSrc -inOutDir "$vSrc\out" -inExtraArgs $vArgsStr -inGuiMode $Gui) -eq "fail") {
			$vAnyFailed = $true
		}
	}
	exit ([int]$vAnyFailed)
}

# ── Full suite mode ───────────────────────────────────────────────
Write-Info "Using C compiler: $vCC"

$vArgsStr  = (@(
	if ($MemTrack)                    { "--mem-track" }
	if ($Ast)                         { "--ast" }
	if ($DumpSemantics)               { "--dump-semantics" }
	if ($Disposed      -ne "NO") { "--disposed=$($Disposed.ToUpper())" }
	if ($DoubleDispose -ne "NO") { "--double-dispose=$($DoubleDispose.ToUpper())" }
	if ($TranspilerArgs -ne "")       { $TranspilerArgs }
) -join " ")
$vAllNames = @(Get-ChildItem $vTestsDir -Directory | Sort-Object Name | ForEach-Object Name)

Invoke-Build
$vResult = Run-Suite -inTestNames $vAllNames -inExtraArgs $vArgsStr -inSkipUnit ($Skip -eq "unit")
Show-Summary $vResult
