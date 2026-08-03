package dev.cxclear.chats

import dev.cxclear.model.PathSnapshotKind
import dev.cxclear.scan.readPathSnapshot
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 会话删除的安全边界。
 *
 * 会话是用户不可恢复的历史记录，比缓存严重得多：这里每条断言都对应一种「删错了就没了」的场景，
 * 所以身份校验、进程阻断、冻结清单都必须逐个钉死。
 */
class ChatDeleterTest {
    private val neverRunning: (ChatTool) -> Boolean = { false }

    private fun tempDir(): Path = Files.createTempDirectory("cxclear-chat")

    /** 用 [mainFile] 及其冻结快照构造一条会话；[extra] 为同级 `<uuid>/` 之类的附带条目。 */
    private fun session(
        mainFile: Path,
        root: Path,
        tool: ChatTool = ChatTool.CLAUDE,
        extra: List<Path> = emptyList(),
    ): ChatSessionSummary {
        val entries = (listOf(mainFile) + extra).mapNotNull { readPathSnapshot(it) }
        return ChatSessionSummary(
            tool = tool,
            id = mainFile.fileName.toString().removeSuffix(".jsonl"),
            title = "t",
            project = "p",
            updatedMillis = 0L,
            sizeBytes = entries.filter { it.kind == PathSnapshotKind.FILE }.sumOf { it.size },
            mainFile = mainFile,
            rootDir = root,
            entries = entries,
        )
    }

    // ─────────────────────────────────────────
    // 正常路径
    // ─────────────────────────────────────────

    @Test
    fun `deletes the frozen entries and reports measured bytes`() = runBlocking {
        val root = tempDir()
        val main = Files.writeString(root.resolve("a.jsonl"), "0123456789")
        val s = session(main, root)

        val result = deleteSessions(listOf(s), neverRunning)

        assertFalse(Files.exists(main))
        assertEquals(1, result.deletedSessions)
        // 报的是实测字节，不是扫描时的预估值。
        assertEquals(10L, result.freedBytes)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `sibling directory is removed after its contents`() = runBlocking {
        val root = tempDir()
        val main = Files.writeString(root.resolve("a.jsonl"), "x")
        val dir = Files.createDirectory(root.resolve("a"))
        val nested = Files.createDirectory(dir.resolve("subagents"))
        val leaf = Files.writeString(nested.resolve("s.jsonl"), "yy")
        val s = session(main, root, extra = listOf(dir, nested, leaf))

        val result = deleteSessions(listOf(s), neverRunning)

        // 深到浅的顺序必须让父目录在子项之后删，否则目录非空会失败。
        assertFalse(Files.exists(leaf))
        assertFalse(Files.exists(nested))
        assertFalse(Files.exists(dir))
        assertFalse(Files.exists(main))
        assertEquals(1, result.deletedSessions)
        assertEquals(3L, result.freedBytes)
    }

    // ─────────────────────────────────────────
    // 身份校验
    // ─────────────────────────────────────────

    @Test
    fun `file modified after scan is never deleted`() = runBlocking {
        val root = tempDir()
        val main = Files.writeString(root.resolve("a.jsonl"), "old")
        val s = session(main, root)
        // 扫描后用户又聊了几句，文件长大了：这条会话不再是当时判定的那条。
        Files.writeString(main, "the user kept chatting")

        val result = deleteSessions(listOf(s), neverRunning)

        assertTrue(Files.exists(main))
        assertEquals(0, result.deletedSessions)
        assertEquals(0L, result.freedBytes)
        assertEquals(1, result.errors.size)
    }

    @Test
    fun `file replaced after scan is never deleted`() = runBlocking {
        val root = tempDir()
        val main = Files.writeString(root.resolve("a.jsonl"), "old")
        val s = session(main, root)
        Files.delete(main)
        Files.writeString(main, "old") // 同名同长度，但已是另一个文件

        val result = deleteSessions(listOf(s), neverRunning)

        assertTrue(Files.exists(main))
        assertEquals(0, result.deletedSessions)
    }

    @Test
    fun `directory with new content after scan is preserved`() = runBlocking {
        val root = tempDir()
        val main = Files.writeString(root.resolve("a.jsonl"), "x")
        val dir = Files.createDirectory(root.resolve("a"))
        val s = session(main, root, extra = listOf(dir))
        val added = Files.writeString(dir.resolve("new.jsonl"), "keep")

        val result = deleteSessions(listOf(s), neverRunning)

        // 目录非空必须保留，且不能连带把新内容删掉。
        assertTrue(Files.exists(added))
        assertTrue(Files.isDirectory(dir))
        assertEquals(1, result.errors.size)
        // 有失败条目的会话不计入已删数量。
        assertEquals(0, result.deletedSessions)
    }

    @Test
    fun `missing entry is skipped without an error`() = runBlocking {
        val root = tempDir()
        val main = Files.writeString(root.resolve("a.jsonl"), "x")
        val s = session(main, root)
        Files.delete(main) // 用户自己先删了

        val result = deleteSessions(listOf(s), neverRunning)

        assertTrue(result.errors.isEmpty())
        assertEquals(1, result.deletedSessions)
        assertEquals(0L, result.freedBytes)
    }

    // ─────────────────────────────────────────
    // 进程阻断
    // ─────────────────────────────────────────

    @Test
    fun `running tool blocks its sessions and leaves others deletable`() = runBlocking {
        val root = tempDir()
        val claudeFile = Files.writeString(root.resolve("c.jsonl"), "keep")
        val codexFile = Files.writeString(root.resolve("x.jsonl"), "gone")
        val sessions = listOf(
            session(claudeFile, root, tool = ChatTool.CLAUDE),
            session(codexFile, root, tool = ChatTool.CODEX),
        )

        val result = deleteSessions(sessions) { it == ChatTool.CLAUDE }

        assertTrue(Files.exists(claudeFile))
        assertFalse(Files.exists(codexFile))
        assertEquals(listOf(ChatTool.CLAUDE.displayName), result.blockedTools)
        assertEquals(1, result.deletedSessions)
    }

    @Test
    fun `single session delete is blocked while its tool runs`() = runBlocking {
        val root = tempDir()
        val main = Files.writeString(root.resolve("a.jsonl"), "keep")
        val s = session(main, root, tool = ChatTool.CODEX)

        val result = deleteSession(s) { true }

        assertTrue(Files.exists(main))
        assertEquals(0, result.deletedSessions)
        assertEquals(listOf(ChatTool.CODEX.displayName), result.blockedTools)
    }

    @Test
    fun `tool is checked once per batch`() = runBlocking {
        val root = tempDir()
        val sessions = (1..3).map {
            session(Files.writeString(root.resolve("$it.jsonl"), "x"), root, tool = ChatTool.CODEX)
        }
        var checks = 0

        deleteSessions(sessions) { checks++; false }

        // 进程枚举有成本，且两次结果不一致会让「报告阻断」与「实际跳过」对不上。
        assertEquals(1, checks)
    }

    @Test
    fun `empty input deletes nothing`() = runBlocking {
        val result = deleteSessions(emptyList(), neverRunning)

        assertEquals(0, result.deletedSessions)
        assertEquals(0L, result.freedBytes)
        assertTrue(result.blockedTools.isEmpty())
    }

    @Test
    fun `entries outside the frozen list are never touched`() = runBlocking {
        val root = tempDir()
        val main = Files.writeString(root.resolve("a.jsonl"), "x")
        val s = session(main, root)
        // 冻结之后同目录出现了另一条会话，删除阶段不得重新遍历目录。
        val other = Files.writeString(root.resolve("b.jsonl"), "keep")

        deleteSessions(listOf(s), neverRunning)

        assertTrue(Files.exists(other))
        assertTrue(Files.isDirectory(root))
    }
}
