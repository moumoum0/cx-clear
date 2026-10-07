package dev.cxclear.storage

import java.nio.file.Path

object AppDir {
    @Volatile
    private var override: Path? = null

    // 仅供测试：把配置目录指向临时路径。传 null 恢复用户主目录。
    internal fun overrideForTest(dir: Path?) {
        override = dir
    }

    fun dir(): Path? = override ?: homeDir()?.resolve(".cxclear")
}
