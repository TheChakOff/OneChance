enum ActivityMetric {
    DEATHS("Смерти"), PLAY_SECONDS("Время игры"), BLOCKS_MINED("Сломано блоков"),
    ORES_MINED("Добыто руды"), BLOCKS_PLACED("Поставлено блоков"),
    MOBS_KILLED("Убито существ"), FISH_CAUGHT("Поймано улова"),
    ADVANCEMENTS("Достижения"), DISTANCE_CM("Пройдено"), QUESTS_COMPLETED("Микро-подвиги"),
    HOSTILES_KILLED("Враждебные мобы"), DIAMOND_ORES_MINED("Алмазная руда");
    final String label;

    ActivityMetric(String label) {
        this.label = label;
    }

    String format(long value) {
        return format(value, java.util.function.UnaryOperator.identity());
    }

    String format(long value, java.util.function.UnaryOperator<String> translate) {
        if (this == PLAY_SECONDS) return translate.apply("%dч %02dм %02dс").formatted(value / 3600, value / 60 % 60, value % 60);
        if (this == DISTANCE_CM) return translate.apply("%.1f км").formatted(value / 100_000.0);
        return Long.toString(value);
    }
}
