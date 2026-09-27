package dev.cxclear.tools

import dev.cxclear.model.PathSnapshot
import dev.cxclear.model.PathSnapshotKind
import dev.cxclear.scan.readPathSnapshot
import dev.cxclear.storage.homeDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.streams.toList

internal fun homeSubdir(name: String): Path? =
    homeDir()?.resolve(name)?.takeIf { Files.isDirectory(it) }

internal fun directMatches(root: Path, vararg patterns: String): List<Path> {
    val matchers = patterns.map { root.fileSystem.getPathMatcher("glob:$it") }
    return Files.newDirectoryStream(root).use { entries ->
        buildList {
            for (entry in entries) {
                if (matchers.any { it.matches(entry.fileName) }) add(entry)
            }
        }
    }
}

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
                    val attrs = Files.readAttributes(
                        p,
                        BasicFileAttributes::class.java,
                        java.nio.file.LinkOption.NOFOLLOW_LINKS,
                    )
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
