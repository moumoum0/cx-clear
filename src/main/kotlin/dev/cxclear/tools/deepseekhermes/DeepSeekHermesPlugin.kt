package dev.cxclear.tools.deepseekhermes

import dev.cxclear.model.ChatTool
import dev.cxclear.resources.Res
import dev.cxclear.resources.deepseek
import dev.cxclear.tools.ToolPlugin

/** 目前没有会话扫描；登记在这里是为了图标和偏好能看到它。 */
internal val DeepSeekHermesPlugin = ToolPlugin(
    profile = DeepSeekHermesProfile,
    icon = Res.drawable.deepseek,
    shortName = "DeepSeek",
    chat = ChatTool(id = DeepSeekHermesProfile.id, displayName = DeepSeekHermesProfile.name),
)
