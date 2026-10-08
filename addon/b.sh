#!/bin/bash
# The workspace path is too long for cl.exe: build in a short temp folder and copy the add-on back
HERE="$(cd "$(dirname "$0")" && pwd)"
W="$(cygpath -u "$TEMP")/pcb"
rm -rf "$W/src"; mkdir -p "$W/inc" "$W/out"
cp -r "$HERE/src" "$W/src"
cp -r "$HERE/../third_party/reshade-6.8.0/include/." "$W/inc/"
cat > "$W/build.cmd" <<'CMD'
@echo off
call "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars32.bat" >nul 2>nul
cd /d "%~dp0"
cl /nologo /std:c++17 /O2 /MT /EHsc /W3 /utf-8 /DWIN32_LEAN_AND_MEAN /DNOMINMAX /Iinc src\*.cpp /Foout\ /LD /Feout\Portalcraft.addon32 /link user32.lib kernel32.lib && echo BUILD-OK
CMD
cmd //c "$(cygpath -w "$W")\build.cmd" 2>&1 | iconv -f cp866 -t utf-8 | grep -vE "^\s*$|Создание кода" | tail -${1:-20}
mkdir -p "$HERE/out" && cp "$W/out/Portalcraft.addon32" "$HERE/out/" 2>/dev/null
