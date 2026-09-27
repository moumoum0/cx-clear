package dev.cxclear.cli

import dev.cxclear.model.ChatSessionSummary
import java.math.BigDecimal

/**
 * CLI 筛选条件解析与应用
 */

// 解析时长（支持 30d, 7d, 1h, 30m 等）
internal fun parseDuration(raw: String): Long {
    val pattern = Regex("""^(\d+)([dhms])$""")
    val match = pattern.matchEntire(raw) ?: throw CliUsageException("时长格式错误：$raw（示例：30d, 7h, 30m）")
    val num = match.groupValues[1].toLongOrNull()
        ?: throw CliUsageException("时长超出范围：$raw")
    val unit = match.groupValues[2]
    val multiplier = when (unit) {
        "d" -> 24L * 3600 * 1000
        "h" -> 3600L * 1000
        "m" -> 60L * 1000
        "s" -> 1000L
        else -> throw CliUsageException("未知时长单位：$unit")
    }
    if (num > Long.MAX_VALUE / multiplier) throw CliUsageException("时长超出范围：$raw")
    return num * multiplier
}

// 解析大小（支持 100MB, 1GB, 500KB 等）
internal fun parseSize(raw: String): Long {
    val pattern = Regex("""^(\d+(?:\.\d+)?)\s*(B|KB|MB|GB|TB)?$""", RegexOption.IGNORE_CASE)
    val match = pattern.matchEntire(raw) ?: throw CliUsageException("大小格式错误：$raw（示例：100MB, 1GB）")
    val num = match.groupValues[1].toBigDecimal()
    val unit = match.groupValues[2].uppercase().ifEmpty { "B" }
    val multiplier = when (unit) {
        "B" -> 1L
        "KB" -> 1024L
        "MB" -> 1024L * 1024L
        "GB" -> 1024L * 1024L * 1024L
        "TB" -> 1024L * 1024L * 1024L * 1024L
        else -> throw CliUsageException("未知大小单位：$unit")
    }
    val bytes = num.multiply(BigDecimal.valueOf(multiplier))
    if (bytes > BigDecimal.valueOf(Long.MAX_VALUE)) throw CliUsageException("大小超出范围：$raw")
    return bytes.toLong()
}

// 应用会话筛选条件
internal data class ChatFilters(
    val olderThan: Long? = null,
    val newerThan: Long? = null,
    val keepRecent: Int? = null,
    val keepDays: Int? = null,
)

internal fun ChatFilters.apply(sessions: List<ChatSessionSummary>, nowMillis: Long): List<ChatSessionSummary> {
    var result = sessions
    
    // 时间筛选
    if (olderThan != null) {
        val threshold = nowMillis - olderThan
        result = result.filter { it.updatedMillis < threshold }
    }
    if (newerThan != null) {
        val threshold = nowMillis - newerThan
        result = result.filter { it.updatedMillis >= threshold }
    }
    
    // keep-days：保留 N 天内的（从筛选结果中排除）
    if (keepDays != null) {
        val keepThreshold = nowMillis - keepDays * 24 * 3600 * 1000L
        result = result.filter { it.updatedMillis < keepThreshold }
    }
    
    // keep-recent：保留最近 N 条（从筛选结果中排除）
    if (keepRecent != null && result.size > keepRecent) {
        val sorted = result.sortedByDescending { it.updatedMillis }
        result = sorted.drop(keepRecent)
    }
    
    return result
}

internal fun parseChatFilters(args: ParsedArgs): ChatFilters {
    val keepRecent = args.value("keep-recent")?.let {
        it.toIntOrNull()?.takeIf { count -> count >= 0 }
            ?: throw CliUsageException("--keep-recent 必须是非负整数")
    }
    val keepDays = args.value("keep-days")?.let {
        it.toIntOrNull()?.takeIf { days -> days >= 0 }
            ?: throw CliUsageException("--keep-days 必须是非负整数")
    }
    return ChatFilters(
        olderThan = args.value("older-than")?.let { parseDuration(it) },
        newerThan = args.value("newer-than")?.let { parseDuration(it) },
        keepRecent = keepRecent,
        keepDays = keepDays,
    )
}
