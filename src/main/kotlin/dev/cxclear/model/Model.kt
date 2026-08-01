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
 * 一个可清理项。[relPath] 相对 [baseDir]（若指定）或所属 [ToolProfile] 的 baseDir。
 *
 * [relPath] 中单独的路径段 `*` 表示「展开为该层每个子目录」
 *（例：projects 下每个子目录里的 agent-tools）。最后一段的文件名 glob
 *（配合 GLOB / STALE_VERSIONS）与路径段展开不冲突。
 */
data class CleanTarget(
    val id: String,
    val label: String,
    val relPath: String,
    val kind: MatchKind,
    val risk: Risk,
    val description: String,
    /**
     * 默认是否勾选。
     * 一般 SAFE 默认勾上、OPTIONAL 交给用户；重建成本高（大体积重下、会暂时影响功能）
     * 的 SAFE 项应显式设为 false。
     */
    val defaultSelected: Boolean = risk == Risk.SAFE,
    /**
     * 覆盖所属 profile 的根目录。
     * 用于数据分散在多处的工具（如 Cursor：`~/.cursor` 与 `%APPDATA%\\Cursor`）。
     */
    val baseDir: (() -> Path?)? = null,
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
    /**
     * 计入「工具总占用」的目录列表（不含安装目录）。
     * 默认只有 [baseDir]；多根工具（Cursor）在此列出全部数据根。
     */
    val spaceDirs: () -> List<Path> = { listOfNotNull(baseDir()) },
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
