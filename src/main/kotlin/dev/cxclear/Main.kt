package dev.cxclear

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.cxclear.resources.Res
import dev.cxclear.resources.hex_knot_arrow
import dev.cxclear.ui.App
import org.jetbrains.compose.resources.painterResource
import java.awt.Dimension

private fun configureHighDpiRendering() {
    if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        WindowsDpi.enablePerMonitorV2()
        System.setProperty("sun.java2d.uiScale.enabled", "true")
    }
}

fun main() {
    // 修复 Windows 控制台 UTF-8 输出乱码
    System.setProperty("file.encoding", "UTF-8")
    System.setProperty("stdout.encoding", "UTF-8")
    System.setProperty("stderr.encoding", "UTF-8")
    try {
        System.setOut(java.io.PrintStream(System.out, true, "UTF-8"))
        System.setErr(java.io.PrintStream(System.err, true, "UTF-8"))
    } catch (e: Exception) {
        // 忽略编码设置失败
    }
    
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
        // 透明底才能露出圆角外的桌面，否则仍是方角黑/白底。
        transparent = true,
        icon = painterResource(Res.drawable.hex_knot_arrow),
    ) {
        window.minimumSize = Dimension(856, 643)
        App(
            windowState = windowState,
            onCloseRequest = ::exitApplication,
        )
    }
}
