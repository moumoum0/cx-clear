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
    const val FastMs = 180
    const val NormalMs = 240
    const val MediumMs = 320
    const val GrowMs = 440
    const val SlowMs = 560
    const val FlipMs = 260
    const val SweepMs = 1600
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
