import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;

final class RunDatabase implements AutoCloseable {
    record Entry(long attempt, long seconds, String status, String reason, String backup, List<String> worlds) {
    }

    record Score(String uuid, String name, long value) {
    }

    record Activity(String owner, long attempt, String uuid, String name, ActivityMetric metric, long value) {
    }

    private final Connection connection;
    private final long sessionStarted = System.nanoTime();
    private final long session;

    RunDatabase(Path file) throws SQLException {
        connection = new org.sqlite.JDBC().connect("jdbc:sqlite:" + file.toAbsolutePath(), new Properties());
        try (Statement sql = connection.createStatement()) {
            sql.execute("PRAGMA busy_timeout=5000");
            sql.execute("""
                CREATE TABLE IF NOT EXISTS attempts(owner TEXT,  attempt INTEGER,  seconds INTEGER,  seed INTEGER,  started_at TEXT,  ended_at TEXT,  status TEXT,  reason TEXT,  backup TEXT,
                PRIMARY KEY(owner, attempt))
                """);
            sql.execute("CREATE TABLE IF NOT EXISTS worlds(owner TEXT, attempt INTEGER, name TEXT PRIMARY KEY, path TEXT, disk_state TEXT)");
            sql.execute("CREATE TABLE IF NOT EXISTS events(id INTEGER PRIMARY KEY, owner TEXT, attempt INTEGER, occurred_at TEXT, type TEXT, detail TEXT)");
            sql.execute("CREATE TABLE IF NOT EXISTS sessions(id INTEGER PRIMARY KEY, started_at TEXT, stopped_at TEXT, uptime_seconds INTEGER DEFAULT 0)");
            sql.execute("CREATE TABLE IF NOT EXISTS players(uuid TEXT PRIMARY KEY,name TEXT,last_seen TEXT)");
            sql.execute("""
                CREATE TABLE IF NOT EXISTS activity(owner TEXT, attempt INTEGER, uuid TEXT, metric TEXT, value INTEGER,
                PRIMARY KEY(owner, attempt, uuid, metric))
                """);
            sql.execute("""
                CREATE TABLE IF NOT EXISTS player_deaths(id INTEGER
                PRIMARY KEY, owner TEXT, attempt INTEGER, uuid TEXT, name TEXT, cause TEXT, world TEXT, occurred_at TEXT)
                """);
            sql.execute("CREATE TABLE IF NOT EXISTS run_records(owner TEXT,attempt INTEGER,best_seconds INTEGER,PRIMARY KEY(owner,attempt))");
            sql.execute("""
                INSERT INTO run_records SELECT owner, attempt, seconds
                FROM attempts
                WHERE seconds>=0
                ON CONFLICT(owner, attempt)
                DO UPDATE SET best_seconds=max(best_seconds, excluded.best_seconds)
                """);
            sql.executeUpdate("INSERT INTO sessions(started_at) VALUES('" + Instant.now() + "')");
            try (ResultSet result = sql.executeQuery("SELECT last_insert_rowid()")) {
                result.next();
                session = result.getLong(1);
            }
        }
    }

    synchronized void start(String owner, long attempt, long seconds, long seed) throws SQLException {
        execute("UPDATE attempts SET status='interrupted' WHERE owner=? AND status='active' AND attempt<>?", owner, attempt);
        execute("""
            INSERT INTO attempts(owner, attempt, seconds, seed, started_at, status)
            VALUES(?, ?, ?, ?, ?, 'active')
            ON CONFLICT(owner, attempt)
            DO UPDATE SET seconds=excluded.seconds, seed=excluded.seed, status='active', ended_at=NULL, reason=NULL
            """, owner, attempt, seconds, seed, Instant.now().toString());
        event(owner, attempt, "active", "");
        execute("""
            INSERT INTO run_records
            VALUES(?, ?, ?)
            ON CONFLICT(owner, attempt)
            DO UPDATE SET best_seconds=max(best_seconds, excluded.best_seconds)
            """, owner, attempt, seconds);
    }

    synchronized void progress(String owner, long attempt, long seconds) throws SQLException {
        execute("""
            INSERT INTO run_records
            VALUES(?, ?, ?)
            ON CONFLICT(owner, attempt)
            DO UPDATE SET best_seconds=max(best_seconds, excluded.best_seconds)
            """, owner, attempt, seconds);
        execute("UPDATE attempts SET seconds=? WHERE owner=? AND attempt=? AND status='active'", seconds, owner, attempt);
        execute("UPDATE sessions SET uptime_seconds=? WHERE id=?", (System.nanoTime() - sessionStarted) / 1_000_000_000, session);
    }

    synchronized void finish(String owner, long attempt, long seconds, String reason) throws SQLException {
        execute("""
            INSERT INTO run_records
            VALUES(?, ?, ?)
            ON CONFLICT(owner, attempt)
            DO UPDATE SET best_seconds=max(best_seconds, excluded.best_seconds)
            """, owner, attempt, seconds);
        execute("UPDATE attempts SET seconds=?,ended_at=?,status='ended',reason=? WHERE owner=? AND attempt=?", seconds, Instant.now().toString(), reason, owner, attempt);
        event(owner, attempt, "death", reason);
    }

    synchronized void world(String owner, long attempt, String name, Path path) throws SQLException {
        execute("INSERT INTO attempts(owner,attempt,seconds,status) VALUES(?,?,-1,'discovered') ON CONFLICT(owner,attempt) DO NOTHING", owner, attempt);
        execute("""
            INSERT INTO worlds(owner, attempt, name, path, disk_state)
            VALUES(?, ?, ?, ?, 'present')
            ON CONFLICT(name)
            DO UPDATE SET path=excluded.path, disk_state='present'
            """, owner, attempt, name, path.toAbsolutePath().normalize().toString());
    }

    synchronized void backup(String owner, long attempt, Path backup) throws SQLException {
        execute("UPDATE attempts SET backup=? WHERE owner=? AND attempt=?", backup.toAbsolutePath().toString(), owner, attempt);
        event(owner, attempt, "backup", backup.toString());
    }

    synchronized void deleted(String name) throws SQLException {
        execute("UPDATE worlds SET disk_state='deleted' WHERE name=?", name);
    }

    synchronized void event(String owner, long attempt, String type, String detail) throws SQLException {
        execute("INSERT INTO events(owner,attempt,occurred_at,type,detail) VALUES(?,?,?,?,?)", owner, attempt, Instant.now().toString(), type, detail);
    }

    synchronized List<Entry> history(String owner, Long attempt) throws SQLException {
        List<Entry> result = new ArrayList<>();
        try (PreparedStatement sql = connection.prepareStatement("SELECT attempt,seconds,status,reason,backup FROM attempts WHERE owner=?" + (attempt == null ? " ORDER BY attempt DESC LIMIT 10" : " AND attempt=?"))) {
            sql.setString(1, owner);
            if (attempt != null) sql.setLong(2, attempt);
            try (ResultSet rows = sql.executeQuery()) {
                while (rows.next()) {
                    long number = rows.getLong(1);
                    List<String> names = new ArrayList<>();
                    try (PreparedStatement worlds = connection.prepareStatement("SELECT name,disk_state FROM worlds WHERE owner=? AND attempt=? ORDER BY name")) {
                        worlds.setString(1, owner);
                        worlds.setLong(2, number);
                        try (ResultSet wr = worlds.executeQuery()) {
                            while (wr.next()) names.add(wr.getString(1) + " [" + wr.getString(2) + "]");
                        }
                    }
                    result.add(new Entry(number, rows.getLong(2), rows.getString(3), rows.getString(4), rows.getString(5), names));
                }
            }
        }
        return result;
    }

    synchronized void addActivity(List<Activity> values) throws SQLException {
        connection.setAutoCommit(false);
        try {
            for (Activity row : values) {
                if (row.value() < 0) throw new SQLException("Activity increments cannot be negative");
                execute("INSERT INTO players VALUES(?,?,?) ON CONFLICT(uuid) DO UPDATE SET name=excluded.name,last_seen=excluded.last_seen", row.uuid(), row.name(), Instant.now().toString());
                execute("INSERT INTO activity VALUES(?,?,?,?,?) ON CONFLICT(owner,attempt,uuid,metric) DO UPDATE SET value=value+excluded.value", row.owner(), row.attempt(), row.uuid(), row.metric().name(), row.value());
            }
            connection.commit();
        } catch (SQLException ex) {
            connection.rollback();
            throw ex;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    synchronized void playerDeath(String owner, long attempt, String uuid, String name, String cause, String world) throws SQLException {
        execute("INSERT INTO player_deaths(owner,attempt,uuid,name,cause,world,occurred_at) VALUES(?,?,?,?,?,?,?)", owner, attempt, uuid, name, cause, world, Instant.now().toString());
    }

    synchronized List<Score> top(String owner, Long attempt, ActivityMetric metric) throws SQLException {
        List<Score> result = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("SELECT a.uuid,p.name,sum(a.value) total FROM activity a JOIN players p ON p.uuid=a.uuid WHERE a.owner=? AND a.metric=?" + (attempt == null ? "" : " AND a.attempt=?") + " GROUP BY a.uuid,p.name ORDER BY total DESC,a.uuid LIMIT 200")) {
            query.setString(1, owner);
            query.setString(2, metric.name());
            if (attempt != null) query.setLong(3, attempt);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) result.add(new Score(rows.getString(1), rows.getString(2), rows.getLong(3)));
            }
        }
        return result;
    }

    synchronized Map<ActivityMetric, Long> profile(String owner, Long attempt, String uuid) throws SQLException {
        Map<ActivityMetric, Long> result = new EnumMap<>(ActivityMetric.class);
        try (PreparedStatement query = connection.prepareStatement("SELECT metric,sum(value) FROM activity WHERE owner=? AND uuid=?" + (attempt == null ? "" : " AND attempt=?") + " GROUP BY metric")) {
            query.setString(1, owner);
            query.setString(2, uuid);
            if (attempt != null) query.setLong(3, attempt);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) result.put(ActivityMetric.valueOf(rows.getString(1)), rows.getLong(2));
            }
        }
        return result;
    }

    synchronized List<Entry> bestRuns(String owner) throws SQLException {
        List<Entry> result = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("SELECT attempt,best_seconds FROM run_records WHERE owner=? ORDER BY best_seconds DESC,attempt LIMIT 200")) {
            query.setString(1, owner);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    var history = history(owner, rows.getLong(1));
                    if (!history.isEmpty()) {
                        var entry = history.getFirst();
                        result.add(new Entry(entry.attempt(), rows.getLong(2), entry.status(), entry.reason(), entry.backup(), entry.worlds()));
                    }
                }
            }
        }
        return result;
    }

    private void execute(String query, Object... values) throws SQLException {
        try (PreparedStatement sql = connection.prepareStatement(query)) {
            for (int i = 0; i < values.length; i++) sql.setObject(i + 1, values[i]);
            sql.executeUpdate();
        }
    }

    @Override
    public synchronized void close() throws SQLException {
        try {
            execute("UPDATE sessions SET stopped_at=?,uptime_seconds=? WHERE id=?", Instant.now().toString(), (System.nanoTime() - sessionStarted) / 1_000_000_000, session);
        } finally {
            connection.close();
        }
    }
}
