package dev.cxclear.cli.commands

import dev.cxclear.chats.RetentionRunner
import dev.cxclear.chats.RetentionStore
import dev.cxclear.chats.match
import dev.cxclear.chats.scanAllChatSessions
import dev.cxclear.cli.Cli
import dev.cxclear.cli.Command
import dev.cxclear.cli.DELETE_SWITCHES
import dev.cxclear.cli.ParsedArgs
import dev.cxclear.cli.previewJson
import dev.cxclear.cli.rulesInfo
import dev.cxclear.cli.sessionJson
import dev.cxclear.storage.AppPreferences
import dev.cxclear.storage.CleanHistory
import dev.cxclear.tools.chatTools
import kotlinx.coroutines.runBlocking

internal object AutocleanRunCommand : Command {
    override val name = listOf("autoclean", "run")
    override val description = "run the saved auto-clean policy now (preview unless --yes)"
    override val switches = DELETE_SWITCHES

    override fun execute(args: ParsedArgs): Int {
        val prefs = AppPreferences.read()
        if (!prefs.autoCleanEnabled) {
            Cli.printJson(
                mapOf(
                    "ok" to true,
                    "command" to "autoclean run",
                    "enabled" to false,
                    "skipped" to "auto-clean is disabled",
                )
            )
            return Cli.EXIT_OK
        }
        val sessions = scanAllChatSessions(chatTools().toSet())
        if (args.isPreview) {
            val config = RetentionStore.read()
            val matched = config.match(sessions, System.currentTimeMillis())
            Cli.printJson(
                previewJson(
                    "autoclean run", matched.size, matched.sumOf { it.sizeBytes },
                    "sessions", matched.map(::sessionJson),
                    mapOf("enabled" to true, "rules" to rulesInfo(config, "saved")),
                )
            )
            return Cli.EXIT_OK
        }
        val result = runBlocking { RetentionRunner.runIfNeeded(sessions) }
        if (result.freedBytes > 0L) CleanHistory.append(result.freedBytes)
        Cli.printJson(
            mapOf(
                "ok" to (result.blockedTools.isEmpty() && result.errors.isEmpty()),
                "command" to "autoclean run",
                "preview" to false,
                "deleted" to result.deletedSessions,
                "freed_bytes" to result.freedBytes,
                "blocked_tools" to result.blockedTools,
                "errors" to result.errors,
            )
        )
        return when {
            result.blockedTools.isNotEmpty() -> Cli.EXIT_BLOCKED
            result.errors.isNotEmpty() -> Cli.EXIT_FAIL
            else -> Cli.EXIT_OK
        }
    }
}
