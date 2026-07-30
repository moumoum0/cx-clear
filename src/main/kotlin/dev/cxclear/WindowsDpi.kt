package dev.cxclear

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary

internal object WindowsDpi {
    private val perMonitorAwareV2 = Pointer.createConstant(-4)

    fun enablePerMonitorV2() {
        runCatching {
            Native.load("user32", User32Dpi::class.java)
                .SetProcessDpiAwarenessContext(perMonitorAwareV2)
        }
    }

    private interface User32Dpi : StdCallLibrary {
        fun SetProcessDpiAwarenessContext(value: Pointer): Boolean
    }
}