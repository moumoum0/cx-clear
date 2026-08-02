package dev.cxclear.chats

import dev.cxclear.clean.isToolProcessRunning
import dev.cxclear.model.PathSnapshotKind
import dev.cxclear.model.ToolProfile
import dev.cxclear.profiles.ALL_PROFILES
import dev.cxclear.scan.readPathSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files

// ─────────────────────────────────────────────
// 进程保护：找对应 ToolProfile
// ─────────────────────────────────────────────

private fun profileForTool(tool: ChatTool): ToolProfile? =
    ALL_PROFILES.firstOrNull { it.id == tool.id }

private fun isToolRunning(tool: ChatTool): Boolean {
    val profile = profileForTool(tool) ?: return false
    return isToolProcessRunning(profile)
}

// ─────────────────────────────────────────────
// 删除逻辑
// ─────────────────────────────────────────────

/**
 * 删除单条会话。
 *
 * - 先检查目标工具是否在运行；命中则整条阻断。
 * - 只删 [session.entries] 冻结的条目，不重新展开目录。
 * - 软链接不跟随：逐条 `Files.delete`。
 * - 单文件失败跳过记账，不中断其余条目。
 *
 * 返回实际释放字节数（只计已删文件）。
 */
suspend fun deleteSession(session: ChatSessionSummary): ChatDeleteResult = withContext(Dispatchers.IO) {
    if (isToolRunning(session.tool)) {
        return@withContext ChatDeleteResult(
            deletedSessions = 0,
            freedBytes = 0L,
            blockedTools = listOf(session.tool.displayName),
        )
    }
    deleteSessions(listOf(session))
}

/**
 * 批量删除。先对每个工具做进程检测，命中的工具整批跳过。
 * 工具未运行的会话继续逐条删除。
 */
suspend fun deleteSessions(sessions: List<ChatSessionSummary>): ChatDeleteResult = withContext(Dispatchers.IO) {
    val blockedTools = sessions.map { it.tool }.distinct()
        .filter { isToolRunning(it) }
        .map { it.displayName }

    val blockedToolIds = sessions.map { it.tool }.distinct()
        .filter { isToolRunning(it) }
        .map { it.id }
        .toSet()

    val toDelete = sessions.filter { it.tool.id !in blockedToolIds }

    var freed = 0L
    var count = 0
    val errors = mutableListOf<String>()

    for (session in toDelete) {
        val (sessionFreed, sessionErrors) = deleteEntries(session)
        freed += sessionFreed
        if (sessionErrors.isEmpty()) count++
        errors += sessionErrors
    }

    ChatDeleteResult(
        deletedSessions = count,
        freedBytes = freed,
        blockedTools = blockedTools,
        errors = errors.distinct(),
    )
}

/** 删除 [session.entries] 里的所有冻结条目，深度优先（文件先于目录）。 */
private fun deleteEntries(session: ChatSessionSummary): Pair<Long, List<String>> {
    val ordered = session.entries.sortedWith(
        compareByDescending<dev.cxclear.model.PathSnapshot> { it.path.nameCount }
            .thenBy { if (it.kind == PathSnapshotKind.DIRECTORY) 1 else 0 }
    )

    var freed = 0L
    val errors = mutableListOf<String>()

    for (expected in ordered) {
        val current = readPathSnapshot(expected.path)
        if (current == null) {
            // 已经不存在，跳过（可能是同一树里父级先被删了）
            continue
        }
        // 身份校验：文件被替换时拒绝删除。
        val sameFile = current.path == expected.path &&
            current.kind == expected.kind &&
            current.creationMillis == expected.creationMillis &&
            (current.kind == PathSnapshotKind.DIRECTORY ||
                (current.lastModifiedMillis == expected.lastModifiedMillis && current.size == expected.size))
        if (!sameFile) {
            errors += "${expected.path.fileName}：文件在扫描后已被修改，已跳过"
            continue
        }
        try {
            Files.delete(expected.path)
            if (expected.kind == PathSnapshotKind.FILE) freed += expected.size
        } catch (e: java.nio.file.DirectoryNotEmptyException) {
            // 扫描后目录有新内容，保留
            errors += "${expected.path.fileName}：目录在扫描后新增了内容，已保留"
        } catch (e: IOException) {
            errors += "${expected.path.fileName}：${e.message ?: "删除失败"}"
        }
    }

    return freed to errors
}

/**
 * 删除早于 [beforeMillis] 的所有会话（已扫描列表的子集）。
 * 不重新扫描目录：只对 [sessions] 里 [ChatSessionSummary.updatedMillis] < [beforeMillis] 的条目操作。
 */
suspend fun deleteSessionsBefore(
    sessions: List<ChatSessionSummary>,
    beforeMillis: Long,
): ChatDeleteResult {
    val targets = sessions.filter { it.updatedMillis < beforeMillis }
    return deleteSessions(targets)
}
