package dev.cxclear.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CliArgsTest {
    @Test
    fun `empty args stay in gui path`() {
        assertNull(parseArgs(emptyArray()))
    }

    @Test
    fun `parses nested command flags and yes`() {
        val parsed = parseArgs(arrayOf("chats", "delete", "--id", "cursor:abc", "--id=claude:x", "--yes"))!!
        assertEquals(listOf("chats", "delete"), parsed.command)
        assertEquals(listOf("cursor:abc", "claude:x"), parsed.values("id"))
        assertTrue(parsed.yes)
    }

    @Test
    fun `help flag overrides command`() {
        val parsed = parseArgs(arrayOf("scan", "--help"))!!
        assertEquals(listOf("help"), parsed.command)
    }

    @Test
    fun `unknown flag is usage error`() {
        assertFailsWith<CliUsageException> {
            parseArgs(arrayOf("scan", "--nope"))
        }
    }

    @Test
    fun `schema command has no flags`() {
        val parsed = parseArgs(arrayOf("schema"))!!
        assertEquals(listOf("schema"), parsed.command)
        assertTrue(parsed.flags.isEmpty())
    }
}
