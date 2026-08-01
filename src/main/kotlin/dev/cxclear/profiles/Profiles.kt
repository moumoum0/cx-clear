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

/** `%LOCALAPPDATA%`。Windows 优先环境变量，再退回 `~/AppData/Local`。 */
private fun appDataLocal(): Path? {
    val candidates = buildList {
        if (isWindows) System.getenv("LOCALAPPDATA")?.let { add(it) }
        homeDir()?.resolve("AppData")?.resolve("Local")?.toString()?.let { add(it) }
    }
    return candidates.asSequence()
        .filter { it.isNotBlank() }
        .map { Paths.get(it) }
        .firstOrNull { Files.isDirectory(it) }
}

private fun cursorHome(): Path? = homeSubdir(".cursor")

private fun cursorAppData(): Path? =
    appDataRoaming()?.resolve("Cursor")?.takeIf { Files.isDirectory(it) }

private fun codexHome(): Path? = homeSubdir(".codex")

/** `~/.cache/codex-runtimes`：primary-runtime 的 python/node/native 依赖，体积通常远大于 `~/.codex`。 */
private fun codexRuntimesCache(): Path? =
    homeDir()?.resolve(".cache")?.resolve("codex-runtimes")?.takeIf { Files.isDirectory(it) }

private fun claudeHome(): Path? = homeSubdir(".claude")

/** CLI / IDE 扩展写入的 MCP 日志缓存（按项目切分）。 */
private fun claudeCliNodejsCache(): Path? =
    appDataLocal()?.resolve("claude-cli-nodejs")?.resolve("Cache")?.takeIf { Files.isDirectory(it) }

/**
 * Claude Desktop 应用数据根（Code 页宿主）。
 * 优先 `%LOCALAPPDATA%\Claude-3p`；没有再退回 `%APPDATA%\Claude`（旧路径 / 部分安装）。
 * 不含 Store MSIX 虚拟化目录、不含安装目录。
 */
private fun claudeDesktopAppData(): Path? {
    val local = appDataLocal()?.resolve("Claude-3p")?.takeIf { Files.isDirectory(it) }
    if (local != null) return local
    return appDataRoaming()?.resolve("Claude")?.takeIf { Files.isDirectory(it) }
}

/**
 * Codex — `~/.codex` + `~/.cache/codex-runtimes`。
 * 不含 `%LOCALAPPDATA%\OpenAI\Codex` 安装目录、不含 Documents\Codex 用户工作区。
 * 顺序按占用从大到小，方便用户从上往下勾。
 */
val CodexProfile = ToolProfile(
    id = "codex",
    name = "Codex",
    subtitle = "~/.codex · ~/.cache/codex-runtimes",
    baseDir = ::codexHome,
    spaceDirs = { listOfNotNull(codexHome(), codexRuntimesCache()) },
    targets = listOf(
        CleanTarget(
            id = "codex.cached-runtimes",
            label = "Primary Runtime 依赖",
            relPath = "",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            // 常占 1GB+，删后要重新下载，不默认勾。
            defaultSelected = false,
            description = "~/.cache/codex-runtimes 下的 python / node / native 运行时。不丢用户数据，但删除后首次使用相关插件会重新下载（体积大、耗时长）。",
            baseDir = ::codexRuntimesCache,
        ),
        CleanTarget(
            id = "codex.plugins-appserver",
            label = "插件宿主程序副本",
            relPath = "plugins/.plugin-appserver",
            kind = MatchKind.DIRECTORY,
            risk = Risk.SAFE,
            // 约数百 MB；一般从安装目录再拷，但仍可能让插件宿主短暂不可用，不默认勾。
            defaultSelected = false,
            description = "安装目录二进制在 ~/.codex 下的副本（可与安装包重复）。删除后需要时会重新放置；请先退出 Codex 再清。",
        ),
        CleanTarget(
            id = "codex.tmp",
            label = "解包与同步缓存",
            relPath = ".tmp",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            // config 可能正指向其中的 marketplace source；清完要等再同步，不默认勾。
            defaultSelected = false,
            description = "marketplace / 插件同步的中间目录（含 staging 残留）。可重建，但配置可能正引用此处，删除后需重新同步，期间插件列表可能暂不可用。",
        ),
        CleanTarget(
            id = "codex.logs-db",
            label = "日志数据库",
            relPath = "logs_*.sqlite*",
            kind = MatchKind.GLOB,
            risk = Risk.SAFE,
            description = "遥测与调试用的 logs_*.sqlite（含 -wal / -shm）。可重建，不影响对话内容。",
        ),
        CleanTarget(
            id = "codex.sessions",
            label = "会话历史",
            relPath = "sessions",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.OPTIONAL,
            description = "按日期存放的对话记录（rollout *.jsonl）。删除后无法再打开或恢复这些会话。",
        ),
        CleanTarget(
            id = "codex.plugins-cache",
            label = "已解包的插件",
            relPath = "plugins/cache",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "marketplace 解包后的插件文件（如 browser、documents）。不含宿主程序本身；下次使用时会重新解包。",
        ),
        CleanTarget(
            id = "codex.sandbox-bin",
            label = "沙箱运行器（历史版本）",
            // 同时覆盖带版本号与无版本号的旧 runner；STALE_VERSIONS 按 mtime 保留最新一份。
            relPath = ".sandbox-bin/codex-command-runner*.exe",
            kind = MatchKind.STALE_VERSIONS,
            risk = Risk.SAFE,
            description = "升级留下的旧版 command-runner（含无版本号旧文件）。只删历史版本，保留最新一份；不动同目录的 codex.exe。",
        ),
        CleanTarget(
            id = "codex.vendor-imports",
            label = "官方 Skills 克隆",
            relPath = "vendor_imports",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            // 需再从 GitHub 拉取，不默认勾。
            defaultSelected = false,
            description = "从 GitHub 拉取的官方 skills 仓库缓存。删除后需要时会重新下载；不影响你自己的 skills 目录。",
        ),
        CleanTarget(
            id = "codex.sandbox-logs",
            label = "沙箱日志",
            // 只清日志，不动 setup_marker / ACL 状态等沙箱元数据。
            relPath = ".sandbox/sandbox*.log",
            kind = MatchKind.GLOB,
            risk = Risk.SAFE,
            description = "Windows 沙箱运行日志。可重建；不包含密钥与沙箱配置。",
        ),
        CleanTarget(
            id = "codex.sqlite-legacy",
            label = "旧版数据库目录",
            relPath = "sqlite",
            kind = MatchKind.DIRECTORY,
            risk = Risk.SAFE,
            description = "极早期版本遗留的 sqlite 目录；新版本已改用根目录下的独立库文件。多数机器上已不存在。",
        ),
    ),
)

/**
 * Claude Code — `~/.claude` + CLI MCP 缓存 + Desktop（`Claude-3p`）Electron 缓存。
 * 不含 npm 安装目录、不含 Desktop 内嵌 claude-code 二进制 / 会话 / 登录态。
 * 顺序按占用从大到小，方便用户从上往下勾。
 */
val ClaudeCodeProfile = ToolProfile(
    id = "claude",
    name = "Claude Code",
    subtitle = "~/.claude · Local\\Claude-3p",
    baseDir = ::claudeHome,
    spaceDirs = { listOfNotNull(claudeHome(), claudeCliNodejsCache(), claudeDesktopAppData()) },
    targets = listOf(
        CleanTarget(
            id = "claude.downloads",
            label = "安装包缓存",
            relPath = "downloads",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "已下载的 claude-*-win32-x64.exe 安装包。不丢用户数据，但删除后升级/重装需重新下载（体积大）。",
        ),
        CleanTarget(
            id = "claude.projects",
            label = "项目会话记录",
            relPath = "projects",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.OPTIONAL,
            description = "按项目切分的对话转录（含 subagents / tool-results）以及 auto memory（projects/*/memory）。删除后无法 --resume，也无法恢复这些记忆。",
        ),
        CleanTarget(
            id = "claude.plugins-marketplaces",
            label = "插件市场缓存",
            relPath = "plugins/marketplaces",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            // 需再从 GitHub 拉取，不默认勾。
            defaultSelected = false,
            description = "克隆下来的插件市场仓库。删除后下次用插件时会重新拉取；不动 plugins 下的配置 JSON。",
        ),
        CleanTarget(
            id = "claude.plugins-cache",
            label = "已安装插件缓存",
            relPath = "plugins/cache",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "已安装插件的解包/缓存副本。删除后需要时会重新解析；插件持久数据在 plugins/data，不在此项。",
        ),
        CleanTarget(
            id = "claude.telemetry",
            label = "遥测数据",
            relPath = "telemetry",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "本地埋点与失败上报缓存，不影响功能。",
        ),
        CleanTarget(
            id = "claude.desktop-electron-cache",
            label = "桌面端 Electron 缓存",
            relPath = "Cache",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "Claude Desktop（Claude-3p）的 Chromium HTTP 磁盘缓存，下次启动自动重建。不碰登录态与 Code 会话。",
            baseDir = ::claudeDesktopAppData,
        ),
        CleanTarget(
            id = "claude.file-history",
            label = "文件改动历史",
            relPath = "file-history",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.OPTIONAL,
            description = "被编辑文件的历史快照，用于 checkpoint 回滚。删除后无法撤销之前的改动。",
        ),
        CleanTarget(
            id = "claude.shell-snapshots",
            label = "Shell 环境快照",
            relPath = "shell-snapshots",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "会话启动时抓取的 shell 环境；正常退出会清掉，此处多为崩溃残留。",
        ),
        CleanTarget(
            id = "claude.cache",
            label = "通用缓存",
            relPath = "cache",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "内部缓存（如 changelog.md）。删除后会在后台重新拉取。",
        ),
        CleanTarget(
            id = "claude.cli-nodejs-cache",
            label = "CLI / IDE MCP 日志缓存",
            relPath = "",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "%LOCALAPPDATA%\\claude-cli-nodejs\\Cache 下按项目切分的 MCP 日志。可重建。",
            baseDir = ::claudeCliNodejsCache,
        ),
        CleanTarget(
            id = "claude.history",
            label = "输入历史",
            relPath = "history.jsonl",
            kind = MatchKind.FILE,
            risk = Risk.OPTIONAL,
            description = "在提示符里输入过的每一行（上箭头回忆）。删除后无法再召回这些输入。",
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
            id = "claude.image-cache",
            label = "图片附件缓存",
            relPath = "image-cache",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "会话里附带图片的本地缓存。可重建；多数机器上可能尚未生成。",
        ),
        CleanTarget(
            id = "claude.backups",
            label = "配置备份",
            relPath = "backups",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.OPTIONAL,
            description = "~/.claude/backups 下的 .claude.json 迁移备份。删除后无法回退这些快照。",
        ),
        CleanTarget(
            id = "claude.home-json-backups",
            label = "主目录配置备份",
            relPath = ".claude.json.backup*",
            kind = MatchKind.GLOB,
            risk = Risk.OPTIONAL,
            description = "用户主目录下的 .claude.json.backup*。不动正在使用的 .claude.json。",
            baseDir = ::homeDir,
        ),
        CleanTarget(
            id = "claude.debug",
            label = "调试日志",
            relPath = "debug",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "--debug 或 /debug 写出的会话调试日志。",
        ),
        CleanTarget(
            id = "claude.session-env",
            label = "会话环境元数据",
            relPath = "session-env",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "每会话的环境元数据目录，无用户可见内容。",
        ),
        CleanTarget(
            id = "claude.tasks",
            label = "会话任务列表",
            relPath = "tasks",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.OPTIONAL,
            description = "任务工具写出的每会话任务列表。删除后无法恢复尚未完成或历史会话中的任务状态。",
        ),
        CleanTarget(
            id = "claude.plans",
            label = "Plan 模式文件",
            relPath = "plans",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.OPTIONAL,
            description = "Plan 模式写出的用户计划文件。删除后计划内容不可恢复。",
        ),
        CleanTarget(
            id = "claude.feedback-bundles",
            label = "反馈归档",
            relPath = "feedback-bundles",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "/feedback 写出的脱敏转录归档。可清。",
        ),
        CleanTarget(
            id = "claude.stats-cache",
            label = "用量统计缓存",
            relPath = "stats-cache.json",
            kind = MatchKind.FILE,
            risk = Risk.OPTIONAL,
            description = "/usage 展示的历史 token / 费用汇总。删除后历史总计不可恢复（会重新累计）。",
        ),
        CleanTarget(
            id = "claude.remote-settings",
            label = "远程设置缓存",
            relPath = "remote-settings.json",
            kind = MatchKind.FILE,
            risk = Risk.SAFE,
            description = "组织下发的 server-managed settings 本地副本。下次启动会重新拉取。",
        ),
        CleanTarget(
            id = "claude.policy-limits",
            label = "策略限额缓存",
            relPath = "policy-limits.json",
            kind = MatchKind.FILE,
            risk = Risk.SAFE,
            description = "账号策略限额的本地缓存。会自动刷新。",
        ),
        CleanTarget(
            id = "claude.todos-legacy",
            label = "旧版 todos 目录",
            relPath = "todos",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "旧版本遗留目录，新版本不再写入。多数机器上已不存在。",
        ),
        CleanTarget(
            id = "claude.statsig-legacy",
            label = "旧版 statsig 目录",
            relPath = "statsig",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "旧版本遗留目录，新版本不再写入。多数机器上已不存在。",
        ),
        CleanTarget(
            id = "claude.logs-legacy",
            label = "旧版 logs 目录",
            relPath = "logs",
            kind = MatchKind.DIRECTORY_CONTENTS,
            risk = Risk.SAFE,
            description = "旧版本遗留目录，新版本不再写入。多数机器上已不存在。",
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
            risk = Risk.OPTIONAL,
            description = "state.vscdb 的备份副本，常与主库同等体积。删除不影响当前状态，但会失去主库损坏时的恢复副本；主库本身不清理。",
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
