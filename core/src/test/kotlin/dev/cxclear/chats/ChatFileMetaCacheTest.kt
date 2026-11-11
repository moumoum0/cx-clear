package dev.cxclear.chats

import dev.cxclear.storage.AppDir
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatFileMetaCacheTest {
    @TempDir
    lateinit var tempDir: Path

    private val file: Path get() = tempDir.resolve("sessions").resolve("a.jsonl")

    @BeforeTest
    fun setUp() {
        AppDir.overrideForTest(tempDir)
        ChatFileMetaCache.invalidate()
    }

    @AfterTest
    fun tearDown() {
        AppDir.overrideForTest(null)
        ChatFileMetaCache.invalidate()
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `lookup hits on matching mtime and size`() {
        ChatFileMetaCache.remember("claude", file, mtime = 100L, size = 5L, fields = mapOf("title" to "测试标题"))

        val cached = ChatFileMetaCache.lookup("claude", file, mtime = 100L, size = 5L)

        assertEquals("测试标题", cached?.get("title"))
    }

    @Test
    fun `lookup misses when mtime or size differs`() {
        ChatFileMetaCache.remember("claude", file, mtime = 100L, size = 5L, fields = mapOf("title" to "旧"))

        assertNull(ChatFileMetaCache.lookup("claude", file, mtime = 200L, size = 5L))
        assertNull(ChatFileMetaCache.lookup("claude", file, mtime = 100L, size = 6L))
        assertNull(ChatFileMetaCache.lookup("claude", file, mtime = 200L, size = 6L))
    }

    @Test
    fun `keys are isolated per tool and path`() {
        ChatFileMetaCache.remember("claude", file, mtime = 100L, size = 5L, fields = mapOf("title" to "claude 的"))
        ChatFileMetaCache.remember("codex", file, mtime = 100L, size = 5L, fields = mapOf("title" to "codex 的"))

        assertEquals("claude 的", ChatFileMetaCache.lookup("claude", file, 100L, 5L)?.get("title"))
        assertEquals("codex 的", ChatFileMetaCache.lookup("codex", file, 100L, 5L)?.get("title"))
        assertNull(ChatFileMetaCache.lookup("cursor", file, 100L, 5L))
        assertNull(ChatFileMetaCache.lookup("claude", tempDir.resolve("other.jsonl"), 100L, 5L))
    }

    @Test
    fun `flushed entries survive reload from disk`() {
        ChatFileMetaCache.remember(
            "deepseek-hermes", file, mtime = 100L, size = 5L,
            fields = mapOf(
                "id" to "session-1",
                "cwd" to null,
                "title" to "落盘标题",
                "updatedMillis" to 4000L,
                "subagent" to true,
            ),
        )
        ChatFileMetaCache.remember("claude", tempDir.resolve("b.jsonl"), 1L, 2L, mapOf("title" to null))
        ChatFileMetaCache.flush()

        assertTrue(Files.exists(tempDir.resolve("chat-meta-cache.json")))

        // 模拟重启：清内存后从磁盘重读
        ChatFileMetaCache.invalidate()

        val cached = ChatFileMetaCache.lookup("deepseek-hermes", file, 100L, 5L)
        assertEquals("session-1", cached?.get("id"))
        assertNull(cached?.get("cwd"))
        assertEquals("落盘标题", cached?.get("title"))
        assertEquals(4000L, (cached?.get("updatedMillis") as? Number)?.toLong())
        assertEquals(true, cached?.get("subagent"))

        // null 值也要保留：title=null 表示上次确实没拿到，避免下次重读
        val claude = ChatFileMetaCache.lookup("claude", tempDir.resolve("b.jsonl"), 1L, 2L)
        assertTrue(claude?.containsKey("title") == true)
        assertNull(claude.get("title"))
    }

    @Test
    fun `corrupt cache file starts empty`() {
        ChatFileMetaCache.remember("claude", file, 100L, 5L, mapOf("title" to "x"))
        ChatFileMetaCache.flush()

        Files.writeString(tempDir.resolve("chat-meta-cache.json"), "不是 JSON{")
        ChatFileMetaCache.invalidate()

        assertNull(ChatFileMetaCache.lookup("claude", file, 100L, 5L))
        // 损坏后仍可继续写入
        ChatFileMetaCache.remember("claude", file, 100L, 5L, mapOf("title" to "y"))
        assertEquals("y", ChatFileMetaCache.lookup("claude", file, 100L, 5L)?.get("title"))
    }

    @Test
    fun `version mismatch starts empty`() {
        Files.createDirectories(tempDir.resolve("chat-meta-cache.json").parent)
        Files.writeString(
            tempDir.resolve("chat-meta-cache.json"),
            """{"version":999,"entries":{"claude|C:\\x.jsonl":{"mtime":1,"size":1,"fields":{"title":"旧"}}}}""",
        )
        ChatFileMetaCache.invalidate()

        assertNull(ChatFileMetaCache.lookup("claude", Path.of("C:\\x.jsonl"), 1L, 1L))
    }

    @Test
    fun `flush skips disk write when nothing new`() {
        assertFalse(Files.exists(tempDir.resolve("chat-meta-cache.json")))
        ChatFileMetaCache.flush()
        assertFalse(Files.exists(tempDir.resolve("chat-meta-cache.json")))
    }
}
