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
private fun homeDir(): Path? {
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

val ALL_PROFILES = listOf(CodexProfile, ClaudeCodeProfile)
