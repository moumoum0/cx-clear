package dev.cxclear.tools.deepseekhermes

import dev.cxclear.scan.resolveTarget
import dev.cxclear.scan.scanResolved
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DeepSeekHermesProfileTest {
    @TempDir
    lateinit var base: Path

    @Test
    fun `partition cleanup selects only cache files across multiple partitions`() {
        val caches = listOf("Cache", "GPUCache", "Code Cache", "DawnGraphiteCache", "DawnWebGPUCache")
        val persistentStores = listOf(
            "IndexedDB", "Local Storage", "Session Storage", "Network", "WebStorage", "blob_storage",
        )
        val expected = mutableSetOf<Path>()
        for (partition in listOf("dsh-platform-first", "dsh-platform-second")) {
            for (directory in caches + persistentStores) {
                val dir = Files.createDirectories(base.resolve("Partitions/$partition/$directory"))
                val file = Files.writeString(dir.resolve("data.bin"), "data")
                if (directory in caches) expected.add(file)
            }
            for (file in listOf("DIPS", "DIPS-wal", "DIPS-shm", "Preferences")) {
                Files.writeString(base.resolve("Partitions/$partition/$file"), "keep")
            }
        }

        assertEquals(expected, plannedPaths())
    }

    @Test
    fun `cleanup preserves DIPS database and its recovery files`() {
        for (file in listOf("DIPS", "DIPS-wal", "DIPS-shm", "DIPS-journal")) {
            Files.writeString(base.resolve(file), "keep")
        }

        assertTrue(plannedPaths().isEmpty(), "DIPS and its recovery files must not enter any deletion plan")
    }

    private fun plannedPaths(): Set<Path> = DeepSeekHermesProfile.targets
        .filter { it.id.startsWith("dsh.desktop-") }
        .flatMap { target ->
            // Resolve desktop rules against the isolated fixture, never the user's data.
            val result = scanResolved(DeepSeekHermesProfile.id, resolveTarget(base, target))
            assertNotNull(result.deletionPlan).entries.map { it.path }
        }.toSet()
}
