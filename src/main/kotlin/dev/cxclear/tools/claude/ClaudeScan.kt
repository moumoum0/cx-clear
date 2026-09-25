package dev.cxclear.tools.claude

import dev.cxclear.model.ChatSessionSummary
import dev.cxclear.tools.firstLineSummary
import dev.cxclear.tools.listDir
import dev.cxclear.tools.snapshotTree
import dev.cxclear.tools.treeSize
import dev.cxclear.util.MiniJson
import dev.cxclear.util.jsonBool
import dev.cxclear.util.jsonStr
import dev.cxclear.model.PathSnapshot
import dev.cxclear.scan.readPathSnapshot
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * Claude Code 会话扫描：~/.claude/projects/<项目>/<uuid>.jsonl 及其同名兄弟目录。
 */

internal val CLAUDE_SKIP_DIRS = setOf("memory", "settings", "todos")

internal val UUID_REGEX = Regex(
    "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
)

internal fun isUuidFileName(name: String): Boolean = name.endsWith(".jsonl") &&
    UUID_REGEX.matches(name.dropLast(6))

/**
 * 从 Claude jsonl 读会话标题，优先级：
 *   1. `ai-title.aiTitle`  — Claude 自动生成的会话标题，追加在文件末尾
 *   2. `last-prompt.lastPrompt` — 用户最后一条提问，次优
 *   3. 首条非 meta 用户文本消息 — 保底
 *
 * 标题字段用 `\uXXXX` 转义存储，MiniJson 已正确解码，无需额外处理。
 * 文件按 UTF-8 读取，遇到损坏行跳过，不中断整体解析。
 */
@Suppress("UNCHECKED_CAST")
internal fun readClaudeTitle(file: Path): String? {
    var aiTitle: String? = null        // 最后一条 ai-title（追加写，越新越靠后）
    var lastPrompt: String? = null     // last-prompt.lastPrompt
    var firstUserText: String? = null  // 首条用户文本（保底）

    runCatching {
        Files.newBufferedReader(file, Charsets.UTF_8).use { br ->
            for (line in br.lineSequence()) {
                val obj = MiniJson.parse(line) ?: continue
                when (obj.jsonStr("type")) {
                    "ai-title" -> {
                        // aiTitle 字段用 \uXXXX 存储，MiniJson 已解码为正常字符串
                        obj.jsonStr("aiTitle")?.takeIf { it.isNotBlank() }?.let { aiTitle = it }
                    }
                    "last-prompt" -> {
                        obj.jsonStr("lastPrompt")?.let(::firstLineSummary)
                            ?.takeIf { it.isNotBlank() }
                            ?.let { lastPrompt = it }
                    }
                    "user" -> {
                        if (firstUserText == null && obj.jsonBool("isMeta") != true) {
                            val content = (obj as? Map<String, Any?>)?.get("message")
                                ?.let { (it as? Map<String, Any?>)?.get("content") }
                            val text = when (content) {
                                is String -> content.trim()
                                is List<*> -> content.asSequence()
                                    .filterIsInstance<Map<String, Any?>>()
                                    .mapNotNull { it.jsonStr("text") }
                                    .firstOrNull()?.trim()
                                else -> null
                            }
                            firstUserText = text?.let(::firstLineSummary)?.takeIf { it.isNotBlank() }
                        }
                    }
                }
            }
        }
    }

    return aiTitle ?: lastPrompt ?: firstUserText
}

internal fun scanClaudeSessions(
    onFound: (ChatSessionSummary) -> Unit = {},
): List<ChatSessionSummary> {
    val projectsRoot = claudeProjectsRoot() ?: return emptyList()
    val sessions = mutableListOf<ChatSessionSummary>()

    for (projectDir in listDir(projectsRoot)) {
        if (!Files.isDirectory(projectDir)) continue
        val projectName = projectDir.fileName.toString()
        if (projectName in CLAUDE_SKIP_DIRS) continue

        for (entry in listDir(projectDir)) {
            val name = entry.fileName.toString()
            if (!Files.isRegularFile(entry) || !isUuidFileName(name)) continue

            runCatching {
                val uuid = name.dropLast(6)
                val attrs = Files.readAttributes(entry, BasicFileAttributes::class.java,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS)
                val updatedMs = attrs.lastModifiedTime().toMillis()

                val siblingDir = projectDir.resolve(uuid)
                val hasSiblingDir = Files.isDirectory(siblingDir)

                val fileSize = attrs.size()
                val dirSize = if (hasSiblingDir) treeSize(siblingDir) else 0L

                val entries = mutableListOf<PathSnapshot>()
                readPathSnapshot(entry)?.let { entries += it }
                if (hasSiblingDir) entries += snapshotTree(siblingDir)

                val title = readClaudeTitle(entry) ?: uuid

                val summary = ChatSessionSummary(
                    tool = ClaudePlugin.chat,
                    id = uuid,
                    title = title,
                    project = projectName,
                    updatedMillis = updatedMs,
                    sizeBytes = fileSize + dirSize,
                    mainFile = entry,
                    rootDir = projectsRoot,
                    entries = entries,
                )
                sessions += summary
                onFound(summary)
            }
        }
    }

    return sessions.sortedByDescending { it.updatedMillis }
}
