package dev.cxclear.chats

import dev.cxclear.scan.readPathSnapshot
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.sql.DriverManager

/**
 * Cursor 会话扫描：~/.cursor/projects/<project-hash>/agent-transcripts/ 下的 jsonl，
 * 元数据取自 state.vscdb 的 composerData 键。
 */

/**
 * 递归收集 Cursor transcript 目录下所有 .jsonl 文件，跳过 subagents/ 子目录。
 */
internal fun collectCursorTranscripts(transcriptsRoot: Path): List<Path> {
    val files = mutableListOf<Path>()
    val pending = mutableListOf(transcriptsRoot)
    while (pending.isNotEmpty()) {
        val dir = pending.removeAt(pending.lastIndex)
        for (entry in listDir(dir)) {
            if (Files.isDirectory(entry)) {
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

/** Cursor 会话元数据（从 state.vscdb 的 composerData 读取）。 */
internal data class CursorComposerData(
    val name: String?,
    val subtitle: String?,
    val lastUpdatedAt: Long?,
    val workspacePath: String?,
)

/** 从 state.vscdb 读取所有 composerData，返回 Map<composerId, CursorComposerData>。 */
@Suppress("UNCHECKED_CAST")
internal fun loadCursorComposerDataMap(): Map<String, CursorComposerData> {
    val dbPath = cursorStateDbFile() ?: return emptyMap()

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

/** 从 Cursor transcript 读标题（第一条非 meta 用户消息），作为降级方案。 */
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

/**
 * 扫描 Cursor 会话。目录结构：
 *   ~/.cursor/projects/<project-hash>/agent-transcripts/<session-uuid>/<session-uuid>.jsonl
 */
internal fun scanCursorSessions(
    onFound: (ChatSessionSummary) -> Unit = {},
): List<ChatSessionSummary> {
    val projectsRoot = cursorProjectsRoot() ?: return emptyList()
    val sessions = mutableListOf<ChatSessionSummary>()

    // 一次性从 state.vscdb 加载所有 composerData
    val composerDataMap = loadCursorComposerDataMap()

    for (projectDir in listDir(projectsRoot)) {
        if (!Files.isDirectory(projectDir)) continue
        val projectHash = projectDir.fileName.toString()
        val transcriptsRoot = projectDir.resolve("agent-transcripts")
        if (!Files.isDirectory(transcriptsRoot)) continue

        val transcriptFiles = collectCursorTranscripts(transcriptsRoot)

        for (file in transcriptFiles) {
            runCatching {
                // 从文件路径提取 composerId (UUID)
                val composerId = file.parent.fileName.toString()
                // Cursor 原生删除会清 composerData，但 agent-transcripts 常残留。
                // 没有索引的 jsonl 不是历史里的对话，标题也会退化成 UUID。
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

                // 使用 composerData 的工作区路径，降级到项目目录名推断
                val project = composerData.workspacePath?.let { path ->
                    // 从路径提取最后一段作为项目名，如 d:\project\cxclear -> cxclear
                    Path.of(path).fileName?.toString()
                } ?: when {
                    projectHash == "empty-window" -> null
                    projectHash.startsWith("d-") || projectHash.startsWith("c-") ->
                        projectHash.substringAfterLast('-')
                    else -> projectHash
                }

                val snap = readPathSnapshot(file) ?: return@runCatching

                val summary = ChatSessionSummary(
                    tool = ChatTool.CURSOR,
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
