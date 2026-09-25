package dev.cxclear.cli

/**
 * 命令行参数解析。
 *
 * 单独成文件是因为它是 Cli.kt 里唯一被测试直接覆盖的部分（CliArgsTest），
 * 且不依赖 Cli object 的任何状态。
 */
internal class CliUsageException(message: String) : RuntimeException(message)

internal data class ParsedArgs(
    val command: List<String>,
    val flags: Map<String, List<String>>,
    val switches: Set<String>,
) {
    val yes: Boolean get() = "yes" in switches || "y" in switches

    fun value(name: String): String? = flags[name]?.last()

    fun values(name: String): List<String> = flags[name].orEmpty()
}

internal fun parseArgs(args: Array<String>): ParsedArgs? {
    if (args.isEmpty()) return null
    val command = mutableListOf<String>()
    val flags = linkedMapOf<String, MutableList<String>>()
    val switches = linkedSetOf<String>()
    var i = 0
    fun takeValue(flag: String): String {
        if (i >= args.size) throw CliUsageException("缺少 $flag 的值")
        return args[i++]
    }
    while (i < args.size) {
        val token = args[i++]
        when {
            token == "--" -> {
                command += args.drop(i)
                break
            }
            token == "-h" || token == "--help" -> return ParsedArgs(listOf("help"), emptyMap(), emptySet())
            token.startsWith("--") -> {
                val eq = token.indexOf('=')
                val name: String
                val value: String?
                if (eq > 2) {
                    name = token.substring(2, eq)
                    value = token.substring(eq + 1)
                } else {
                    name = token.substring(2)
                    value = null
                }
                if (name.isEmpty()) throw CliUsageException("空的选项")
                when (name) {
                    "yes", "y" -> switches += name
                    "tool", "risk", "id", "file" -> {
                        val v = value ?: takeValue("--$name")
                        flags.getOrPut(name) { mutableListOf() }.add(v)
                    }
                    else -> throw CliUsageException("未知选项：--$name")
                }
            }
            token.startsWith("-") && token != "-" -> throw CliUsageException("未知选项：$token")
            else -> command += token
        }
    }
    if (command.isEmpty()) return ParsedArgs(listOf("help"), flags, switches)
    return ParsedArgs(command, flags, switches)
}
