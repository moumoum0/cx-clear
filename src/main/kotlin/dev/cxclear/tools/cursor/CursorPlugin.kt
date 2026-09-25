package dev.cxclear.tools.cursor

import dev.cxclear.model.ChatTool
import dev.cxclear.resources.Res
import dev.cxclear.resources.cursor
import dev.cxclear.tools.ToolPlugin

internal val CursorPlugin = ToolPlugin(
    profile = CursorProfile,
    icon = Res.drawable.cursor,
    chat = ChatTool(id = CursorProfile.id, displayName = CursorProfile.name),
    scan = ::scanCursorSessions,
    load = ::loadCursorMessages,
    delete = ::deleteCursorSession,
)
