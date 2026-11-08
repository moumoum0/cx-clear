package dev.cxclear.cli

import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path

internal const val AUTOCLEAN_TASK_NAME = "CX Clear AutoClean"

// 计划任务执行的子命令；改这里要同步 autocleanTaskXml 和 status 输出
internal const val AUTOCLEAN_TASK_ARGS = "autoclean run --yes"

// 计划任务要一个能独立拉起 CLI 的命令。优先打包好的 cxclear.exe；
// npm 包没有 exe（node 调 java），就退回 java.exe -cp <jar 目录>\*。
// 任务以 S4U 非交互方式运行，java.exe 也不会弹控制台窗口。
internal sealed interface AutoCleanLaunch {
    val command: String
    val arguments: String
    val display: String get() = "$command $arguments"
}

internal data class ExeLaunch(val exe: Path) : AutoCleanLaunch {
    override val command: String get() = exe.toString()
    override val arguments: String get() = AUTOCLEAN_TASK_ARGS
}

internal data class JavaLaunch(val java: Path, val classpathDir: Path) : AutoCleanLaunch {
    override val command: String get() = java.toString()
    override val arguments: String
        get() = "-Dfile.encoding=UTF-8 -cp \"$classpathDir\\*\" dev.cxclear.cli.MainKt $AUTOCLEAN_TASK_ARGS"
}

// jar 与 exe 的相对位置：
//   安装包 {app}/cli/cxclear.exe + {app}/app/*.jar
//   独立 CLI 包 <root>/cxclear.exe + <root>/lib/*.jar
//   npm 包 <root>/native/lib/*.jar（没有 exe，走 JavaLaunch）
// 开发环境跑的是 classes 目录不是 jar，返回 null。
internal fun resolveAutocleanLaunch(): AutoCleanLaunch? {
    val location = Cli::class.java.protectionDomain?.codeSource?.location ?: return null
    val jar = runCatching { Path.of(location.toURI()) }.getOrNull() ?: return null
    if (!Files.isRegularFile(jar)) return null
    val classpathDir = jar.parent ?: return null
    val root = classpathDir.parent ?: return null
    listOf(root.resolve("cli/cxclear.exe"), root.resolve("cxclear.exe"))
        .firstOrNull { Files.isRegularFile(it) }
        ?.let { return ExeLaunch(it.toAbsolutePath().normalize()) }
    val java = Path.of(System.getProperty("java.home"), "bin", "java.exe")
    if (!Files.isRegularFile(java)) return null
    return JavaLaunch(java.toAbsolutePath().normalize(), classpathDir.toAbsolutePath().normalize())
}

internal data class SchtasksResult(val exitCode: Int, val output: String)

// schtasks 的输出走系统 OEM 编码（中文 Windows 是 GBK），不是 UTF-8
private val consoleCharset: Charset
    get() = runCatching { Charset.forName(System.getProperty("native.encoding")) }
        .getOrDefault(Charset.defaultCharset())

internal fun runSchtasks(vararg args: String): SchtasksResult {
    val process = ProcessBuilder("schtasks", *args)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.readBytes().toString(consoleCharset)
    return SchtasksResult(process.waitFor(), output)
}

internal fun autocleanTaskInstalled(): Boolean =
    runCatching { runSchtasks("/query", "/tn", AUTOCLEAN_TASK_NAME).exitCode == 0 }
        .getOrDefault(false)

// 当前用户的 NT 账号名（domain\user），任务触发器和 Principal 都要用它限定到本用户
internal fun currentNtUser(): String {
    val whoami = runCatching {
        val process = ProcessBuilder("whoami").redirectErrorStream(true).start()
        val text = process.inputStream.readBytes().toString(consoleCharset).trim()
        text.takeIf { process.waitFor() == 0 && it.contains('\\') }
    }.getOrNull()
    if (whoami != null) return whoami
    val user = System.getProperty("user.name").orEmpty()
    val domain = System.getenv("USERDOMAIN").orEmpty()
    return if (domain.isEmpty()) user else "$domain\\$user"
}

private fun xmlEscape(raw: String): String = raw
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")

// S4U 登录方式不需要密码、不弹控制台窗口；LogonTrigger 限定只在当前用户登录时触发
internal fun autocleanTaskXml(launch: AutoCleanLaunch, ntUser: String): String {
    val escapedUser = xmlEscape(ntUser)
    return """<?xml version="1.0" encoding="UTF-16"?>
<Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
  <RegistrationInfo>
    <Description>Run the CX Clear auto-clean policy at logon</Description>
  </RegistrationInfo>
  <Triggers>
    <LogonTrigger>
      <Enabled>true</Enabled>
      <UserId>$escapedUser</UserId>
    </LogonTrigger>
  </Triggers>
  <Principals>
    <Principal id="Author">
      <UserId>$escapedUser</UserId>
      <LogonType>S4U</LogonType>
      <RunLevel>LeastPrivilege</RunLevel>
    </Principal>
  </Principals>
  <Settings>
    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
    <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
    <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
    <StartWhenAvailable>true</StartWhenAvailable>
    <AllowHardTerminate>true</AllowHardTerminate>
    <ExecutionTimeLimit>PT10M</ExecutionTimeLimit>
    <Enabled>true</Enabled>
  </Settings>
  <Actions context="Author">
    <Exec>
      <Command>${xmlEscape(launch.command)}</Command>
      <Arguments>${xmlEscape(launch.arguments)}</Arguments>
    </Exec>
  </Actions>
</Task>
"""
}

internal fun installAutocleanTask(launch: AutoCleanLaunch): SchtasksResult {
    // schtasks /xml 只认 UTF-16 文件
    val xml = Files.createTempFile("cxclear-autoclean", ".xml")
    try {
        Files.writeString(xml, autocleanTaskXml(launch, currentNtUser()), Charsets.UTF_16)
        return runSchtasks("/create", "/tn", AUTOCLEAN_TASK_NAME, "/xml", xml.toString(), "/f")
    } finally {
        Files.deleteIfExists(xml)
    }
}
