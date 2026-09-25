@echo off
setlocal EnableDelayedExpansion

title Building KDE Connect Windows MSI Installer
color 0B

echo.
echo  ========================================================================
echo    Building KDE Connect MSI Package (.msi) with WiX Toolset
echo  ========================================================================
echo.

:: ----------------------------------------------------------------------------
:: 1. Locate WiX Toolset (candle.exe, light.exe, heat.exe)
:: ----------------------------------------------------------------------------
set "WIX_DIR="

if defined WIX (
    if exist "%WIX%bin\candle.exe" set "WIX_DIR=%WIX%bin"
)

if not defined WIX_DIR (
    if exist "%ProgramFiles(x86)%\WiX Toolset v3.11\bin\candle.exe" (
        set "WIX_DIR=%ProgramFiles(x86)%\WiX Toolset v3.11\bin"
    ) else if exist "%ProgramFiles%\WiX Toolset v3.11\bin\candle.exe" (
        set "WIX_DIR=%ProgramFiles%\WiX Toolset v3.11\bin"
    ) else if exist "%ProgramFiles(x86)%\WiX Toolset v3.14\bin\candle.exe" (
        set "WIX_DIR=%ProgramFiles(x86)%\WiX Toolset v3.14\bin"
    )
)

:: If WiX not found, attempt auto-installation via winget
if not defined WIX_DIR (
    echo  [!] WiX Toolset compiler not found in standard paths.
    echo  [*] Attempting to install WiX Toolset via winget...
    winget install WiX.Toolset.v3 --silent --accept-package-agreements --accept-source-agreements
    
    if exist "%ProgramFiles(x86)%\WiX Toolset v3.14\bin\candle.exe" (
        set "WIX_DIR=%ProgramFiles(x86)%\WiX Toolset v3.14\bin"
    ) else if exist "%ProgramFiles(x86)%\WiX Toolset v3.11\bin\candle.exe" (
        set "WIX_DIR=%ProgramFiles(x86)%\WiX Toolset v3.11\bin"
    ) else if exist "%ProgramFiles%\WiX Toolset v3.11\bin\candle.exe" (
        set "WIX_DIR=%ProgramFiles%\WiX Toolset v3.11\bin"
    )
)

if not defined WIX_DIR (
    echo.
    echo  [-] Error: WiX Toolset v3 is required to generate .msi packages.
    echo      Please install it from https://wixtoolset.org/releases/
    echo      or run: winget install WiX.Toolset.v3
    echo.
    pause
    exit /b 1
)

echo  [+] WiX Toolset found at: "%WIX_DIR%"
echo.

:: ----------------------------------------------------------------------------
:: 2. Setup Build Output Directory
:: ----------------------------------------------------------------------------
set "PROJECT_ROOT=%~dp0..\"
set "OUTPUT_DIR=%PROJECT_ROOT%dist"
set "BUILD_TMP=%~dp0obj"

if not exist "%OUTPUT_DIR%" mkdir "%OUTPUT_DIR%"
if not exist "%BUILD_TMP%" mkdir "%BUILD_TMP%"

:: Check source binary existence
if not exist "%PROJECT_ROOT%bin\kdeconnect-app.exe" (
    echo  [!] Warning: %PROJECT_ROOT%bin\kdeconnect-app.exe not found.
    echo      Ensure your compiled binaries are present in bin\ before building.
    echo.
)

:: ----------------------------------------------------------------------------
:: 3. Harvest Files using Heat.exe
:: ----------------------------------------------------------------------------
echo  [*] Harvesting application binaries and assets...

"%WIX_DIR%\heat.exe" dir "%PROJECT_ROOT%bin" -cg AppBinGroup -dr BinFolder -scom -sreg -srd -var "var.SourceDir\bin" -ag -out "%BUILD_TMP%\bin_files.wxs"
if exist "%PROJECT_ROOT%lib" (
    "%WIX_DIR%\heat.exe" dir "%PROJECT_ROOT%lib" -cg AppLibGroup -dr LibFolder -scom -sreg -srd -var "var.SourceDir\lib" -ag -out "%BUILD_TMP%\lib_files.wxs"
)
if exist "%PROJECT_ROOT%share" (
    "%WIX_DIR%\heat.exe" dir "%PROJECT_ROOT%share" -cg AppShareGroup -dr ShareFolder -scom -sreg -srd -var "var.SourceDir\share" -ag -out "%BUILD_TMP%\share_files.wxs"
)
if exist "%PROJECT_ROOT%qml" (
    "%WIX_DIR%\heat.exe" dir "%PROJECT_ROOT%qml" -cg AppQmlGroup -dr QmlFolder -scom -sreg -srd -var "var.SourceDir\qml" -ag -out "%BUILD_TMP%\qml_files.wxs"
)

:: Create combined component group aggregator
(
    echo ^<?xml version="1.0" encoding="UTF-8"?^>
    echo ^<Wix xmlns="http://schemas.microsoft.com/wix/2006/wi"^>
    echo   ^<Fragment^>
    echo     ^<ComponentGroup Id="AppFilesGroup"^>
    echo       ^<ComponentGroupRef Id="AppBinGroup" /^>
    if exist "%PROJECT_ROOT%lib" echo       ^<ComponentGroupRef Id="AppLibGroup" /^>
    if exist "%PROJECT_ROOT%share" echo       ^<ComponentGroupRef Id="AppShareGroup" /^>
    if exist "%PROJECT_ROOT%qml" echo       ^<ComponentGroupRef Id="AppQmlGroup" /^>
    echo     ^</ComponentGroup^>
    echo   ^</Fragment^>
    echo ^</Wix^>
) > "%BUILD_TMP%\files_aggregator.wxs"

:: ----------------------------------------------------------------------------
:: 4. Compile with Candle.exe
:: ----------------------------------------------------------------------------
echo  [*] Compiling WiX manifests...

set "WXS_FILES="%~dp0kdeconnect.wxs" "%BUILD_TMP%\files_aggregator.wxs" "%BUILD_TMP%\bin_files.wxs""
if exist "%PROJECT_ROOT%lib" set "WXS_FILES=!WXS_FILES! "%BUILD_TMP%\lib_files.wxs""
if exist "%PROJECT_ROOT%share" set "WXS_FILES=!WXS_FILES! "%BUILD_TMP%\share_files.wxs""
if exist "%PROJECT_ROOT%qml" set "WXS_FILES=!WXS_FILES! "%BUILD_TMP%\qml_files.wxs""

"%WIX_DIR%\candle.exe" -arch x64 -ext WixUIExtension -ext WixUtilExtension -dSourceDir="%PROJECT_ROOT%." -out "%BUILD_TMP%\" !WXS_FILES!

if %errorlevel% neq 0 (
    echo  [-] Error: Candle compilation failed.
    pause
    exit /b %errorlevel%
)

:: ----------------------------------------------------------------------------
:: 5. Link with Light.exe
:: ----------------------------------------------------------------------------
echo  [*] Linking MSI Package...

set "WIXOBJ_FILES="%BUILD_TMP%\*.wixobj""

"%WIX_DIR%\light.exe" -ext WixUIExtension -ext WixUtilExtension -cultures:en-us -sval -out "%OUTPUT_DIR%\KDEConnect-Setup-x64.msi" %WIXOBJ_FILES%

if %errorlevel% equ 0 (
    echo.
    echo  ========================================================================
    echo    [SUCCESS] Single MSI package generated successfully!
    echo    Output: dist\KDEConnect-Setup-x64.msi
    echo  ========================================================================
    echo.
    
    :: Cleanup intermediate build objects
    if exist "%BUILD_TMP%" rmdir /S /Q "%BUILD_TMP%" >nul 2>&1
) else (
    echo  [-] Linking failed. Check output messages above.
)

pause
