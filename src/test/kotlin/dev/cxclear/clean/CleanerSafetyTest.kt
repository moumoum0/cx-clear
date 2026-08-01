package dev.cxclear.clean

import dev.cxclear.model.CleanEvent
import dev.cxclear.model.CleanTarget
import dev.cxclear.model.MatchKind
import dev.cxclear.model.Risk
import dev.cxclear.model.ToolProfile
import dev.cxclear.scan.resolveTarget
import dev.cxclear.scan.scanResolved
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CleanerSafetyTest {
    @Test
    fun `file created after scan is never deleted`() = runBlocking {
        val base = Files.createTempDirectory("cxclear-base")
        val cache = Files.createDirectory(base.resolve("cache"))
        val scanned = Files.writeString(cache.resolve("scanned.tmp"), "old")
        val target = target("cache", MatchKind.DIRECTORY_CONTENTS)
        val profile = profile(base, target)
        val plan = assertNotNull(scanResolved(profile.id, resolveTarget(base, target)).deletionPlan)
        val createdLater = Files.writeString(cache.resolve("created-later.txt"), "keep")

        val events = clean(
            listOf(CleanRequest(profile, target, plan)),
            toolIsRunning = { false },
        ).toList()

        assertFalse(Files.exists(scanned))
        assertTrue(Files.exists(createdLater))
        assertTrue(Files.isDirectory(cache))
        assertEquals(0, events.filterIsInstance<CleanEvent.AllDone>().single().failures)
    }

    @Test
    fun `file replaced after scan is never deleted`() = runBlocking {
        val base = Files.createTempDirectory("cxclear-base")
        val file = Files.writeString(base.resolve("cache.bin"), "old")
        val target = target("cache.bin", MatchKind.FILE)
        val profile = profile(base, target)
        val plan = assertNotNull(scanResolved(profile.id, resolveTarget(base, target)).deletionPlan)
        Files.delete(file)
        Files.writeString(file, "new user content with a different identity")

        val events = clean(
            listOf(CleanRequest(profile, target, plan)),
            toolIsRunning = { false },
        ).toList()

        assertTrue(Files.exists(file))
        assertEquals("new user content with a different identity", Files.readString(file))
        assertEquals(0L, events.filterIsInstance<CleanEvent.AllDone>().single().totalFreedBytes)
    }

    @Test
    fun `file modified in place after scan is never deleted`() = runBlocking {
        val base = Files.createTempDirectory("cxclear-base")
        val file = Files.writeString(base.resolve("cache.bin"), "old")
        val target = target("cache.bin", MatchKind.FILE)
        val profile = profile(base, target)
        val plan = assertNotNull(scanResolved(profile.id, resolveTarget(base, target)).deletionPlan)
        Files.writeString(file, "new user content that changes size")

        val events = clean(
            listOf(CleanRequest(profile, target, plan)),
            toolIsRunning = { false },
        ).toList()

        assertTrue(Files.exists(file))
        assertEquals("new user content that changes size", Files.readString(file))
        assertEquals(0L, events.filterIsInstance<CleanEvent.AllDone>().single().totalFreedBytes)
    }

    @Test
    fun `directory changed after scan aborts before deleting scanned files`() = runBlocking {
        val base = Files.createTempDirectory("cxclear-base")
        val cache = Files.createDirectory(base.resolve("cache"))
        val scanned = Files.writeString(cache.resolve("scanned.tmp"), "old")
        val target = target("cache", MatchKind.DIRECTORY)
        val profile = profile(base, target)
        val plan = assertNotNull(scanResolved(profile.id, resolveTarget(base, target)).deletionPlan)
        val createdLater = Files.writeString(cache.resolve("created-later.txt"), "keep")

        val events = clean(
            listOf(CleanRequest(profile, target, plan)),
            toolIsRunning = { false },
        ).toList()

        assertTrue(Files.exists(scanned))
        assertTrue(Files.exists(createdLater))
        assertTrue(events.filterIsInstance<CleanEvent.TargetDone>().single().error != null)
    }

    @Test
    fun `protected path resolver failure blocks deletion`() = runBlocking {
        val base = Files.createTempDirectory("cxclear-base")
        val file = Files.writeString(base.resolve("cache.bin"), "keep")
        val target = target("cache.bin", MatchKind.FILE)
        val profile = profile(base, target).copy(
            protectedPaths = { error("protected path lookup failed") },
        )
        val plan = assertNotNull(scanResolved(profile.id, resolveTarget(base, target)).deletionPlan)

        val events = clean(
            listOf(CleanRequest(profile, target, plan)),
            toolIsRunning = { false },
        ).toList()

        assertTrue(Files.exists(file))
        assertTrue(events.filterIsInstance<CleanEvent.TargetDone>().single().error != null)
    }

    @Test
    fun `plan for another target is rejected`() = runBlocking {
        val base = Files.createTempDirectory("cxclear-base")
        val firstFile = Files.writeString(base.resolve("first.bin"), "keep")
        val firstTarget = target("first.bin", MatchKind.FILE)
        val secondTarget = firstTarget.copy(id = "second-target", relPath = "second.bin")
        val profile = profile(base, firstTarget).copy(targets = listOf(firstTarget, secondTarget))
        val plan = assertNotNull(scanResolved(profile.id, resolveTarget(base, firstTarget)).deletionPlan)

        val events = clean(
            listOf(CleanRequest(profile, secondTarget, plan)),
            toolIsRunning = { false },
        ).toList()

        assertTrue(Files.exists(firstFile))
        assertTrue(events.filterIsInstance<CleanEvent.TargetDone>().single().error != null)
    }

    @Test
    fun `protected path blocks an ancestor directory plan`() = runBlocking {
        val base = Files.createTempDirectory("cxclear-base")
        val data = Files.createDirectory(base.resolve("data"))
        val protected = Files.createDirectory(data.resolve("protected"))
        val userFile = Files.writeString(protected.resolve("user.db"), "keep")
        val target = target("data", MatchKind.DIRECTORY)
        val profile = profile(base, target).copy(protectedPaths = { listOf(protected) })
        val plan = assertNotNull(scanResolved(profile.id, resolveTarget(base, target)).deletionPlan)

        val events = clean(
            listOf(CleanRequest(profile, target, plan)),
            toolIsRunning = { false },
        ).toList()

        assertTrue(Files.exists(userFile))
        assertTrue(events.filterIsInstance<CleanEvent.TargetDone>().single().error != null)
    }

    @Test
    fun `running tool blocks the entire batch before deletion`() = runBlocking {
        val base = Files.createTempDirectory("cxclear-base")
        val file = Files.writeString(base.resolve("cache.bin"), "keep")
        val target = target("cache.bin", MatchKind.FILE)
        val profile = profile(base, target).copy(id = "codex", name = "Codex")
        val plan = assertNotNull(scanResolved(profile.id, resolveTarget(base, target)).deletionPlan)

        val events = clean(
            listOf(CleanRequest(profile, target, plan)),
            toolIsRunning = { true },
        ).toList()

        assertTrue(Files.exists(file))
        assertIs<CleanEvent.Blocked>(events[1])
        assertEquals(0L, events.filterIsInstance<CleanEvent.AllDone>().single().totalFreedBytes)
    }

    @Test
    fun `directory link is removed without touching its target`() = runBlocking {
        val base = Files.createTempDirectory("cxclear-base")
        val cache = Files.createDirectory(base.resolve("cache"))
        val outside = Files.createTempDirectory("cxclear-outside")
        val userFile = Files.writeString(outside.resolve("user-file.txt"), "keep")
        val link = cache.resolve("linked-directory")
        assertTrue(createDirectoryLink(link, outside), "test environment must support a link or junction")
        val target = target("cache", MatchKind.DIRECTORY_CONTENTS)
        val profile = profile(base, target)
        val plan = assertNotNull(scanResolved(profile.id, resolveTarget(base, target)).deletionPlan)

        clean(
            listOf(CleanRequest(profile, target, plan)),
            toolIsRunning = { false },
        ).toList()

        assertFalse(Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.exists(userFile))
        assertEquals("keep", Files.readString(userFile))
    }

    private fun target(relPath: String, kind: MatchKind) = CleanTarget(
        id = "duplicate-id",
        label = "test",
        relPath = relPath,
        kind = kind,
        risk = Risk.SAFE,
        description = "test",
    )

    private fun profile(base: Path, target: CleanTarget) = ToolProfile(
        id = "test",
        name = "test",
        subtitle = "test",
        baseDir = { base },
        targets = listOf(target),
    )

    private fun createDirectoryLink(link: Path, target: Path): Boolean {
        if (runCatching { Files.createSymbolicLink(link, target) }.isSuccess) return true
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return false
        return runCatching {
            ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true)
                .start()
                .also { it.inputStream.readAllBytes() }
                .waitFor() == 0
        }.getOrDefault(false)
    }
}
