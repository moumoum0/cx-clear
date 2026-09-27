package dev.cxclear.cli

import dev.cxclear.model.ChatSessionSummary

/**
 * CLI 筛选条件解析与应用
 */

// 解析时长（支持 30d, 7d, 1h, 30m 等）
internal fun parseDuration(raw: String): Long {
    val pattern = Regex("""^(\d+)([dhms])$""")
    val match = pattern.matchEntire(raw) ?: throw CliUsageException("时长格式错误：$raw（示例：30d, 7h, 30m）")
    val num = match.groupValues[1].toLong()
    val unit = match.groupValues[2]
    return when (unit) {
        "d" -> num * 24 * 3600 * 1000
        "h" -> num * 3600 * 1000
        "m" -> num * 60 * 1000
        "s" -> num * 1000
        else -> throw CliUsageException("未知时长单位：$unit")
    }
}

// 解析大小（支持 100MB, 1GB, 500KB 等）
internal fun parseSize(raw: String): Long {
    val pattern = Regex("""^(\d+(?:\.\d+)?)\s*(B|KB|MB|GB|TB)?$""", RegexOption.IGNORE_CASE)
    val match = pattern.matchEntire(raw) ?: throw CliUsageException("大小格式错误：$raw（示例：100MB, 1GB）")
    val num = match.groupValues[1].toDouble()
    val unit = match.groupValues[2].uppercase().ifEmpty { "B" }
    val multiplier = when (unit) {
        "B" -> 1L
        "KB" -> 1024L
        "MB" -> 1024L * 1024L
        "GB" -> 1024L * 1024L * 1024L
        "TB" -> 1024L * 1024L * 1024L * 1024L
        else -> throw CliUsageException("未知大小单位：$unit")
    }
    return (num * multiplier).toLong()
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
    return ChatFilters(
        olderThan = args.value("older-than")?.let { parseDuration(it) },
        newerThan = args.value("newer-than")?.let { parseDuration(it) },
        keepRecent = args.value("keep-recent")?.toIntOrNull() ?: 
            args.value("keep-recent")?.let { throw CliUsageException("--keep-recent 必须是整数") },
        keepDays = args.value("keep-days")?.toIntOrNull() ?: 
            args.value("keep-days")?.let { throw CliUsageException("--keep-days 必须是整数") },
    )
}
