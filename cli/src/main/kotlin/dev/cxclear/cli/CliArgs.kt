package dev.cxclear.cli

internal class CliUsageException(message: String) : RuntimeException(message)

internal data class ParsedArgs(
    val command: List<String>,
    val flags: Map<String, List<String>>,
    val switches: Set<String>,
) {
    val yes: Boolean get() = "yes" in switches || "y" in switches
    val preview: Boolean get() = "preview" in switches
    val json: Boolean get() = "json" in switches
    val safeOnly: Boolean get() = "safe-only" in switches
    val rules: Boolean get() = "rules" in switches
    val isPreview: Boolean get() = preview || !yes

    fun value(name: String): String? = flags[name]?.last()

    fun values(name: String): List<String> = flags[name].orEmpty()

    fun csv(name: String): List<String> =
        values(name).flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
}

internal val SWITCH_OPTIONS = setOf("yes", "y", "preview", "json", "safe-only", "rules")
internal val VALUE_OPTIONS = setOf(
    "tool", "type", "older-than", "newer-than", "size-gt", "size-lt",
    "keep-recent", "keep-days",
    "file", "rules-file", "rule", "targets", "limit",
)

internal fun parseArgs(args: Array<String>): ParsedArgs? {
    if (args.isEmpty()) return null
    val command = mutableListOf<String>()
    val flags = linkedMapOf<String, MutableList<String>>()
    val switches = linkedSetOf<String>()
    var i = 0
    fun takeValue(flag: String): String {
        if (i >= args.size) throw CliUsageException("missing value for $flag")
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
            token == "-v" || token == "--version" -> return ParsedArgs(listOf("version"), emptyMap(), emptySet())
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
                if (name.isEmpty()) throw CliUsageException("empty option")
                when (name) {
                    in SWITCH_OPTIONS -> {
                        if (value != null) throw CliUsageException("--$name does not take a value")
                        switches += name
                    }
                    in VALUE_OPTIONS -> {
                        val v = value ?: takeValue("--$name")
                        flags.getOrPut(name) { mutableListOf() }.add(v)
                    }
                    else -> throw CliUsageException("unknown option: --$name")
                }
            }
            token.startsWith("-") && token != "-" -> throw CliUsageException("unknown option: $token")
            else -> command += token
        }
    }
    if (command.isEmpty()) return ParsedArgs(listOf("help"), flags, switches)
    return ParsedArgs(command, flags, switches)
}
