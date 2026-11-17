# Removes everything a build makes, to check that the repository builds from nothing: the build
# directories, Gradle's and Kotlin's caches in the checkout, the examples' builds, and ktc2's cache of
# fetched sources (~/.ktc2/cache). Keeps the IDE's settings and local.properties.
#   powershell -File tools/clean.ps1
$ErrorActionPreference = 'Continue'
$vRoot = Split-Path -Parent $PSScriptRoot
Set-Location $vRoot

Write-Host '== stopping the compiler process and Gradle'
$vTool = Join-Path $vRoot 'build\install\ktc2\bin\ktc2.bat'
if (Test-Path $vTool) { & $vTool --stop-daemon 2>$null | Out-Null }
& .\gradlew.bat --stop 2>$null | Out-Null

# The suite's directories have paths longer than Windows' 260, which rd cannot always remove: robocopy
# mirrors an empty directory over them first (it handles long paths), then rd removes what is left.
$vEmpty = Join-Path ([System.IO.Path]::GetTempPath()) 'ktc2-empty'
New-Item -ItemType Directory -Force $vEmpty | Out-Null
function Remove-Tree($inPath) {
	robocopy $vEmpty $inPath /MIR /NFL /NDL /NJH /NJS /NC /NS /NP /R:1 /W:1 | Out-Null
	cmd /c rd /s /q "\\?\$inPath"
}
$vDirectories = @('build', 'compiler\build', 'compat\build', 'ide\build', '.gradle', '.kotlin') +
	(Get-ChildItem examples -Directory | ForEach-Object { "examples\$($_.Name)\build" }) +
	(Get-ChildItem compat -Directory -ErrorAction SilentlyContinue | ForEach-Object { "compat\$($_.Name)\build" })
foreach ($vName in $vDirectories) {
	$vPath = Join-Path $vRoot $vName
	if (Test-Path $vPath) {
		Write-Host "== removing $vName"
		Remove-Tree $vPath
	}
}
$vCache = Join-Path $HOME '.ktc2\cache'
if (Test-Path $vCache) {
	Write-Host '== removing ~/.ktc2/cache'
	Remove-Tree $vCache
}
# Directories that moves left empty: git does not keep them.
Get-ChildItem tests -Recurse -Directory | Sort-Object { $_.FullName.Length } -Descending |
	Where-Object { -not (Get-ChildItem $_.FullName -Force) } | ForEach-Object { Write-Host "== removing empty $($_.FullName)"; Remove-Item $_.FullName }
Write-Host '== left, not tracked by git:'
git status --short --ignored
