package dev.cxclear.storage

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * 应用级偏好。存 `~/.cxclear/preferences.txt`，key=value，容错解析。
 *
 * 与 [RetentionStore] / [CleanHistory] 同目录；只记启动默认与总开关，
 * 不代替对话策略或清理历史。
 */
data class AppPrefs(
    /** 扫描页默认勾选的工具 id（codex / claude / cursor）。空则回退到 codex。 */
    val defaultTools: Set<String> = setOf("codex"),
    /** 启动时是否恢复 [lastScreenId]。 */
    val rememberLastScreen: Boolean = false,
    /** 上次打开的页面：scan / chats / settings。 */
    val lastScreenId: String = "scan",
    /** 对话管理默认页：manual / auto。 */
    val defaultChatsMode: String = "manual",
    /** 总开关：关则跳过自动清理执行，不改各条规则。 */
    val autoCleanEnabled: Boolean = true,
    /** 自动清理删过东西后是否弹出通知条。 */
    val autoCleanNotify: Boolean = true,
)

object AppPreferences {
    private const val FILE_NAME = "preferences.txt"

    private val knownTools = setOf("codex", "claude", "cursor")
    private val knownScreens = setOf("scan", "chats", "settings")
    private val knownChatsModes = setOf("manual", "auto")

    private fun file(): Path? = AppDir.dir()?.resolve(FILE_NAME)

    fun read(): AppPrefs {
        val path = file() ?: return AppPrefs()
        if (!Files.exists(path)) return AppPrefs()
        val props = runCatching {
            Files.readAllLines(path).mapNotNull { line ->
                val eq = line.indexOf('=')
                if (eq < 0) null else line.substring(0, eq).trim() to line.substring(eq + 1).trim()
            }.toMap()
        }.getOrDefault(emptyMap())
        if (props.isEmpty()) return AppPrefs()

        val tools = props["default_tools"]
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it in knownTools }
            ?.toSet()
            ?.ifEmpty { setOf("codex") }
            ?: setOf("codex")

        val screen = props["last_screen"]?.takeIf { it in knownScreens } ?: "scan"
        val chatsMode = props["default_chats_mode"]?.takeIf { it in knownChatsModes } ?: "manual"

        return AppPrefs(
            defaultTools = tools,
            rememberLastScreen = props["remember_last_screen"]?.toBooleanStrictOrNull() ?: false,
            lastScreenId = screen,
            defaultChatsMode = chatsMode,
            autoCleanEnabled = props["auto_clean_enabled"]?.toBooleanStrictOrNull() ?: true,
            autoCleanNotify = props["auto_clean_notify"]?.toBooleanStrictOrNull() ?: true,
        )
    }

    fun write(prefs: AppPrefs) {
        val path = file() ?: return
        val tools = prefs.defaultTools.filter { it in knownTools }.ifEmpty { listOf("codex") }
        val screen = prefs.lastScreenId.takeIf { it in knownScreens } ?: "scan"
        val chatsMode = prefs.defaultChatsMode.takeIf { it in knownChatsModes } ?: "manual"
        val lines = listOf(
            "default_tools=${tools.joinToString(",")}",
            "remember_last_screen=${prefs.rememberLastScreen}",
            "last_screen=$screen",
            "default_chats_mode=$chatsMode",
            "auto_clean_enabled=${prefs.autoCleanEnabled}",
            "auto_clean_notify=${prefs.autoCleanNotify}",
        )
        runCatching {
            Files.createDirectories(path.parent)
            Files.write(
                path,
                lines,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
        }
    }

    fun update(transform: (AppPrefs) -> AppPrefs) {
        write(transform(read()))
    }
}
