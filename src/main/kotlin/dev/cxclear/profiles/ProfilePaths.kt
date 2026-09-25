package dev.cxclear.profiles

import dev.cxclear.storage.appDataLocal
import dev.cxclear.storage.appDataRoaming
import dev.cxclear.storage.homeDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * 各工具数据根目录的定位函数与永久保护路径清单。
 * 只负责「在哪」和「不能删什么」，清理项名单在各自的 XxxProfile.kt。
 */

internal fun homeSubdir(name: String): Path? =
    homeDir()?.resolve(name)?.takeIf { Files.isDirectory(it) }

internal fun cursorHome(): Path? = homeSubdir(".cursor")

internal fun cursorAppData(): Path? =
    appDataRoaming()?.resolve("Cursor")?.takeIf { Files.isDirectory(it) }

internal fun opencodeHome(): Path? =
    homeDir()?.resolve(".local")?.resolve("share")?.resolve("opencode")?.takeIf { Files.isDirectory(it) }

internal fun opencodeConfigHome(): Path? =
    homeDir()?.resolve(".config")?.resolve("opencode")?.takeIf { Files.isDirectory(it) }

internal fun opencodeCacheHome(): Path? =
    homeDir()?.resolve(".cache")?.resolve("opencode")?.takeIf { Files.isDirectory(it) }

internal fun opencodeAppData(): Path? =
    appDataRoaming()?.resolve("ai.opencode.desktop")?.takeIf { Files.isDirectory(it) }

internal fun codexHome(): Path? = homeSubdir(".codex")

/** `~/.cache/codex-runtimes`：primary-runtime 的 python/node/native 依赖，体积通常远大于 `~/.codex`。 */
internal fun codexRuntimesCache(): Path? =
    homeDir()?.resolve(".cache")?.resolve("codex-runtimes")?.takeIf { Files.isDirectory(it) }

internal fun claudeHome(): Path? = homeSubdir(".claude")

internal fun directMatches(root: Path, vararg patterns: String): List<Path> {
    val matchers = patterns.map { root.fileSystem.getPathMatcher("glob:$it") }
    return Files.newDirectoryStream(root).use { entries ->
        buildList {
            for (entry in entries) {
                if (matchers.any { it.matches(entry.fileName) }) add(entry)
            }
        }
    }
}

internal fun codexProtectedPaths(): List<Path> {
    val root = codexHome() ?: return emptyList()
    val fixed = listOf(
        "sqlite",
        "auth.json",
        "config.toml",
        "AGENTS.md",
        "installation_id",
        "session_index.jsonl",
        ".codex-global-state.json",
        ".codex-global-state.json.bak",
        ".sandbox-secrets",
        "memories",
        "rules",
        "skills",
    ).map(root::resolve)
    return fixed + directMatches(root, "state_*.sqlite*", "goals_*.sqlite*", "memories_*.sqlite*")
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

internal fun cursorProtectedPaths(): List<Path> = buildList {
    cursorHome()?.let { root ->
        listOf(
            "argv.json",
            "ide_state.json",
            "mcp.json",
            "agents",
            "plans",
            "plugins",
            "skills",
            "skills-cursor",
            "worktrees",
        ).forEach { add(root.resolve(it)) }
    }
    cursorAppData()?.let { root ->
        listOf(
            "User/settings.json",
            "User/keybindings.json",
            "User/snippets",
            "User/globalStorage/state.vscdb",
            "User/globalStorage/state.vscdb-shm",
            "User/globalStorage/state.vscdb-wal",
            "User/globalStorage/storage.json",
        ).forEach { add(root.resolve(it)) }
    }
}

internal fun opencodeProtectedPaths(): List<Path> = buildList {
    opencodeHome()?.let { root ->
        listOf(
            "auth.json",
            "opencode.db",
            "opencode.db-shm",
            "opencode.db-wal",
            "opencode-local.db",
            "opencode-local.db-shm",
            "opencode-local.db-wal",
        ).forEach { add(root.resolve(it)) }
    }
    opencodeConfigHome()?.let { root ->
        listOf(
            "opencode.json",
            "opencode.jsonc",
        ).forEach { add(root.resolve(it)) }
    }
    opencodeAppData()?.let { root ->
        listOf(
            "Preferences",
            "Local State",
            "opencode.settings",
            "opencode.global.dat",
        ).forEach { add(root.resolve(it)) }
    }
}

internal fun dshProtectedPaths(): List<Path> = buildList {
    dshHome()?.let { root ->
        listOf(
            ".anonymous-user-id",
            ".credentials.yaml",
            "settings.yaml",
            "settings.yaml.imported",
            "profiles/desktop/cordis.yml",
            "profiles/desktop/cordis.patch.yml",
            "profiles/web/cordis.yml",
            "profiles/web/cordis.patch.yml",
        ).forEach { add(root.resolve(it)) }
    }
    dshDesktopAppData()?.let { root ->
        listOf(
            "Preferences",
            "Local State",
            ".updaterId",
            "background-close-confirmed",
            "lockfile",
        ).forEach { add(root.resolve(it)) }
    }
}

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

internal fun dshHome(): Path? = homeSubdir(".dsh")

internal fun dshDesktopAppData(): Path? =
    appDataRoaming()?.resolve("@deepseek-ai")?.resolve("dsh-desktop")?.takeIf { Files.isDirectory(it) }

internal fun dshUpdaterCache(): Path? =
    appDataLocal()?.resolve("@deepseek-aidsh-desktop-updater")?.takeIf { Files.isDirectory(it) }
