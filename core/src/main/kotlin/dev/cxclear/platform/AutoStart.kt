package dev.cxclear.platform

import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path

// GUI 侧的开机自启动：HKCU Run 键启动「CX Clear.exe --autostart」。
// 安装器 setup.iss 的 [Registry] 写的是同一个值名，卸载靠 uninsdeletevalue 清除。
object AutoStart {
    const val AUTOSTART_ARG = "--autostart"

    const val VALUE_NAME = "CX Clear"

    // 安装包布局：{app}/app/*.jar + {app}/CX Clear.exe（GUI 启动器）
    private const val EXE_NAME = "CX Clear.exe"
    private const val RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"

    data class RegResult(val exitCode: Int, val output: String) {
        val ok: Boolean get() = exitCode == 0
    }

    // 注册表 Run 键里的值：带引号的 exe 路径 + 静默清理参数
    internal fun runValue(exe: Path): String = "\"$exe\" $AUTOSTART_ARG"

    internal fun regQueryCommand(): List<String> =
        listOf("reg", "query", RUN_KEY, "/v", VALUE_NAME)

    internal fun regAddCommand(exe: Path): List<String> =
        listOf(
            "reg", "add", RUN_KEY,
            "/v", VALUE_NAME, "/t", "REG_SZ",
            // reg.exe parses embedded quotes using Windows command-line escaping.
            "/d", runValue(exe).replace("\"", "\\\""), "/f",
        )

    internal fun regDeleteCommand(): List<String> =
        listOf("reg", "delete", RUN_KEY, "/v", VALUE_NAME, "/f")

    // 从本类所在 jar 反推安装目录里的 GUI 启动器。
    // 开发环境跑的是 classes 目录，返回 null；独立 CLI 包只有 cxclear.exe，也返回 null。
    fun installedExePath(): Path? {
        if (currentOs() != HostOs.WINDOWS) return null
        val location = AutoStart::class.java.protectionDomain?.codeSource?.location ?: return null
        val jar = runCatching { Path.of(location.toURI()) }.getOrNull() ?: return null
        if (!Files.isRegularFile(jar)) return null
        val root = jar.parent?.parent ?: return null
        val exe = root.resolve(EXE_NAME)
        if (!Files.isRegularFile(exe)) return null
        return exe.toAbsolutePath().normalize()
    }

    fun isEnabled(): Boolean =
        currentOs() == HostOs.WINDOWS && runReg(*regQueryCommand().toTypedArray()).exitCode == 0

    fun enable(exe: Path): RegResult = runReg(*regAddCommand(exe).toTypedArray())

    // 值本来就不存在视为成功；删除失败才是失败
    fun disable(): RegResult {
        if (runReg(*regQueryCommand().toTypedArray()).exitCode != 0) {
            return RegResult(0, "")
        }
        return runReg(*regDeleteCommand().toTypedArray())
    }

    // reg 的输出走系统 OEM 编码（中文 Windows 是 GBK），不是 UTF-8
    private val consoleCharset: Charset
        get() = runCatching { Charset.forName(System.getProperty("native.encoding")) }
            .getOrDefault(Charset.defaultCharset())

    // 传入的命令列表以 "reg" 开头（regXxxCommand 返回完整命令行）
    private fun runReg(vararg command: String): RegResult {
        val process = ProcessBuilder(*command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.readBytes().toString(consoleCharset)
        return RegResult(process.waitFor(), output)
    }
}
