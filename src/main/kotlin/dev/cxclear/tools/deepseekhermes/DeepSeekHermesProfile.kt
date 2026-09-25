package dev.cxclear.tools.deepseekhermes

import dev.cxclear.model.CleanTarget
import dev.cxclear.model.MatchKind
import dev.cxclear.model.Risk
import dev.cxclear.model.ToolProfile

/**
 * DeepSeek Hermes 的清理名单：`~/.dsh`、桌面版 Electron 数据与更新缓存三处数据根。
 */

/**
 * DeepSeek Hermes — 数据分四处：
 * - `~/.dsh`：凭据、会话、存储、profiles（插件 node_modules）、dsh-runtimes（约 270MB）
 * - `%APPDATA%\@deepseek-ai\dsh-desktop`：桌面版 Electron 缓存
 * - `%LOCALAPPDATA%\@deepseek-aidsh-desktop-updater`：更新缓存（约 550MB 待安装包）
 * - npm 全局安装：`%APPDATA%\npm\node_modules\@deepseek-ai\dsh`（另一套 CLI，约 220MB）
 *
 * 不清理 npm 全局安装（用户可能用 `npm uninstall -g` 管理）。
 * 不清理桌面版安装目录（多数在自定义位置如 `D:\app data\dsh`，不在标准路径）。
 */
val DeepSeekHermesProfile = ToolProfile(
    id = "deepseek-hermes",
    name = "DeepSeek Hermes",
    subtitle = "~/.dsh · AppData\\@deepseek-ai",
    baseDir = { dshHome() ?: dshDesktopAppData() },
    processNamePrefixes = setOf("DeepSeek Harness", "dsh"),
    protectedPaths = ::dshProtectedPaths,
    spaceDirs = { listOfNotNull(dshHome(), dshDesktopAppData(), dshUpdaterCache()) },
    targets = listOf(
        // —— ~/.dsh：缓存与临时（大头是 dsh-runtimes，约 270MB）——
        CleanTarget(
            id = "dsh.runtimes",
            label = "Primary Runtime 依赖",
            relPath = "dsh-runtimes",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            // 约 270MB，删后要重新下载，不默认勾。
            defaultSelected = false,
            description = "~/.dsh/dsh-runtimes 下的 dsh-primary-runtime（python/node/native 运行时依赖）。不丢用户数据，但删除后首次使用相关插件会重新下载（体积大、耗时长）。",
        ),
        CleanTarget(
            id = "dsh.sessions",
            label = "会话历史",
            relPath = "sessions",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.OPTIONAL,
            description = "按工作区切分的对话历史（session-<uuid> 目录）。删除后无法再打开或恢复这些会话。",
        ),
        CleanTarget(
            id = "dsh.storages",
            label = "存储与项目缓存",
            relPath = "storages",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.OPTIONAL,
            description = "session_projcache 等持久化存储。删除后项目缓存不可恢复。",
        ),
        CleanTarget(
            id = "dsh.profiles-cache",
            label = "Profile 插件缓存",
            relPath = "profiles/node_modules",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            // profiles/node_modules 被 desktop 和 web 共享，约 250 个 @deepseek-ai/dsh-* 包。
            // 但用户不会频繁清，体积相对小（几 MB），默认不勾。
            defaultSelected = false,
            description = "profiles/node_modules 下共享的 @deepseek-ai/dsh-* 插件包（被 desktop 和 web profile 共用）。删除后需要时会重新安装。",
        ),

        // —— %APPDATA%\@deepseek-ai\dsh-desktop：Electron 缓存（SAFE）——
        CleanTarget(
            id = "dsh.desktop-cache",
            label = "桌面端 HTTP 缓存",
            relPath = "Cache",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "Chromium HTTP 磁盘缓存，下次启动自动重建。",
            baseDir = ::dshDesktopAppData,
        ),
        CleanTarget(
            id = "dsh.desktop-gpu-cache",
            label = "桌面端 GPU 缓存",
            relPath = "GPUCache",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "GPU 进程缓存。",
            baseDir = ::dshDesktopAppData,
        ),
        CleanTarget(
            id = "dsh.desktop-code-cache",
            label = "桌面端代码缓存",
            relPath = "Code Cache",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "V8 编译缓存。",
            baseDir = ::dshDesktopAppData,
        ),
        CleanTarget(
            id = "dsh.desktop-dawn-graphite",
            label = "桌面端 Dawn Graphite 缓存",
            relPath = "DawnGraphiteCache",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "Dawn Graphite 图形缓存。",
            baseDir = ::dshDesktopAppData,
        ),
        CleanTarget(
            id = "dsh.desktop-dawn-webgpu",
            label = "桌面端 Dawn WebGPU 缓存",
            relPath = "DawnWebGPUCache",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "Dawn WebGPU 图形缓存。",
            baseDir = ::dshDesktopAppData,
        ),
        CleanTarget(
            id = "dsh.desktop-logs",
            label = "桌面端日志",
            relPath = "logs",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "Electron 应用历史运行日志，可直接清理。",
            baseDir = ::dshDesktopAppData,
        ),
        CleanTarget(
            id = "dsh.desktop-blob-storage",
            label = "桌面端 Blob 存储",
            relPath = "blob_storage",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.OPTIONAL,
            description = "Chromium blob 存储，可能包含尚未持久化的页面或附件数据。删除后不可恢复。",
            baseDir = ::dshDesktopAppData,
        ),
        CleanTarget(
            id = "dsh.desktop-session-storage",
            label = "桌面端会话存储",
            relPath = "Session Storage",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.OPTIONAL,
            description = "浏览器会话存储数据，删除后不可恢复。",
            baseDir = ::dshDesktopAppData,
        ),
        CleanTarget(
            id = "dsh.desktop-local-storage",
            label = "桌面端本地存储",
            relPath = "Local Storage",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.OPTIONAL,
            description = "浏览器本地存储数据，可能包含应用状态。删除后不可恢复。",
            baseDir = ::dshDesktopAppData,
        ),
        CleanTarget(
            id = "dsh.desktop-network",
            label = "桌面端网络数据",
            relPath = "Network",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.OPTIONAL,
            description = "Cookies / Trust Tokens / Network Persistent State。删除后登录态可能失效。",
            baseDir = ::dshDesktopAppData,
        ),
        // 分区包含持久化数据，只清明确的缓存子目录，保留分区本身及其存储。
        CleanTarget(
            id = "dsh.desktop-partitions-cache",
            label = "内置浏览器 HTTP 缓存",
            relPath = "Partitions/*/Cache",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "内置浏览器各分区的 HTTP 缓存。不清理 IndexedDB、Local Storage、Session Storage 或 Network 等持久化数据。",
            baseDir = ::dshDesktopAppData,
        ),
        CleanTarget(
            id = "dsh.desktop-partitions-gpu-cache",
            label = "内置浏览器 GPU 缓存",
            relPath = "Partitions/*/GPUCache",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "内置浏览器各分区的 GPU 缓存。",
            baseDir = ::dshDesktopAppData,
        ),
        CleanTarget(
            id = "dsh.desktop-partitions-code-cache",
            label = "内置浏览器代码缓存",
            relPath = "Partitions/*/Code Cache",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "内置浏览器各分区的 V8 代码缓存。",
            baseDir = ::dshDesktopAppData,
        ),
        CleanTarget(
            id = "dsh.desktop-partitions-dawn-graphite",
            label = "内置浏览器 Dawn Graphite 缓存",
            relPath = "Partitions/*/DawnGraphiteCache",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "内置浏览器各分区的 Dawn Graphite 缓存。",
            baseDir = ::dshDesktopAppData,
        ),
        CleanTarget(
            id = "dsh.desktop-partitions-dawn-webgpu",
            label = "内置浏览器 Dawn WebGPU 缓存",
            relPath = "Partitions/*/DawnWebGPUCache",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "内置浏览器各分区的 Dawn WebGPU 缓存。",
            baseDir = ::dshDesktopAppData,
        ),
        CleanTarget(
            id = "dsh.desktop-shared-dictionary",
            label = "共享字典缓存",
            relPath = "Shared Dictionary",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "Chromium 共享字典缓存。",
            baseDir = ::dshDesktopAppData,
        ),
        CleanTarget(
            id = "dsh.desktop-dictionaries",
            label = "拼写检查字典",
            relPath = "Dictionaries",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "拼写检查字典文件（如 en-US-10-1.bdic）。删除后会重新下载。",
            baseDir = ::dshDesktopAppData,
        ),
        // DIPS 及其 WAL/SHM 恢复文件一起保留，不能把 WAL 当作普通日志单独清理。

        // —— %LOCALAPPDATA%\@deepseek-aidsh-desktop-updater：更新缓存（约 550MB）——
        CleanTarget(
            id = "dsh.updater-cache",
            label = "桌面端更新缓存",
            relPath = "",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            // 约 550MB 待安装包，清后下次更新会重新下载。
            defaultSelected = false,
            description = "%LOCALAPPDATA%\\@deepseek-aidsh-desktop-updater 下的更新安装包（约 550MB）。清理后下次更新会重新下载。",
            baseDir = ::dshUpdaterCache,
        ),
    ),
)
