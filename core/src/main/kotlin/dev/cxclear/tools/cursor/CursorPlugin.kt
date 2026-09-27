package dev.cxclear.tools.cursor

import dev.cxclear.model.ChatTool
import dev.cxclear.tools.ToolPlugin

internal val CursorPlugin = ToolPlugin(
    profile = CursorProfile,
    iconName = "cursor",
    chat = ChatTool(id = CursorProfile.id, displayName = CursorProfile.name),
    scan = ::scanCursorSessions,
    load = ::loadCursorMessages,
    delete = ::deleteCursorSession,
)
