package dev.cxclear.chats

import dev.cxclear.model.PathSnapshot
import java.nio.file.Path

/** 支持对话管理的工具。Cursor 的会话在受保护的主库里，不做。 */
enum class ChatTool(val id: String, val displayName: String) {
    CODEX("codex", "Codex"),
    CLAUDE("claude", "Claude Code"),
}

enum class ChatRole { USER, ASSISTANT }

/** 详情里展示的一条消息。只保留纯文本，工具调用不展示。 */
data class ChatMessage(
    val role: ChatRole,
    val text: String,
    val epochMillis: Long?,
)

/**
 * 列表里的一条会话。
 *
 * [entries] 是扫描时冻结的待删条目（Codex 一个文件；Claude 主 jsonl + 同级 `<uuid>/` 下的全部内容），
 * 删除阶段只允许按这份清单逐条删，不重新展开目录。
 */
data class ChatSessionSummary(
    val tool: ChatTool,
    val id: String,
    val title: String,
    val project: String?,
    val updatedMillis: Long,
    val sizeBytes: Long,
    val mainFile: Path,
    val rootDir: Path,
    val entries: List<PathSnapshot>,
)

/** 自动保留策略：开启后删除超过 [days] 天未更新的会话。 */
data class RetentionPolicy(
    val enabled: Boolean = false,
    val days: Int = 30,
)

/** 一次对话删除的结果。单条失败只记账，不中断其余条目。 */
data class ChatDeleteResult(
    val deletedSessions: Int,
    val freedBytes: Long,
    val blockedTools: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
)
