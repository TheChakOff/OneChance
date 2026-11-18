import com.destroystokyo.paper.profile.PlayerProfile;
import org.bukkit.entity.*;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Captured before the reset clears equipment; never retains the original mob or player entity.
 */
record DeathSceneContext(DeathScene scene, String victimName, PlayerProfile victimProfile,
                         ItemStack[] armor, ItemStack hand, EntityType attackerType,
                         PlayerProfile attackerProfile, String attackerName, ItemStack attackerHand,
                         org.bukkit.Material projectile) {
    static DeathSceneContext capture(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        var lastDamage = victim.getLastDamageCause();
        DeathScene scene = DeathScene.forDamage(lastDamage == null ? null : lastDamage.getCause(),
            event.getDamageSource().getDamageType().getKey().getKey());
        Entity attacker = event.getDamageSource().getCausingEntity();
        Entity direct = event.getDamageSource().getDirectEntity();
        if (attacker == null && direct instanceof Projectile shot && shot.getShooter() instanceof Entity shooter)
            attacker = shooter;
        org.bukkit.Material projectile = direct instanceof Trident ? org.bukkit.Material.TRIDENT
            : direct instanceof Fireball ? org.bukkit.Material.FIRE_CHARGE : org.bukkit.Material.ARROW;
        return of(victim, scene, attacker, projectile);
    }

    static DeathSceneContext preview(Player player, DeathScene scene) {
        return of(player, scene, null, org.bukkit.Material.ARROW);
    }

    private static DeathSceneContext of(Player victim, DeathScene scene, Entity attacker, org.bukkit.Material projectile) {
        ItemStack weapon = attacker instanceof LivingEntity living && living.getEquipment() != null
            ? living.getEquipment().getItemInMainHand().clone() : null;
        ItemStack[] armor = victim.getInventory().getArmorContents();
        for (int i = 0; i < armor.length; i++) if (armor[i] != null) armor[i] = armor[i].clone();
        return new DeathSceneContext(scene, victim.getName(), (PlayerProfile) ((org.bukkit.profile.PlayerProfile) victim.getPlayerProfile()).clone(), armor,
            victim.getInventory().getItemInMainHand().clone(), attacker == null ? null : attacker.getType(),
            attacker instanceof Player player ? (PlayerProfile) ((org.bukkit.profile.PlayerProfile) player.getPlayerProfile()).clone() : null,
            attacker == null ? "" : attacker.getName(), weapon, projectile);
    }
}
