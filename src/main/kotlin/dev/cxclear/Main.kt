package dev.cxclear

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Dimension

private fun configureHighDpiRendering() {
    if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        WindowsDpi.enablePerMonitorV2()
        System.setProperty("sun.java2d.uiScale.enabled", "true")
    }
}

fun main() {
    configureHighDpiRendering()
    startApplication()
}

private fun startApplication() = application {
    val windowState = rememberWindowState(
        size = DpSize(1000.dp, 700.dp),
        position = WindowPosition.Aligned(androidx.compose.ui.Alignment.Center)
    )

    Window(
        onCloseRequest = ::exitApplication,
        title = "Cx Clear",
        state = windowState,
        resizable = true,
    ) {
        window.minimumSize = Dimension(800, 600)
        dev.cxclear.ui.App()
    }
}
