package dev.cxclear.tools.deepseekhermes

import com.github.luben.zstd.ZstdOutputStream
import dev.cxclear.chats.deleteSessions
import dev.cxclear.model.ChatRole
import dev.cxclear.model.ChatTool
import dev.cxclear.util.MiniJson
import dev.cxclear.util.jsonArr
import dev.cxclear.util.jsonMap
import dev.cxclear.util.jsonObj
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeepSeekHermesChatTest {
    @TempDir
    lateinit var home: Path

    private val neverRunning: (ChatTool) -> Boolean = { false }

    @BeforeTest
    fun useFixture() {
        dshHomeOverride = home
    }

    @AfterTest
    fun clearFixture() {
        dshHomeOverride = null
    }

    @Test
    fun `scan lists parent sessions and folds subagents into the delete plan`() {
        writeSession(
            "session-parent",
            frames(
                """{"type":"session","id":"session-parent","createdAt":1000,"cwd":"D:\\project\\cxclear"}""",
                """
                {"type":"user/message","time":2000,"data":{"content":[{"type":"text","text":"看一下测试"}],"source":{"kind":"user"}}}
                {"type":"user/message","time":2001,"data":{"content":[{"type":"text","text":"<system-reminder>ignore"}],"source":{"kind":"user"}}}
                {"type":"assistant/message","time":3000,"data":{"message":{"content":[{"type":"text","text":"我先看测试"},{"type":"tool-call","name":"glob"}]}}}
                {"type":"session/title","time":2100,"data":{"title":"旧标题","source":{"kind":"fallback"}}}
                {"type":"session/title","time":4000,"data":{"title":"审查测试","source":{"kind":"provider"}}}
                """.trimIndent(),
            ),
            alsoV3 = true,
        )
        writeSession(
            "child-1",
            frames("""{"type":"session","id":"child-1","createdAt":1500,"cwd":"D:\\project\\cxclear","origin":"subagent","parentSession":"session-parent"}"""),
        )
        writeSession(
            "session-keep",
            frames("""{"type":"session","id":"session-keep","createdAt":500,"cwd":"D:\\project\\cxclear"}"""),
        )
        writeWorkspace(listOf("session-parent", "session-keep"), archived = listOf("session-parent"))

        val found = scanDeepSeekHermesSessions()

        assertEquals(listOf("session-parent", "session-keep"), found.map { it.id })
        val parent = found.first { it.id == "session-parent" }
        assertEquals("审查测试", parent.title)
        assertEquals("cxclear", parent.project)
        assertEquals(4000L, parent.updatedMillis)
        assertTrue(parent.entries.any { it.path.fileName.toString() == "child-1" }, parent.entries.joinToString { it.path.toString() })
        assertTrue(parent.entries.any { it.path.fileName.toString() == "session-parent.json" })
        assertTrue(parent.entries.any { it.path.fileName.toString() == "child-1.json" })

        val messages = loadDeepSeekHermesMessages(parent)
        assertEquals(2, messages.size, messages.joinToString(" || ") { "${it.role}:${it.text}" })
        assertEquals(listOf(ChatRole.USER, ChatRole.ASSISTANT), messages.map { it.role })
        assertEquals("看一下测试", messages[0].text)
        assertEquals("我先看测试", messages[1].text)
    }

    @Test
    fun `delete removes the parent, its subagent and the workspace index`() = runBlocking {
        writeSession(
            "session-parent",
            frames("""{"type":"session","id":"session-parent","createdAt":1000,"cwd":"D:\\work\\demo"}"""),
        )
        writeSession(
            "child-1",
            frames("""{"type":"session","id":"child-1","createdAt":1500,"cwd":"D:\\work\\demo","origin":"subagent","parentSession":"session-parent"}"""),
        )
        writeSession(
            "session-keep",
            frames("""{"type":"session","id":"session-keep","createdAt":500,"cwd":"D:\\work\\demo"}"""),
        )
        writeWorkspace(listOf("session-parent", "session-keep"), archived = listOf("session-parent"))
        val parent = scanDeepSeekHermesSessions().first { it.id == "session-parent" }

        val result = deleteSessions(listOf(parent), neverRunning)

        assertEquals(1, result.deletedSessions)
        assertTrue(result.errors.isEmpty())
        assertFalse(Files.exists(sessionDir("session-parent")))
        assertFalse(Files.exists(sessionDir("child-1")))
        assertFalse(Files.exists(cacheFile("session-parent")))
        assertFalse(Files.exists(cacheFile("child-1")))
        assertTrue(Files.exists(sessionDir("session-keep")))
        val workspace = MiniJson.parse(Files.readString(workspaceFile()))!!.jsonMap()!!
        val ids = workspace.jsonObj("tables")!!.jsonObj("workspaces")!!.values.first().jsonArr("sessionIds")
        assertEquals(listOf("session-keep"), ids)
        assertEquals(emptyList<Any?>(), workspace.jsonObj("global")!!.jsonArr("archivedSessionIds"))
    }

    @Test
    fun `a log replaced after the scan is kept and stays in the index`() = runBlocking {
        writeSession(
            "session-parent",
            frames("""{"type":"session","id":"session-parent","createdAt":1000,"cwd":"D:\\work\\demo"}"""),
        )
        writeWorkspace(listOf("session-parent"), archived = emptyList())
        val parent = scanDeepSeekHermesSessions().single()
        Files.write(parent.mainFile, Files.readAllBytes(parent.mainFile) + byteArrayOf(0x20))

        val result = deleteSessions(listOf(parent), neverRunning)

        assertEquals(0, result.deletedSessions, "errors=${result.errors} exists=${Files.exists(parent.mainFile)}")
        assertTrue(Files.exists(parent.mainFile))
        val raw = MiniJson.parse(Files.readString(workspaceFile()))
        val ids = raw?.jsonObj("tables")?.jsonObj("workspaces")?.values?.firstOrNull()?.jsonArr("sessionIds")
        assertEquals(listOf("session-parent"), ids, Files.readString(workspaceFile()))
    }

    private fun writeSession(id: String, body: ByteArray, alsoV3: Boolean = false) {
        val dir = Files.createDirectories(sessionDir(id))
        Files.write(dir.resolve("session.v4.jsonl.zstd"), body)
        if (alsoV3) {
            Files.write(dir.resolve("session.v3.jsonl.zstd"), frames("""{"type":"session","id":"$id","createdAt":1}"""))
        }
        Files.createDirectories(cacheFile(id).parent)
        Files.writeString(cacheFile(id), "{}")
    }

    private fun writeWorkspace(sessionIds: List<String>, archived: List<String>) {
        val ids = sessionIds.joinToString(",") { "\"$it\"" }
        val archivedIds = archived.joinToString(",") { "\"$it\"" }
        Files.createDirectories(workspaceFile().parent)
        Files.writeString(
            workspaceFile(),
            """{"global":{"archivedSessionIds":[$archivedIds],"pinnedSessionIds":[]},"tables":{"workspaces":{"ws":{"path":"D:\\project\\cxclear","title":"cxclear","sessionIds":[$ids]}}}}""",
        )
    }

    private fun sessionDir(id: String): Path =
        home.resolve("sessions").resolve("--D-project-cxclear--").resolve(id)

    private fun cacheFile(id: String): Path = dshProjCacheFile(home, id)

    private fun workspaceFile(): Path = home.resolve("storages").resolve("workspace.json")

    private fun frames(vararg texts: String): ByteArray {
        val out = ByteArrayOutputStream()
        for (text in texts) {
            val frame = ByteArrayOutputStream()
            ZstdOutputStream(frame).use { it.write(text.toByteArray(Charsets.UTF_8)) }
            out.write(frame.toByteArray())
        }
        return out.toByteArray()
    }
}
