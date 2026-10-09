# Builds the release zip: what install.cmd needs (built add-on, mod jar, ReShade, scripts, shader), the sources,
# and the docs. No game files, no dev world, no logs. Output: ..\..\release\Portalcraft-<version>.zip
param([string]$Version = '')
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
# the version the mod is built with
if (-not $Version) { $Version = ((Get-Content (Join-Path $root 'mc\gradle.properties')) -match '^version=' -replace '^version=', '').Trim() }
$out = Join-Path (Split-Path -Parent $root) 'release'
$name = "Portalcraft-$Version"
$stage = Join-Path $out $name
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
New-Item -ItemType Directory -Force $stage | Out-Null

function Add([string]$rel) {
	$src = Join-Path $root $rel
	if (-not (Test-Path $src)) { throw "missing $rel (build first)" }
	$dst = Join-Path $stage $rel
	New-Item -ItemType Directory -Force (Split-Path $dst) | Out-Null
	Copy-Item $src $dst -Recurse -Force
}

# to play
foreach ($f in 'Portalcraft.cmd', 'install.cmd', 'install.ps1', 'uninstall.cmd', 'play.cmd', 'play.ps1',
	'README.md', 'README.ru.md', 'CHANGELOG.md', 'PROTOCOL.md', 'LICENSE', 'tools\find-portal2.ps1', 'tools\minecraft.ps1',
	'addon\out\Portalcraft.addon32', "mc\build\libs\portalcraft-$Version.jar", 'runtime',
	'p2\scripts\vscripts\portalcraft\solids.nut', 'p2\scripts\vscripts\portalcraft\view.nut',
	'third_party\reshade_payload\ReShade32.dll', 'third_party\reshade-6.8.0\LICENSE.md') { Add $f }
# to build it yourself
foreach ($f in 'addon\src', 'addon\b.sh', 'mc\src', 'mc\build.gradle', 'mc\settings.gradle', 'mc\gradle.properties',
	'mc\gradlew', 'mc\gradlew.bat', 'mc\gradle', 'mc\LICENSE', 'mc\README.md') { Add $f }

$zip = Join-Path $out "$name.zip"
if (Test-Path $zip) { Remove-Item $zip -Force }
Compress-Archive -Path $stage -DestinationPath $zip
$size = [math]::Round((Get-Item $zip).Length / 1MB, 2)
Write-Host "$zip ($size MB)"
