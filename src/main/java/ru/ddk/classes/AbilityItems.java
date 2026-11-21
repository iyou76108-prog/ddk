package ru.ddk.classes;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

public final class AbilityItems {
    private final NamespacedKey abilityKey;

    public AbilityItems(SMPClassesPlugin plugin) {
        this.abilityKey = new NamespacedKey(plugin, "ability_id");
    }

    public ItemStack create(String id, Material material, String title, String description) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§a" + title);
        meta.setLore(List.of("§7" + description, "§8ПКМ — использовать"));
        meta.getPersistentDataContainer().set(abilityKey, PersistentDataType.STRING, id);
        item.setItemMeta(meta);
        return item;
    }

    public ItemStack createPassive(String id, Material material, String title, String description) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§e" + title);
        meta.setLore(List.of("§7" + description, "§6Пассивная способность — работает постоянно"));
        meta.getPersistentDataContainer().set(abilityKey, PersistentDataType.STRING, id);
        item.setItemMeta(meta);
        return item;
    }

    public String getAbilityId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(abilityKey, PersistentDataType.STRING);
    }

    public ItemStack[] forClass(ClassType type) {
        return forClass(type, null);
    }

    public ItemStack[] forClass(ClassType type, String copiedAbilityId) {
        ItemStack[] result = switch (type) {
            case DRUID -> new ItemStack[] {
                    createPassive("druid_passive", Material.OAK_SAPLING, "Благословение чащи",
                            "Регенерация и сопротивление рядом с листвой"),
                    create("druid_rootstorm", Material.MANGROVE_ROOTS, "Гнев корней",
                            "Корни хватают врагов в радиусе 7 блоков, наносят урон и держат 3 секунды"),
                    create("druid_wildcharge", Material.RAVAGER_SPAWN_EGG, "Звериный рывок",
                            "Рывок на 12 блоков, урон и отбрасывание врагов на пути"),
                    create("druid_sporeburst", Material.FIREWORK_STAR, "Споровый шторм",
                            "Взрыв спор вокруг цели: урон, яд и слабость"),
                    create("druid_avatar", Material.NETHER_STAR, "Аватар леса",
                            "На 9 секунд: сила II, скорость II, сопротивление и регенерация")
            };
            case DUELIST -> new ItemStack[] {
                    create("duelist_parry", Material.SHIELD, "Идеальное парирование", "Стойка и контратака"),
                    create("duelist_lunge", Material.IRON_SWORD, "Быстрый выпад", "Рывок к противнику"),
                    create("duelist_challenge", Material.TARGET, "Вызов на дуэль", "Пометить цель на 8 секунд"),
                    create("duelist_whirlwind", Material.IRON_AXE, "Вихрь клинков", "Круговой удар вокруг себя"),
                    create("duelist_last", Material.NETHER_STAR, "Последний раунд", "Боевые эффекты на 6 секунд")
            };
            case SUMMONER -> new ItemStack[] {
                    createPassive("summoner_bond", Material.SOUL_LANTERN, "Некротический пакт",
                            "Призывы получают усиление, не атакуют хозяина и союзных призывов"),
                    create("summoner_hound", Material.WITHER_SKELETON_SPAWN_EGG, "Пожиратель душ",
                            "Призывает усиленного Пожирателя — элитного ближнего бойца"),
                    create("summoner_archers", Material.STRAY_SPAWN_EGG, "Морозный дозор",
                            "Призывает двух усиленных Стужников-лучников"),
                    create("summoner_knight", Material.NETHERITE_SWORD, "Рыцарь могилы",
                            "Призывает бронированного визер-скелета"),
                    create("summoner_legion", Material.RAVAGER_SPAWN_EGG, "Легион бездны",
                            "Призывает Тварь бездны и двух рыцарей на 25 секунд")
            };
            case SLAVE_OWNER -> new ItemStack[] {
                    create("owner_place", Material.RED_BANNER, "Моё место", "Поставить или поднять флаг деревни"),
                    create("owner_work", Material.WRITABLE_BOOK, "Работайте за меня", "Выбрать профессию для жителя"),
                    create("owner_army", Material.IRON_SWORD, "Армия", "15 бойцов на 20 секунд; ПКМ по игроку исключает его из целей"),
                    create("owner_mega", Material.SADDLE, "Мега житель", "Оседлать одного жителя на 15 секунд; WASD — движение"),
                    createPassive("owner_passive", Material.HEART_OF_THE_SEA, "Возьму взамен",
                            "+2 сердца и +0,5 брони за каждого принадлежащего жителя")
            };
            case CHAMELEON -> new ItemStack[] {
                    create("chameleon_camouflage", Material.LIME_DYE, "Мой окрас", "Невидимость на 4 секунды и скорость II"),
                    createPassive("chameleon_passive", Material.FERN, "Свой среди чужих", "Враждебные мобы не выбирают вас целью"),
                    create("chameleon_tongue", Material.SNOWBALL, "Лизнуть языком", "Подтянуться к точке до 33 блоков"),
                    createPassive("chameleon_life", Material.OAK_LEAVES, "Живучесть", "+0,5 максимального здоровья за блок листвы рядом"),
                    create("chameleon_copy", Material.AMETHYST_SHARD, "Копирование", "Скопировать случайную способность игрока на 80 секунд")
            };
        };
        if (type == ClassType.CHAMELEON && copiedAbilityId != null) {
            ItemStack copied = abilityItem(copiedAbilityId);
            if (copied != null) result[4] = copied;
        }
        return result;
    }

    public ItemStack abilityItem(String id) {
        return switch (id) {
            case "druid_rootstorm" -> create(id, Material.MANGROVE_ROOTS, "Гнев корней", "Скопированная способность друида");
            case "druid_wildcharge" -> create(id, Material.RAVAGER_SPAWN_EGG, "Звериный рывок", "Скопированная способность друида");
            case "druid_sporeburst" -> create(id, Material.FIREWORK_STAR, "Споровый шторм", "Скопированная способность друида");
            case "druid_avatar" -> create(id, Material.NETHER_STAR, "Аватар леса", "Скопированная способность друида");
            case "duelist_parry" -> create(id, Material.SHIELD, "Идеальное парирование", "Скопированная способность дуэлянта");
            case "duelist_lunge" -> create(id, Material.IRON_SWORD, "Быстрый выпад", "Скопированная способность дуэлянта");
            case "duelist_challenge" -> create(id, Material.TARGET, "Вызов на дуэль", "Скопированная способность дуэлянта");
            case "duelist_whirlwind" -> create(id, Material.IRON_AXE, "Вихрь клинков", "Скопированная способность дуэлянта");
            case "duelist_last" -> create(id, Material.NETHER_STAR, "Последний раунд", "Скопированная способность дуэлянта");
            case "summoner_hound" -> create(id, Material.WITHER_SKELETON_SPAWN_EGG, "Пожиратель душ", "Скопированная способность призывателя");
            case "summoner_archers" -> create(id, Material.STRAY_SPAWN_EGG, "Морозный дозор", "Скопированная способность призывателя");
            case "summoner_knight" -> create(id, Material.NETHERITE_SWORD, "Рыцарь могилы", "Скопированная способность призывателя");
            case "summoner_legion" -> create(id, Material.RAVAGER_SPAWN_EGG, "Легион бездны", "Скопированная способность призывателя");
            case "owner_work" -> create(id, Material.WRITABLE_BOOK, "Работайте за меня", "Скопированная способность помещика");
            case "owner_army" -> create(id, Material.IRON_SWORD, "Армия", "Скопированная способность помещика");
            case "owner_mega" -> create(id, Material.SADDLE, "Мега житель", "Скопированная способность помещика");
            case "chameleon_camouflage" -> create(id, Material.LIME_DYE, "Мой окрас", "Скопированная способность хамелеона");
            case "chameleon_tongue" -> create(id, Material.SNOWBALL, "Лизнуть языком", "Скопированная способность хамелеона");
            default -> null;
        };
    }
}
