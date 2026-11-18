import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.DriverManager;
import static org.junit.jupiter.api.Assertions.*;

class RunDatabaseTest {
    @TempDir Path directory;

    @Test void keepsAttemptDurationWorldNameAndBackupAcrossReopen() throws Exception {
        Path file = directory.resolve("history.db");
        try (RunDatabase db = new RunDatabase(file)) {
            db.start("owner", 3, 10, 42);
            db.world("owner", 3, "minecraft:hcr_owner_3", directory.resolve("world"));
            db.progress("owner", 3, 123);
            db.finish("owner", 3, 150, "Steve was slain by Wolf");
            db.backup("owner", 3, directory.resolve("backup"));
            db.deleted("minecraft:hcr_owner_3");
            db.progress("owner", 3, 0); // A late checkpoint must not overwrite a completed attempt.
        }
        try (RunDatabase db = new RunDatabase(file)) {
            var entry = db.history("owner", 3L).getFirst();
            assertEquals(150, entry.seconds());
            assertEquals("ended", entry.status());
            assertEquals("Steve was slain by Wolf", entry.reason());
            assertTrue(entry.backup().endsWith("backup"));
            assertEquals("minecraft:hcr_owner_3 [deleted]", entry.worlds().getFirst());
            assertTrue(db.history("another-owner", null).isEmpty());
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT count(*) FROM sessions WHERE stopped_at IS NOT NULL")) {
            rows.next(); assertEquals(2, rows.getInt(1));
        }
    }

    @Test void importingOldFolderDoesNotInventDurationOrOverwriteKnownDuration() throws Exception {
        try (RunDatabase db = new RunDatabase(directory.resolve("history.db"))) {
            db.world("owner", 1, "minecraft:hcr_owner_1", directory.resolve("old"));
            assertEquals(-1, db.history("owner", 1L).getFirst().seconds());
            db.start("owner", 1, 90, 42);
            db.world("owner", 1, "minecraft:hcr_owner_1", directory.resolve("old"));
            assertEquals(90, db.history("owner", 1L).getFirst().seconds());
        }
    }

    @Test void rollbackAndNextAttemptKeepSeparateHistoryEvents() throws Exception {
        try (RunDatabase db = new RunDatabase(directory.resolve("history.db"))) {
            db.start("owner", 3, 0, 42);
            db.finish("owner", 3, 100, "death");
            db.start("owner", 4, 0, 43);
            db.start("owner", 3, 100, 42);
            db.event("owner", 3, "rollback", "restored");
            assertEquals("active", db.history("owner", 3L).getFirst().status());
            assertEquals("interrupted", db.history("owner", 4L).getFirst().status());
            assertEquals(2, db.history("owner", null).size());
        }
    }
}
