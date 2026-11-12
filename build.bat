@echo off
setlocal
pushd "%~dp0" || exit /b 1
if not exist ".venv\Scripts\python.exe" (
    echo The virtual environment is missing. Running install.bat first.
    call install.bat
    if errorlevel 1 goto :failed
)
".venv\Scripts\python.exe" -m PyInstaller --noconfirm --clean --windowed --onedir --name TransferKit --add-data "shared;shared" --distpath "dist" --workpath "build\pyinstaller" "transferkit_launcher.py"
if errorlevel 1 goto :failed
echo Build complete: dist\TransferKit\TransferKit.exe
popd
exit /b 0

:failed
popd
exit /b 1
