import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;

import java.time.Duration;

import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import io.papermc.paper.event.player.PlayerClientLoadedWorldEvent;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Level;

public final class Main extends JavaPlugin implements Listener {
    private YamlConfiguration state;
    private YamlConfiguration legacyConfig;
    private Path dataDirectory;
    private Path stateFile;
    private NamespacedKey runKey;
    private String worldId;
    private long run, seconds;
    private World[] worlds;
    private volatile boolean resetting, failed;
    private final Map<UUID, UUID> protectedPlayers = new HashMap<>();
    private final Map<UUID, Long> pendingArrivals = new HashMap<>();
    private volatile LoginTarget loginTarget;
    private DeathBackups deathBackups;
    private RunDatabase database;
    private ActivityTracker activity;
    private PluginMenus menus;
    private DeathScreen deathScreen;
    private DeathSceneContext deathScene;
    private MicroQuests microQuests;
    private RewardInbox rewardInbox;
    private HealthHud healthHud;
    private Localized localized;
    private HealthPlaceholders placeholders;

    String text(String message) {
        return localized.text(message);
    }
    private RareMobSounds rareMobSounds;
    private boolean freezeHeld, previouslyFrozen;
    private int backupsInProgress;
    private int checkpointTicks;

    private record LoginTarget(Set<UUID> worlds, Location spawn) {
    }

    @Override
    public org.bukkit.configuration.file.FileConfiguration getConfig() {
        return legacyConfig == null ? super.getConfig() : legacyConfig;
    }

    @Override
    public void reloadConfig() {
        if (dataDirectory == null || dataDirectory.equals(getDataFolder().toPath())) {
            super.reloadConfig();
            return;
        }
        legacyConfig = YamlConfiguration.loadConfiguration(dataDirectory.resolve("config.yml").toFile());
        try (var defaults = new InputStreamReader(Objects.requireNonNull(getResource("config.yml")),
                java.nio.charset.StandardCharsets.UTF_8)) {
            legacyConfig.setDefaults(YamlConfiguration.loadConfiguration(defaults));
        } catch (IOException ex) {
            getLogger().log(Level.WARNING, "Cannot read bundled config.yml", ex);
        }
    }

    @Override
    public void saveConfig() {
        if (legacyConfig == null) {
            super.saveConfig();
            return;
        }
        try {
            legacyConfig.save(dataDirectory.resolve("config.yml").toFile());
        } catch (IOException ex) {
            getLogger().log(Level.WARNING, "Cannot save config.yml", ex);
        }
    }

    @Override
    public void onEnable() {
        var previous = getServer().getPluginManager().getPlugin("HardcoreReset");
        if (previous != null) {
            getLogger().severe("Remove the previous HardcoreReset JAR before starting OneChance.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        Path current = getDataFolder().toPath();
        Path legacy = current.resolveSibling("HardcoreReset");
        if (Files.isDirectory(current) && Files.isDirectory(legacy)) {
            getLogger().severe("Both OneChance and HardcoreReset data folders exist. Keep the intended data folder before restart.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        dataDirectory = Files.isDirectory(legacy) ? legacy : current;
        if (dataDirectory.equals(legacy)) reloadConfig();
        else saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        getConfig().set("death-screen.resource-pack-url", null);
        getConfig().set("death-screen.assume-pack-installed", null);
        getConfig().set("micro-quests.next-delay-seconds", null);
        getConfig().set("micro-quests.skip-cooldown-seconds", null);
        saveConfig();
        localized = new Localized(this);
        runKey = Objects.requireNonNull(NamespacedKey.fromString("hardcorereset:run"));
        stateFile = dataDirectory.resolve("state.yml");
        getServer().getPluginManager().registerEvents(this, this);
        if (hasApi("io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent"))
            getServer().getPluginManager().registerEvents(new AsyncSpawn(), this);
        else getServer().getPluginManager().registerEvents(new LegacySpawn(), this);
        if (hasApi("io.papermc.paper.event.player.PlayerClientLoadedWorldEvent"))
            getServer().getPluginManager().registerEvents(new ClientLoaded(), this);
        try {
            deathBackups = new DeathBackups(dataDirectory, levelDirectory());
            if (Files.exists(dataDirectory.resolve("death-backups/restore-request"))
                && Bukkit.getWorlds().stream().anyMatch(w -> w.getKey().getKey().startsWith("hcr_")))
                throw new IOException("Cannot restore while another plugin has loaded OneChance worlds");
            boolean restored = deathBackups.restoreRequested(stateFile);
            if (restored) getLogger().info("Death backup restored successfully");
            state = new YamlConfiguration();
            if (Files.exists(stateFile)) state.load(stateFile.toFile());
            worldId = state.getString("world-id");
            if (worldId == null) {
                worldId = UUID.randomUUID().toString().replace("-", "");
                state.set("world-id", worldId);
                state.set("run", 1L);
                state.set("seed", new Random().nextLong());
                saveState();
            }
            if (!worldId.matches("[a-f0-9]{32}")) throw new IOException("Invalid world-id");
            database = new RunDatabase(dataDirectory.resolve("history.db"));
            run = state.getLong("run", 1);
            seconds = state.getLong("seconds");
            boolean newAttempt = !state.getBoolean("initialized");
            worlds = createWorlds(run, state.getLong("seed"), null);
            if (newAttempt) startAtMorning(worlds[0]);
            saveState();
            database.start(worldId, run, seconds, state.getLong("seed"));
            if (state.getBoolean("reset-pending")) {
                database.finish(worldId, run, seconds, state.getString("pending-reason", "Interrupted reset"));
            }
            if (restored) database.event(worldId, run, "rollback", "Restored death backup");
            for (Path path : oldWorldDirectories()) registerDirectory(path);
            updateLoginTarget();
            activity = new ActivityTracker(this, database);
            microQuests = new MicroQuests(this, state);
            rewardInbox = new RewardInbox(state, this::saveQuestState);
            healthHud = new HealthHud(this);
            if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                HealthPlaceholders expansion = new HealthPlaceholders(this);
                if (expansion.register()) placeholders = expansion;
            }
            rareMobSounds = new RareMobSounds(this);
            menus = new PluginMenus(this, database, deathBackups);
            deathScreen = new DeathScreen(this);
            getServer().getPluginManager().registerEvents(activity, this);
            getServer().getPluginManager().registerEvents(menus, this);
            getServer().getPluginManager().registerEvents(deathScreen, this);
            getServer().getPluginManager().registerEvents(rareMobSounds, this);
            PluginCommands commands = new PluginCommands(this, menus, database, deathBackups, microQuests, state);
            for (String name : List.of("hcr", "quest", "stats", "try", "survived")) {
                PluginCommand command = Objects.requireNonNull(getCommand(name));
                command.setExecutor(commands);
                command.setTabCompleter(commands);
            }
            Bukkit.getScheduler().runTaskTimer(this, healthHud::refresh, 10, 10);
            if (state.getBoolean("reset-pending")) {
                resetting = true;
                Bukkit.getScheduler().runTask(this, this::performReset);
            } else for (Player p : Bukkit.getOnlinePlayers()) enterRun(p);
            Bukkit.getScheduler().runTaskTimer(this, this::tickAttempt, 20, 20);
        } catch (Exception ex) {
            failed = true;
            getLogger().log(Level.SEVERE, "Startup failed; joining is blocked until fixed", ex);
        }
    }

    @Override
    public void onDisable() {
        if (placeholders != null) placeholders.unregister();
        if (microQuests != null) microQuests.close();
        if (healthHud != null) healthHud.close();
        if (deathScreen != null) deathScreen.close();
        releaseFreeze();
        if (activity != null) try {
            activity.flush();
        } catch (java.sql.SQLException ex) {
            getLogger().log(Level.WARNING, "Cannot flush player statistics", ex);
        }
        if (state != null && !failed) checkpoint();
        if (database != null) try {
            database.close();
        } catch (java.sql.SQLException ex) {
            getLogger().log(Level.WARNING, "Could not close history database", ex);
        }
    }

    private void holdFreeze() {
        if (freezeHeld) return;
        previouslyFrozen = getServer().getServerTickManager().isFrozen();
        getServer().getServerTickManager().setFrozen(true);
        freezeHeld = true;
    }

    private void releaseFreeze() {
        if (freezeHeld) getServer().getServerTickManager().setFrozen(previouslyFrozen);
        freezeHeld = false;
    }

    private void protect(Player player, long ticks) {
        UUID token = UUID.randomUUID();
        UUID playerId = player.getUniqueId();
        protectedPlayers.put(playerId, token);
        if (ticks > 0) Bukkit.getScheduler().runTaskLater(this, () -> protectedPlayers.remove(playerId, token), ticks);
    }

    double maximumHealth(Player player) {
        Attribute type = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("max_health"));
        if (type == null) type = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("generic.max_health"));
        var attribute = type == null ? null : player.getAttribute(type);
        return attribute == null ? 20.0 : Math.max(1.0, attribute.getValue());
    }

    private void saveState() throws IOException {
        Path temporary = stateFile.resolveSibling("state.yml.tmp");
        state.save(temporary.toFile());
        try {
            Files.move(temporary, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, stateFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void checkpoint() {
        if (failed) return;
        state.set("seconds", seconds);
        try {
            saveState();
            if (activity != null) activity.flush();
            if (database != null) database.progress(worldId, run, seconds);
        } catch (IOException | java.sql.SQLException ex) {
            getLogger().log(Level.SEVERE, "Cannot save run state/history", ex);
        }
    }

    private String worldName(long attempt) {
        return "hcr_" + worldId + "_" + attempt;
    }

    private void tickAttempt() {
        if (placeholders != null) placeholders.refresh();
        // A failed transition must not overwrite the last recoverable state on disk.
        if (failed) return;
        if (!resetting && !Bukkit.getOnlinePlayers().isEmpty()) {
            seconds++;
            activity.second();
        }
        microQuests.second();
        rareMobSounds.second();
        menus.refresh();
        if (++checkpointTicks >= 30) {
            checkpointTicks = 0;
            checkpoint();
        }
    }

    private World[] createWorlds(long attempt, long seed, World[] previous) throws IOException {
        String base = worldName(attempt);
        World.Environment[] types = {World.Environment.NORMAL, World.Environment.NETHER, World.Environment.THE_END};
        String[] suffix = {"", "_nether", "_the_end"};
        World[] result = new World[3];
        for (int i = 0; i < result.length; i++) {
            WorldCreator creator = new WorldCreator(base + suffix[i]).environment(types[i]).seed(seed).hardcore(true);
            boolean deferredSpawn = getServer().getPluginManager().isPluginEnabled("DHSupport")
                && !WorldFiles.hasSavedWorld(Bukkit.getWorldContainer().toPath(), levelDirectory(), base + suffix[i]);
            // DHSupport registers worlds at WorldLoadEvent, after vanilla's initial spawn search.
            // Avoid generating spawn chunks until createWorld has returned and that event has fired.
            if (deferredSpawn) deferredSpawn = forceSpawn(creator, i == 2);
            result[i] = Objects.requireNonNull(creator.createWorld());
            result[i].setHardcore(true);
            result[i].setDifficulty(Difficulty.HARD);
            if (deferredSpawn && i == 0) selectSpawnAfterLoad(result[i]);
            if (result[i].getSeed() != seed) throw new IOException("Unexpected seed: " + result[i].getName());
            if (database != null) try {
                database.world(worldId, attempt, result[i].getKey().toString(), result[i].getWorldFolder().toPath());
            } catch (java.sql.SQLException ex) {
                throw new IOException("Cannot record world in history", ex);
            }
            if (previous != null) {
                for (GameRule<?> rule : gameRules()) copyRule(previous[i], result[i], rule);
            }
        }
        state.set("initialized", true);
        return result;
    }

    private void startAtMorning(World world) {
        world.setFullTime(0L);
        world.setStorm(false);
        world.setThundering(false);
        world.setWeatherDuration(12000);
    }

    private Iterable<GameRule<?>> gameRules() {
        try {
            return Registry.GAME_RULE;
        } catch (NoSuchFieldError legacy) {
            return Arrays.asList(GameRule.values());
        }
    }

    private <T> void copyRule(World from, World to, GameRule<T> rule) {
        Set<FeatureFlag> required;
        try {
            required = rule.requiredFeatures();
        } catch (NoSuchMethodError legacy) {
            required = Set.of();
        }
        RuleTransfer.copy(required, from.getFeatureFlags(), to.getFeatureFlags(),
            () -> from.getGameRuleValue(rule), value -> to.setGameRule(rule, value));
    }

    private void selectSpawnAfterLoad(World world) {
        for (int radius = 0; radius <= 64; radius += 8) {
            for (int x = -radius; x <= radius; x += 8)
                for (int z = -radius; z <= radius; z += 8) {
                    if (Math.max(Math.abs(x), Math.abs(z)) != radius) continue;
                    int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                    var ground = world.getBlockAt(x, y, z);
                    if (!ground.getType().isSolid() || ground.isLiquid() || ground.getType() == Material.MAGMA_BLOCK
                        || ground.getType() == Material.CACTUS || ground.getType() == Material.POWDER_SNOW)
                        continue;
                    if (world.getBlockAt(x, y + 1, z).isPassable() && world.getBlockAt(x, y + 2, z).isPassable()) {
                        world.setSpawnLocation(x, y + 1, z);
                        return;
                    }
                }
        }
        // An ocean-only search area still needs a safe arrival location.
        int y = Math.max(world.getSeaLevel(), world.getHighestBlockYAt(0, 0)) + 1;
        for (int x = -2; x <= 2; x++)
            for (int z = -2; z <= 2; z++) {
                world.getBlockAt(x, y, z).setType(Material.STONE);
                world.getBlockAt(x, y + 1, z).setType(Material.AIR);
                world.getBlockAt(x, y + 2, z).setType(Material.AIR);
            }
        world.setSpawnLocation(0, y + 1, 0);
    }

    private boolean managed(World world) {
        return worlds != null && Arrays.asList(worlds).contains(world);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        if (failed || !managed(event.getEntity().getWorld())) return;
        Component death = event.deathMessage();
        event.setCancelled(true);
        event.setReviveHealth(maximumHealth(event.getEntity()));
        if (!resetting) {
            deathScene = DeathSceneContext.capture(event);
            Component reason = deathReason(deathScene, death);
            try {
                activity.death(event.getEntity(), net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(reason));
            } catch (java.sql.SQLException ex) {
                getLogger().log(Level.WARNING, "Cannot record player death", ex);
            }
            state.set("pending-death-player", event.getEntity().getUniqueId().toString());
            beginReset(reason);
        }
    }

    String scenePath(DeathScene scene) {
        return "death-screen.causes." + scene.name().toLowerCase(Locale.ROOT);
    }

    private Component deathReason(DeathSceneContext context, Component vanilla) {
        Component fallback = vanilla == null ? Component.text(context.victimName() + text(" погиб")) : vanilla;
        String template = getConfig().getString(scenePath(context.scene()) + ".message",
            getConfig().getString("death-screen.message", "{vanilla}"));
        if (template.equals("{vanilla}")) return fallback;
        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(fallback);
        return Localized.colored(template.replace("{player}", context.victimName())
            .replace("{killer}", context.attackerName()).replace("{cause}", context.scene().name())
            .replace("{vanilla}", plain), NamedTextColor.WHITE);
    }

    private void beginReset(Component reason) {
        if (resetting || failed) return;
        resetting = true;
        if (microQuests != null) microQuests.suspend();
        holdFreeze();
        state.set("reset-pending", true);
        state.set("seconds", seconds);
        state.set("pending-reason", net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(reason));
        try {
            saveState();
            database.finish(worldId, run, seconds, state.getString("pending-reason"));
        } catch (IOException | java.sql.SQLException ex) {
            releaseFreeze();
            failed = true;
            getLogger().log(Level.SEVERE, "Cannot persist reset; restart after fixing storage", ex);
            return;
        }
        Bukkit.broadcast(Component.text(text("Попытка #") + run + text(" завершена. "), NamedTextColor.RED).append(reason));
        Bukkit.broadcast(Component.text(text("Продержались: ") + duration(seconds) + text(". Создаётся новый мир…"), NamedTextColor.GOLD));
        String victimId = state.getString("pending-death-player");
        String victim = victimId == null ? text("Администратор") : Bukkit.getOfflinePlayer(UUID.fromString(victimId)).getName();
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.closeInventory();
            protect(p, 0);
            if (deathScene == null) deathScreen.show(p, victim == null ? text("Игрок") : victim, run);
            else deathScreen.showDeath(p, deathScene, run);
        }
        long delay = Math.max(0, Math.min(60, getConfig().getLong("reset-delay-seconds", 3))) * 20;
        if (deathScene != null) delay = Math.max(delay, deathScreen.sequenceTicks());
        for (long remaining = delay / 20; remaining > 0; remaining--) {
            long countdown = remaining;
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (!resetting || failed) return;
                for (Player p : Bukkit.getOnlinePlayers())
                    p.sendActionBar(Component.text(text("Новая попытка через ") + countdown + "…", NamedTextColor.GOLD));
            }, delay - remaining * 20 + 1);
        }
        Bukkit.getScheduler().runTaskLater(this, this::performReset, Math.max(1, delay));
    }

    private void performReset() {
        World[] old = worlds;
        try {
            holdFreeze();
            Path snapshot = prepareDeathSnapshot(old);
            long archivedAttempt = run;
            long next = Math.max(run, state.getLong("highest-run", run)) + 1;
            // Persist the candidate seed before generation so interrupted generation can resume.
            long seed = state.contains("next-seed") ? state.getLong("next-seed") : new Random().nextLong();
            state.set("next-seed", seed);
            saveState();
            World[] fresh = createWorlds(next, seed, old);
            startAtMorning(fresh[0]);
            RewardInbox.clearForNewAttempt(state);
            state.set("run", next);
            state.set("highest-run", next);
            state.set("seed", seed);
            state.set("seconds", 0L);
            state.set("reset-pending", false);
            state.set("next-seed", null);
            state.set("pending-death-player", null);
            state.set("rollback-revive", null);
            saveState();
            worlds = fresh;
            run = next;
            seconds = 0;
            database.start(worldId, run, seconds, seed);
            updateLoginTarget();
            for (Player p : Bukkit.getOnlinePlayers()) enterRun(p);
            for (World w : old) {
                if (!w.getPlayers().isEmpty() || !Bukkit.unloadWorld(w, true))
                    throw new IOException("Cannot archive loaded world: " + w.getName());
            }
            finishBackupAsync(snapshot, archivedAttempt, old);
            resetting = false;
            deathScene = null;
            releaseFreeze();
            Bukkit.broadcast(Component.text(text("Новая попытка #") + run + text(". Удачи!"), NamedTextColor.GREEN));
        } catch (Exception ex) {
            releaseFreeze();
            failed = true;
            getLogger().log(Level.SEVERE, "Reset failed. Fix the cause and restart; old worlds are retained.", ex);
            Bukkit.broadcast(Component.text(text("Ошибка сброса. Проверьте консоль и перезапустите сервер."), NamedTextColor.RED));
        }
    }

    private Path prepareDeathSnapshot(World[] old) throws IOException, org.bukkit.configuration.InvalidConfigurationException {
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.closeInventory();
            p.saveData();
        }
        YamlConfiguration restoreState = new YamlConfiguration();
        restoreState.loadFromString(state.saveToString());
        restoreState.set("reset-pending", false);
        restoreState.set("next-seed", null);
        restoreState.set("rollback-revive", state.getString("pending-death-player"));
        restoreState.set("pending-death-player", null);
        return deathBackups.prepare(restoreState, Arrays.stream(old).map(w -> w.getWorldFolder().toPath()).toList());
    }

    private void finishBackupAsync(Path snapshot, long archivedAttempt, World[] old) {
        backupsInProgress++;
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            boolean complete = false;
            try {
                deathBackups.finish(snapshot);
                complete = true;
            } catch (Exception ex) {
                getLogger().log(Level.SEVERE, "Death backup incomplete; original world directories retained", ex);
            }
            boolean ready = complete;
            if (!isEnabled()) return;
            Bukkit.getScheduler().runTask(this, () -> {
                backupsInProgress--;
                if (ready) {
                    getLogger().info("Death backup ready: " + snapshot.getFileName());
                    try {
                        database.backup(worldId, archivedAttempt, snapshot);
                    } catch (java.sql.SQLException ex) {
                        getLogger().log(Level.WARNING, "Cannot record backup in database", ex);
                    }
                    retire(old);
                }
            });
        });
    }

    private void enterRun(Player p) {
        if (failed || worlds == null) return;
        Long saved = p.getPersistentDataContainer().get(runKey, PersistentDataType.LONG);
        boolean fresh = saved == null || saved != run;
        if (fresh || !managed(p.getWorld())) {
            protect(p, 80);
            p.leaveVehicle();
            p.closeInventory();
            if (!p.teleport(worlds[0].getSpawnLocation())) {
                p.kick(Component.text(text("Не удалось перенести в новую попытку. Подключитесь снова.")));
                return;
            }
        }
        if (fresh) {
            p.getInventory().clear();
            p.getEnderChest().clear();
            p.setItemOnCursor(null);
            p.setExp(0);
            p.setLevel(0);
            p.setTotalExperience(0);
            p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));
            p.setHealth(maximumHealth(p));
            p.setFoodLevel(20);
            p.setSaturation(5);
            p.setExhaustion(0);
            p.setFireTicks(0);
            p.setFreezeTicks(0);
            p.setFallDistance(0);
            p.setRemainingAir(p.getMaximumAir());
            p.setVelocity(new org.bukkit.util.Vector());
            p.setRespawnLocation(null, true);
            // Insomnia is player data and survives world changes. Reset it only for a fresh attempt.
            p.setStatistic(Statistic.TIME_SINCE_REST, 0);
            Bukkit.advancementIterator().forEachRemaining(adv -> {
                var progress = p.getAdvancementProgress(adv);
                new ArrayList<>(progress.getAwardedCriteria()).forEach(progress::revokeCriteria);
            });
            p.getPersistentDataContainer().set(runKey, PersistentDataType.LONG, run);
            p.saveData();
            queueArrival(p);
        }
        p.getWorld().setHardcore(true);
        p.getWorld().setDifficulty(Difficulty.HARD);
    }

    @EventHandler
    public void onLogin(AsyncPlayerPreLoginEvent e) {
        if (failed)
            e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Component.text(text("Один шанс: ошибка, обратитесь к администратору.")));
        else if (resetting)
            e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Component.text(text("Создаётся новая попытка. Подключитесь через несколько секунд.")));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTask(this, () -> {
            Player p = e.getPlayer();
            if (!p.isOnline()) return;
            // The transition can start after pre-login was accepted but before this task runs.
            if (failed || resetting) {
                p.kick(Component.text(text("Один шанс: переход мира. Подключитесь через несколько секунд.")));
                return;
            }
            enterRun(p);
            if (!p.isOnline()) return;
            if (p.getUniqueId().toString().equals(state.getString("rollback-revive"))) {
                if (!p.teleport(worlds[0].getSpawnLocation())) {
                    p.kick(Component.text(text("Не удалось вернуть на спавн. Подключитесь снова.")));
                    return;
                }
                p.setHealth(maximumHealth(p));
                p.setFireTicks(0);
                p.setFallDistance(0);
                p.setFreezeTicks(0);
                p.setFoodLevel(20);
                p.setSaturation(5);
                p.setRemainingAir(p.getMaximumAir());
                protect(p, 200);
                p.saveData();
                state.set("rollback-revive", null);
                checkpoint();
                queueArrival(p);
            }
            sendStatus(p);
            microQuests.join(p);
            if (!pendingArrivals.containsKey(p.getUniqueId())) showAttemptTitle(p, false);
        });
    }

    private void updateLoginTarget() {
        loginTarget = new LoginTarget(Arrays.stream(worlds).map(World::getUID).collect(java.util.stream.Collectors.toUnmodifiableSet()),
            worlds[0].getSpawnLocation().clone());
    }

    static boolean hasApi(String name) {
        try {
            Class.forName(name, false, Main.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError unavailable) {
            return false;
        }
    }

    private Path levelDirectory() {
        try {
            return getServer().getLevelDirectory();
        } catch (NoSuchMethodError legacy) {
            return getServer().getWorlds().getFirst().getWorldFolder().toPath();
        }
    }

    private boolean forceSpawn(WorldCreator creator, boolean end) {
        try {
            creator.forcedSpawnPosition(end ? io.papermc.paper.math.Position.block(100, 49, 0)
                : io.papermc.paper.math.Position.block(0, 80, 0), 0, 0);
            return true;
        } catch (NoSuchMethodError legacy) {
            return false;
        }
    }

    private Location managedSpawn(Location original) {
        LoginTarget target = loginTarget;
        return target != null && !failed && !target.worlds().contains(original.getWorld().getUID())
            ? target.spawn().clone() : original;
    }

    private final class AsyncSpawn implements Listener {
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onSpawnLocation(AsyncPlayerSpawnLocationEvent event) {
            event.setSpawnLocation(managedSpawn(event.getSpawnLocation()));
        }
    }

    private final class LegacySpawn implements Listener {
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onSpawnLocation(org.spigotmc.event.player.PlayerSpawnLocationEvent event) {
            event.setSpawnLocation(managedSpawn(event.getSpawnLocation()));
        }
    }

    private final class ClientLoaded implements Listener {
        @EventHandler
        public void onClientLoaded(PlayerClientLoadedWorldEvent event) {
            completeArrival(event.getPlayer());
        }
    }

    private void queueArrival(Player p) {
        pendingArrivals.put(p.getUniqueId(), run);
        long expected = run;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (run == expected) completeArrival(p);
        }, 80);
    }

    private void completeArrival(Player p) {
        if (!p.isOnline()) return;
        Long expected = pendingArrivals.remove(p.getUniqueId());
        if (expected != null && expected == run && p.isOnline() && managed(p.getWorld())) effect(p);
    }

    private void showAttemptTitle(Player p, boolean fresh) {
        p.showTitle(Title.title(Component.text(text("Попытка #") + run, NamedTextColor.GOLD),
            Component.text(fresh ? text("Новый мир. Новая жизнь.") : text("Хардкор · ") + duration(seconds), NamedTextColor.WHITE),
            Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(3), Duration.ofMillis(1000))));
    }

    void sendStatus(CommandSender sender) {
        sender.sendMessage(Component.text(text("Хардкор · Попытка #") + run, NamedTextColor.GOLD)
            .append(Component.text(text(" · Время: ") + duration(seconds), NamedTextColor.WHITE)));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && (resetting || protectedPlayers.containsKey(p.getUniqueId())))
            e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onResetMove(PlayerMoveEvent e) {
        if (resetting && (e.getFrom().getX() != e.getTo().getX() || e.getFrom().getY() != e.getTo().getY() || e.getFrom().getZ() != e.getTo().getZ()))
            e.setTo(e.getFrom());
    }

    @EventHandler(ignoreCancelled = true)
    public void onResetInteract(PlayerInteractEvent e) {
        if (resetting) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onResetBreak(BlockBreakEvent e) {
        if (resetting) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onResetPlace(BlockPlaceEvent e) {
        if (resetting) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onResetClick(InventoryClickEvent e) {
        if (resetting) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onResetDrag(InventoryDragEvent e) {
        if (resetting) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onResetDrop(PlayerDropItemEvent e) {
        if (resetting) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onResetPickup(EntityPickupItemEvent e) {
        if (resetting && e.getEntity() instanceof Player) e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (microQuests != null) microQuests.leave(e.getPlayer());
        protectedPlayers.remove(e.getPlayer().getUniqueId());
        pendingArrivals.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent e) {
        if (worlds == null || failed) return;
        if (!managed(e.getRespawnLocation().getWorld())) e.setRespawnLocation(worlds[0].getSpawnLocation());
    }

    // Entities must not leak into the server's original dimensions via portals.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent e) {
        if (!managed(e.getFrom().getWorld())) return;
        if (resetting || failed) {
            e.setCancelled(true);
            return;
        }
        World from = e.getFrom().getWorld();
        if (e.getPortalType() == PortalType.NETHER) {
            World target = from == worlds[1] ? worlds[0] : worlds[1];
            double scale = target == worlds[1] ? 0.125 : 8;
            Location at = e.getFrom().clone();
            at.setWorld(target);
            at.setX(at.getX() * scale);
            at.setZ(at.getZ() * scale);
            e.setTo(at);
        } else if (e.getPortalType() == PortalType.ENDER) {
            e.setTo(from == worlds[2] ? worlds[0].getSpawnLocation() : new Location(worlds[2], 100.5, 49, 0.5));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent e) {
        if (!managed(e.getFrom().getWorld())) return;
        if (resetting || failed) {
            e.setCancelled(true);
            return;
        }
        World from = e.getFrom().getWorld();
        if (e.getCause() == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL) {
            World target = from == worlds[1] ? worlds[0] : worlds[1];
            double scale = target == worlds[1] ? 0.125 : 8;
            Location at = e.getFrom().clone();
            at.setWorld(target);
            at.setX(at.getX() * scale);
            at.setZ(at.getZ() * scale);
            at.setY(Math.max(target.getMinHeight() + 5, Math.min(target.getMaxHeight() - 10, at.getY())));
            e.setTo(at);
            e.setCanCreatePortal(true);
        } else if (e.getCause() == PlayerTeleportEvent.TeleportCause.END_PORTAL) {
            if (from == worlds[2]) e.setTo(worlds[0].getSpawnLocation());
            else {
                World end = worlds[2];
                for (int x = 98; x <= 102; x++)
                    for (int z = -2; z <= 2; z++) {
                        end.getBlockAt(x, 48, z).setType(Material.OBSIDIAN);
                        for (int y = 49; y <= 52; y++) end.getBlockAt(x, y, z).setType(Material.AIR);
                    }
                e.setTo(new Location(end, 100.5, 49, 0.5, 90, 0));
            }
        }
    }

    private void retire(World[] old) {
        for (World world : old) {
            if (managed(world)) continue;
            if (Bukkit.getWorld(world.getUID()) != null && (!world.getPlayers().isEmpty() || !Bukkit.unloadWorld(world, true))) {
                getLogger().warning("World could not be unloaded: " + world.getName());
                continue;
            }
            if (!getConfig().getBoolean("delete-old-worlds", true)) continue;
            try {
                WorldFiles.delete(Bukkit.getWorldContainer().toPath(), world.getWorldFolder().toPath(), worldId,
                    Arrays.stream(worlds).map(w -> w.getWorldFolder().toPath()).collect(java.util.stream.Collectors.toSet()),
                    levelDirectory().resolve("dimensions/minecraft"));
                database.deleted(world.getKey().toString());
                getLogger().info("Deleted archived world directory: " + world.getWorldFolder());
            } catch (IOException | java.sql.SQLException ex) {
                getLogger().log(Level.WARNING, "Old world retained or history update failed", ex);
            }
        }
    }

    double setting(Player p, String key) {
        return state.getDouble("players." + p.getUniqueId() + "." + key, getConfig().getDouble("effects." + key));
    }

    private List<Path> oldWorldDirectories() throws IOException {
        Set<Path> result = new LinkedHashSet<>();
        for (Path parent : List.of(Bukkit.getWorldContainer().toPath(), levelDirectory().resolve("dimensions/minecraft"))) {
            if (!Files.isDirectory(parent)) continue;
            try (var children = Files.list(parent)) {
                children.filter(Files::isDirectory).filter(p -> p.getFileName().toString().matches("hcr_" + worldId + "_[0-9]+(_nether|_the_end)?"))
                    .map(p -> p.toAbsolutePath().normalize()).forEach(result::add);
            }
        }
        return List.copyOf(result);
    }

    private void registerDirectory(Path path) throws java.sql.SQLException {
        String name = path.getFileName().toString();
        String number = name.substring(("hcr_" + worldId + "_").length()).split("_")[0];
        database.world(worldId, Long.parseLong(number), "minecraft:" + name, path);
    }

    record CleanupResult(int eligible, int retained) {
    }

    void cleanup(CommandSender sender, boolean confirmed) throws IOException, java.sql.SQLException {
        CleanupResult report = menuCleanup(confirmed);
        sender.sendMessage((confirmed ? text("Удалено каталогов: ") : text("Можно удалить каталогов: ")) + report.eligible() + text(". Сохранено без копии: ") + report.retained() + ".");
        if (!confirmed)
            sender.sendMessage(text("/hcr cleanup confirm — удалить только выгруженные старые миры с готовой копией для отката."));
    }

    CleanupResult menuCleanup(boolean confirmed) throws IOException, java.sql.SQLException {
        if (isTransitioning()) throw new IOException("World transition or backup in progress");
        Set<Path> backedUp = new HashSet<>();
        for (Path snapshot : deathBackups.list()) {
            var metadata = deathBackups.manifest(snapshot);
            if (!worldId.equals(metadata.getString("owner"))) continue;
            for (String path : metadata.getStringList("worlds"))
                backedUp.add(Path.of(path).toAbsolutePath().normalize());
        }
        Set<Path> loaded = Bukkit.getWorlds().stream().map(w -> w.getWorldFolder().toPath().toAbsolutePath().normalize()).collect(java.util.stream.Collectors.toSet());
        int eligible = 0, retained = 0;
        for (Path path : oldWorldDirectories()) {
            if (loaded.contains(path)) continue;
            registerDirectory(path);
            if (!backedUp.contains(path)) {
                retained++;
                continue;
            }
            eligible++;
            if (confirmed) {
                WorldFiles.delete(Bukkit.getWorldContainer().toPath(), path, worldId, loaded,
                    levelDirectory().resolve("dimensions/minecraft"));
                database.deleted("minecraft:" + path.getFileName());
            }
        }
        return new CleanupResult(eligible, retained);
    }

    String ownerId() {
        return worldId;
    }

    long activeAttempt() {
        return run;
    }

    long attemptSeconds() {
        return seconds;
    }

    boolean hasFailed() {
        return failed;
    }

    boolean canTrack(World world) {
        return !failed && !resetting && managed(world);
    }

    boolean isTransitioning() {
        return failed || resetting || backupsInProgress > 0;
    }

    void flushActivity() throws java.sql.SQLException {
        activity.flush();
        database.progress(worldId, run, seconds);
    }

    void requireAdmin(CommandSender player) {
        if (!player.hasPermission("hardcorereset.admin"))
            throw new SecurityException("Administrative permission required");
    }

    void previewDeath(Player player) {
        deathScreen.show(player, player.getName(), run);
    }

    void previewScene(Player player, DeathScene scene) {
        deathScreen.showDeath(player, DeathSceneContext.preview(player, scene), run);
    }

    void previewMobSound(Player player) { rareMobSounds.play(player); }

    void microQuestProgress(Player player, ActivityMetric metric, long value) {
        if (microQuests != null) microQuests.observe(player, metric, value);
    }

    void microQuestCompleted(Player player) {
        activity.questCompleted(player);
    }

    void saveQuestState() throws IOException {
        state.set("seconds", seconds);
        saveState();
    }

    MicroQuests.Info microQuestInfo(Player player) {
        return microQuests.info(player);
    }

    String skipMicroQuest(Player player) {
        return microQuests.skip(player);
    }

    String toggleMicroQuest(Player player) {
        return microQuests.toggle(player);
    }

    boolean questBarVisible(Player player) {
        return microQuests.isVisible(player);
    }

    void storeQuestReward(Player player, String reward) throws IOException {
        rewardInbox.deposit(player.getUniqueId(), reward);
    }

    List<RewardInbox.Entry> questRewards(Player player) {
        return rewardInbox.list(player.getUniqueId());
    }

    String claimReward(Player player, String id) throws IOException {
        if (!canTrack(player.getWorld()) || player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR)
            return text("Забрать награду можно во время игры в текущей попытке.");
        var entry = questRewards(player).stream().filter(e -> e.id().equals(id)).findFirst().orElse(null);
        if (entry == null) return text("Эта награда уже забрана или началась новая попытка.");
        var prize = QuestReward.item(entry.reward(), this::text);
        // Each prize is a single item. An empty storage slot guarantees room without dropping leftovers.
        if (player.getInventory().firstEmpty() < 0) return text("Освободите хотя бы один слот. Награда остаётся здесь.");
        if (rewardInbox.claim(player.getUniqueId(), id) == null) return text("Награда уже забрана.");
        player.getInventory().addItem(prize);
        player.saveData();
        player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, .4f, 1.3f);
        return text("Награда перемещена в ваш инвентарь.");
    }

    void adminReset(CommandSender player) {
        requireAdmin(player);
        if (isTransitioning()) throw new IllegalStateException("Transition in progress");
        state.set("pending-death-player", null);
        deathScene = null;
        beginReset(Component.text(text("Сброс администратором: ") + player.getName()));
    }

    void menuRollback(CommandSender player, String snapshot) throws IOException {
        requireAdmin(player);
        if (isTransitioning()) throw new IOException("Transition or backup in progress");
        Path selected = deathBackups.list().stream().filter(p -> p.getFileName().toString().equals(snapshot)).findFirst().orElseThrow(() -> new IOException("Backup not found"));
        if (!worldId.equals(deathBackups.manifest(selected).getString("owner")))
            throw new IOException("Backup belongs to another world set");
        deathBackups.request(snapshot);
        resetting = true;
        Bukkit.broadcast(Component.text(text("Подготовлен откат. Сервер останавливается; подключитесь после запуска."), NamedTextColor.GOLD));
        Bukkit.getScheduler().runTask(this, Bukkit::shutdown);
    }

    void effect(Player p) {
        long expectedRun = run;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!p.isOnline() || run != expectedRun) return;
            if (getConfig().getBoolean("effects.title", true)) showAttemptTitle(p, true);
            int fade = getConfig().getBoolean("effects.blindness", true) ? (int) setting(p, "fade") : 0;
            if (fade > 0) p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, fade, 0, false, false, false));
            arrivalStage(p, "start", "block.respawn_anchor.charge", Particle.TOTEM_OF_UNDYING, 45, .5, .7, .1);
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (p.isOnline() && run == expectedRun && managed(p.getWorld()))
                    arrivalStage(p, "finish", "block.amethyst_block.chime", Particle.END_ROD, 24, .7, .8, .02);
            }, Math.clamp(getConfig().getInt("effects.finish.delay-ticks", Math.max(10, fade)), 1, 1200));
        }, Math.max(1, (int) setting(p, "delay")));
    }

    private void arrivalStage(Player player, String stage, String defaultSound, Particle defaultParticle,
                              int defaultCount, double width, double height, double speed) {
        var config = getConfig();
        String path = "effects." + stage;
        String sound = config.getString(path + ".sound", "minecraft:" + defaultSound);
        if (!sound.isBlank()) player.playSound(player.getLocation(), sound,
            (float) setting(player, "volume"), (float) setting(player, "pitch"));
        String key = config.getString(path + ".particle");
        NamespacedKey id = key == null ? null : NamespacedKey.fromString(key);
        Particle particle = key == null ? defaultParticle : id == null ? null : Registry.PARTICLE_TYPE.get(id);
        int count = Math.clamp(config.getInt(path + ".count", defaultCount), 0, 500);
        if (particle != null && particle.getDataType() == Void.class && count > 0)
            player.spawnParticle(particle, player.getLocation().add(0, 1, 0), count,
                config.getDouble(path + ".spread-x", width), config.getDouble(path + ".spread-y", height),
                config.getDouble(path + ".spread-z", width), config.getDouble(path + ".speed", speed));
    }

    String duration(long t) {
        return text("%dч %02dм %02dс").formatted(t / 3600, t / 60 % 60, t % 60);
    }

    @Override
    public boolean onCommand(@org.jetbrains.annotations.NotNull CommandSender sender,
                             @org.jetbrains.annotations.NotNull Command command,
                             @org.jetbrains.annotations.NotNull String label,
                             @org.jetbrains.annotations.NotNull String[] args) {
        sender.sendMessage(text("Один шанс: запуск не завершён. Проверьте консоль."));
        return true;
    }
}
