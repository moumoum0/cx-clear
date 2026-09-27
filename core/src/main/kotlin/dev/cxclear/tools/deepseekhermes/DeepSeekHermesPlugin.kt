package dev.cxclear.tools.deepseekhermes

import dev.cxclear.model.ChatTool
import dev.cxclear.tools.ToolPlugin

internal val DeepSeekHermesPlugin = ToolPlugin(
    profile = DeepSeekHermesProfile,
    iconName = "deepseek",
    shortName = "DeepSeek",
    chat = ChatTool(id = DeepSeekHermesProfile.id, displayName = DeepSeekHermesProfile.name),
    scan = ::scanDeepSeekHermesSessions,
    load = ::loadDeepSeekHermesMessages,
    delete = ::deleteDeepSeekHermesSession,
)
