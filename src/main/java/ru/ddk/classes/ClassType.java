package ru.ddk.classes;

import org.bukkit.Material;

public enum ClassType {
    DRUID("Друид", Material.OAK_SAPLING),
    DUELIST("Дуэлянт", Material.IRON_SWORD),
    SUMMONER("Призыватель", Material.SOUL_LANTERN),
    SLAVE_OWNER("Рабовладелец", Material.VILLAGER_SPAWN_EGG),
    CHAMELEON("Хамелеон", Material.LIME_DYE);

    private final String displayName;
    private final Material icon;

    ClassType(String displayName, Material icon) {
        this.displayName = displayName;
        this.icon = icon;
    }

    public String displayName() { return displayName; }
    public Material icon() { return icon; }

    public static ClassType fromString(String value) {
        if (value == null) return null;
        String normalized = value.trim().toLowerCase();
        for (ClassType type : values()) {
            if (type.name().equalsIgnoreCase(normalized)
                    || type.displayName.equalsIgnoreCase(value.trim())) return type;
        }
        return switch (normalized) {
            case "druid", "друид" -> DRUID;
            case "duelist", "дуэлянт", "дуэлист" -> DUELIST;
            case "summoner", "призыватель" -> SUMMONER;
            case "slave_owner", "slaveowner", "рабовладелец" -> SLAVE_OWNER;
            case "chameleon", "хамелеон" -> CHAMELEON;
            default -> null;
        };
    }
}