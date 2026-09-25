package dev.cxclear.tools

import dev.cxclear.model.ChatSessionSummary
import dev.cxclear.model.ChatTool
import dev.cxclear.platform.currentOs
import dev.cxclear.tools.claude.ClaudePlugin
import dev.cxclear.tools.codex.CodexPlugin
import dev.cxclear.tools.cursor.CursorPlugin
import dev.cxclear.tools.deepseekhermes.DeepSeekHermesPlugin
import dev.cxclear.tools.opencode.OpenCodePlugin

/**
 * 全工具登记表。新工具在 `tools/<id>/` 写完自己的 Plugin，再追加到 [TOOLS]。
 * 扫描、删会话、图标、偏好里的已知 id 都从这里读，不要再各写一份名单。
 *
 * [tools] 按当前系统过滤。某个软件只在部分系统上有数据时，给 Plugin 填 supportedOs。
 */
internal val TOOLS: List<ToolPlugin> = listOf(
    CodexPlugin,
    ClaudePlugin,
    CursorPlugin,
    OpenCodePlugin,
    DeepSeekHermesPlugin,
)

fun tools(): List<ToolPlugin> = TOOLS.filter { it.supports(currentOs()) }

val ALL_PROFILES get() = tools().map { it.profile }

fun chatTools(): List<ChatTool> = tools().map { it.chat }

fun chatToolById(id: String): ChatTool? = tools().firstOrNull { it.chat.id == id }?.chat

fun toolPlugin(tool: ChatTool): ToolPlugin? = tools().firstOrNull { it.chat.id == tool.id }

fun toolPlugin(session: ChatSessionSummary): ToolPlugin? = toolPlugin(session.tool)
