package dev.cxclear.chats

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 自动清理匹配引擎的判定测试。
 *
 * 这里的每条断言都对应「会不会误删」：匹配引擎多命中一条，就是用户少一份不可恢复的会话记录，
 * 所以空条件、零值、未开启这些中间态必须逐个钉死。
 */
class RetentionMatchTest {
    private val now = 1_700_000_000_000L
    private val day = 86_400_000L

    private fun session(
        id: String,
        tool: ChatTool = ChatTool.CODEX,
        title: String = "会话 $id",
        project: String? = "demo",
        agoDays: Long = 0L,
        sizeBytes: Long = 1024L,
    ) = ChatSessionSummary(
        tool = tool,
        id = id,
        title = title,
        project = project,
        updatedMillis = now - agoDays * day,
        sizeBytes = sizeBytes,
        mainFile = Paths.get("x", "$id.jsonl"),
        rootDir = Paths.get("x"),
        entries = emptyList(),
    )

    private fun rule(
        vararg conditions: ChatCondition,
        join: ConditionJoin = ConditionJoin.AND,
        enabled: Boolean = true,
    ) = RetentionRule("rule-1", enabled = enabled, join = join, conditions = conditions.toList())

    // ─────────────────────────────────────────
    // 安全兜底：不该命中的中间态
    // ─────────────────────────────────────────

    @Test
    fun `rule with no conditions never matches`() {
        // AND 对空集合在逻辑上为真，照搬会把全部会话判成待删。
        val fresh = session("a")
        assertFalse(rule().matches(fresh, now))
        assertFalse(rule(join = ConditionJoin.OR).matches(fresh, now))
    }

    @Test
    fun `disabled rule never matches`() {
        val old = session("a", agoDays = 999L)
        val r = rule(ChatCondition(ChatConditionType.UPDATED_BEFORE_DAYS, number = 30), enabled = false)
        assertFalse(r.matches(old, now))
    }

    @Test
    fun `zero valued numeric condition is treated as incomplete`() {
        // 「未更新超过 0 天」会命中一切，必须当成还没填完。
        val cond = ChatCondition(ChatConditionType.UPDATED_BEFORE_DAYS, number = 0)
        assertFalse(cond.isComplete())
        assertFalse(rule(cond).matches(session("a", agoDays = 999L), now))
    }

    @Test
    fun `blank text condition is treated as incomplete`() {
        // 空串 contains 恒为真，同样不能参与匹配。
        val cond = ChatCondition(ChatConditionType.TITLE_CONTAINS, text = "  ")
        assertFalse(cond.isComplete())
        assertFalse(rule(cond).matches(session("a"), now))
    }

    @Test
    fun `unknown tool id is treated as incomplete`() {
        val cond = ChatCondition(ChatConditionType.TOOL_IS, text = "cursor")
        assertFalse(cond.isComplete())
        assertFalse(rule(cond).matches(session("a"), now))
    }

    @Test
    fun `incomplete conditions are dropped but complete ones still apply`() {
        val r = rule(
            ChatCondition(ChatConditionType.UPDATED_BEFORE_DAYS, number = 30),
            ChatCondition(ChatConditionType.TITLE_CONTAINS, text = ""),
        )
        // 只剩「超过 30 天」一个有效条件，不应因为另一个填了空就整条失效。
        assertTrue(r.matches(session("a", agoDays = 60L), now))
        assertFalse(r.matches(session("b", agoDays = 5L), now))
    }

    // ─────────────────────────────────────────
    // 各条件类型
    // ─────────────────────────────────────────

    @Test
    fun `updated before days uses strict threshold`() {
        val r = rule(ChatCondition(ChatConditionType.UPDATED_BEFORE_DAYS, number = 30))
        assertTrue(r.matches(session("old", agoDays = 31L), now))
        // 正好 30 天不算「超过 30 天」。
        assertFalse(r.matches(session("edge", agoDays = 30L), now))
        assertFalse(r.matches(session("fresh", agoDays = 1L), now))
    }

    @Test
    fun `updated within days is the strict complement of before`() {
        // 「未更新少于 30 天」= 最近 30 天内动过。与「超过」互补，边界都归到「少于」一侧。
        val r = rule(ChatCondition(ChatConditionType.UPDATED_WITHIN_DAYS, number = 30))
        assertTrue(r.matches(session("fresh", agoDays = 1L), now))
        // 两侧都用严格比较：正好 30 天既不「超过」也不「少于」，落在边界上谁都不命中。
        assertFalse(r.matches(session("edge", agoDays = 30L), now))
        assertFalse(r.matches(session("old", agoDays = 31L), now))
    }

    @Test
    fun `size larger than compares against megabytes`() {
        val r = rule(ChatCondition(ChatConditionType.SIZE_LARGER_MB, number = 10))
        val mb = 1024L * 1024L
        assertTrue(r.matches(session("big", sizeBytes = 11 * mb), now))
        assertFalse(r.matches(session("edge", sizeBytes = 10 * mb), now))
        assertFalse(r.matches(session("small", sizeBytes = 1024L), now))
    }

    @Test
    fun `size smaller than compares against megabytes`() {
        val r = rule(ChatCondition(ChatConditionType.SIZE_SMALLER_MB, number = 10))
        val mb = 1024L * 1024L
        assertTrue(r.matches(session("small", sizeBytes = 1024L), now))
        // 两侧都用严格比较：正好 10 MB 落在边界上，「超过」「少于」都不命中。
        assertFalse(r.matches(session("edge", sizeBytes = 10 * mb), now))
        assertFalse(r.matches(session("big", sizeBytes = 11 * mb), now))
    }

    @Test
    fun `tool condition matches by tool id`() {
        val r = rule(ChatCondition(ChatConditionType.TOOL_IS, text = ChatTool.CLAUDE.id))
        assertTrue(r.matches(session("a", tool = ChatTool.CLAUDE), now))
        assertFalse(r.matches(session("b", tool = ChatTool.CODEX), now))
    }

    @Test
    fun `text conditions ignore case`() {
        val titleRule = rule(ChatCondition(ChatConditionType.TITLE_CONTAINS, text = "REFACTOR"))
        assertTrue(titleRule.matches(session("a", title = "refactor scanner"), now))

        val projectRule = rule(ChatCondition(ChatConditionType.PROJECT_CONTAINS, text = "CX"))
        assertTrue(projectRule.matches(session("b", project = "cxclear"), now))
    }

    @Test
    fun `project condition matches the displayed label`() {
        // Claude 的目录名是整条路径编码，projectLabel 取末段；条件应按展示值匹配。
        val r = rule(ChatCondition(ChatConditionType.PROJECT_CONTAINS, text = "cxclear"))
        assertTrue(r.matches(session("a", tool = ChatTool.CLAUDE, project = "d--project-cxclear"), now))
    }

    // ─────────────────────────────────────────
    // 与 / 或 组合
    // ─────────────────────────────────────────

    @Test
    fun `and requires every condition`() {
        val r = rule(
            ChatCondition(ChatConditionType.UPDATED_BEFORE_DAYS, number = 30),
            ChatCondition(ChatConditionType.SIZE_LARGER_MB, number = 10),
            join = ConditionJoin.AND,
        )
        val mb = 1024L * 1024L
        assertTrue(r.matches(session("both", agoDays = 60L, sizeBytes = 20 * mb), now))
        assertFalse(r.matches(session("oldOnly", agoDays = 60L, sizeBytes = 1024L), now))
        assertFalse(r.matches(session("bigOnly", agoDays = 1L, sizeBytes = 20 * mb), now))
    }

    @Test
    fun `or accepts any condition`() {
        val r = rule(
            ChatCondition(ChatConditionType.UPDATED_BEFORE_DAYS, number = 30),
            ChatCondition(ChatConditionType.SIZE_LARGER_MB, number = 10),
            join = ConditionJoin.OR,
        )
        val mb = 1024L * 1024L
        assertTrue(r.matches(session("oldOnly", agoDays = 60L, sizeBytes = 1024L), now))
        assertTrue(r.matches(session("bigOnly", agoDays = 1L, sizeBytes = 20 * mb), now))
        assertFalse(r.matches(session("neither", agoDays = 1L, sizeBytes = 1024L), now))
    }

    // ─────────────────────────────────────────
    // 多策略：规则之间取「或」
    // ─────────────────────────────────────────

    @Test
    fun `config unions matches across rules`() {
        val byAge = RetentionRule(
            "rule-1", enabled = true, join = ConditionJoin.AND,
            conditions = listOf(ChatCondition(ChatConditionType.UPDATED_BEFORE_DAYS, number = 30)),
        )
        val byTool = RetentionRule(
            "rule-2", enabled = true, join = ConditionJoin.AND,
            conditions = listOf(ChatCondition(ChatConditionType.TOOL_IS, text = ChatTool.CLAUDE.id)),
        )
        val sessions = listOf(
            session("old", agoDays = 60L),
            session("claude", tool = ChatTool.CLAUDE, agoDays = 1L),
            session("keep", agoDays = 1L),
        )
        val matched = RetentionConfig(listOf(byAge, byTool)).match(sessions, now).map { it.id }
        assertEquals(listOf("old", "claude"), matched)
    }

    @Test
    fun `disabled rules do not contribute to config matches`() {
        val off = RetentionRule(
            "rule-1", enabled = false, join = ConditionJoin.AND,
            conditions = listOf(ChatCondition(ChatConditionType.UPDATED_BEFORE_DAYS, number = 30)),
        )
        val config = RetentionConfig(listOf(off))
        assertFalse(config.isActive())
        assertTrue(config.match(listOf(session("old", agoDays = 60L)), now).isEmpty())
    }

    @Test
    fun `config with only incomplete rules is inactive`() {
        val hollow = RetentionRule(
            "rule-1", enabled = true, join = ConditionJoin.AND,
            conditions = listOf(ChatCondition(ChatConditionType.TITLE_CONTAINS, text = "")),
        )
        val config = RetentionConfig(listOf(hollow))
        assertFalse(config.isActive())
        assertTrue(config.match(listOf(session("a")), now).isEmpty())
    }

    @Test
    fun `config keeps sessions matched by no rule`() {
        val r = RetentionRule(
            "rule-1", enabled = true, join = ConditionJoin.AND,
            conditions = listOf(ChatCondition(ChatConditionType.UPDATED_BEFORE_DAYS, number = 30)),
        )
        val sessions = listOf(session("old", agoDays = 60L), session("fresh", agoDays = 1L))
        assertEquals(listOf("old"), RetentionConfig(listOf(r)).match(sessions, now).map { it.id })
    }

    @Test
    fun `empty config matches nothing`() {
        assertTrue(RetentionConfig().match(listOf(session("a", agoDays = 999L)), now).isEmpty())
        assertFalse(RetentionConfig().isActive())
    }
}
