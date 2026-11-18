import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

final class HealthPlaceholders extends PlaceholderExpansion {
    private record Health(int hp, int max, int absorption) {}

    private final Main plugin;
    private volatile Map<UUID, Health> health = Map.of();

    HealthPlaceholders(Main plugin) {
        this.plugin = plugin;
    }

    void refresh() {
        Map<UUID, Health> next = new HashMap<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            next.put(player.getUniqueId(), new Health(rounded(player.getHealth()),
                rounded(plugin.maximumHealth(player)), rounded(player.getAbsorptionAmount())));
        }
        health = Map.copyOf(next);
    }

    private static int rounded(double value) {
        return (int) Math.ceil(value);
    }

    @Override
    public String getIdentifier() { return "hardcorereset"; }

    @Override
    public String getAuthor() { return "OneChance"; }

    @Override
    public String getVersion() { return plugin.getDescription().getVersion(); }

    @Override
    public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        Health playerHealth = player == null ? null : health.get(player.getUniqueId());
        return switch (params.toLowerCase(Locale.ROOT)) {
            case "hp" -> playerHealth == null ? "0" : Integer.toString(playerHealth.hp());
            case "max_hp" -> playerHealth == null ? "0" : Integer.toString(playerHealth.max());
            case "absorption" -> playerHealth == null ? "0" : Integer.toString(playerHealth.absorption());
            default -> null;
        };
    }
}
