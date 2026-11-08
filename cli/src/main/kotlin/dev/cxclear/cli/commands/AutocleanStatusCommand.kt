package dev.cxclear.cli.commands

import dev.cxclear.chats.RetentionStore
import dev.cxclear.cli.AUTOCLEAN_TASK_NAME
import dev.cxclear.cli.Cli
import dev.cxclear.cli.Command
import dev.cxclear.cli.ParsedArgs
import dev.cxclear.cli.autocleanTaskInstalled
import dev.cxclear.cli.resolveAutocleanLaunch
import dev.cxclear.platform.HostOs
import dev.cxclear.platform.currentOs
import dev.cxclear.storage.AppPreferences

internal object AutocleanStatusCommand : Command {
    override val name = listOf("autoclean", "status")
    override val description = "show auto-clean enabled state and logon task"

    override fun execute(args: ParsedArgs): Int {
        val prefs = AppPreferences.read()
        val config = RetentionStore.read()
        Cli.printJson(
            mapOf(
                "ok" to true,
                "command" to "autoclean status",
                "enabled" to prefs.autoCleanEnabled,
                "task" to AUTOCLEAN_TASK_NAME,
                "task_installed" to (currentOs() == HostOs.WINDOWS && autocleanTaskInstalled()),
                "runs" to resolveAutocleanLaunch()?.display,
                "rules_total" to config.rules.size,
                "rules_enabled" to config.rules.count { it.enabled },
            )
        )
        return Cli.EXIT_OK
    }
}
