package dev.cxclear.cli.commands

import dev.cxclear.cli.AUTOCLEAN_TASK_NAME
import dev.cxclear.cli.Cli
import dev.cxclear.cli.Command
import dev.cxclear.cli.ParsedArgs
import dev.cxclear.cli.autocleanTaskInstalled
import dev.cxclear.cli.runSchtasks
import dev.cxclear.platform.HostOs
import dev.cxclear.platform.currentOs
import dev.cxclear.storage.AppPreferences

internal object AutocleanOffCommand : Command {
    override val name = listOf("autoclean", "off")
    override val description = "remove the Windows logon auto-clean task"

    override fun execute(args: ParsedArgs): Int {
        var removed = false
        if (currentOs() == HostOs.WINDOWS) {
            val result = runSchtasks("/delete", "/tn", AUTOCLEAN_TASK_NAME, "/f")
            if (result.exitCode != 0 && autocleanTaskInstalled()) {
                Cli.printJson(
                    mapOf(
                        "ok" to false,
                        "error" to result.output.trim().ifEmpty { "schtasks exited ${result.exitCode}" },
                    )
                )
                return Cli.EXIT_FAIL
            }
            removed = result.exitCode == 0
        }
        AppPreferences.update { it.copy(autoCleanEnabled = false) }
        Cli.printJson(
            mapOf(
                "ok" to true,
                "command" to "autoclean off",
                "task_removed" to removed,
            )
        )
        return Cli.EXIT_OK
    }
}
