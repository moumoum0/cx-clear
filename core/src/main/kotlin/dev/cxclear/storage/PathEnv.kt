package dev.cxclear.storage

import dev.cxclear.platform.HostOs
import dev.cxclear.platform.currentOs
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

// 用户主目录。Windows 上 USERPROFILE 比 user.home 更可靠。
// 放在 storage：放 tools 会让 storage 反向依赖工具名单。
private fun firstExistingDir(candidates: List<String>): Path? = candidates.asSequence()
    .filter { it.isNotBlank() }
    .map { Paths.get(it) }
    .firstOrNull { Files.isDirectory(it) }

private fun xdgPath(env: String, vararg homeParts: String): Path? {
    System.getenv(env)?.takeIf { it.isNotBlank() }?.let { return Paths.get(it) }
    return homeParts.fold(homeDir()) { acc, part -> acc?.resolve(part) }
}

internal fun homeDir(): Path? = firstExistingDir(
    buildList {
        if (currentOs() == HostOs.WINDOWS) System.getenv("USERPROFILE")?.let { add(it) }
        System.getProperty("user.home")?.let { add(it) }
    }
)

internal fun appDataRoaming(): Path? = when (currentOs()) {
    HostOs.WINDOWS -> firstExistingDir(
        buildList {
            System.getenv("APPDATA")?.let { add(it) }
            homeDir()?.resolve("AppData")?.resolve("Roaming")?.toString()?.let { add(it) }
        }
    )
    else -> userConfigDir()
}

internal fun appDataLocal(): Path? = when (currentOs()) {
    HostOs.WINDOWS -> firstExistingDir(
        buildList {
            System.getenv("LOCALAPPDATA")?.let { add(it) }
            homeDir()?.resolve("AppData")?.resolve("Local")?.toString()?.let { add(it) }
        }
    )
    else -> userCacheDir()
}

internal fun userDataDir(): Path? = when (currentOs()) {
    HostOs.LINUX -> xdgPath("XDG_DATA_HOME", ".local", "share")
    else -> homeDir()?.resolve(".local")?.resolve("share")
}

internal fun userConfigDir(): Path? = when (currentOs()) {
    HostOs.MACOS -> homeDir()?.resolve("Library")?.resolve("Application Support")
    HostOs.LINUX -> xdgPath("XDG_CONFIG_HOME", ".config")
    else -> homeDir()?.resolve(".config")
}

internal fun userCacheDir(): Path? = when (currentOs()) {
    HostOs.LINUX -> xdgPath("XDG_CACHE_HOME", ".cache")
    else -> homeDir()?.resolve(".cache")
}
