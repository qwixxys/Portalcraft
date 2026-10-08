# Stops the Minecraft dev client started by "gradlew runClient" (exact PID, found by its launch class)
Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
  Where-Object { $_.CommandLine -like '*devlaunchinjector*' } |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force; "stopped $($_.ProcessId)" }
