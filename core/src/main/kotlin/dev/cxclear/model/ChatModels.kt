package dev.cxclear.model

import java.nio.file.Path

/** 项目名为空时的归档名。各工具的 [ChatTool.projectLabel] 也落到这里。 */
const val NO_PROJECT_LABEL = "未归属项目"

/**
 * 一个工具在对话管理里的身份。
 * 实例由 `tools/<id>/` 的插件持有，不在这里枚举具体软件。
 * 相等只看 [id]，方便扫描结果和登记表互相比较。
 */
class ChatTool(
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

/** 一次对话删除的结果。单条失败只记账，不中断其余条目。 */
data class ChatDeleteResult(
    val deletedSessions: Int,
    val freedBytes: Long,
    val blockedTools: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
)
