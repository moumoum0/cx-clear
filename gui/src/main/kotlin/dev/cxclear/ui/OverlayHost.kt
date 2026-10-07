package dev.cxclear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

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
