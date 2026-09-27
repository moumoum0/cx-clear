package dev.cxclear.ui.components

import dev.cxclear.chats.ChatCondition
import dev.cxclear.chats.ChatConditionType
import dev.cxclear.chats.ConditionJoin

/**
 * 条件编辑向导的草稿态：已确认的条件、当前的连接词，以及正在选的属性/比较符。
 * 纯逻辑无 Compose，附带从已有条件反推草稿的入口。
 */
internal data class Draft(
    val committed: List<ChatCondition> = emptyList(),
    val join: ConditionJoin = ConditionJoin.AND,
    val attr: AttrSpec? = null,
    val larger: Boolean? = null,
    val showCombine: Boolean = false,
) {
    // showCombine 时末尾是正在改的那条，算占用会把自己选项弄没。
    val usedTypes: List<ChatConditionType>
        get() = (if (showCombine) committed.dropLast(1) else committed).map { it.type }

    val allTypes: List<ChatConditionType> get() = committed.map { it.type }
}

internal fun pathOf(condition: ChatCondition): Pair<AttrSpec, Boolean?> {
    val attr = attrFor(condition.type)
    return attr to if (attr.isNumeric) condition.type == attr.larger else null
}

internal fun draftFromExisting(conditions: List<ChatCondition>, join: ConditionJoin): Draft {
    if (conditions.isEmpty()) return Draft(join = join)
    val last = conditions.last()
    val (attr, larger) = pathOf(last)
    return Draft(
        committed = conditions,
        join = join,
        attr = attr,
        larger = larger,
        showCombine = true,
    )
}
