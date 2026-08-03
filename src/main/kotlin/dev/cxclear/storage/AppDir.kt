package dev.cxclear.storage

import dev.cxclear.profiles.homeDir
import java.nio.file.Path

/**
 * 应用自身配置/记录的落盘目录（`~/.cxclear`）。
 *
 * 单独抽出来是为了给测试一个注入点：清理历史与保留策略都写在这里，
 * 测试若直接落到真实主目录会污染用户数据，也无法并行。
 * 生产代码永远走 [homeDir]，只有测试通过 [overrideForTest] 改写。
 */
object AppDir {
    @Volatile
    private var override: Path? = null

    /** 仅供测试：把配置目录指向临时路径；传 null 恢复真实主目录。 */
    internal fun overrideForTest(dir: Path?) {
        override = dir
    }

    /** 配置目录。主目录不可用时返回 null，调用方一律降级为「没有记录」。 */
    fun dir(): Path? = override ?: homeDir()?.resolve(".cxclear")
}
