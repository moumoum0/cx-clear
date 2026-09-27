package dev.cxclear.tools.opencode

import dev.cxclear.model.ChatTool
import dev.cxclear.tools.ToolPlugin

internal val OpenCodePlugin = ToolPlugin(
    profile = OpenCodeProfile,
    iconName = "opencode",
    chat = ChatTool(id = OpenCodeProfile.id, displayName = OpenCodeProfile.name),
    scan = ::scanOpenCodeSessions,
    load = ::loadOpenCodeMessages,
    delete = ::deleteOpenCodeSession,
)
