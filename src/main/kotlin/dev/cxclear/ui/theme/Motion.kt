package dev.cxclear.ui.theme

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween

/**
 * 全应用动效节奏。组件里的 tween / AnimatedVisibility 一律取这里的时长与缓动，
 * 避免各处自写毫秒数导致节奏漂移。
 */
object Motion {
    /** 侧栏选中缩放、chevron、极短反馈 */
    const val FastMs = 180

    /** 淡入淡出、文案/按钮组切换、展开收起 */
    const val NormalMs = 240

    /** 占比条、分类底纹宽度 */
    const val MediumMs = 320

    /** 柱体段生长、历史柱 */
    const val GrowMs = 440

    /** 磁盘占用等较慢的数据入场 */
    const val SlowMs = 560

    /** 数字翻牌单次 */
    const val FlipMs = 260

    /** 扫描柱体扫光一周 */
    const val SweepMs = 1600

    /** 侧栏选中轻微放大 */
    const val ScaleMs = 120

    val Emphasized: Easing = FastOutSlowInEasing
    val Linear: Easing = LinearEasing

    fun <T> fast() = tween<T>(durationMillis = FastMs, easing = Emphasized)
    fun <T> normal() = tween<T>(durationMillis = NormalMs, easing = Emphasized)
    fun <T> medium() = tween<T>(durationMillis = MediumMs, easing = Emphasized)
    fun <T> grow() = tween<T>(durationMillis = GrowMs, easing = Emphasized)
    fun <T> slow() = tween<T>(durationMillis = SlowMs, easing = Emphasized)
    fun <T> scale() = tween<T>(durationMillis = ScaleMs, easing = Emphasized)
    fun <T> flip() = tween<T>(durationMillis = FlipMs, easing = Emphasized)
}
