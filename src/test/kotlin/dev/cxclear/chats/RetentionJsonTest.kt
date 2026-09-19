package dev.cxclear.chats

import dev.cxclear.storage.AppDir
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RetentionJsonTest {
    @Test
    fun `round trip keeps rule fields`() {
        val config = RetentionConfig(
            listOf(
                RetentionRule(
                    id = "rule-1",
                    name = "Codex 过期",
                    enabled = true,
                    join = ConditionJoin.AND,
                    conditions = listOf(
                        ChatCondition(ChatConditionType.UPDATED_BEFORE_DAYS, number = 90),
                        ChatCondition(ChatConditionType.TOOL_IS, text = "codex"),
                    ),
                ),
                RetentionRule(
                    id = "rule-2",
                    name = "标题含临时",
                    enabled = false,
                    join = ConditionJoin.OR,
                    conditions = listOf(
                        ChatCondition(ChatConditionType.TITLE_CONTAINS, text = "临时"),
                    ),
                ),
            )
        )

        val parsed = RetentionJson.parse(RetentionJson.stringify(config))
        val ok = assertIs<RetentionParseResult.Ok>(parsed)
        assertEquals(config, ok.config)
    }

    @Test
    fun `duplicate ids and unknown types fail`() {
        val json = """
            {"version":2,"rules":[
              {"id":"rule-1","name":"a","enabled":false,"join":"and","conditions":[]},
              {"id":"rule-1","name":"b","enabled":false,"join":"and","conditions":[{"type":"nope","number":1,"text":""}]}
            ]}
        """.trimIndent()
        val parsed = RetentionJson.parse(json)
        val fail = assertIs<RetentionParseResult.Fail>(parsed)
        assertTrue(fail.errors.any { it.contains("重复") })
        assertTrue(fail.errors.any { it.contains("无法识别") })
    }

    @Test
    fun `malformed json fails without throwing`() {
        val parsed = RetentionJson.parse("{")
        assertIs<RetentionParseResult.Fail>(parsed)
    }

    @Test
    fun `store write then json get keep the same rules`() {
        val dir = Files.createTempDirectory("cxclear-rules-json")
        try {
            AppDir.overrideForTest(dir)
            val config = RetentionConfig(
                listOf(
                    RetentionRule(
                        id = "rule-1",
                        name = "过期",
                        enabled = true,
                        conditions = listOf(
                            ChatCondition(ChatConditionType.UPDATED_BEFORE_DAYS, number = 14),
                        ),
                    )
                )
            )
            RetentionStore.write(config)
            val parsed = RetentionJson.parse(RetentionJson.stringify(RetentionStore.read()))
            val ok = assertIs<RetentionParseResult.Ok>(parsed)
            assertEquals(config, ok.config)
        } finally {
            AppDir.overrideForTest(null)
            dir.toFile().deleteRecursively()
        }
    }
}
