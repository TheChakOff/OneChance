import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/** Sends one personal sound packet; never spawns mobs or broadcasts audio to neighbours. */
final class RareMobSounds implements Listener {
    private static final Sound[] SOUNDS = {
            Sound.ENTITY_GOAT_SCREAMING_AMBIENT, Sound.ENTITY_VILLAGER_NO,
            Sound.ENTITY_CHICKEN_EGG, Sound.ENTITY_PIG_AMBIENT, Sound.ENTITY_FOX_SCREECH,
            Sound.ENTITY_CAT_HISS, Sound.ENTITY_CREEPER_PRIMED, Sound.ENTITY_ENDERMAN_STARE,
            Sound.ENTITY_GHAST_AMBIENT, Sound.ENTITY_SHEEP_AMBIENT, Sound.ENTITY_DOLPHIN_PLAY
    };
    private final Main plugin;
    private final Random random = new Random();
    private final Map<UUID, RareSoundTimer> timers = new HashMap<>();

    RareMobSounds(Main plugin) { this.plugin = plugin; }

    void second() {
        if (!plugin.getConfig().getBoolean("rare-mob-sounds.enabled", true)) return;
        double chance = plugin.getConfig().getDouble("rare-mob-sounds.chance-per-minute", .005);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!plugin.canTrack(player.getWorld()) || player.getGameMode() == GameMode.CREATIVE
                    || player.getGameMode() == GameMode.SPECTATOR) continue;
            RareSoundTimer timer = timers.computeIfAbsent(player.getUniqueId(), ignored -> new RareSoundTimer(
                    Math.clamp(plugin.getConfig().getInt("rare-mob-sounds.join-grace-seconds", 120), 1, 3600),
                    Math.clamp(plugin.getConfig().getInt("rare-mob-sounds.cooldown-seconds", 600), 60, 86400)));
            if (timer.second(chance, random)) play(player);
        }
    }

    void play(Player player) {
        // Put the sound a few blocks behind or beside the listener, at a comfortable volume.
        double angle = Math.toRadians(player.getLocation().getYaw()) + random.nextDouble(-.8, .8);
        double distance = random.nextDouble(2, 4);
        Location source = player.getLocation().clone().add(Math.sin(angle) * distance, .7, -Math.cos(angle) * distance);
        double configuredVolume = plugin.getConfig().getDouble("rare-mob-sounds.volume", .45);
        float volume = (float) (Double.isFinite(configuredVolume) ? Math.clamp(configuredVolume, 0, 1) : .45);
        player.playSound(source, SOUNDS[random.nextInt(SOUNDS.length)], SoundCategory.HOSTILE,
                volume, (float) random.nextDouble(.85, 1.15));
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) { timers.remove(event.getPlayer().getUniqueId()); }
}
