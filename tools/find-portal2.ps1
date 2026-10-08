# shared by install.ps1 and play.ps1
function Find-Portal2 {
	$steam = (Get-ItemProperty 'HKCU:\Software\Valve\Steam' -ErrorAction SilentlyContinue).SteamPath
	if (-not $steam) { $steam = 'C:\Program Files (x86)\Steam' }
	$libs = @($steam)
	$vdf = Join-Path $steam 'steamapps\libraryfolders.vdf'
	if (Test-Path $vdf) {
		foreach ($m in [regex]::Matches((Get-Content $vdf -Raw), '"path"\s+"([^"]+)"')) { $libs += $m.Groups[1].Value -replace '\\\\', '\' }
	}
	foreach ($l in $libs) {
		$p = Join-Path $l 'steamapps\common\Portal 2'
		if (Test-Path (Join-Path $p 'portal2.exe')) { return $p }
	}
	throw 'Portal 2 not found in any Steam library.'
}
