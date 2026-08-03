package dev.cxclear.chats

import dev.cxclear.profiles.homeDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * 自动清理策略落盘。
 *
 * 格式：`~/.cxclear/chat-retention.txt`，每行一个 `key=value`，容错解析——
 * 认不出的行直接跳过，宁可少读一条规则也不能让整份配置读失败后「什么都不删」变成「乱删」。
 *
 * 规则顺序由 `order=` 显式记录，不依赖文件里 key 的出现次序。
 * 值里的 `\`、换行、回车做转义，避免用户在文本条件里粘进换行把格式撑破。
 *
 * v1 只有单条 `enabled=` / `days=`，读到时迁成一条等价规则（[migrateV1]）。
 */
object RetentionStore {
    private const val FILE_NAME = "chat-retention.txt"
    private const val CURRENT_VERSION = 2

    /** 规则数与单规则条件数的上限：配置文件被写坏时不至于让 UI 撑爆。 */
    private const val MAX_RULES = 50
    private const val MAX_CONDITIONS = 20

    private fun file(): Path? =
        homeDir()?.resolve(".cxclear")?.resolve(FILE_NAME)

    // ─────────────────────────────────────────
    // 转义
    // ─────────────────────────────────────────

    private fun encode(raw: String): String = raw
        .replace("\\", "\\\\")
        .replace("\n", "\\n")
        .replace("\r", "\\r")

    private fun decode(raw: String): String {
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) {
                when (raw[i + 1]) {
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    else -> { sb.append(c); i++ }
                }
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    // ─────────────────────────────────────────
    // 读
    // ─────────────────────────────────────────

    fun read(): RetentionConfig {
        val path = file() ?: return RetentionConfig()
        if (!Files.exists(path)) return RetentionConfig()
        val props = runCatching {
            Files.readAllLines(path).mapNotNull { line ->
                val eq = line.indexOf('=')
                if (eq < 0) null else line.substring(0, eq).trim() to decode(line.substring(eq + 1).trim())
            }.toMap()
        }.getOrDefault(emptyMap())
        if (props.isEmpty()) return RetentionConfig()

        val version = props["version"]?.toIntOrNull() ?: 1
        if (version < 2) return migrateV1(props)

        val order = props["order"]
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            ?.take(MAX_RULES)
            ?: emptyList()

        return RetentionConfig(order.mapNotNull { id -> readRule(props, id) })
    }

    private fun readRule(props: Map<String, String>, id: String): RetentionRule? {
        val prefix = "rule.$id."
        // count 缺失说明这条规则没写全，跳过。
        val count = props["${prefix}count"]?.toIntOrNull()?.coerceIn(0, MAX_CONDITIONS) ?: return null
        val name = props["${prefix}name"] ?: ""
        val enabled = props["${prefix}enabled"]?.toBooleanStrictOrNull() ?: false
        val join = ConditionJoin.fromId(props["${prefix}join"] ?: "")

        val conditions = (0 until count).mapNotNull { i ->
            val typeId = props["${prefix}cond.$i.type"] ?: return@mapNotNull null
            val type = ChatConditionType.fromId(typeId) ?: return@mapNotNull null
            ChatCondition(
                type = type,
                number = props["${prefix}cond.$i.number"]?.toIntOrNull() ?: defaultNumberFor(type),
                text = props["${prefix}cond.$i.text"] ?: "",
            )
        }

        return RetentionRule(id = id, name = name, enabled = enabled, join = join, conditions = conditions)
    }

    /** v1 的 `enabled` / `days` 等价于一条「未更新超过 N 天」的规则。 */
    private fun migrateV1(props: Map<String, String>): RetentionConfig {
        val enabled = props["enabled"]?.toBooleanStrictOrNull() ?: false
        val days = props["days"]?.toIntOrNull()?.coerceIn(1, 3650) ?: 30
        return RetentionConfig(
            listOf(
                RetentionRule(
                    id = "rule-1",
                    name = "未更新超过 $days 天",
                    enabled = enabled,
                    join = ConditionJoin.AND,
                    conditions = listOf(
                        ChatCondition(ChatConditionType.UPDATED_BEFORE_DAYS, number = days),
                    ),
                )
            )
        )
    }

    // ─────────────────────────────────────────
    // 写
    // ─────────────────────────────────────────

    fun write(config: RetentionConfig) {
        val path = file() ?: return
        val rules = config.rules.take(MAX_RULES)
        val lines = mutableListOf(
            "version=$CURRENT_VERSION",
            "order=${rules.joinToString(",") { it.id }}",
        )
        for (rule in rules) {
            val prefix = "rule.${rule.id}."
            val conditions = rule.conditions.take(MAX_CONDITIONS)
            lines += "${prefix}name=${encode(rule.name)}"
            lines += "${prefix}enabled=${rule.enabled}"
            lines += "${prefix}join=${rule.join.id}"
            lines += "${prefix}count=${conditions.size}"
            conditions.forEachIndexed { i, cond ->
                lines += "${prefix}cond.$i.type=${cond.type.id}"
                lines += "${prefix}cond.$i.number=${cond.number}"
                lines += "${prefix}cond.$i.text=${encode(cond.text)}"
            }
        }
        runCatching {
            Files.createDirectories(path.parent)
            Files.write(
                path,
                lines,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
        }
    }
}
