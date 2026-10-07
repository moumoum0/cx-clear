package dev.cxclear.tools.cursor

import dev.cxclear.model.ChatSessionSummary
import dev.cxclear.tools.firstLineSummary
import dev.cxclear.tools.listDir
import dev.cxclear.util.MiniJson
import dev.cxclear.util.jsonBool
import dev.cxclear.util.jsonStr
import dev.cxclear.scan.readPathSnapshot
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.sql.DriverManager

internal fun unwrapCursorUserQuery(raw: String): String {
    val trimmed = raw.trim()
    val queryStart = trimmed.indexOf("<user_query>")
    if (queryStart < 0) return raw
    val queryContentStart = queryStart + "<user_query>".length
    val queryEnd = trimmed.indexOf("</user_query>", queryContentStart)
    if (queryEnd < 0) return raw
    val inner = trimmed.substring(queryContentStart, queryEnd).trim()
    return inner.ifBlank { raw }
}

internal fun collectCursorTranscripts(transcriptsRoot: Path): List<Path> {
    val files = mutableListOf<Path>()
    val pending = mutableListOf(transcriptsRoot)
    while (pending.isNotEmpty()) {
        val dir = pending.removeAt(pending.lastIndex)
        for (entry in listDir(dir)) {
            if (Files.isDirectory(entry)) {
                // subagents/ 是子代理转录，不算独立会话。
                if (entry.fileName.toString() != "subagents") {
                    pending.add(entry)
                }
            } else if (Files.isRegularFile(entry) && entry.fileName.toString().endsWith(".jsonl")) {
                files.add(entry)
            }
        }
    }
    files.sortBy { it.toString() }
    return files
}

internal data class CursorComposerData(
    val name: String?,
    val subtitle: String?,
    val lastUpdatedAt: Long?,
    val workspacePath: String?,
)

@Suppress("UNCHECKED_CAST")
internal fun loadCursorComposerDataMap(): Map<String, CursorComposerData> {
    val dbPath = resolveCursorStateDb() ?: return emptyMap()

    val map = mutableMapOf<String, CursorComposerData>()
    runCatching {
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery(
                    "SELECT key, value FROM cursorDiskKV WHERE key LIKE 'composerData:%'"
                ).use { rs ->
                    while (rs.next()) {
                        runCatching {
                            val key = rs.getString("key")
                            val composerId = key.removePrefix("composerData:")
                            val jsonText = rs.getString("value")
                            val obj = MiniJson.parse(jsonText) as? Map<String, Any?> ?: return@runCatching

                            val name = obj.jsonStr("name")
                            val subtitle = obj.jsonStr("subtitle")
                            val lastUpdatedAt = (obj["lastUpdatedAt"] as? Number)?.toLong()
                            val workspaceId = obj["workspaceIdentifier"] as? Map<String, Any?>
                            val uri = workspaceId?.get("uri") as? Map<String, Any?>
                            val workspacePath = uri?.jsonStr("fsPath")

                            map[composerId] = CursorComposerData(
                                name = name,
                                subtitle = subtitle,
                                lastUpdatedAt = lastUpdatedAt,
                                workspacePath = workspacePath,
                            )
                        }
                    }
                }
            }
        }
    }
    return map
}

internal fun readCursorTranscriptTitle(file: Path): String? {
    var title: String? = null
    runCatching {
        Files.newBufferedReader(file, Charsets.UTF_8).use { br ->
            for (line in br.lineSequence()) {
                val obj = MiniJson.parse(line) ?: continue
                if (obj.jsonStr("role") == "user") {
                    if (obj.jsonBool("isMeta") == true || obj.jsonBool("isSidechain") == true) {
                        continue
                    }
                    val content = (obj as? Map<String, Any?>)?.get("message")
                        ?.let { (it as? Map<String, Any?>)?.get("content") }
                    val text = when (content) {
                        is String -> content.trim()
                        is List<*> -> content.asSequence()
                            .filterIsInstance<Map<String, Any?>>()
                            .mapNotNull { it.jsonStr("text") }
                            .firstOrNull()?.trim()
                        else -> null
                    }
                    title = text?.let(::unwrapCursorUserQuery)?.let(::firstLineSummary)
                        ?.takeIf { it.isNotBlank() }
                    if (title != null) break
                }
            }
        }
    }
    return title
}

internal fun scanCursorSessions(
    onFound: (ChatSessionSummary) -> Unit = {},
): List<ChatSessionSummary> {
    val projectsRoot = cursorProjectsRoot() ?: return emptyList()
    val sessions = mutableListOf<ChatSessionSummary>()

    // composerData 一次全量读进内存，每个 jsonl 都查一次库太慢。
    val composerDataMap = loadCursorComposerDataMap()

    for (projectDir in listDir(projectsRoot)) {
        if (!Files.isDirectory(projectDir)) continue
        val projectHash = projectDir.fileName.toString()
        val transcriptsRoot = projectDir.resolve("agent-transcripts")
        if (!Files.isDirectory(transcriptsRoot)) continue

        val transcriptFiles = collectCursorTranscripts(transcriptsRoot)

        for (file in transcriptFiles) {
            runCatching {
                val composerId = file.parent.fileName.toString()
                // Cursor 应用内删除后 jsonl 常残留；没索引的标题会退化成 UUID。
                val composerData = composerDataMap[composerId] ?: return@runCatching

                val updatedMs = composerData.lastUpdatedAt ?: run {
                    val attrs = Files.readAttributes(file, BasicFileAttributes::class.java,
                        java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    attrs.lastModifiedTime().toMillis()
                }

                val title = composerData.name?.takeIf { it.isNotBlank() }
                    ?: composerData.subtitle?.takeIf { it.isNotBlank() }
                    ?: readCursorTranscriptTitle(file)
                    ?: composerId

                // 项目名取 composerData 工作区路径末段，取不到退回项目目录名推断
                val project = composerData.workspacePath?.let { path ->
                    Path.of(path).fileName?.toString()
                } ?: when {
                    projectHash == "empty-window" -> null
                    projectHash.startsWith("d-") || projectHash.startsWith("c-") ->
                        projectHash.substringAfterLast('-')
                    else -> projectHash
                }

                val snap = readPathSnapshot(file) ?: return@runCatching

                val summary = ChatSessionSummary(
                    tool = CursorPlugin.chat,
                    id = composerId,
                    title = title,
                    project = project,
                    updatedMillis = updatedMs,
                    sizeBytes = snap.size,
                    mainFile = file,
                    rootDir = projectsRoot,
                    entries = listOf(snap),
                )
                sessions += summary
                onFound(summary)
            }
        }
    }

    return sessions.sortedByDescending { it.updatedMillis }
}
