package dev.cxclear.chats

import dev.cxclear.model.PathSnapshot
import dev.cxclear.model.PathSnapshotKind
import dev.cxclear.scan.readPathSnapshot
import dev.cxclear.storage.homeDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.streams.toList

/**
 * 各工具会话扫描共用的入口路径解析与目录/文本 helper。
 * 这里的路径函数同时被 chats/ChatDeleter.kt 引用，名字与可见性不能改。
 */

internal fun claudeProjectsRoot(): Path? =
    homeDir()?.resolve(".claude")?.resolve("projects")?.takeIf { Files.isDirectory(it) }

internal fun codexSessionsRoot(): Path? =
    homeDir()?.resolve(".codex")?.resolve("sessions")?.takeIf { Files.isDirectory(it) }

internal fun codexIndexFile(): Path? =
    homeDir()?.resolve(".codex")?.resolve("session_index.jsonl")?.takeIf { Files.isRegularFile(it) }

/** Cursor 项目存储根目录：~/.cursor/projects/ */
internal fun cursorProjectsRoot(): Path? =
    homeDir()?.resolve(".cursor")?.resolve("projects")?.takeIf { Files.isDirectory(it) }

/** Cursor 全局状态库：%APPDATA%/Cursor/User/globalStorage/state.vscdb */
internal fun cursorStateDbFile(): Path? {
    val appData = System.getenv("APPDATA") ?: return null
    return Path.of(appData, "Cursor", "User", "globalStorage", "state.vscdb")
        .takeIf { Files.isRegularFile(it) }
}

/** Open Code 数据库文件：~/.local/share/opencode/opencode.db */
internal fun opencodeDbFile(): Path? =
    homeDir()?.resolve(".local")?.resolve("share")?.resolve("opencode")?.resolve("opencode.db")
        ?.takeIf { Files.isRegularFile(it) }

/** Open Code 存储目录：~/.local/share/opencode/storage/ */
internal fun opencodeStorageDir(): Path? =
    homeDir()?.resolve(".local")?.resolve("share")?.resolve("opencode")?.resolve("storage")
        ?.takeIf { Files.isDirectory(it) }

internal fun listDir(dir: Path): List<Path> = runCatching {
    Files.newDirectoryStream(dir).use { it.toList() }
}.getOrDefault(emptyList())

/** 不跟随软链接。 */
internal fun snapshotTree(dir: Path): List<PathSnapshot> {
    if (!Files.exists(dir)) return emptyList()
    val snap = readPathSnapshot(dir) ?: return emptyList()
    val list = mutableListOf(snap)
    if (snap.kind == PathSnapshotKind.DIRECTORY) {
        for (child in listDir(dir)) {
            list += snapshotTree(child)
        }
    }
    return list
}

/** 只计文件，不跟随链接。 */
internal fun treeSize(dir: Path): Long {
    if (!Files.exists(dir)) return 0L
    return runCatching {
        Files.walk(dir).use { stream ->
            stream.mapToLong { p ->
                runCatching {
                    val attrs = Files.readAttributes(p, BasicFileAttributes::class.java,
                        java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    if (attrs.isRegularFile) attrs.size() else 0L
                }.getOrDefault(0L)
            }.sum()
        }
    }.getOrDefault(0L)
}

internal fun isoToMillis(iso: String?): Long? {
    iso ?: return null
    return runCatching {
        java.time.Instant.parse(iso).toEpochMilli()
    }.getOrNull()
}

/**
 * 把一段消息文本压成一行标题。
 * 首条消息常含 `<environment_context>` 一类注入块，跳过尖括号行，取第一行真正的用户文字。
 */
internal fun firstLineSummary(raw: String): String? = raw
    .lineSequence()
    .map { it.trim() }
    .firstOrNull { it.isNotBlank() && !it.startsWith('<') }
    ?.take(60)

/**
 * Cursor 用户消息可能包裹在 `<user_query>...</user_query>` 里，前面还有 `<timestamp>` 等上下文标签。
 * 这里简化处理：若文本以 `<user_query>` 开头（前导空白忽略），提取内部文本。
 */
internal fun unwrapCursorUserQuery(raw: String): String {
    val trimmed = raw.trim()
    val queryStart = trimmed.indexOf("<user_query>")
    if (queryStart < 0) return raw
    val queryContentStart = queryStart + "<user_query>".length
    val queryEnd = trimmed.indexOf("</user_query>", queryContentStart)
    if (queryEnd < 0) return raw
    val inner = trimmed.substring(queryContentStart, queryEnd).trim()
    return inner.ifBlank { raw }
}
