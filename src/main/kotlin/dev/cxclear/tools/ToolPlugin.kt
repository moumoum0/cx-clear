package dev.cxclear.tools

import dev.cxclear.clean.deleteSessionEntries
import dev.cxclear.model.ChatMessage
import dev.cxclear.model.ChatSessionSummary
import dev.cxclear.model.ChatTool
import dev.cxclear.model.ToolProfile
import dev.cxclear.platform.HostOs
import dev.cxclear.platform.currentOs
import org.jetbrains.compose.resources.DrawableResource

/**
 * 一个软件对外的全部入口：清理名单、图标、会话扫描 / 读消息 / 删除。
 * 没有会话的工具保持 scan / load 默认空实现即可。
 */
class ToolPlugin(
    val profile: ToolProfile,
    val icon: DrawableResource,
    val shortName: String = profile.name,
    val chat: ChatTool,
    /** 空集合表示所有系统都登记。名单按当前系统滤一次，不在每个调用点再判断。 */
    val supportedOs: Set<HostOs> = emptySet(),
    val scan: ((ChatSessionSummary) -> Unit) -> List<ChatSessionSummary> = { emptyList() },
    val load: (ChatSessionSummary) -> List<ChatMessage> = { emptyList() },
    val delete: (ChatSessionSummary) -> Pair<Long, List<String>> = { session ->
        deleteSessionEntries(session)
    },
) {
    fun supports(os: HostOs = currentOs()): Boolean = supportedOs.isEmpty() || os in supportedOs
}
