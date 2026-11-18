import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Level;

/**
 * Parses commands separately from world transitions and the player lifecycle.
 */
final class PluginCommands implements CommandExecutor, TabCompleter {
    private final Main plugin;
    private final PluginMenus menus;
    private final RunDatabase database;
    private final DeathBackups deathBackups;
    private final MicroQuests microQuests;
    private final YamlConfiguration state;

    PluginCommands(Main plugin, PluginMenus menus, RunDatabase database, DeathBackups deathBackups,
                   MicroQuests microQuests, YamlConfiguration state) {
        this.plugin = plugin;
        this.menus = menus;
        this.database = database;
        this.deathBackups = deathBackups;
        this.microQuests = microQuests;
        this.state = state;
    }

    private List<Path> currentBackups() throws IOException {
        List<Path> result = new ArrayList<>();
        for (Path path : deathBackups.list()) {
            if (plugin.ownerId().equals(deathBackups.manifest(path).getString("owner"))) result.add(path);
        }
        return result;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        try {
            return execute(sender, command, args);
        } catch (SecurityException ex) {
            sender.sendMessage(plugin.text("Недостаточно прав."));
        } catch (IOException | IllegalStateException ex) {
            plugin.getLogger().log(Level.WARNING, "Command failed", ex);
            sender.sendMessage(plugin.text("Не удалось выполнить действие. Подробности в консоли."));
        }
        return true;
    }

    private boolean execute(CommandSender sender, Command command, String[] args) throws IOException {
        if (plugin.hasFailed()) {
            sender.sendMessage(plugin.text("Один шанс: ошибка, проверьте консоль."));
            return true;
        }
        switch (command.getName()) {
            case "quest" -> quest(sender, args);
            case "stats" -> {
                if (sender instanceof Player player) menus.stats(player, null);
                else sender.sendMessage(plugin.text("Меню статистики доступно в игре."));
            }
            case "survived" -> plugin.sendStatus(sender);
            case "try" ->
                sender.sendMessage(Component.text(plugin.text("Текущая попытка: #") + plugin.activeAttempt(), NamedTextColor.GOLD));
            case "hcr" -> hardcore(sender, args);
            default -> {
                return false;
            }
        }
        return true;
    }

    private boolean quest(CommandSender sender, String[] args) {
        if (sender instanceof Player p) {
            if (args.length > 0 && args[0].equalsIgnoreCase("rewards")) {
                menus.rewards(p, 0, null);
                return true;
            }
            String notice = args.length == 0 ? null : switch (args[0].toLowerCase(Locale.ROOT)) {
                case "skip" -> plugin.skipMicroQuest(p);
                case "hide", "off" -> microQuests.visibility(p, false);
                case "show", "on" -> microQuests.visibility(p, true);
                case "toggle" -> plugin.toggleMicroQuest(p);
                default -> null;
            };
            menus.quest(p, notice);
        } else sender.sendMessage(plugin.text("Личные задания доступны в игре."));
        return true;
    }

    private void hardcore(CommandSender sender, String[] args) throws IOException {
        String action = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (sender instanceof Player p) {
            if (List.of("", "stats", "menu").contains(action)) {
                menus.stats(p, null);
                return;
            }
            if (action.equals("quest")) {
                menus.quest(p, null);
                return;
            }
            if (action.equals("rewards")) {
                menus.rewards(p, 0, null);
                return;
            }
            if (action.equals("settings")) {
                menus.settings(p, null);
                return;
            }
            if (action.equals("deathtest")) {
                plugin.previewDeath(p);
                return;
            }
            if (action.equals("soundtest")) {
                plugin.previewMobSound(p);
                return;
            }
            if (action.equals("scenetest")) {
                if (args.length > 1 && args[1].equalsIgnoreCase("list")) {
                    sender.sendMessage(plugin.text("Причины: ") + String.join(", ", Arrays.stream(DeathScene.values()).map(scene -> scene.name().toLowerCase(Locale.ROOT)).toList()));
                } else {
                    try {
                        DeathScene scene = args.length > 1 ? DeathScene.valueOf(args[1].toUpperCase(Locale.ROOT)) : DeathScene.PROJECTILE;
                        plugin.previewScene(p, scene);
                    } catch (IllegalArgumentException ex) {
                        sender.sendMessage(plugin.text("/hcr scenetest list — доступные миниатюры."));
                    }
                }
                return;
            }
            if (action.equals("admin")) {
                menus.admin(p);
                return;
            }
            if (action.equals("history")) {
                if (args.length == 2) try {
                    menus.attempt(p, Long.parseLong(args[1]));
                } catch (NumberFormatException ex) {
                    menus.best(p, 0);
                }
                else menus.best(p, 0);
                return;
            }
            if (action.equals("backups") || (action.equals("rollback") && args.length == 1)) {
                menus.backups(p, 0);
                return;
            }
            if (action.equals("cleanup") && args.length == 1) {
                menus.admin(p);
                return;
            }
        }
        if (action.equals("history")) {
            try {
                Long number = args.length == 2 ? Long.parseLong(args[1]) : null;
                var entries = database.history(plugin.ownerId(), number);
                if (entries.isEmpty()) sender.sendMessage(plugin.text("История попыток пуста."));
                for (var entry : entries) {
                    String status = switch (entry.status()) {
                        case "active" -> plugin.text("идёт");
                        case "ended" -> plugin.text("завершён");
                        case "interrupted" -> plugin.text("прерван");
                        default -> plugin.text("найден на диске");
                    };
                    sender.sendMessage(plugin.text("Трай #") + entry.attempt() + " · " + (entry.seconds() < 0 ? plugin.text("время неизвестно") : plugin.duration(entry.seconds())) + " · " + status);
                    if (number != null) {
                        sender.sendMessage(plugin.text("Миры: ") + String.join(", ", entry.worlds()).replace("[present]", plugin.text("[на диске]")).replace("[deleted]", plugin.text("[удалён]")));
                        if (entry.reason() != null) sender.sendMessage(plugin.text("Завершение: ") + entry.reason());
                        sender.sendMessage(plugin.text("Копия для отката: ") + (entry.backup() == null ? plugin.text("нет записи") : plugin.text("есть")));
                    }
                }
                if (number == null) sender.sendMessage(plugin.text("/hcr history <номер> — миры и подробности попытки."));
            } catch (NumberFormatException ex) {
                sender.sendMessage(plugin.text("/hcr history [номер попытки]"));
            } catch (java.sql.SQLException ex) {
                plugin.getLogger().log(Level.WARNING, "Cannot read history", ex);
                sender.sendMessage(plugin.text("Ошибка чтения истории."));
            }
            return;
        }
        if (action.equals("cleanup")) {
            if (!sender.hasPermission("hardcorereset.admin")) {
                sender.sendMessage(plugin.text("Недостаточно прав."));
                return;
            }
            if (plugin.isTransitioning()) {
                sender.sendMessage(plugin.text("Дождитесь завершения сохранения."));
                return;
            }
            try {
                plugin.cleanup(sender, args.length == 2 && args[1].equalsIgnoreCase("confirm"));
            } catch (IOException | java.sql.SQLException ex) {
                plugin.getLogger().log(Level.WARNING, "Cleanup failed", ex);
                sender.sendMessage(plugin.text("Ошибка очистки: ") + ex.getMessage());
            }
            return;
        }
        if (action.equals("backups") || action.equals("rollback")) {
            if (!sender.hasPermission("hardcorereset.admin")) {
                sender.sendMessage(plugin.text("Недостаточно прав."));
                return;
            }
            try {
                if (action.equals("backups")) {
                    List<Path> snapshots = currentBackups();
                    if (snapshots.isEmpty()) sender.sendMessage(plugin.text("Сохранений смерти пока нет."));
                    for (Path snapshot : snapshots.stream().limit(10).toList()) {
                        var info = deathBackups.manifest(snapshot);
                        sender.sendMessage(plugin.text("Трай #") + info.getLong("run") + " · " + plugin.duration(info.getLong("seconds")) + " · " + info.getString("created"));
                    }
                } else if (plugin.isTransitioning())
                    sender.sendMessage(plugin.text("Дождитесь завершения сброса и сохранения копии."));
                else if (args.length != 2 || !args[1].equalsIgnoreCase("confirm"))
                    sender.sendMessage(plugin.text("/hcr rollback confirm — вернуть последнее сохранение смерти. Сервер остановится; затем запустите его снова. Текущий прогресс будет заменён."));
                else {
                    Path latest = currentBackups().stream().findFirst().orElseThrow(() -> new IOException("No death backups available"));
                    plugin.menuRollback(sender, latest.getFileName().toString());
                }
            } catch (IOException ex) {
                sender.sendMessage(plugin.text("Не удалось выполнить операцию с копией: ") + ex.getMessage());
                plugin.getLogger().log(Level.WARNING, "Death backup operation failed", ex);
            }
            return;
        }
        if (action.isEmpty() || List.of("status", "time", "try").contains(action)) {
            plugin.sendStatus(sender);
            sender.sendMessage("/try · /trytime · /hcr test · /hcr volume|pitch|delay|fade <число> · /hcr reset");
            if (sender instanceof Player p)
                sender.sendMessage("volume=" + plugin.setting(p, "volume") + ", pitch=" + plugin.setting(p, "pitch")
                    + ", delay=" + plugin.setting(p, "delay") + ", fade=" + plugin.setting(p, "fade"));
            return;
        }
        if (action.equals("forcereset")) {
            if (!sender.hasPermission("hardcorereset.admin")) sender.sendMessage(plugin.text("Недостаточно прав."));
            else plugin.adminReset(sender);
            return;
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage(plugin.text("Личные настройки доступны в игре."));
            return;
        }
        if (action.equals("test")) {
            plugin.effect(p);
            return;
        }
        if (action.equals("reset")) {
            saveSetting("players." + p.getUniqueId(), null);
            sender.sendMessage(plugin.text("Личные настройки восстановлены."));
            return;
        }
        if (List.of("volume", "pitch", "delay", "fade").contains(action) && args.length == 2) {
            try {
                double value = Double.parseDouble(args[1]);
                double min = action.equals("pitch") ? 0.5 : 0;
                double max = List.of("delay", "fade").contains(action) ? 200 : 2;
                if (!Double.isFinite(value) || value < min || value > max || (max == 200 && value != Math.floor(value)))
                    throw new NumberFormatException();
                saveSetting("players." + p.getUniqueId() + "." + action, value);
                sender.sendMessage(action + " = " + value);
            } catch (NumberFormatException ex) {
                sender.sendMessage(plugin.text("Диапазон: volume 0–2, pitch 0.5–2, delay/fade 0–200 целых тиков."));
            }
            return;
        }
        sender.sendMessage("/survived; /hcr test|reset; /hcr volume|pitch|delay|fade <число>");
        sender.sendMessage("volume=" + plugin.setting(p, "volume") + ", pitch=" + plugin.setting(p, "pitch") + ", delay=" + plugin.setting(p, "delay") + ", fade=" + plugin.setting(p, "fade"));
        return;
    }

    private void saveSetting(String key, Object value) throws IOException {
        Object previous = state.get(key);
        if (previous instanceof org.bukkit.configuration.ConfigurationSection section)
            previous = section.getValues(true);
        state.set(key, value);
        try {
            plugin.saveQuestState();
        } catch (IOException ex) {
            if (previous instanceof Map<?, ?> values) state.createSection(key, values);
            else state.set(key, previous);
            throw ex;
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String @NotNull [] args) {
        if (command.getName().equals("hcr") && args.length == 2 && args[0].equalsIgnoreCase("scenetest")) {
            return java.util.stream.Stream.concat(java.util.stream.Stream.of("list"), Arrays.stream(DeathScene.values()).map(scene -> scene.name().toLowerCase(Locale.ROOT)))
                .filter(name -> name.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (command.getName().equals("quest"))
            return args.length == 1 ? java.util.stream.Stream.of("skip", "hide", "show", "toggle", "rewards").filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList() : List.of();
        if (command.getName().equals("hcr") && args.length == 2 && List.of("rollback", "cleanup").contains(args[0].toLowerCase(Locale.ROOT)) && sender.hasPermission("hardcorereset.admin"))
            return "confirm".startsWith(args[1].toLowerCase(Locale.ROOT)) ? List.of("confirm") : List.of();
        if (!command.getName().equals("hcr") || args.length != 1) return List.of();
        List<String> options = new ArrayList<>(List.of("quest", "rewards", "settings", "stats", "menu", "status", "time", "try", "history", "test", "deathtest", "scenetest", "soundtest", "reset", "volume", "pitch", "delay", "fade"));
        if (sender.hasPermission("hardcorereset.admin"))
            options.addAll(List.of("admin", "forcereset", "backups", "rollback", "cleanup"));
        return options.stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
    }
}
