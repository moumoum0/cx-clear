package dev.cxclear.platform

import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutoStartTest {
    @Test
    fun `reg exe stores the complete startup command`() {
        if (currentOs() != HostOs.WINDOWS) return
        val key = "HKCU\\Software\\CXClearTests\\${UUID.randomUUID()}"
        val exe = Path.of("C:\\Apps\\CX Clear 中文\\CX Clear.exe")
        val command = AutoStart.regAddCommand(exe).toMutableList().apply { this[2] = key }
        try {
            val add = ProcessBuilder(command).redirectErrorStream(true).start()
            val output = add.inputStream.readBytes()
            assertEquals(0, add.waitFor(), output.decodeToString())
            val query = ProcessBuilder("reg", "query", key, "/v", AutoStart.VALUE_NAME)
                .redirectErrorStream(true).start()
            val stored = query.inputStream.readBytes().toString(
                java.nio.charset.Charset.forName(System.getProperty("native.encoding")),
            )
            assertEquals(0, query.waitFor(), stored)
            assertEquals(AutoStart.runValue(exe), stored.substringAfter("REG_SZ").trim())
        } finally {
            val delete = ProcessBuilder("reg", "delete", key, "/f").redirectErrorStream(true).start()
            delete.inputStream.readBytes()
            delete.waitFor()
        }
    }

    @Test
    fun `run value quotes the exe path and appends the autostart arg`() {
        val value = AutoStart.runValue(Path.of("C:\\Apps\\CX Clear\\CX Clear.exe"))
        assertEquals("\"C:\\Apps\\CX Clear\\CX Clear.exe\" ${AutoStart.AUTOSTART_ARG}", value)
    }

    @Test
    fun `reg add command writes the run key with the quoted value`() {
        val cmd = AutoStart.regAddCommand(Path.of("C:\\Apps\\CX Clear\\CX Clear.exe"))
        assertEquals(
            listOf(
                "reg", "add", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
                "/v", "CX Clear", "/t", "REG_SZ",
                "/d", "\\\"C:\\Apps\\CX Clear\\CX Clear.exe\\\" ${AutoStart.AUTOSTART_ARG}",
                "/f",
            ),
            cmd,
        )
    }

    @Test
    fun `query and delete target the same run key value`() {
        val query = AutoStart.regQueryCommand()
        assertEquals(listOf("reg", "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run", "/v", "CX Clear"), query)

        val delete = AutoStart.regDeleteCommand()
        assertEquals(listOf("reg", "delete", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run", "/v", "CX Clear", "/f"), delete)

        assertEquals(query[2], delete[2])
        assertEquals(query[4], delete[4])
    }

    @Test
    fun `dev classpath resolves no exe`() {
        // 测试进程跑在 classes 目录上，installedExePath 必须返回 null 而不是瞎猜
        assertNull(AutoStart.installedExePath())
    }

    @Test
    fun `enable result ok only on zero exit code`() {
        assertTrue(AutoStart.RegResult(0, "成功").ok)
        assertTrue(!AutoStart.RegResult(1, "找不到指定的注册表项或值。").ok)
    }
}
