@echo off
setlocal EnableDelayedExpansion
title KDE Connect Android USB Tether and Client Deployment
color 0B
echo.
echo  ========================================================================
echo    KDE Connect Android ADB Reverse Tether and Client Setup
echo  ========================================================================
echo.

where adb >nul 2>&1
if %errorlevel% neq 0 (
    echo  [-] Error: ADB (Android Debug Bridge) is not installed or not in PATH.
    echo      Please install Android platform-tools or enable USB debugging.
    echo.
    pause
    exit /b 1
)

echo  [*] Checking connected Android devices...
adb devices
echo.

echo  [*] Configuring reverse port forwarding for Streamer (59001) and Audio (59002)...
adb reverse tcp:59001 tcp:59001
adb reverse tcp:59002 tcp:59002
echo  [+] Port reverse forwarding configured.
echo.

if exist "%~dp0app-debug.apk" (
    echo  [*] Android Client APK found: %~dp0app-debug.apk
    set /p INSTALL_CHOICE="  [?] Do you want to install/update the APK on connected device? (Y/N): "
    if /i "!INSTALL_CHOICE!"=="Y" (
        echo  [*] Deploying APK to device via ADB...
        adb install -r "%~dp0app-debug.apk"
        if %errorlevel% equ 0 (
            echo  [+] APK successfully installed!
        ) else (
            echo  [-] Failed to install APK. Please check device authorization and screen unlock.
        )
    )
)

echo.
echo  [+] Setup finished.
pause
