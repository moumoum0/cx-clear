package dev.cxclear.cli.commands

import dev.cxclear.cli.AUTOCLEAN_TASK_NAME
import dev.cxclear.cli.Cli
import dev.cxclear.cli.Command
import dev.cxclear.cli.ParsedArgs
import dev.cxclear.cli.installAutocleanTask
import dev.cxclear.cli.resolveAutocleanLaunch
import dev.cxclear.platform.HostOs
import dev.cxclear.platform.currentOs
import dev.cxclear.storage.AppPreferences

internal object AutocleanOnCommand : Command {
    override val name = listOf("autoclean", "on")
    override val description = "run the saved auto-clean policy at Windows logon"

    override fun execute(args: ParsedArgs): Int {
        if (currentOs() != HostOs.WINDOWS) {
            Cli.printJson(mapOf("ok" to false, "error" to "autoclean only supports Windows"))
            return Cli.EXIT_FAIL
        }
        val launch = resolveAutocleanLaunch()
        if (launch == null) {
            Cli.printJson(mapOf("ok" to false, "error" to "no packaged install found; run autoclean on from the installed CLI"))
            return Cli.EXIT_FAIL
        }
        val result = installAutocleanTask(launch)
        if (result.exitCode != 0) {
            Cli.printJson(
                mapOf(
                    "ok" to false,
                    "error" to result.output.trim().ifEmpty { "schtasks exited ${result.exitCode}" },
                )
            )
            return Cli.EXIT_FAIL
        }
        AppPreferences.update { it.copy(autoCleanEnabled = true) }
        Cli.printJson(
            mapOf(
                "ok" to true,
                "command" to "autoclean on",
                "task" to AUTOCLEAN_TASK_NAME,
                "runs" to launch.display,
            )
        )
        return Cli.EXIT_OK
    }
}
