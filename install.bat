@echo off
setlocal
pushd "%~dp0" || exit /b 1
set "PYTHON_PREFIX="
set "PYTHON_EXE="

where py.exe >nul 2>&1
if not errorlevel 1 set "PYTHON_PREFIX=py -3"
if defined PYTHON_PREFIX (
    call :python -c "import sys; raise SystemExit(0 if sys.version_info >= (3,11) else 1)" >nul 2>&1
    if errorlevel 1 set "PYTHON_PREFIX="
)
if not defined PYTHON_PREFIX if exist "%LOCALAPPDATA%\Python\bin\python.exe" set "PYTHON_EXE=%LOCALAPPDATA%\Python\bin\python.exe"
if not defined PYTHON_PREFIX if not defined PYTHON_EXE (
    echo Python 3.11 or newer was not found. Install Python and run this script again.
    goto :failed
)

call :python -c "import sys; raise SystemExit(0 if sys.version_info >= (3,11) else 1)"
if errorlevel 1 (
    echo TransferKit requires Python 3.11 or newer.
    goto :failed
)

call :python -m venv .venv
if errorlevel 1 goto :failed
call ".venv\Scripts\python.exe" -m pip install --upgrade pip
if errorlevel 1 goto :failed
call ".venv\Scripts\python.exe" -m pip install -r requirements.txt
if errorlevel 1 goto :failed

echo Installation complete. Start TransferKit with run.bat or build it with build.bat.
popd
exit /b 0

:python
if defined PYTHON_PREFIX (
    %PYTHON_PREFIX% %*
) else (
    "%PYTHON_EXE%" %*
)
exit /b %ERRORLEVEL%

:failed
popd
exit /b 1
