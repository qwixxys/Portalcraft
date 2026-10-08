#!/bin/bash
# Restart only the Minecraft dev client (Portal 2 keeps running) and wait until the map is read.
HERE="$(cd "$(dirname "$0")/.." && pwd)"
powershell -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$HERE/tools/mcstop.ps1")" >/dev/null 2>&1; sleep 4 # let the old gradle finish writing its log
cd "$HERE/mc" && export JAVA_HOME="$(cygpath -w "$APPDATA/.minecraft/runtime/windows-x64/java-runtime-epsilon")"
nohup ./gradlew runClient --console=plain > ../runclient.log 2>&1 &
L="$HERE/runclient.log"
for i in $(seq 1 120); do sleep 2; grep -aqE "map sp_|map mp_|Caused by|BUILD FAILED" "$L" 2>/dev/null && break; done
sleep 2; grep -anE "map sp_|map mp_|world rules|Caused by|BUILD FAILED" "$L" | tail -3
