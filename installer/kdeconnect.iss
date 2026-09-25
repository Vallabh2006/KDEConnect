#define MyAppName "KDE Connect"
#define MyAppVersion "24.0"
#define MyAppPublisher "KDE Community"
#define MyAppURL "https://kdeconnect.kde.org/"
#define MyAppExeName "kdeconnect-app.exe"
#define MyAppIndicatorExe "kdeconnect-indicator.exe"
#define SetupOutputName "KDEConnect-Setup-x64"

[Setup]
AppId={{D814AC22-86DE-494B-96DE-9A6836F85E0E}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
AppPublisherURL={#MyAppURL}
AppSupportURL={#MyAppURL}
AppUpdatesURL={#MyAppURL}
DefaultDirName={localappdata}\{#MyAppName}
DefaultGroupName={#MyAppName}
AllowNoIcons=yes
OutputDir=..\dist
OutputBaseFilename={#SetupOutputName}
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog commandline
UninstallDisplayIcon={app}\bin\{#MyAppExeName}
UsePreviousAppDir=no
DisableDirPage=no
SetupLogging=yes
VersionInfoVersion=24.0.0.0
VersionInfoProductVersion=24.0.0.0
VersionInfoCompany={#MyAppPublisher}
VersionInfoDescription=KDE Connect for Windows Installer
VersionInfoProductName={#MyAppName}
VersionInfoCopyright=Copyright (C) KDE Community

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"
Name: "autostart"; Description: "Start KDE Connect Indicator automatically at Windows login"; GroupDescription: "Startup:"
Name: "startstreamer"; Description: "Start Screen Streamer background service automatically at Windows login"; GroupDescription: "Startup:"
Name: "installdeps"; Description: "Install required Microsoft Visual C++ runtime if missing"; GroupDescription: "Dependencies:"

[Files]
Source: "..\bin\*"; DestDir: "{app}\bin"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "..\lib\*"; DestDir: "{app}\lib"; Flags: ignoreversion recursesubdirs createallsubdirs skipifsourcedoesntexist
Source: "..\share\*"; DestDir: "{app}\share"; Flags: ignoreversion recursesubdirs createallsubdirs skipifsourcedoesntexist
Source: "..\qml\*"; DestDir: "{app}\qml"; Flags: ignoreversion recursesubdirs createallsubdirs skipifsourcedoesntexist
Source: "..\etc\*"; DestDir: "{app}\etc"; Flags: ignoreversion recursesubdirs createallsubdirs skipifsourcedoesntexist
Source: "downloads\vc_redist.x64.exe"; DestDir: "{tmp}"; Flags: ignoreversion deleteafterinstall; Check: VCRedistNeedsInstall
Source: "tools\kdeconnect-streamer.exe"; DestDir: "{app}\tools"; Flags: ignoreversion
Source: "tools\screen_streamer.py"; DestDir: "{app}\tools"; Flags: ignoreversion
Source: "tools\app-debug.apk"; DestDir: "{app}\tools"; Flags: ignoreversion
Source: "tools\streamer_silent.vbs"; DestDir: "{app}\tools"; Flags: ignoreversion
Source: "tools\streamer_service.bat"; DestDir: "{app}\tools"; Flags: ignoreversion
Source: "tools\setup_adb_android.bat"; DestDir: "{app}\tools"; Flags: ignoreversion

[Icons]
Name: "{group}\{#MyAppName}"; Filename: "{app}\bin\{#MyAppExeName}"; WorkingDir: "{app}\bin"
Name: "{group}\KDE Connect Indicator"; Filename: "{app}\bin\{#MyAppIndicatorExe}"; WorkingDir: "{app}\bin"; Flags: createonlyiffileexists
Name: "{group}\KDE Connect Settings"; Filename: "{app}\bin\kdeconnect-settings.exe"; WorkingDir: "{app}\bin"; Flags: createonlyiffileexists
Name: "{group}\KDE Connect SMS"; Filename: "{app}\bin\kdeconnect-sms.exe"; WorkingDir: "{app}\bin"; Flags: createonlyiffileexists
Name: "{group}\Start Screen Streamer"; Filename: "{app}\tools\kdeconnect-streamer.exe"; WorkingDir: "{app}\tools"; IconFilename: "{app}\bin\{#MyAppExeName}"
Name: "{group}\Streamer Console (Debug)"; Filename: "{app}\tools\streamer_service.bat"; WorkingDir: "{app}\tools"
Name: "{group}\Deploy Android Client (ADB)"; Filename: "{app}\tools\setup_adb_android.bat"; WorkingDir: "{app}\tools"
Name: "{group}\{cm:UninstallProgram,{#MyAppName}}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\bin\{#MyAppExeName}"; WorkingDir: "{app}\bin"; Tasks: desktopicon

[Registry]
Root: HKCU; Subkey: "SOFTWARE\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "KDEConnectIndicator"; ValueData: """{app}\bin\{#MyAppIndicatorExe}"""; Flags: uninsdeletevalue; Tasks: autostart
Root: HKCU; Subkey: "SOFTWARE\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "KDEConnectStreamer"; ValueData: """{app}\tools\kdeconnect-streamer.exe"""; Flags: uninsdeletevalue; Tasks: startstreamer

[Run]
Filename: "{app}\bin\{#MyAppIndicatorExe}"; Description: "Launch KDE Connect Indicator"; Flags: nowait postinstall skipifsilent; Tasks: autostart
Filename: "{app}\tools\kdeconnect-streamer.exe"; Description: "Launch Screen Streamer background service"; Flags: nowait postinstall skipifsilent; Tasks: startstreamer

[UninstallDelete]
Type: filesandordirs; Name: "{app}\bin"
Type: filesandordirs; Name: "{app}\lib"
Type: filesandordirs; Name: "{app}\share"
Type: filesandordirs; Name: "{app}\qml"
Type: filesandordirs; Name: "{app}\etc"
Type: filesandordirs; Name: "{app}\tools"
Type: filesandordirs; Name: "{app}\installers"
Type: filesandordirs; Name: "{app}"
Type: filesandordirs; Name: "{autopf}\KDE Connect"
Type: filesandordirs; Name: "{autopf}\KDEConnect"
Type: filesandordirs; Name: "{autopf32}\KDE Connect"
Type: filesandordirs; Name: "{autopf32}\KDEConnect"
Type: filesandordirs; Name: "C:\KDE Connect"
Type: filesandordirs; Name: "C:\KDEConnect"

[Code]
var
  MaintenancePage: TInputOptionWizardPage;
  IsMaintenanceMode: Boolean;
  ExistingInstallPath: string;

function VCRedistNeedsInstall: Boolean;
var
  Installed: Cardinal;
begin
  Result := True;
  if RegQueryDWordValue(HKLM, "SOFTWARE\Microsoft\VisualStudio\14.0\VC\Runtimes\x64", "Installed", Installed) then
  begin
    if Installed = 1 then
      Result := False;
  end;
  if Result and RegQueryDWordValue(HKLM, "SOFTWARE\WOW6432Node\Microsoft\VisualStudio\14.0\VC\Runtimes\x64", "Installed", Installed) then
  begin
    if Installed = 1 then
      Result := False;
  end;
end;

function GetExistingInstallDir: string;
var
  InstPath: string;
begin
  InstPath := "";
  if RegQueryStringValue(HKLM, "SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\{D814AC22-86DE-494B-96DE-9A6836F85E0E}_is1", "InstallLocation", InstPath) then
  begin
    if (InstPath <> "") and DirExists(InstPath) then
    begin
      Result := InstPath;
      Exit;
    end;
  end;
  if RegQueryStringValue(HKCU, "SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\{D814AC22-86DE-494B-96DE-9A6836F85E0E}_is1", "InstallLocation", InstPath) then
  begin
    if (InstPath <> "") and DirExists(InstPath) then
    begin
      Result := InstPath;
      Exit;
    end;
  end;
  if RegQueryStringValue(HKLM, "SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\KDEConnect", "InstallLocation", InstPath) then
  begin
    if (InstPath <> "") and DirExists(InstPath) then
    begin
      Result := InstPath;
      Exit;
    end;
  end;
  if RegQueryStringValue(HKCU, "SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\KDEConnect", "InstallLocation", InstPath) then
  begin
    if (InstPath <> "") and DirExists(InstPath) then
    begin
      Result := InstPath;
      Exit;
    end;
  end;
  if FileExists(ExpandConstant("{autopf}\KDE Connect\bin\kdeconnect-app.exe")) then
  begin
    Result := ExpandConstant("{autopf}\KDE Connect");
    Exit;
  end;
  if FileExists("C:\KDE Connect\bin\kdeconnect-app.exe") then
  begin
    Result := "C:\KDE Connect";
    Exit;
  end;
  Result := "";
end;

procedure StopKDEProcesses;
var
  ResultCode: Integer;
begin
  Exec("taskkill.exe", "/F /IM kdeconnect-app.exe", "", SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Exec("taskkill.exe", "/F /IM kdeconnectd.exe", "", SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Exec("taskkill.exe", "/F /IM kdeconnect-indicator.exe", "", SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Exec("taskkill.exe", "/F /IM kdeconnect-settings.exe", "", SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Exec("taskkill.exe", "/F /IM kdeconnect-handler.exe", "", SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Exec("taskkill.exe", "/F /IM kdeconnect-sms.exe", "", SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Exec("taskkill.exe", "/F /IM kdeconnect-streamer.exe", "", SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Exec("powershell.exe", "-NoProfile -Command "Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match 'screen_streamer\.py' -or $_.CommandLine -match 'streamer_silent\.vbs' -or $_.CommandLine -match 'kdeconnect' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }"", "", SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Exec("cmd.exe", "/c taskkill /F /FI "WINDOWTITLE eq KDE Connect Screen Streamer"", "", SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Exec("sc.exe", "stop KDEConnectStreamer", "", SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Exec("sc.exe", "delete KDEConnectStreamer", "", SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Exec("schtasks.exe", "/Delete /TN "KDEConnectStreamer" /F", "", SW_HIDE, ewWaitUntilTerminated, ResultCode);
end;

procedure InstallDependencies;
var
  ResultCode: Integer;
begin
  if VCRedistNeedsInstall then
  begin
    if FileExists(ExpandConstant("{tmp}\vc_redist.x64.exe")) then
    begin
      Exec(ExpandConstant("{tmp}\vc_redist.x64.exe"), "/install /quiet /norestart", "", SW_SHOW, ewWaitUntilTerminated, ResultCode);
    end;
  end;
end;

procedure ConfigureFirewall;
var
  ResultCode: Integer;
begin
  Exec("netsh.exe", "advfirewall firewall delete rule name="KDE Connect Daemon (TCP-In)"", "", SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Exec("netsh.exe", "advfirewall firewall delete rule name="KDE Connect Daemon (UDP-In)"", "", SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Exec("netsh.exe", "advfirewall firewall delete rule name="KDE Connect Streamer (TCP-In)"", "", SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Exec("netsh.exe", "advfirewall firewall delete rule name="KDE Connect Streamer Audio (TCP-In)"", "", SW_HIDE, ewWaitUntilTerminated, ResultCode);

  Exec("netsh.exe", "advfirewall firewall add rule name="KDE Connect Daemon (TCP-In)" dir=in action=allow protocol=TCP localport=1714-1764 profile=any", "", SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Exec("netsh.exe", "advfirewall firewall add rule name="KDE Connect Daemon (UDP-In)" dir=in action=allow protocol=UDP localport=1714-1764 profile=any", "", SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Exec("netsh.exe", "advfirewall firewall add rule name="KDE Connect Streamer (TCP-In)" dir=in action=allow protocol=TCP localport=59001 profile=any", "", SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Exec("netsh.exe", "advfirewall firewall add rule name="KDE Connect Streamer Audio (TCP-In)" dir=in action=allow protocol=TCP localport=59002 profile=any", "", SW_HIDE, ewWaitUntilTerminated, ResultCode);
end;

procedure RemoveFirewall;
var
  ResultCode: Integer;
begin
  Exec("netsh.exe", "advfirewall firewall delete rule name="KDE Connect Daemon (TCP-In)"", "", SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Exec("netsh.exe", "advfirewall firewall delete rule name="KDE Connect Daemon (UDP-In)"", "", SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Exec("netsh.exe", "advfirewall firewall delete rule name="KDE Connect Streamer (TCP-In)"", "", SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Exec("netsh.exe", "advfirewall firewall delete rule name="KDE Connect Streamer Audio (TCP-In)"", "", SW_HIDE, ewWaitUntilTerminated, ResultCode);
end;

procedure PerformFullUninstall;
var
  ResultCode: Integer;
  UninsExe: string;
  CleanupCmd: string;
begin
  if MsgBox("Are you sure you want to completely uninstall KDE Connect and all of its components?", mbConfirmation, MB_YESNO) = IDYES then
  begin
    StopKDEProcesses;
    RemoveFirewall;

    RegDeleteValue(HKLM, "SOFTWARE\Microsoft\Windows\CurrentVersion\Run", "KDEConnectIndicator");
    RegDeleteValue(HKLM, "SOFTWARE\Microsoft\Windows\CurrentVersion\Run", "KDEConnectStreamer");
    RegDeleteValue(HKCU, "SOFTWARE\Microsoft\Windows\CurrentVersion\Run", "KDEConnectIndicator");
    RegDeleteValue(HKCU, "SOFTWARE\Microsoft\Windows\CurrentVersion\Run", "KDEConnectStreamer");

    UninsExe := ExpandConstant(ExistingInstallPath + "\unins000.exe");
    if FileExists(UninsExe) then
    begin
      Exec(UninsExe, "/SILENT /VERYSILENT /SUPPRESSMSGBOXES /NORESTART", "", SW_SHOW, ewWaitUntilTerminated, ResultCode);
    end;

    DelTree(ExpandConstant(ExistingInstallPath + "\bin"), True, True, True);
    DelTree(ExpandConstant(ExistingInstallPath + "\lib"), True, True, True);
    DelTree(ExpandConstant(ExistingInstallPath + "\share"), True, True, True);
    DelTree(ExpandConstant(ExistingInstallPath + "\qml"), True, True, True);
    DelTree(ExpandConstant(ExistingInstallPath + "\etc"), True, True, True);
    DelTree(ExpandConstant(ExistingInstallPath + "\tools"), True, True, True);
    DelTree(ExpandConstant(ExistingInstallPath + "\installers"), True, True, True);
    DelTree(ExistingInstallPath, True, True, True);

    DelTree(ExpandConstant("{autopf}\KDE Connect\bin"), True, True, True);
    DelTree(ExpandConstant("{autopf}\KDE Connect\lib"), True, True, True);
    DelTree(ExpandConstant("{autopf}\KDE Connect\share"), True, True, True);
    DelTree(ExpandConstant("{autopf}\KDE Connect\qml"), True, True, True);
    DelTree(ExpandConstant("{autopf}\KDE Connect\etc"), True, True, True);
    DelTree(ExpandConstant("{autopf}\KDE Connect\tools"), True, True, True);
    DelTree(ExpandConstant("{autopf}\KDE Connect\installers"), True, True, True);
    DelTree(ExpandConstant("{autopf}\KDE Connect"), True, True, True);
    DelTree(ExpandConstant("{autopf}\KDEConnect"), True, True, True);
    DelTree(ExpandConstant("{autopf32}\KDE Connect"), True, True, True);
    DelTree(ExpandConstant("{autopf32}\KDEConnect"), True, True, True);
    DelTree("C:\KDE Connect", True, True, True);
    DelTree("C:\KDEConnect", True, True, True);

    RegDeleteKeyIncludingSubkeys(HKLM, "SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\{D814AC22-86DE-494B-96DE-9A6836F85E0E}_is1");
    RegDeleteKeyIncludingSubkeys(HKCU, "SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\{D814AC22-86DE-494B-96DE-9A6836F85E0E}_is1");
    RegDeleteKeyIncludingSubkeys(HKLM, "SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\KDEConnect");
    RegDeleteKeyIncludingSubkeys(HKCU, "SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\KDEConnect");

    CleanupCmd := "/c timeout /t 2 /nobreak >nul & " +
      "rmdir /s /q "" + ExistingInstallPath + "" >nul 2>&1 & " +
      "rmdir /s /q "" + ExpandConstant("{autopf}\KDE Connect") + "" >nul 2>&1 & " +
      "rmdir /s /q "C:\KDE Connect" >nul 2>&1 & " +
      "rmdir /s /q "C:\KDEConnect" >nul 2>&1";
    Exec("cmd.exe", CleanupCmd, "", SW_HIDE, ewNoWait, ResultCode);

    MsgBox("KDE Connect has been successfully uninstalled from this computer.", mbInformation, MB_OK);
    WizardForm.Close;
  end;
end;

procedure InitializeWizard;
begin
  ExistingInstallPath := GetExistingInstallDir;
  IsMaintenanceMode := (ExistingInstallPath <> "");

  if IsMaintenanceMode then
  begin
    MaintenancePage := CreateInputOptionPage(
      wpWelcome,
      "Maintenance Options",
      "Select setup mode",
      "An existing installation of KDE Connect was detected at:" + #13#10 +
      ExistingInstallPath + #13#10#13#10 +
      "Please select the operation you would like to perform:",
      True, False
    );
    MaintenancePage.Add("&Modify - Change installation directory or selected features");
    MaintenancePage.Add("&Repair - Reinstall files, restore firewall rules, and dependencies");
    MaintenancePage.Add("&Uninstall - Remove KDE Connect from this computer");
    MaintenancePage.SelectedValueIndex := 0;
  end;
end;

function ShouldSkipPage(PageID: Integer): Boolean;
begin
  Result := False;
  if IsMaintenanceMode and (MaintenancePage <> nil) then
  begin
    if (PageID = wpSelectDir) and (MaintenancePage.SelectedValueIndex = 1) then
      Result := True;
  end;
end;

function NextButtonClick(CurPageID: Integer): Boolean;
begin
  Result := True;
  if IsMaintenanceMode and (MaintenancePage <> nil) and (CurPageID = MaintenancePage.ID) then
  begin
    if MaintenancePage.SelectedValueIndex = 2 then
    begin
      PerformFullUninstall;
      Result := False;
      Exit;
    end
    else if MaintenancePage.SelectedValueIndex = 0 then
    begin
      WizardForm.DirEdit.Text := ExistingInstallPath;
    end
    else if MaintenancePage.SelectedValueIndex = 1 then
    begin
      WizardForm.DirEdit.Text := ExistingInstallPath;
    end;
  end;
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if CurStep = ssInstall then
  begin
    StopKDEProcesses;
    if IsMaintenanceMode and (MaintenancePage <> nil) and (MaintenancePage.SelectedValueIndex = 0) then
    begin
      if (ExistingInstallPath <> "") and (CompareText(ExistingInstallPath, ExpandConstant("{app}")) <> 0) then
      begin
        DelTree(ExistingInstallPath, True, True, True);
      end;
    end;
  end
  else if CurStep = ssPostInstall then
  begin
    if WizardIsTaskSelected("installdeps") then
    begin
      InstallDependencies;
    end;
    ConfigureFirewall;

    RegWriteDWordValue(HKLM, "SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\{D814AC22-86DE-494B-96DE-9A6836F85E0E}_is1", "NoModify", 0);
  end;
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  ResultCode: Integer;
  CleanupCmd: string;
begin
  if CurUninstallStep = usUninstall then
  begin
    StopKDEProcesses;
  end
  else if CurUninstallStep = usPostUninstall then
  begin
    RemoveFirewall;
    DelTree(ExpandConstant("{app}\bin"), True, True, True);
    DelTree(ExpandConstant("{app}\lib"), True, True, True);
    DelTree(ExpandConstant("{app}\share"), True, True, True);
    DelTree(ExpandConstant("{app}\qml"), True, True, True);
    DelTree(ExpandConstant("{app}\etc"), True, True, True);
    DelTree(ExpandConstant("{app}\tools"), True, True, True);
    DelTree(ExpandConstant("{app}\installers"), True, True, True);
    DelTree(ExpandConstant("{app}"), True, True, True);
    DelTree(ExpandConstant("{autopf}\KDE Connect"), True, True, True);
    DelTree(ExpandConstant("{autopf}\KDEConnect"), True, True, True);
    DelTree(ExpandConstant("{autopf32}\KDE Connect"), True, True, True);
    DelTree(ExpandConstant("{autopf32}\KDEConnect"), True, True, True);
    DelTree("C:\KDE Connect", True, True, True);
    DelTree("C:\KDEConnect", True, True, True);

    CleanupCmd := "/c timeout /t 2 /nobreak >nul & " +
      "rmdir /s /q "" + ExpandConstant("{app}") + "" >nul 2>&1 & " +
      "rmdir /s /q "" + ExpandConstant("{autopf}\KDE Connect") + "" >nul 2>&1 & " +
      "rmdir /s /q "C:\KDE Connect" >nul 2>&1 & " +
      "rmdir /s /q "C:\KDEConnect" >nul 2>&1";
    Exec("cmd.exe", CleanupCmd, "", SW_HIDE, ewNoWait, ResultCode);
  end;
end;
