package dev.cxclear.cli

import dev.cxclear.clean.CleanRequest
import dev.cxclear.clean.clean
import dev.cxclear.model.*
import dev.cxclear.scan.resolveTarget
import dev.cxclear.scan.scanResolved
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.*

class CliFiltersTest {
    private fun session(id: String, updated: Long, bytes: Long = 100) = ChatSessionSummary(
        ChatTool("test", "Test"), id, id, null, updated, bytes,
        Path.of(id), Path.of("."), emptyList(),
    )

    @Test
    fun `keep recent handles fewer equal and more matches`() {
        val sessions = listOf(session("old", 1), session("new", 2))
        assertTrue(ChatFilters(keepRecent = 3).apply(sessions, 10).isEmpty())
        assertTrue(ChatFilters(keepRecent = 2).apply(sessions, 10).isEmpty())
        assertEquals(listOf("old"), ChatFilters(keepRecent = 1).apply(sessions, 10).map { it.id })
        assertEquals(sessions, ChatFilters(keepRecent = 0).apply(sessions, 10))
    }

    @Test
    fun `chat size and time filters precede retention`() {
        val sessions = listOf(session("small", 1, 10), session("old", 2, 100), session("new", 8, 100))
        assertEquals(listOf("old"), ChatFilters(sizeGt = 10, sizeLt = 101, olderThan = 1, keepRecent = 1)
            .apply(sessions, 10).map { it.id })
        assertTrue(ChatFilters(keepDays = Int.MAX_VALUE).apply(sessions, 10).isEmpty())
    }

    @Test
    fun `all file commands accept common filters`() {
        for (name in listOf(listOf("find", "files"), listOf("delete", "files"), listOf("scan"), listOf("clean"))) {
            ALL_COMMANDS.first { it.name == name }.validate(parseArgs(
                (name + listOf("--type", "cache", "--older-than", "7d", "--newer-than", "30d", "--size-gt", "1KB", "--size-lt", "1GB")).toTypedArray()
            )!!)
        }
    }

    @Test
    fun `time filtered plan deletes only matching files and preserves directories`() = runBlocking {
        val base = Files.createTempDirectory("cxclear-cli-filters")
        try {
            val dir = Files.createDirectories(base.resolve("cache/nested"))
            val old = Files.writeString(dir.resolve("old"), "old")
            val recent = Files.writeString(dir.resolve("recent"), "recent")
            val now = System.currentTimeMillis()
            Files.setLastModifiedTime(old, FileTime.fromMillis(now - 100_000))
            Files.setLastModifiedTime(recent, FileTime.fromMillis(now - 1_000))
            val target = CleanTarget("cache", "Cache", "cache", MatchKind.DIRECTORY, Risk.SAFE, "")
            val profile = ToolProfile("test", "Test", "", { base }, listOf(target))
            val scanned = scanResolved(profile.id, resolveTarget(base, target))
            val filters = FileFilters(type = "cache", olderThan = 10_000, newerThan = 200_000, sizeGt = 2, sizeLt = 4)
            val filtered = filters.apply(listOf(scanned), now).single()
            assertEquals(3L, filtered.bytes)
            assertEquals(1, filtered.fileCount)
            assertEquals(listOf(old), filtered.deletionPlan!!.entries.map { it.path })
            assertTrue(FileFilters(olderThan = 200_000).apply(listOf(scanned), now).isEmpty())
            assertTrue(FileFilters(sizeGt = 3, olderThan = 10_000).apply(listOf(scanned), now).isEmpty())
            val events = clean(listOf(CleanRequest(profile, target, filtered.deletionPlan!!)), toolIsRunning = { false }).toList()
            assertEquals(0, events.filterIsInstance<CleanEvent.AllDone>().single().failures)
            assertFalse(Files.exists(old))
            assertTrue(Files.exists(recent))
            assertTrue(Files.isDirectory(dir))
        } finally {
            base.toFile().deleteRecursively()
        }
    }
}
