import java.io.IOException;
import java.nio.file.*;
import java.util.*;

import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Disk-only operations. World directories must be unloaded before finish().
 */
final class DeathBackups {
    private static final List<String> PLAYER_DIRS = List.of("playerdata", "advancements", "stats");
    private final Path directory;
    private final Path level;

    DeathBackups(Path plugin, Path level) throws IOException {
        this.directory = plugin.toAbsolutePath().normalize().resolve("death-backups");
        this.level = level.toRealPath();
        Files.createDirectories(directory);
    }

    Path prepare(YamlConfiguration state, List<Path> worlds) throws IOException {
        Path snapshot = directory.resolve("death-" + state.getLong("run") + "-" + UUID.randomUUID());
        Files.createDirectory(snapshot);
        YamlConfiguration manifest = new YamlConfiguration();
        manifest.set("owner", state.getString("world-id"));
        manifest.set("worlds", worlds.stream().map(p -> p.toAbsolutePath().normalize().toString()).toList());
        manifest.set("run", state.getLong("run"));
        manifest.set("seconds", state.getLong("seconds"));
        manifest.set("created", java.time.Instant.now().toString());
        manifest.save(snapshot.resolve("manifest.yml").toFile());
        state.save(snapshot.resolve("state.yml").toFile());
        for (String name : PLAYER_DIRS) {
            Path source = level.resolve(name);
            if (Files.isDirectory(source)) SnapshotFiles.copy(source, snapshot.resolve(name));
            else Files.createDirectory(snapshot.resolve(name));
        }
        return snapshot;
    }

    void finish(Path snapshot) throws IOException {
        var manifest = read(snapshot.resolve("manifest.yml"));
        List<Path> worlds = validatedWorlds(manifest);
        for (int i = 0; i < worlds.size(); i++) SnapshotFiles.copy(worlds.get(i), snapshot.resolve("world-" + i));
        Files.writeString(snapshot.resolve("READY"), "complete");
    }

    List<Path> list() throws IOException {
        try (var files = Files.list(directory)) {
            return files.filter(Files::isDirectory).filter(p -> Files.isRegularFile(p.resolve("READY")))
                    .sorted(Comparator.comparingLong((Path p) -> {
                        try {
                            return Files.getLastModifiedTime(p.resolve("READY")).toMillis();
                        } catch (IOException e) {
                            return 0;
                        }
                    }).reversed()).toList();
        }
    }

    YamlConfiguration manifest(Path snapshot) throws IOException {
        return read(snapshot.resolve("manifest.yml"));
    }

    void requestLatest() throws IOException {
        List<Path> available = list();
        if (available.isEmpty()) throw new IOException("No death backups available");
        request(available.getFirst().getFileName().toString());
    }

    void request(String id) throws IOException {
        if (!id.matches("death-[0-9]+-[a-f0-9-]{36}") || list().stream().noneMatch(p -> p.getFileName().toString().equals(id)))
            throw new IOException("Backup is not complete or does not exist");
        Path temporary = directory.resolve("restore-request.tmp");
        Files.writeString(temporary, id);
        Files.move(temporary, directory.resolve("restore-request"), StandardCopyOption.REPLACE_EXISTING);
    }

    boolean restoreRequested(Path stateFile) throws IOException {
        Path request = directory.resolve("restore-request");
        if (!Files.exists(request)) return false;
        String id = Files.readString(request).trim();
        if (!id.matches("death-[0-9]+-[a-f0-9-]{36}")) throw new IOException("Invalid restore request");
        Path snapshot = directory.resolve(id);
        if (!Files.isRegularFile(snapshot.resolve("READY")) || Files.isSymbolicLink(snapshot))
            throw new IOException("Incomplete backup: " + id);
        var manifest = manifest(snapshot);
        var restored = read(snapshot.resolve("state.yml"));
        var current = read(stateFile);
        if (!Objects.equals(current.getString("world-id"), manifest.getString("owner")))
            throw new IOException("Backup belongs to another world set");
        if (!Objects.equals(restored.getString("world-id"), manifest.getString("owner"))
                || restored.getLong("run") != manifest.getLong("run"))
            throw new IOException("Backup state does not match its manifest");
        List<Path> targets = validatedWorlds(manifest);
        // Validate every source before the first replacement. READY alone cannot detect later damage.
        for (int i = 0; i < targets.size(); i++) SnapshotFiles.validate(snapshot.resolve("world-" + i));
        for (String name : PLAYER_DIRS) SnapshotFiles.validate(snapshot.resolve(name));
        Path safety = directory.resolve("safety-" + UUID.randomUUID());
        Files.createDirectory(safety);
        Files.copy(stateFile, safety.resolve("state.yml"));
        for (int i = 0; i < targets.size(); i++)
            SnapshotFiles.replace(snapshot.resolve("world-" + i), targets.get(i), safety.resolve("world-" + i));
        for (String name : PLAYER_DIRS)
            SnapshotFiles.replace(snapshot.resolve(name), level.resolve(name), safety.resolve(name));
        restored.set("highest-run", Math.max(current.getLong("highest-run", current.getLong("run")), restored.getLong("highest-run", restored.getLong("run"))));
        restored.set("reset-pending", false);
        restored.set("next-seed", null);
        Path temporary = stateFile.resolveSibling("state.yml.restore");
        restored.save(temporary.toFile());
        Files.move(temporary, stateFile, StandardCopyOption.REPLACE_EXISTING);
        Files.delete(request);
        return true;
    }

    private List<Path> validatedWorlds(YamlConfiguration manifest) throws IOException {
        String owner = manifest.getString("owner", "");
        long attempt = manifest.getLong("run");
        if (!owner.matches("[a-f0-9]{32}")) throw new IOException("Invalid backup owner");
        List<String> paths = manifest.getStringList("worlds");
        if (paths.size() != 3) throw new IOException("Expected three worlds");
        List<Path> result = new ArrayList<>();
        String[] suffix = {"", "_nether", "_the_end"};
        for (int i = 0; i < paths.size(); i++) {
            Path target = Path.of(paths.get(i)).toAbsolutePath().normalize();
            String expected = "hcr_" + owner + "_" + attempt + suffix[i];
            Path parent = target.getParent();
            if (!target.getFileName().toString().equals(expected) || parent == null
                    || !(parent.equals(level.resolve("dimensions/minecraft")) || parent.equals(level.getParent()))
                    || !parent.toRealPath().equals(parent) || Files.isSymbolicLink(target))
                throw new IOException("Unsafe backup destination: " + target);
            result.add(target);
        }
        return result;
    }

    private static YamlConfiguration read(Path file) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file.toFile());
        } catch (org.bukkit.configuration.InvalidConfigurationException e) {
            throw new IOException(e);
        }
        return yaml;
    }
}
