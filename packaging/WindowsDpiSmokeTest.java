import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.win32.StdCallLibrary;

class WindowsDpiSmokeTest {
    public interface User32 extends StdCallLibrary {
        boolean SetProcessDpiAwarenessContext(Pointer context);
        Pointer SetThreadDpiAwarenessContext(Pointer context);
        Pointer GetThreadDpiAwarenessContext();
        int GetAwarenessFromDpiAwarenessContext(Pointer context);
    }

    public static void main(String[] args) {
        var user32 = Native.load("user32", User32.class);
        boolean changedProcess = user32.SetProcessDpiAwarenessContext(Pointer.createConstant(-4L));
        int processError = Native.getLastError();
        // java.exe may already set process DPI awareness through its manifest.
        if (!changedProcess && processError != 5) {
            throw new AssertionError("Process DPI call failed: " + processError);
        }
        var previous = user32.SetThreadDpiAwarenessContext(Pointer.createConstant(-4L));
        if (previous == null) throw new AssertionError("Thread DPI call failed: " + Native.getLastError());
        try {
            if (user32.GetAwarenessFromDpiAwarenessContext(user32.GetThreadDpiAwarenessContext()) != 2) {
                throw new AssertionError("Per-monitor DPI awareness failed");
            }
        } finally {
            user32.SetThreadDpiAwarenessContext(previous);
        }
        System.out.println("JNA: per-monitor DPI awareness passed");
    }
}
