# Portalcraft installer: real Minecraft inside Portal 2.
#  - Portal 2: puts the ReShade runtime folder (add-on, effect, config) and two VScript helpers in the game folder.
#    ReShade's d3d9.dll itself is only placed while you play (play.ps1 puts it in and takes it out).
#  - Minecraft: adds a "Portalcraft" installation (Fabric 0.19.5 for 26.3) to your own Minecraft Launcher,
#    with its own game folder holding this mod and Fabric API. You start it signed in, as usual. Portalcraft.cmd can
#    also start Minecraft through Prism Launcher (an instance with these mods) or offline (tools\minecraft.ps1).
param([switch]$Uninstall)
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path

$MC_VERSION = '26.3'
$LOADER = '0.19.5'
$FABRIC_API = '0.161.0+26.3'
$PROFILE_ID = "fabric-loader-$LOADER-$MC_VERSION"
$mcRoot = Join-Path $env:APPDATA '.minecraft'
$gameDir = Join-Path $mcRoot 'portalcraft'   # inside .minecraft: a new top-level AppData folder can end up in an app's private copy

. (Join-Path $here 'tools/find-portal2.ps1')

# copy unless the destination is already identical (Portal 2 keeps the add-on open while it runs)
function Copy-IfChanged([string]$src, [string]$dst) {
	if ((Test-Path $dst -PathType Leaf) -and (Get-FileHash $src).Hash -eq (Get-FileHash $dst).Hash) { return }
	try { Copy-Item $src $dst -Force }
	catch { throw "Can't update $dst - close Portal 2 and run the installer again." }
}

function Copy-Tree([string]$srcDir, [string]$dstDir) {
	$root = (Resolve-Path $srcDir).Path
	Get-ChildItem $root -Recurse -File | ForEach-Object {
		$to = Join-Path $dstDir $_.FullName.Substring($root.Length + 1)
		New-Item -ItemType Directory -Force (Split-Path $to) | Out-Null
		Copy-IfChanged $_.FullName $to
	}
}

# the launcher and Minecraft read these files as JSON: no byte-order mark
function Write-Utf8NoBom([string]$path, [string]$text) {
	[System.IO.File]::WriteAllText($path, $text, (New-Object System.Text.UTF8Encoding $false))
}

function Edit-LauncherProfiles([scriptblock]$change) {
	$file = Join-Path $mcRoot 'launcher_profiles.json'
	if (Get-Process -Name 'Minecraft', 'MinecraftLauncher' -ErrorAction SilentlyContinue) {
		throw 'Close the Minecraft Launcher first (it rewrites launcher_profiles.json when it exits).'
	}
	$backup = "$file.portalcraft-backup"
	if (-not (Test-Path $backup)) { Copy-Item $file $backup }
	$json = Get-Content $file -Raw -Encoding UTF8 | ConvertFrom-Json
	& $change $json
	Write-Utf8NoBom $file ($json | ConvertTo-Json -Depth 32)
}

$p2 = Find-Portal2
$rt = Join-Path $p2 'portalcraft_runtime'

if ($Uninstall) {
	foreach ($f in @('bin\d3d9.dll', 'ReShade.ini')) {
		$path = Join-Path $p2 $f
		if (Test-Path $path) { Remove-Item $path -Force }
	}
	if (Test-Path $rt) { Remove-Item $rt -Recurse -Force }
	$scripts = Join-Path $p2 'portal2\scripts\vscripts\portalcraft'
	if (Test-Path $scripts) { Remove-Item $scripts -Recurse -Force }
	if (Test-Path (Join-Path $mcRoot 'launcher_profiles.json')) { Edit-LauncherProfiles { param($j) $j.profiles.PSObject.Properties.Remove('portalcraft') } }
	Write-Host "Portalcraft removed (your Minecraft world folder $gameDir is kept)."
	$prism = Join-Path $env:APPDATA 'PrismLauncher\instances\Portalcraft'
	if (Test-Path $prism) { Write-Host "Prism Launcher's Portalcraft instance is kept too, with its world (delete it in Prism if you like)." }
	return
}

# ---- Portal 2 side
New-Item -ItemType Directory -Force $rt | Out-Null
Copy-IfChanged (Join-Path $here 'addon\out\Portalcraft.addon32') (Join-Path $rt 'Portalcraft.addon32')
Copy-Tree (Join-Path $here 'runtime') $rt
Copy-IfChanged (Join-Path $here 'third_party\reshade_payload\ReShade32.dll') (Join-Path $rt 'ReShade32.dll')
Copy-Tree (Join-Path $here 'p2') (Join-Path $p2 'portal2')
Write-Host "Portal 2: $rt"

# ---- Minecraft side: Fabric version JSON (with the loader's own checksum, which Fabric's meta leaves out)
$verDir = Join-Path $mcRoot "versions\$PROFILE_ID"
New-Item -ItemType Directory -Force $verDir | Out-Null
$versionJson = Join-Path $verDir "$PROFILE_ID.json"
if (-not (Test-Path $versionJson)) { # already there from an earlier install: nothing to download
$profileJson = Invoke-RestMethod "https://meta.fabricmc.net/v2/versions/loader/$MC_VERSION/$LOADER/profile/json"
foreach ($lib in $profileJson.libraries) {
	if ($lib.name -like 'net.fabricmc:fabric-loader:*' -and -not $lib.sha1) {
		$url = "$($lib.url)net/fabricmc/fabric-loader/$LOADER/fabric-loader-$LOADER.jar"
		$tmp = Join-Path $env:TEMP "fabric-loader-$LOADER.jar"
		Invoke-WebRequest $url -OutFile $tmp -UseBasicParsing
		$expected = (Invoke-RestMethod "$url.sha1").Trim()
		$actual = (Get-FileHash $tmp -Algorithm SHA1).Hash.ToLower()
		if ($actual -ne $expected) { throw "fabric-loader download checksum mismatch" }
		$lib | Add-Member -NotePropertyName sha1 -NotePropertyValue $actual -Force
		$lib | Add-Member -NotePropertyName size -NotePropertyValue (Get-Item $tmp).Length -Force
	}
}
Write-Utf8NoBom $versionJson ($profileJson | ConvertTo-Json -Depth 32)
}
if (-not (Test-Path (Join-Path $verDir "$PROFILE_ID.jar"))) { New-Item -ItemType File (Join-Path $verDir "$PROFILE_ID.jar") | Out-Null }

# mods: this mod + Fabric API (checksum from Fabric's maven)
$mods = Join-Path $gameDir 'mods'
New-Item -ItemType Directory -Force $mods | Out-Null
$modJar = Get-ChildItem (Join-Path $here 'mc\build\libs') -Filter 'portalcraft-*.jar' | Where-Object { $_.Name -notlike '*-sources.jar' } |
	Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $modJar) { throw 'mc\build\libs has no portalcraft jar.' }
# an update: the older version's jar goes (Fabric refuses two copies of one mod)
Get-ChildItem $mods -Filter 'portalcraft-*.jar' | Where-Object { $_.Name -ne $modJar.Name } | Remove-Item -Force
Copy-Item $modJar.FullName $mods -Force
$apiJar = Join-Path $mods "fabric-api-$FABRIC_API.jar"
if (-not (Test-Path $apiJar)) {
	$url = "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/$FABRIC_API/fabric-api-$FABRIC_API.jar"
	Invoke-WebRequest $url -OutFile $apiJar -UseBasicParsing
	$expected = (Invoke-RestMethod "$url.sha1").Trim()
	if ((Get-FileHash $apiJar -Algorithm SHA1).Hash.ToLower() -ne $expected) { Remove-Item $apiJar; throw 'Fabric API checksum mismatch' }
}

# launcher installation (left alone when it is already set up, so the launcher may stay open)
$current = $null
try { $current = (Get-Content (Join-Path $mcRoot 'launcher_profiles.json') -Raw -Encoding UTF8 | ConvertFrom-Json).profiles.portalcraft } catch {}
if (-not (Test-Path (Join-Path $mcRoot 'launcher_profiles.json'))) {
	Write-Host 'Minecraft: no Minecraft Launcher here; Portalcraft.cmd starts Minecraft through Prism Launcher or offline'
} elseif ($current -and $current.lastVersionId -eq $PROFILE_ID -and $current.gameDir -eq $gameDir -and $current.javaArgs -like '*--enable-native-access=ALL-UNNAMED*') {
	Write-Host "Minecraft: launcher installation 'Portalcraft' is already set up"
} else {
Edit-LauncherProfiles {
	param($j)
	$entry = [ordered]@{
		name = 'Portalcraft'
		type = 'custom'
		icon = 'Grass'
		lastVersionId = $PROFILE_ID
		gameDir = $gameDir
		javaArgs = '-Xmx4G --enable-native-access=ALL-UNNAMED'
		lastUsed = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
		created = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
	}
	$j.profiles | Add-Member -NotePropertyName 'portalcraft' -NotePropertyValue ([pscustomobject]$entry) -Force
}
Write-Host "Minecraft: launcher installation 'Portalcraft' ($PROFILE_ID), game folder $gameDir"
}
Write-Host 'Done. Start with Portalcraft.cmd.'
