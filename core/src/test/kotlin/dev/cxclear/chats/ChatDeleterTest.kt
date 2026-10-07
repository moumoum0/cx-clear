package dev.cxclear.chats

import dev.cxclear.model.ChatSessionSummary
import dev.cxclear.model.ChatTool
import dev.cxclear.model.PathSnapshotKind
import dev.cxclear.scan.readPathSnapshot
import dev.cxclear.tools.chatToolById
import dev.cxclear.tools.cursor.cursorStateDbOverride
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val cursorTool = chatToolById("cursor")!!
private val claudeTool = chatToolById("claude")!!
private val codexTool = chatToolById("codex")!!

class ChatDeleterTest {
    private val neverRunning: (ChatTool) -> Boolean = { false }

    @AfterTest
    fun clearCursorDbOverride() {
        cursorStateDbOverride = null
    }

    private fun tempDir(): Path = Files.createTempDirectory("cxclear-chat")

    private fun session(
        mainFile: Path,
        root: Path,
        tool: ChatTool = chatToolById("claude")!!,
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

    @Test
    fun `deletes the frozen entries and reports measured bytes`() = runBlocking {
        val root = tempDir()
        val main = Files.writeString(root.resolve("a.jsonl"), "0123456789")
        val s = session(main, root)

        val result = deleteSessions(listOf(s), neverRunning)

        assertFalse(Files.exists(main))
        assertEquals(1, result.deletedSessions)
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

        assertFalse(Files.exists(leaf))
        assertFalse(Files.exists(nested))
        assertFalse(Files.exists(dir))
        assertFalse(Files.exists(main))
        assertEquals(1, result.deletedSessions)
        assertEquals(3L, result.freedBytes)
    }

    @Test
    fun `file modified after scan is never deleted`() = runBlocking {
        val root = tempDir()
        val main = Files.writeString(root.resolve("a.jsonl"), "old")
        val s = session(main, root)
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
        Files.writeString(main, "old")

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

        assertTrue(Files.exists(added))
        assertTrue(Files.isDirectory(dir))
        assertEquals(1, result.errors.size)
        assertEquals(0, result.deletedSessions)
    }

    @Test
    fun `missing entry is skipped without an error`() = runBlocking {
        val root = tempDir()
        val main = Files.writeString(root.resolve("a.jsonl"), "x")
        val s = session(main, root)
        Files.delete(main)

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
        val s = session(main, root, tool = cursorTool)
        val db = writeCursorStateDb(
            root.resolve("state.vscdb"),
            composerId = s.id,
            otherComposerId = "keep-composer",
        )

        cursorStateDbOverride = db
        val result = deleteSessions(
            sessions = listOf(s),
            toolIsRunning = neverRunning,
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
        val s = session(main, root, tool = cursorTool)
        val db = writeCursorStateDb(
            root.resolve("state.vscdb"),
            composerId = s.id,
            subComposerId = "child-composer",
        )

        cursorStateDbOverride = db
        val result = deleteSessions(
            sessions = listOf(s),
            toolIsRunning = neverRunning,
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
        val s = session(main, root, tool = cursorTool)

        cursorStateDbOverride = root.resolve("missing.vscdb")
        val result = deleteSessions(
            sessions = listOf(s),
            toolIsRunning = neverRunning,
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
        val s = session(main, root, tool = cursorTool)
        val db = writeCursorStateDb(root.resolve("state.vscdb"), composerId = s.id)

        cursorStateDbOverride = db
        val result = deleteSessions(
            sessions = listOf(s),
            toolIsRunning = { it == cursorTool },
        )

        assertTrue(Files.exists(main))
        assertEquals(0, result.deletedSessions)
        assertEquals(listOf(cursorTool.displayName), result.blockedTools)
        assertEquals(1, kvCount(db, "composerData:${s.id}"))
        assertEquals(1, headerCount(db, s.id))
    }

    @Test
    fun `running tool blocks its sessions and leaves others deletable`() = runBlocking {
        val root = tempDir()
        val claudeFile = Files.writeString(root.resolve("c.jsonl"), "keep")
        val codexFile = Files.writeString(root.resolve("x.jsonl"), "gone")
        val sessions = listOf(
            session(claudeFile, root, tool = claudeTool),
            session(codexFile, root, tool = codexTool),
        )

        val result = deleteSessions(sessions) { it == claudeTool }

        assertTrue(Files.exists(claudeFile))
        assertFalse(Files.exists(codexFile))
        assertEquals(listOf(claudeTool.displayName), result.blockedTools)
        assertEquals(1, result.deletedSessions)
    }

    @Test
    fun `single session delete is blocked while its tool runs`() = runBlocking {
        val root = tempDir()
        val main = Files.writeString(root.resolve("a.jsonl"), "keep")
        val s = session(main, root, tool = codexTool)

        val result = deleteSession(s) { true }

        assertTrue(Files.exists(main))
        assertEquals(0, result.deletedSessions)
        assertEquals(listOf(codexTool.displayName), result.blockedTools)
    }

    @Test
    fun `tool is checked once per batch`() = runBlocking {
        val root = tempDir()
        val sessions = (1..3).map {
            session(Files.writeString(root.resolve("$it.jsonl"), "x"), root, tool = codexTool)
        }
        var checks = 0

        deleteSessions(sessions) { checks++; false }

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
