package dev.cxclear.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Material 3 浅色方案，逐字段对应设计给的完整 token 表。
 *
 * 这里只做数据声明，不做任何语义解释：UI 一律通过 [AppColors] 取色，
 * 换主题时只需替换这一个对象，调用方不受影响。
 */
object M3Light {
    val primary = Color(0xFF475D92)
    val onPrimary = Color(0xFFFFFFFF)
    val primaryContainer = Color(0xFFD9E2FF)
    val onPrimaryContainer = Color(0xFF001945)

    val secondary = Color(0xFF575E71)
    val onSecondary = Color(0xFFFFFFFF)
    val secondaryContainer = Color(0xFFDCE2F9)
    val onSecondaryContainer = Color(0xFF151B2C)

    val tertiary = Color(0xFF725572)
    val onTertiary = Color(0xFFFFFFFF)
    val tertiaryContainer = Color(0xFFFDD7FA)
    val onTertiaryContainer = Color(0xFF2A122C)

    val error = Color(0xFFB3261E)
    val onError = Color(0xFFFFFFFF)
    val errorContainer = Color(0xFFF9DEDC)
    val onErrorContainer = Color(0xFF410E0B)

    val background = Color(0xFFFEFBFF)
    val onBackground = Color(0xFF1A1B20)
    val surface = Color(0xFFFEFBFF)
    val onSurface = Color(0xFF1A1B20)
    val surfaceVariant = Color(0xFFE1E2EC)
    val onSurfaceVariant = Color(0xFF44464F)

    val outline = Color(0xFF757780)
    val outlineVariant = Color(0xFFCAC4D0)
    val scrim = Color(0xFF000000)

    val inverseSurface = Color(0xFF2F3036)
    val inverseOnSurface = Color(0xFFF1F0F7)
    val inversePrimary = Color(0xFFB0C6FF)

    val surfaceDim = Color(0xFFDAD9E0)
    val surfaceBright = Color(0xFFFEFBFF)
    val surfaceContainerLowest = Color(0xFFFFFFFF)
    val surfaceContainerLow = Color(0xFFF8F7FE)
    val surfaceContainer = Color(0xFFF2F1F8)
    val surfaceContainerHigh = Color(0xFFECEBF2)
    val surfaceContainerHighest = Color(0xFFE6E5ED)
}

/**
 * 语义色门面。每个字段说明「用在哪」，取值一律来自 [M3Light]，
 * 不在此处新造颜色，避免主题之外出现游离色值。
 */
object AppColors {
    val Surface0 = M3Light.surfaceContainerLowest
    val Surface1 = M3Light.surface
    val Surface2 = M3Light.surfaceContainer
    val Surface3 = M3Light.surfaceContainerHighest
    val Surface4 = M3Light.surfaceVariant

    val Primary = M3Light.primary
    val PrimaryHover = M3Light.inversePrimary
    val PrimaryContainer = M3Light.primaryContainer
    val OnPrimary = M3Light.onPrimary

    val Safe = M3Light.primary
    val Optional = M3Light.tertiary
    val Error = M3Light.error

    val TextPrimary = M3Light.onSurface
    val TextSecondary = M3Light.onSurfaceVariant
    val TextTertiary = M3Light.outline

    val Outline = M3Light.outline
    val OutlineVariant = M3Light.outlineVariant

    /**
     * 存储分类色：可清理的三类走同一蓝色系明度阶梯，自浅到深；
     * 不可清理的保留数据用深灰跳出色系，与「可清理」形成类别区分而非程度区分。
     */
    val CategoryPackages = M3Light.inversePrimary
    val CategoryWorking = M3Light.primary
    val CategoryHistory = M3Light.onPrimaryContainer
    val CategoryRetained = M3Light.inverseSurface

    /**
     * 圆柱外壳：容器本体，取最浅的 surface 阶梯，避免与内容争视觉重量。
     * Edge 只比 Mid 深一阶 —— 用 surfaceDim 会在空筒两侧压出两道明显的灰边，
     * 圆度靠这一点点明度差交代就够，再深就成了脏。
     */
    val CylinderShellLight = M3Light.surfaceContainerLowest
    val CylinderShellMid = M3Light.surfaceContainerLow
    val CylinderShellEdge = M3Light.surfaceContainerHigh
}

object AppDimensions {
    const val SidebarWidth = 88f
    const val Radius = 12f
    const val RadiusFull = 999f
    const val SpacingSmall = 8f
    const val SpacingMedium = 16f
    const val SpacingLarge = 24f
}