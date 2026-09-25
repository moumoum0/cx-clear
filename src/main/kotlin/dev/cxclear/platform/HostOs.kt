package dev.cxclear.platform

/** 宿主系统。路径、进程名、磁盘都从这里分发，不要再各写一份 os.name。 */
enum class HostOs { WINDOWS, MACOS, LINUX, OTHER }

internal fun currentOs(): HostOs {
    val name = System.getProperty("os.name").orEmpty()
    return when {
        name.startsWith("Windows", ignoreCase = true) -> HostOs.WINDOWS
        name.startsWith("Mac", ignoreCase = true) -> HostOs.MACOS
        name.startsWith("Linux", ignoreCase = true) -> HostOs.LINUX
        else -> HostOs.OTHER
    }
}

/** 进程名去掉平台后缀后再对 processNamePrefixes。 */
internal fun executableStem(fileName: String): String {
    val name = fileName.lowercase().removeSuffix(".exe")
    return if (currentOs() == HostOs.MACOS) name.removeSuffix(".app") else name
}
