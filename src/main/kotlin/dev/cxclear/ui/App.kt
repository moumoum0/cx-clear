package dev.cxclear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import dev.cxclear.ui.components.MainContent
import dev.cxclear.ui.components.Sidebar
import dev.cxclear.ui.theme.AppColors

enum class Screen {
    SCAN, CLEAN, HISTORY
}

@Composable
fun App() {
    var currentScreen by remember { mutableStateOf(Screen.SCAN) }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.Surface1)
    ) {
        Sidebar(
            currentScreen = currentScreen,
            onScreenChange = { currentScreen = it }
        )

        MainContent(currentScreen = currentScreen)
    }
}
