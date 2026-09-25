package dev.cxclear.chats

import dev.cxclear.scan.readPathSnapshot
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * Codex 会话扫描：读 ~/.codex/session_index.jsonl 索引 + rollout-*.jsonl 头部元数据。
 */

internal data class CodexIndexEntry(val id: String, val title: String, val updatedAt: String?)

/** 坏行跳过；索引本身不会被删，损坏不影响主流程。 */
@Suppress("UNCHECKED_CAST")
internal fun readCodexIndex(): Map<String, CodexIndexEntry> {
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

internal fun lastPathSegment(raw: String): String = raw
    .trimEnd('\\', '/')
    .substringAfterLast('\\')
    .substringAfterLast('/')

internal fun codexFileId(file: Path): String {
    val name = file.fileName.toString()
    val withoutExt = if (name.endsWith(".jsonl")) name.dropLast(6) else name
    val dashIdx = withoutExt.indexOfLast { it == '-' }
    return if (dashIdx >= 0) withoutExt.substring(dashIdx + 1) else withoutExt
}

internal data class CodexMeta(val title: String?, val project: String?)

/**
 * 扫一遍 rollout jsonl 头部，取标题与项目名。
 * 标题优先第一条用户消息，退回 cwd 末段；项目名一律取 cwd 末段。
 */
internal fun readCodexMeta(file: Path): CodexMeta {
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

internal fun scanCodexSessions(
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
