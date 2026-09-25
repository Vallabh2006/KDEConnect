@echo off
setlocal EnableDelayedExpansion


title KDE Connect Windows Uninstaller
color 0C

echo.
echo  ========================================================================
echo    KDE Connect for Windows - Uninstaller
echo  ========================================================================
echo.

set "CURRENT_DIR=%~dp0"
if /I "%CURRENT_DIR:~-1%"=="\" set "CURRENT_DIR=%CURRENT_DIR:~0,-1%"
set "CHECK_TEMP=%TEMP%"
if /I "%CHECK_TEMP:~-1%"=="\" set "CHECK_TEMP=%CHECK_TEMP:~0,-1%"

if /I not "%CURRENT_DIR%"=="%CHECK_TEMP%" (
    copy /Y "%~f0" "%TEMP%\kdeconnect_uninstall.bat" >nul 2>&1
    call "%TEMP%\kdeconnect_uninstall.bat" %*
    exit /b %errorlevel%
)

set "AUTO_CONFIRM=0"
if /I "%~1"=="/Y" set "AUTO_CONFIRM=1"
if /I "%~1"=="/SILENT" set "AUTO_CONFIRM=1"
if /I "%~1"=="-y" set "AUTO_CONFIRM=1"

echo  [*] Checking permissions...
net session >nul 2>&1
if %errorlevel% neq 0 (
    echo  [!] Elevation required. Requesting Administrator privileges...
    powershell -NoProfile -Command "Start-Process cmd.exe -ArgumentList '/c \"\"%TEMP%\kdeconnect_uninstall.bat\" %*\"' -Verb RunAs"
    exit /b
)
echo  [+] Administrator privileges confirmed.
echo.

set "INSTALL_DIR=%ProgramFiles%\KDE Connect"
set "SHORTCUTS_DIR=%ProgramData%\Microsoft\Windows\Start Menu\Programs\KDE Connect"
set "USER_SHORTCUTS_DIR=%APPDATA%\Microsoft\Windows\Start Menu\Programs\KDE Connect"

set "REG_INSTALL_DIR="
for /f "tokens=2* delims= " %%A in ('reg query "HKLM\Software\Microsoft\Windows\CurrentVersion\Uninstall\KDEConnect" /v InstallLocation 2^>nul') do set "REG_INSTALL_DIR=%%B"
if not defined REG_INSTALL_DIR (
    for /f "tokens=2* delims= " %%A in ('reg query "HKLM\Software\Microsoft\Windows\CurrentVersion\Uninstall\{D814AC22-86DE-494B-96DE-9A6836F85E0E}_is1" /v InstallLocation 2^>nul') do set "REG_INSTALL_DIR=%%B"
)
if not defined REG_INSTALL_DIR (
    for /f "tokens=2* delims= " %%A in ('reg query "HKCU\Software\Microsoft\Windows\CurrentVersion\Uninstall\KDEConnect" /v InstallLocation 2^>nul') do set "REG_INSTALL_DIR=%%B"
)
if not defined REG_INSTALL_DIR (
    for /f "tokens=2* delims= " %%A in ('reg query "HKCU\Software\Microsoft\Windows\CurrentVersion\Uninstall\{D814AC22-86DE-494B-96DE-9A6836F85E0E}_is1" /v InstallLocation 2^>nul') do set "REG_INSTALL_DIR=%%B"
)

if defined REG_INSTALL_DIR (
    if "!REG_INSTALL_DIR:~-1!"=="\" set "REG_INSTALL_DIR=!REG_INSTALL_DIR:~0,-1!"
)

echo  [*] Terminating KDE Connect background processes and services...
taskkill /F /IM kdeconnect-app.exe >nul 2>&1
taskkill /F /IM kdeconnectd.exe >nul 2>&1
taskkill /F /IM kdeconnect-indicator.exe >nul 2>&1
taskkill /F /IM kdeconnect-settings.exe >nul 2>&1
taskkill /F /IM kdeconnect-handler.exe >nul 2>&1
taskkill /F /IM kdeconnect-sms.exe >nul 2>&1
taskkill /F /IM kdeconnect-streamer.exe >nul 2>&1
powershell -NoProfile -Command "Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match 'screen_streamer\.py' -or $_.CommandLine -match 'streamer_silent\.vbs' -or $_.CommandLine -match 'kdeconnect' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }" >nul 2>&1
cmd.exe /c taskkill /F /FI "WINDOWTITLE eq KDE Connect Screen Streamer" >nul 2>&1

:: Stop and delete any registered Windows service
sc stop KDEConnectStreamer >nul 2>&1
sc delete KDEConnectStreamer >nul 2>&1
sc stop KDEConnectDaemon >nul 2>&1
sc delete KDEConnectDaemon >nul 2>&1
sc stop KDEConnect >nul 2>&1
sc delete KDEConnect >nul 2>&1

:: Delete any scheduled tasks
schtasks /Delete /TN "KDEConnectStreamer" /F >nul 2>&1
schtasks /Delete /TN "KDEConnectIndicator" /F >nul 2>&1
schtasks /Delete /TN "KDEConnect" /F >nul 2>&1

echo  [+] All KDE Connect processes and services stopped and removed.
echo.

echo  [*] Removing Windows Defender Firewall rules...
netsh advfirewall firewall delete rule name="KDE Connect Daemon (TCP-In)" >nul 2>&1
netsh advfirewall firewall delete rule name="KDE Connect Daemon (UDP-In)" >nul 2>&1
netsh advfirewall firewall delete rule name="KDE Connect Streamer (TCP-In)" >nul 2>&1
netsh advfirewall firewall delete rule name="KDE Connect Streamer Audio (TCP-In)" >nul 2>&1
echo  [+] Firewall rules removed.
echo.

echo  [*] Removing startup entries...
reg delete "HKCU\Software\Microsoft\Windows\CurrentVersion\Run" /v "KDEConnectIndicator" /f >nul 2>&1
reg delete "HKLM\Software\Microsoft\Windows\CurrentVersion\Run" /v "KDEConnectIndicator" /f >nul 2>&1
reg delete "HKCU\Software\Microsoft\Windows\CurrentVersion\Run" /v "KDEConnectStreamer" /f >nul 2>&1
reg delete "HKLM\Software\Microsoft\Windows\CurrentVersion\Run" /v "KDEConnectStreamer" /f >nul 2>&1
echo  [+] Startup entries removed.
echo.

echo  [*] Removing Windows Settings registration...
reg delete "HKLM\Software\Microsoft\Windows\CurrentVersion\Uninstall\KDEConnect" /f >nul 2>&1
reg delete "HKCU\Software\Microsoft\Windows\CurrentVersion\Uninstall\KDEConnect" /f >nul 2>&1
reg delete "HKLM\Software\Microsoft\Windows\CurrentVersion\Uninstall\{D814AC22-86DE-494B-96DE-9A6836F85E0E}_is1" /f >nul 2>&1
reg delete "HKCU\Software\Microsoft\Windows\CurrentVersion\Uninstall\{D814AC22-86DE-494B-96DE-9A6836F85E0E}_is1" /f >nul 2>&1
echo  [+] Windows uninstaller registration removed.
echo.

echo  [*] Removing Start Menu and Desktop shortcuts...
if exist "%SHORTCUTS_DIR%" rmdir /S /Q "%SHORTCUTS_DIR%" >nul 2>&1
if exist "%USER_SHORTCUTS_DIR%" rmdir /S /Q "%USER_SHORTCUTS_DIR%" >nul 2>&1
if exist "%Public%\Desktop\KDE Connect.lnk" del /F /Q "%Public%\Desktop\KDE Connect.lnk" >nul 2>&1
if exist "%USERPROFILE%\Desktop\KDE Connect.lnk" del /F /Q "%USERPROFILE%\Desktop\KDE Connect.lnk" >nul 2>&1
echo  [+] Shortcuts deleted.
echo.

if "!AUTO_CONFIRM!"=="1" (
    set "PURGE_DATA=Y"
) else (
    set /p PURGE_DATA=" [*] Do you want to remove saved pairings, keys, and device configs? [Y/N]: "
)
if /I "%PURGE_DATA%"=="Y" (
    echo  [*] Purging user configuration and cached keys...
    if exist "%LOCALAPPDATA%\kdeconnect" rmdir /S /Q "%LOCALAPPDATA%\kdeconnect" >nul 2>&1
    if exist "%LOCALAPPDATA%\kdeconnect.app" rmdir /S /Q "%LOCALAPPDATA%\kdeconnect.app" >nul 2>&1
    if exist "%LOCALAPPDATA%\kdeconnect.daemon" rmdir /S /Q "%LOCALAPPDATA%\kdeconnect.daemon" >nul 2>&1
    if exist "%APPDATA%\kdeconnect" rmdir /S /Q "%APPDATA%\kdeconnect" >nul 2>&1
    if exist "%LOCALAPPDATA%\kdeconnect-sms" rmdir /S /Q "%LOCALAPPDATA%\kdeconnect-sms" >nul 2>&1
    if exist "%TEMP%\task_manager_analytics.db" del /F /Q "%TEMP%\task_manager_analytics.db*" >nul 2>&1
    if exist "%TEMP%\kdeconnect_screenshot.png" del /F /Q "%TEMP%\kdeconnect_screenshot.png" >nul 2>&1
    echo  [+] User configuration purged.
)
echo.

echo  [*] Removing application directories from Program Files and C:...
cd /d "%TEMP%"

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$dirs = @('%ProgramFiles%\KDE Connect', '%ProgramFiles%\KDEConnect', '%ProgramFiles(x86)%\KDE Connect', '%ProgramFiles(x86)%\KDEConnect', 'C:\KDE Connect', 'C:\KDEConnect'); " ^
    "if ('!REG_INSTALL_DIR!' -ne '') { $dirs += '!REG_INSTALL_DIR!' }; " ^
    "foreach ($d in $dirs) { if (Test-Path -LiteralPath $d) { try { Get-ChildItem -LiteralPath $d -Recurse -Force | Remove-Item -Force -Recurse -ErrorAction SilentlyContinue } catch {} ; try { Remove-Item -LiteralPath $d -Force -Recurse -ErrorAction SilentlyContinue } catch {} } }" >nul 2>&1

for %%D in (
    "!REG_INSTALL_DIR!"
    "%ProgramFiles%\KDE Connect"
    "%ProgramFiles%\KDEConnect"
    "%ProgramFiles(x86)%\KDE Connect"
    "%ProgramFiles(x86)%\KDEConnect"
    "C:\KDE Connect"
    "C:\KDEConnect"
) do (
    if "%%~D" neq "" (
        if exist "%%~D" (
            echo      - Removing "%%~D"...
            attrib -r -h -s "%%~D\*" /s /d >nul 2>&1
            del /f /q /s /a "%%~D\*" >nul 2>&1
            rmdir /s /q "%%~D" >nul 2>&1
        )
    )
)

(
    echo @echo off
    echo timeout /t 2 /nobreak ^>nul
    for %%D in (
        "!REG_INSTALL_DIR!"
        "%ProgramFiles%\KDE Connect"
        "%ProgramFiles%\KDEConnect"
        "%ProgramFiles(x86)%\KDE Connect"
        "%ProgramFiles(x86)%\KDEConnect"
        "C:\KDE Connect"
        "C:\KDEConnect"
    ) do (
        if "%%~D" neq "" (
            echo if exist "%%~D" rmdir /s /q "%%~D" ^>nul 2^>^&1
        )
    )
    echo del "%%~f0" ^>nul 2^>^&1
) > "%TEMP%\kdeconnect_post_uninstall.bat"
start "" /b cmd.exe /c "%TEMP%\kdeconnect_post_uninstall.bat"

echo.
echo  ========================================================================
echo    [SUCCESS] KDE Connect has been cleanly uninstalled!
echo  ========================================================================
echo.
if not "!AUTO_CONFIRM!"=="1" pause
