package dev.cxclear.cli

import dev.cxclear.util.MiniJson
import kotlin.system.exitProcess

/** 给 AI 调的无头入口。统一查找-删除模型，stdout 只出 JSON。 */
object Cli {
    const val EXIT_OK = 0
    const val EXIT_FAIL = 1
    const val EXIT_BLOCKED = 2
    const val EXIT_USAGE = 3

    fun run(args: Array<String>): Int {
        return try {
            val parsed = parseArgs(args) ?: ParsedArgs(listOf("help"), emptyMap(), emptySet())
            dispatch(parsed)
        } catch (e: CliUsageException) {
            printError(e.message ?: "invalid arguments")
            EXIT_USAGE
        } catch (e: Exception) {
            printError(e.message ?: e::class.simpleName ?: "unknown error")
            EXIT_FAIL
        }
    }

    fun runAndExit(args: Array<String>): Nothing {
        exitProcess(run(args))
    }

    private fun dispatch(args: ParsedArgs): Int {
        val command = ALL_COMMANDS.firstOrNull { it.name == args.command }
        return if (command != null) {
            command.validate(args)
            command.execute(args)
        } else {
            printError("unknown command: ${args.command.joinToString(" ").ifBlank { "(empty)" }}")
            EXIT_USAGE
        }
    }

    fun printJson(value: Any?) {
        println(MiniJson.stringify(value))
    }

    fun printError(message: String) {
        System.err.println(message)
        printJson(mapOf("ok" to false, "error" to message))
    }
}
