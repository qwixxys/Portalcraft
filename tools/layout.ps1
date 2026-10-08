# Test helper: switch the foreground window's keyboard layout (default English US), as Alt+Shift would.
param([string]$Hkl = '04090409')
Add-Type -Namespace PC -Name Lay -MemberDefinition @'
[DllImport("user32.dll")] public static extern System.IntPtr GetForegroundWindow();
[DllImport("user32.dll")] public static extern System.IntPtr LoadKeyboardLayout(string id, uint flags);
[DllImport("user32.dll")] public static extern bool PostMessage(System.IntPtr h, uint msg, System.IntPtr w, System.IntPtr l);
'@
$h = [PC.Lay]::LoadKeyboardLayout($Hkl, 1)
[void][PC.Lay]::PostMessage([PC.Lay]::GetForegroundWindow(), 0x0050, [IntPtr]::Zero, $h)
