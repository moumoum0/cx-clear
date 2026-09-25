package dev.cxclear.profiles

/**
 * 全工具的清理名单入口。
 * 各 profile 与路径 helper 见同目录的 XxxProfile.kt / ProfilePaths.kt。
 *
 * 新增一个工具时要同时改的地方：
 * 1. 新建 `XxxProfile.kt`，加 profile 并在这里追加进 `ALL_PROFILES`
 * 2. `chats/ChatModels.kt` 的 `ChatTool` 枚举加条目
 * 3. `chats/ChatGrouping.kt` 的 `projectLabel` 补 `when` 分支
 * 4. `chats/ChatMessageLoader.kt` 的 `loadChatMessages` 补 `when` 分支（或返回 `emptyList()`）
 * 5. `ui/components/ToolIcon.kt` 的 `ToolEntries` 添加条目（`src/main/composeResources/drawable/<tool>.svg`）
 * 6. 跑 `./gradlew test`：`ProfileRiskTest` 会检查 id 唯一、每个 profile 都有进程名、OPTIONAL 不默认勾
 */
val ALL_PROFILES = listOf(CodexProfile, ClaudeCodeProfile, CursorProfile, OpenCodeProfile, DeepSeekHermesProfile)
