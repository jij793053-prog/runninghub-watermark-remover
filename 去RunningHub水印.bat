@echo off
setlocal
set "PYTHONUTF8=1"
set "APP=%~dp0rh_panel_app.py"
set "PYW="

if exist "%~dp0.venv\Scripts\pythonw.exe" set "PYW=%~dp0.venv\Scripts\pythonw.exe"
if not defined PYW if exist "%~dp0venv\Scripts\pythonw.exe" set "PYW=%~dp0venv\Scripts\pythonw.exe"

if not defined PYW (
    for /f "delims=" %%P in ('where pythonw 2^>nul') do (
        echo "%%P" | findstr /I "WindowsApps" >nul
        if errorlevel 1 if not defined PYW set "PYW=%%P"
    )
)

if defined PYW (
    start "" "%PYW%" "%APP%" %*
    exit /b 0
)

where py >nul 2>&1
if not errorlevel 1 (
    start "" pyw -3 "%APP%" %*
    exit /b 0
)

echo [Error] Python (pythonw.exe) not found. Please install Python 3.10+ and requirements.txt.
pause
exit /b 1
