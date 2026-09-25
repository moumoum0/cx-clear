package dev.cxclear.storage

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private val isWindows: Boolean
    get() = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

private fun firstExistingDir(candidates: List<String>): Path? = candidates.asSequence()
    .filter { it.isNotBlank() }
    .map { Paths.get(it) }
    .firstOrNull { Files.isDirectory(it) }

/**
 * 用户主目录。Windows 上 USERPROFILE 比 user.home 更可靠（后者在某些 JVM 下指向别处）。
 *
 * 放在 storage 而不是 profiles：AppDir、ChatScanner 都要用它，留在 profiles 会让
 * storage 和 chats 仅为这一个函数反向依赖 profile 名单。
 */
internal fun homeDir(): Path? = firstExistingDir(
    buildList {
        if (isWindows) System.getenv("USERPROFILE")?.let { add(it) }
        System.getProperty("user.home")?.let { add(it) }
    }
)

/** `%APPDATA%`（Roaming）。Windows 优先环境变量，再退回 `~/AppData/Roaming`。 */
internal fun appDataRoaming(): Path? = firstExistingDir(
    buildList {
        if (isWindows) System.getenv("APPDATA")?.let { add(it) }
        homeDir()?.resolve("AppData")?.resolve("Roaming")?.toString()?.let { add(it) }
    }
)

/** `%LOCALAPPDATA%`。Windows 优先环境变量，再退回 `~/AppData/Local`。 */
internal fun appDataLocal(): Path? = firstExistingDir(
    buildList {
        if (isWindows) System.getenv("LOCALAPPDATA")?.let { add(it) }
        homeDir()?.resolve("AppData")?.resolve("Local")?.toString()?.let { add(it) }
    }
)
