package dev.cxclear.scan

import dev.cxclear.model.CleanTarget
import dev.cxclear.model.MatchKind
import dev.cxclear.model.Risk
import dev.cxclear.model.ToolProfile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
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
        val linked = runCatching { Files.createSymbolicLink(link, outside) }.isSuccess
        if (!linked) return

        val resolved = resolveTarget(base, target(relPath = "cache"))

        assertTrue(resolved.paths.isEmpty())
        assertTrue(Files.exists(protected))
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
