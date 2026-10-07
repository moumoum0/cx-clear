package dev.cxclear.chats

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RetentionRuleTextTest {
    @Test
    fun `new rule ids never collide with existing ones`() {
        assertEquals("rule-1", newRuleId(emptyList()))
        assertEquals("rule-3", newRuleId(listOf("rule-1", "rule-2")))
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
        assertEquals(ConditionJoin.AND, ConditionJoin.fromId("garbage"))
        assertEquals(ConditionJoin.OR, ConditionJoin.fromId("or"))
    }

    @Test
    fun `numeric defaults are never zero`() {
        ChatConditionType.entries
            .filter { it.kind == ConditionValueKind.DAYS || it.kind == ConditionValueKind.MEGABYTES }
            .forEach { assertTrue(defaultNumberFor(it) >= 1, "${it.id} default must be >= 1") }
    }

    @Test
    fun `new rules start disabled`() {
        assertFalse(RetentionRule("rule-1").enabled)
    }
}
