package dev.cxclear.cli

import dev.cxclear.cli.commands.CleanCommand
import dev.cxclear.cli.commands.DeleteChatsCommand
import dev.cxclear.cli.commands.DeleteFilesCommand
import dev.cxclear.cli.commands.FindChatsCommand
import dev.cxclear.cli.commands.FindFilesCommand
import dev.cxclear.cli.commands.HelpCommand
import dev.cxclear.cli.commands.HistoryCommand
import dev.cxclear.cli.commands.RulesGetCommand
import dev.cxclear.cli.commands.RulesValidateCommand
import dev.cxclear.cli.commands.ScanCommand
import dev.cxclear.cli.commands.SchemaCommand
import dev.cxclear.cli.commands.StatusCommand

internal interface Command {
    val name: List<String>
    val description: String
    val flags: Set<String> get() = emptySet()
    val switches: Set<String> get() = setOf("json")
    fun execute(args: ParsedArgs): Int

    fun validate(args: ParsedArgs) {
        val unsupported = (args.flags.keys - flags) + (args.switches - switches)
        if (unsupported.isNotEmpty()) {
            throw CliUsageException("${name.joinToString(" ")} 不支持 --${unsupported.first()}")
        }
    }
}

internal val ALL_COMMANDS: List<Command> = listOf(
    HelpCommand,
    SchemaCommand,
    FindChatsCommand,
    FindFilesCommand,
    DeleteChatsCommand,
    DeleteFilesCommand,
    ScanCommand,
    CleanCommand,
    StatusCommand,
    HistoryCommand,
    RulesGetCommand,
    RulesValidateCommand,
)
