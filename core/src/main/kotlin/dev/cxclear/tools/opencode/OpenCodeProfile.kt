package dev.cxclear.tools.opencode

import dev.cxclear.model.CleanTarget
import dev.cxclear.model.MatchKind
import dev.cxclear.model.Risk
import dev.cxclear.model.ToolProfile

/**
 * Open Code 的清理名单：`~/.local/share/opencode`、`~/.config/opencode`、
 * `~/.cache/opencode` 与 `%APPDATA%\ai.opencode.desktop` 四处数据根。
 */

/**
 * Open Code — 数据分三处：
 * - `~/.local/share/opencode`：SQLite 数据库（会话、消息）、storage 目录
 * - `~/.config/opencode`：配置文件
 * - `~/.cache/opencode`：缓存（models.json）
 * - `%APPDATA%\ai.opencode.desktop`：Electron 应用缓存
 *
 * Open Code 使用 SQLite 存储会话，不支持通过文件系统直接清理单个会话。
 * 主数据库 `opencode.db` 和 `opencode-local.db` 不提供清理项。
 */
val OpenCodeProfile = ToolProfile(
    id = "opencode",
    name = "Open Code",
    subtitle = "~/.local/share/opencode · AppData\\ai.opencode.desktop",
    baseDir = { opencodeHome() ?: opencodeAppData() },
    processNamePrefixes = setOf("opencode", "ai.opencode"),
    protectedPaths = ::opencodeProtectedPaths,
    spaceDirs = { listOfNotNull(opencodeHome(), opencodeConfigHome(), opencodeCacheHome(), opencodeAppData()) },
    targets = listOf(
        CleanTarget(
            id = "opencode.models-cache",
            label = "模型配置缓存",
            relPath = "models.json",
            kind = MatchKind.FILE,
            risk = Risk.SAFE,
            description = "~/.cache/opencode/models.json 模型列表缓存，下次启动会重新拉取。",
            baseDir = ::opencodeCacheHome,
        ),
        CleanTarget(
            id = "opencode.storage-snapshot",
            label = "存储快照",
            relPath = "storage/snapshot",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.OPTIONAL,
            description = "会话快照数据。删除后无法恢复历史快照。",
            baseDir = ::opencodeHome,
        ),
        CleanTarget(
            id = "opencode.storage-repos",
            label = "仓库缓存",
            relPath = "storage/repos",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "克隆的仓库缓存，需要时会重新拉取。",
            baseDir = ::opencodeHome,
        ),
        CleanTarget(
            id = "opencode.logs",
            label = "应用日志",
            relPath = "log",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "~/.local/share/opencode/log 下的应用日志，可直接清理。",
            baseDir = ::opencodeHome,
        ),
        CleanTarget(
            id = "opencode.desktop-cache",
            label = "桌面端 HTTP 缓存",
            relPath = "Cache",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "Electron 应用的 HTTP 磁盘缓存，下次启动自动重建。",
            baseDir = ::opencodeAppData,
        ),
        CleanTarget(
            id = "opencode.desktop-code-cache",
            label = "桌面端代码缓存",
            relPath = "Code Cache",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "V8 编译缓存。",
            baseDir = ::opencodeAppData,
        ),
        CleanTarget(
            id = "opencode.desktop-gpu-cache",
            label = "桌面端 GPU 缓存",
            relPath = "GPUCache",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "GPU 进程缓存。",
            baseDir = ::opencodeAppData,
        ),
        CleanTarget(
            id = "opencode.desktop-dawn-graphite",
            label = "桌面端 Dawn Graphite 缓存",
            relPath = "DawnGraphiteCache",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "Dawn Graphite 图形缓存。",
            baseDir = ::opencodeAppData,
        ),
        CleanTarget(
            id = "opencode.desktop-dawn-webgpu",
            label = "桌面端 Dawn WebGPU 缓存",
            relPath = "DawnWebGPUCache",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "Dawn WebGPU 图形缓存。",
            baseDir = ::opencodeAppData,
        ),
        CleanTarget(
            id = "opencode.desktop-crashpad",
            label = "崩溃转储",
            relPath = "Crashpad",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "崩溃报告与 minidump，仅用于问题诊断，可直接清理。",
            baseDir = ::opencodeAppData,
        ),
        CleanTarget(
            id = "opencode.desktop-logs",
            label = "桌面端日志",
            relPath = "logs",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "Electron 应用历史运行日志，可直接清理。",
            baseDir = ::opencodeAppData,
        ),
        CleanTarget(
            id = "opencode.desktop-blob-storage",
            label = "桌面端 Blob 存储",
            relPath = "blob_storage",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.OPTIONAL,
            description = "Chromium blob 存储，可能包含尚未持久化的页面或附件数据。删除后不可恢复。",
            baseDir = ::opencodeAppData,
        ),
        CleanTarget(
            id = "opencode.desktop-session-storage",
            label = "桌面端会话存储",
            relPath = "Session Storage",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.OPTIONAL,
            description = "浏览器会话存储数据，删除后不可恢复。",
            baseDir = ::opencodeAppData,
        ),
        CleanTarget(
            id = "opencode.desktop-local-storage",
            label = "桌面端本地存储",
            relPath = "Local Storage",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.OPTIONAL,
            description = "浏览器本地存储数据，可能包含应用状态。删除后不可恢复。",
            baseDir = ::opencodeAppData,
        ),
    ),
)
