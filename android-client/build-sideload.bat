@echo off
setlocal
pushd "%~dp0" || exit /b 1

if not defined ANDROID_HOME if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
if not defined ANDROID_SDK_ROOT if defined ANDROID_HOME set "ANDROID_SDK_ROOT=%ANDROID_HOME%"

call gradlew.bat --no-daemon :app:assembleDebug
if errorlevel 1 goto :failed

if not exist "dist" mkdir "dist"
copy /y "app\build\outputs\apk\debug\app-debug.apk" "dist\TransferKit-Android-debug.apk" >nul
if errorlevel 1 goto :failed

echo Sideload APK ready: %CD%\dist\TransferKit-Android-debug.apk
popd
exit /b 0

:failed
echo Android sideload build failed. Check the Gradle output above.
popd
exit /b 1
