package dev.cxclear.profiles

import dev.cxclear.model.CleanTarget
import dev.cxclear.model.MatchKind
import dev.cxclear.model.Risk
import dev.cxclear.model.ToolProfile

/**
 * Codex 的清理名单：数据根、可清理项、进程名与永久保护路径。
 */

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
    processNamePrefixes = setOf("codex"),
    protectedPaths = ::codexProtectedPaths,
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
            description = "Codex 调试日志库（含 -wal / -shm）。用于问题诊断，不承载会话主数据；请先完全退出 Codex 再清理。",
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
            description = "Windows 沙箱运行日志。不包含密钥与沙箱配置，可直接清理。",
        ),
    ),
)
