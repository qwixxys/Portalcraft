<# : Portalcraft launcher (batch part)
@echo off
chcp 65001 >nul
powershell -NoProfile -ExecutionPolicy Bypass -Command "& ([scriptblock]::Create((Get-Content -LiteralPath '%~f0' -Raw -Encoding UTF8)))"
exit /b
#>
# Everything above is the batch part; to PowerShell it is one block comment.
# Needs a finished install (install.cmd). Puts ReShade into Portal 2 for this session only, starts Portal 2,
# opens the Minecraft Launcher, and takes ReShade out again when Portal 2 closes.
$ErrorActionPreference = 'Stop'

function Done([string]$msg) { Write-Host ''; Write-Host $msg; Read-Host 'Нажми Enter, чтобы закрыть' | Out-Null; exit }

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
$profiles = Join-Path $env:APPDATA '.minecraft\launcher_profiles.json'
if (-not (Test-Path $profiles) -or -not (Select-String -Path $profiles -Pattern '"portalcraft"' -Quiet)) {
	Done 'В лаунчере Minecraft нет установки Portalcraft: запусти install.cmd (при закрытом лаунчере).'
}
if (Get-Process portal2 -ErrorAction SilentlyContinue) { Done 'Portal 2 уже запущен: закрой его и запусти Portalcraft снова.' }

# ---- ReShade in, only for this session
$dll = Join-Path $p2 'bin\d3d9.dll'
$ini = Join-Path $p2 'ReShade.ini'
if ((Test-Path $dll) -and ((Get-FileHash $dll).Hash -ne (Get-FileHash $reshade).Hash)) {
	Done "В $p2\bin уже лежит чужой d3d9.dll (другой мод?). Не трогаю его."
}
Copy-Item $reshade $dll -Force
[System.IO.File]::WriteAllText($ini, "[INSTALL]`r`nBasePath=$rt\`r`n", (New-Object System.Text.UTF8Encoding $false))

# ---- start Portal 2 (-insecure: no VAC while a modified renderer is loaded; single player only)
Write-Host 'Запускаю Portal 2...'
if ($steamExe -and (Test-Path $steamExe)) { Start-Process $steamExe -ArgumentList '-applaunch', '620', '-insecure', '-novid' }
else { Start-Process 'steam://run/620//-insecure -novid/' }

# ---- open the Minecraft Launcher (Microsoft Store version, or the classic one)
Write-Host 'Открываю лаунчер Minecraft: выбери установку "Portalcraft" и нажми "Играть".'
Write-Host '(Лаунчер предупредит о модифицированной версии - нажми "Играть" ещё раз.)'
$classic = @("${env:ProgramFiles(x86)}\Minecraft Launcher\MinecraftLauncher.exe", "$env:ProgramFiles\Minecraft Launcher\MinecraftLauncher.exe") | Where-Object { Test-Path $_ } | Select-Object -First 1
try { Start-Process 'shell:AppsFolder\Microsoft.4297127D64EC6_8wekyb3d8bbwe!Minecraft' }
catch { if ($classic) { Start-Process $classic } else { Write-Host 'Не нашёл лаунчер Minecraft - открой его сам.' } }

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
