import java.util.List;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

enum QuestReward {
    STICK(Material.STICK, "Лицензия на владение палкой", "Действительна, только если палка уже ваша."),
    DIRT(Material.DIRT, "Квадратный метр ипотечного ада", "Первый взнос внесён. Осталось 327 лет."),
    SEEDS(Material.WHEAT_SEEDS, "Акция ОАО «Один колос»", "Дивиденды выплачиваются исключительно надеждами."),
    FEATHER(Material.FEATHER, "Перо главного бездельника", "Заверяет, что вы устали совершенно официально."),
    FLESH(Material.ROTTEN_FLESH, "Шаурма с вокзала Эндермира", "Срок годности истёк ещё в прошлом трае."),
    BUSH(Material.DEAD_BUSH, "Букет от администрации", "Поливали вашими стараниями. Не помогло."),
    SNOW(Material.SNOWBALL, "Снежок с автографом солнца", "Автограф исчез при попытке прочитать."),
    PAPER(Material.PAPER, "Сертификат на половину ничего", "Вторая половина выдаётся за новый подвиг."),
    POTATO(Material.POISONOUS_POTATO, "Премия «Золотой гастрит»", "Торжественно не рекомендуется к употреблению."),
    BUTTON(Material.STONE_BUTTON, "Кнопка вызова здравого смысла", "Абонент временно недоступен."),
    HOE(Material.WOODEN_HOE, "Мотыга заместителя генерального", "Повышение без зарплаты и обязанностей."),
    STRING(Material.STRING, "Поводок для невидимого начальника", "Начальник ушёл на обед. Навсегда.");
    final Material material;
    final String title;
    final String joke;

    QuestReward(Material material, String title, String joke) {
        this.material = material;
        this.title = title;
        this.joke = joke;
    }

    static ItemStack item(String key, java.util.function.UnaryOperator<String> translate) {
        QuestReward reward = valueOf(key);
        ItemStack prize = new ItemStack(reward.material, 1);
        var meta = prize.getItemMeta();
        meta.displayName(Component.text(translate.apply(reward.title), NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        meta.setEnchantmentGlintOverride(true);
        meta.lore(List.of(Component.text(translate.apply(reward.joke), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text(translate.apply("Легендарная бесполезность. Вы это заслужили."), NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false)));
        prize.setItemMeta(meta);
        return prize;
    }

    static boolean known(String key) {
        if (key == null) return false;
        try {
            valueOf(key);
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
