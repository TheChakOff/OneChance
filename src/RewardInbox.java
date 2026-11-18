import java.io.IOException;
import java.util.*;

import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Virtual prizes live in the same atomic state file as quest completion and world backups.
 */
final class RewardInbox {
    @FunctionalInterface
    interface Saver {
        void save() throws IOException;
    }

    record Entry(String id, String reward) {
    }

    private final YamlConfiguration state;
    private final Saver saver;

    RewardInbox(YamlConfiguration state, Saver saver) {
        this.state = state;
        this.saver = saver;
    }

    private String path(UUID player) {
        return "reward-inbox." + player;
    }

    List<Entry> list(UUID player) {
        var section = state.getConfigurationSection(path(player));
        if (section == null) return List.of();
        List<Entry> entries = new ArrayList<>();
        for (String id : section.getKeys(false)) {
            String reward = section.getString(id);
            if (QuestReward.known(reward)) entries.add(new Entry(id, reward));
        }
        return List.copyOf(entries);
    }

    void deposit(UUID player, String reward) throws IOException {
        if (!QuestReward.known(reward)) throw new IllegalArgumentException("Unknown quest reward: " + reward);
        String key = path(player) + "." + UUID.randomUUID();
        state.set(key, reward);
        try {
            saver.save();
        } catch (IOException ex) {
            state.set(key, null);
            throw ex;
        }
    }

    Entry claim(UUID player, String id) throws IOException {
        // Never accept a configuration path supplied by a stale or forged menu action.
        Entry entry = list(player).stream().filter(e -> e.id().equals(id)).findFirst().orElse(null);
        if (entry == null) return null;
        String key = path(player) + "." + entry.id();
        state.set(key, null);
        try {
            saver.save();
        } catch (IOException ex) {
            state.set(key, entry.reward());
            throw ex;
        }
        return entry;
    }

    static void clearForNewAttempt(YamlConfiguration state) {
        state.set("reward-inbox", null);
    }
}
