# Test helper: press keys in the foreground window (Portal 2), held long enough for the add-on's per-frame poll.
# Usage: key.ps1 0x42 [hold ms] [0x45 [ms] ...]   or   key.ps1 -Text "/summon zombie"   (types characters)
$Text = ""; $Keys = @()
for ($a = 0; $a -lt $args.Count; $a++) { if ($args[$a] -eq "-Text") { $Text = $args[$a + 1]; $a++ } else { $Keys += [string]$args[$a] } }
Add-Type -Namespace PC -Name Kb -MemberDefinition @'
[DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint flags, System.UIntPtr extra);
[DllImport("user32.dll")] public static extern uint MapVirtualKey(uint code, uint type);
[DllImport("user32.dll")] public static extern uint SendInput(uint n, INPUT[] inputs, int size);
[StructLayout(LayoutKind.Sequential)] public struct KEYBDINPUT { public ushort vk; public ushort scan; public uint flags; public uint time; public System.IntPtr extra; }
[StructLayout(LayoutKind.Explicit, Size = 40)] public struct INPUT { [FieldOffset(0)] public uint type; [FieldOffset(8)] public KEYBDINPUT ki; }
'@
for ($i = 0; $i -lt $Keys.Count; $i++) {
	$vk = [Convert]::ToByte($Keys[$i], 16)
	$ms = 150
	if ($i + 1 -lt $Keys.Count -and $Keys[$i + 1] -notmatch '^0x') { $ms = [int]$Keys[$i + 1]; $i++ }
	$scan = [byte][PC.Kb]::MapVirtualKey($vk, 0)
	[PC.Kb]::keybd_event($vk, $scan, 0, [UIntPtr]::Zero)
	Start-Sleep -Milliseconds $ms
	[PC.Kb]::keybd_event($vk, $scan, 2, [UIntPtr]::Zero)
	Start-Sleep -Milliseconds 120
}
foreach ($ch in $Text.ToCharArray()) {
	$in = New-Object 'PC.Kb+INPUT[]' 2
	for ($k = 0; $k -lt 2; $k++) { $in[$k].type = 1; $in[$k].ki.scan = [uint16]$ch; $in[$k].ki.flags = 4 -bor (2 * $k) }
	[void][PC.Kb]::SendInput(2, $in, 40)
	Start-Sleep -Milliseconds 25
}
