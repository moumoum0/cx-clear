package dev.cxclear.chats

import dev.cxclear.model.PathSnapshot
import dev.cxclear.model.PathSnapshotKind
import dev.cxclear.scan.readPathSnapshot
import dev.cxclear.profiles.homeDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.streams.toList

// ─────────────────────────────────────────────
// 路径解析
// ─────────────────────────────────────────────

internal fun claudeProjectsRoot(): Path? =
    homeDir()?.resolve(".claude")?.resolve("projects")?.takeIf { Files.isDirectory(it) }

internal fun codexSessionsRoot(): Path? =
    homeDir()?.resolve(".codex")?.resolve("sessions")?.takeIf { Files.isDirectory(it) }

internal fun codexIndexFile(): Path? =
    homeDir()?.resolve(".codex")?.resolve("session_index.jsonl")?.takeIf { Files.isRegularFile(it) }

// ─────────────────────────────────────────────
// 辅助：快照工具
// ─────────────────────────────────────────────

/** 目录下所有直接子条目，失败返回空列表。 */
private fun listDir(dir: Path): List<Path> = runCatching {
    Files.newDirectoryStream(dir).use { it.toList() }
}.getOrDefault(emptyList())

/** 递归收集 [dir] 下所有文件和目录（包含 dir 本身）的快照，不跟随软链接。 */
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

/** 目录及其全部内容的字节总量（只计文件，不跟随链接）。 */
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

// ─────────────────────────────────────────────
// Codex index
// ─────────────────────────────────────────────

/** `session_index.jsonl` 里的一行：id → thread_name / updated_at。 */
private data class CodexIndexEntry(val id: String, val title: String, val updatedAt: String?)

/** 解析整份索引，失败跳过该行（索引本身不会被删除，损坏了不影响主流程）。 */
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

// ─────────────────────────────────────────────
// Codex 会话扫描
// ─────────────────────────────────────────────

/** 把 ISO 8601 字符串（带或不带小数秒、带或不带 'Z'）转成毫秒；失败返回 null。 */
private fun isoToMillis(iso: String?): Long? {
    iso ?: return null
    return runCatching {
        java.time.Instant.parse(iso).toEpochMilli()
    }.getOrNull()
}

/** 从 rollout jsonl 文件名里提取 UUID（最后一段 `-<uuid>`）。 */
private fun codexFileId(file: Path): String {
    val name = file.fileName.toString()
    // 格式：rollout-YYYY-MM-DDTHH-MM-SS-<uuid>.jsonl
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

/** 从 rollout jsonl 里读会话标题（第一条 `session_meta` 的 `cwd`，或第一条用户消息摘要）。 */
private fun readCodexTitle(file: Path): String? {
    return runCatching {
        Files.newBufferedReader(file).use { br ->
            var fallbackCwd: String? = null
            var scanned = 0
            for (line in br.lineSequence()) {
                if (scanned++ > 200) break
                val obj = MiniJson.parse(line) ?: continue
                when (obj.jsonStr("type")) {
                    // session_meta 的 cwd 只做备用：用户第一句话比目录名更能标识会话。
                    "session_meta" -> {
                        val cwd = obj.jsonObj("payload")?.jsonStr("cwd")
                        if (!cwd.isNullOrBlank()) {
                            fallbackCwd = cwd.trimEnd('\\', '/')
                                .substringAfterLast('\\')
                                .substringAfterLast('/')
                        }
                    }
                    "event_msg" -> {
                        val payload = obj.jsonObj("payload") ?: continue
                        if (payload.jsonStr("type") == "user_message") {
                            val msg = payload.jsonStr("message")?.let(::firstLineSummary)
                            if (!msg.isNullOrBlank()) return@use msg
                        }
                    }
                }
            }
            fallbackCwd
        }
    }.getOrNull()
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
                    val title = indexEntry?.title?.ifBlank { null }
                        ?: readCodexTitle(file)
                        ?: file.fileName.toString()
                    val snap = readPathSnapshot(file) ?: return@runCatching
                    val summary = ChatSessionSummary(
                        tool = ChatTool.CODEX,
                        id = id,
                        title = title,
                        project = null,
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

// ─────────────────────────────────────────────
// Claude 会话扫描
// ─────────────────────────────────────────────

/** 跳过非会话目录（memory、其他文本目录）。 */
private val CLAUDE_SKIP_DIRS = setOf("memory", "settings", "todos")

/** UUID 正则：8-4-4-4-12。 */
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

                // 同名子目录（含 subagents 等）
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

// ─────────────────────────────────────────────
// 公开 API
// ─────────────────────────────────────────────

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

// ─────────────────────────────────────────────
// 详情按需解析
// ─────────────────────────────────────────────

/** 解析 [session] 的消息流，返回 user/assistant 纯文本列表。只读 IO，不修改任何文件。 */
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
