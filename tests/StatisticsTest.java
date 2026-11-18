import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class StatisticsTest {
    @TempDir
    Path directory;

    private RunDatabase.Activity row(long attempt, String uuid, ActivityMetric metric, long value) {
        return new RunDatabase.Activity("world", attempt, uuid, "Игрок " + uuid, metric, value);
    }

    @Test
    void ranksPlayersGloballyAndInsideOneAttempt() throws Exception {
        try (RunDatabase db = new RunDatabase(directory.resolve("stats.db"))) {
            db.addActivity(List.of(row(1, "alice", ActivityMetric.BLOCKS_MINED, 100), row(2, "alice", ActivityMetric.BLOCKS_MINED, 20), row(2, "bob", ActivityMetric.BLOCKS_MINED, 50)));
            assertEquals("alice", db.top("world", null, ActivityMetric.BLOCKS_MINED).getFirst().uuid());
            assertEquals(120, db.top("world", null, ActivityMetric.BLOCKS_MINED).getFirst().value());
            assertEquals("bob", db.top("world", 2L, ActivityMetric.BLOCKS_MINED).getFirst().uuid());
            assertEquals(20, db.profile("world", 2L, "alice").get(ActivityMetric.BLOCKS_MINED));
            assertTrue(db.top("another-world", null, ActivityMetric.BLOCKS_MINED).isEmpty());
        }
    }

    @Test
    void deathCountsAndNamesPersistAcrossRestart() throws Exception {
        Path file = directory.resolve("stats.db");
        try (RunDatabase db = new RunDatabase(file)) {
            db.addActivity(List.of(row(1, "a", ActivityMetric.DEATHS, 1), row(2, "a", ActivityMetric.DEATHS, 1)));
            db.addActivity(List.of(new RunDatabase.Activity("world", 2, "a", "New Name's", ActivityMetric.PLAY_SECONDS, 5)));
            db.playerDeath("world", 2, "a", "New Name's", "slain by Wolf", "minecraft:overworld");
        }
        try (RunDatabase db = new RunDatabase(file)) {
            var leader = db.top("world", null, ActivityMetric.DEATHS).getFirst();
            assertEquals(2, leader.value());
            assertEquals("New Name's", leader.name());
        }
    }

    @Test
    void failedBatchDoesNotPartiallyCountActivity() throws Exception {
        try (RunDatabase db = new RunDatabase(directory.resolve("stats.db"))) {
            assertThrows(SQLException.class, () -> db.addActivity(List.of(row(1, "a", ActivityMetric.DEATHS, 1), row(1, "b", ActivityMetric.DEATHS, -1))));
            assertTrue(db.top("world", null, ActivityMetric.DEATHS).isEmpty());
            db.addActivity(List.of(row(1, "a", ActivityMetric.DEATHS, 1)));
            assertEquals(1, db.top("world", null, ActivityMetric.DEATHS).getFirst().value());
        }
    }

    @Test
    void bestRunRecordSurvivesRollbackToEarlierTime() throws Exception {
        try (RunDatabase db = new RunDatabase(directory.resolve("stats.db"))) {
            db.start("world", 1, 0, 42);
            db.finish("world", 1, 600, "death");
            db.start("world", 2, 0, 43);
            db.progress("world", 2, 300);
            db.start("world", 1, 100, 42);
            db.progress("world", 1, 110);
            var best = db.bestRuns("world").getFirst();
            assertEquals(1, best.attempt());
            assertEquals(600, best.seconds());
        }
    }
}
