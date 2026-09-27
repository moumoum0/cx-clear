package dev.cxclear.cli.commands

import dev.cxclear.chats.RetentionJson
import dev.cxclear.chats.RetentionParseResult
import dev.cxclear.chats.RetentionStore
import dev.cxclear.cli.Cli
import dev.cxclear.cli.Command
import dev.cxclear.cli.ParsedArgs
import dev.cxclear.cli.readConfigJson
import dev.cxclear.cli.ruleWarnings

internal object RulesPutCommand : Command {
    override val name = listOf("rules", "put")
    override val description = "写入自动清理策略"
    override val flags = setOf("file")
    override val switches = setOf("json", "yes", "y", "preview")

    override fun execute(args: ParsedArgs): Int {
        val parsed = readConfigJson(args)
        if (parsed is RetentionParseResult.Fail) {
            Cli.printJson(mapOf("ok" to false, "errors" to parsed.errors))
            return Cli.EXIT_USAGE
        }
        val config = (parsed as RetentionParseResult.Ok).config
        if (args.preview || !args.yes) {
            Cli.printJson(
                mapOf(
                    "ok" to true,
                    "preview" to true,
                    "config" to RetentionJson.toMap(config),
                    "warnings" to ruleWarnings(config),
                )
            )
            return Cli.EXIT_OK
        }
        RetentionStore.write(config)
        Cli.printJson(
            mapOf(
                "ok" to true,
                "preview" to false,
                "config" to RetentionJson.toMap(RetentionStore.read()),
                "warnings" to ruleWarnings(config),
            )
        )
        return Cli.EXIT_OK
    }
}
