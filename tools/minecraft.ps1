# Starting Minecraft for Portalcraft.cmd, besides the Minecraft Launcher:
#  - Prism Launcher: a "Portalcraft" instance (Minecraft 26.3 + Fabric Loader) with the mods install.cmd put in
#    %APPDATA%\.minecraft\portalcraft\mods, started with the account chosen in Prism;
#  - offline: Minecraft started directly, without an account, from the Minecraft 26.3 a launcher (the Minecraft
#    Launcher or Prism) already put on this PC. The game itself is never downloaded here; only open libraries a
#    launcher hasn't fetched yet (Fabric's, LWJGL...) and the version's description.
# Saved UTF-8 with a byte-order mark: Windows PowerShell reads scripts without one as ANSI (the messages are Russian).

$PcMcVersion = '26.3'
$PcLoader = '0.19.5'
$PcFabricId = "fabric-loader-$PcLoader-$PcMcVersion" # install.ps1's Fabric version
$PcMcRoot = Join-Path $env:APPDATA '.minecraft'
$PcGameDir = Join-Path $PcMcRoot 'portalcraft' # the game folder: mods, world, settings (install.ps1)

function Read-PcJson([string]$path) { Get-Content $path -Raw -Encoding UTF8 | ConvertFrom-Json }

function Write-PcUtf8([string]$path, [string]$text) {
	[System.IO.File]::WriteAllText($path, $text, (New-Object System.Text.UTF8Encoding $false))
}

# ---- what Portalcraft.cmd asked last time (the defaults next time)
function Read-LaunchSettings {
	$s = @{ mode = '1'; launcher = '1'; name = 'Player' }
	$file = Join-Path $PcGameDir 'portalcraft_launch.txt'
	if (Test-Path $file) {
		foreach ($line in Get-Content $file -Encoding UTF8) { if ($line -match '^(\w+)=(.*)$') { $s[$Matches[1]] = $Matches[2] } }
	}
	return $s
}

function Save-LaunchSettings($s) {
	New-Item -ItemType Directory -Force $PcGameDir | Out-Null
	Write-PcUtf8 (Join-Path $PcGameDir 'portalcraft_launch.txt') ((($s.Keys | Sort-Object | ForEach-Object { "$_=$($s[$_])" }) -join "`r`n") + "`r`n")
}

# ---- Prism Launcher
function Find-Prism {
	foreach ($exe in @("$env:LOCALAPPDATA\Programs\PrismLauncher\prismlauncher.exe", "$env:ProgramFiles\PrismLauncher\prismlauncher.exe")) {
		if (-not (Test-Path $exe)) { continue }
		# a portable copy keeps its data next to it
		$data = if (Test-Path (Join-Path (Split-Path $exe) 'portable.txt')) { Split-Path $exe } else { Join-Path $env:APPDATA 'PrismLauncher' }
		$dir = 'instances'
		$cfg = Join-Path $data 'prismlauncher.cfg'
		if (Test-Path $cfg) {
			$m = Select-String -Path $cfg -Pattern '^InstanceDir=(.+)$' | Select-Object -First 1
			if ($m) { $dir = $m.Matches[0].Groups[1].Value.Trim() }
		}
		$instances = if ([System.IO.Path]::IsPathRooted($dir)) { $dir } else { Join-Path $data $dir }
		return [pscustomobject]@{ Exe = $exe; Data = $data; Instances = $instances }
	}
	return $null
}

# The "Portalcraft" instance, made the first time; its mods follow install.cmd's every time. Its world is its own
# (Prism keeps every instance's game folder inside the instance).
function Sync-PrismInstance($prism) {
	$name = 'Portalcraft'
	$inst = Join-Path $prism.Instances $name
	$game = Join-Path $inst 'minecraft'
	New-Item -ItemType Directory -Force (Join-Path $game 'mods') | Out-Null
	$pack = Join-Path $inst 'mmc-pack.json'
	$current = $null
	if (Test-Path $pack) { try { $current = Read-PcJson $pack } catch {} }
	$versions = @($current.components | ForEach-Object { "$($_.uid)=$($_.version)" })
	if ($versions -notcontains "net.minecraft=$PcMcVersion" -or $versions -notcontains "net.fabricmc.fabric-loader=$PcLoader") {
		# Prism adds what these need (LWJGL, mappings) when it starts the instance
		Write-PcUtf8 $pack (@{ formatVersion = 1; components = @(
			[ordered]@{ uid = 'net.minecraft'; version = $PcMcVersion; important = $true },
			[ordered]@{ uid = 'net.fabricmc.fabric-loader'; version = $PcLoader }) } | ConvertTo-Json -Depth 5)
	}
	$cfg = Join-Path $inst 'instance.cfg'
	if (-not (Test-Path $cfg)) {
		Write-PcUtf8 $cfg ("[General]`r`nConfigVersion=1.3`r`nInstanceType=OneSix`r`nname=$name`r`niconKey=default`r`n" +
			"OverrideMemory=true`r`nMinMemAlloc=512`r`nMaxMemAlloc=4096`r`nOverrideJavaArgs=true`r`nJvmArgs=--enable-native-access=ALL-UNNAMED`r`n")
	}
	$src = Join-Path $PcGameDir 'mods'
	$jars = @(Get-ChildItem $src -Filter '*.jar' -ErrorAction SilentlyContinue)
	if (-not ($jars | Where-Object { $_.Name -like 'portalcraft-*' })) { throw "В $src нет мода Portalcraft: сначала запусти install.cmd." }
	$dst = Join-Path $game 'mods'
	# an older version's jar goes (Fabric refuses two copies of one mod)
	Get-ChildItem $dst -Filter '*.jar' | Where-Object { ($_.Name -like 'portalcraft-*' -or $_.Name -like 'fabric-api-*') -and $jars.Name -notcontains $_.Name } |
		Remove-Item -Force
	foreach ($j in $jars) { Copy-Item $j.FullName $dst -Force }
	return $name
}

# ---- offline
# Where launchers keep Minecraft's files: the Minecraft Launcher's folder, then Prism's
function Get-McRoots {
	$roots = @($PcMcRoot)
	$prism = Find-Prism
	if ($prism) { $roots += $prism.Data } elseif (Test-Path "$env:APPDATA\PrismLauncher") { $roots += "$env:APPDATA\PrismLauncher" }
	return $roots
}

# Mojang's rules (os, features): this is 64-bit Windows, and no feature (demo, custom size, quick play) is on
function Test-McRules($rules) {
	if (-not $rules) { return $true }
	$allowed = $false
	foreach ($r in $rules) {
		$match = $true
		if ($r.os) {
			if ($r.os.name -and $r.os.name -ne 'windows') { $match = $false }
			if ($r.os.arch -and -not ($r.os.arch -eq 'x86' -and -not [Environment]::Is64BitOperatingSystem)) { $match = $false }
		}
		if ($r.features) { $match = $false }
		if ($match) { $allowed = $r.action -eq 'allow' }
	}
	return $allowed
}

function Get-McLibraryPath($lib) {
	if ($lib.downloads -and $lib.downloads.artifact -and $lib.downloads.artifact.path) { return $lib.downloads.artifact.path }
	$p = $lib.name -split ':'
	$file = "$($p[1])-$($p[2])" + $(if ($p.Count -gt 3) { "-$($p[3])" } else { '' }) + '.jar'
	return (($p[0] -replace '\.', '/'), $p[1], $p[2], $file) -join '/'
}

function Get-PcFile([string]$url, [string]$path, [string]$sha1) {
	New-Item -ItemType Directory -Force (Split-Path $path) | Out-Null
	$tmp = "$path.download"
	Invoke-WebRequest $url -OutFile $tmp -UseBasicParsing
	if ($sha1 -and (Get-FileHash $tmp -Algorithm SHA1).Hash -ne $sha1) { Remove-Item $tmp; throw "Скачанный файл не совпал по контрольной сумме: $url" }
	Move-Item $tmp $path -Force
}

# Java's major version from its release file (JAVA_VERSION="25.0.1", "1.8.0_402")
function Get-JavaMajor([string]$javaw) {
	$release = Join-Path (Split-Path (Split-Path $javaw)) 'release'
	if (-not (Test-Path $release)) { return 0 }
	$m = Select-String -Path $release -Pattern '^JAVA_VERSION="(\d+)(?:\.(\d+))?' | Select-Object -First 1
	if (-not $m) { return 0 }
	$major = [int]$m.Matches[0].Groups[1].Value
	if ($major -eq 1) { $major = [int]$m.Matches[0].Groups[2].Value }
	return $major
}

function Find-McJava([string]$component, [int]$major, [string[]]$roots) {
	# the Java a launcher keeps for this Minecraft version
	$own = @("$PcMcRoot\runtime\windows-x64\$component\bin\javaw.exe", "$PcMcRoot\runtime\$component\windows-x64\$component\bin\javaw.exe",
		"$env:LOCALAPPDATA\Packages\Microsoft.4297127D64EC6_8wekyb3d8bbwe\LocalCache\Local\runtime\$component\windows-x64\$component\bin\javaw.exe")
	foreach ($r in $roots) { $own += Join-Path $r "java\$component\bin\javaw.exe" }
	foreach ($j in $own) { if (Test-Path $j) { return $j } }
	# or any installed Java new enough
	$any = @()
	if ($env:JAVA_HOME) { $any += Join-Path $env:JAVA_HOME 'bin\javaw.exe' }
	$cmd = Get-Command javaw -ErrorAction SilentlyContinue
	if ($cmd) { $any += $cmd.Source }
	foreach ($vendor in 'Java', 'Eclipse Adoptium', 'Microsoft', 'Zulu', 'BellSoft', 'Amazon Corretto') {
		$any += @(Get-ChildItem (Join-Path $env:ProgramFiles $vendor) -Directory -ErrorAction SilentlyContinue | ForEach-Object { Join-Path $_.FullName 'bin\javaw.exe' })
	}
	foreach ($j in $any) { if ((Test-Path $j) -and (Get-JavaMajor $j) -ge $major) { return $j } }
	return $null
}

# Everything needed to start Minecraft offline as $Name: returns the Java and its argument file. Throws a message
# for the player when something is missing.
function Get-OfflineLaunch([string]$Name, [string]$GameDir = $PcGameDir, [string[]]$Roots = (Get-McRoots)) {
	$fabricJson = Join-Path $PcMcRoot "versions\$PcFabricId\$PcFabricId.json"
	if (-not (Test-Path $fabricJson)) { throw 'Нет версии Fabric для Portalcraft: сначала запусти install.cmd.' }
	if (-not (Get-ChildItem (Join-Path $GameDir 'mods') -Filter 'portalcraft-*.jar' -ErrorAction SilentlyContinue)) { throw 'Мод Portalcraft не установлен: сначала запусти install.cmd.' }
	$fabric = Read-PcJson $fabricJson
	$ver = $fabric.inheritsFrom

	# the version's description (the Minecraft Launcher keeps it; it is Mojang's public metadata, not the game)
	$vanillaJson = Join-Path $PcMcRoot "versions\$ver\$ver.json"
	if (-not (Test-Path $vanillaJson)) {
		Write-Host "Скачиваю описание версии $ver..."
		$manifest = Invoke-RestMethod 'https://piston-meta.mojang.com/mc/game/version_manifest_v2.json'
		$entry = $manifest.versions | Where-Object { $_.id -eq $ver } | Select-Object -First 1
		if (-not $entry) { throw "Версия $ver не найдена у Mojang." }
		Get-PcFile $entry.url $vanillaJson $entry.sha1
	}
	$vanilla = Read-PcJson $vanillaJson

	# the game: a copy some launcher already downloaded
	$client = $null
	$candidates = @("$PcMcRoot\versions\$ver\$ver.jar", "$PcMcRoot\versions\$PcFabricId\$PcFabricId.jar")
	foreach ($r in $Roots) { $candidates += Join-Path $r "libraries\com\mojang\minecraft\$ver\minecraft-$ver-client.jar" }
	foreach ($c in $candidates) {
		if ((Test-Path $c) -and (Get-Item $c).Length -eq $vanilla.downloads.client.size -and
			(Get-FileHash $c -Algorithm SHA1).Hash -eq $vanilla.downloads.client.sha1) { $client = $c; break }
	}
	$assetsDir = $Roots | Where-Object { Test-Path (Join-Path $_ "assets\indexes\$($vanilla.assetIndex.id).json") } | Select-Object -First 1
	if (-not $client -or -not $assetsDir) {
		throw "На этом компьютере нет Minecraft $ver. Запусти версию $ver один раз в своём лаунчере (лаунчер Minecraft, Prism), " +
			'чтобы он её скачал, и попробуй снова. Portalcraft не скачивает саму игру.'
	}
	$assetsDir = Join-Path $assetsDir 'assets'

	$java = Find-McJava $vanilla.javaVersion.component ([int]$vanilla.javaVersion.majorVersion) $Roots
	if (-not $java) {
		throw "Не нашёл Java $($vanilla.javaVersion.majorVersion): она есть у лаунчера Minecraft и у Prism после запуска версии $ver, " +
			"или поставь Java $($vanilla.javaVersion.majorVersion) (например, Eclipse Temurin с adoptium.net)."
	}

	# libraries: Fabric's first (they win over the same library from Minecraft), then Minecraft's
	$classpath = @()
	$seen = @{}
	foreach ($lib in @($fabric.libraries) + @($vanilla.libraries)) {
		if (-not (Test-McRules $lib.rules)) { continue }
		$parts = $lib.name -split ':'
		$key = $parts[0] + ':' + $parts[1] + $(if ($parts.Count -gt 3) { ':' + $parts[3] } else { '' })
		if ($seen.ContainsKey($key)) { continue }
		$seen[$key] = $true
		$rel = Get-McLibraryPath $lib
		$file = $null
		foreach ($r in $Roots) {
			$f = Join-Path $r ('libraries\' + ($rel -replace '/', '\'))
			if (Test-Path $f) { $file = $f; break }
		}
		if (-not $file) {
			$file = Join-Path $PcMcRoot ('libraries\' + ($rel -replace '/', '\'))
			$url = if ($lib.downloads -and $lib.downloads.artifact) { $lib.downloads.artifact.url } else { $lib.url.TrimEnd('/') + '/' + $rel }
			$sha1 = if ($lib.downloads -and $lib.downloads.artifact) { $lib.downloads.artifact.sha1 } else { $lib.sha1 }
			Write-Host "Скачиваю библиотеку $($lib.name)..."
			try { Get-PcFile $url $file $sha1 }
			catch { throw "Не удалось скачать $($lib.name) ($url): $($_.Exception.Message)" }
		}
		$classpath += $file
	}
	$classpath += $client

	$natives = Join-Path $GameDir 'natives'
	foreach ($d in 'java', 'jna', 'lwjgl', 'netty') { New-Item -ItemType Directory -Force (Join-Path $natives $d) | Out-Null }
	$values = @{
		auth_player_name = $Name; version_name = $PcFabricId; game_directory = $GameDir; assets_root = $assetsDir
		assets_index_name = $vanilla.assetIndex.id; auth_uuid = ''; auth_access_token = '0'; clientid = ''; auth_xuid = ''
		user_type = 'legacy'; version_type = 'release'; natives_directory = $natives; launcher_name = 'portalcraft'
		launcher_version = '1'; classpath = ($classpath -join ';'); classpath_separator = ';'; library_directory = (Join-Path $PcMcRoot 'libraries')
	}
	function Expand-McArgs($list) {
		$out = @()
		foreach ($a in $list) {
			if ($a -is [string]) { $vals = @($a) }
			elseif (Test-McRules $a.rules) { $vals = @($a.value) }
			else { continue }
			foreach ($v in $vals) { $out += [regex]::Replace($v, '\$\{(\w+)\}', { param($m) if ($values.ContainsKey($m.Groups[1].Value)) { $values[$m.Groups[1].Value] } else { $m.Value } }) }
		}
		return $out
	}
	$jvm = @('-Xmx4G') + (Expand-McArgs $vanilla.arguments.jvm) + (Expand-McArgs $fabric.arguments.jvm)
	$game = @()
	$ga = @(Expand-McArgs $vanilla.arguments.game) + @(Expand-McArgs $fabric.arguments.game)
	for ($i = 0; $i -lt $ga.Count; $i++) {
		# an option with an empty value (no uuid: Minecraft makes the offline one from the name; no Xbox ids) goes
		if ($ga[$i] -like '--*' -and $i + 1 -lt $ga.Count -and $ga[$i + 1] -eq '') { $i++; continue }
		$game += $ga[$i]
	}
	# a Java argument file: the command line stays short. Quoted, with forward slashes (a backslash escapes in it),
	# in the system's code page, which is how Java reads it
	$argFile = Join-Path $GameDir 'portalcraft_offline.args'
	$lines = @($jvm + @($fabric.mainClass) + $game | ForEach-Object { '"' + ($_ -replace '\\', '/') + '"' })
	[System.IO.File]::WriteAllLines($argFile, [string[]]$lines, [System.Text.Encoding]::Default)
	return [pscustomobject]@{ Java = $java; ArgFile = $argFile; GameDir = $GameDir }
}

function Start-OfflineMinecraft($launch) {
	Start-Process -FilePath $launch.Java -ArgumentList "@`"$($launch.ArgFile)`"" -WorkingDirectory $launch.GameDir -PassThru
}
