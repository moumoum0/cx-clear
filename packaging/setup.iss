; CX Clear 安装脚本 —— per-user、现代向导、简体中文
; 由 Gradle 的 packageInnoSetup 任务调用；APP_DIR / APP_VERSION 通过 /D 传入。

#ifndef APP_VERSION
  #define APP_VERSION "1.0.0"
#endif
#ifndef APP_DIR
  #define APP_DIR "..\build\compose\binaries\main\app\CX Clear"
#endif

#define APP_NAME "CX Clear"
#define APP_EXE "CX Clear.exe"
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
DefaultDirName={autopf}\{#APP_NAME}
DefaultGroupName={#APP_NAME}
DisableProgramGroupPage=yes
AllowNoIcons=yes
UninstallDisplayIcon={app}\{#APP_EXE}
UninstallDisplayName={#APP_NAME}
Compression=lzma2/fast
SolidCompression=no
OutputDir=..\build\compose\binaries\main\innosetup
OutputBaseFilename=CXClear-{#APP_VERSION}-setup
SetupIconFile=app_icon.ico
; 显示「选择安装位置」页，让用户自定义安装目录
DisableDirPage=no
DisableWelcomePage=no

[Languages]
Name: "chinesesimp"; MessagesFile: "ChineseSimplified.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
Source: "{#APP_DIR}\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs ignoreversion

[Icons]
Name: "{group}\{#APP_NAME}"; Filename: "{app}\{#APP_EXE}"
Name: "{group}\{cm:UninstallProgram,{#APP_NAME}}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#APP_NAME}"; Filename: "{app}\{#APP_EXE}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#APP_EXE}"; Description: "{cm:LaunchProgram,{#APP_NAME}}"; Flags: nowait postinstall skipifsilent
