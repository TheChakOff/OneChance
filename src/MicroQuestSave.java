

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

record MicroQuestSave(String owner, long attempt, QuestProgress quest, String reward, int remainingSeconds,
                      int cycleSeconds, boolean skipped) {
    MicroQuestSave {
        if (cycleSeconds < 300 || cycleSeconds > 1200 || remainingSeconds < 0 || remainingSeconds > cycleSeconds)
            throw new IllegalArgumentException("Invalid quest cycle");
    }

    static MicroQuestSave read(ConfigurationSection saved, String owner, long attempt) {
        if (saved == null || saved.getInt("schema") != 2 || !owner.equals(saved.getString("owner")) || saved.getLong("attempt") != attempt)
            return null;
        try {
            QuestProgress progress = new QuestProgress(MicroTask.valueOf(saved.getString("task", "")), saved.getInt("target"), saved.getLong("value"));
            return new MicroQuestSave(owner, attempt, progress, saved.getString("reward", ""),
                    saved.getInt("remaining-seconds"), saved.getInt("cycle-seconds"), saved.getBoolean("skipped"));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    void write(YamlConfiguration state, String key) {
        state.set(key + ".owner", owner);
        state.set(key + ".attempt", attempt);
        state.set(key + ".schema", 2);
        state.set(key + ".task", quest.task().name());
        state.set(key + ".target", quest.target());
        state.set(key + ".value", quest.value());
        state.set(key + ".reward", reward);
        state.set(key + ".remaining-seconds", remainingSeconds);
        state.set(key + ".cycle-seconds", cycleSeconds);
        state.set(key + ".skipped", skipped);
        state.set(key + ".next-seconds", null);
        state.set(key + ".skip-cooldown", null);
    }
}
