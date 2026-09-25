package dev.cxclear.clean

import dev.cxclear.model.ChatSessionSummary
import dev.cxclear.model.PathSnapshotKind
import dev.cxclear.scan.readPathSnapshot
import java.io.IOException
import java.nio.file.Files

/** 删除 [session.entries] 里的所有冻结条目，深度优先（文件先于目录）。 */
internal fun deleteSessionEntries(session: ChatSessionSummary): Pair<Long, List<String>> {
    val ordered = session.entries.sortedWith(
        compareByDescending<dev.cxclear.model.PathSnapshot> { it.path.nameCount }
            .thenBy { if (it.kind == PathSnapshotKind.DIRECTORY) 1 else 0 }
    )

    var freed = 0L
    val errors = mutableListOf<String>()

    for (expected in ordered) {
        val current = readPathSnapshot(expected.path)
        if (current == null) {
            continue
        }
        val allowDirectoryMtimeChange = expected.kind == PathSnapshotKind.DIRECTORY
        if (!sameIdentity(expected, current, allowDirectoryMtimeChange)) {
            errors += "${expected.path.fileName}：文件在扫描后已被修改，已跳过"
            continue
        }
        try {
            Files.delete(expected.path)
            if (expected.kind == PathSnapshotKind.FILE) freed += expected.size
        } catch (e: java.nio.file.DirectoryNotEmptyException) {
            errors += "${expected.path.fileName}：目录在扫描后新增了内容，已保留"
        } catch (e: IOException) {
            errors += "${expected.path.fileName}：${e.message ?: "删除失败"}"
        }
    }

    return freed to errors
}
