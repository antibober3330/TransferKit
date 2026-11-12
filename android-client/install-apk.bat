@echo off
setlocal EnableExtensions EnableDelayedExpansion
pushd "%~dp0" || exit /b 1

if not defined ANDROID_HOME if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
if not defined ANDROID_HOME if exist "local.properties" (
    for /f "usebackq tokens=1,* delims==" %%A in ("local.properties") do (
        if "%%A"=="sdk.dir" set "ANDROID_HOME=%%B"
    )
)

set "ADB_EXE="
if defined ANDROID_HOME if exist "%ANDROID_HOME%\platform-tools\adb.exe" set "ADB_EXE=%ANDROID_HOME%\platform-tools\adb.exe"
if not defined ADB_EXE (
    where adb.exe >nul 2>&1
    if not errorlevel 1 set "ADB_EXE=adb.exe"
)
if not defined ADB_EXE (
    echo Android Debug Bridge was not found. Set ANDROID_HOME or add platform-tools to PATH.
    popd
    exit /b 1
)

if not exist "dist\TransferKit-Android-debug.apk" (
    call build-sideload.bat
    if errorlevel 1 (
        popd
        exit /b 1
    )
)

"%ADB_EXE%" devices
set "DEVICE_COUNT=0"
set "ONLY_DEVICE="
for /f "skip=1 tokens=1,2" %%A in ('"%ADB_EXE%" devices') do if "%%B"=="device" (
    set /a DEVICE_COUNT+=1
    set "ONLY_DEVICE=%%A"
)

if "%~1"=="" (
    if not "!DEVICE_COUNT!"=="1" (
        echo Multiple or no ADB devices are connected. Run: install-apk.bat DEVICE_SERIAL
        popd
        exit /b 1
    )
    set "DEVICE_SERIAL=!ONLY_DEVICE!"
) else (
    set "DEVICE_SERIAL=%~1"
)

set "DEVICE_FOUND="
for /f "skip=1 tokens=1,2" %%A in ('"%ADB_EXE%" devices') do if "%%A"=="%DEVICE_SERIAL%" if "%%B"=="device" set "DEVICE_FOUND=1"
if not defined DEVICE_FOUND (
    echo Device "%DEVICE_SERIAL%" is not online in ADB.
    popd
    exit /b 1
)

"%ADB_EXE%" -s "%DEVICE_SERIAL%" install -r "dist\TransferKit-Android-debug.apk"
if errorlevel 1 (
    popd
    exit /b 1
)

echo TransferKit debug APK installed on %DEVICE_SERIAL%.
popd
exit /b 0
