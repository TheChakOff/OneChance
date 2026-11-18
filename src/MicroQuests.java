import java.io.IOException;
import java.util.*;
import java.util.logging.Level;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.*;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

final class MicroQuests {
    record Info(String title, String goal, String progress, String reward, boolean visible, int remainingSeconds,
                boolean completed, boolean skipped) {
        boolean waiting() {
            return completed || skipped;
        }
    }

    private static final class Session {
        final String owner;
        final long attempt;
        QuestProgress quest;
        final QuestReward reward;
        int remainingSeconds, cycleSeconds;
        boolean skipped;
        BossBar bar;
        boolean shown;

        Session(String owner, long attempt, QuestProgress quest, QuestReward reward) {
            this.owner = owner;
            this.attempt = attempt;
            this.quest = quest;
            this.reward = reward;
        }
    }

    private final Main plugin;
    private final YamlConfiguration state;
    private final Random random = new Random();
    private final Map<UUID, Session> sessions = new HashMap<>();

    MicroQuests(Main plugin, YamlConfiguration state) {
        this.plugin = plugin;
        this.state = state;
    }

    private boolean playable(Player player) {
        return plugin.getConfig().getBoolean("micro-quests.enabled", true) && plugin.canTrack(player.getWorld())
                && player.getGameMode() != GameMode.CREATIVE && player.getGameMode() != GameMode.SPECTATOR;
    }

    private String path(Player player) {
        return "micro-quests.players." + player.getUniqueId();
    }

    private boolean visible(Player player) {
        return !state.getBoolean("micro-quests.hidden." + player.getUniqueId(), false);
    }

    private Session session(Player player) {
        Session cached = sessions.get(player.getUniqueId());
        if (cached != null && cached.owner.equals(plugin.ownerId()) && cached.attempt == plugin.activeAttempt())
            return cached;
        if (cached != null) hide(player, cached);
        MicroQuestSave saved = MicroQuestSave.read(state.getConfigurationSection(path(player)), plugin.ownerId(), plugin.activeAttempt());
        if (saved != null) {
            try {
                Session restored = new Session(plugin.ownerId(), plugin.activeAttempt(), saved.quest(), QuestReward.valueOf(saved.reward()));
                restored.remainingSeconds = saved.remainingSeconds();
                restored.cycleSeconds = saved.cycleSeconds();
                restored.skipped = saved.skipped();
                if (restored.remainingSeconds <= 0) return assign(player, restored.quest.task());
                sessions.put(player.getUniqueId(), restored);
                return restored;
            } catch (IllegalArgumentException ignored) { /* Replace invalid or obsolete quest data. */ }
        }
        return assign(player, null);
    }

    private Session assign(Player player, MicroTask previous) {
        boolean rod = player.getInventory().contains(Material.FISHING_ROD), pickaxe = false, weapon = false, ironTier = false;
        for (ItemStack item : player.getInventory().getContents())
            if (item != null) {
                String name = item.getType().name();
                pickaxe |= name.endsWith("_PICKAXE");
                ironTier |= Set.of(Material.IRON_PICKAXE, Material.DIAMOND_PICKAXE, Material.NETHERITE_PICKAXE).contains(item.getType());
                weapon |= name.endsWith("_SWORD") || name.endsWith("_AXE") || item.getType() == Material.BOW;
            }
        MicroTask task = MicroTask.choose(random, previous, rod, pickaxe, weapon, ironTier);
        int duration = QuestCycle.duration(random, plugin.getConfig().getInt("micro-quests.min-interval-minutes", 5), plugin.getConfig().getInt("micro-quests.max-interval-minutes", 20));
        Session old = sessions.get(player.getUniqueId());
        if (old != null) hide(player, old);
        Session fresh = new Session(plugin.ownerId(), plugin.activeAttempt(), new QuestProgress(task, task.target(random, duration), 0), QuestReward.values()[random.nextInt(QuestReward.values().length)]);
        fresh.remainingSeconds = duration;
        fresh.cycleSeconds = duration;
        sessions.put(player.getUniqueId(), fresh);
        persist(player, fresh);
        return fresh;
    }

    private void persist(Player player, Session session) {
        new MicroQuestSave(session.owner, session.attempt, session.quest, session.reward.name(), session.remainingSeconds, session.cycleSeconds, session.skipped).write(state, path(player));
    }

    void observe(Player player, ActivityMetric metric, long amount) {
        if (!playable(player) || metric == ActivityMetric.QUESTS_COMPLETED || metric == ActivityMetric.DEATHS) return;
        Session session = session(player);
        if (session.skipped || session.remainingSeconds <= 0) return;
        QuestProgress before = session.quest;
        session.quest = before.advance(metric, amount);
        if (session.quest == before) return;
        if (session.quest.completed()) {
            persist(player, session);
            try {
                plugin.storeQuestReward(player, session.reward.name());
            } catch (IOException ex) {
                session.quest = before;
                persist(player, session);
                plugin.getLogger().log(Level.WARNING, "Micro-quest reward withheld because completion could not be saved", ex);
                return;
            }
            // Completion and its mailbox entry are saved together; nothing enters the player's inventory.
            plugin.microQuestCompleted(player);
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, .5f, 1.4f);
            player.spawnParticle(Particle.HAPPY_VILLAGER, player.getLocation().add(0, 1, 0), 12, .4, .5, .4, 0);
            player.sendActionBar(Component.text(plugin.text("Подвиг принят! Награда в /quest rewards"), NamedTextColor.GOLD));
            refresh(player, session);
        } else persist(player, session);
    }

    void second() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!playable(player)) {
                Session old = sessions.get(player.getUniqueId());
                if (old != null) hide(player, old);
                continue;
            }
            Session session = session(player);
            if (--session.remainingSeconds <= 0) {
                session = assign(player, session.quest.task());
                player.sendActionBar(Component.text(plugin.text("Новое испытание: ") + plugin.text(session.quest.task().title), NamedTextColor.GOLD));
            }
            persist(player, session);
            refresh(player, session);
        }
    }

    void join(Player player) {
        if (playable(player)) refresh(player, session(player));
    }

    void leave(Player player) {
        Session old = sessions.remove(player.getUniqueId());
        if (old != null) {
            persist(player, old);
            hide(player, old);
        }
    }

    void suspend() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Session old = sessions.get(player.getUniqueId());
            if (old != null) hide(player, old);
        }
    }

    void close() {
        suspend();
        sessions.clear();
    }

    private void hide(Player player, Session session) {
        if (session.shown) {
            player.hideBossBar(session.bar);
            session.shown = false;
        }
    }

    private void refresh(Player player, Session session) {
        if (!visible(player)) {
            hide(player, session);
            return;
        }
        boolean waiting = session.quest.completed() || session.skipped;
        String clock = QuestCycle.time(session.remainingSeconds);
        Component text = Component.text(waiting ? (session.skipped ? plugin.text("Пропущено") : plugin.text("Подвиг принят!")) + plugin.text(" • Новое через ") + clock
                : session.quest.task().goal(session.quest.target(), plugin::text) + " • " + session.quest.task().progress(session.quest.value(), session.quest.target()) + plugin.text(" • до смены ") + clock, NamedTextColor.WHITE);
        float progress = waiting ? (float) session.remainingSeconds / session.cycleSeconds : session.quest.fraction();
        if (session.bar == null)
            session.bar = BossBar.bossBar(text, progress, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
        session.bar.name(text).progress(progress).overlay(waiting ? BossBar.Overlay.NOTCHED_10 : BossBar.Overlay.PROGRESS)
                .color(session.quest.completed() ? BossBar.Color.GREEN : session.skipped ? BossBar.Color.BLUE : BossBar.Color.YELLOW);
        if (!session.shown) {
            player.showBossBar(session.bar);
            session.shown = true;
        }
    }

    Info info(Player player) {
        if (!playable(player)) return null;
        Session s = session(player);
        return new Info(plugin.text(s.quest.task().title), s.quest.task().goal(s.quest.target(), plugin::text), s.quest.task().progress(s.quest.value(), s.quest.target()), plugin.text(s.reward.title), visible(player), s.remainingSeconds, s.quest.completed(), s.skipped);
    }

    String skip(Player player) {
        if (!playable(player)) return plugin.text("Задания доступны в мирах текущей попытки.");
        Session s = session(player);
        if (s.quest.completed() || s.skipped)
            return plugin.text("Следующее задание через ") + QuestCycle.time(s.remainingSeconds) + ".";
        s.skipped = true;
        persist(player, s);
        try {
            plugin.saveQuestState();
        } catch (IOException ex) {
            s.skipped = false;
            persist(player, s);
            plugin.getLogger().log(Level.WARNING, "Cannot persist skipped micro-quest", ex);
            return plugin.text("Не удалось сохранить пропуск. Задание осталось активным.");
        }
        refresh(player, s);
        return plugin.text("Пропущено без награды. Новое через ") + QuestCycle.time(s.remainingSeconds) + ".";
    }

    boolean isVisible(Player player) {
        return visible(player);
    }

    String toggle(Player player) {
        return visibility(player, !visible(player));
    }

    String visibility(Player player, boolean show) {
        boolean hide = !show;
        String key = "micro-quests.hidden." + player.getUniqueId();
        Object previous = state.get(key);
        state.set(key, hide);
        try {
            plugin.saveQuestState();
        } catch (IOException ex) {
            state.set(key, previous);
            plugin.getLogger().log(Level.WARNING, "Cannot persist boss-bar preference", ex);
            return plugin.text("Не удалось сохранить настройку. Попробуйте позже.");
        }
        if (playable(player)) refresh(player, session(player));
        return hide ? plugin.text("Босс-бар скрыт. Прогресс продолжает считаться.") : plugin.text("Босс-бар включён.");
    }
}
