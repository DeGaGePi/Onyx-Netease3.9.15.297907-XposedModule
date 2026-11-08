@echo off








setlocal
set "PY="
if exist "C:\Users\lenovo\.workbuddy\binaries\python\versions\3.13.12\python.exe" set "PY=C:\Users\lenovo\.workbuddy\binaries\python\versions\3.13.12\python.exe"
if not defined PY if exist "E:\Python3.11.9\python.exe" set "PY=E:\Python3.11.9\python.exe"
if not defined PY set "PY=python"
echo [*] packer: %PY%
"%PY%" "%~dp0fast_pack.py" %*
if errorlevel 1 (
  echo [X] fast pack failed
  exit /b 1
)
echo [OK] done - see E:\NewUI-build\dist
endlocal
