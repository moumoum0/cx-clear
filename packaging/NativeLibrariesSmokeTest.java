import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;

class NativeLibrariesSmokeTest {
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

    }
}
