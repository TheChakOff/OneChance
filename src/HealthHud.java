import java.util.*;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.*;

/**
 * Adds objectives to the current boards, preserving teams and other plugins' occupied display slots.
 */
final class HealthHud implements AutoCloseable {
    private static final int ROWS_PER_PAGE = 15;
    private static final int REFRESHES_PER_PAGE = 10;

    private record BoardView(Objective sidebar, Objective tab, Set<String> sidebarNames, Set<String> tabNames) {
    }

    private final Main plugin;
    private final Map<Scoreboard, BoardView> boards = new IdentityHashMap<>();
    private long refreshCount;

    HealthHud(Main plugin) {
        this.plugin = plugin;
    }

    void refresh() {
        if (!plugin.getConfig().getBoolean("health-hud.enabled", true)) {
            close();
            return;
        }
        refreshCount++;
        Map<Scoreboard, List<Player>> viewers = new IdentityHashMap<>();
        for (Player player : Bukkit.getOnlinePlayers())
            viewers.computeIfAbsent(player.getScoreboard(), b -> new ArrayList<>()).add(player);
        var iterator = boards.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!viewers.containsKey(entry.getKey())) {
                dispose(entry.getValue());
                iterator.remove();
            }
        }
        for (var group : viewers.entrySet()) {
            Scoreboard board = group.getKey();
            BoardView view = boards.get(board);
            if (view == null || view.sidebar.getScoreboard() == null || view.tab.getScoreboard() == null) {
                if (view != null) dispose(view);
                view = createView(board);
                boards.put(board, view);
            }
            refreshBoard(board, view, group.getValue());
        }
    }

    private BoardView createView(Scoreboard board) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        Objective sidebar = board.registerNewObjective("hcr_s_" + id, Criteria.DUMMY, Component.text(plugin.text("Игроки"), NamedTextColor.GOLD));
        sidebar.numberFormat(NumberFormat.blank());
        Objective tab = board.registerNewObjective("hcr_t_" + id, Criteria.DUMMY, Component.text("❤"), RenderType.HEARTS);
        return new BoardView(sidebar, tab, new HashSet<>(), new HashSet<>());
    }

    private void refreshBoard(Scoreboard board, BoardView view, List<Player> viewers) {
        // Shared scoreboards must not expose someone hidden from any of their viewers.
        List<? extends Player> players = Bukkit.getOnlinePlayers().stream()
                .filter(target -> viewers.stream().allMatch(viewer -> viewer.canSee(target)))
                .sorted(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER)).toList();
        int pages = Math.max(1, (players.size() + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE);
        int page = (int) ((refreshCount / REFRESHES_PER_PAGE) % pages);
        Component title = Component.text("❤ " + plugin.text("Игроки"), NamedTextColor.GOLD);
        if (pages > 1) title = title.append(Component.text(" · " + (page + 1) + "/" + pages, NamedTextColor.GRAY));
        if (!title.equals(view.sidebar.displayName())) view.sidebar.displayName(title);

        Set<String> sidebarNames = new HashSet<>(), tabNames = new HashSet<>();
        for (int i = 0; i < players.size(); i++) {
            Player player = players.get(i);
            String name = player.getName();
            int hitPoints = Math.max(0, (int) Math.ceil(player.getHealth()));
            setScore(view.tab.getScore(name), hitPoints);
            tabNames.add(name);
            if (i / ROWS_PER_PAGE == page) {
                Score row = view.sidebar.getScore(name);
                setScore(row, ROWS_PER_PAGE - i % ROWS_PER_PAGE);
                Component label = healthLabel(player);
                if (!label.equals(row.customName())) row.customName(label);
                sidebarNames.add(name);
            }
        }
        clearStale(view.sidebar, view.sidebarNames, sidebarNames);
        clearStale(view.tab, view.tabNames, tabNames);
        displayIfFree(board, view.sidebar, DisplaySlot.SIDEBAR);
        if (plugin.getConfig().getBoolean("health-hud.tab-hearts", true)
                && !(plugin.getConfig().getBoolean("health-hud.delegate-tab", false)
                    && Bukkit.getPluginManager().isPluginEnabled("TAB")))
            displayIfFree(board, view.tab, DisplaySlot.PLAYER_LIST);
        else if (board.getObjective(DisplaySlot.PLAYER_LIST) == view.tab) view.tab.setDisplaySlot(null);
    }

    private Component healthLabel(Player player) {
        NamedTextColor color = player.getHealth() <= 6 ? NamedTextColor.RED : NamedTextColor.GREEN;
        Component label = Component.text(player.getName(), NamedTextColor.WHITE).append(Component.text("  "))
                .append(Component.text(hearts(player.getHealth()) + " ♥", color));
        if (player.getAbsorptionAmount() > 0)
            label = label.append(Component.text(" +" + hearts(player.getAbsorptionAmount()) + " ♥", NamedTextColor.GOLD));
        return label;
    }

    private void setScore(Score score, int value) {
        if (!score.isScoreSet() || score.getScore() != value) score.setScore(value);
    }

    static String hearts(double hp) {
        double value = Double.isFinite(hp) ? Math.max(0, hp) / 2 : 0;
        return value == Math.floor(value) ? Long.toString((long) value) : String.format(Locale.ROOT, "%.1f", value);
    }

    private void clearStale(Objective objective, Set<String> previous, Set<String> current) {
        for (String name : previous) if (!current.contains(name)) objective.getScore(name).resetScore();
        previous.clear();
        previous.addAll(current);
    }

    private void displayIfFree(Scoreboard board, Objective objective, DisplaySlot slot) {
        if (board.getObjective(slot) == null) objective.setDisplaySlot(slot);
    }

    private void dispose(BoardView view) {
        if (view.sidebar.getScoreboard() != null) view.sidebar.unregister();
        if (view.tab.getScoreboard() != null) view.tab.unregister();
    }

    @Override
    public void close() {
        boards.values().forEach(this::dispose);
        boards.clear();
    }
}
