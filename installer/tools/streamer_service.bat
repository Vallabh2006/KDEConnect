@echo off
setlocal
title KDE Connect Screen Streamer
cd /d "%~dp0"
echo ========================================================================
echo   KDE Connect Screen Streamer Service (Port 59001)
echo ========================================================================
echo.
python.exe screen_streamer.py
if %errorlevel% neq 0 (
    echo.
    echo [!] Screen Streamer exited with code %errorlevel%.
    pause
)
