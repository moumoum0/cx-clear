package dev.cxclear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.WindowState
import dev.cxclear.ui.components.AppTitleBar
import dev.cxclear.ui.components.MainContent
import dev.cxclear.ui.components.Sidebar
import dev.cxclear.ui.theme.AppColors
import dev.cxclear.ui.theme.AppDimensions
import dev.cxclear.ui.theme.AppTheme

enum class Screen {
    SCAN, CHATS, SETTINGS
}

@Composable
fun WindowScope.App(
    windowState: WindowState,
    onCloseRequest: () -> Unit,
) {
    var currentScreen by remember { mutableStateOf(Screen.SCAN) }
    val isMaximized = windowState.placement == WindowPlacement.Maximized
    val windowShape = if (isMaximized) {
        RoundedCornerShape(0.dp)
    } else {
        RoundedCornerShape(AppDimensions.WindowCornerRadius.dp)
    }

    AppTheme {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(windowShape)
            .background(AppColors.Surface1, windowShape)
            .then(
                if (isMaximized) Modifier
                else Modifier.border(1.dp, AppColors.OutlineVariant, windowShape)
            ),
    ) {
        AppTitleBar(
            title = "Cx Clear",
            isMaximized = isMaximized,
            onMinimize = { windowState.isMinimized = true },
            onToggleMaximize = {
                windowState.placement =
                    if (isMaximized) WindowPlacement.Floating else WindowPlacement.Maximized
            },
            onClose = onCloseRequest,
        )

        // 必须 weight 吃掉标题栏以下剩余高度；fillMaxSize 会按整窗量高，底边被窗口 clip 裁掉一块。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(AppColors.Surface1),
        ) {
            Sidebar(
                currentScreen = currentScreen,
                onScreenChange = { currentScreen = it },
            )

            MainContent(
                currentScreen = currentScreen,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
    }
}
