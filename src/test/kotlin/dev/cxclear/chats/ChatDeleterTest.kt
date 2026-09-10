package dev.cxclear.chats

import dev.cxclear.model.PathSnapshotKind
import dev.cxclear.scan.readPathSnapshot
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
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

    @Test
    fun `Cursor sessions delete state vscdb keys and frozen transcripts`() = runBlocking {
        val root = tempDir()
        val main = Files.writeString(root.resolve("cursor.jsonl"), "data")
        val other = Files.writeString(root.resolve("keep.jsonl"), "keep")
        val s = session(main, root, tool = ChatTool.CURSOR)
        val db = writeCursorStateDb(
            root.resolve("state.vscdb"),
            composerId = s.id,
            otherComposerId = "keep-composer",
        )

        val result = deleteSessions(
            sessions = listOf(s),
            toolIsRunning = neverRunning,
            cursorStateDb = db,
        )

        assertFalse(Files.exists(main))
        assertTrue(Files.exists(other))
        assertEquals(1, result.deletedSessions)
        assertTrue(result.errors.isEmpty())
        assertEquals(0, kvCount(db, "composerData:${s.id}"))
        assertEquals(0, kvCount(db, "bubbleId:${s.id}:msg-1"))
        assertEquals(0, kvCount(db, "checkpointId:${s.id}:cp-1"))
        assertEquals(0, kvCount(db, "ofsContent:${s.id}:file:///tmp/a.kt"))
        assertEquals(0, kvCount(db, "cloudAgentDraft:${s.id}"))
        assertEquals(1, kvCount(db, "composerData:keep-composer"))
        assertEquals(1, kvCount(db, "bubbleId:keep-composer:msg-keep"))
        assertEquals(0, headerCount(db, s.id))
        assertEquals(1, headerCount(db, "keep-composer"))
        val index = itemText(db, "composer.composerData")
        assertTrue(index != null && !index.contains(s.id) && index.contains("keep-composer"))
        assertTrue(itemText(db, "composer.composerHeaders.version") != null)
    }

    @Test
    fun `Cursor delete also removes nested subcomposers`() = runBlocking {
        val root = tempDir()
        val main = Files.writeString(root.resolve("cursor.jsonl"), "x")
        val s = session(main, root, tool = ChatTool.CURSOR)
        val db = writeCursorStateDb(
            root.resolve("state.vscdb"),
            composerId = s.id,
            subComposerId = "child-composer",
        )

        val result = deleteSessions(
            sessions = listOf(s),
            toolIsRunning = neverRunning,
            cursorStateDb = db,
        )

        assertEquals(1, result.deletedSessions)
        assertEquals(0, kvCount(db, "composerData:${s.id}"))
        assertEquals(0, kvCount(db, "composerData:child-composer"))
        assertEquals(0, kvCount(db, "bubbleId:child-composer:msg-child"))
        assertEquals(0, headerCount(db, "child-composer"))
    }

    @Test
    fun `missing Cursor state db reports an error and leaves transcript`() = runBlocking {
        val root = tempDir()
        val main = Files.writeString(root.resolve("cursor.jsonl"), "keep")
        val s = session(main, root, tool = ChatTool.CURSOR)

        val result = deleteSessions(
            sessions = listOf(s),
            toolIsRunning = neverRunning,
            cursorStateDb = null,
        )

        assertTrue(Files.exists(main))
        assertEquals(0, result.deletedSessions)
        assertEquals(0L, result.freedBytes)
        assertEquals(listOf("Cursor 状态库不存在"), result.errors)
    }

    @Test
    fun `running Cursor blocks its sessions and leaves state db untouched`() = runBlocking {
        val root = tempDir()
        val main = Files.writeString(root.resolve("cursor.jsonl"), "keep")
        val s = session(main, root, tool = ChatTool.CURSOR)
        val db = writeCursorStateDb(root.resolve("state.vscdb"), composerId = s.id)

        val result = deleteSessions(
            sessions = listOf(s),
            toolIsRunning = { it == ChatTool.CURSOR },
            cursorStateDb = db,
        )

        assertTrue(Files.exists(main))
        assertEquals(0, result.deletedSessions)
        assertEquals(listOf(ChatTool.CURSOR.displayName), result.blockedTools)
        assertEquals(1, kvCount(db, "composerData:${s.id}"))
        assertEquals(1, headerCount(db, s.id))
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

    private fun writeCursorStateDb(
        db: Path,
        composerId: String,
        otherComposerId: String? = null,
        subComposerId: String? = null,
    ): Path {
        DriverManager.getConnection("jdbc:sqlite:$db").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE cursorDiskKV (key TEXT UNIQUE ON CONFLICT REPLACE, value BLOB)")
                stmt.execute(
                    """
                    CREATE TABLE composerHeaders (
                        composerId TEXT PRIMARY KEY,
                        workspaceId TEXT,
                        createdAt INTEGER,
                        lastUpdatedAt INTEGER,
                        isArchived INTEGER,
                        isSubagent INTEGER,
                        recency INTEGER,
                        checkpointAt INTEGER,
                        subagentTypeName TEXT,
                        value TEXT
                    )
                    """.trimIndent(),
                )
                stmt.execute("CREATE TABLE ItemTable (key TEXT UNIQUE ON CONFLICT REPLACE, value BLOB)")
            }
            val kv = conn.prepareStatement("INSERT INTO cursorDiskKV (key, value) VALUES (?, ?)")
            val header = conn.prepareStatement(
                "INSERT INTO composerHeaders (composerId, workspaceId, createdAt, lastUpdatedAt, isArchived, isSubagent, recency, checkpointAt, subagentTypeName, value) VALUES (?, ?, 1, 1, 0, 0, 1, NULL, '', ?)",
            )
            fun putComposer(id: String, data: String, extra: List<Pair<String, String>> = emptyList()) {
                kv.setString(1, "composerData:$id")
                kv.setString(2, data)
                kv.executeUpdate()
                extra.forEach { (key, value) ->
                    kv.setString(1, key)
                    kv.setString(2, value)
                    kv.executeUpdate()
                }
                header.setString(1, id)
                header.setString(2, "ws")
                header.setString(3, """{"composerId":"$id"}""")
                header.executeUpdate()
            }
            val childJson = if (subComposerId != null) """["$subComposerId"]""" else "[]"
            putComposer(
                composerId,
                """{"composerId":"$composerId","subComposerIds":$childJson}""",
                extra = listOf(
                    "bubbleId:$composerId:msg-1" to "bubble",
                    "checkpointId:$composerId:cp-1" to "cp",
                    "ofsContent:$composerId:file:///tmp/a.kt" to "ofs",
                    "cloudAgentDraft:$composerId" to "draft",
                ),
            )
            if (subComposerId != null) {
                putComposer(
                    subComposerId,
                    """{"composerId":"$subComposerId","subComposerIds":[]}""",
                    extra = listOf("bubbleId:$subComposerId:msg-child" to "child"),
                )
            }
            if (otherComposerId != null) {
                putComposer(
                    otherComposerId,
                    """{"composerId":"$otherComposerId","subComposerIds":[]}""",
                    extra = listOf("bubbleId:$otherComposerId:msg-keep" to "keep"),
                )
            }
            kv.close()
            header.close()
            val selected = listOfNotNull(composerId, otherComposerId)
                .joinToString(",") { "\"$it\"" }
            conn.prepareStatement("INSERT INTO ItemTable (key, value) VALUES (?, ?)").use { stmt ->
                stmt.setString(1, "composer.composerData")
                stmt.setString(2, """{"selectedComposerIds":[$selected],"lastFocusedComposerIds":[$selected]}""")
                stmt.executeUpdate()
            }
        }
        return db
    }

    private fun kvCount(db: Path, key: String): Int =
        DriverManager.getConnection("jdbc:sqlite:$db").use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM cursorDiskKV WHERE key = ?").use { stmt ->
                stmt.setString(1, key)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    private fun headerCount(db: Path, composerId: String): Int =
        DriverManager.getConnection("jdbc:sqlite:$db").use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM composerHeaders WHERE composerId = ?").use { stmt ->
                stmt.setString(1, composerId)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    private fun itemText(db: Path, key: String): String? =
        DriverManager.getConnection("jdbc:sqlite:$db").use { conn ->
            conn.prepareStatement("SELECT value FROM ItemTable WHERE key = ?").use { stmt ->
                stmt.setString(1, key)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getString(1) else null
                }
            }
        }
}
