package dev.cxclear.tools.deepseekhermes

import dev.cxclear.model.ChatTool
import dev.cxclear.resources.Res
import dev.cxclear.resources.deepseek
import dev.cxclear.tools.ToolPlugin

internal val DeepSeekHermesPlugin = ToolPlugin(
    profile = DeepSeekHermesProfile,
    icon = Res.drawable.deepseek,
    shortName = "DeepSeek",
    chat = ChatTool(id = DeepSeekHermesProfile.id, displayName = DeepSeekHermesProfile.name),
    scan = ::scanDeepSeekHermesSessions,
    load = ::loadDeepSeekHermesMessages,
    delete = ::deleteDeepSeekHermesSession,
)
