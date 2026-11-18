import java.util.random.RandomGenerator;

/** Counts active seconds only. Each player rolls once per minute, with a quiet period after a hit. */
final class RareSoundTimer {
    private static final int CHECK_INTERVAL_SECONDS = 60;
    private final int cooldownSeconds;
    private int remainingSeconds;

    RareSoundTimer(int graceSeconds, int cooldownSeconds) {
        remainingSeconds = Math.max(1, graceSeconds);
        this.cooldownSeconds = Math.max(CHECK_INTERVAL_SECONDS, cooldownSeconds);
    }

    boolean second(double chance, RandomGenerator random) {
        if (--remainingSeconds > 0) return false;
        remainingSeconds = CHECK_INTERVAL_SECONDS;
        if (!Double.isFinite(chance) || chance <= 0 || random.nextDouble() >= Math.min(1, chance)) return false;
        remainingSeconds = cooldownSeconds;
        return true;
    }
}
