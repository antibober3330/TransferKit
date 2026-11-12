@echo off
setlocal
pushd "%~dp0" || exit /b 1
if not exist ".venv\Scripts\python.exe" (
    echo The virtual environment is missing. Running install.bat first.
    call install.bat
    if errorlevel 1 goto :failed
)
".venv\Scripts\python.exe" -m transferkit %*
set "RESULT=%ERRORLEVEL%"
popd
exit /b %RESULT%

:failed
popd
exit /b 1
