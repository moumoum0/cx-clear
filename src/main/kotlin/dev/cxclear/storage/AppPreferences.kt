package dev.cxclear.storage

import dev.cxclear.tools.tools
import dev.cxclear.ui.theme.AppColorScheme
import dev.cxclear.ui.theme.ThemeMode
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

private fun knownToolIds(): Set<String> = tools().map { it.profile.id }.toSet()

// 偏好设置
data class AppPrefs(
    // 扫描页默认勾选的工具 id。空则回退到 ChatTool 全量。
    val defaultTools: Set<String> = knownToolIds(),
    // 启动时是否恢复 [lastScreenId]。
    val rememberLastScreen: Boolean = false,
    // 上次打开的页面：scan / chats / settings。
    val lastScreenId: String = "scan",
    // 对话管理默认页：manual / auto。
    val defaultChatsMode: String = "manual",
    // 总开关：关则跳过自动清理执行，不改各条规则。
    val autoCleanEnabled: Boolean = true,
    // 自动清理删过东西后是否弹出通知条。
    val autoCleanNotify: Boolean = true,
    // 亮 / 暗 / 跟随系统。
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    // 配色：应用默认 / 云野。
    val colorScheme: AppColorScheme = AppColorScheme.APP_DEFAULT,
)

object AppPreferences {
    private const val FILE_NAME = "preferences.txt"

    private val knownTools get() = knownToolIds()
    private val knownScreens = setOf("scan", "chats", "settings")
    private val knownChatsModes = setOf("manual", "auto")

    private fun file(): Path? = AppDir.dir()?.resolve(FILE_NAME)

    private fun parseThemeMode(raw: String?): ThemeMode =
        ThemeMode.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: ThemeMode.SYSTEM

    private fun parseColorScheme(raw: String?): AppColorScheme =
        AppColorScheme.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
            ?: AppColorScheme.APP_DEFAULT

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
            ?.ifEmpty { knownTools }
            ?: knownTools

        val screen = props["last_screen"]?.takeIf { it in knownScreens } ?: "scan"
        val chatsMode = props["default_chats_mode"]?.takeIf { it in knownChatsModes } ?: "manual"

        return AppPrefs(
            defaultTools = tools,
            rememberLastScreen = props["remember_last_screen"]?.toBooleanStrictOrNull() ?: false,
            lastScreenId = screen,
            defaultChatsMode = chatsMode,
            autoCleanEnabled = props["auto_clean_enabled"]?.toBooleanStrictOrNull() ?: true,
            autoCleanNotify = props["auto_clean_notify"]?.toBooleanStrictOrNull() ?: true,
            themeMode = parseThemeMode(props["theme_mode"]),
            colorScheme = parseColorScheme(props["color_scheme"]),
        )
    }

    fun write(prefs: AppPrefs) {
        val path = file() ?: return
        val tools = prefs.defaultTools.filter { it in knownTools }.ifEmpty { knownTools.toList() }
        val screen = prefs.lastScreenId.takeIf { it in knownScreens } ?: "scan"
        val chatsMode = prefs.defaultChatsMode.takeIf { it in knownChatsModes } ?: "manual"
        val lines = listOf(
            "default_tools=${tools.joinToString(",")}",
            "remember_last_screen=${prefs.rememberLastScreen}",
            "last_screen=$screen",
            "default_chats_mode=$chatsMode",
            "auto_clean_enabled=${prefs.autoCleanEnabled}",
            "auto_clean_notify=${prefs.autoCleanNotify}",
            "theme_mode=${prefs.themeMode.name}",
            "color_scheme=${prefs.colorScheme.name}",
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
