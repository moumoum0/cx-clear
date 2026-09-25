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


val LocalAppColors = staticCompositionLocalOf { appColorTokensOf(M3DefaultLight) }

/** 当前主题语义色；只在 @Composable 里读。Canvas 等非组合作用域先抓到局部变量再用。 */
val AppColors: AppColorTokens
    @Composable
    @ReadOnlyComposable
    get() = LocalAppColors.current

private fun resolveM3Tokens(scheme: AppColorScheme, dark: Boolean): M3Tokens = when (scheme) {
    AppColorScheme.APP_DEFAULT -> if (dark) M3DefaultDark else M3DefaultLight
    AppColorScheme.CLOUD_FIELD -> if (dark) M3CloudFieldDark else M3CloudFieldLight
}

// 35 个具名参数与 M3Tokens 的 35 个字段逐一硬对应，与 ThemeTokens.kt 保持同步
private fun M3Tokens.toMaterialColorScheme(dark: Boolean) = if (dark) {
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
private fun shouldUseDarkTheme(themeMode: ThemeMode): Boolean = when (themeMode) {
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
