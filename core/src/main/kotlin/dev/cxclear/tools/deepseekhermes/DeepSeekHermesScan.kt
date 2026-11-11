package dev.cxclear.tools.deepseekhermes

import dev.cxclear.chats.ChatFileMetaCache
import dev.cxclear.model.ChatSessionSummary
import dev.cxclear.model.PathSnapshot
import dev.cxclear.scan.readPathSnapshot
import dev.cxclear.tools.firstLineSummary
import dev.cxclear.tools.listDir
import dev.cxclear.tools.snapshotTree
import dev.cxclear.tools.treeSize
import dev.cxclear.util.jsonBool
import dev.cxclear.util.jsonObj
import dev.cxclear.util.jsonStr
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

internal fun scanDeepSeekHermesSessions(
    onFound: (ChatSessionSummary) -> Unit = {},
): List<ChatSessionSummary> {
    val root = dshSessionsRoot() ?: return emptyList()
    val home = dshHome() ?: return emptyList()
    val located = mutableListOf<LocatedSession>()
    for (workspaceDir in listDir(root)) {
        if (!Files.isDirectory(workspaceDir)) continue
        for (sessionDir in listDir(workspaceDir)) {
            if (!Files.isDirectory(sessionDir)) continue
            runCatching {
                val log = findSessionLog(sessionDir) ?: return@runCatching
                val header = cachedHeader(log) ?: return@runCatching
                located += LocatedSession(sessionDir, log, header)
            }
        }
    }
    val childrenByParent = located
        .filter { it.header.subagent && it.header.parentId != null }
        .groupBy { it.header.parentId }

    val sessions = mutableListOf<ChatSessionSummary>()
    for (item in located) {
        if (item.header.subagent) continue
        val entries = snapshotTree(item.dir).toMutableList()
        var size = treeSize(item.dir)
        size += includeCache(home, item.header.id, entries)
        for (child in childrenByParent[item.header.id].orEmpty()) {
            entries += snapshotTree(child.dir)
            size += treeSize(child.dir)
            size += includeCache(home, child.header.id, entries)
        }
        val summary = ChatSessionSummary(
            tool = DeepSeekHermesPlugin.chat,
            id = item.header.id,
            title = item.header.title ?: item.header.id,
            project = item.header.cwd?.let(::workspaceName),
            updatedMillis = item.header.updatedMillis,
            sizeBytes = size,
            mainFile = item.log,
            rootDir = root,
            entries = entries,
        )
        sessions += summary
        onFound(summary)
    }

    return sessions.sortedByDescending { it.updatedMillis }
}

private data class LocatedSession(
    val dir: Path,
    val log: Path,
    val header: DeepSeekSessionHeader,
)

// 会话头要解压并解析整个日志；按日志 mtime+size 缓存。id 记成 null 表示上次也没读到头。
private fun cachedHeader(log: Path): DeepSeekSessionHeader? {
    val attrs = runCatching {
        Files.readAttributes(log, BasicFileAttributes::class.java,
            java.nio.file.LinkOption.NOFOLLOW_LINKS)
    }.getOrNull() ?: return null
    val mtime = attrs.lastModifiedTime().toMillis()
    val size = attrs.size()

    val cached = ChatFileMetaCache.lookup(DeepSeekHermesPlugin.chat.id, log, mtime, size)
    if (cached != null) {
        val id = cached.jsonStr("id") ?: return null
        return DeepSeekSessionHeader(
            id = id,
            cwd = cached.jsonStr("cwd"),
            parentId = cached.jsonStr("parentId"),
            title = cached.jsonStr("title"),
            updatedMillis = (cached["updatedMillis"] as? Number)?.toLong() ?: 0L,
            subagent = cached.jsonBool("subagent") == true,
        )
    }

    val header = readSessionHeader(log)
    ChatFileMetaCache.remember(
        DeepSeekHermesPlugin.chat.id, log, mtime, size,
        if (header == null) mapOf<String, Any?>("id" to null) else mapOf(
            "id" to header.id,
            "cwd" to header.cwd,
            "parentId" to header.parentId,
            "title" to header.title,
            "updatedMillis" to header.updatedMillis,
            "subagent" to header.subagent,
        ),
    )
    return header
}

private fun includeCache(home: Path, sessionId: String, entries: MutableList<PathSnapshot>): Long {
    val cache = dshProjCacheFile(home, sessionId)
    if (!Files.isRegularFile(cache)) return 0L
    readPathSnapshot(cache)?.let { entries += it }
    return Files.size(cache)
}

private fun workspaceName(cwd: String): String = cwd
    .trimEnd('\\', '/')
    .substringAfterLast('\\')
    .substringAfterLast('/')

internal data class DeepSeekSessionHeader(
    val id: String,
    val cwd: String?,
    val parentId: String?,
    val title: String?,
    val updatedMillis: Long,
    val subagent: Boolean,
)

internal fun readSessionHeader(log: Path): DeepSeekSessionHeader? = runCatching {
    readDeepSeekLog(log) { events ->
        var id: String? = null
        var cwd: String? = null
        var parentId: String? = null
        var origin: String? = null
        var createdAt = 0L
        var updatedAt = 0L
        var providerTitle: String? = null
        var fallbackTitle: String? = null
        var firstUser: String? = null
        for (event in events) {
            val time = jsonMillis(event["time"]) ?: jsonMillis(event["createdAt"])
            if (time != null && time > updatedAt) updatedAt = time
            when (event.jsonStr("type")) {
                "session" -> {
                    id = event.jsonStr("id")
                    cwd = event.jsonStr("cwd")
                    origin = event.jsonStr("origin")
                    parentId = event.jsonStr("parentSession")
                    createdAt = jsonMillis(event["createdAt"]) ?: createdAt
                }
                "session/title" -> {
                    val title = event.jsonObj("data")?.jsonStr("title")?.takeIf { it.isNotBlank() }
                        ?: continue
                    val kind = event.jsonObj("data")?.jsonObj("source")?.jsonStr("kind")
                    if (kind == "provider") providerTitle = title else fallbackTitle = title
                }
                "user/message" -> {
                    if (firstUser == null && event.jsonObj("data")?.jsonObj("source")?.jsonStr("kind") == "user") {
                        firstUser = textBlocks(event.jsonObj("data"))?.let(::firstLineSummary)
                    }
                }
            }
        }
        val sessionId = id ?: return@readDeepSeekLog null
        DeepSeekSessionHeader(
            id = sessionId,
            cwd = cwd,
            parentId = parentId,
            title = providerTitle ?: fallbackTitle ?: firstUser,
            updatedMillis = updatedAt.takeIf { it > 0L } ?: createdAt,
            subagent = origin == "subagent",
        )
    }
}.getOrNull()

private fun jsonMillis(value: Any?): Long? {
    val number = value as? Number ?: return null
    val asLong = number.toLong()
    return asLong.takeIf { it > 0L }
}
