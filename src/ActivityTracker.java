import java.sql.SQLException;
import java.util.*;

import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.*;

final class ActivityTracker implements Listener {
    private record Counter(String owner, long attempt, UUID uuid, ActivityMetric metric) {
    }

    private final Main plugin;
    private final RunDatabase database;
    private final Map<Counter, Long> pending = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();

    ActivityTracker(Main plugin, RunDatabase database) {
        this.plugin = plugin;
        this.database = database;
    }

    private boolean tracking(Player player) {
        return plugin.canTrack(player.getWorld()) && player.getGameMode() != GameMode.CREATIVE && player.getGameMode() != GameMode.SPECTATOR;
    }

    private void add(Player player, ActivityMetric metric, long value) {
        if (value <= 0) return;
        names.put(player.getUniqueId(), player.getName());
        pending.merge(new Counter(plugin.ownerId(), plugin.activeAttempt(), player.getUniqueId(), metric), value, Long::sum);
        plugin.microQuestProgress(player, metric, value);
    }

    void questCompleted(Player player) {
        add(player, ActivityMetric.QUESTS_COMPLETED, 1);
    }

    void second() {
        for (Player player : Bukkit.getOnlinePlayers())
            if (tracking(player)) add(player, ActivityMetric.PLAY_SECONDS, 1);
    }

    void death(Player player, String cause) throws SQLException {
        add(player, ActivityMetric.DEATHS, 1);
        flush();
        database.playerDeath(plugin.ownerId(), plugin.activeAttempt(), player.getUniqueId().toString(), player.getName(), cause, player.getWorld().getKey().toString());
    }

    void flush() throws SQLException {
        List<RunDatabase.Activity> rows = pending.entrySet().stream().map(e -> new RunDatabase.Activity(e.getKey().owner(), e.getKey().attempt(), e.getKey().uuid().toString(), names.get(e.getKey().uuid()), e.getKey().metric(), e.getValue())).toList();
        database.addActivity(rows);
        pending.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!tracking(event.getPlayer())) return;
        add(event.getPlayer(), ActivityMetric.BLOCKS_MINED, 1);
        String type = event.getBlock().getType().name();
        if (type.endsWith("_ORE") || type.equals("ANCIENT_DEBRIS"))
            add(event.getPlayer(), ActivityMetric.ORES_MINED, 1);
        if ((event.getBlock().getType() == Material.DIAMOND_ORE || event.getBlock().getType() == Material.DEEPSLATE_DIAMOND_ORE)
                && Set.of(Material.IRON_PICKAXE, Material.DIAMOND_PICKAXE, Material.NETHERITE_PICKAXE).contains(event.getPlayer().getInventory().getItemInMainHand().getType()))
            add(event.getPlayer(), ActivityMetric.DIAMOND_ORES_MINED, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (tracking(event.getPlayer())) add(event.getPlayer(), ActivityMetric.BLOCKS_PLACED, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (!(event.getEntity() instanceof Player) && killer != null && tracking(killer))
            add(killer, ActivityMetric.MOBS_KILLED, 1);
        if (event.getEntity() instanceof Enemy && killer != null && tracking(killer))
            add(killer, ActivityMetric.HOSTILES_KILLED, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() == PlayerFishEvent.State.CAUGHT_FISH && tracking(event.getPlayer()))
            add(event.getPlayer(), ActivityMetric.FISH_CAUGHT, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        if (event.getAdvancement().getDisplay() != null && tracking(event.getPlayer()))
            add(event.getPlayer(), ActivityMetric.ADVANCEMENTS, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event instanceof PlayerTeleportEvent || !tracking(event.getPlayer()) || event.getFrom().getWorld() != event.getTo().getWorld())
            return;
        double distance = event.getFrom().distance(event.getTo());
        if (distance > 0 && distance <= 16)
            add(event.getPlayer(), ActivityMetric.DISTANCE_CM, Math.round(distance * 100));
    }
}
