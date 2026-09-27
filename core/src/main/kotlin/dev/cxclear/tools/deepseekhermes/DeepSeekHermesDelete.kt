package dev.cxclear.tools.deepseekhermes

import dev.cxclear.clean.deleteSessionEntries
import dev.cxclear.model.ChatSessionSummary
import dev.cxclear.util.MiniJson
import dev.cxclear.util.jsonArr
import dev.cxclear.util.jsonMap
import dev.cxclear.util.jsonObj
import java.nio.file.Files

/**
 * 删扫描时冻结的会话目录（含子代理目录和投影缓存），
 * 再从 workspace.json 的 sessionIds 里摘掉这条。索引改失败时文件已经删了，要把错误带回。
 */
internal fun deleteDeepSeekHermesSession(session: ChatSessionSummary): Pair<Long, List<String>> {
    val (freed, errors) = deleteSessionEntries(session)
    if (errors.isNotEmpty()) return freed to errors
    return freed to listOfNotNull(removeFromWorkspaceIndex(session.id))
}

private fun removeFromWorkspaceIndex(sessionId: String): String? {
    val file = dshWorkspaceFile() ?: return null
    return runCatching {
        val root = MiniJson.parse(Files.readString(file))?.jsonMap()?.toMutableMap()
            ?: return "workspace.json 无法解析，会话索引未更新"
        val tables = root.jsonObj("tables")?.toMutableMap() ?: return null
        val workspaces = tables.jsonObj("workspaces")?.toMutableMap() ?: return null
        var changed = false
        for ((key, value) in workspaces) {
            val workspace = value.jsonMap()?.toMutableMap() ?: continue
            val ids = workspace.jsonArr("sessionIds")?.filterIsInstance<String>() ?: continue
            if (sessionId !in ids) continue
            workspace["sessionIds"] = ids.filter { it != sessionId }
            workspaces[key] = workspace
            changed = true
        }
        if (!changed) return null
        val global = root.jsonObj("global")?.toMutableMap()
        if (global != null) {
            for (field in listOf("archivedSessionIds", "pinnedSessionIds")) {
                val ids = global.jsonArr(field)?.filterIsInstance<String>() ?: continue
                if (sessionId in ids) global[field] = ids.filter { it != sessionId }
            }
            root["global"] = global
        }
        tables["workspaces"] = workspaces
        root["tables"] = tables
        Files.writeString(file, MiniJson.stringify(root))
        null
    }.getOrElse { "workspace.json：${it.message ?: "更新失败"}" }
}
