package dev.cxclear.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
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
import dev.cxclear.ui.theme.Motion

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

    val overlayHost = remember { OverlayHostState() }
    // 浮层出现时把底层内容模糊掉，营造「在画布上叠了一层」的纵深；关闭时平滑回到 0。
    val blurRadius by animateDpAsState(
        targetValue = if (overlayHost.content != null) 12.dp else 0.dp,
        animationSpec = Motion.normal(),
        label = "overlayBlur",
    )

    AppTheme {
    CompositionLocalProvider(LocalOverlayHost provides overlayHost) {
    // 整窗浮层要盖过标题栏，得有一层铺满整窗的 Box 兜着 scrim 与浮层内容；
    // clip 在最外层，scrim 与浮层因此都尊重窗口圆角。
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(windowShape),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .blur(blurRadius)
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

        // scrim + 浮层：只在有内容时出现，淡入淡出。点 scrim 空白处等同取消。
        val overlay = overlayHost.content
        AnimatedVisibility(
            visible = overlay != null,
            enter = fadeIn(Motion.normal()),
            exit = fadeOut(Motion.fast()),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AppColors.Scrim.copy(alpha = 0.45f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { overlayHost.hide() },
                    ),
            )
        }
        // 浮层内容单独渲染，不随 scrim 的 AnimatedVisibility 一起卸载——
        // 内容自持状态（草稿/输入），交给它自己决定何时 hide()。
        overlay?.invoke()
    }
    }
    }
}
