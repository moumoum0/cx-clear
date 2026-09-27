; CX Clear 安装脚本 —— per-user、现代向导、简体中文
; 由 Gradle 的 packageInnoSetup 任务调用；APP_DIR / APP_VERSION / OUTPUT_* 通过 /D 传入。

#ifndef APP_VERSION
  #define APP_VERSION "1.0.0"
#endif
#ifndef APP_DIR
  #define APP_DIR "..\gui\build\compose\binaries\main-release\app\CX Clear"
#endif
#ifndef OUTPUT_DIR
  #define OUTPUT_DIR "..\gui\build\compose\binaries\main\dist"
#endif
#ifndef OUTPUT_BASE
  #define OUTPUT_BASE "CXClear-{#APP_VERSION}-setup"
#endif
#ifndef APP_EXE
  #define APP_EXE "CX Clear.exe"
#endif

#define APP_NAME "CX Clear"
#define APP_PUBLISHER "CX Clear"
#define APP_ID "{{B5F8A2C1-3D4E-5F6A-7B8C-9D0E1F2A3B4C}"

[Setup]
AppId={#APP_ID}
AppName={#APP_NAME}
AppVersion={#APP_VERSION}
AppPublisher={#APP_PUBLISHER}
VersionInfoVersion={#APP_VERSION}
WizardStyle=modern
; per-user 安装：无 UAC、装到用户目录
PrivilegesRequired=lowest
ChangesEnvironment=yes
DefaultDirName={autopf}\{#APP_NAME}
DefaultGroupName={#APP_NAME}
DisableProgramGroupPage=yes
AllowNoIcons=yes
UninstallDisplayIcon={app}\app_icon.ico
UninstallDisplayName={#APP_NAME}
Compression=lzma2/fast
SolidCompression=no
OutputDir={#OUTPUT_DIR}
OutputBaseFilename={#OUTPUT_BASE}
SetupIconFile=app_icon.ico
; 显示「选择安装位置」页，让用户自定义安装目录
DisableDirPage=no
DisableWelcomePage=no

[Languages]
Name: "chinesesimp"; MessagesFile: "ChineseSimplified.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked
Name: "addtopath"; Description: "将 cxclear 添加到用户 PATH"; Flags: checkedonce

[Files]
Source: "{#APP_DIR}\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs ignoreversion
[Icons]
Name: "{group}\{#APP_NAME}"; Filename: "{app}\{#APP_EXE}"; IconFilename: "{app}\app_icon.ico"
Name: "{group}\{cm:UninstallProgram,{#APP_NAME}}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#APP_NAME}"; Filename: "{app}\{#APP_EXE}"; IconFilename: "{app}\app_icon.ico"; Tasks: desktopicon

[Run]
Filename: "{app}\{#APP_EXE}"; Description: "{cm:LaunchProgram,{#APP_NAME}}"; Flags: nowait postinstall skipifsilent

[Code]
function HasPathEntry(PathValue, Entry: string): Boolean;
var
  Item: string;
  Separator: Integer;
begin
  Result := False;
  while PathValue <> '' do
  begin
    Separator := Pos(';', PathValue);
    if Separator = 0 then
    begin
      Item := PathValue;
      PathValue := '';
    end
    else
    begin
      Item := Copy(PathValue, 1, Separator - 1);
      Delete(PathValue, 1, Separator);
    end;
    if CompareText(Trim(Item), Entry) = 0 then
    begin
      Result := True;
      Exit;
    end;
  end;
end;

function WithoutPathEntry(PathValue, Entry: string): string;
var
  Item: string;
  Separator: Integer;
begin
  Result := '';
  while PathValue <> '' do
  begin
    Separator := Pos(';', PathValue);
    if Separator = 0 then
    begin
      Item := PathValue;
      PathValue := '';
    end
    else
    begin
      Item := Copy(PathValue, 1, Separator - 1);
      Delete(PathValue, 1, Separator);
    end;
    if (Item <> '') and (CompareText(Trim(Item), Entry) <> 0) then
    begin
      if Result <> '' then Result := Result + ';';
      Result := Result + Item;
    end;
  end;
end;

procedure CurStepChanged(CurStep: TSetupStep);
var
  PathValue: string;
  AppPath: string;
begin
  if (CurStep <> ssPostInstall) or (not WizardIsTaskSelected('addtopath')) then Exit;
  AppPath := ExpandConstant('{app}');
  if not RegQueryStringValue(HKCU, 'Environment', 'Path', PathValue) then PathValue := '';
  if HasPathEntry(PathValue, AppPath) then Exit;
  if PathValue <> '' then PathValue := PathValue + ';';
  RegWriteExpandStringValue(HKCU, 'Environment', 'Path', PathValue + AppPath);
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  PathValue: string;
  AppPath: string;
begin
  if CurUninstallStep <> usPostUninstall then Exit;
  AppPath := ExpandConstant('{app}');
  if not RegQueryStringValue(HKCU, 'Environment', 'Path', PathValue) then Exit;
  if HasPathEntry(PathValue, AppPath) then
    RegWriteExpandStringValue(HKCU, 'Environment', 'Path', WithoutPathEntry(PathValue, AppPath));
end;
