package dev.cxclear.ui

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.cxclear.WindowsDpi
import dev.cxclear.platform.HostOs
import dev.cxclear.platform.currentOs
import dev.cxclear.resources.Res
import dev.cxclear.resources.hex_knot_arrow
import org.jetbrains.compose.resources.painterResource
import java.awt.Dimension

private fun configureHighDpiRendering() {
    if (currentOs() == HostOs.WINDOWS) {
        WindowsDpi.enablePerMonitorV2()
        System.setProperty("sun.java2d.uiScale.enabled", "true")
    }
}

fun main(args: Array<String>) {
    configureHighDpiRendering()
    startApplication()
}

private fun startApplication() = application {
    val windowState = rememberWindowState(
        size = DpSize(1070.dp, 750.dp),
        position = WindowPosition.Aligned(androidx.compose.ui.Alignment.Center)
    )

    Window(
        onCloseRequest = ::exitApplication,
        title = "CX Clear",
        state = windowState,
        resizable = true,
        undecorated = true,
        transparent = true,
        icon = painterResource(Res.drawable.hex_knot_arrow),
    ) {
        window.minimumSize = Dimension(856, 643)
        window.isAutoRequestFocus = false
        window.focusableWindowState = true
        App(
            windowState = windowState,
            onCloseRequest = ::exitApplication,
        )
    }
}
