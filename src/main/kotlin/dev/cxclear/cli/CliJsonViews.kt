package dev.cxclear.cli

import dev.cxclear.model.ChatSessionSummary
import dev.cxclear.model.CleanTarget
import dev.cxclear.model.ScanResult
import dev.cxclear.model.ToolProfile
import dev.cxclear.tools.ALL_PROFILES
import dev.cxclear.scan.ToolSpaceResult
import dev.cxclear.util.formatBytes

/**
 * 给 AI 看的 JSON 投影：domain 对象 → 稳定的字段名。
 *
 * 字段名（bytes_label / target_id 之类）是外部契约，改名会破坏消费方。
 */
internal fun profileAndTarget(toolId: String, targetId: String): Pair<ToolProfile, CleanTarget> {
    val profile = ALL_PROFILES.first { it.id == toolId }
    return profile to profile.targets.first { it.id == targetId }
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
