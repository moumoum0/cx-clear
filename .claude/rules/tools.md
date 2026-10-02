---
paths:
  - "core/src/main/kotlin/dev/cxclear/tools/**"
  - "core/src/main/kotlin/dev/cxclear/clean/SessionEntries.kt"
  - "core/src/test/kotlin/dev/cxclear/tools/**"
  - "gui/src/main/composeResources/drawable/**"
---

# 各软件的清理策略

- 安装目录、登录态、主配置、用户规则一律不进名单。补全名单按 `doc/清理名单完善流程.md` 走。
- DeepSeek Hermes 的 `%LOCALAPPDATA%\@deepseek-aidsh-desktop-updater` 目录名就是这样，不是漏了反斜杠，别「修」。npm 全局安装 `%APPDATA%\npm\node_modules\@deepseek-ai\dsh` 有意不进名单。

## 新增工具

1. 在 `tools/<id>/` 建 profile、路径，有会话再加扫描 / 消息 / 删除，最后写一个 `ToolPlugin`。图标放 `gui/src/main/composeResources/drawable/<tool>.svg`；项目名编码写在 `ChatTool.projectLabel`。
2. 把 Plugin 追加进 `tools/AllProfiles.kt` 的 `TOOLS`。暂时没有会话的工具也要登记（scan / load 用默认空实现），否则扫描时会被静默跳过。只在部分系统可用的用 `supportedOs` 限定。
3. `ProfileRiskTest` 会卡这几条：OPTIONAL 项不能默认勾、`id` 全局唯一、每个 profile 都要有进程名、Codex 活跃 sqlite 必须永久保护。

## 会话不是文件的两个软件

- Cursor：`cursor/CursorStore.kt` 按 ComposerService 的写入顺序改 `state.vscdb`——删 `composerData:` / `bubbleId:` / `checkpointId:` 等前缀键、清 `composerHeaders`、过滤 `composer.composerData` 索引并 bump 版本号。`agentKv:blob` 无法归属到单条会话，不碰。
- Open Code：会话在 `opencode.db` 的 `session` 表，删记录靠级联，另外清 `storage/session_diff/<id>.json`。
