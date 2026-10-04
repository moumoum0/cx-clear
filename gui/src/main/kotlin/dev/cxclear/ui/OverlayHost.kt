package dev.cxclear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 全窗浮层宿主：要浮的内容交给它，由根部 [App] 在 scrim 之上统一渲染，
 * 省得把状态一路 prop-drill 穿过 [dev.cxclear.ui.components.MainContent]。
 * [show] / [hide] 在事件回调里调用，组合过程中改是误用。
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
