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
        return switch (type) {
            case DRUID -> new ItemStack[] {
                    create("druid_roots", Material.MANGROVE_ROOTS, "Корни земли", "Опутать цель на 2,5 секунды"),
                    create("druid_dash", Material.FEATHER, "Порыв чащи", "Рывок вперёд до 9 блоков"),
                    create("druid_thorn", Material.POISONOUS_POTATO, "Шипастая лоза", "Снаряд: урон и отравление"),
                    create("druid_bark", Material.OAK_LOG, "Кора древних", "Поглощение и сопротивление"),
                    create("druid_grove", Material.GOLDEN_APPLE, "Сердце рощи", "Исцелить себя и союзников")
            };
            case DUELIST -> new ItemStack[] {
                    create("duelist_parry", Material.SHIELD, "Идеальное парирование", "Стойка и контратака"),
                    create("duelist_lunge", Material.IRON_SWORD, "Быстрый выпад", "Рывок к противнику"),
                    create("duelist_challenge", Material.TARGET, "Вызов на дуэль", "Пометить цель на 8 секунд"),
                    create("duelist_whirlwind", Material.IRON_AXE, "Вихрь клинков", "Круговой удар вокруг себя"),
                    create("duelist_last", Material.NETHER_STAR, "Последний раунд", "Боевые эффекты на 6 секунд")
            };
            case SUMMONER -> new ItemStack[] {
                    create("summoner_wolf", Material.WOLF_SPAWN_EGG, "Спутник души", "Призвать волка на 35 секунд"),
                    create("summoner_skeletons", Material.SKELETON_SPAWN_EGG, "Костяной дозор", "Призвать двух скелетов"),
                    create("summoner_chains", Material.IRON_BARS, "Цепи могилы", "Замедлить врагов в области"),
                    create("summoner_recall", Material.SOUL_LANTERN, "Перекличка душ", "Вернуть прислужников к себе"),
                    create("summoner_guardian", Material.IRON_BLOCK, "Древний страж", "Призвать железного голема")
            };
            case SLAVE_OWNER -> new ItemStack[] {
                    create("owner_place", Material.RED_BANNER, "Моё место", "Поставить или убрать флаг деревни"),
                    create("owner_work", Material.WRITABLE_BOOK, "Работайте за меня", "Открыть управление работой жителей"),
                    create("owner_army", Material.IRON_SWORD, "Армия", "Призвать 15 бойцов на 23 секунды"),
                    create("owner_mega", Material.SADDLE, "Мега житель", "Оседлать башню из трёх жителей"),
                    createPassive("owner_passive", Material.HEART_OF_THE_SEA, "Возьму взамен", "+2 сердца и +0,5 брони за каждого вашего жителя")
            };
            case CHAMELEON -> new ItemStack[] {
                    create("chameleon_camouflage", Material.LIME_DYE, "Мой окрас", "Невидимость на 4 секунды и скорость II"),
                    createPassive("chameleon_passive", Material.FERN, "Свой среди чужих", "Враждебные мобы не выбирают вас целью"),
                    create("chameleon_tongue", Material.SNOWBALL, "Лизнуть языком", "Подтянуться к точке до 33 блоков"),
                    createPassive("chameleon_life", Material.OAK_LEAVES, "Живучесть", "+0,5 максимального здоровья за блок листвы рядом"),
                    create("chameleon_copy", Material.AMETHYST_SHARD, "Копирование", "Скопировать способность цели на 80 секунд")
            };
        };
    }
}