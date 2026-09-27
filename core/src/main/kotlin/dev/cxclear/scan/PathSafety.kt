package dev.cxclear.scan

import dev.cxclear.model.PathSnapshot
import dev.cxclear.model.PathSnapshotKind
import dev.cxclear.model.TargetEntryType
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** 路径展开与删除安全校验：软链接不跟随、路径边界判定、身份快照读取。 */

/**
 * 解析相对路径；单独的路径段 `*` 展开为该层每个非符号链接子目录。
 * 不含 `*` 时与 [Path.resolve] 等价（返回单元素列表）。
 */
internal fun expandPathPattern(baseDir: Path, relPath: String): List<Path> {
    val safeBase = baseDir.toAbsolutePath().normalize()
    val parts = relPath.replace('\\', '/').split('/').filter { it.isNotEmpty() }
    if (parts.any { it == "." || it == ".." }) return emptyList()
    if (parts.isEmpty()) return listOf(safeBase)
    var currents = listOf(safeBase)
    for (part in parts) {
        currents = if (part == "*") {
            currents.flatMap { dir ->
                if (!isSafeTraversalDirectory(safeBase, dir)) {
                    emptyList()
                } else {
                    runCatching {
                        directoryEntries(dir).filter {
                            Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) &&
                                isSafeDeletionPath(safeBase, it)
                        }
                    }.getOrDefault(emptyList())
                }
            }
        } else {
            currents.mapNotNull { current ->
                val candidate = current.resolve(part).toAbsolutePath().normalize()
                candidate.takeIf { it.startsWith(safeBase) }
            }
        }
    }
    return currents
}

/** 最终条目可以是链接（删除链接本身是安全的），但 base 到其父目录之间不能经过链接。 */
internal fun isSafeDeletionPath(baseDir: Path, candidate: Path): Boolean {
    val base = baseDir.toAbsolutePath().normalize()
    val path = candidate.toAbsolutePath().normalize()
    if (path == base || !path.startsWith(base)) return false
    if (linkState(base) != LinkState.PLAIN) return false

    val parent = path.parent ?: return false
    if (parent == base) return true
    val relativeParent = runCatching { base.relativize(parent) }.getOrNull() ?: return false
    var current = base
    for (part in relativeParent) {
        current = current.resolve(part)
        if (linkState(current) != LinkState.PLAIN) return false
    }
    return true
}

/** 只允许真实目录；符号链接和 Windows 目录联接点都不能作为遍历入口。 */
internal fun isSafeTraversalDirectory(baseDir: Path, candidate: Path): Boolean {
    val base = baseDir.toAbsolutePath().normalize()
    val path = candidate.toAbsolutePath().normalize()
    if (!path.startsWith(base)) return false
    if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return false
    if (linkState(base) != LinkState.PLAIN) return false

    val relative = runCatching { base.relativize(path) }.getOrNull() ?: return false
    var current = base
    for (part in relative) {
        current = current.resolve(part)
        if (linkState(current) != LinkState.PLAIN) return false
    }
    return true
}

internal enum class LinkState { PLAIN, LINK_LIKE, UNKNOWN }

internal data class PathInspection(
    val path: Path,
    val attributes: BasicFileAttributes,
    val linkState: LinkState,
)

/** 一次属性读取同时完成类型和链接判断；仅对无法分类的特殊项做跟随目录兜底检查。 */
internal fun inspectPath(path: Path): PathInspection? {
    val normalized = path.toAbsolutePath().normalize()
    val attrs = runCatching {
        Files.readAttributes(normalized, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    }.getOrNull() ?: return null
    val state = when {
        attrs.isSymbolicLink || attrs.isOther -> LinkState.LINK_LIKE
        attrs.isDirectory || attrs.isRegularFile -> LinkState.PLAIN
        Files.isDirectory(normalized) -> LinkState.LINK_LIKE
        else -> LinkState.PLAIN
    }
    return PathInspection(normalized, attrs, state)
}

/** 属性读取失败时返回 UNKNOWN；所有安全判断都把 UNKNOWN 当成拒绝，而不是放行。 */
internal fun linkState(path: Path): LinkState {
    return inspectPath(path)?.linkState ?: LinkState.UNKNOWN
}

internal fun matchesExpectedType(path: Path, expected: TargetEntryType): Boolean {
    val inspection = inspectPath(path) ?: return false
    if (inspection.linkState != LinkState.PLAIN) return false
    val attrs = inspection.attributes
    return when (expected) {
        TargetEntryType.FILE -> attrs.isRegularFile
        TargetEntryType.DIRECTORY -> attrs.isDirectory
    }
}

internal fun lastModifiedOrNull(path: Path): Long? =
    runCatching { Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis() }.getOrNull()

/** 目录条目迭代：目录打不开、迭代中途抛错都当空处理，绝不把异常冒到扫描主流程。 */
internal inline fun forEachDirectoryEntry(directory: Path, action: (Path) -> Unit) {
    val entries = runCatching { Files.newDirectoryStream(directory) }.getOrNull() ?: return
    try {
        val iterator = entries.iterator()
        while (true) {
            val entry = try {
                if (iterator.hasNext()) iterator.next() else break
            } catch (_: Exception) {
                break
            }
            action(entry)
        }
    } finally {
        runCatching { entries.close() }
    }
}

internal fun directoryEntries(directory: Path): List<Path> = buildList {
    forEachDirectoryEntry(directory) { add(it) }
}

/** Cleaner 复用的 NOFOLLOW_LINKS 身份读取。读取失败或特殊类型不生成可删除快照。 */
internal fun readPathSnapshot(path: Path): PathSnapshot? {
    val inspection = inspectPath(path) ?: return null
    val normalized = inspection.path
    val attrs = inspection.attributes
    val kind = when {
        inspection.linkState == LinkState.LINK_LIKE -> PathSnapshotKind.LINK
        attrs.isDirectory -> PathSnapshotKind.DIRECTORY
        attrs.isRegularFile -> PathSnapshotKind.FILE
        else -> return null
    }
    return PathSnapshot(
        path = normalized,
        kind = kind,
        fileKey = attrs.fileKey()?.toString(),
        size = if (kind == PathSnapshotKind.FILE) attrs.size() else 0L,
        creationMillis = attrs.creationTime().toMillis(),
        lastModifiedMillis = attrs.lastModifiedTime().toMillis(),
    )
}
