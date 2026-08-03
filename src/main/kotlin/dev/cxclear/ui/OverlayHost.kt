package dev.cxclear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 全窗浮层宿主。
 *
 * 谁需要「盖住整个窗口（含标题栏）的浮层」，就把要浮的内容交给它——由根部 [App] 在
 * scrim 之上统一渲染，而不用把状态一路 prop-drill 穿过 [dev.cxclear.ui.components.MainContent]。
 *
 * [show] / [hide] 只应在事件回调里调用（点击、确认、取消），不要在组合过程中改它。
 * 内容自持 `remember` 状态，是一段正常的 composable 子树，只是挂在 [App] 的位置渲染。
 */
class OverlayHostState {
    var content: (@Composable () -> Unit)? by mutableStateOf(null)
        private set

    fun show(content: @Composable () -> Unit) {
        this.content = content
    }

    fun hide() {
        content = null
    }
}

val LocalOverlayHost = staticCompositionLocalOf<OverlayHostState> {
    error("LocalOverlayHost 未提供：确认内容包在 App 的 CompositionLocalProvider 里")
}
