# Mouse button press without moving the cursor (moving it would turn Portal 2's view). Usage: click.ps1 left|right [ms]
param([string]$button = 'left', [int]$hold = 60)
Add-Type -Namespace PC -Name Mouse -MemberDefinition '[DllImport("user32.dll")] public static extern void mouse_event(uint f, uint dx, uint dy, uint d, System.UIntPtr e);'
$down, $up = if ($button -eq 'right') { 0x0008, 0x0010 } elseif ($button -eq 'middle') { 0x0020, 0x0040 } else { 0x0002, 0x0004 }
[PC.Mouse]::mouse_event($down, 0, 0, 0, [UIntPtr]::Zero)
Start-Sleep -Milliseconds $hold
[PC.Mouse]::mouse_event($up, 0, 0, 0, [UIntPtr]::Zero)
