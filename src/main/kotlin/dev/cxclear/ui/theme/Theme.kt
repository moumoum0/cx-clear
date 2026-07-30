package dev.cxclear.ui.theme

import androidx.compose.ui.graphics.Color

object AppColors {
    // 背景阶梯 (基于浅色方案)
    val Surface0 = Color(0xFFFFFFFF)
    val Surface1 = Color(0xFFFEFBFF)
    val Surface2 = Color(0xFFF2F1F8)
    val Surface3 = Color(0xFFE6E5ED)
    val Surface4 = Color(0xFFE1E2EC)

    // 强调色 (使用 Material 3 primary)
    val Primary = Color(0xFF475D92)
    val PrimaryHover = Color(0xFF5A6FA0)
    val PrimaryContainer = Color(0xFFD9E2FF)
    val OnPrimary = Color(0xFFFFFFFF)

    // 状态色
    val Safe = Color(0xFF2E7D32)        // 绿色系
    val Optional = Color(0xFFED6C02)    // 橙色系
    val Error = Color(0xFFB3261E)       // Material 3 error

    // 文本
    val TextPrimary = Color(0xFF1A1B20)
    val TextSecondary = Color(0xFF44464F)
    val TextTertiary = Color(0xFF757780)

    // 边框
    val Outline = Color(0xFF757780)
    val OutlineVariant = Color(0xFFCAC4D0)
}

object AppDimensions {
    const val SidebarWidth = 88f
    const val Radius = 12f
    const val RadiusFull = 999f
    const val SpacingSmall = 8f
    const val SpacingMedium = 16f
    const val SpacingLarge = 24f
}
