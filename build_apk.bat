@echo off






setlocal
chcp 65001 >nul


set "JAVA_HOME=C://Program Files\Java\jdk-17"


set "GRADLE_USER_HOME=E://gradle-home"
set "BUILD_ROOT=E://NewUI-build"

if /i "%~1"=="debug" (set "GRADLE_TASKS=assembleDebug") else (set "GRADLE_TASKS=assembleRelease assembleDebug")

call "%~dp0build_native.bat"
if errorlevel 1 exit /b 1









for %%D in (apk packaged_manifests) do (
  if exist "%BUILD_ROOT%\app\intermediates\%%D" rmdir /s /q "%BUILD_ROOT%\app\intermediates\%%D"
)
if exist "%BUILD_ROOT%\app\outputs\apk" rmdir /s /q "%BUILD_ROOT%\app\outputs\apk"

cd /d "%~dp0"
call gradlew.bat %GRADLE_TASKS% --console=plain --offline --project-cache-dir="%BUILD_ROOT%\.project-cache"
if errorlevel 1 exit /b 1


set "VER=unknown"
for /f "tokens=2 delims= " %%v in ('findstr /r /c:"versionName " "%~dp0app\build.gradle"') do set "VER=%%~v"

if not exist "%BUILD_ROOT%\dist" mkdir "%BUILD_ROOT%\dist"

echo.
echo [OK] version = %VER%
echo [OK] output  = %BUILD_ROOT%
echo.
copy /y "%BUILD_ROOT%\app\outputs\apk\release\app-release-unsigned.apk" "%BUILD_ROOT%\dist\Onyx-%VER%-release-unsigned.apk" >nul
copy /y "%BUILD_ROOT%\app\outputs\apk\debug\app-debug.apk"                        "%BUILD_ROOT%\dist\Onyx-%VER%-debug-signed.apk"     >nul
echo.
echo [OK] %BUILD_ROOT%\dist\Onyx-%VER%-release-unsigned.apk
echo      -- NOT signed, sign it yourself
echo [OK] %BUILD_ROOT%\dist\Onyx-%VER%-debug-signed.apk
echo      -- signed with debug key, install directly
endlocal
