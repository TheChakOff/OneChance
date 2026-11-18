import java.util.*;
import java.util.random.RandomGenerator;



enum MicroTask {
    MINER("Шахтёр без выходных", ActivityMetric.BLOCKS_MINED, 128, 1024, "Сломай %d блоков"),
    LANDSCAPER("Снести половину ландшафта", ActivityMetric.BLOCKS_MINED, 256, 2048, "Сломай %d блоков"),
    BUILDER("Застройщик без разрешения", ActivityMetric.BLOCKS_PLACED, 96, 512, "Поставь %d блоков"),
    DECORATOR("Вавилон за свой счёт", ActivityMetric.BLOCKS_PLACED, 128, 640, "Поставь %d блоков"),
    ORE("Профсоюз подземных жадин", ActivityMetric.ORES_MINED, 24, 128, "Добудь руду: %d блока"),
    HUNT("Ночная смена охотника", ActivityMetric.HOSTILES_KILLED, 12, 64, "Победи врагов: %d"),
    FISH("Рыбный отдел без обеда", ActivityMetric.FISH_CAUGHT, 12, 48, "Поймай улов: %d"),
    WALK("Курьер без зарплаты", ActivityMetric.DISTANCE_CM, 100000, 600000, "Пройди %d м"),
    DIAMOND("Алмазы в обмен на позор", ActivityMetric.DIAMOND_ORES_MINED, 3, 18, "Алмазная руда: %d блока");
    final String title;
    final ActivityMetric metric;
    final int min, max;
    private final String objective;

    MicroTask(String title, ActivityMetric metric, int min, int max, String objective) {
        this.title = title;
        this.metric = metric;
        this.min = min;
        this.max = max;
        this.objective = objective;
    }

    String goal(int target) {
        return goal(target, java.util.function.UnaryOperator.identity());
    }

    String goal(int target, java.util.function.UnaryOperator<String> translate) {
        return translate.apply(objective).formatted(metric == ActivityMetric.DISTANCE_CM ? target / 100 : target);
    }

    String progress(long value, int target) {
        return metric == ActivityMetric.DISTANCE_CM ? value / 100 + "/" + target / 100 + " м" : value + "/" + target;
    }

    int target(RandomGenerator random, int seconds) {
        seconds = Math.max(300, Math.min(1200, seconds));
        if (metric == ActivityMetric.DISTANCE_CM) {
            int low = Math.max(min, seconds * 200), high = Math.min(max, seconds * 400);
            return random.nextInt((low + 9999) / 10000, high / 10000 + 1) * 10000;
        }
        double duration = (seconds - 300) / 900.0;
        int low = min + (int) ((max - min) * duration * .45);
        int high = min + (int) ((max - min) * (.25 + .75 * duration));
        return random.nextInt(low, Math.max(low, high) + 1);
    }

    static MicroTask choose(RandomGenerator random, MicroTask previous, boolean rod, boolean pickaxe, boolean weapon, boolean ironTier) {
        List<MicroTask> options = Arrays.stream(values()).filter(task -> task != previous)
                .filter(task -> task != FISH || rod).filter(task -> task != ORE || pickaxe)
                .filter(task -> task != DIAMOND || ironTier).filter(task -> task != HUNT || weapon).toList();
        return options.get(random.nextInt(options.size()));
    }
}
