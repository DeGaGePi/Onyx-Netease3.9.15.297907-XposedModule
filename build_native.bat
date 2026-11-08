@echo off
rem ============================================================
rem  NewUI - compile native so into jniLibs
rem  (no CMake; call NDK clang++ directly, output arm64-v8a .so)
rem ============================================================
setlocal
rem clang writes its intermediate .o to %TEMP%; security software holds
rem handles there -> "unable to rename temporary ... Permission denied".
rem Point TMP/TEMP at E: instead.
if not exist "E:\NewUI-build\tmp" mkdir "E:\NewUI-build\tmp"
set "TMP=E:\NewUI-build\tmp"
set "TEMP=E:\NewUI-build\tmp"


set NDK=D://Android//Sdk//ndk//25.2.9519653
set TC=%NDK%\toolchains\llvm\prebuilt\windows-x86_64
set CXX=%TC%\bin\aarch64-linux-android24-clang++.cmd
set STRIP=%TC%\bin\llvm-strip.exe

set ROOT=%~dp0
set SRC=%ROOT%app\src\main\cpp
set OUT=%ROOT%app\src\main\jniLibs\arm64-v8a

if not exist "%CXX%" (
  echo [X] NDK compiler not found: %CXX%
  exit /b 1
)
if not exist "%STRIP%" (
  echo [X] NDK strip not found: %STRIP%
  exit /b 1
)

if not exist "%OUT%" mkdir "%OUT%"

echo [*] building libonyxmc.so ...
rem !!! MUST use call: clang++.cmd is a .cmd, and without call cmd.exe
rem     transfers control away and NEVER comes back -> strip below is
rem     skipped and the .so ships with a full symbol table (2.3MB vs 365KB).
call "%CXX%" -fintegrated-cc1 -shared -O2 -std=c++17 -fPIC -fvisibility=hidden -static-libstdc++ -o "%OUT%\libonyxmc.so" "%SRC%\onyxmc.cpp" -llog
if errorlevel 1 (
  echo [X] compile failed
  exit /b 1
)

rem strip is mandatory: 2300904 -> 365632 bytes
call "%STRIP%" --strip-unneeded "%OUT%\libonyxmc.so"
if errorlevel 1 (
  echo [X] strip failed
  exit /b 1
)

for %%A in ("%OUT%\libonyxmc.so") do set SZ=%%~zA
echo [OK] output: %OUT%\libonyxmc.so  (%SZ% bytes)
endlocal
