package dev.cxclear.tools.opencode

import dev.cxclear.storage.appDataRoaming
import dev.cxclear.storage.userCacheDir
import dev.cxclear.storage.userConfigDir
import dev.cxclear.storage.userDataDir
import java.nio.file.Files
import java.nio.file.Path

internal fun opencodeHome(): Path? =
    userDataDir()?.resolve("opencode")?.takeIf { Files.isDirectory(it) }

internal fun opencodeConfigHome(): Path? =
    userConfigDir()?.resolve("opencode")?.takeIf { Files.isDirectory(it) }

internal fun opencodeCacheHome(): Path? =
    userCacheDir()?.resolve("opencode")?.takeIf { Files.isDirectory(it) }

internal fun opencodeAppData(): Path? =
    appDataRoaming()?.resolve("ai.opencode.desktop")?.takeIf { Files.isDirectory(it) }

/** Open Code 数据库文件：~/.local/share/opencode/opencode.db */
internal fun opencodeDbFile(): Path? =
    userDataDir()?.resolve("opencode")?.resolve("opencode.db")
        ?.takeIf { Files.isRegularFile(it) }

/** Open Code 存储目录：~/.local/share/opencode/storage/ */
internal fun opencodeStorageDir(): Path? =
    userDataDir()?.resolve("opencode")?.resolve("storage")
        ?.takeIf { Files.isDirectory(it) }

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
