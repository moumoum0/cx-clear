package dev.cxclear.chats

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 条件类型表与 id 生成的一致性。落盘只认 id，改动这些常量会让旧配置读不回来。 */
class RetentionRuleTextTest {
    @Test
    fun `new rule ids never collide with existing ones`() {
        assertEquals("rule-1", newRuleId(emptyList()))
        assertEquals("rule-3", newRuleId(listOf("rule-1", "rule-2")))
        // 删掉中间一条后要补空位，而不是撞上已有 id。
        assertEquals("rule-2", newRuleId(listOf("rule-1", "rule-3")))
    }

    @Test
    fun `condition type ids are unique and resolvable`() {
        val ids = ChatConditionType.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        ChatConditionType.entries.forEach {
            assertEquals(it, ChatConditionType.fromId(it.id))
        }
        assertEquals(null, ChatConditionType.fromId("nope"))
    }

    @Test
    fun `join falls back to and for unknown ids`() {
        // 配置文件被写坏时要落到更严的一侧：「且」比「或」少删。
        assertEquals(ConditionJoin.AND, ConditionJoin.fromId("garbage"))
        assertEquals(ConditionJoin.OR, ConditionJoin.fromId("or"))
    }

    @Test
    fun `numeric defaults are never zero`() {
        // 0 会让条件命中一切；新建条件必须给一个安全起点。
        ChatConditionType.entries
            .filter { it.kind == ConditionValueKind.DAYS || it.kind == ConditionValueKind.MEGABYTES }
            .forEach { assertTrue(defaultNumberFor(it) >= 1, "${it.id} default must be >= 1") }
    }

    @Test
    fun `new rules start disabled`() {
        // 建好即生效会在用户还没看清条件时就删东西。
        assertFalse(RetentionRule("rule-1").enabled)
    }
}
