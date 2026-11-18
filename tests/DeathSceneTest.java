import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DeathSceneTest {
    @Test
    void everyPaperDamageCauseHasItsOwnNamedSceneInsteadOfUnknownFallback() {
        for (DamageCause cause : DamageCause.values()) {
            DeathScene scene = DeathScene.forCause(cause);
            assertNotEquals(DeathScene.UNKNOWN, scene, cause.name());
            assertFalse(scene.title.isBlank());
            assertNotNull(scene.prop);
            assertNotNull(scene.motion);
        }
        assertEquals(DeathScene.UNKNOWN, DeathScene.forCause(null));
    }

    @Test
    void modernContactDamageKeepsSpecificEnvironmentalCause() {
        assertEquals(DeathScene.HOT_FLOOR, DeathScene.forDamage(DamageCause.CONTACT, "hot_floor"));
        assertEquals(DeathScene.CAMPFIRE, DeathScene.forDamage(DamageCause.CONTACT, "campfire"));
        assertEquals(DeathScene.SWEET_BERRY_BUSH, DeathScene.forDamage(DamageCause.CONTACT, "sweet_berry_bush"));
        assertEquals(DeathScene.STALAGMITE, DeathScene.forDamage(DamageCause.FALL, "stalagmite"));
        assertEquals(DeathScene.FALLING_STALACTITE, DeathScene.forDamage(DamageCause.FALLING_BLOCK, "falling_stalactite"));
        assertEquals(DeathScene.CUSTOM, DeathScene.forDamage(DamageCause.CUSTOM, "plugin_damage"));
        assertEquals(DeathScene.FALL, DeathScene.forDamage(DamageCause.FALL, null));
    }

    @Test
    void allTrajectoriesStayFiniteAndAnimationProgressIsClamped() {
        assertEquals(0, DeathScene.progress(-1, 80));
        assertEquals(.5, DeathScene.progress(40, 80));
        assertEquals(1, DeathScene.progress(999, 80));
        assertEquals(1, DeathScene.progress(0, 0));
        for (DeathScene scene : DeathScene.values()) {
            for (int age = 0; age <= 120; age++) {
                double height = scene.victimHeight(DeathScene.progress(age, 80));
                assertTrue(Double.isFinite(height), scene.name());
                assertTrue(height >= -1.1 && height <= .9, scene.name());
            }
        }
    }

    @Test
    void fallAndVoidHaveDifferentEndings() {
        assertEquals(.9, DeathScene.FALL.victimHeight(0));
        assertEquals(0, DeathScene.FALL.victimHeight(1));
        assertEquals(-1.1, DeathScene.VOID.victimHeight(1));
        assertEquals(-.55, DeathScene.DROWNING.victimHeight(1));
    }
}
