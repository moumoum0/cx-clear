package dev.cxclear.chats

import dev.cxclear.model.PathSnapshot
import dev.cxclear.model.PathSnapshotKind
import dev.cxclear.scan.readPathSnapshot
import dev.cxclear.profiles.homeDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.streams.toList

internal fun claudeProjectsRoot(): Path? =
    homeDir()?.resolve(".claude")?.resolve("projects")?.takeIf { Files.isDirectory(it) }

internal fun codexSessionsRoot(): Path? =
    homeDir()?.resolve(".codex")?.resolve("sessions")?.takeIf { Files.isDirectory(it) }

internal fun codexIndexFile(): Path? =
    homeDir()?.resolve(".codex")?.resolve("session_index.jsonl")?.takeIf { Files.isRegularFile(it) }

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
 * 枚举本机所有 [ChatTool.CODEX] + [ChatTool.CLAUDE] 会话，按更新时间倒序。运行在 IO 线程。
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
    return result.sortedByDescending { it.updatedMillis }
}

/** 只读 IO，不改任何文件。 */
@Suppress("UNCHECKED_CAST")
fun loadChatMessages(session: ChatSessionSummary): List<ChatMessage> = runCatching {
    when (session.tool) {
        ChatTool.CLAUDE -> loadClaudeMessages(session.mainFile)
        ChatTool.CODEX -> loadCodexMessages(session.mainFile)
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
