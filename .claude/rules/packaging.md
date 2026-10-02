---
paths:
  - "packaging/**"
  - "gui/build.gradle.kts"
  - "cli/build.gradle.kts"
  - ".github/workflows/**"
  - "CHANGELOG.md"
---

# 打包与发版

## 前置

- `packageInnoSetup` 需要 Inno Setup 6（`winget install JRSoftware.InnoSetup`）和 MinGW g++（MSYS2 ucrt64），后者编两个启动器：`packaging/launcher.cpp` → `CX Clear.exe`，`packaging/cli_launcher.cpp` → `cxclear.exe`。
- 图标 `packaging/app_icon.ico` 已由 `gui/src/main/composeResources/drawable/hex_knot_arrow.svg` 转好提交，换图标才需 ImageMagick 重转。

## 坑

- Windows 不出 MSI：`targetFormats` 只留 `Dmg`，Windows 走 app-image + Inno Setup + `CX Clear.exe` 启动器。
- 启动器优先用捆绑 JRE，其次 `JAVA_HOME`，不遍历 PATH。

## 发版

手动触发 `.github/workflows/release.yml`。发之前：

1. 根 `build.gradle.kts` 的 `version` 改成目标版本。
2. `CHANGELOG.md` 新增标题为 `## v1.2.3` 的段落（脚本截取该段做 Release 正文，至少要有一条 `- ` 开头的条目）。

版本号含 `-` 会标成 prerelease。
