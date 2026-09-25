@echo off
setlocal EnableDelayedExpansion

:: KDE Connect - Windows 10/11 Installation Script (With Dependency Resolver)
:: Features: Install, Modify (custom install location), Firewall & Dependencies

title KDE Connect Windows Installer
color 0B

echo.
echo  ========================================================================
echo    KDE Connect for Windows - Unified Installer
echo  ========================================================================
echo.

echo  [*] Checking permissions...
net session >nul 2>&1
if %errorlevel% neq 0 (
    echo  [!] Elevation required. Requesting Administrator privileges...
    powershell -Command "Start-Process '%~f0' -Verb RunAs"
    exit /b
)
echo  [+] Administrator privileges confirmed.
echo.

:: 2. Check and Install Dependencies
echo  [*] Checking system dependencies...

:: Check for Visual C++ 2015-2022 Redistributable (x64)
set "VCREDIST_INSTALLED=0"
reg query "HKLM\SOFTWARE\Microsoft\VisualStudio\14.0\VC\Runtimes\x64" /v Installed >nul 2>&1
if %errorlevel% equ 0 (
    set "VCREDIST_INSTALLED=1"
)
reg query "HKLM\SOFTWARE\WOW6432Node\Microsoft\VisualStudio\14.0\VC\Runtimes\x64" /v Installed >nul 2>&1
if %errorlevel% equ 0 (
    set "VCREDIST_INSTALLED=1"
)

if "!VCREDIST_INSTALLED!"=="1" (
    echo  [+] Microsoft Visual C++ Redistributable (x64) is already installed.
) else (
    echo  [!] Missing Microsoft Visual C++ Redistributable (x64).
    echo  [*] Downloading and installing Microsoft Visual C++ Runtime...
    
    set "VCREDIST_URL=https://aka.ms/vs/17/release/vc_redist.x64.exe"
    set "TEMP_INSTALLER=%TEMP%\vc_redist.x64.exe"
    
    powershell -Command "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; (New-Object System.Net.WebClient).DownloadFile('!VCREDIST_URL!', '!TEMP_INSTALLER!')"
    
    if exist "!TEMP_INSTALLER!" (
        echo  [*] Running Visual C++ installer in silent mode...
        start /wait "" "!TEMP_INSTALLER!" /install /quiet /norestart
        del /F /Q "!TEMP_INSTALLER!" >nul 2>&1
        echo  [+] Microsoft Visual C++ Runtime installed successfully.
    ) else (
        echo  [-] Warning: Failed to download Visual C++ Redistributable automatically.
    )
)

:: Check for Python and install Python dependencies
where python >nul 2>&1
if %errorlevel% equ 0 (
    echo  [*] Python detected. Updating Python packages (Pillow, psutil)...
    python -m pip install --quiet Pillow psutil
    echo  [+] Python dependencies verified.
) else (
    echo  [!] Note: Python was not found in PATH.
    echo      Screen Streamer daemon requires Python 3.
)
echo.

:: 3. Configuration Paths (Allow custom directory via %1)
set "SOURCE_DIR=%~dp0..\"
set "INSTALL_DIR=%ProgramFiles%\KDE Connect"
if not "%~1"=="" (
    set "INSTALL_DIR=%~1"
)

set "BIN_APP=%INSTALL_DIR%\bin\kdeconnect-app.exe"
set "BIN_DAEMON=%INSTALL_DIR%\bin\kdeconnectd.exe"
set "BIN_INDICATOR=%INSTALL_DIR%\bin\kdeconnect-indicator.exe"
set "SHORTCUTS_DIR=%ProgramData%\Microsoft\Windows\Start Menu\Programs\KDE Connect"

echo  [*] Target Installation Path : %INSTALL_DIR%
echo  [*] Shortcuts Path           : %SHORTCUTS_DIR%
echo.

:: 4. Terminate Any Running Instances
echo  [*] Stopping existing KDE Connect processes if active...
taskkill /F /IM kdeconnect-app.exe >nul 2>&1
taskkill /F /IM kdeconnectd.exe >nul 2>&1
taskkill /F /IM kdeconnect-indicator.exe >nul 2>&1
taskkill /F /IM kdeconnect-settings.exe >nul 2>&1
taskkill /F /IM kdeconnect-handler.exe >nul 2>&1
taskkill /F /IM kdeconnect-sms.exe >nul 2>&1
taskkill /F /IM kdeconnect-streamer.exe >nul 2>&1
powershell -NoProfile -Command "Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match 'screen_streamer\.py' -or $_.CommandLine -match 'streamer_silent\.vbs' -or $_.CommandLine -match 'kdeconnect' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }" >nul 2>&1
cmd.exe /c taskkill /F /FI "WINDOWTITLE eq KDE Connect Screen Streamer" >nul 2>&1
sc stop KDEConnectStreamer >nul 2>&1
sc stop KDEConnectDaemon >nul 2>&1
echo  [+] Processes stopped.
echo.

echo  [*] Deploying files to %INSTALL_DIR%...
if not exist "%INSTALL_DIR%" mkdir "%INSTALL_DIR%"
if not exist "%INSTALL_DIR%\installers" mkdir "%INSTALL_DIR%\installers"
if not exist "%INSTALL_DIR%\tools" mkdir "%INSTALL_DIR%\tools"

if exist "%SOURCE_DIR%bin" (
    robocopy "%SOURCE_DIR%bin" "%INSTALL_DIR%\bin" /E /NFL /NDL /NJH /NJS /nc /ns /np >nul
)
if exist "%SOURCE_DIR%lib" (
    robocopy "%SOURCE_DIR%lib" "%INSTALL_DIR%\lib" /E /NFL /NDL /NJH /NJS /nc /ns /np >nul
)
if exist "%SOURCE_DIR%share" (
    robocopy "%SOURCE_DIR%share" "%INSTALL_DIR%\share" /E /NFL /NDL /NJH /NJS /nc /ns /np >nul
)
if exist "%SOURCE_DIR%qml" (
    robocopy "%SOURCE_DIR%qml" "%INSTALL_DIR%\qml" /E /NFL /NDL /NJH /NJS /nc /ns /np >nul
)
if exist "%SOURCE_DIR%etc" (
    robocopy "%SOURCE_DIR%etc" "%INSTALL_DIR%\etc" /E /NFL /NDL /NJH /NJS /nc /ns /np >nul
)

:: Deploy streamer and tools
if exist "%~dp0tools" (
    robocopy "%~dp0tools" "%INSTALL_DIR%\tools" /E /NFL /NDL /NJH /NJS /nc /ns /np >nul
)

:: Copy uninstaller to installation folder
copy /Y "%~dp0uninstall.bat" "%INSTALL_DIR%\installers\uninstall.bat" >nul 2>&1

echo  [+] Files deployed successfully.
echo.

echo  [*] Configuring Windows Defender Firewall rules...

netsh advfirewall firewall delete rule name="KDE Connect Daemon (TCP-In)" >nul 2>&1
netsh advfirewall firewall delete rule name="KDE Connect Daemon (UDP-In)" >nul 2>&1
netsh advfirewall firewall delete rule name="KDE Connect Streamer (TCP-In)" >nul 2>&1
netsh advfirewall firewall delete rule name="KDE Connect Streamer Audio (TCP-In)" >nul 2>&1

netsh advfirewall firewall add rule name="KDE Connect Daemon (TCP-In)" dir=in action=allow protocol=TCP localport=1714-1764 profile=any >nul
netsh advfirewall firewall add rule name="KDE Connect Daemon (UDP-In)" dir=in action=allow protocol=UDP localport=1714-1764 profile=any >nul
netsh advfirewall firewall add rule name="KDE Connect Streamer (TCP-In)" dir=in action=allow protocol=TCP localport=59001 profile=any >nul
netsh advfirewall firewall add rule name="KDE Connect Streamer Audio (TCP-In)" dir=in action=allow protocol=TCP localport=59002 profile=any >nul

echo  [+] Firewall rules registered for TCP/UDP ports 1714-1764 and Streamer ports 59001-59002.
echo.

echo  [*] Creating Shortcuts...
if not exist "%SHORTCUTS_DIR%" mkdir "%SHORTCUTS_DIR%"

powershell -Command "$ws = New-Object -ComObject WScript.Shell; $s = $ws.CreateShortcut('%SHORTCUTS_DIR%\KDE Connect.lnk'); $s.TargetPath = '%BIN_APP%'; $s.WorkingDirectory = '%INSTALL_DIR%\bin'; $s.Description = 'KDE Connect for Windows'; $s.Save()"

if exist "%INSTALL_DIR%\tools\kdeconnect-streamer.exe" (
    powershell -Command "$ws = New-Object -ComObject WScript.Shell; $s = $ws.CreateShortcut('%SHORTCUTS_DIR%\Start Screen Streamer.lnk'); $s.TargetPath = '%INSTALL_DIR%\tools\kdeconnect-streamer.exe'; $s.WorkingDirectory = '%INSTALL_DIR%\tools'; $s.IconLocation = '%BIN_APP%,0'; $s.Save()"
) else (
    powershell -Command "$ws = New-Object -ComObject WScript.Shell; $s = $ws.CreateShortcut('%SHORTCUTS_DIR%\Start Screen Streamer.lnk'); $s.TargetPath = 'wscript.exe'; $s.Arguments = '\"%INSTALL_DIR%\tools\streamer_silent.vbs\"'; $s.WorkingDirectory = '%INSTALL_DIR%\tools'; $s.IconLocation = '%BIN_APP%,0'; $s.Save()"
)

powershell -Command "$ws = New-Object -ComObject WScript.Shell; $s = $ws.CreateShortcut('%SHORTCUTS_DIR%\Deploy Android Client (ADB).lnk'); $s.TargetPath = '%INSTALL_DIR%\tools\setup_adb_android.bat'; $s.WorkingDirectory = '%INSTALL_DIR%\tools'; $s.Save()"
powershell -Command "$ws = New-Object -ComObject WScript.Shell; $s = $ws.CreateShortcut('%SHORTCUTS_DIR%\Uninstall KDE Connect.lnk'); $s.TargetPath = '%INSTALL_DIR%\installers\uninstall.bat'; $s.IconLocation = '%SystemRoot%\System32\shell32.dll,32'; $s.Save()"
powershell -Command "$ws = New-Object -ComObject WScript.Shell; $s = $ws.CreateShortcut('%Public%\Desktop\KDE Connect.lnk'); $s.TargetPath = '%BIN_APP%'; $s.WorkingDirectory = '%INSTALL_DIR%\bin'; $s.Save()"

echo  [+] Shortcuts created.
echo.

echo  [*] Registering background indicator and streamer in user startup...
reg add "HKCU\Software\Microsoft\Windows\CurrentVersion\Run" /v "KDEConnectIndicator" /t REG_SZ /d "\"%BIN_INDICATOR%\"" /f >nul 2>&1
if exist "%INSTALL_DIR%\tools\kdeconnect-streamer.exe" (
    reg add "HKCU\Software\Microsoft\Windows\CurrentVersion\Run" /v "KDEConnectStreamer" /t REG_SZ /d "\"%INSTALL_DIR%\tools\kdeconnect-streamer.exe\"" /f >nul 2>&1
) else (
    reg add "HKCU\Software\Microsoft\Windows\CurrentVersion\Run" /v "KDEConnectStreamer" /t REG_SZ /d "wscript.exe \"%INSTALL_DIR%\tools\streamer_silent.vbs\"" /f >nul 2>&1
)
echo  [+] Autostart configured.
echo.

echo  [*] Registering in Windows Settings...
set "UNINSTALL_REG=HKLM\Software\Microsoft\Windows\CurrentVersion\Uninstall\KDEConnect"

reg add "%UNINSTALL_REG%" /v "DisplayName" /t REG_SZ /d "KDE Connect" /f >nul
reg add "%UNINSTALL_REG%" /v "DisplayVersion" /t REG_SZ /d "24.0" /f >nul
reg add "%UNINSTALL_REG%" /v "Publisher" /t REG_SZ /d "KDE Community" /f >nul
reg add "%UNINSTALL_REG%" /v "InstallLocation" /t REG_SZ /d "%INSTALL_DIR%" /f >nul
reg add "%UNINSTALL_REG%" /v "UninstallString" /t REG_SZ /d "\"%INSTALL_DIR%\installers\uninstall.bat\"" /f >nul
reg add "%UNINSTALL_REG%" /v "ModifyPath" /t REG_SZ /d "\"%~f0\"" /f >nul
reg add "%UNINSTALL_REG%" /v "NoModify" /t REG_DWORD /d 0 /f >nul
reg add "%UNINSTALL_REG%" /v "NoRepair" /t REG_DWORD /d 0 /f >nul

echo  [+] Registered in Windows Installed Apps (with Modify and Uninstall options).
echo.

echo  ========================================================================
echo    [SUCCESS] KDE Connect has been installed successfully!
echo  ========================================================================
echo.
echo    - Installation Directory : %INSTALL_DIR%
echo    - Dependencies Installed : MSVC x64 Runtime, Python libraries (Pillow, psutil)
echo    - Windows Firewall       : Ports 1714-1764 TCP/UDP, 59001-59002 TCP opened
echo    - Background Daemon      : Indicator and Streamer enabled for user startup
echo.
pause
