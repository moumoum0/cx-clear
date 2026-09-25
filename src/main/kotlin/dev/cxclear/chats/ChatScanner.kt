package dev.cxclear.chats

import dev.cxclear.model.PathSnapshot
import dev.cxclear.model.PathSnapshotKind
import dev.cxclear.scan.readPathSnapshot
import dev.cxclear.profiles.homeDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.sql.DriverManager
import kotlin.streams.toList

internal fun claudeProjectsRoot(): Path? =
    homeDir()?.resolve(".claude")?.resolve("projects")?.takeIf { Files.isDirectory(it) }

internal fun codexSessionsRoot(): Path? =
    homeDir()?.resolve(".codex")?.resolve("sessions")?.takeIf { Files.isDirectory(it) }

internal fun codexIndexFile(): Path? =
    homeDir()?.resolve(".codex")?.resolve("session_index.jsonl")?.takeIf { Files.isRegularFile(it) }

/** Cursor 项目存储根目录：~/.cursor/projects/ */
internal fun cursorProjectsRoot(): Path? =
    homeDir()?.resolve(".cursor")?.resolve("projects")?.takeIf { Files.isDirectory(it) }

/** Cursor 全局状态库：%APPDATA%/Cursor/User/globalStorage/state.vscdb */
internal fun cursorStateDbFile(): Path? {
    val appData = System.getenv("APPDATA") ?: return null
    return Path.of(appData, "Cursor", "User", "globalStorage", "state.vscdb")
        .takeIf { Files.isRegularFile(it) }
}

/** Open Code 数据库文件：~/.local/share/opencode/opencode.db */
internal fun opencodeDbFile(): Path? =
    homeDir()?.resolve(".local")?.resolve("share")?.resolve("opencode")?.resolve("opencode.db")
        ?.takeIf { Files.isRegularFile(it) }

/** Open Code 存储目录：~/.local/share/opencode/storage/ */
internal fun opencodeStorageDir(): Path? =
    homeDir()?.resolve(".local")?.resolve("share")?.resolve("opencode")?.resolve("storage")
        ?.takeIf { Files.isDirectory(it) }

private fun listDir(dir: Path): List<Path> = runCatching {
    Files.newDirectoryStream(dir).use { it.toList() }
}.getOrDefault(emptyList())

/** 不跟随软链接。 */
private fun snapshotTree(dir: Path): List<PathSnapshot> {
    if (!Files.exists(dir)) return emptyList()
    val snap = readPathSnapshot(dir) ?: return emptyList()
    val list = mutableListOf(snap)
    if (snap.kind == PathSnapshotKind.DIRECTORY) {
        for (child in listDir(dir)) {
            list += snapshotTree(child)
        }
    }
    return list
}

/** 只计文件，不跟随链接。 */
private fun treeSize(dir: Path): Long {
    if (!Files.exists(dir)) return 0L
    return runCatching {
        Files.walk(dir).use { stream ->
            stream.mapToLong { p ->
                runCatching {
                    val attrs = Files.readAttributes(p, BasicFileAttributes::class.java,
                        java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    if (attrs.isRegularFile) attrs.size() else 0L
                }.getOrDefault(0L)
            }.sum()
        }
    }.getOrDefault(0L)
}

private data class CodexIndexEntry(val id: String, val title: String, val updatedAt: String?)

/** 坏行跳过；索引本身不会被删，损坏不影响主流程。 */
@Suppress("UNCHECKED_CAST")
private fun readCodexIndex(): Map<String, CodexIndexEntry> {
    val file = codexIndexFile() ?: return emptyMap()
    val map = LinkedHashMap<String, CodexIndexEntry>()
    runCatching {
        Files.readAllLines(file).forEach { line ->
            val root = MiniJson.parse(line) ?: return@forEach
            val id = root.jsonStr("id") ?: return@forEach
            val title = root.jsonStr("thread_name") ?: ""
            val updatedAt = root.jsonStr("updated_at")
            map[id] = CodexIndexEntry(id, title, updatedAt)
        }
    }
    return map
}

private fun isoToMillis(iso: String?): Long? {
    iso ?: return null
    return runCatching {
        java.time.Instant.parse(iso).toEpochMilli()
    }.getOrNull()
}

private fun lastPathSegment(raw: String): String = raw
    .trimEnd('\\', '/')
    .substringAfterLast('\\')
    .substringAfterLast('/')

private fun codexFileId(file: Path): String {
    val name = file.fileName.toString()
    val withoutExt = if (name.endsWith(".jsonl")) name.dropLast(6) else name
    val dashIdx = withoutExt.indexOfLast { it == '-' }
    return if (dashIdx >= 0) withoutExt.substring(dashIdx + 1) else withoutExt
}

/**
 * 把一段消息文本压成一行标题。
 * 首条消息常含 `<environment_context>` 一类注入块，跳过尖括号行，取第一行真正的用户文字。
 */
private fun firstLineSummary(raw: String): String? = raw
    .lineSequence()
    .map { it.trim() }
    .firstOrNull { it.isNotBlank() && !it.startsWith('<') }
    ?.take(60)

private data class CodexMeta(val title: String?, val project: String?)

/**
 * 扫一遍 rollout jsonl 头部，取标题与项目名。
 * 标题优先第一条用户消息，退回 cwd 末段；项目名一律取 cwd 末段。
 */
private fun readCodexMeta(file: Path): CodexMeta {
    return runCatching {
        Files.newBufferedReader(file).use { br ->
            var project: String? = null
            var title: String? = null
            var scanned = 0
            for (line in br.lineSequence()) {
                if (scanned++ > 200) break
                val obj = MiniJson.parse(line) ?: continue
                when (obj.jsonStr("type")) {
                    // session_meta 的 cwd 是项目名来源；标题只在没有用户消息时才退回用它。
                    "session_meta" -> {
                        val cwd = obj.jsonObj("payload")?.jsonStr("cwd")
                        if (!cwd.isNullOrBlank()) project = lastPathSegment(cwd)
                    }
                    "event_msg" -> {
                        val payload = obj.jsonObj("payload") ?: continue
                        if (title == null && payload.jsonStr("type") == "user_message") {
                            title = payload.jsonStr("message")?.let(::firstLineSummary)
                                ?.takeIf { it.isNotBlank() }
                        }
                    }
                }
                if (title != null && project != null) break
            }
            CodexMeta(title ?: project, project)
        }
    }.getOrDefault(CodexMeta(null, null))
}

private fun scanCodexSessions(
    onFound: (ChatSessionSummary) -> Unit = {},
): List<ChatSessionSummary> {
    val root = codexSessionsRoot() ?: return emptyList()
    val index = readCodexIndex()
    val sessions = mutableListOf<ChatSessionSummary>()

    runCatching {
        Files.walk(root).use { stream ->
            stream.filter { p ->
                Files.isRegularFile(p) &&
                    p.fileName.toString().startsWith("rollout-") &&
                    p.fileName.toString().endsWith(".jsonl")
            }.forEach { file ->
                runCatching {
                    val id = codexFileId(file)
                    val indexEntry = index[id]
                    val attrs = Files.readAttributes(file, BasicFileAttributes::class.java,
                        java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    val updatedMs = isoToMillis(indexEntry?.updatedAt)
                        ?: attrs.lastModifiedTime().toMillis()
                    val meta = readCodexMeta(file)
                    val title = indexEntry?.title?.ifBlank { null }
                        ?: meta.title
                        ?: file.fileName.toString()
                    val snap = readPathSnapshot(file) ?: return@runCatching
                    val summary = ChatSessionSummary(
                        tool = ChatTool.CODEX,
                        id = id,
                        title = title,
                        project = meta.project,
                        updatedMillis = updatedMs,
                        sizeBytes = attrs.size(),
                        mainFile = file,
                        rootDir = root,
                        entries = listOf(snap),
                    )
                    sessions += summary
                    onFound(summary)
                }
            }
        }
    }

    return sessions.sortedByDescending { it.updatedMillis }
}

private val CLAUDE_SKIP_DIRS = setOf("memory", "settings", "todos")

private val UUID_REGEX = Regex(
    "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
)

private fun isUuidFileName(name: String): Boolean = name.endsWith(".jsonl") &&
    UUID_REGEX.matches(name.dropLast(6))

/**
 * 从 Claude jsonl 读会话标题，优先级：
 *   1. `ai-title.aiTitle`  — Claude 自动生成的会话标题，追加在文件末尾
 *   2. `last-prompt.lastPrompt` — 用户最后一条提问，次优
 *   3. 首条非 meta 用户文本消息 — 保底
 *
 * 标题字段用 `\uXXXX` 转义存储，MiniJson 已正确解码，无需额外处理。
 * 文件按 UTF-8 读取，遇到损坏行跳过，不中断整体解析。
 */
@Suppress("UNCHECKED_CAST")
private fun readClaudeTitle(file: Path): String? {
    var aiTitle: String? = null        // 最后一条 ai-title（追加写，越新越靠后）
    var lastPrompt: String? = null     // last-prompt.lastPrompt
    var firstUserText: String? = null  // 首条用户文本（保底）

    runCatching {
        Files.newBufferedReader(file, Charsets.UTF_8).use { br ->
            for (line in br.lineSequence()) {
                val obj = MiniJson.parse(line) ?: continue
                when (obj.jsonStr("type")) {
                    "ai-title" -> {
                        // aiTitle 字段用 \uXXXX 存储，MiniJson 已解码为正常字符串
                        obj.jsonStr("aiTitle")?.takeIf { it.isNotBlank() }?.let { aiTitle = it }
                    }
                    "last-prompt" -> {
                        obj.jsonStr("lastPrompt")?.let(::firstLineSummary)
                            ?.takeIf { it.isNotBlank() }
                            ?.let { lastPrompt = it }
                    }
                    "user" -> {
                        if (firstUserText == null && obj.jsonBool("isMeta") != true) {
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
                            firstUserText = text?.let(::firstLineSummary)?.takeIf { it.isNotBlank() }
                        }
                    }
                }
            }
        }
    }

    return aiTitle ?: lastPrompt ?: firstUserText
}

private fun scanClaudeSessions(
    onFound: (ChatSessionSummary) -> Unit = {},
): List<ChatSessionSummary> {
    val projectsRoot = claudeProjectsRoot() ?: return emptyList()
    val sessions = mutableListOf<ChatSessionSummary>()

    for (projectDir in listDir(projectsRoot)) {
        if (!Files.isDirectory(projectDir)) continue
        val projectName = projectDir.fileName.toString()
        if (projectName in CLAUDE_SKIP_DIRS) continue

        for (entry in listDir(projectDir)) {
            val name = entry.fileName.toString()
            if (!Files.isRegularFile(entry) || !isUuidFileName(name)) continue

            runCatching {
                val uuid = name.dropLast(6)
                val attrs = Files.readAttributes(entry, BasicFileAttributes::class.java,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS)
                val updatedMs = attrs.lastModifiedTime().toMillis()

                val siblingDir = projectDir.resolve(uuid)
                val hasSiblingDir = Files.isDirectory(siblingDir)

                val fileSize = attrs.size()
                val dirSize = if (hasSiblingDir) treeSize(siblingDir) else 0L

                val entries = mutableListOf<PathSnapshot>()
                readPathSnapshot(entry)?.let { entries += it }
                if (hasSiblingDir) entries += snapshotTree(siblingDir)

                val title = readClaudeTitle(entry) ?: uuid

                val summary = ChatSessionSummary(
                    tool = ChatTool.CLAUDE,
                    id = uuid,
                    title = title,
                    project = projectName,
                    updatedMillis = updatedMs,
                    sizeBytes = fileSize + dirSize,
                    mainFile = entry,
                    rootDir = projectsRoot,
                    entries = entries,
                )
                sessions += summary
                onFound(summary)
            }
        }
    }

    return sessions.sortedByDescending { it.updatedMillis }
}

/**
 * 递归收集 Cursor transcript 目录下所有 .jsonl 文件，跳过 subagents/ 子目录。
 */
private fun collectCursorTranscripts(transcriptsRoot: Path): List<Path> {
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
private data class CursorComposerData(
    val name: String?,
    val subtitle: String?,
    val lastUpdatedAt: Long?,
    val workspacePath: String?,
)

/** 从 state.vscdb 读取所有 composerData，返回 Map<composerId, CursorComposerData>。 */
@Suppress("UNCHECKED_CAST")
private fun loadCursorComposerDataMap(): Map<String, CursorComposerData> {
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
private fun readCursorTranscriptTitle(file: Path): String? {
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
 * Cursor 用户消息可能包裹在 `<user_query>...</user_query>` 里，前面还有 `<timestamp>` 等上下文标签。
 * 这里简化处理：若文本以 `<user_query>` 开头（前导空白忽略），提取内部文本。
 */
private fun unwrapCursorUserQuery(raw: String): String {
    val trimmed = raw.trim()
    val queryStart = trimmed.indexOf("<user_query>")
    if (queryStart < 0) return raw
    val queryContentStart = queryStart + "<user_query>".length
    val queryEnd = trimmed.indexOf("</user_query>", queryContentStart)
    if (queryEnd < 0) return raw
    val inner = trimmed.substring(queryContentStart, queryEnd).trim()
    return inner.ifBlank { raw }
}

/**
 * 扫描 Cursor 会话。目录结构：
 *   ~/.cursor/projects/<project-hash>/agent-transcripts/<session-uuid>/<session-uuid>.jsonl
 */
private fun scanCursorSessions(
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

/**
 * 扫描 Open Code 会话。数据存储在 SQLite 数据库中：
 *   ~/.local/share/opencode/opencode.db
 *   - session 表：会话元数据（id, title, project_id, directory, time_updated 等）
 *   - session_message 表：消息内容（关联 session_id）
 *   - project 表：项目信息（id, worktree, name）
 *   ~/.local/share/opencode/storage/session_diff/<session_id>.json：会话的 diff 数据
 */
private fun scanOpenCodeSessions(
    onFound: (ChatSessionSummary) -> Unit = {},
): List<ChatSessionSummary> {
    val dbFile = opencodeDbFile() ?: return emptyList()
    val storageDir = opencodeStorageDir()
    val sessions = mutableListOf<ChatSessionSummary>()

    runCatching {
        DriverManager.getConnection("jdbc:sqlite:$dbFile").use { conn ->
            // 加载所有项目信息到内存，用于后续关联
            val projects = mutableMapOf<String, String>()
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT id, worktree, name FROM project").use { rs ->
                    while (rs.next()) {
                        val id = rs.getString("id")
                        val worktree = rs.getString("worktree")
                        val name = rs.getString("name")
                        // 从 worktree 路径提取项目名，如果有 name 就用 name
                        val projectName = name?.takeIf { it.isNotBlank() }
                            ?: worktree?.let { Path.of(it).fileName?.toString() }
                        if (projectName != null) {
                            projects[id] = projectName
                        }
                    }
                }
            }

            // 扫描所有会话
            conn.createStatement().use { stmt ->
                stmt.executeQuery(
                    """
                    SELECT id, project_id, directory, title, time_created, time_updated
                    FROM session
                    WHERE time_archived IS NULL
                    ORDER BY time_updated DESC
                    """.trimIndent()
                ).use { rs ->
                    while (rs.next()) {
                        runCatching {
                            val sessionId = rs.getString("id")
                            val projectId = rs.getString("project_id")
                            val directory = rs.getString("directory")
                            val title = rs.getString("title")
                            val timeUpdated = rs.getLong("time_updated")

                            // 项目名从 projects map 获取
                            val projectName = projects[projectId]

                            // 计算数据大小
                            val entries = mutableListOf<PathSnapshot>()
                            var totalSize = 0L

                            // session_diff 文件
                            storageDir?.resolve("session_diff")?.resolve("$sessionId.json")?.let { diffFile ->
                                if (Files.isRegularFile(diffFile)) {
                                    readPathSnapshot(diffFile)?.let {
                                        entries += it
                                        totalSize += it.size
                                    }
                                }
                            }

                            // 数据库中的消息数据（估算）
                            conn.createStatement().use { msgStmt ->
                                msgStmt.executeQuery(
                                    "SELECT COUNT(*) as cnt, SUM(LENGTH(data)) as total_len FROM session_message WHERE session_id = '$sessionId'"
                                ).use { msgRs ->
                                    if (msgRs.next()) {
                                        val dataSize = msgRs.getLong("total_len")
                                        totalSize += dataSize
                                    }
                                }
                            }

                            val summary = ChatSessionSummary(
                                tool = ChatTool.OPENCODE,
                                id = sessionId,
                                title = title,
                                project = projectName,
                                updatedMillis = timeUpdated,
                                sizeBytes = totalSize,
                                mainFile = dbFile, // Open Code 使用数据库，没有单独的文件
                                rootDir = dbFile.parent,
                                entries = entries,
                            )
                            sessions += summary
                            onFound(summary)
                        }.onFailure { e ->
                            println("Failed to process Open Code session: ${e.message}")
                        }
                    }
                }
            }
        }
    }.onFailure { e ->
        println("Failed to scan Open Code sessions: ${e.message}")
        e.printStackTrace()
    }

    return sessions.sortedByDescending { it.updatedMillis }
}

/**
 * 枚举本机所有 [ChatTool.CODEX] + [ChatTool.CLAUDE] + [ChatTool.CURSOR] + [ChatTool.OPENCODE] 会话，按更新时间倒序。运行在 IO 线程。
 *
 * [onProgress] 每找到一条会话回调累计数量与字节，供 UI 实时展示「已找到」。
 */
fun scanAllChatSessions(
    tools: Set<ChatTool> = ChatTool.entries.toSet(),
    onProgress: (count: Int, bytes: Long) -> Unit = { _, _ -> },
): List<ChatSessionSummary> {
    val result = mutableListOf<ChatSessionSummary>()
    var count = 0
    var bytes = 0L
    val onFound: (ChatSessionSummary) -> Unit = { session ->
        count++
        bytes += session.sizeBytes
        onProgress(count, bytes)
    }
    if (ChatTool.CODEX in tools) result += scanCodexSessions(onFound)
    if (ChatTool.CLAUDE in tools) result += scanClaudeSessions(onFound)
    if (ChatTool.CURSOR in tools) result += scanCursorSessions(onFound)
    if (ChatTool.OPENCODE in tools) result += scanOpenCodeSessions(onFound)
    return result.sortedByDescending { it.updatedMillis }
}

/** 只读 IO，不改任何文件。 */
@Suppress("UNCHECKED_CAST")
fun loadChatMessages(session: ChatSessionSummary): List<ChatMessage> = runCatching {
    when (session.tool) {
        ChatTool.CLAUDE -> loadClaudeMessages(session.mainFile)
        ChatTool.CODEX -> loadCodexMessages(session.mainFile)
        ChatTool.CURSOR -> loadCursorMessages(session)
        ChatTool.OPENCODE -> loadOpenCodeMessages(session)
        ChatTool.DEEPSEEK_HERMES -> emptyList() // DeepSeek Hermes 暂不支持读取会话内容
    }
}.getOrDefault(emptyList())

private fun loadClaudeMessages(file: Path): List<ChatMessage> {
    val messages = mutableListOf<ChatMessage>()
    Files.newBufferedReader(file).use { br ->
        for (line in br.lineSequence()) {
            val obj = MiniJson.parse(line) ?: continue
            val type = obj.jsonStr("type") ?: continue
            val role = when (type) {
                "user" -> {
                    if (obj.jsonBool("isMeta") == true) continue
                    ChatRole.USER
                }
                "assistant" -> ChatRole.ASSISTANT
                else -> continue
            }
            @Suppress("UNCHECKED_CAST")
            val content = (obj as? Map<String, Any?>)?.get("message")
                ?.let { (it as? Map<String, Any?>)?.get("content") }
            val text = when (content) {
                is String -> content.trim()
                is List<*> -> content
                    .filterIsInstance<Map<String, Any?>>()
                    .mapNotNull { block ->
                        when (block.jsonStr("type")) {
                            "text" -> block.jsonStr("text")?.trim()
                            else -> null
                        }
                    }
                    .joinToString("\n").trim()
                else -> null
            }
            if (!text.isNullOrBlank()) {
                val ts = obj.jsonStr("timestamp")?.let { isoToMillis(it) }
                messages += ChatMessage(role, text, ts)
            }
        }
    }
    return messages
}

@Suppress("UNCHECKED_CAST")
private fun loadCodexMessages(file: Path): List<ChatMessage> {
    val messages = mutableListOf<ChatMessage>()
    Files.newBufferedReader(file).use { br ->
        for (line in br.lineSequence()) {
            val obj = MiniJson.parse(line) ?: continue
            if (obj.jsonStr("type") != "response_item") continue
            val payload = obj.jsonObj("payload") ?: continue
            if (payload.jsonStr("type") != "message") continue
            val role = when (payload.jsonStr("role")) {
                "user" -> ChatRole.USER
                "assistant" -> ChatRole.ASSISTANT
                else -> continue
            }
            val content = payload.jsonArr("content")
            val text = content?.filterIsInstance<Map<String, Any?>>()
                ?.mapNotNull { block ->
                    when (block.jsonStr("type")) {
                        "input_text", "output_text" -> block.jsonStr("text")?.trim()
                        else -> null
                    }
                }
                ?.joinToString("\n")?.trim()
            if (!text.isNullOrBlank()) {
                messages += ChatMessage(role, text, null)
            }
        }
    }
    return messages
}

@Suppress("UNCHECKED_CAST")
private fun loadCursorMessages(session: ChatSessionSummary): List<ChatMessage> {
    val messages = mutableListOf<ChatMessage>()
    Files.newBufferedReader(session.mainFile, Charsets.UTF_8).use { br ->
        for (line in br.lineSequence()) {
            val obj = MiniJson.parse(line) ?: continue
            val role = when (obj.jsonStr("role")) {
                "user" -> ChatRole.USER
                "assistant" -> ChatRole.ASSISTANT
                else -> continue
            }
            // 跳过元数据消息
            if (obj.jsonBool("isMeta") == true || obj.jsonBool("isSidechain") == true) {
                continue
            }
            val content = (obj as? Map<String, Any?>)?.get("message")
                ?.let { (it as? Map<String, Any?>)?.get("content") }
            val text = when (content) {
                is String -> content.trim()
                is List<*> -> content.asSequence()
                    .filterIsInstance<Map<String, Any?>>()
                    .mapNotNull { block ->
                        when (block.jsonStr("type")) {
                            "text" -> block.jsonStr("text")?.trim()
                            else -> null
                        }
                    }
                    .joinToString("\n").trim()
                else -> null
            }
            if (!text.isNullOrBlank()) {
                val finalText = if (role == ChatRole.USER) unwrapCursorUserQuery(text) else text
                messages += ChatMessage(role, finalText, null)
            }
        }
    }
    return messages
}

/**
 * 从 Open Code 数据库加载消息。
 * Open Code 将消息存储在 session_message 表中，type 字段区分不同类型。
 * 用户和助手消息的 data 字段为 JSON，包含消息内容。
 */
@Suppress("UNCHECKED_CAST")
private fun loadOpenCodeMessages(session: ChatSessionSummary): List<ChatMessage> {
    val messages = mutableListOf<ChatMessage>()
    val dbFile = opencodeDbFile() ?: return emptyList()

    runCatching {
        DriverManager.getConnection("jdbc:sqlite:$dbFile").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery(
                    """
                    SELECT type, data, time_created
                    FROM session_message
                    WHERE session_id = '${session.id}'
                    ORDER BY seq
                    """.trimIndent()
                ).use { rs ->
                    while (rs.next()) {
                        val type = rs.getString("type")
                        val dataJson = rs.getString("data")
                        val timeCreated = rs.getLong("time_created")

                        // 跳过非消息类型
                        if (type in setOf("agent-switched", "model-switched", "context-compacted")) {
                            continue
                        }

                        val data = MiniJson.parse(dataJson) as? Map<String, Any?> ?: continue

                        // 提取角色和内容
                        val role = when (type) {
                            "user-message", "user" -> ChatRole.USER
                            "assistant-message", "assistant" -> ChatRole.ASSISTANT
                            else -> continue
                        }

                        // 从 data 中提取文本内容
                        val content = data["content"] ?: data["message"]
                        val text = when (content) {
                            is String -> content.trim()
                            is List<*> -> content.asSequence()
                                .filterIsInstance<Map<String, Any?>>()
                                .mapNotNull { block ->
                                    when (block["type"]) {
                                        "text" -> block["text"] as? String
                                        else -> null
                                    }
                                }
                                .joinToString("\n").trim()
                            is Map<*,*> -> {
                                @Suppress("UNCHECKED_CAST")
                                val contentMap = content as? Map<String, Any?>
                                contentMap?.get("text") as? String ?: ""
                            }
                            else -> null
                        }

                        if (!text.isNullOrBlank()) {
                            messages += ChatMessage(role, text, timeCreated)
                        }
                    }
                }
            }
        }
    }.onFailure { e ->
        println("Failed to load Open Code messages for session ${session.id}: ${e.message}")
    }

    return messages
}
