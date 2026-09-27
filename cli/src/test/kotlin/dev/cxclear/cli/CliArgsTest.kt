package dev.cxclear.cli

import dev.cxclear.model.ScanResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CliArgsTest {
    @Test
    fun `empty args have no parsed command`() {
        assertNull(parseArgs(emptyArray()))
    }

    @Test
    fun `cli shows help with no args`() {
        assertEquals(Cli.EXIT_OK, Cli.run(emptyArray()))
    }

    @Test
    fun `cli reports invalid flags as usage errors`() {
        assertEquals(Cli.EXIT_USAGE, Cli.run(arrayOf("schema", "--nope")))
    }

    @Test
    fun `parses nested command flags and yes`() {
        val parsed = parseArgs(arrayOf("delete", "chats", "--tool", "cursor", "--tool=claude", "--yes"))!!
        assertEquals(listOf("delete", "chats"), parsed.command)
        assertEquals(listOf("cursor", "claude"), parsed.values("tool"))
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

    @Test
    fun `destructive commands reject ignored filters`() {
        assertEquals(Cli.EXIT_USAGE, Cli.run(arrayOf("delete", "files", "--older-than", "7d", "--yes")))
        assertEquals(Cli.EXIT_USAGE, Cli.run(arrayOf("delete", "chats", "--size-gt", "1GB", "--yes")))
    }

    @Test
    fun `yes switch cannot take a false value`() {
        assertFailsWith<CliUsageException> { parseArgs(arrayOf("clean", "--yes=false")) }
    }

    @Test
    fun `negative retention counts and overflowing durations are rejected`() {
        assertFailsWith<CliUsageException> {
            parseChatFilters(parseArgs(arrayOf("delete", "chats", "--keep-days", "-1"))!!)
        }
        assertFailsWith<CliUsageException> { parseDuration("999999999999999999999d") }
        assertFailsWith<CliUsageException> { parseSize("999999999999999999999GB") }
    }

    @Test
    fun `safe target predicate excludes optional conversation history`() {
        assertTrue(isSafeTarget(ScanResult("codex", "codex.logs-db", 1, 1, true)))
        assertEquals(false, isSafeTarget(ScanResult("codex", "codex.sessions", 1, 1, true)))
    }
}
