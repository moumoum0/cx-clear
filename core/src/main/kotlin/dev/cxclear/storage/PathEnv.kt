package dev.cxclear.storage

import dev.cxclear.platform.HostOs
import dev.cxclear.platform.currentOs
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private fun firstExistingDir(candidates: List<String>): Path? = candidates.asSequence()
    .filter { it.isNotBlank() }
    .map { Paths.get(it) }
    .firstOrNull { Files.isDirectory(it) }

private fun xdgPath(env: String, vararg homeParts: String): Path? {
    System.getenv(env)?.takeIf { it.isNotBlank() }?.let { return Paths.get(it) }
    return homeParts.fold(homeDir()) { acc, part -> acc?.resolve(part) }
}

/**
 * 用户主目录。Windows 上 USERPROFILE 比 user.home 更可靠（后者在某些 JVM 下指向别处）。
 *
 * 放在 storage 而不是 tools：AppDir、会话扫描都要用它，留在某个软件目录会让
 * storage 仅为这一个函数反向依赖工具名单。
 */
internal fun homeDir(): Path? = firstExistingDir(
    buildList {
        if (currentOs() == HostOs.WINDOWS) System.getenv("USERPROFILE")?.let { add(it) }
        System.getProperty("user.home")?.let { add(it) }
    }
)

/** `%APPDATA%`（Roaming）。非 Windows 走 [userConfigDir]。 */
internal fun appDataRoaming(): Path? = when (currentOs()) {
    HostOs.WINDOWS -> firstExistingDir(
        buildList {
            System.getenv("APPDATA")?.let { add(it) }
            homeDir()?.resolve("AppData")?.resolve("Roaming")?.toString()?.let { add(it) }
        }
    )
    else -> userConfigDir()
}

/** `%LOCALAPPDATA%`。非 Windows 走 [userCacheDir]。 */
internal fun appDataLocal(): Path? = when (currentOs()) {
    HostOs.WINDOWS -> firstExistingDir(
        buildList {
            System.getenv("LOCALAPPDATA")?.let { add(it) }
            homeDir()?.resolve("AppData")?.resolve("Local")?.toString()?.let { add(it) }
        }
    )
    else -> userCacheDir()
}

/** XDG 数据目录。Windows 仍是 `~/.local/share`，不读 XDG_DATA_HOME。 */
internal fun userDataDir(): Path? = when (currentOs()) {
    HostOs.LINUX -> xdgPath("XDG_DATA_HOME", ".local", "share")
    else -> homeDir()?.resolve(".local")?.resolve("share")
}

/** 用户配置目录。Windows 仍是 `~/.config`，不是 `%APPDATA%`。 */
internal fun userConfigDir(): Path? = when (currentOs()) {
    HostOs.MACOS -> homeDir()?.resolve("Library")?.resolve("Application Support")
    HostOs.LINUX -> xdgPath("XDG_CONFIG_HOME", ".config")
    else -> homeDir()?.resolve(".config")
}

/** 用户缓存目录。Windows 仍是 `~/.cache`，不读 XDG_CACHE_HOME。 */
internal fun userCacheDir(): Path? = when (currentOs()) {
    HostOs.LINUX -> xdgPath("XDG_CACHE_HOME", ".cache")
    else -> homeDir()?.resolve(".cache")
}
