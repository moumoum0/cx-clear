package dev.cxclear.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

enum class ThemeMode(val displayName: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色模式"),
    DARK("深色模式"),
}

// 壁纸取色是 Android 专用,桌面端暂不开放
enum class AppColorScheme(
    val displayName: String,
    val description: String,
) {
    APP_DEFAULT("应用默认", "使用应用默认配色方案"),
    CLOUD_FIELD("云野", "云野 - 自然清新的绿色主题"),
}


data class M3Tokens(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color,
    val onTertiary: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color,
    val error: Color,
    val onError: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
    val background: Color,
    val onBackground: Color,
    val surface: Color,
    val onSurface: Color,
    val surfaceVariant: Color,
    val onSurfaceVariant: Color,
    val outline: Color,
    val outlineVariant: Color,
    val scrim: Color,
    val inverseSurface: Color,
    val inverseOnSurface: Color,
    val inversePrimary: Color,
    val surfaceDim: Color,
    val surfaceBright: Color,
    val surfaceContainerLowest: Color,
    val surfaceContainerLow: Color,
    val surfaceContainer: Color,
    val surfaceContainerHigh: Color,
    val surfaceContainerHighest: Color,
)

// 默认配色
val M3DefaultLight = M3Tokens(
    primary = Color(0xFF475D92),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD9E2FF),
    onPrimaryContainer = Color(0xFF001945),
    secondary = Color(0xFF575E71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCE2F9),
    onSecondaryContainer = Color(0xFF151B2C),
    tertiary = Color(0xFF725572),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFDD7FA),
    onTertiaryContainer = Color(0xFF2A122C),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFFEFBFF),
    onBackground = Color(0xFF1A1B20),
    surface = Color(0xFFFEFBFF),
    onSurface = Color(0xFF1A1B20),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF44464F),
    outline = Color(0xFF757780),
    outlineVariant = Color(0xFFCAC4D0),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF2F3036),
    inverseOnSurface = Color(0xFFF1F0F7),
    inversePrimary = Color(0xFFB0C6FF),
    surfaceDim = Color(0xFFDAD9E0),
    surfaceBright = Color(0xFFFEFBFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8F7FE),
    surfaceContainer = Color(0xFFF2F1F8),
    surfaceContainerHigh = Color(0xFFECEBF2),
    surfaceContainerHighest = Color(0xFFE6E5ED),
)

// 目前部分深色由AI自动推算，可能存在错误，之后会改
val M3DefaultDark = M3Tokens(
    primary = Color(0xFFB0C6FF),
    onPrimary = Color(0xFF152E60),
    primaryContainer = Color(0xFF2E4578),
    onPrimaryContainer = Color(0xFFD9E2FF),
    secondary = Color(0xFFC0C6DC),
    onSecondary = Color(0xFF2A3042),
    secondaryContainer = Color(0xFF404659),
    onSecondaryContainer = Color(0xFFDCE2F9),
    tertiary = Color(0xFFE0BBDC),
    onTertiary = Color(0xFF412742),
    tertiaryContainer = Color(0xFF593D59),
    onTertiaryContainer = Color(0xFFFDD7FA),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    background = Color(0xFF121318),
    onBackground = Color(0xFFE3E2E9),
    surface = Color(0xFF121318),
    onSurface = Color(0xFFE3E2E9),
    surfaceVariant = Color(0xFF44464F),
    onSurfaceVariant = Color(0xFFC5C6D0),
    outline = Color(0xFF8F909A),
    outlineVariant = Color(0xFF44464F),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFE3E2E9),
    inverseOnSurface = Color(0xFF2F3036),
    inversePrimary = Color(0xFF475D92),
    surfaceDim = Color(0xFF121318),
    surfaceBright = Color(0xFF38393E),
    surfaceContainerLowest = Color(0xFF0D0E13),
    surfaceContainerLow = Color(0xFF1A1B20),
    surfaceContainer = Color(0xFF1E1F25),
    surfaceContainerHigh = Color(0xFF292A2F),
    surfaceContainerHighest = Color(0xFF34343A),
)

// 云野浅色
val M3CloudFieldLight = M3Tokens(
    primary = Color(0xFF3C6839),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFBDF0B3),
    onPrimaryContainer = Color(0xFF245023),
    secondary = Color(0xFF53634F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD6E8CE),
    onSecondaryContainer = Color(0xFF3B4B38),
    tertiary = Color(0xFF38656A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFBCEBF0),
    onTertiaryContainer = Color(0xFF1E4D52),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
    background = Color(0xFFF7FBF1),
    onBackground = Color(0xFF191D17),
    surface = Color(0xFFF7FBF1),
    onSurface = Color(0xFF191D17),
    surfaceVariant = Color(0xFFDEE5D8),
    onSurfaceVariant = Color(0xFF424940),
    outline = Color(0xFF73796F),
    outlineVariant = Color(0xFFC2C8BD),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF2D322B),
    inverseOnSurface = Color(0xFFEFF2E9),
    inversePrimary = Color(0xFFA2D399),
    surfaceDim = Color(0xFFD8DBD2),
    surfaceBright = Color(0xFFF7FBF1),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F5EB),
    surfaceContainer = Color(0xFFECEFE6),
    surfaceContainerHigh = Color(0xFFE6E9E0),
    surfaceContainerHighest = Color(0xFFE0E4DA),
)

// 云野深色
val M3CloudFieldDark = M3Tokens(
    primary = Color(0xFFA2D399),
    onPrimary = Color(0xFF0C390E),
    primaryContainer = Color(0xFF245023),
    onPrimaryContainer = Color(0xFFBDF0B3),
    secondary = Color(0xFFBACCB3),
    onSecondary = Color(0xFF253423),
    secondaryContainer = Color(0xFF3B4B38),
    onSecondaryContainer = Color(0xFFD6E8CE),
    tertiary = Color(0xFFA0CFD4),
    onTertiary = Color(0xFF00363B),
    tertiaryContainer = Color(0xFF1E4D52),
    onTertiaryContainer = Color(0xFFBCEBF0),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF10140F),
    onBackground = Color(0xFFE0E4DA),
    surface = Color(0xFF10140F),
    onSurface = Color(0xFFE0E4DA),
    surfaceVariant = Color(0xFF424940),
    onSurfaceVariant = Color(0xFFC2C8BD),
    outline = Color(0xFF8C9388),
    outlineVariant = Color(0xFF424940),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFE0E4DA),
    inverseOnSurface = Color(0xFF2D322B),
    inversePrimary = Color(0xFF3C6839),
    surfaceDim = Color(0xFF10140F),
    surfaceBright = Color(0xFF363A34),
    surfaceContainerLowest = Color(0xFF0B0F0A),
    surfaceContainerLow = Color(0xFF191D17),
    surfaceContainer = Color(0xFF1D211B),
    surfaceContainerHigh = Color(0xFF272B25),
    surfaceContainerHighest = Color(0xFF323630),
)


data class AppColorTokens(
    val Surface0: Color,
    val Surface1: Color,
    val Surface2: Color,
    val Surface3: Color,
    val Surface4: Color,
    val Primary: Color,
    val PrimaryHover: Color,
    val PrimaryContainer: Color,
    val OnPrimary: Color,
    val Safe: Color,
    val Optional: Color,
    val Error: Color,
    val TextPrimary: Color,
    val TextSecondary: Color,
    val TextTertiary: Color,
    val Outline: Color,
    val OutlineVariant: Color,
    val TextOnScrim: Color,
    val Scrim: Color,
    val CategoryPackages: Color,
    val CategoryWorking: Color,
    val CategoryHistory: Color,
    val CategoryRetained: Color,
    val CylinderShellLight: Color,
    val CylinderShellMid: Color,
    val CylinderShellEdge: Color,
    /** 柱体高光 / 扫光混色用，暗色下不能硬写 White。 */
    val Highlight: Color,
)

fun appColorTokensOf(tokens: M3Tokens): AppColorTokens = AppColorTokens(
    Surface0 = tokens.surfaceContainerLowest,
    Surface1 = tokens.surface,
    Surface2 = tokens.surfaceContainer,
    Surface3 = tokens.surfaceContainerHighest,
    Surface4 = tokens.surfaceVariant,
    Primary = tokens.primary,
    PrimaryHover = tokens.inversePrimary,
    PrimaryContainer = tokens.primaryContainer,
    OnPrimary = tokens.onPrimary,
    Safe = tokens.primary,
    Optional = tokens.tertiary,
    Error = tokens.error,
    TextPrimary = tokens.onSurface,
    TextSecondary = tokens.onSurfaceVariant,
    TextTertiary = tokens.outline,
    Outline = tokens.outline,
    OutlineVariant = tokens.outlineVariant,
    TextOnScrim = tokens.inverseOnSurface,
    Scrim = tokens.scrim,
    CategoryPackages = tokens.inversePrimary,
    CategoryWorking = tokens.primary,
    CategoryHistory = tokens.onPrimaryContainer,
    CategoryRetained = tokens.inverseSurface,
    CylinderShellLight = tokens.surfaceContainerLowest,
    CylinderShellMid = tokens.surfaceContainerLow,
    CylinderShellEdge = tokens.surfaceContainerHigh,
    Highlight = tokens.surfaceBright,
)

val LocalAppColors = staticCompositionLocalOf { appColorTokensOf(M3DefaultLight) }

/** 当前主题语义色；只在 @Composable 里读。Canvas 等非组合作用域先抓到局部变量再用。 */
val AppColors: AppColorTokens
    @Composable
    @ReadOnlyComposable
    get() = LocalAppColors.current

object AppDimensions {
    const val SidebarWidth = 88f
    const val TitleBarHeight = 40f
    /** 无边框窗口外轮廓圆角；最大化时不用。 */
    const val WindowCornerRadius = 8f
    const val Radius = 12f
    const val RadiusFull = 999f
    const val SpacingSmall = 8f
    const val SpacingMedium = 16f
    const val SpacingLarge = 24f
}

fun resolveM3Tokens(scheme: AppColorScheme, dark: Boolean): M3Tokens = when (scheme) {
    AppColorScheme.APP_DEFAULT -> if (dark) M3DefaultDark else M3DefaultLight
    AppColorScheme.CLOUD_FIELD -> if (dark) M3CloudFieldDark else M3CloudFieldLight
}

fun M3Tokens.toMaterialColorScheme(dark: Boolean) = if (dark) {
    darkColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        inversePrimary = inversePrimary,
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiary,
        onTertiary = onTertiary,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
        error = error,
        onError = onError,
        errorContainer = errorContainer,
        onErrorContainer = onErrorContainer,
        background = background,
        onBackground = onBackground,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        outline = outline,
        outlineVariant = outlineVariant,
        scrim = scrim,
        inverseSurface = inverseSurface,
        inverseOnSurface = inverseOnSurface,
        surfaceDim = surfaceDim,
        surfaceBright = surfaceBright,
        surfaceContainerLowest = surfaceContainerLowest,
        surfaceContainerLow = surfaceContainerLow,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest,
    )
} else {
    lightColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        inversePrimary = inversePrimary,
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiary,
        onTertiary = onTertiary,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
        error = error,
        onError = onError,
        errorContainer = errorContainer,
        onErrorContainer = onErrorContainer,
        background = background,
        onBackground = onBackground,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        outline = outline,
        outlineVariant = outlineVariant,
        scrim = scrim,
        inverseSurface = inverseSurface,
        inverseOnSurface = inverseOnSurface,
        surfaceDim = surfaceDim,
        surfaceBright = surfaceBright,
        surfaceContainerLowest = surfaceContainerLowest,
        surfaceContainerLow = surfaceContainerLow,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest,
    )
}

@Composable
fun shouldUseDarkTheme(themeMode: ThemeMode): Boolean = when (themeMode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun AppTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    colorScheme: AppColorScheme = AppColorScheme.APP_DEFAULT,
    content: @Composable () -> Unit,
) {
    val dark = shouldUseDarkTheme(themeMode)
    val tokens = resolveM3Tokens(colorScheme, dark)
    CompositionLocalProvider(LocalAppColors provides appColorTokensOf(tokens)) {
        MaterialTheme(
            colorScheme = tokens.toMaterialColorScheme(dark),
            content = content,
        )
    }
}

/** OutlinedTextField 统一取 [AppColors]，避免各输入框各自拼一套颜色。 */
@Composable
fun appOutlinedTextFieldColors(
    focusedTrailingIconColor: Color = AppColors.TextTertiary,
    unfocusedTrailingIconColor: Color = AppColors.TextTertiary,
    focusedLeadingIconColor: Color = AppColors.TextTertiary,
    unfocusedLeadingIconColor: Color = AppColors.TextTertiary,
): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedTextColor = AppColors.TextPrimary,
    unfocusedTextColor = AppColors.TextPrimary,
    cursorColor = AppColors.Primary,
    focusedBorderColor = AppColors.Primary,
    unfocusedBorderColor = AppColors.OutlineVariant,
    focusedContainerColor = AppColors.Surface3,
    unfocusedContainerColor = AppColors.Surface3,
    focusedLabelColor = AppColors.Primary,
    unfocusedLabelColor = AppColors.TextTertiary,
    focusedPlaceholderColor = AppColors.TextTertiary,
    unfocusedPlaceholderColor = AppColors.TextTertiary,
    focusedTrailingIconColor = focusedTrailingIconColor,
    unfocusedTrailingIconColor = unfocusedTrailingIconColor,
    disabledTrailingIconColor = AppColors.TextTertiary,
    focusedLeadingIconColor = focusedLeadingIconColor,
    unfocusedLeadingIconColor = unfocusedLeadingIconColor,
    disabledLeadingIconColor = AppColors.TextTertiary,
    focusedSuffixColor = AppColors.TextSecondary,
    unfocusedSuffixColor = AppColors.TextSecondary,
)
