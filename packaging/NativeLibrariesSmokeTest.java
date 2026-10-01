import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.win32.StdCallLibrary;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;

class NativeLibrariesSmokeTest {
    public interface User32 extends StdCallLibrary {
        boolean SetProcessDpiAwarenessContext(Pointer context);
        Pointer SetThreadDpiAwarenessContext(Pointer context);
        Pointer GetThreadDpiAwarenessContext();
        int GetAwarenessFromDpiAwarenessContext(Pointer context);
    }

    public static void main(String[] args) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE session (id TEXT PRIMARY KEY)");
                statement.execute("INSERT INTO session VALUES ('fixture')");
                connection.commit();
                try (var rows = statement.executeQuery("SELECT id FROM session")) {
                    if (!rows.next() || !"fixture".equals(rows.getString(1))) {
                        throw new AssertionError("SQLite read failed");
                    }
                }
                statement.execute("DELETE FROM session");
                connection.rollback();
                try (var rows = statement.executeQuery("SELECT COUNT(*) FROM session")) {
                    if (!rows.next() || rows.getInt(1) != 1) {
                        throw new AssertionError("SQLite rollback failed");
                    }
                }
            }
        }
        System.out.println("SQLite: read, write and rollback passed");

        var frames = new ByteArrayOutputStream();
        for (var text : new String[] {"first\n", "second\n"}) {
            var frame = new ByteArrayOutputStream();
            try (var compressed = new ZstdOutputStream(frame)) {
                compressed.write(text.getBytes(StandardCharsets.UTF_8));
            }
            frames.write(frame.toByteArray());
        }
        try (var decoded = new ZstdInputStream(new ByteArrayInputStream(frames.toByteArray()))) {
            decoded.setContinuous(true);
            if (!"first\nsecond\n".equals(new String(decoded.readAllBytes(), StandardCharsets.UTF_8))) {
                throw new AssertionError("Zstd concatenated frames failed");
            }
        }
        System.out.println("Zstd: concatenated frames passed");

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
