package dev.cxclear.storage

import java.nio.file.Path

/**
 * 应用自身落盘目录（`~/.cxclear`）。抽出来是给测试注入点：
 * 生产走 [homeDir]，测试用 [overrideForTest] 改写，免得落到用户主目录。
 */
object AppDir {
    @Volatile
    private var override: Path? = null

    // 仅供测试：把配置目录指向临时路径。传 null 恢复用户主目录。
    internal fun overrideForTest(dir: Path?) {
        override = dir
    }

    fun dir(): Path? = override ?: homeDir()?.resolve(".cxclear")
}
