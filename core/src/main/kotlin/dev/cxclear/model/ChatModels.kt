package dev.cxclear.model

import java.nio.file.Path

const val NO_PROJECT_LABEL = "未归属项目"
// 项目名为空时的归档名。各工具的 [ChatTool.projectLabel] 也落到这里。

class ChatTool(
    // 工具在对话管理里的身份，实例由 `tools/<id>/` 插件持有；相等只看 [id]。
    val id: String,
    val displayName: String,
    val projectLabel: (String?) -> String = { raw ->
        raw?.takeIf { it.isNotBlank() } ?: NO_PROJECT_LABEL
    },
) {
    override fun equals(other: Any?): Boolean = other is ChatTool && other.id == id

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "ChatTool($id)"
}

enum class ChatRole { USER, ASSISTANT }

data class ChatMessage(
    val role: ChatRole,
    val text: String,
    val epochMillis: Long?,
)

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

data class ChatDeleteResult(
    // 一次对话删除的结果。单条失败记入 errors，其余条目继续删。
    val deletedSessions: Int,
    val freedBytes: Long,
    val blockedTools: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
)
