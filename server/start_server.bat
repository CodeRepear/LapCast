@echo off
title LapCast Server
echo ========================================================
echo                 LapCast Desktop Server
echo ========================================================
echo.

where python >nul 2>nul
if %errorlevel% neq 0 (
    echo [ERROR] Python is not installed or not added to your PATH!
    echo Please install Python 3.8+ from https://www.python.org/
    pause
    exit /b 1
)

echo [*] Checking dependencies...
python -m pip install -q -r requirements.txt
if %errorlevel% neq 0 (
    echo [WARNING] Failed to install dependencies via pip.
    echo Please run manually: pip install mss pillow aiohttp qrcode
)

echo.
echo [*] Starting LapCast server...
echo     (Note: Run this batch file as Administrator if you need to
echo      control elevated Windows apps or UAC prompts)
echo.

python lapcast_server.py %*

if %errorlevel% neq 0 (
    echo.
    echo [!] Server exited with an error.
    pause
)
