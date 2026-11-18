import org.bukkit.Material;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;

/**
 * Every Paper damage cause has a caption and a safe visual choreography.
 */
enum DeathScene {
    KILL("Команда /kill", Motion.FADE, Material.BARRIER),
    WORLD_BORDER("Граница мира", Motion.WALL, Material.RED_STAINED_GLASS),
    CONTACT("Опасное прикосновение", Motion.THORNS, Material.CACTUS),
    SWEET_BERRY_BUSH("Куст оказался сильнее", Motion.THORNS, Material.SWEET_BERRY_BUSH),
    STALAGMITE("Острый конец приключения", Motion.FALL, Material.POINTED_DRIPSTONE),
    FALLING_STALACTITE("Сосулька с доставкой", Motion.CRUSH, Material.POINTED_DRIPSTONE),
    ENTITY_ATTACK("Последний удар", Motion.MELEE, Material.IRON_SWORD),
    ENTITY_SWEEP_ATTACK("Размашистый удар", Motion.MELEE, Material.IRON_SWORD),
    PROJECTILE("Последний выстрел", Motion.SHOT, Material.ARROW),
    SUFFOCATION("Застрял в блоках", Motion.CRUSH, Material.STONE),
    FALL("Гравитация победила", Motion.FALL, Material.STONE),
    FIRE("Слишком горячо", Motion.BURN, Material.CAMPFIRE),
    FIRE_TICK("Не успел потушиться", Motion.BURN, Material.CAMPFIRE),
    MELTING("Растаял", Motion.SINK, Material.ICE),
    LAVA("Искупался в лаве", Motion.BURN, Material.MAGMA_BLOCK),
    DROWNING("Забыл подышать", Motion.SINK, Material.BLUE_STAINED_GLASS),
    BLOCK_EXPLOSION("Взрывной финал", Motion.EXPLODE, Material.TNT),
    ENTITY_EXPLOSION("Объятия со взрывом", Motion.EXPLODE, Material.TNT),
    VOID("Билет в пустоту", Motion.VOID, Material.OBSIDIAN),
    LIGHTNING("Персональная гроза", Motion.LIGHTNING, Material.LIGHTNING_ROD),
    SUICIDE("Сам себе противник", Motion.FADE, Material.BARRIER),
    STARVATION("Обед отменяется", Motion.FADE, Material.ROTTEN_FLESH),
    POISON("Не тот напиток", Motion.POISON, Material.POTION),
    MAGIC("Фокус не удался", Motion.POISON, Material.ENCHANTED_BOOK),
    WITHER("Иссушающий финал", Motion.POISON, Material.WITHER_ROSE),
    FALLING_BLOCK("Доставка сверху", Motion.CRUSH, Material.ANVIL),
    THORNS("Урон вернулся", Motion.THORNS, Material.CACTUS),
    DRAGON_BREATH("Дыхание дракона", Motion.POISON, Material.DRAGON_BREATH),
    FLY_INTO_WALL("Стена оказалась прочнее", Motion.WALL, Material.STONE_BRICKS),
    HOT_FLOOR("Пол — это лава", Motion.BURN, Material.MAGMA_BLOCK),
    CAMPFIRE("Присел у костра", Motion.BURN, Material.CAMPFIRE),
    CRAMMING("Слишком тесная компания", Motion.CRUSH, Material.PISTON),
    DRYOUT("Без воды никак", Motion.FADE, Material.SAND),
    FREEZE("Совсем остыл", Motion.FREEZE, Material.PACKED_ICE),
    SONIC_BOOM("Бас от вардена", Motion.WAVE, Material.SCULK),
    CUSTOM("Необычный финал", Motion.FADE, Material.STRUCTURE_VOID),
    UNKNOWN("Причина неизвестна", Motion.FADE, Material.STRUCTURE_VOID);

    enum Motion {MELEE, SHOT, FALL, BURN, SINK, EXPLODE, VOID, LIGHTNING, FADE, CRUSH, THORNS, WALL, FREEZE, WAVE, POISON}

    final String title;
    final Motion motion;
    final Material prop;

    DeathScene(String title, Motion motion, Material prop) {
        this.title = title;
        this.motion = motion;
        this.prop = prop;
    }

    static DeathScene forCause(DamageCause cause) {
        if (cause == null) return UNKNOWN;
        try {
            return valueOf(cause.name());
        } catch (IllegalArgumentException futureCause) {
            return UNKNOWN;
        }
    }

    static DeathScene forDamage(DamageCause cause, String damageType) {
        if (damageType == null) return forCause(cause);
        return switch (damageType) {
            // Modern Paper reports these as CONTACT or FALLING_BLOCK; keep their actual visuals.
            case "hot_floor" -> HOT_FLOOR;
            case "campfire" -> CAMPFIRE;
            case "sweet_berry_bush" -> SWEET_BERRY_BUSH;
            case "stalagmite" -> STALAGMITE;
            case "falling_stalactite" -> FALLING_STALACTITE;
            default -> forCause(cause);
        };
    }

    /**
     * Normalised progress shared by every scene; safe for preview and zero-duration input.
     */
    static double progress(int age, int duration) {
        return duration <= 0 ? 1 : Math.clamp((double) age / duration, 0, 1);
    }

    double victimHeight(double progress) {
        return switch (motion) {
            case FALL -> .9 * (1 - Math.min(1, progress / .55) * Math.min(1, progress / .55));
            case VOID -> -1.1 * progress * progress;
            case SINK -> -.55 * progress;
            case WAVE, EXPLODE -> progress < .5 ? 0 : .25 * Math.sin((progress - .5) * Math.PI * 2);
            default -> 0;
        };
    }
}
