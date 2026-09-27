package dev.cxclear.cli.commands

import dev.cxclear.cli.Cli
import dev.cxclear.cli.CliSchema
import dev.cxclear.cli.Command
import dev.cxclear.cli.ParsedArgs

internal object SchemaCommand : Command {
    override val name = listOf("schema")
    override val description = "打印机器可读的命令与字段说明"

    override fun execute(args: ParsedArgs): Int {
        Cli.printJson(CliSchema.schemaPayload())
        return Cli.EXIT_OK
    }
}
