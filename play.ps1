# Starts a Portalcraft session: ReShade goes into Portal 2 for this session only and comes out when Portal 2 exits.
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $here 'tools\find-portal2.ps1')
$p2 = Find-Portal2
$rt = Join-Path $p2 'portalcraft_runtime'
$dll = Join-Path $p2 'bin\d3d9.dll'
$ini = Join-Path $p2 'ReShade.ini'
$reshade = Join-Path $rt 'ReShade32.dll'
if (-not (Test-Path $reshade)) { throw 'Run install.cmd first.' }
if ((Test-Path $dll) -and ((Get-FileHash $dll).Hash -ne (Get-FileHash $reshade).Hash)) {
	throw "Another d3d9.dll is already in $p2\bin (another mod?). Not touching it."
}
Copy-Item $reshade $dll -Force
[System.IO.File]::WriteAllText($ini, "[INSTALL]`r`nBasePath=$rt\`r`n", (New-Object System.Text.UTF8Encoding $false))

Write-Host 'Starting Portal 2...'
# -insecure: no VAC while a modified renderer is in the game (single player doesn't need it)
$steam = (Get-ItemProperty 'HKCU:\Software\Valve\Steam' -ErrorAction SilentlyContinue).SteamExe
if ($steam -and (Test-Path $steam)) { Start-Process $steam -ArgumentList '-applaunch', '620', '-insecure', '-novid' }
else { Start-Process 'steam://run/620//-insecure -novid/' }
Write-Host 'Now start Minecraft from the Minecraft Launcher with the "Portalcraft" installation.'
try { Start-Process 'shell:AppsFolder\Microsoft.4297127D64EC6_8wekyb3d8bbwe!Minecraft' } catch { }

# wait for Portal 2 to start and then to exit, then take ReShade out again
$deadline = (Get-Date).AddMinutes(3)
while (-not (Get-Process portal2 -ErrorAction SilentlyContinue)) {
	if ((Get-Date) -gt $deadline) { break }
	Start-Sleep -Seconds 2
}
while (Get-Process portal2 -ErrorAction SilentlyContinue) { Start-Sleep -Seconds 3 }
Remove-Item $dll, $ini -Force -ErrorAction SilentlyContinue
Write-Host 'Portal 2 closed: ReShade removed from the game folder.'
