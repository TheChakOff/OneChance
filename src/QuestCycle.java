import java.util.random.RandomGenerator;



final class QuestCycle {
    static int duration(RandomGenerator random, int minMinutes, int maxMinutes) {
        int min = Math.max(5, Math.min(20, minMinutes));
        int max = Math.max(min, Math.max(5, Math.min(20, maxMinutes)));
        return random.nextInt(min * 60, max * 60 + 1);
    }

    static String time(int seconds) {
        return "%02d:%02d".formatted(Math.max(0, seconds) / 60, Math.max(0, seconds) % 60);
    }
}
