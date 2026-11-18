import org.junit.jupiter.api.Test;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class RareSoundTimerTest {
    @Test void waitsForJoinGraceThenEnforcesCooldownEvenAtFullChance() {
        var timer = new RareSoundTimer(120, 600);
        var random = new Random(1);
        for (int i = 0; i < 119; i++) assertFalse(timer.second(1, random));
        assertTrue(timer.second(1, random));
        for (int i = 0; i < 599; i++) assertFalse(timer.second(1, random));
        assertTrue(timer.second(1, random));
    }

    @Test void zeroNegativeAndInvalidChanceNeverPlaySounds() {
        for (double chance : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            var timer = new RareSoundTimer(1, 60);
            for (int i = 0; i < 600; i++) assertFalse(timer.second(chance, new Random(1)));
        }
    }

    @Test void tinyProbabilityOnlyRollsOncePerMinuteAndUsesTheConfiguredThreshold() {
        Random random = new Random() {
            private int calls;
            @Override public double nextDouble() { return ++calls == 1 ? .005 : .004; }
        };
        var timer = new RareSoundTimer(1, 600);
        assertFalse(timer.second(.005, random));
        for (int i = 0; i < 59; i++) assertFalse(timer.second(.005, random));
        assertTrue(timer.second(.005, random));
    }

    @Test void misconfiguredIntervalsStillPreventAPlaybackFlood() {
        var timer = new RareSoundTimer(-1, -5);
        var random = new Random(1);
        assertTrue(timer.second(100, random));
        for (int i = 0; i < 59; i++) assertFalse(timer.second(100, random));
        assertTrue(timer.second(100, random));
    }
}
