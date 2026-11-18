import java.time.Duration;
import java.util.*;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

final class DeathScreen implements Listener {
    private final Main plugin;
    private final Map<UUID, Animation> animations = new HashMap<>();
    private final DeathMiniature miniatures;

    DeathScreen(Main plugin) {
        this.plugin = plugin;
        miniatures = Main.hasApi("org.bukkit.entity.Mannequin") ? new DeathMiniature(plugin) : null;
        if (miniatures != null) plugin.getServer().getPluginManager().registerEvents(miniatures, plugin);
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("death-screen.enabled", true);
    }

    private boolean clown() {
        return plugin.getConfig().getBoolean("death-screen.clown.enabled", true);
    }

    int sequenceTicks() {
        if (!enabled()) return 0;
        return miniatureTicks() + ticks("fade-in-ticks", 10) + ticks("hold-ticks", 24) + ticks("fade-out-ticks", 16) + 4;
    }

    private int miniatureTicks() {
        return miniatures != null && enabled() && plugin.getConfig().getBoolean("death-screen.miniature.enabled", true)
            ? Math.clamp(plugin.getConfig().getInt("death-screen.miniature.duration-ticks", 80), 40, 120) : 0;
    }

    void showDeath(Player viewer, DeathSceneContext context, long attempt) {
        stop(viewer);
        if (!enabled()) return;
        if (miniatureTicks() == 0 || !plugin.getConfig().getBoolean(
                plugin.scenePath(context.scene()) + ".miniature", true)) {
            show(viewer, context.victimName(), attempt);
            return;
        }
        miniatures.show(viewer, context, miniatureTicks(), () -> show(viewer, context.victimName(), attempt));
    }

    void show(Player player, String victim, long attempt) {
        stop(player);
        if (!enabled()) return;
        String subtitle = plugin.getConfig().getString("death-screen.subtitle",
            "{player} · " + plugin.text("Трай #") + "{attempt}" + plugin.text(" завершён"));
        if (!subtitle.isBlank()) player.showTitle(Title.title(Component.empty(), Localized.colored(
            subtitle.replace("{player}", victim).replace("{attempt}", Long.toString(attempt)), NamedTextColor.WHITE),
            Title.Times.times(Duration.ofMillis(ticks("fade-in-ticks", 10) * 50L), Duration.ofMillis(ticks("hold-ticks", 24) * 50L), Duration.ofMillis(ticks("fade-out-ticks", 16) * 50L))));
        if (!clown()) return;
        Animation animation = new Animation(player);
        animations.put(player.getUniqueId(), animation);
        animation.runTaskTimer(plugin, 0, animation.interval);
    }

    void stop(Player player) {
        if (miniatures != null) miniatures.stop(player);
        Animation previous = animations.remove(player.getUniqueId());
        if (previous != null) previous.dispose();
    }

    void close() {
        if (miniatures != null) miniatures.close();
        animations.values().forEach(Animation::dispose);
        animations.clear();
    }

    private int ticks(String name, int fallback) {
        return Math.max(0, Math.min(200, plugin.getConfig().getInt("death-screen." + name, fallback)));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        stop(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        stop(event.getPlayer());
    }

    private final class Animation extends BukkitRunnable {
        private final Player player;
        private final UUID world;
        private final TextDisplay caption;
        private final int fadeIn = ticks("fade-in-ticks", 10), hold = ticks("hold-ticks", 24), fadeOut = ticks("fade-out-ticks", 16);
        private final int interval = Math.max(2, Math.min(10, plugin.getConfig().getInt("death-screen.particle-interval-ticks", 3)));
        private final int color = plugin.getConfig().getInt("death-screen.clown.color", -1);
        private int age;

        Animation(Player player) {
            this.player = player;
            this.world = player.getWorld().getUID();
            Location start = player.getEyeLocation().add(player.getEyeLocation().getDirection().multiply(2.4)).subtract(0, .7, 0);
            caption = player.getWorld().spawn(start, TextDisplay.class, entity -> {
                entity.setVisibleByDefault(false);
                entity.setPersistent(false);
                entity.setInvulnerable(true);
                entity.setBillboard(Display.Billboard.CENTER);
                entity.setAlignment(TextDisplay.TextAlignment.CENTER);
                entity.setLineWidth(240);
                entity.setDefaultBackground(false);
                entity.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                entity.setShadowed(true);
                entity.setSeeThrough(true);
                entity.setBrightness(new Display.Brightness(15, 15));
                entity.setTextOpacity((byte) 5);
                entity.text(Localized.colored(plugin.getConfig().getString("death-screen.clown.caption",
                    plugin.text("СПАСИБО НАШЕМУ БЕЗДАРЮ\nЗА ОБНОВЛЕНИЕ МИРА")), NamedTextColor.GOLD).decorate(TextDecoration.BOLD));
                var transform = entity.getTransformation();
                transform.getScale().set(.36f);
                entity.setTransformation(transform);
            });
            player.showEntity(plugin, caption);
        }

        void dispose() {
            caption.remove();
            cancel();
        }

        @Override
        public void run() {
            if (!player.isOnline() || !player.getWorld().getUID().equals(world) || age >= fadeIn + hold + fadeOut) {
                if (animations.get(player.getUniqueId()) == this) animations.remove(player.getUniqueId());
                dispose();
                return;
            }
            Location eye = player.getEyeLocation();
            Vector forward = eye.getDirection().normalize();
            double yaw = Math.toRadians(eye.getYaw());
            Vector right = new Vector(-Math.cos(yaw), 0, -Math.sin(yaw));
            Vector up = right.clone().crossProduct(forward).normalize();
            double desired = 2.4;
            double distance = desired;
            var wall = player.getWorld().rayTraceBlocks(eye, forward, desired, FluidCollisionMode.NEVER, true);
            if (wall != null) distance = Math.max(.35, wall.getHitPosition().distance(eye.toVector()) - .18);
            double phase = ClownShape.envelope(age, fadeIn, hold, fadeOut);
            double scale = (.42 + .58 * phase) * distance / desired;
            Location center = eye.clone().add(forward.clone().multiply(distance)).add(up.clone().multiply(.35 * distance / desired));
            caption.teleport(center.clone().subtract(up.clone().multiply(1.05 * distance / desired)));
            caption.setTextOpacity((byte) Math.max(5, Math.min(255, Math.round(phase * 255))));
            var transform = caption.getTransformation();
            transform.getScale().set((float) ((.36 + .09 * phase) * distance / desired));
            caption.setTransformation(transform);
            for (int i = 0; i < ClownShape.DOTS.size(); i++) {
                if (age >= fadeIn + hold && (i % 10) / 10.0 > phase) continue;
                var dot = ClownShape.DOTS.get(i);
                Location at = center.clone().add(right.clone().multiply(dot.x() * scale))
                    .add(up.clone().multiply(dot.y() * scale)).subtract(forward.clone().multiply(dot.layer() * scale));
                float size = (float) Math.max(.08, dot.size() * (.20 + .65 * phase) * distance / desired);
                player.spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0, new Particle.DustOptions(Color.fromRGB(color < 0 ? dot.rgb() : color & 0xffffff), size), true);
            }
            age += interval;
        }
    }
}
