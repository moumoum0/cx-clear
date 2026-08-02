package dev.cxclear.chats

/**
 * 只够用的 JSON 读取器：把一行 JSON 解析成 Map / List / String / Double / Boolean / null。
 *
 * 会话 jsonl 只需要取少量嵌套字段，为此引入完整序列化框架不划算；正则又扛不住
 * `message.content` 这种数组套对象的结构，所以在这里放一个手写解析器。
 * 解析失败一律返回 null，由调用方跳过该行——转录文件被写坏不该让整页崩掉。
 */
internal object MiniJson {
    fun parse(text: String): Any? = runCatching { Reader(text).parseRoot() }.getOrNull()

    private class Reader(private val s: String) {
        private var i = 0

        fun parseRoot(): Any? {
            val value = value()
            skipWs()
            return value
        }

        private fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun fail(): Nothing = throw IllegalArgumentException("invalid json at $i")

        private fun value(): Any? {
            skipWs()
            if (i >= s.length) fail()
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> number()
            }
        }

        private fun literal(token: String, value: Any?): Any? {
            if (!s.startsWith(token, i)) fail()
            i += token.length
            return value
        }

        private fun obj(): Map<String, Any?> {
            i++ // '{'
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (i < s.length && s[i] == '}') {
                i++
                return map
            }
            while (true) {
                skipWs()
                if (i >= s.length || s[i] != '"') fail()
                val key = string()
                skipWs()
                if (i >= s.length || s[i] != ':') fail()
                i++
                map[key] = value()
                skipWs()
                if (i >= s.length) fail()
                when (s[i]) {
                    ',' -> i++
                    '}' -> {
                        i++
                        return map
                    }
                    else -> fail()
                }
            }
        }

        private fun arr(): List<Any?> {
            i++ // '['
            val list = ArrayList<Any?>()
            skipWs()
            if (i < s.length && s[i] == ']') {
                i++
                return list
            }
            while (true) {
                list += value()
                skipWs()
                if (i >= s.length) fail()
                when (s[i]) {
                    ',' -> i++
                    ']' -> {
                        i++
                        return list
                    }
                    else -> fail()
                }
            }
        }

        private fun string(): String {
            i++ // '"'
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail()
                when (val c = s[i]) {
                    '"' -> {
                        i++
                        return sb.toString()
                    }
                    '\\' -> {
                        i++
                        if (i >= s.length) fail()
                        when (val esc = s[i]) {
                            '"', '\\', '/' -> sb.append(esc)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 >= s.length) fail()
                                val hex = s.substring(i + 1, i + 5)
                                sb.append(hex.toInt(16).toChar())
                                i += 4
                            }
                            else -> fail()
                        }
                        i++
                    }
                    else -> {
                        sb.append(c)
                        i++
                    }
                }
            }
        }

        private fun number(): Double {
            val start = i
            if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' ||
                    ((s[i] == '-' || s[i] == '+') && (s[i - 1] == 'e' || s[i - 1] == 'E')))
            ) i++
            if (i == start) fail()
            return s.substring(start, i).toDoubleOrNull() ?: fail()
        }
    }
}

@Suppress("UNCHECKED_CAST")
internal fun Any?.jsonObj(key: String): Map<String, Any?>? =
    (this as? Map<String, Any?>)?.get(key) as? Map<String, Any?>

@Suppress("UNCHECKED_CAST")
internal fun Any?.jsonArr(key: String): List<Any?>? =
    (this as? Map<String, Any?>)?.get(key) as? List<Any?>

@Suppress("UNCHECKED_CAST")
internal fun Any?.jsonStr(key: String): String? =
    (this as? Map<String, Any?>)?.get(key) as? String

@Suppress("UNCHECKED_CAST")
internal fun Any?.jsonBool(key: String): Boolean? =
    (this as? Map<String, Any?>)?.get(key) as? Boolean
