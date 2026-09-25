@echo off
setlocal EnableDelayedExpansion

title Building KDE Connect Windows Installer (.exe)
color 0A

echo.
echo  ========================================================================
echo    Building KDE Connect Windows Unified Installer (.exe)
echo  ========================================================================
echo.

set "ISCC_PATH="
if exist "%LOCALAPPDATA%\Programs\Inno Setup 6\ISCC.exe" (
    set "ISCC_PATH=%LOCALAPPDATA%\Programs\Inno Setup 6\ISCC.exe"
) else if exist "%ProgramFiles(x86)%\Inno Setup 6\ISCC.exe" (
    set "ISCC_PATH=%ProgramFiles(x86)%\Inno Setup 6\ISCC.exe"
) else if exist "%ProgramFiles%\Inno Setup 6\ISCC.exe" (
    set "ISCC_PATH=%ProgramFiles%\Inno Setup 6\ISCC.exe"
) else (
    for /f "delims=" %%I in ('where iscc.exe 2^^^>nul') do (
        set "ISCC_PATH=%%I"
    )
)

if not defined ISCC_PATH (
    echo  [!] Inno Setup 6 compiler not found in standard paths.
    echo  [*] Installing Inno Setup via winget...
    winget install JRSoftware.InnoSetup --silent --accept-package-agreements --accept-source-agreements
    if exist "%LOCALAPPDATA%\Programs\Inno Setup 6\ISCC.exe" (
        set "ISCC_PATH=%LOCALAPPDATA%\Programs\Inno Setup 6\ISCC.exe"
    ) else if exist "%ProgramFiles(x86)%\Inno Setup 6\ISCC.exe" (
        set "ISCC_PATH=%ProgramFiles(x86)%\Inno Setup 6\ISCC.exe"
    ) else if exist "%ProgramFiles%\Inno Setup 6\ISCC.exe" (
        set "ISCC_PATH=%ProgramFiles%\Inno Setup 6\ISCC.exe"
    )
)

if not defined ISCC_PATH (
    echo  [-] Error: Please install Inno Setup 6 from https://jrsoftware.org/isinfo.php
    pause
    exit /b 1
)

echo  [+] Inno Setup Compiler: "%ISCC_PATH%"
echo.

if not exist "%~dp0..\bin\kdeconnect-app.exe" (
    echo  [!] Warning: %~dp0..\bin\kdeconnect-app.exe not found.
    echo      Attempting to restore binaries from installer cache or official release...
    if exist "%~dp0downloads\kdeconnect-kde-release_*.7z" (
        for /f "delims=" %%A in ('dir /b /s "%~dp0downloads\kdeconnect-kde-release_*.7z"') do (
            echo  [*] Extracting %%~nxA to project root...
            powershell -Command "& { $seven = (Get-ChildItem -Path 'C:\Program Files*', 'C:\Users\*\AppData\Local\Programs*' -Filter '7z.exe' -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1).FullName; if ($seven) { & $seven x '%%A' -o'%~dp0..\' -y } }"
        )
    )
)

if not exist "%~dp0..\bin\kdeconnect-app.exe" (
    echo  [-] Error: Required binaries not found in %~dp0..\bin.
    pause
    exit /b 1
)

echo  [*] Compiling unified installer package...
"%ISCC_PATH%" "%~dp0kdeconnect.iss"

if %errorlevel% equ 0 (
    echo.
    echo  ========================================================================
    echo    [SUCCESS] Single installer generated at: dist\KDEConnect-Setup-x64.exe
    echo  ========================================================================
    echo.
) else (
    echo  [-] Compilation failed. Check errors above.
)

pause
