package dev.cxclear.chats

/**
 * 自动清理策略模型与匹配引擎。
 *
 * 一条 [RetentionRule] = 若干 [ChatCondition] 用「且 / 或」（[ConditionJoin]）串起来；
 * 一份 [RetentionConfig] 可以有多条规则，规则之间恒为「或」——任一规则命中即待删。
 *
 * 这里只做纯计算，不碰文件系统，落盘在 [RetentionStore]，执行在 [RetentionRunner]。
 */

private const val MB = 1024L * 1024L
private const val DAY_MILLIS = 86_400_000L

/** 条件取值的形态，决定 UI 给什么输入控件、以及数值后面跟什么单位。 */
enum class ConditionValueKind(val unit: String) {
    /** 天数，整数 */
    DAYS("天"),

    /** 兆字节，整数 */
    MEGABYTES("MB"),

    /** 单选一个 [ChatTool]，值存工具 id */
    TOOL(""),

    /** 自由文本，大小写不敏感的「包含」匹配 */
    TEXT(""),
}

/** 条件类型。[label] 与取值拼起来就是一句可读的中文（「未更新超过 30 天」）。 */
enum class ChatConditionType(
    val id: String,
    val label: String,
    val kind: ConditionValueKind,
) {
    UPDATED_BEFORE_DAYS("older_than", "未更新超过", ConditionValueKind.DAYS),
    UPDATED_WITHIN_DAYS("newer_than", "未更新少于", ConditionValueKind.DAYS),
    SIZE_LARGER_MB("larger_than", "大小超过", ConditionValueKind.MEGABYTES),
    SIZE_SMALLER_MB("smaller_than", "大小少于", ConditionValueKind.MEGABYTES),
    TOOL_IS("tool_is", "所属工具是", ConditionValueKind.TOOL),
    PROJECT_CONTAINS("project_has", "项目名包含", ConditionValueKind.TEXT),
    TITLE_CONTAINS("title_has", "标题包含", ConditionValueKind.TEXT),
    ;

    companion object {
        fun fromId(id: String): ChatConditionType? = entries.firstOrNull { it.id == id }
    }
}

/**
 * 一个条件。数值型用 [number]，文本型与工具型用 [text]，
 * 同时保留两个字段是为了在 UI 上切换类型时不丢已填的输入。
 */
data class ChatCondition(
    val type: ChatConditionType,
    val number: Int = defaultNumberFor(type),
    val text: String = "",
)

/** 各类型的默认数值：新加条件时给一个合理起点，而不是 0（0 天会命中全部会话）。 */
fun defaultNumberFor(type: ChatConditionType): Int = when (type.kind) {
    ConditionValueKind.DAYS -> 30
    ConditionValueKind.MEGABYTES -> 10
    else -> 0
}

/** 规则内条件的组合方式。 */
enum class ConditionJoin(val id: String, val label: String) {
    AND("and", "且"),
    OR("or", "或"),
    ;

    companion object {
        fun fromId(id: String): ConditionJoin = entries.firstOrNull { it.id == id } ?: AND
    }
}

/**
 * 一条策略。[enabled] 由用户单独开关；新建时默认关闭，避免建好就开始删。
 *
 * [name] 是列表里的唯一可读标识（列表行只显示名称、不再展示条件主句），因此建策略时必填；
 * 旧配置或迁移出来的规则可能为空，读回后由 UI 用条件句子兜底显示。
 */
data class RetentionRule(
    val id: String,
    val name: String = "",
    val enabled: Boolean = false,
    val join: ConditionJoin = ConditionJoin.AND,
    val conditions: List<ChatCondition> = emptyList(),
)

/** 全部策略。规则之间是「或」：任一条命中的会话都会被删。 */
data class RetentionConfig(val rules: List<RetentionRule> = emptyList())

// ─────────────────────────────────────────────
// 条件完整性
// ─────────────────────────────────────────────

/**
 * 条件是否填写完整。不完整的条件一律不参与匹配。
 *
 * 数值下界卡在 1：「未更新超过 0 天」会命中全部会话，这类空/零输入
 * 必须当成「还没填完」而不是「匹配一切」。
 */
fun ChatCondition.isComplete(): Boolean = when (type.kind) {
    ConditionValueKind.DAYS, ConditionValueKind.MEGABYTES -> number >= 1
    ConditionValueKind.TOOL -> ChatTool.entries.any { it.id == text }
    ConditionValueKind.TEXT -> text.isNotBlank()
}

/** 参与匹配的条件（滤掉没填完的）。 */
fun RetentionRule.effectiveConditions(): List<ChatCondition> = conditions.filter { it.isComplete() }

/** 这条规则是否真的会删东西：开着，且至少有一个填完的条件。 */
fun RetentionRule.isEffective(): Boolean = enabled && effectiveConditions().isNotEmpty()

// ─────────────────────────────────────────────
// 匹配
// ─────────────────────────────────────────────

/** 单个条件是否命中某会话。[nowMillis] 由调用方固定，保证一次判定内时间基准一致。 */
fun ChatCondition.matches(session: ChatSessionSummary, nowMillis: Long): Boolean = when (type) {
    ChatConditionType.UPDATED_BEFORE_DAYS ->
        session.updatedMillis < nowMillis - number * DAY_MILLIS

    ChatConditionType.UPDATED_WITHIN_DAYS ->
        session.updatedMillis > nowMillis - number * DAY_MILLIS

    ChatConditionType.SIZE_LARGER_MB ->
        session.sizeBytes > number * MB

    ChatConditionType.SIZE_SMALLER_MB ->
        session.sizeBytes < number * MB

    ChatConditionType.TOOL_IS ->
        session.tool.id == text

    ChatConditionType.PROJECT_CONTAINS ->
        projectLabel(session).contains(text, ignoreCase = true)

    ChatConditionType.TITLE_CONTAINS ->
        session.title.contains(text, ignoreCase = true)
}

/**
 * 规则是否命中某会话。
 *
 * 没有任何完整条件时一律不命中——「且」对空条件集在逻辑上为真，
 * 照搬会把所有会话判成待删。这个兜底必须在这里，不能指望 UI 拦住。
 */
fun RetentionRule.matches(session: ChatSessionSummary, nowMillis: Long): Boolean {
    if (!enabled) return false
    val effective = effectiveConditions()
    if (effective.isEmpty()) return false
    return when (join) {
        ConditionJoin.AND -> effective.all { it.matches(session, nowMillis) }
        ConditionJoin.OR -> effective.any { it.matches(session, nowMillis) }
    }
}

/** 配置里是否有任何一条规则真的会执行。 */
fun RetentionConfig.isActive(): Boolean = rules.any { it.isEffective() }

/** 按全部规则（规则间取「或」）筛出待删会话。 */
fun RetentionConfig.match(
    sessions: List<ChatSessionSummary>,
    nowMillis: Long,
): List<ChatSessionSummary> =
    sessions.filter { session -> rules.any { it.matches(session, nowMillis) } }

/** 生成一个不与现有规则冲突的 id。落盘只认 id，不认下标。 */
fun newRuleId(existing: Collection<String>): String {
    var i = 1
    while ("rule-$i" in existing) i++
    return "rule-$i"
}
