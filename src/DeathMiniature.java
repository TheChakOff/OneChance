import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.logging.Level;

/**
 * Private, disposable actors. Animation uses scheduler-driven teleports even while the world is frozen.
 */
final class DeathMiniature implements Listener, AutoCloseable {
    private static final String ACTOR_TAG = "hcr_death_scene";
    private static final Set<EntityType> SAFE_ACTORS = EnumSet.of(EntityType.SKELETON, EntityType.STRAY,
        EntityType.WITHER_SKELETON, EntityType.BOGGED, EntityType.ZOMBIE, EntityType.HUSK,
        EntityType.DROWNED, EntityType.CREEPER, EntityType.SPIDER, EntityType.CAVE_SPIDER,
        EntityType.ENDERMAN, EntityType.PIGLIN, EntityType.PIGLIN_BRUTE, EntityType.ZOMBIFIED_PIGLIN,
        EntityType.BLAZE, EntityType.WITCH, EntityType.SLIME, EntityType.MAGMA_CUBE, EntityType.WOLF,
        EntityType.IRON_GOLEM, EntityType.PILLAGER, EntityType.VINDICATOR, EntityType.EVOKER,
        EntityType.VEX, EntityType.RAVAGER, EntityType.PHANTOM, EntityType.SILVERFISH,
        EntityType.ENDERMITE, EntityType.GUARDIAN, EntityType.ELDER_GUARDIAN, EntityType.WARDEN);
    private final Main plugin;
    private final Map<UUID, Scene> scenes = new HashMap<>();

    DeathMiniature(Main plugin) {
        this.plugin = plugin;
    }

    void show(Player viewer, DeathSceneContext context, int duration, Runnable finished) {
        stop(viewer);
        Scene scene = new Scene(viewer, context, duration, finished);
        scenes.put(viewer.getUniqueId(), scene);
        try {
            scene.spawn();
            scene.runTaskTimer(plugin, 0, 2);
            scene.scheduled = true;
        } catch (RuntimeException | LinkageError failure) {
            scenes.remove(viewer.getUniqueId());
            scene.dispose();
            plugin.getLogger().log(Level.WARNING, "Cannot start death miniature; using the clown screen", failure);
            finished.run();
        }
    }

    void stop(Player player) {
        Scene scene = scenes.remove(player.getUniqueId());
        if (scene != null) scene.dispose();
    }

    @Override
    public void close() {
        scenes.values().forEach(Scene::dispose);
        scenes.clear();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        stop(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        stop(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.getRightClicked().getScoreboardTags().contains(ACTOR_TAG)) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteractAt(PlayerInteractAtEntityEvent event) {
        if (event.getRightClicked().getScoreboardTags().contains(ACTOR_TAG)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onActorDamage(org.bukkit.event.entity.EntityDamageEvent event) {
        if (event.getEntity().getScoreboardTags().contains(ACTOR_TAG)) event.setCancelled(true);
    }

    private final class Scene extends BukkitRunnable {
        private final Player viewer;
        private final DeathSceneContext context;
        private final DeathScene.Motion motion;
        private final String path;
        private final int duration;
        private final Runnable finished;
        private final UUID world;
        private final List<Entity> actors = new ArrayList<>();
        private Mannequin victim;
        private LivingEntity attacker;
        private Display prop, projectile;
        private BlockDisplay pistonHead;
        private TextDisplay caption;
        private double attackerScale;
        private int age;
        private boolean impact, shotReleased, scheduled, disposed;

        Scene(Player viewer, DeathSceneContext context, int duration, Runnable finished) {
            this.viewer = viewer;
            this.context = context;
            path = plugin.scenePath(context.scene());
            var defaultMotion = context.scene().motion;
            DeathScene.Motion selected;
            try {
                selected = DeathScene.Motion.valueOf(plugin.getConfig().getString(path + ".motion", defaultMotion.name())
                    .toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException invalid) {
                selected = defaultMotion;
            }
            motion = selected;
            this.duration = duration;
            this.finished = finished;
            this.world = viewer.getWorld().getUID();
        }

        private <T extends Entity> T actor(Class<T> type) {
            T entity = viewer.getWorld().spawn(viewer.getEyeLocation(), type, spawned -> {
                // Track before configuring so partial spawn failures can still be cleaned up.
                actors.add(spawned);
                spawned.setVisibleByDefault(false);
                spawned.setPersistent(false);
                spawned.setInvulnerable(true);
                spawned.setSilent(true);
                spawned.setGravity(false);
                spawned.setNoPhysics(true);
                spawned.addScoreboardTag(ACTOR_TAG);
                if (spawned instanceof LivingEntity living) {
                    living.setCollidable(false);
                    living.setCanPickupItems(false);
                    if (living instanceof Mob mob) mob.setAI(false);
                }
                if (spawned instanceof Mannequin mannequin) {
                    mannequin.setImmovable(true);
                    mannequin.setDescription(null);
                }
                if (spawned instanceof Display display) {
                    display.setTeleportDuration(2);
                    display.setBrightness(new Display.Brightness(15, 15));
                }
                if (spawned instanceof ItemDisplay item) item.setBillboard(Display.Billboard.CENTER);
            });
            viewer.showEntity(plugin, entity);
            return entity;
        }

        void spawn() {
            victim = actor(Mannequin.class);
            victim.setProfile(ResolvableProfile.resolvableProfile(context.victimProfile()));
            victim.getEquipment().setArmorContents(context.armor());
            victim.getEquipment().setItemInMainHand(context.hand());
            if (motion == DeathScene.Motion.MELEE || motion == DeathScene.Motion.SHOT
                || motion == DeathScene.Motion.WAVE || context.attackerType() != null) {
                EntityType type = context.attackerType();
                if (context.attackerProfile() != null) {
                    Mannequin human = actor(Mannequin.class);
                    human.setProfile(ResolvableProfile.resolvableProfile(context.attackerProfile()));
                    attacker = human;
                } else {
                    if (!SAFE_ACTORS.contains(type)) type = motion == DeathScene.Motion.SHOT ? EntityType.SKELETON
                        : motion == DeathScene.Motion.WAVE ? EntityType.WARDEN
                        : motion == DeathScene.Motion.EXPLODE ? EntityType.CREEPER : EntityType.ZOMBIE;
                    attacker = actor(Objects.requireNonNull(type.getEntityClass()).asSubclass(LivingEntity.class));
                }
                attackerScale = .75 / Math.max(1, attacker.getHeight());
                if (attacker.getEquipment() != null) {
                    ItemStack hand = context.attackerHand();
                    if (hand == null && motion == DeathScene.Motion.SHOT) hand = new ItemStack(Material.BOW);
                    if (hand != null) attacker.getEquipment().setItemInMainHand(hand);
                    if (motion == DeathScene.Motion.SHOT && hand != null
                            && (hand.getType() == Material.BOW || hand.getType() == Material.CROSSBOW)) {
                        attacker.startUsingItem(org.bukkit.inventory.EquipmentSlot.HAND);
                    }
                }
            }
            Material material = Material.matchMaterial(plugin.getConfig().getString(path + ".material", ""));
            if (material == null || !material.isBlock()) material = context.scene().prop;
            if (material.isBlock()) {
                BlockDisplay block = actor(BlockDisplay.class);
                var data = material.createBlockData();
                if (data instanceof org.bukkit.block.data.Directional directional
                        && directional.getFaces().contains(org.bukkit.block.BlockFace.NORTH)) {
                    directional.setFacing(org.bukkit.block.BlockFace.NORTH);
                }
                if (data instanceof org.bukkit.block.data.type.Piston piston) {
                    piston.setExtended(true);
                    pistonHead = actor(BlockDisplay.class);
                    var head = (org.bukkit.block.data.type.PistonHead) Material.PISTON_HEAD.createBlockData();
                    head.setFacing(org.bukkit.block.BlockFace.NORTH);
                    pistonHead.setBlock(head);
                }
                block.setBlock(data);
                prop = block;
            } else {
                ItemDisplay item = actor(ItemDisplay.class);
                item.setItemStack(new ItemStack(material));
                prop = item;
            }
            if (motion == DeathScene.Motion.SHOT) {
                ItemDisplay item = actor(ItemDisplay.class);
                item.setItemStack(new ItemStack(context.projectile()));
                projectile = item;
            }
            caption = actor(TextDisplay.class);
            caption.setBillboard(Display.Billboard.CENTER);
            caption.setDefaultBackground(false);
            caption.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            caption.setSeeThrough(true);
            caption.setShadowed(true);
            caption.setLineWidth(300);
            caption.text(Localized.colored(plugin.getConfig().getString(path + ".caption",
                plugin.text(context.scene().title)), NamedTextColor.GOLD)
                .append(Component.text("\n" + context.victimName()
                    + (context.attackerName().isBlank() ? "" : " ← " + context.attackerName()), NamedTextColor.WHITE)));
            // Place and scale everything before the first tick can be sent to the viewer.
            draw();
        }

        @Override
        public void run() {
            scheduled = true;
            if (!viewer.isOnline() || !viewer.getWorld().getUID().equals(world)) {
                finish(false);
                return;
            }
            if (age >= duration) {
                finish(true);
                return;
            }
            try {
                draw();
                age += 2;
            } catch (RuntimeException | LinkageError failure) {
                plugin.getLogger().log(Level.WARNING, "Death miniature interrupted", failure);
                finish(true);
            }
        }

        private void draw() {
            Location eye = viewer.getEyeLocation();
            Vector forward = eye.getDirection().normalize();
            double yaw = Math.toRadians(eye.getYaw());
            Vector right = new Vector(-Math.cos(yaw), 0, -Math.sin(yaw));
            Vector up = right.clone().crossProduct(forward).normalize();
            double distance = 3;
            var wall = viewer.getWorld().rayTraceBlocks(eye, forward, distance, FluidCollisionMode.NEVER, true);
            if (wall != null) distance = Math.max(.5, wall.getHitPosition().distance(eye.toVector()) - .2);
            double ratio = distance / 3;
            Location center = eye.clone().add(forward.clone().multiply(distance));
            double t = DeathScene.progress(age, duration);
            double extension = SceneGeometry.attackProgress(t);
            double x = motion == DeathScene.Motion.WALL ? -.4 + Math.min(t / .55, 1) * .9 : .35;
            if (pistonHead != null) x += .15 * extension;
            if (attacker != null && t > .55) x += .18 * Math.min(1, (t - .55) / .15);
            Location victimFeet = point(center, right, up, x, -.6 + context.scene().victimHeight(t), ratio);
            Location victimChest = victimFeet.clone().add(up.clone().multiply(.4 * ratio));
            double fade = motion == DeathScene.Motion.FADE && t > .55 ? Math.max(.01, (1 - t) / .45) : 1;
            scale(victim, .42 * ratio * fade);
            Location attackerFeet = null, attackerChest = null;
            if (attacker != null) {
                double lunge = motion == DeathScene.Motion.MELEE ? SceneGeometry.lunge(t) : 0;
                attackerFeet = point(center, right, up, -.75 + lunge, -.6, ratio);
                attackerChest = attackerFeet.clone().add(up.clone().multiply(.4 * ratio));
                scale(attacker, attackerScale * ratio);
                placeFacing(attacker, attackerFeet, attackerChest, victimChest);
            }
            double propY = motion == DeathScene.Motion.CRUSH ? .85 - Math.min(t / .55, 1) * 1.05 : -.875;
            double propX = motion == DeathScene.Motion.WALL ? .85 : x;
            if (motion == DeathScene.Motion.WALL) propY = -.25;
            if (pistonHead != null) { propX = -.65; propY = -.2; }
            Location propCenter = point(center, right, up, propX, propY, ratio);
            // The model quaternion below already uses world coordinates. Do not rotate it again by the camera.
            propCenter.setYaw(0);
            propCenter.setPitch(0);
            prop.teleport(propCenter);
            // Directional block fronts are NORTH in model space. Rotate that face toward the target.
            Vector blockDirection = victimChest.toVector().subtract(propCenter.toVector());
            if (motion == DeathScene.Motion.WALL) blockDirection = right.clone().multiply(-1);
            orientProp(prop, pistonHead == null ? forward.clone().multiply(-1) : blockDirection, .55 * ratio);
            if (pistonHead != null) {
                Vector axis = blockDirection.clone().normalize();
                pistonHead.teleport(propCenter.clone().add(axis.clone().multiply((.3 + .55 * extension) * ratio)));
                orientProp(pistonHead, axis, .55 * ratio);
            }
            Location threat = attackerChest == null ? propCenter : attackerChest;
            if (motion == DeathScene.Motion.FADE || motion == DeathScene.Motion.VOID) threat = eye;
            placeFacing(victim, victimFeet, victimChest, threat);
            caption.teleport(point(center, right, up, 0, -1, ratio));
            scale(caption, .32 * ratio);
            if (projectile != null) {
                if (t >= .15 && !shotReleased) {
                    shotReleased = true;
                    // Clear the visual use pose without releasing a real projectile into the world.
                    if (attacker != null) attacker.clearActiveItem();
                    sceneSound("projectile-sound", "entity.arrow.shoot", .2f, 1.2f);
                }
                Location start = attackerChest == null ? point(center, right, up, -.65, -.2, ratio) : attackerChest;
                Vector position = SceneGeometry.between(start.toVector(), victimChest.toVector(), extension);
                projectile.teleport(new Location(center.getWorld(), position.getX(), position.getY(), position.getZ()));
                scale(projectile, t < .15 || t > .57 ? .001 : .3 * ratio);
                Vector direction = victimChest.toVector().subtract(start.toVector());
                double angle = Math.atan2(direction.dot(up), direction.dot(right));
                var transform = projectile.getTransformation();
                // GUI arrow/trident sprites point diagonally; align their tip with screen-space flight.
                transform.getLeftRotation().rotationZ((float) (angle - Math.PI / 4));
                projectile.setTransformation(transform);
            }
            if (t >= .55 && !impact) {
                impact = true;
                if (attacker != null) attacker.swingMainHand();
                if (Mannequin.validPoses().contains(Pose.SWIMMING)) victim.setPose(Pose.SWIMMING, true);
                sceneSound("impact-sound", "entity.player.hurt", .25f, .8f);
            }
            drawParticles(center, right, up, ratio, t, attackerChest, victimChest);
        }

        private void sceneSound(String setting, String fallback, float volume, float pitch) {
            String sound = plugin.getConfig().getString(path + "." + setting, "minecraft:" + fallback);
            if (!sound.isBlank()) viewer.playSound(viewer.getLocation(), sound, volume, pitch);
        }

        private void placeFacing(LivingEntity entity, Location feet, Location origin, Location target) {
            SceneGeometry.Facing facing = SceneGeometry.facing(origin.toVector(), target.toVector(), feet.getYaw());
            feet.setYaw(facing.yaw());
            feet.setPitch((float) Math.clamp(facing.pitch(), -65, 65));
            entity.teleport(feet);
            entity.setRotation(feet.getYaw(), feet.getPitch());
            entity.setBodyYaw(feet.getYaw());
        }

        private void orientProp(Display display, Vector direction, double size) {
            scale(display, size);
            if (display instanceof BlockDisplay) {
                var transform = display.getTransformation();
                var rotation = SceneGeometry.blockRotation(direction);
                transform.getLeftRotation().set(rotation);
                transform.getTranslation().set(SceneGeometry.centeredTranslation(rotation, size));
                display.setTransformation(transform);
            }
        }

        private void drawParticles(Location center, Vector right, Vector up, double ratio, double t,
                                   Location attackerChest, Location victimChest) {
            int color = switch (motion) {
                case BURN, EXPLODE -> 0xff7020;
                case SINK, FREEZE -> 0x65cfff;
                case POISON -> 0xa050ce;
                case LIGHTNING, WAVE -> 0x71fff0;
                case THORNS -> 0x60b34c;
                default -> 0xeeeeee;
            };
            if (motion == DeathScene.Motion.LIGHTNING) {
                for (int i = 0; i < 8; i++)
                    dust(point(center, right, up, .35 + (i % 2) * .08, .8 - i * .16, ratio), color, ratio);
            } else if (motion == DeathScene.Motion.WAVE || (impact && motion == DeathScene.Motion.EXPLODE)) {
                boolean wave = motion == DeathScene.Motion.WAVE;
                double radius = wave && t < .55 ? .15 : Math.max(.1, (t - .55) * 1.5);
                Location ringCenter = victimChest;
                if (wave && attackerChest != null && t < .55) {
                    Vector at = SceneGeometry.between(attackerChest.toVector(), victimChest.toVector(), SceneGeometry.attackProgress(t));
                    ringCenter = new Location(center.getWorld(), at.getX(), at.getY(), at.getZ());
                }
                for (int i = 0; i < 12; i++) {
                    double angle = i * Math.PI / 6;
                    dust(point(ringCenter, right, up, Math.cos(angle) * radius, Math.sin(angle) * radius, ratio), color, ratio);
                }
            } else if (impact || motion == DeathScene.Motion.BURN || motion == DeathScene.Motion.POISON) {
                for (int i = 0; i < 4; i++)
                    dust(point(center, right, up, .35 + Math.sin(age * .2 + i) * .25, -.4 + i * .13, ratio), color, ratio);
            }
        }

        private Location point(Location center, Vector right, Vector up, double x, double y, double ratio) {
            return center.clone().add(right.clone().multiply(x * ratio)).add(up.clone().multiply(y * ratio));
        }

        private void scale(Entity entity, double value) {
            if (entity instanceof LivingEntity living) {
                var attribute = living.getAttribute(Attribute.SCALE);
                if (attribute != null) attribute.setBaseValue(Math.clamp(value, .01, 2));
            } else if (entity instanceof Display display) {
                var transform = display.getTransformation();
                transform.getScale().set((float) value);
                if (entity instanceof BlockDisplay)
                    transform.getTranslation().set((float) (-value / 2), 0, (float) (-value / 2));
                display.setTransformation(transform);
            }
        }

        private void dust(Location at, int color, double ratio) {
            viewer.spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0,
                new Particle.DustOptions(Color.fromRGB(plugin.getConfig().getInt(path + ".particle-color", color) & 0xffffff), (float) Math.max(.1, ratio * .55)), true);
        }

        private void finish(boolean continueToClown) {
            scenes.remove(viewer.getUniqueId(), this);
            dispose();
            if (continueToClown && viewer.isOnline()) finished.run();
        }

        void dispose() {
            if (disposed) return;
            disposed = true;
            actors.forEach(Entity::remove);
            if (scheduled) cancel();
        }
    }
}
