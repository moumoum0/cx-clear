package dev.cxclear.cli

import dev.cxclear.model.ChatSessionSummary
import dev.cxclear.model.ScanResult
import dev.cxclear.scan.ToolSpaceResult
import dev.cxclear.util.formatBytes

internal fun previewJson(
    command: String,
    matched: Int,
    bytes: Long,
    listKey: String,
    items: List<Any?>,
    extra: Map<String, Any?> = emptyMap(),
): Map<String, Any?> = buildMap {
    put("ok", true)
    put("command", command)
    put("preview", true)
    put("matched", matched)
    put("bytes", bytes)
    put(listKey, items)
    putAll(extra)
}

internal fun spaceJson(space: ToolSpaceResult) = mapOf(
    "tool" to space.toolId,
    "bytes" to space.bytes,
    "files" to space.fileCount,
    "bytes_label" to formatBytes(space.bytes),
)

internal fun targetJson(result: ScanResult): Map<String, Any?> {
    val (_, target) = profileAndTarget(result.toolId, result.targetId)
    return mapOf(
        "tool" to result.toolId,
        "target_id" to result.targetId,
        "label" to target.label,
        "risk" to target.risk.name.lowercase(),
        "default_selected" to target.defaultSelected,
        "bytes" to result.bytes,
        "files" to result.fileCount,
        "bytes_label" to formatBytes(result.bytes),
    )
}

internal fun sessionJson(session: ChatSessionSummary) = mapOf(
    "id" to "${session.tool.id}:${session.id}",
    "tool" to session.tool.id,
    "session_id" to session.id,
    "title" to session.title,
    "project" to session.project,
    "updated_millis" to session.updatedMillis,
    "bytes" to session.sizeBytes,
    "bytes_label" to formatBytes(session.sizeBytes),
)
