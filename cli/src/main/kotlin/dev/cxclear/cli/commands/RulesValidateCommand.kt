package dev.cxclear.cli.commands

import dev.cxclear.chats.RetentionJson
import dev.cxclear.chats.RetentionParseResult
import dev.cxclear.cli.Cli
import dev.cxclear.cli.Command
import dev.cxclear.cli.ParsedArgs
import dev.cxclear.cli.readConfigJson
import dev.cxclear.cli.ruleWarnings

internal object RulesValidateCommand : Command {
    override val name = listOf("rules", "validate")
    override val description = "校验策略 JSON，不写入"
    override val flags = setOf("file")

    override fun execute(args: ParsedArgs): Int {
        val parsed = readConfigJson(args)
        return when (parsed) {
            is RetentionParseResult.Fail -> {
                Cli.printJson(mapOf("ok" to false, "errors" to parsed.errors))
                Cli.EXIT_USAGE
            }
            is RetentionParseResult.Ok -> {
                Cli.printJson(
                    mapOf(
                        "ok" to true,
                        "config" to RetentionJson.toMap(parsed.config),
                        "warnings" to ruleWarnings(parsed.config),
                    )
                )
                Cli.EXIT_OK
            }
        }
    }
}
