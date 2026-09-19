package dev.cxclear.chats

/** 策略 JSON 合同。落盘仍走 [RetentionStore] 的 kv 文件，命令行不让 AI 手搓 txt。 */
object RetentionJson {
    const val VERSION = 2
    const val MAX_RULES = 50
    const val MAX_CONDITIONS = 20

    fun toMap(config: RetentionConfig): Map<String, Any?> = mapOf(
        "version" to VERSION,
        "rules" to config.rules.map { rule ->
            mapOf(
                "id" to rule.id,
                "name" to rule.name,
                "enabled" to rule.enabled,
                "join" to rule.join.id,
                "conditions" to rule.conditions.map { cond ->
                    mapOf(
                        "type" to cond.type.id,
                        "number" to cond.number,
                        "text" to cond.text,
                    )
                },
            )
        },
    )

    fun stringify(config: RetentionConfig): String = MiniJson.stringify(toMap(config))

    fun parse(text: String): RetentionParseResult {
        val root = MiniJson.parse(text)?.jsonMap()
            ?: return RetentionParseResult.Fail(listOf("不是合法 JSON 对象"))
        return parseRoot(root)
    }

    fun parseRoot(root: Map<String, Any?>): RetentionParseResult {
        val errors = mutableListOf<String>()
        val version = root.jsonInt("version") ?: VERSION
        if (version != VERSION) {
            errors += "version 只支持 $VERSION"
        }

        val rulesRaw = root["rules"]
        if (rulesRaw == null) {
            errors += "缺少 rules 数组"
            return RetentionParseResult.Fail(errors)
        }
        val rulesList = rulesRaw.jsonList()
        if (rulesList == null) {
            errors += "rules 必须是数组"
            return RetentionParseResult.Fail(errors)
        }
        if (rulesList.size > MAX_RULES) {
            errors += "规则最多 $MAX_RULES 条"
        }

        val seenIds = mutableSetOf<String>()
        val rules = rulesList.take(MAX_RULES).mapIndexedNotNull { index, item ->
            val obj = item.jsonMap()
            if (obj == null) {
                errors += "rules[$index] 必须是对象"
                return@mapIndexedNotNull null
            }
            parseRule(obj, index, seenIds, errors)
        }

        if (errors.isNotEmpty()) return RetentionParseResult.Fail(errors)
        return RetentionParseResult.Ok(RetentionConfig(rules))
    }

    private fun parseRule(
        obj: Map<String, Any?>,
        index: Int,
        seenIds: MutableSet<String>,
        errors: MutableList<String>,
    ): RetentionRule? {
        val prefix = "rules[$index]"
        val id = obj.jsonStr("id")?.trim().orEmpty()
        if (id.isEmpty()) {
            errors += "$prefix 缺少 id"
        } else if (!seenIds.add(id)) {
            errors += "$prefix id 重复：$id"
        }

        val name = obj.jsonStr("name") ?: ""
        val enabled = obj.jsonBool("enabled") ?: false
        val joinRaw = obj.jsonStr("join") ?: "and"
        val join = when (joinRaw) {
            "and" -> ConditionJoin.AND
            "or" -> ConditionJoin.OR
            else -> {
                errors += "$prefix.join 只能是 and 或 or"
                ConditionJoin.AND
            }
        }

        val condsRaw = obj["conditions"]
        val condList = when (condsRaw) {
            null -> emptyList()
            else -> {
                val list = condsRaw.jsonList()
                if (list == null) {
                    errors += "$prefix.conditions 必须是数组"
                    emptyList()
                } else {
                    if (list.size > MAX_CONDITIONS) {
                        errors += "$prefix 条件最多 $MAX_CONDITIONS 个"
                    }
                    list.take(MAX_CONDITIONS).mapIndexedNotNull { ci, item ->
                        parseCondition(item, "$prefix.conditions[$ci]", errors)
                    }
                }
            }
        }

        if (id.isEmpty()) return null
        return RetentionRule(id = id, name = name, enabled = enabled, join = join, conditions = condList)
    }

    private fun parseCondition(
        item: Any?,
        prefix: String,
        errors: MutableList<String>,
    ): ChatCondition? {
        val obj = item.jsonMap()
        if (obj == null) {
            errors += "$prefix 必须是对象"
            return null
        }
        val typeId = obj.jsonStr("type")?.trim().orEmpty()
        val type = ChatConditionType.fromId(typeId)
        if (type == null) {
            errors += "$prefix.type 无法识别：$typeId"
            return null
        }
        val number = if ("number" in obj) {
            obj.jsonInt("number") ?: run {
                errors += "$prefix.number 必须是整数"
                return null
            }
        } else {
            defaultNumberFor(type)
        }
        val text = obj.jsonStr("text") ?: ""
        return ChatCondition(type = type, number = number, text = text)
    }
}

sealed interface RetentionParseResult {
    data class Ok(val config: RetentionConfig) : RetentionParseResult
    data class Fail(val errors: List<String>) : RetentionParseResult
}
