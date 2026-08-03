package dev.cxclear.clean

import dev.cxclear.model.CleanEvent
import dev.cxclear.model.CleanTarget
import dev.cxclear.model.DeletionPlan
import dev.cxclear.model.PathSnapshot
import dev.cxclear.model.PathSnapshotKind
import dev.cxclear.model.ToolProfile
import dev.cxclear.scan.isSafeDeletionPath
import dev.cxclear.scan.readPathSnapshot
import dev.cxclear.scan.resolveBase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.IOException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** 用户勾选的一项，以及扫描阶段冻结下来的精确删除清单。 */
data class CleanRequest(
    val profile: ToolProfile,
    val target: CleanTarget,
    val plan: DeletionPlan,
)

private class DeleteOutcome {
    var freed: Long = 0
    var failed: Int = 0
    var firstError: String? = null

    fun note(error: Throwable) {
        failed++
        if (firstError == null) firstError = error.message ?: error::class.simpleName ?: "未知错误"
    }
}

/**
 * 冻结快照与当前状态是否仍是同一个对象。会话删除（[dev.cxclear.chats.deleteSessions]）复用同一份判定：
 * 校验分成两套实现，任一套漏比对一项就等于开了一条误删的旁路。
 */
internal fun sameIdentity(
    expected: PathSnapshot,
    current: PathSnapshot,
    allowDirectoryMtimeChange: Boolean = false,
): Boolean {
    if (expected.path != current.path || expected.kind != current.kind) return false
    if (expected.fileKey != null || current.fileKey != null) {
        if (expected.fileKey == null || expected.fileKey != current.fileKey) return false
    }
    if (expected.creationMillis != current.creationMillis) return false
    return when (expected.kind) {
        // 子项删除会改变父目录 mtime，因此只在删除动作已经开始后放宽这一项。
        PathSnapshotKind.DIRECTORY ->
            allowDirectoryMtimeChange || expected.lastModifiedMillis == current.lastModifiedMillis
        PathSnapshotKind.FILE ->
            expected.lastModifiedMillis == current.lastModifiedMillis &&
                expected.size == current.size
        PathSnapshotKind.LINK -> expected.lastModifiedMillis == current.lastModifiedMillis
    }
}

private fun normalizedProtectedPaths(profile: ToolProfile): Result<List<Path>> = runCatching {
    profile.protectedPaths().map { it.toAbsolutePath().normalize() }
}

private fun overlapsProtectedPath(path: Path, protected: Path): Boolean {
    if (path == protected || path.startsWith(protected) || protected.startsWith(path)) return true
    val realPath = runCatching { path.toRealPath(LinkOption.NOFOLLOW_LINKS) }.getOrNull() ?: return false
    val realProtected = runCatching { protected.toRealPath(LinkOption.NOFOLLOW_LINKS) }.getOrNull() ?: return false
    return realPath == realProtected ||
        realPath.startsWith(realProtected) ||
        realProtected.startsWith(realPath)
}

/** 删除前先完整校验整份计划；任何路径变化都会让该 target 在删除第一个文件前终止。 */
private fun validatePlan(request: CleanRequest, outcome: DeleteOutcome): Boolean {
    if (request.plan.toolId != request.profile.id || request.plan.targetId != request.target.id) {
        outcome.note(IOException("扫描计划与所选清理项不匹配，已拒绝清理"))
        return false
    }
    val currentBase = runCatching { resolveBase(request.profile, request.target) }.getOrNull()
    if (currentBase == null || currentBase != request.plan.baseDir) {
        outcome.note(IOException("扫描后目标根目录发生变化，已取消该项清理"))
        return false
    }
    val protectedPaths = normalizedProtectedPaths(request.profile).getOrElse {
        outcome.note(IOException("无法校验永久保护路径，已拒绝清理", it))
        return false
    }
    for (expected in request.plan.entries) {
        if (!isSafeDeletionPath(request.plan.baseDir, expected.path)) {
            outcome.note(IOException("扫描后路径边界发生变化，已取消该项清理"))
            return false
        }
        if (protectedPaths.any { overlapsProtectedPath(expected.path, it) }) {
            outcome.note(IOException("目标命中永久保护路径，已拒绝清理"))
            return false
        }
        val current = readPathSnapshot(expected.path)
        if (current == null || !sameIdentity(expected, current)) {
            outcome.note(IOException("扫描后文件已新增、替换或修改，请重新扫描"))
            return false
        }
    }
    return true
}

/**
 * 只删除扫描时冻结的条目，按深度从深到浅逐个删除。
 *
 * 不再 walkFileTree：扫描后新出现的文件不在计划内，因此不会被递归带走；父目录非空时只会保留。
 */
private fun deletePlan(request: CleanRequest, outcome: DeleteOutcome) {
    if (!validatePlan(request, outcome)) return

    val ordered = request.plan.entries.sortedWith(
        compareByDescending<PathSnapshot> { it.path.nameCount }
            .thenBy { if (it.kind == PathSnapshotKind.DIRECTORY) 1 else 0 },
    )
    for (expected in ordered) {
        if (!isSafeDeletionPath(request.plan.baseDir, expected.path)) {
            outcome.note(IOException("删除期间路径边界发生变化，已停止该项清理"))
            return
        }
        val current = readPathSnapshot(expected.path)
        val allowDirectoryMtimeChange = expected.kind == PathSnapshotKind.DIRECTORY
        if (current == null || !sameIdentity(expected, current, allowDirectoryMtimeChange)) {
            outcome.note(IOException("删除期间文件已被替换或修改，已停止该项清理"))
            return
        }
        try {
            Files.delete(expected.path)
            if (expected.kind == PathSnapshotKind.FILE) outcome.freed += expected.size
        } catch (e: DirectoryNotEmptyException) {
            // 扫描后出现的新内容绝不删除；保留目录并明确报错。
            outcome.note(IOException("目录在扫描后出现新内容，新增内容已保留", e))
        } catch (e: Exception) {
            outcome.note(e)
        }
    }
}

private fun processMatchesTool(profile: ToolProfile, executableName: String): Boolean =
    profile.processNamePrefixes.any { executableName.startsWith(it.lowercase()) }

/** 清理运行中的工具会产生扫描/删除竞态；检测到相关进程时整批阻断。 */
internal fun isToolProcessRunning(profile: ToolProfile): Boolean = runCatching {
    ProcessHandle.allProcesses().use { processes ->
        processes.anyMatch { process ->
            val command = runCatching { process.info().command().orElse(null) }
                .getOrNull() ?: return@anyMatch false
            val executable = runCatching { Path.of(command).fileName.toString().lowercase() }
                .getOrDefault("")
            processMatchesTool(profile, executable)
        }
    }
}.getOrDefault(true)

/**
 * 逐项清理并以事件流上报进度。
 *
 * 大小以实际删掉的字节累计。删除前要求工具已退出，并且文件身份与扫描时完全一致。
 */
fun clean(
    requests: List<CleanRequest>,
    toolIsRunning: (ToolProfile) -> Boolean = ::isToolProcessRunning,
): Flow<CleanEvent> = flow {
    emit(CleanEvent.Started(requests.size))

    val runningProfiles = requests
        .map { it.profile }
        .distinctBy { it.id }
        .filter { toolIsRunning(it) }
    if (runningProfiles.isNotEmpty()) {
        emit(CleanEvent.Blocked(runningProfiles.map { it.name }))
        emit(CleanEvent.AllDone(0L, runningProfiles.size))
        return@flow
    }

    var total = 0L
    var failures = 0
    for (request in requests) {
        val outcome = DeleteOutcome()
        if (toolIsRunning(request.profile)) {
            outcome.note(IOException("清理过程中检测到 ${request.profile.name} 已启动，已取消该项清理"))
        } else {
            deletePlan(request, outcome)
        }
        total += outcome.freed
        val error = outcome.firstError?.let {
            failures++
            if (outcome.failed > 1) "$it（另有 ${outcome.failed - 1} 项失败）" else it
        }
        emit(CleanEvent.TargetDone(request.target.id, request.target.label, outcome.freed, error))
    }

    emit(CleanEvent.AllDone(total, failures))
}.flowOn(Dispatchers.IO)
