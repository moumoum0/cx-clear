package dev.cxclear.chats

import dev.cxclear.clean.isToolProcessRunning
import dev.cxclear.clean.sameIdentity
import dev.cxclear.model.PathSnapshotKind
import dev.cxclear.model.ToolProfile
import dev.cxclear.profiles.ALL_PROFILES
import dev.cxclear.scan.readPathSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

private fun profileForTool(tool: ChatTool): ToolProfile? =
    ALL_PROFILES.firstOrNull { it.id == tool.id }

/**
 * 默认的运行检测：查得到 profile 才检测，查不到一律当作未运行
 * （对话页只支持 Codex / Claude，两者都有 profile）。
 */
internal val defaultChatToolIsRunning: (ChatTool) -> Boolean = { tool ->
    val profile = profileForTool(tool)
    if (profile == null) false else isToolProcessRunning(profile)
}

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
suspend fun deleteSession(
    session: ChatSessionSummary,
    toolIsRunning: (ChatTool) -> Boolean = defaultChatToolIsRunning,
): ChatDeleteResult = deleteSessions(listOf(session), toolIsRunning)

/**
 * 批量删除。先对每个工具做进程检测，命中的工具整批跳过。
 * 工具未运行的会话继续逐条删除。
 */
suspend fun deleteSessions(
    sessions: List<ChatSessionSummary>,
    toolIsRunning: (ChatTool) -> Boolean = defaultChatToolIsRunning,
): ChatDeleteResult = deleteSessions(sessions, toolIsRunning, cursorStateDbFile())

internal suspend fun deleteSessions(
    sessions: List<ChatSessionSummary>,
    toolIsRunning: (ChatTool) -> Boolean,
    cursorStateDb: Path?,
): ChatDeleteResult = withContext(Dispatchers.IO) {
    // 每个工具只检测一次：进程枚举有成本，且两次检测结果可能不一致，
    // 那会让「报告为阻断」与「实际跳过」对不上。
    val blocked = sessions.map { it.tool }.distinct().filter(toolIsRunning)
    val blockedTools = blocked.map { it.displayName }
    val blockedToolIds = blocked.map { it.id }.toSet()

    val toDelete = sessions.filter { it.tool.id !in blockedToolIds }

    var freed = 0L
    var count = 0
    val errors = mutableListOf<String>()

    for (session in toDelete) {
        val (sessionFreed, sessionErrors) = when (session.tool) {
            ChatTool.OPENCODE -> deleteOpenCodeSession(session)
            ChatTool.CURSOR -> deleteCursorSession(session, cursorStateDb)
            else -> deleteEntries(session)
        }
        freed += sessionFreed
        if (sessionErrors.isEmpty()) {
            count++
        }
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
        // 身份校验：与 Cleaner 共用同一份判定（含 fileKey），文件被替换时拒绝删除。
        // 子项删除会改变父目录 mtime，所以目录放宽这一项。
        val allowDirectoryMtimeChange = expected.kind == PathSnapshotKind.DIRECTORY
        if (!sameIdentity(expected, current, allowDirectoryMtimeChange)) {
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
 * 删除 Open Code 会话。
 * Open Code 使用 SQLite 存储，需要：
 * 1. 从数据库中删除 session 记录（会级联删除 session_message）
 * 2. 删除 storage/session_diff/<session_id>.json 文件
 */
private fun deleteOpenCodeSession(session: ChatSessionSummary): Pair<Long, List<String>> {
    val dbFile = opencodeDbFile()
    if (dbFile == null) {
        return 0L to listOf("Open Code 数据库文件不存在")
    }

    var freed = 0L
    val errors = mutableListOf<String>()

    runCatching {
        // 先删除关联的文件（storage/session_diff）
        for (entry in session.entries) {
            if (entry.kind == PathSnapshotKind.FILE && Files.exists(entry.path)) {
                try {
                    Files.delete(entry.path)
                    freed += entry.size
                } catch (e: IOException) {
                    errors += "${entry.path.fileName}：${e.message ?: "删除失败"}"
                }
            }
        }

        // 从数据库删除 session（会级联删除 session_message）
        DriverManager.getConnection("jdbc:sqlite:$dbFile").use { conn ->
            conn.autoCommit = false
            try {
                // 计算要删除的消息数据大小（估算）
                var messageDataSize = 0L
                conn.createStatement().use { stmt ->
                    stmt.executeQuery(
                        "SELECT SUM(LENGTH(data)) as total FROM session_message WHERE session_id = '${session.id}'"
                    ).use { rs ->
                        if (rs.next()) {
                            messageDataSize = rs.getLong("total")
                        }
                    }
                }

                // 删除 session（会级联删除关联的 session_message）
                conn.createStatement().use { stmt ->
                    val deleted = stmt.executeUpdate(
                        "DELETE FROM session WHERE id = '${session.id}'"
                    )
                    if (deleted > 0) {
                        freed += messageDataSize
                        conn.commit()
                    } else {
                        errors += "会话 ${session.title} 在数据库中不存在"
                        conn.rollback()
                    }
                }
            } catch (e: Exception) {
                conn.rollback()
                throw e
            }
        }
    }.onFailure { e ->
        errors += "删除 Open Code 会话失败：${e.message}"
    }

    return freed to errors
}

/**
 * 删除 Cursor 会话。
 * 直接按 ComposerService.deleteComposer 的落盘顺序改 state.vscdb：
 * composerHeaders、composerData、bubble/checkpoint/ofs 前缀，以及 transcript。
 * Cursor 在跑时由进程检测整批跳过，避免 WAL 把改动冲掉。
 */
private fun deleteCursorSession(
    session: ChatSessionSummary,
    cursorStateDb: Path?,
): Pair<Long, List<String>> {
    val dbPath = cursorStateDb ?: return 0L to listOf("Cursor 状态库不存在")
    val (dbFreed, errors) = deleteCursorComposerFromStateDb(dbPath, session.id)
    if (errors.isNotEmpty()) return 0L to errors

    val (fileFreed, fileErrors) = deleteEntries(session)
    return (dbFreed + fileFreed) to fileErrors
}
