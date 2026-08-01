package dev.cxclear.clean

import dev.cxclear.model.CleanEvent
import dev.cxclear.model.CleanTarget
import dev.cxclear.model.ToolProfile
import dev.cxclear.scan.ResolvedTarget
import dev.cxclear.scan.isSafeDeletionPath
import dev.cxclear.scan.isSafeTraversalDirectory
import dev.cxclear.scan.resolveBase
import dev.cxclear.scan.resolveTarget
import dev.cxclear.scan.scanResolved
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** 用户勾选的一项：属于哪个工具的哪个 target。 */
data class CleanRequest(val profile: ToolProfile, val target: CleanTarget)

private class DeleteOutcome {
    var freed: Long = 0
    var failed: Int = 0
    var firstError: String? = null

    fun note(e: Exception) {
        failed++
        if (firstError == null) firstError = e.message ?: e::class.simpleName ?: "未知错误"
    }
}

/**
 * 递归删除。删不掉的条目（被占用、权限不足）跳过并记账，不让单个文件中断整个清理。
 */
private fun deleteRecursively(root: Path, allowedRoot: Path, outcome: DeleteOutcome) {
    if (!isSafeDeletionPath(allowedRoot, root)) {
        outcome.note(IOException("拒绝删除允许目录之外或经由目录链接的路径"))
        return
    }
    if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return

    if (!Files.isDirectory(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
        val size = runCatching { Files.size(root) }.getOrDefault(0L)
        try {
            Files.delete(root)
            outcome.freed += size
        } catch (e: IOException) {
            outcome.note(e)
        }
        return
    }

    try {
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (isSafeTraversalDirectory(allowedRoot, dir)) return FileVisitResult.CONTINUE

                // 若运行时把子目录换成了链接/联接点，只删除链接本身，绝不进入其目标。
                runCatching { Files.delete(dir) }.onFailure {
                    outcome.note(it as? Exception ?: IOException(it.message, it))
                }
                return FileVisitResult.SKIP_SUBTREE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (!isSafeDeletionPath(allowedRoot, file)) {
                    outcome.note(IOException("删除期间路径边界发生变化，已跳过"))
                    return FileVisitResult.CONTINUE
                }
                val size = if (attrs.isSymbolicLink) 0L else attrs.size()
                try {
                    Files.delete(file)
                    outcome.freed += size
                } catch (e: IOException) {
                    outcome.note(e)
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                outcome.note(exc)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                // 目录里还有删不掉的文件时，删目录本身也会失败 —— 这不是新错误，静默跳过。
                if (isSafeDeletionPath(allowedRoot, dir)) {
                    runCatching { Files.delete(dir) }
                } else {
                    outcome.note(IOException("删除期间路径边界发生变化，已跳过"))
                }
                return FileVisitResult.CONTINUE
            }
        })
    } catch (e: IOException) {
        outcome.note(e)
    }
}

/**
 * 逐项清理并以事件流上报进度。
 *
 * 大小以实际删掉的字节累计，而不是扫描时的预估值 —— 有文件被占用时，报出来的数字才是真的。
 */
fun clean(requests: List<CleanRequest>): Flow<CleanEvent> = flow {
    emit(CleanEvent.Started(requests.size))

    var total = 0L
    var failures = 0

    for (req in requests) {
        val base = resolveBase(req.profile, req.target)
        if (base == null) {
            emit(CleanEvent.TargetDone(req.target.id, req.target.label, 0L, "目录不存在"))
            continue
        }

        val resolved: ResolvedTarget = resolveTarget(base, req.target)
        val outcome = DeleteOutcome()
        for (p in resolved.paths) {
            deleteRecursively(p, resolved.baseDir, outcome)
        }

        total += outcome.freed
        val error = outcome.firstError?.let {
            failures++
            if (outcome.failed > 1) "$it（另有 ${outcome.failed - 1} 项失败）" else it
        }
        emit(CleanEvent.TargetDone(req.target.id, req.target.label, outcome.freed, error))
    }

    emit(CleanEvent.AllDone(total, failures))
}.flowOn(Dispatchers.IO)

/** 清理后重新量一遍某项的残留，用于校验结果。 */
fun remaining(request: CleanRequest): Long {
    val base = resolveBase(request.profile, request.target) ?: return 0L
    return scanResolved(request.profile.id, resolveTarget(base, request.target)).bytes
}
