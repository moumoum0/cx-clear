package dev.cxclear.profiles

import dev.cxclear.model.CleanTarget
import dev.cxclear.model.MatchKind
import dev.cxclear.model.Risk
import dev.cxclear.model.ToolProfile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private val isWindows: Boolean
    get() = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

/** 用户主目录。Windows 上 USERPROFILE 比 user.home 更可靠（后者在某些 JVM 下指向别处）。 */
internal fun homeDir(): Path? {
    val candidates = buildList {
        if (isWindows) System.getenv("USERPROFILE")?.let { add(it) }
        System.getProperty("user.home")?.let { add(it) }
    }
    return candidates.asSequence()
        .filter { it.isNotBlank() }
        .map { Paths.get(it) }
        .firstOrNull { Files.isDirectory(it) }
}

private fun homeSubdir(name: String): Path? =
    homeDir()?.resolve(name)?.takeIf { Files.isDirectory(it) }

/** `%APPDATA%`（Roaming）。Windows 优先环境变量，再退回 `~/AppData/Roaming`。 */
private fun appDataRoaming(): Path? {
    val candidates = buildList {
        if (isWindows) System.getenv("APPDATA")?.let { add(it) }
        homeDir()?.resolve("AppData")?.resolve("Roaming")?.toString()?.let { add(it) }
    }
    return candidates.asSequence()
        .filter { it.isNotBlank() }
        .map { Paths.get(it) }
        .firstOrNull { Files.isDirectory(it) }
}

private fun cursorHome(): Path? = homeSubdir(".cursor")

private fun cursorAppData(): Path? =
    appDataRoaming()?.resolve("Cursor")?.takeIf { Files.isDirectory(it) }

/**
 * Codex — `~/.codex`。实测本机占用最大的工具，主要来自插件缓存和沙箱二进制。
 * 顺序按占用从大到小，方便用户从上往下勾。
 */
val CodexProfile = ToolProfile(
    id = "codex",
    name = "Codex",
    subtitle = "~/.codex",
    baseDir = { homeSubdir(".codex") },
    targets = listOf(
        CleanTarget(
            id = "codex.plugins-cache",
            label = "插件缓存",
            relPath = "plugins/cache",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "已解包的插件运行时（openai-bundled / openai-primary-runtime）。下次启动自动重新解包。",
        ),
        CleanTarget(
            id = "codex.sandbox-bin",
            label = "沙箱运行器（历史版本）",
            relPath = ".sandbox-bin/codex-command-runner-*.exe",
            kind = MatchKind.STALE_VERSIONS,
            risk = Risk.SAFE,
            description = "每次升级都会留下一份旧的 codex-command-runner。保留最新版本，只删历史版本。",
        ),
        CleanTarget(
            id = "codex.tmp",
            label = "临时解包目录",
            relPath = ".tmp",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "bundled-marketplaces 与 plugins 的中间产物，纯临时数据。",
        ),
        CleanTarget(
            id = "codex.logs-db",
            label = "日志数据库",
            relPath = "logs_*.sqlite*",
            kind = MatchKind.GLOB,
            risk = Risk.SAFE,
            description = "遥测与调试日志（含 -shm / -wal）。删除不影响会话历史。",
        ),
        CleanTarget(
            id = "codex.sqlite-legacy",
            label = "旧版数据库备份",
            relPath = "sqlite",
            kind = MatchKind.DIRECTORY,
            risk = Risk.SAFE,
            description = "早期版本遗留的 sqlite 目录，新版本已不再读取。",
        ),
        CleanTarget(
            id = "codex.sessions",
            label = "会话历史",
            relPath = "sessions",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.OPTIONAL,
            description = "历史对话记录。删除后无法再回看或恢复之前的会话。",
        ),
        CleanTarget(
            id = "codex.vendor-imports",
            label = "vendor 导入缓存",
            relPath = "vendor_imports",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "第三方依赖导入的中间缓存。",
        ),
    ),
)

/**
 * Claude Code — `~/.claude`。
 * 大头是安装包和按项目切分的会话记录。
 */
val ClaudeCodeProfile = ToolProfile(
    id = "claude",
    name = "Claude Code",
    subtitle = "~/.claude",
    baseDir = { homeSubdir(".claude") },
    targets = listOf(
        CleanTarget(
            id = "claude.downloads",
            label = "安装包缓存",
            relPath = "downloads",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "已安装版本的安装程序，留着只是占地方，需要时会重新下载。",
        ),
        CleanTarget(
            id = "claude.plugins-marketplaces",
            label = "插件市场缓存",
            relPath = "plugins/marketplaces",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "克隆下来的插件市场仓库，下次用到时自动重新拉取。",
        ),
        CleanTarget(
            id = "claude.telemetry",
            label = "遥测数据",
            relPath = "telemetry",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "本地埋点数据，不影响任何功能。",
        ),
        CleanTarget(
            id = "claude.file-history",
            label = "文件改动历史",
            relPath = "file-history",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.OPTIONAL,
            description = "被编辑文件的历史快照，用于回滚。删除后无法撤销之前的改动。",
        ),
        CleanTarget(
            id = "claude.cache",
            label = "通用缓存",
            relPath = "cache",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "内部缓存目录。",
        ),
        CleanTarget(
            id = "claude.paste-cache",
            label = "粘贴内容缓存",
            relPath = "paste-cache",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "大段粘贴文本的临时副本。",
        ),
        CleanTarget(
            id = "claude.shell-snapshots",
            label = "Shell 环境快照",
            relPath = "shell-snapshots",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "每个会话启动时抓取的 shell 环境，会话结束即失效。",
        ),
        CleanTarget(
            id = "claude.debug",
            label = "调试日志",
            relPath = "debug",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "调试输出。",
        ),
        CleanTarget(
            id = "claude.backups",
            label = "配置备份",
            relPath = "backups",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.OPTIONAL,
            description = "配置文件的历史备份，删除后无法回退配置。",
        ),
        CleanTarget(
            id = "claude.projects",
            label = "项目会话记录",
            relPath = "projects",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.OPTIONAL,
            description = "按项目切分的完整对话记录，也是 --resume 的数据来源。删除后无法恢复历史会话。",
        ),
    ),
)

/**
 * Cursor — 数据分两处：
 * - `~/.cursor`：扩展、Agent 项目态、技能与配置
 * - `%APPDATA%\Cursor`：Electron/Chromium 缓存、工作区存储、全局状态库
 *
 * 安装目录（`%LOCALAPPDATA%\Programs\Cursor`）不计入、不清理。
 * 主状态库 `state.vscdb` 不提供清理项——删掉会导致历史会话卡在 Loading Chat。
 * `Partitions` 只清 Cache/GPUCache 等，不动 Local Storage / Session Storage。
 * `~/.cursor/projects` 按子目录拆分：不清 canvases / rules（用户产物）。
 */
val CursorProfile = ToolProfile(
    id = "cursor",
    name = "Cursor",
    subtitle = "~/.cursor · AppData\\Cursor",
    baseDir = { cursorHome() ?: cursorAppData() },
    spaceDirs = { listOfNotNull(cursorHome(), cursorAppData()) },
    targets = listOf(
        // —— AppData\\Cursor：缓存与临时（SAFE）——
        CleanTarget(
            id = "cursor.state-backup",
            label = "全局状态库备份",
            relPath = "User/globalStorage/state.vscdb.backup",
            kind = MatchKind.FILE,
            risk = Risk.SAFE,
            description = "state.vscdb 的备份副本，常与主库同等体积。删除不影响当前状态；主库本身不清理。",
            baseDir = ::cursorAppData,
        ),
        CleanTarget(
            id = "cursor.cache",
            label = "HTTP 缓存",
            relPath = "Cache",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "Chromium HTTP 磁盘缓存，下次启动自动重建。",
            baseDir = ::cursorAppData,
        ),
        CleanTarget(
            id = "cursor.gpu-cache",
            label = "GPU 缓存",
            relPath = "GPUCache",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "GPU 进程缓存。",
            baseDir = ::cursorAppData,
        ),
        CleanTarget(
            id = "cursor.code-cache",
            label = "V8 代码缓存",
            relPath = "Code Cache",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "V8 编译缓存。",
            baseDir = ::cursorAppData,
        ),
        CleanTarget(
            id = "cursor.dawn-graphite-cache",
            label = "Dawn Graphite 缓存",
            relPath = "DawnGraphiteCache",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "Dawn Graphite 图形缓存。",
            baseDir = ::cursorAppData,
        ),
        CleanTarget(
            id = "cursor.dawn-webgpu-cache",
            label = "Dawn WebGPU 缓存",
            relPath = "DawnWebGPUCache",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "Dawn WebGPU 图形缓存。",
            baseDir = ::cursorAppData,
        ),
        CleanTarget(
            id = "cursor.cached-data",
            label = "编辑器版本缓存（历史）",
            relPath = "*",
            kind = MatchKind.STALE_VERSIONS,
            risk = Risk.SAFE,
            description = "CachedData 下按 commit 分的旧版本资源。保留最新一份，只删历史版本。",
            baseDir = { cursorAppData()?.resolve("CachedData")?.takeIf { Files.isDirectory(it) } },
        ),
        CleanTarget(
            id = "cursor.cached-extensions",
            label = "扩展安装包缓存",
            relPath = "CachedExtensionVSIXs",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "已下载的 VSIX 安装包。已安装扩展不受影响，需要时会重新下载。",
            baseDir = ::cursorAppData,
        ),
        CleanTarget(
            id = "cursor.cached-profiles",
            label = "配置档案缓存",
            relPath = "CachedProfilesData",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "Profile 相关缓存数据。",
            baseDir = ::cursorAppData,
        ),
        CleanTarget(
            id = "cursor.app-logs",
            label = "应用日志",
            relPath = "logs",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "Cursor 主程序日志。",
            baseDir = ::cursorAppData,
        ),
        CleanTarget(
            id = "cursor.crashpad",
            label = "崩溃转储",
            relPath = "Crashpad",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "崩溃报告与 minidump。",
            baseDir = ::cursorAppData,
        ),
        CleanTarget(
            id = "cursor.service-worker",
            label = "Service Worker 缓存",
            relPath = "Service Worker",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "内置页 Service Worker 缓存。",
            baseDir = ::cursorAppData,
        ),
        CleanTarget(
            id = "cursor.blob-storage",
            label = "Blob 存储",
            relPath = "blob_storage",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "Chromium blob 临时存储。",
            baseDir = ::cursorAppData,
        ),
        // Partitions 整目录含 Local Storage / Session Storage，不能整删。
        // 只清其中明确是缓存的子目录。
        CleanTarget(
            id = "cursor.partition-cache",
            label = "内置浏览器 HTTP 缓存",
            relPath = "Partitions/cursor-browser/Cache",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "内置浏览器分区的 HTTP 缓存。不碰同分区下的 Local Storage / Session Storage。",
            baseDir = ::cursorAppData,
        ),
        CleanTarget(
            id = "cursor.partition-gpu-cache",
            label = "内置浏览器 GPU 缓存",
            relPath = "Partitions/cursor-browser/GPUCache",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "内置浏览器分区的 GPU 缓存。",
            baseDir = ::cursorAppData,
        ),
        CleanTarget(
            id = "cursor.partition-code-cache",
            label = "内置浏览器代码缓存",
            relPath = "Partitions/cursor-browser/Code Cache",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "内置浏览器分区的 V8 代码缓存。",
            baseDir = ::cursorAppData,
        ),
        CleanTarget(
            id = "cursor.partition-dawn-graphite",
            label = "内置浏览器 Dawn Graphite 缓存",
            relPath = "Partitions/cursor-browser/DawnGraphiteCache",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "内置浏览器分区的 Dawn Graphite 缓存。",
            baseDir = ::cursorAppData,
        ),
        CleanTarget(
            id = "cursor.partition-dawn-webgpu",
            label = "内置浏览器 Dawn WebGPU 缓存",
            relPath = "Partitions/cursor-browser/DawnWebGPUCache",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "内置浏览器分区的 Dawn WebGPU 缓存。",
            baseDir = ::cursorAppData,
        ),
        CleanTarget(
            id = "cursor.partition-blob",
            label = "内置浏览器 Blob 存储",
            relPath = "Partitions/cursor-browser/blob_storage",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "内置浏览器分区的 blob 临时存储。",
            baseDir = ::cursorAppData,
        ),
        CleanTarget(
            id = "cursor.sentry",
            label = "Sentry 遥测缓存",
            relPath = "sentry",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "错误上报本地缓存。",
            baseDir = ::cursorAppData,
        ),
        CleanTarget(
            id = "cursor.process-monitor",
            label = "进程监控日志",
            relPath = "process-monitor",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "本地进程监控输出。",
            baseDir = ::cursorAppData,
        ),
        CleanTarget(
            id = "cursor.clp",
            label = "语言包缓存",
            relPath = "clp",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "已下载的语言包（CLP）缓存，需要时会重新拉取。",
            baseDir = ::cursorAppData,
        ),

        // —— ~/.cursor：缓存与临时（SAFE）——
        CleanTarget(
            id = "cursor.home-logs",
            label = "Agent 日志",
            relPath = "logs",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "~/.cursor 下的连接与调试日志。",
            baseDir = ::cursorHome,
        ),
        CleanTarget(
            id = "cursor.agent-tools",
            label = "Agent 工具临时输出",
            relPath = "projects/*/agent-tools",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "各项目下 Agent 工具写出的临时文本，可重建。不碰 canvases / 对话转录。",
            baseDir = ::cursorHome,
        ),
        CleanTarget(
            id = "cursor.project-terminals",
            label = "项目终端快照",
            relPath = "projects/*/terminals",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "各项目下终端输出的本地快照，仅供 Agent 上下文，可重建。",
            baseDir = ::cursorHome,
        ),
        CleanTarget(
            id = "cursor.project-mcps",
            label = "项目 MCP 描述缓存",
            relPath = "projects/*/mcps",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "各项目缓存的 MCP 工具描述 JSON，连接时会重新拉取。",
            baseDir = ::cursorHome,
        ),

        // —— OPTIONAL：会丢历史 / 需重装 ——
        CleanTarget(
            id = "cursor.ai-tracking",
            label = "AI 代码追踪库",
            relPath = "ai-tracking",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.OPTIONAL,
            description = "本地 AI 代码归属追踪数据库（ai-code-tracking.db）。删除后历史追踪记录不可恢复。",
            baseDir = ::cursorHome,
        ),
        CleanTarget(
            id = "cursor.agent-transcripts",
            label = "Agent 对话转录",
            relPath = "projects/*/agent-transcripts",
            kind = MatchKind.DIRECTORY,
            risk = Risk.OPTIONAL,
            description = "各项目的 Agent 对话 jsonl。不删除同级的 canvases（Canvas 文件）与 rules。",
            baseDir = ::cursorHome,
        ),
        CleanTarget(
            id = "cursor.checkpoints-commits",
            label = "Agent 提交检查点",
            relPath = "User/globalStorage/anysphere.cursor-commits",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.OPTIONAL,
            description = "Composer/Agent 的 commits 检查点（含文件快照与 diff）。删除后无法按检查点回滚。",
            baseDir = ::cursorAppData,
        ),
        CleanTarget(
            id = "cursor.checkpoints-retrieval",
            label = "代码检索检查点",
            relPath = "User/globalStorage/anysphere.cursor-retrieval",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.OPTIONAL,
            description = "检索相关检查点快照。删除后无法恢复这些检查点。",
            baseDir = ::cursorAppData,
        ),
        CleanTarget(
            id = "cursor.workspace-storage",
            label = "工作区存储",
            relPath = "User/workspaceStorage",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.OPTIONAL,
            description = "各工作区的 state.vscdb、图片与 UI 状态。删除后该工作区侧栏历史可能无法打开。",
            baseDir = ::cursorAppData,
        ),
        CleanTarget(
            id = "cursor.file-history",
            label = "本地文件历史",
            relPath = "User/History",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.OPTIONAL,
            description = "编辑器本地时间线（含 Undo Reject Diff 等快照）。删除后无法按时间线回滚文件。",
            baseDir = ::cursorAppData,
        ),
        CleanTarget(
            id = "cursor.backups",
            label = "热退出备份",
            relPath = "Backups",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.OPTIONAL,
            description = "异常退出时的工作区备份。",
            baseDir = ::cursorAppData,
        ),
        CleanTarget(
            id = "cursor.extensions",
            label = "已安装扩展",
            relPath = "extensions",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.OPTIONAL,
            description = "已解包的扩展目录与 extensions.json。删除后需在扩展市场重新安装。",
            baseDir = ::cursorHome,
        ),
    ),
)

val ALL_PROFILES = listOf(CodexProfile, ClaudeCodeProfile, CursorProfile)
