@echo off
rem Builds Portalcraft.addon32 (ReShade add-on for 32-bit Portal 2) with the MSVC x86 compiler
setlocal
call "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars32.bat" >nul || exit /b 1
cd /d "%~dp0"
set RESHADE_INC=%~dp0..\third_party\reshade-6.8.0\include
if not exist out mkdir out
cl /nologo /std:c++17 /O2 /MT /EHsc /W3 /utf-8 /DWIN32_LEAN_AND_MEAN /DNOMINMAX /I"%RESHADE_INC%" src\*.cpp /Foout\ /LD /Feout\Portalcraft.addon32 /link user32.lib kernel32.lib || exit /b 1
echo built out\Portalcraft.addon32
