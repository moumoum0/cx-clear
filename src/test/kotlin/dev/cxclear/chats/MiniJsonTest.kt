package dev.cxclear.chats

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 手写 JSON 解析器的行为。
 *
 * 会话标题、项目名都从这里取值；解析错一个字段，UI 上就是一条张冠李戴的会话，
 * 而用户是照着标题决定删哪条的。解析失败必须返回 null 让调用方跳过该行，绝不能抛。
 */
class MiniJsonTest {
    @Test
    fun `parses nested objects and arrays`() {
        val json = """{"type":"user","message":{"content":[{"type":"text","text":"你好"}]}}"""
        val root = MiniJson.parse(json)

        assertEquals("user", root.jsonStr("type"))
        val content = root.jsonObj("message").jsonArr("content")
        assertEquals(1, content?.size)
        assertEquals("你好", content?.get(0).jsonStr("text"))
    }

    @Test
    fun `decodes escapes including unicode`() {
        val root = MiniJson.parse("""{"t":"a\"b\\c\nd\te你"}""")

        assertEquals("a\"b\\c\nd\te你", root.jsonStr("t"))
    }

    @Test
    fun `reads booleans nulls and numbers`() {
        val root = MiniJson.parse("""{"isMeta":true,"off":false,"none":null,"n":-1.5e2}""")

        assertEquals(true, root.jsonBool("isMeta"))
        assertEquals(false, root.jsonBool("off"))
        assertNull(root.jsonStr("none"))
        assertEquals(-150.0, (root as Map<*, *>)["n"])
    }

    @Test
    fun `handles empty containers and whitespace`() {
        assertEquals(emptyMap<String, Any?>(), MiniJson.parse("  { }  "))
        assertEquals(emptyList<Any?>(), MiniJson.parse("[]"))
        assertEquals(emptyList<Any?>(), MiniJson.parse("""{"a":[]}""").jsonArr("a"))
    }

    @Test
    fun `later duplicate key wins`() {
        // 转录文件是追加写的，同名字段以最后一次为准更符合「最新状态」。
        assertEquals("second", MiniJson.parse("""{"k":"first","k":"second"}""").jsonStr("k"))
    }

    @Test
    fun `malformed input returns null instead of throwing`() {
        // 转录被写坏（进程被 kill 留下半行）不该让整页崩掉。
        val broken = listOf(
            "",
            "   ",
            "not json",
            "{",
            """{"a"}""",
            """{"a":}""",
            """{"a":1,}""",
            """{"a":"unterminated""",
            "[1,2",
            """{"a":tru}""",
            """{"a":"\q"}""",
        )
        for (line in broken) {
            assertNull(MiniJson.parse(line), "应解析失败：$line")
        }
    }

    @Test
    fun `typed accessors return null on kind mismatch`() {
        val root = MiniJson.parse("""{"s":"text","o":{},"a":[]}""")

        assertNull(root.jsonObj("s"))
        assertNull(root.jsonArr("s"))
        assertNull(root.jsonStr("o"))
        assertNull(root.jsonBool("s"))
        assertNull(root.jsonStr("missing"))
    }

    @Test
    fun `accessors on non object values are null safe`() {
        assertNull(null.jsonStr("k"))
        assertNull(MiniJson.parse("[1]").jsonStr("k"))
    }

    @Test
    fun `parses a realistic long transcript line`() {
        val json = """{"type":"response_item","payload":{"type":"message","role":"assistant",""" +
            """"content":[{"type":"output_text","text":"line1\nline2"}]}}"""
        val payload = MiniJson.parse(json).jsonObj("payload")

        assertEquals("assistant", payload.jsonStr("role"))
        assertTrue(payload.jsonArr("content")?.get(0).jsonStr("text")!!.contains("\n"))
    }
}
