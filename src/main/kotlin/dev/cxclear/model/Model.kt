package dev.cxclear.model

import java.nio.file.Path

/**
 * 清理风险等级。
 * SAFE     — 纯缓存/临时文件，删除后工具会自动重建，不丢任何用户数据。
 * OPTIONAL — 会丢历史记录（会话、日志），功能不受影响但内容不可恢复。
 */
enum class Risk { SAFE, OPTIONAL }

/** 目标匹配方式。 */
enum class MatchKind {
    /** 整个目录连同内容一起删除。 */
    DIRECTORY,

    /** 只删目录里的内容，保留目录本身（有些工具启动时要求目录存在）。 */
    DIRECTORY_CONTENTS,

    /** 单个文件。 */
    FILE,

    /** 目录下用 glob 匹配的一批文件，如 `logs_*.sqlite*`。 */
    GLOB,

    /**
     * 同 [GLOB]，但按修改时间保留最新的一个。
     * 用于「同一程序留了很多历史版本」的目录：旧版本是垃圾，最新那份可能正在被使用。
     */
    STALE_VERSIONS,
}

/**
 * 一个可清理项。[relPath] 相对所属 [ToolProfile] 的 baseDir。
 */
data class CleanTarget(
    val id: String,
    val label: String,
    val relPath: String,
    val kind: MatchKind,
    val risk: Risk,
    val description: String,
    /** 默认是否勾选。SAFE 项默认勾上，OPTIONAL 交给用户决定。 */
    val defaultSelected: Boolean = risk == Risk.SAFE,
)

/**
 * 一个被支持的工具（Codex / Claude Code / …）。
 * [baseDir] 延迟求值，因为路径解析依赖运行时环境变量。
 */
data class ToolProfile(
    val id: String,
    val name: String,
    val subtitle: String,
    val baseDir: () -> Path?,
    val targets: List<CleanTarget>,
)

/** 单个 target 的扫描结果。 */
data class ScanResult(
    val toolId: String,
    val targetId: String,
    val bytes: Long,
    val fileCount: Int,
    val exists: Boolean,
)

/** 清理过程中的事件流。 */
sealed interface CleanEvent {
    data class Started(val totalTargets: Int) : CleanEvent

    data class TargetDone(
        val targetId: String,
        val label: String,
        val freedBytes: Long,
        val error: String? = null,
    ) : CleanEvent

    data class AllDone(val totalFreedBytes: Long, val failures: Int) : CleanEvent
}
