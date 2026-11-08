package dev.cxclear.cli

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliAutoCleanTest {
    @Test
    fun `exe task xml is scoped to the current user and runs non-interactively`() {
        val xml = autocleanTaskXml(
            ExeLaunch(Path.of("C:\\Apps\\CX Clear\\cli\\cxclear.exe")),
            "DESKTOP\\user",
        )
        assertTrue(xml.contains("<LogonTrigger>"))
        assertTrue(xml.contains("<UserId>DESKTOP\\user</UserId>"))
        assertTrue(xml.contains("<LogonType>S4U</LogonType>"))
        assertTrue(xml.contains("<RunLevel>LeastPrivilege</RunLevel>"))
        assertTrue(xml.contains("<Command>C:\\Apps\\CX Clear\\cli\\cxclear.exe</Command>"))
        assertTrue(xml.contains("<Arguments>autoclean run --yes</Arguments>"))
    }

    @Test
    fun `java fallback launches the cli through the jar directory wildcard`() {
        val launch = JavaLaunch(
            Path.of("C:\\Cache\\cxclear-java\\jdk\\bin\\java.exe"),
            Path.of("D:\\Tools\\cxclear\\native\\lib"),
        )
        val xml = autocleanTaskXml(launch, "u\\x")
        assertTrue(xml.contains("<Command>C:\\Cache\\cxclear-java\\jdk\\bin\\java.exe</Command>"))
        assertTrue(xml.contains("-cp \"D:\\Tools\\cxclear\\native\\lib\\*\""))
        assertTrue(xml.contains("dev.cxclear.cli.MainKt autoclean run --yes"))
    }

    @Test
    fun `xml escapes special characters in paths`() {
        val xml = autocleanTaskXml(
            ExeLaunch(Path.of("C:\\R&D\\cxclear.exe")),
            "DOM\\a<b",
        )
        assertFalse(xml.contains("R&D"))
        assertTrue(xml.contains("R&amp;D"))
        assertTrue(xml.contains("DOM\\a&lt;b"))
    }

    @Test
    fun `dev classpath resolves no launch target`() {
        // 测试进程跑在 classes 目录上，resolveAutocleanLaunch 必须返回 null 而不是瞎猜
        assertTrue(resolveAutocleanLaunch() == null)
    }
}
