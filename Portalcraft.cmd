<# : Portalcraft launcher (batch part)
@echo off
chcp 65001 >nul
set "PC_HOME=%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -Command "& ([scriptblock]::Create((Get-Content -LiteralPath '%~f0' -Raw -Encoding UTF8)))"
exit /b
#>
# Everything above is the batch part; to PowerShell it is one block comment.
# Needs a finished install (install.cmd). Asks how to start Minecraft (signed in through the Minecraft Launcher or
# Prism Launcher, offline, or from another launcher), puts ReShade into Portal 2 for this session only, starts
# Portal 2 and Minecraft, and takes ReShade out again when Portal 2 closes.
$ErrorActionPreference = 'Stop'
. (Join-Path $env:PC_HOME 'tools\minecraft.ps1')

function Done([string]$msg) { Write-Host ''; Write-Host $msg; Read-Host 'Нажми Enter, чтобы закрыть' | Out-Null; exit }

# one of $choices; Enter keeps the default
function Ask([string]$question, [string[]]$choices, [string]$default) {
	if ($choices -notcontains $default) { $default = $choices[0] }
	while ($true) {
		$a = (Read-Host "$question [$default]").Trim()
		if (-not $a) { return $default }
		if ($choices -contains $a) { return $a }
	}
}

# ---- find Portal 2
$steamExe = (Get-ItemProperty 'HKCU:\Software\Valve\Steam' -ErrorAction SilentlyContinue).SteamExe
$steamDir = if ($steamExe) { Split-Path $steamExe } else { 'C:\Program Files (x86)\Steam' }
$libs = @($steamDir)
$vdf = Join-Path $steamDir 'steamapps\libraryfolders.vdf'
if (Test-Path $vdf) {
	foreach ($m in [regex]::Matches((Get-Content $vdf -Raw), '"path"\s+"([^"]+)"')) { $libs += $m.Groups[1].Value -replace '\\\\', '\' }
}
$p2 = $libs | ForEach-Object { Join-Path $_ 'steamapps\common\Portal 2' } | Where-Object { Test-Path (Join-Path $_ 'portal2.exe') } | Select-Object -First 1
if (-not $p2) { Done 'Portal 2 не найден ни в одной библиотеке Steam.' }

$rt = Join-Path $p2 'portalcraft_runtime'
$reshade = Join-Path $rt 'ReShade32.dll'
if (-not (Test-Path $reshade) -or -not (Test-Path (Join-Path $rt 'Portalcraft.addon32'))) {
	Done 'Portalcraft не установлен: сначала запусти install.cmd из папки проекта.'
}
if (Get-Process portal2 -ErrorAction SilentlyContinue) { Done 'Portal 2 уже запущен: закрой его и запусти Portalcraft снова.' }

# ---- how to start Minecraft (last time's answers are the defaults)
$settings = Read-LaunchSettings
Write-Host 'Portalcraft: Minecraft внутри Portal 2'
Write-Host ''
Write-Host 'Как запустить Minecraft?'
Write-Host '  1 - со своим аккаунтом (лаунчер Minecraft или Prism Launcher)'
Write-Host '  2 - без сети: без входа в аккаунт, с любым ником'
Write-Host '  3 - сам запущу его из другого лаунчера'
$mode = Ask 'Выбери 1, 2 или 3' @('1', '2', '3') $settings.mode
$firstTime = $settings.mode -ne $mode
$settings.mode = $mode
$mods = Join-Path $PcGameDir 'mods'

if ($mode -eq '1') {
	$profiles = Join-Path $PcMcRoot 'launcher_profiles.json'
	$official = (Test-Path $profiles) -and (Select-String -Path $profiles -Pattern '"portalcraft"' -Quiet)
	$prism = Find-Prism
	if ($official -and $prism) {
		Write-Host ''
		Write-Host 'Через какой лаунчер?  1 - лаунчер Minecraft   2 - Prism Launcher'
		$launcher = Ask 'Выбери 1 или 2' @('1', '2') $settings.launcher
		$settings.launcher = $launcher
	} elseif ($official) { $launcher = '1' }
	elseif ($prism) { $launcher = '2' }
	else {
		Done ('Не нашёл ни лаунчера Minecraft с установкой Portalcraft (её добавляет install.cmd, когда лаунчер закрыт), ' +
			'ни Prism Launcher. Выбери 2, чтобы играть без сети, или 3, чтобы запустить Minecraft из своего лаунчера.')
	}
	if ($launcher -eq '2') {
		try { $instance = Sync-PrismInstance $prism } catch { Done $_.Exception.Message }
	}
} elseif ($mode -eq '2') {
	Write-Host ''
	while ($true) {
		$name = (Read-Host "Ник (3-16 латинских букв, цифр или _) [$($settings.name)]").Trim()
		if (-not $name) { $name = $settings.name }
		if ($name -match '^[A-Za-z0-9_]{3,16}$') { break }
	}
	$settings.name = $name
	try { $offline = Get-OfflineLaunch -Name $name } catch { Done $_.Exception.Message }
} else {
	Write-Host ''
	Write-Host 'Сборка в твоём лаунчере: Minecraft 26.3 + Fabric Loader 0.19.5, в её папку mods положи оба файла из'
	Write-Host "$mods. После обновления Portalcraft положи их туда снова."
	if ($firstTime) { Start-Process explorer.exe $mods }
}
Save-LaunchSettings $settings

# ---- ReShade in, only for this session
$dll = Join-Path $p2 'bin\d3d9.dll'
$ini = Join-Path $p2 'ReShade.ini'
if ((Test-Path $dll) -and ((Get-FileHash $dll).Hash -ne (Get-FileHash $reshade).Hash)) {
	Done "В $p2\bin уже лежит чужой d3d9.dll (другой мод?). Не трогаю его."
}
Copy-Item $reshade $dll -Force
[System.IO.File]::WriteAllText($ini, "[INSTALL]`r`nBasePath=$rt\`r`n", (New-Object System.Text.UTF8Encoding $false))

# ---- start Portal 2 (-insecure: no VAC while a modified renderer is loaded; single player only)
Write-Host ''
Write-Host 'Запускаю Portal 2...'
if ($steamExe -and (Test-Path $steamExe)) { Start-Process $steamExe -ArgumentList '-applaunch', '620', '-insecure', '-novid' }
else { Start-Process 'steam://run/620//-insecure -novid/' }

# ---- and Minecraft
if ($mode -eq '1' -and $launcher -eq '1') {
	# the Minecraft Launcher (Microsoft Store version, or the classic one)
	Write-Host 'Открываю лаунчер Minecraft: выбери установку "Portalcraft" и нажми "Играть".'
	Write-Host '(Лаунчер предупредит о модифицированной версии - нажми "Играть" ещё раз.)'
	$classic = @("${env:ProgramFiles(x86)}\Minecraft Launcher\MinecraftLauncher.exe", "$env:ProgramFiles\Minecraft Launcher\MinecraftLauncher.exe") | Where-Object { Test-Path $_ } | Select-Object -First 1
	try { Start-Process 'shell:AppsFolder\Microsoft.4297127D64EC6_8wekyb3d8bbwe!Minecraft' }
	catch { if ($classic) { Start-Process $classic } else { Write-Host 'Не нашёл лаунчер Minecraft - открой его сам.' } }
} elseif ($mode -eq '1') {
	Write-Host "Запускаю сборку `"$instance`" в Prism Launcher (с аккаунтом, выбранным в Prism)."
	Start-Process $prism.Exe -ArgumentList '--launch', $instance
} elseif ($mode -eq '2') {
	Write-Host "Запускаю Minecraft без сети, ник $name."
	Start-OfflineMinecraft $offline | Out-Null
} else {
	Write-Host 'Теперь запусти свою сборку Minecraft с модом.'
}

# ---- wait for Portal 2 to start and to close, then take ReShade out
$deadline = (Get-Date).AddMinutes(3)
while (-not (Get-Process portal2 -ErrorAction SilentlyContinue)) {
	if ((Get-Date) -gt $deadline) { break }
	Start-Sleep -Seconds 2
}
Write-Host ''
Write-Host 'Играй! Управление: B - блоки/портальная пушка, ЛКМ/ПКМ - сломать/поставить, E - инвентарь,'
Write-Host 'V - взять предмет Portal 2, F5 - вид от третьего лица, T - чат, / - команды,'
Write-Host 'стены и пол Portal 2 ломаются (и динамитом), пробел в воздухе - элитры. Это окно можно не трогать.'
while (Get-Process portal2 -ErrorAction SilentlyContinue) { Start-Sleep -Seconds 3 }
Remove-Item $dll, $ini -Force -ErrorAction SilentlyContinue
Write-Host 'Portal 2 закрыт: ReShade убран из папки игры, Minecraft сам сохранит мир и закроется.'
Start-Sleep -Seconds 4
