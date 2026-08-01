package dev.cxclear.scan

import dev.cxclear.model.CleanTarget
import dev.cxclear.model.MatchKind
import dev.cxclear.model.Risk
import dev.cxclear.model.ToolProfile
import dev.cxclear.model.TargetKey
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ScannerSafetyTest {
    @Test
    fun `missing target-specific base never falls back to profile base`() {
        val profileBase = Files.createTempDirectory("cxclear-profile")
        val target = target(relPath = "", baseDir = { null })
        val profile = profile(profileBase, target)

        assertEquals(null, resolveBase(profile, target))
    }

    @Test
    fun `dot dot cannot escape the allowed base`() {
        val base = Files.createTempDirectory("cxclear-base")
        val outside = Files.writeString(base.parent.resolve("cxclear-outside-${System.nanoTime()}.txt"), "keep")
        val target = target(relPath = "../${outside.fileName}", kind = MatchKind.FILE)

        val resolved = resolveTarget(base, target)

        assertTrue(resolved.paths.isEmpty())
        assertTrue(Files.exists(outside))
        Files.deleteIfExists(outside)
    }

    @Test
    fun `directory contents never traverse a directory symbolic link`() {
        val base = Files.createTempDirectory("cxclear-base")
        val outside = Files.createTempDirectory("cxclear-outside")
        val protected = Files.writeString(outside.resolve("user-file.txt"), "keep")
        val link = base.resolve("cache")
        val linked = createDirectoryLink(link, outside)
        assertTrue(linked, "test environment must support a symbolic link or Windows junction")

        val resolved = resolveTarget(base, target(relPath = "cache"))

        assertTrue(resolved.paths.isEmpty())
        assertTrue(Files.exists(protected))
    }

    @Test
    fun `deletion plan records but never traverses a nested directory link`() {
        val base = Files.createTempDirectory("cxclear-base")
        val cache = Files.createDirectory(base.resolve("cache"))
        val local = Files.writeString(cache.resolve("local.tmp"), "local")
        val outside = Files.createTempDirectory("cxclear-outside")
        val protected = Files.writeString(outside.resolve("user-file.txt"), "keep")
        val link = cache.resolve("linked")
        assertTrue(
            createDirectoryLink(link, outside),
            "test environment must support a symbolic link or Windows junction",
        )

        val target = target(relPath = "cache")
        val result = scanResolved("test", resolveTarget(base, target))
        val plan = assertNotNull(result.deletionPlan)

        assertTrue(plan.entries.any { it.path == local })
        assertTrue(plan.entries.any { it.path == link })
        assertFalse(plan.entries.any { it.path == protected })
        assertTrue(Files.exists(protected))
    }

    @Test
    fun `file target never accepts a same-named directory`() {
        val base = Files.createTempDirectory("cxclear-base")
        Files.createDirectory(base.resolve("state.json"))

        val resolved = resolveTarget(base, target(relPath = "state.json", kind = MatchKind.FILE))

        assertTrue(resolved.paths.isEmpty())
    }

    @Test
    fun `file glob never accepts a matching directory`() {
        val base = Files.createTempDirectory("cxclear-base")
        Files.createDirectory(base.resolve(".claude.json.backup-user-data"))

        val resolved = resolveTarget(
            base,
            target(relPath = ".claude.json.backup*", kind = MatchKind.GLOB),
        )

        assertTrue(resolved.paths.isEmpty())
    }

    @Test
    fun `stale versions preserve every item tied for newest mtime`() {
        val base = Files.createTempDirectory("cxclear-base")
        val first = Files.writeString(base.resolve("runner-1.exe"), "one")
        val second = Files.writeString(base.resolve("runner-2.exe"), "two")
        val sameTime = FileTime.fromMillis(1_700_000_000_000L)
        Files.setLastModifiedTime(first, sameTime)
        Files.setLastModifiedTime(second, sameTime)

        val resolved = resolveTarget(
            base,
            target(relPath = "runner-*.exe", kind = MatchKind.STALE_VERSIONS),
        )

        assertTrue(resolved.paths.isEmpty())
        assertTrue(Files.exists(first))
        assertTrue(Files.exists(second))
    }

    @Test
    fun `stale versions delete only entries strictly older than newest`() {
        val base = Files.createTempDirectory("cxclear-base")
        val old = Files.writeString(base.resolve("runner-old.exe"), "old")
        val newest = Files.writeString(base.resolve("runner-new.exe"), "new")
        Files.setLastModifiedTime(old, FileTime.fromMillis(1_600_000_000_000L))
        Files.setLastModifiedTime(newest, FileTime.fromMillis(1_700_000_000_000L))

        val resolved = resolveTarget(
            base,
            target(relPath = "runner-*.exe", kind = MatchKind.STALE_VERSIONS),
        )

        assertEquals(listOf(old), resolved.paths)
        assertFalse(resolved.paths.contains(newest))
    }

    @Test
    fun `duplicate target ids from different tools remain isolated`() = runBlocking {
        val firstBase = Files.createTempDirectory("cxclear-first")
        val secondBase = Files.createTempDirectory("cxclear-second")
        Files.writeString(firstBase.resolve("cache.bin"), "1")
        Files.writeString(secondBase.resolve("cache.bin"), "22")
        val firstTarget = target("cache.bin", MatchKind.FILE)
        val secondTarget = target("cache.bin", MatchKind.FILE)
        val profiles = listOf(
            profile(firstBase, firstTarget).copy(id = "first"),
            profile(secondBase, secondTarget).copy(id = "second"),
        )

        val finalResults = scanStream(profiles).toList()
            .filterIsInstance<ScanEvent.TargetsScanned>()
            .last()
            .results

        assertEquals(2, finalResults.size)
        assertEquals(
            setOf(TargetKey("first", "test.target"), TargetKey("second", "test.target")),
            finalResults.mapTo(mutableSetOf()) { TargetKey(it.toolId, it.targetId) },
        )
        assertEquals(setOf(1L, 2L), finalResults.mapTo(mutableSetOf()) { it.bytes })
    }

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

    private fun target(
        relPath: String,
        kind: MatchKind = MatchKind.DIRECTORY_CONTENTS,
        baseDir: (() -> Path?)? = null,
    ) = CleanTarget(
        id = "test.target",
        label = "test",
        relPath = relPath,
        kind = kind,
        risk = Risk.SAFE,
        description = "test",
        baseDir = baseDir,
    )

    private fun profile(base: Path, target: CleanTarget) = ToolProfile(
        id = "test",
        name = "test",
        subtitle = "test",
        baseDir = { base },
        targets = listOf(target),
    )
}
