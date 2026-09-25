package dev.cxclear.tools.deepseekhermes

import dev.cxclear.storage.appDataLocal
import dev.cxclear.storage.appDataRoaming
import dev.cxclear.tools.homeSubdir
import java.nio.file.Files
import java.nio.file.Path

internal fun dshHome(): Path? = homeSubdir(".dsh")

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
