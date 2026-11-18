import java.nio.file.Path;
import java.util.*;
import java.util.logging.Level;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;

final class PluginMenus implements Listener {
    @FunctionalInterface
    private interface Action {
        void run(Player player) throws Exception;
    }

    private final class Screen implements InventoryHolder {
        private Inventory inventory;
        private final UUID viewer;
        private final Map<Integer, Action> actions = new HashMap<>();
        private Runnable refresh;

        private final String path;
        private final int[] positions = new int[54];

        Screen(UUID viewer, String id) {
            this.viewer = viewer;
            path = "menus." + id;
            Arrays.setAll(positions, slot -> slot);
            for (String swap : plugin.getConfig().getStringList(path + ".swaps")) {
                String[] pair = swap.trim().split("\\s*:\\s*");
                try {
                    if (pair.length != 2) throw new IllegalArgumentException();
                    int first = Objects.checkIndex(Integer.parseInt(pair[0]), positions.length);
                    int second = Objects.checkIndex(Integer.parseInt(pair[1]), positions.length);
                    int previous = positions[first];
                    positions[first] = positions[second];
                    positions[second] = previous;
                } catch (IllegalArgumentException | IndexOutOfBoundsException invalid) {
                    plugin.getLogger().warning("Invalid slot swap in " + path + ": " + swap);
                }
            }
        }

        void put(int slot, ItemStack stack) {
            String settings = path + ".items." + slot;
            var config = plugin.getConfig();
            if (!config.getBoolean(settings + ".enabled", true)) stack = null;
            if (stack != null && config.isConfigurationSection(settings)) {
                Material icon = Material.matchMaterial(config.getString(settings + ".material", ""));
                if (icon != null && icon.isItem() && !icon.isAir() && icon != stack.getType()) {
                    ItemStack replacement = new ItemStack(icon, stack.getAmount());
                    replacement.setItemMeta(stack.getItemMeta());
                    stack = replacement;
                }
                ItemMeta meta = stack.getItemMeta();
                if (config.isString(settings + ".name")) {
                    var existing = meta.displayName();
                    String original = existing == null ? "" : net.kyori.adventure.text.serializer.plain
                        .PlainTextComponentSerializer.plainText().serialize(existing);
                    meta.displayName(Localized.colored(config.getString(settings + ".name")
                        .replace("{name}", original), NamedTextColor.GOLD));
                }
                if (config.isList(settings + ".lore")) {
                    meta.lore(config.getStringList(settings + ".lore").stream()
                        .map(line -> Localized.colored(line, NamedTextColor.GRAY)).toList());
                }
                stack.setItemMeta(meta);
            }
            inventory.setItem(positions[slot], stack);
        }

        @Override
        public @org.jetbrains.annotations.NotNull Inventory getInventory() {
            return inventory;
        }
    }

    private final Main plugin;
    private final RunDatabase database;
    private final DeathBackups backups;

    PluginMenus(Main plugin, RunDatabase database, DeathBackups backups) {
        this.plugin = plugin;
        this.database = database;
        this.backups = backups;
    }

    private Screen screen(Player player, String id, String title) {
        Screen view = new Screen(player.getUniqueId(), id);
        view.inventory = Bukkit.createInventory(view, 54, Localized.colored(plugin.getConfig().getString(view.path + ".title", title).replace("{title}", title), NamedTextColor.DARK_GRAY));
        for (int i = 45; i < 54; i++) view.put(i, item(Material.BLACK_STAINED_GLASS_PANE, " "));
        button(view, 49, Material.NETHER_STAR, plugin.text("Главное меню"), p -> stats(p, null));
        button(view, 53, Material.BARRIER, plugin.text("Закрыть"), Player::closeInventory);
        return view;
    }

    private ItemStack item(Material material, String label, String... description) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(label, NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        meta.lore(Arrays.stream(description).map(s -> Component.text(s, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)).toList());
        stack.setItemMeta(meta);
        return stack;
    }

    private void button(Screen screen, int slot, Material icon, String title, Action action, String... lore) {
        screen.put(slot, item(icon, title, lore));
        screen.actions.put(screen.positions[slot], action);
    }

    private String scope(Long attempt) {
        return attempt == null ? plugin.text("Все попытки") : plugin.text("Трай #") + attempt;
    }

    private void scopeButton(Screen view, Long attempt) {
        button(view, 47, Material.COMPASS, scope(attempt), p -> stats(p, attempt == null ? plugin.activeAttempt() : null), plugin.text("Нажмите: все / текущая попытка"));
    }

    private void open(Player player, Screen screen) {
        player.openInventory(screen.inventory);
    }

    void refresh() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof Screen screen
                    && screen.viewer.equals(player.getUniqueId()) && screen.refresh != null) {
                guarded(player, ignored -> screen.refresh.run());
            }
        }
    }

    private void frame(Screen screen) {
        for (int i = 0; i < 45; i++) screen.put(i, item(Material.GRAY_STAINED_GLASS_PANE, " "));
        for (int i = 10; i <= 34; i++) if (i % 9 != 0 && i % 9 != 8) screen.put(i, null);
    }

    private void guarded(Player player, Action action) {
        try {
            action.run(player);
        } catch (SecurityException ex) {
            message(player, plugin.text("Недостаточно прав"), plugin.text("Нужен OP или право hardcorereset.admin."));
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "Menu operation failed", ex);
            Screen error = screen(player, "error", plugin.text("Не удалось выполнить действие"));
            error.put(22, item(Material.REDSTONE_BLOCK, plugin.text("Действие не выполнено"), plugin.text("Попробуйте после завершения перехода."), plugin.text("Подробности записаны в консоль сервера.")));
            open(player, error);
        }
    }

    void stats(Player player, Long attempt) {
        guarded(player, p -> {
            plugin.flushActivity();
            Screen view = screen(p, "stats", plugin.text("Хардкор • ") + scope(attempt));
            frame(view);
            view.put(4, item(Material.CLOCK, plugin.text("Сейчас трай #") + plugin.activeAttempt(), plugin.duration(plugin.attemptSeconds()), plugin.text("Сложность: HARD • Хардкор")));
            button(view, 10, Material.GOLDEN_HELMET, plugin.text("Лучшие траи"), q -> best(q, 0), plugin.text("Рейтинг по максимальной длительности"), plugin.text("Рекорды сохраняются после откатов"));
            button(view, 12, Material.PLAYER_HEAD, plugin.text("Игроки"), q -> leaders(q, attempt, ActivityMetric.PLAY_SECONDS, 0), plugin.text("Время игры и личные профили"));
            button(view, 14, Material.SKELETON_SKULL, plugin.text("Кто чаще умирал"), q -> leaders(q, attempt, ActivityMetric.DEATHS, 0));
            button(view, 16, Material.DIAMOND_PICKAXE, plugin.text("Шахтёры"), q -> leaders(q, attempt, ActivityMetric.ORES_MINED, 0), plugin.text("Добыча руды"));
            button(view, 20, Material.IRON_PICKAXE, plugin.text("Добыча блоков"), q -> leaders(q, attempt, ActivityMetric.BLOCKS_MINED, 0));
            button(view, 22, Material.BRICKS, plugin.text("Строители"), q -> leaders(q, attempt, ActivityMetric.BLOCKS_PLACED, 0));
            button(view, 24, Material.IRON_SWORD, plugin.text("Охотники"), q -> leaders(q, attempt, ActivityMetric.MOBS_KILLED, 0));
            button(view, 29, Material.FISHING_ROD, plugin.text("Рыболовы"), q -> leaders(q, attempt, ActivityMetric.FISH_CAUGHT, 0));
            button(view, 30, Material.COOKIE, plugin.text("Микро-подвиги"), q -> leaders(q, attempt, ActivityMetric.QUESTS_COMPLETED, 0));
            button(view, 31, Material.EXPERIENCE_BOTTLE, plugin.text("Достижения"), q -> leaders(q, attempt, ActivityMetric.ADVANCEMENTS, 0));
            button(view, 33, Material.LEATHER_BOOTS, plugin.text("Путешественники"), q -> leaders(q, attempt, ActivityMetric.DISTANCE_CM, 0));
            button(view, 40, Material.BOOK, plugin.text("Моя статистика"), q -> profile(q, attempt, q.getUniqueId().toString(), q.getName()));
            button(view, 38, Material.JUKEBOX, plugin.text("Проверить экран смерти"), q -> {
                q.closeInventory();
                plugin.previewDeath(q);
            }, plugin.text("Только для вас • Без сброса мира"));
            button(view, 39, Material.WRITABLE_BOOK, plugin.text("Моё мини-задание"), q -> quest(q, null), plugin.text("Босс-бар с личным прогрессом"), plugin.text("Символическая награда за суету"));
            button(view, 41, Material.CHEST, plugin.text("Мои награды • ") + plugin.questRewards(p).size(), q -> rewards(q, 0, null), plugin.text("Призы хранятся здесь, пока вы их не заберёте"), plugin.text("При новой попытке хранилище очистится"));
            button(view, 43, Material.COMPARATOR, plugin.text("Настройки интерфейса"), q -> settings(q, null), plugin.text("Показать или скрыть босс-бар"));
            if (p.hasPermission("hardcorereset.admin"))
                button(view, 51, Material.REDSTONE, plugin.text("Панель администратора"), this::admin);
            scopeButton(view, attempt);
            open(p, view);
        });
    }

    private Material icon(ActivityMetric metric) {
        return switch (metric) {
            case DEATHS -> Material.SKELETON_SKULL;
            case PLAY_SECONDS -> Material.CLOCK;
            case BLOCKS_MINED -> Material.IRON_PICKAXE;
            case ORES_MINED -> Material.DIAMOND;
            case BLOCKS_PLACED -> Material.BRICKS;
            case MOBS_KILLED -> Material.IRON_SWORD;
            case FISH_CAUGHT -> Material.COD;
            case ADVANCEMENTS -> Material.EXPERIENCE_BOTTLE;
            case DISTANCE_CM -> Material.LEATHER_BOOTS;
            case QUESTS_COMPLETED -> Material.COOKIE;
            case HOSTILES_KILLED -> Material.ZOMBIE_HEAD;
            case DIAMOND_ORES_MINED -> Material.DIAMOND_ORE;
        };
    }

    void quest(Player player, String notice) {
        guarded(player, p -> {
            Screen view = screen(p, "quest", plugin.text("Микро-задание • Ради интереса"));
            frame(view);
            view.refresh = () -> {
                var info = plugin.microQuestInfo(p);
                if (info == null) {
                    view.put(13, item(Material.PAPER, plugin.text("Задания сейчас недоступны"), plugin.text("Нужен Survival в мире текущей попытки."), plugin.text("Администратор также может отключить задания.")));
                    view.put(20, null);
                    view.actions.remove(view.positions[20]);
                    view.put(24, null);
                    view.actions.remove(view.positions[24]);
                } else {
                    view.put(13, item(Material.WRITABLE_BOOK, info.title(), info.goal(), plugin.text("Прогресс: ") + info.progress(), plugin.text("Награда: 1 × ") + info.reward(), plugin.text("До нового задания: ") + QuestCycle.time(info.remainingSeconds()),
                            info.completed() ? plugin.text("Готово! Награда ждёт в хранилище.") : info.skipped() ? plugin.text("Пропущено. Ожидайте окончания таймера.") : plugin.text("Успейте до конца отсчёта.")));
                    button(view, 20, info.waiting() ? Material.CLOCK : Material.FEATHER, info.waiting() ? plugin.text("Ожидание следующего задания") : plugin.text("Пропустить задание"), q -> quest(q, plugin.skipMicroQuest(q)), plugin.text("Награды за пропуск нет."), plugin.text("Пропуск не ускоряет следующий цикл."));
                    button(view, 24, info.visible() ? Material.LIME_DYE : Material.GRAY_DYE, info.visible() ? plugin.text("Скрыть босс-бар") : plugin.text("Показать босс-бар"), q -> quest(q, plugin.toggleMicroQuest(q)), plugin.text("Прогресс считается и со скрытым босс-баром."));
                }
                button(view, 40, Material.CHEST, plugin.text("Хранилище наград • ") + plugin.questRewards(p).size(), q -> rewards(q, 0, null), plugin.text("Заберите только то, что вам нужно."), plugin.text("Содержимое исчезнет при новой попытке."));
            };
            view.refresh.run();
            button(view, 45, Material.COMPARATOR, plugin.text("Настройки интерфейса"), q -> settings(q, null));
            if (notice != null) view.put(31, item(Material.PAPER, plugin.text("Мини-задания"), notice));
            open(p, view);
        });
    }

    void settings(Player player, String notice) {
        Screen view = screen(player, "settings", plugin.text("Хардкор • Настройки интерфейса"));
        frame(view);
        boolean visible = plugin.questBarVisible(player);
        button(view, 22, visible ? Material.LIME_DYE : Material.GRAY_DYE, visible ? plugin.text("Босс-бар: включён") : plugin.text("Босс-бар: скрыт"),
                p -> settings(p, plugin.toggleMicroQuest(p)), plugin.text("Нажмите, чтобы переключить."), plugin.text("Задание и таймер продолжаются в фоне."), plugin.text("Выбор сохраняется после переподключения."));
        button(view, 20, Material.WRITABLE_BOOK, plugin.text("Моё задание"), p -> quest(p, null));
        button(view, 24, Material.CHEST, plugin.text("Мои награды"), p -> rewards(p, 0, null));
        if (notice != null) view.put(31, item(Material.PAPER, plugin.text("Настройка сохранена"), notice));
        open(player, view);
    }

    void rewards(Player player, int requestedPage, String notice) {
        guarded(player, p -> {
            var rewards = plugin.questRewards(p);
            int page = Math.max(0, Math.min(requestedPage, Math.max(0, (rewards.size() - 1) / 28)));
            Screen view = screen(p, "rewards", plugin.text("Награды • Трай #") + plugin.activeAttempt());
            frame(view);
            view.put(4, item(Material.CHEST, plugin.text("Ваши трофеи • ") + rewards.size(), plugin.text("Нажмите на предмет, чтобы забрать его."), plugin.text("При полном инвентаре он останется здесь."), plugin.text("Новая попытка удаляет все незабранные призы.")));
            for (int i = page * 28; i < Math.min(rewards.size(), (page + 1) * 28); i++) {
                var entry = rewards.get(i);
                int index = i % 28, slot = 10 + index / 7 * 9 + index % 7;
                var prize = QuestReward.item(entry.reward(), plugin::text);
                var meta = prize.getItemMeta();
                var lore = new ArrayList<>(Objects.requireNonNullElse(meta.lore(), List.<Component>of()));
                lore.add(Component.empty());
                lore.add(Component.text(plugin.text("▸ Нажмите, чтобы забрать"), NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
                meta.lore(lore);
                prize.setItemMeta(meta);
                view.put(slot, prize);
                view.actions.put(view.positions[slot], q -> rewards(q, page, plugin.claimReward(q, entry.id())));
            }
            if (rewards.isEmpty())
                view.put(22, item(Material.BUNDLE, plugin.text("Пока пусто"), plugin.text("Выполните мини-задание, чтобы получить трофей."), plugin.text("Предметы не попадают в карманы автоматически.")));
            button(view, 45, Material.ARROW, plugin.text("К заданию"), q -> quest(q, null));
            if (page > 0) button(view, 48, Material.ARROW, plugin.text("Назад"), q -> rewards(q, page - 1, null));
            if ((page + 1) * 28 < rewards.size())
                button(view, 50, Material.ARROW, plugin.text("Далее"), q -> rewards(q, page + 1, null));
            view.put(51, item(Material.PAPER, plugin.text("Страница ") + (page + 1), notice == null ? plugin.text("Трофеи текущей попытки") : notice));
            open(p, view);
        });
    }

    private void profile(Player player, Long attempt, String uuid, String name) throws Exception {
        Map<ActivityMetric, Long> scores = database.profile(plugin.ownerId(), attempt, uuid);
        Screen view = screen(player, "profile", plugin.text("Профиль • ") + name);
        view.put(4, item(Material.PLAYER_HEAD, name, scope(attempt), plugin.text("Статистика записывается с обновления 1.4.0")));
        for (ActivityMetric metric : ActivityMetric.values())
            view.put(18 + metric.ordinal(), item(icon(metric), plugin.text(metric.label), metric.format(scores.getOrDefault(metric, 0L), plugin::text)));
        button(view, 45, Material.ARROW, plugin.text("Назад"), p -> stats(p, attempt));
        scopeButton(view, attempt);
        open(player, view);
    }

    private void pages(Screen view, int page, int total, java.util.function.IntConsumer openPage) {
        if (page > 0) button(view, 48, Material.ARROW, plugin.text("Предыдущая страница"), p -> openPage.accept(page - 1));
        if ((page + 1) * 45 < total)
            button(view, 50, Material.ARROW, plugin.text("Следующая страница"), p -> openPage.accept(page + 1));
    }

    private void leaders(Player player, Long attempt, ActivityMetric metric, int page) throws Exception {
        plugin.flushActivity();
        var scores = database.top(plugin.ownerId(), attempt, metric);
        Screen view = screen(player, "leaders", plugin.text(metric.label) + " • " + scope(attempt));
        for (int i = page * 45; i < Math.min(scores.size(), (page + 1) * 45); i++) {
            var score = scores.get(i);
            button(view, i % 45, Material.PLAYER_HEAD, "#" + (i + 1) + " " + score.name(), p -> profile(p, attempt, score.uuid(), score.name()), metric.format(score.value(), plugin::text), plugin.text("Нажмите, чтобы открыть профиль"));
        }
        if (scores.isEmpty())
            view.put(22, item(Material.PAPER, plugin.text("Пока нет записей"), plugin.text("Сыграйте немного после обновления.")));
        button(view, 45, Material.ARROW, plugin.text("Назад"), p -> stats(p, attempt));
        pages(view, page, scores.size(), next -> guarded(player, p -> leaders(p, attempt, metric, next)));
        scopeButton(view, attempt);
        open(player, view);
    }

    void best(Player player, int page) {
        guarded(player, p -> {
            var runs = database.bestRuns(plugin.ownerId());
            Screen view = screen(p, "records", plugin.text("Лучшие траи • Рекорды времени"));
            for (int i = page * 45; i < Math.min(runs.size(), (page + 1) * 45); i++) {
                var entry = runs.get(i);
                button(view, i % 45, i == 0 ? Material.GOLD_BLOCK : Material.PAPER, "#" + (i + 1) + plugin.text(" • Трай ") + entry.attempt(), q -> attempt(q, entry.attempt()),
                        plugin.duration(entry.seconds()), entry.reason() == null ? plugin.text("Попытка ещё не завершена") : entry.reason(), plugin.text("Нажмите: миры и статистика игроков"));
            }
            if (runs.isEmpty()) view.put(22, item(Material.PAPER, plugin.text("Рекордов пока нет")));
            pages(view, page, runs.size(), next -> best(p, next));
            open(p, view);
        });
    }

    void attempt(Player player, long number) {
        guarded(player, p -> {
            if (database.history(plugin.ownerId(), number).isEmpty()) {
                message(p, plugin.text("Попытка не найдена"), plugin.text("Для трая #") + number + plugin.text(" пока нет записи."));
                return;
            }
            stats(p, number);
            var entries = database.history(plugin.ownerId(), number);
            if (!entries.isEmpty() && p.getOpenInventory().getTopInventory().getHolder() instanceof Screen screen) {
                var entry = entries.getFirst();
                List<String> info = new ArrayList<>(entry.worlds());
                info.add(entry.reason() == null ? plugin.text("Причина завершения не записана") : entry.reason());
                screen.put(4, item(Material.FILLED_MAP, plugin.text("Трай #") + number, info.toArray(String[]::new)));
            }
        });
    }

    void admin(Player player) {
        guarded(player, p -> {
            plugin.requireAdmin(p);
            Screen view = screen(p, "admin", plugin.text("Хардкор • Администратор"));
            button(view, 11, Material.ENDER_CHEST, plugin.text("Сохранения и откат"), q -> backups(q, 0), plugin.text("Выбор конкретной копии"), plugin.text("Перед откатом потребуется подтверждение"));
            button(view, 13, Material.TNT, plugin.text("Новая попытка"), q -> confirm(q, plugin.text("Начать новый трай?"), this::admin, r -> {
                plugin.adminReset(r);
            }, plugin.text("Текущий трай завершится."), plugin.text("Инвентари игроков будут очищены.")));
            button(view, 15, Material.LAVA_BUCKET, plugin.text("Очистка старых миров"), q -> {
                plugin.requireAdmin(q);
                var report = plugin.menuCleanup(false);
                confirm(q, plugin.text("Удалить старые каталоги?"), this::admin, r -> {
                    plugin.requireAdmin(r);
                    var done = plugin.menuCleanup(true);
                    message(r, plugin.text("Очистка завершена"), plugin.text("Удалено: ") + done.eligible(), plugin.text("Без копии сохранено: ") + done.retained());
                }, plugin.text("Можно удалить: ") + report.eligible(), plugin.text("Без копии сохранятся: ") + report.retained(), plugin.text("Готовые резервные копии останутся."));
            });
            button(view, 22, Material.CLOCK, plugin.text("История попыток"), q -> best(q, 0));
            open(p, view);
        });
    }

    void backups(Player player, int page) {
        guarded(player, p -> {
            plugin.requireAdmin(p);
            List<Path> snapshots = new ArrayList<>();
            for (Path path : backups.list())
                if (plugin.ownerId().equals(backups.manifest(path).getString("owner"))) snapshots.add(path);
            Screen view = screen(p, "backups", plugin.text("Сохранения смерти • Выбор копии"));
            for (int i = page * 45; i < Math.min(snapshots.size(), (page + 1) * 45); i++) {
                Path path = snapshots.get(i);
                var info = backups.manifest(path);
                button(view, i % 45, Material.ENDER_CHEST, plugin.text("Трай #") + info.getLong("run"), q -> confirm(q, plugin.text("Откатить к этому сохранению?"), r -> backups(r, page), r -> plugin.menuRollback(r, path.getFileName().toString()),
                                plugin.text("Текущий прогресс будет заменён."), plugin.text("Сервер ОСТАНОВИТСЯ."), plugin.text("После остановки запустите сервер снова."), plugin.text("Выбран трай #") + info.getLong("run"), info.getString("created", "")),
                        plugin.duration(info.getLong("seconds")), info.getString("created", ""), plugin.text("Нажмите для подтверждения отката"));
            }
            if (snapshots.isEmpty())
                view.put(22, item(Material.PAPER, plugin.text("Готовых копий пока нет"), plugin.text("Копия появится после следующей смерти.")));
            button(view, 45, Material.ARROW, plugin.text("Панель администратора"), this::admin);
            pages(view, page, snapshots.size(), next -> backups(p, next));
            open(p, view);
        });
    }

    private void confirm(Player player, String title, Action back, Action action, String... explanation) {
        Screen view = screen(player, "confirm", title);
        view.put(13, item(Material.WRITABLE_BOOK, title, explanation));
        button(view, 20, Material.LIME_CONCRETE, plugin.text("Подтвердить"), p -> {
            plugin.requireAdmin(p);
            p.closeInventory();
            action.run(p);
        });
        button(view, 24, Material.RED_CONCRETE, plugin.text("Отмена"), back);
        open(player, view);
    }

    private void message(Player player, String title, String... text) {
        Screen view = screen(player, "message", title);
        view.put(22, item(Material.PAPER, title, text));
        open(player, view);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Screen view)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !view.viewer.equals(player.getUniqueId())) return;
        Action action = view.actions.get(event.getRawSlot());
        if (action != null && event.getCurrentItem() != null) Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && player.getOpenInventory().getTopInventory().getHolder() == view)
                guarded(player, action);
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Screen) event.setCancelled(true);
    }
}
