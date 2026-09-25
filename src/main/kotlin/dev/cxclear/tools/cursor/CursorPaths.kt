package dev.cxclear.tools.cursor

import dev.cxclear.storage.appDataRoaming
import dev.cxclear.storage.homeDir
import dev.cxclear.tools.homeSubdir
import java.nio.file.Files
import java.nio.file.Path

internal fun cursorHome(): Path? = homeSubdir(".cursor")

internal fun cursorAppData(): Path? =
    appDataRoaming()?.resolve("Cursor")?.takeIf { Files.isDirectory(it) }

/** Cursor 项目存储根目录：~/.cursor/projects/ */
internal fun cursorProjectsRoot(): Path? =
    homeDir()?.resolve(".cursor")?.resolve("projects")?.takeIf { Files.isDirectory(it) }

/** 测试注入。null 走真实路径；指向不存在的文件则视为状态库缺失。 */
internal var cursorStateDbOverride: Path? = null

/** Cursor 全局状态库：%APPDATA%/Cursor/User/globalStorage/state.vscdb */
internal fun cursorStateDbFile(): Path? =
    appDataRoaming()
        ?.resolve("Cursor")
        ?.resolve("User")
        ?.resolve("globalStorage")
        ?.resolve("state.vscdb")
        ?.takeIf { Files.isRegularFile(it) }

internal fun resolveCursorStateDb(): Path? {
    val override = cursorStateDbOverride
    if (override != null) return override.takeIf { Files.isRegularFile(it) }
    return cursorStateDbFile()
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
