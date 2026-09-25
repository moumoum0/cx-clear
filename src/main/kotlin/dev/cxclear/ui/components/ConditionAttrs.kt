package dev.cxclear.ui.components

import dev.cxclear.chats.ChatConditionType
import dev.cxclear.chats.ConditionValueKind

/**
 * 条件构造器的属性元数据：属性 → 可用条件类型（比较符）与取值类型。
 * 纯逻辑无 Compose，供向导列与文案生成共用。
 */
internal data class AttrSpec(
    val label: String,
    val kind: ConditionValueKind,
    val larger: ChatConditionType? = null,
    val smaller: ChatConditionType? = null,
    val direct: ChatConditionType? = null,
) {
    val types: List<ChatConditionType> = listOfNotNull(larger, smaller, direct)
}

internal val ATTRS = listOf(
    AttrSpec("大小", ConditionValueKind.MEGABYTES,
        larger = ChatConditionType.SIZE_LARGER_MB, smaller = ChatConditionType.SIZE_SMALLER_MB),
    AttrSpec("未更新", ConditionValueKind.DAYS,
        larger = ChatConditionType.UPDATED_BEFORE_DAYS, smaller = ChatConditionType.UPDATED_WITHIN_DAYS),
    AttrSpec("所属工具", ConditionValueKind.TOOL, direct = ChatConditionType.TOOL_IS),
    AttrSpec("项目名", ConditionValueKind.TEXT, direct = ChatConditionType.PROJECT_CONTAINS),
    AttrSpec("标题", ConditionValueKind.TEXT, direct = ChatConditionType.TITLE_CONTAINS),
)

internal fun attrFor(type: ChatConditionType): AttrSpec = ATTRS.first { type in it.types }

internal fun presetsFor(kind: ConditionValueKind): List<Int> = when (kind) {
    ConditionValueKind.MEGABYTES -> listOf(1, 5, 10)
    ConditionValueKind.DAYS -> listOf(7, 30, 90)
    else -> emptyList()
}

internal val AttrSpec.isNumeric: Boolean
    get() = kind == ConditionValueKind.MEGABYTES || kind == ConditionValueKind.DAYS

internal fun AttrSpec.typeFor(larger: Boolean?): ChatConditionType =
    if (isNumeric) (if (larger == true) this.larger!! else smaller!!) else direct!!
