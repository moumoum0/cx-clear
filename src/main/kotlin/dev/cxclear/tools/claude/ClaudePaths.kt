package dev.cxclear.tools.claude

import dev.cxclear.storage.appDataLocal
import dev.cxclear.storage.appDataRoaming
import dev.cxclear.storage.homeDir
import dev.cxclear.tools.homeSubdir
import java.nio.file.Files
import java.nio.file.Path

internal fun claudeHome(): Path? = homeSubdir(".claude")

internal fun claudeProjectsRoot(): Path? =
    homeDir()?.resolve(".claude")?.resolve("projects")?.takeIf { Files.isDirectory(it) }

/** CLI / IDE 扩展写入的 MCP 日志缓存（按项目切分）。 */
internal fun claudeCliNodejsCache(): Path? =
    appDataLocal()?.resolve("claude-cli-nodejs")?.resolve("Cache")?.takeIf { Files.isDirectory(it) }

/**
 * Claude Desktop 应用数据根（Code 页宿主）。
 * 优先 `%LOCALAPPDATA%\Claude-3p`；没有再退回 `%APPDATA%\Claude`（旧路径 / 部分安装）。
 * 不含 Store MSIX 虚拟化目录、不含安装目录。
 */
internal fun claudeDesktopAppData(): Path? {
    val local = appDataLocal()?.resolve("Claude-3p")?.takeIf { Files.isDirectory(it) }
    if (local != null) return local
    return appDataRoaming()?.resolve("Claude")?.takeIf { Files.isDirectory(it) }
}

internal fun claudeProtectedPaths(): List<Path> = buildList {
    claudeHome()?.let { root ->
        listOf(
            "config.json",
            "settings.json",
            "statusline-command.sh",
            "ide",
            "sessions",
            "skills",
            "plugins/blocklist.json",
            "plugins/known_marketplaces.json",
            "plugins/data",
        ).forEach { add(root.resolve(it)) }
    }
    homeDir()?.let { add(it.resolve(".claude.json")) }
}
