package dev.cxclear.tools.deepseekhermes

import dev.cxclear.storage.appDataLocal
import dev.cxclear.storage.appDataRoaming
import dev.cxclear.tools.homeSubdir
import java.nio.file.Files
import java.nio.file.Path

internal var dshHomeOverride: Path? = null
// 测试注入。null 时解析用户目录下的 ~/.dsh。

internal fun dshHome(): Path? {
    val override = dshHomeOverride
    if (override != null) return override.takeIf { Files.isDirectory(it) }
    return homeSubdir(".dsh")
}

internal fun dshSessionsRoot(): Path? =
    dshHome()?.resolve("sessions")?.takeIf { Files.isDirectory(it) }

internal fun dshWorkspaceFile(): Path? =
    dshHome()?.resolve("storages")?.resolve("workspace.json")?.takeIf { Files.isRegularFile(it) }

internal fun dshProjCacheFile(home: Path, sessionId: String): Path =
    home.resolve("storages").resolve("session_projcache").resolve("sessions").resolve("$sessionId.json")
// session_projcache 里按会话 id 存的缓存文件，删会话时要一起删掉。

internal fun dshDesktopAppData(): Path? =
    appDataRoaming()?.resolve("@deepseek-ai")?.resolve("dsh-desktop")?.takeIf { Files.isDirectory(it) }

internal fun dshUpdaterCache(): Path? =
    appDataLocal()?.resolve("@deepseek-aidsh-desktop-updater")?.takeIf { Files.isDirectory(it) }

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
