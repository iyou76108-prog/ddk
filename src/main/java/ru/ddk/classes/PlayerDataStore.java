package ru.ddk.classes;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;

public final class PlayerDataStore {
    private final File file;
    private YamlConfiguration yaml;

    public PlayerDataStore(JavaPlugin plugin) {
        this.file = new File(plugin.getDataFolder(), "data.yml");
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("Не удалось создать папку данных.");
        }
        this.yaml = YamlConfiguration.loadConfiguration(file);
    }

    private String base(UUID uuid) { return "players." + uuid; }

    public ClassType getClass(UUID uuid) {
        return ClassType.fromString(yaml.getString(base(uuid) + ".class"));
    }

    public void setClass(UUID uuid, ClassType type) {
        yaml.set(base(uuid) + ".class", type == null ? null : type.name());
        save();
    }

    public int getSummonerKills(UUID uuid) {
        return yaml.getInt(base(uuid) + ".summoner_kills", 0);
    }

    public int addSummonerKill(UUID uuid) {
        int kills = getSummonerKills(uuid) + 1;
        yaml.set(base(uuid) + ".summoner_kills", kills);
        save();
        return kills;
    }

    public Set<UUID> getArmyExcludedPlayers(UUID uuid) {
        Set<UUID> result = new HashSet<>();
        for (String raw : yaml.getStringList(base(uuid) + ".army_excluded")) {
            try { result.add(UUID.fromString(raw)); } catch (IllegalArgumentException ignored) { }
        }
        return result;
    }

    public void addArmyExcludedPlayer(UUID owner, UUID excluded) {
        Set<UUID> players = getArmyExcludedPlayers(owner);
        if (players.add(excluded)) {
            yaml.set(base(owner) + ".army_excluded", players.stream().map(UUID::toString).toList());
            save();
        }
    }

    public void clearArmyExcludedPlayers(UUID owner) {
        yaml.set(base(owner) + ".army_excluded", null);
        save();
    }

    public long getCooldown(UUID uuid, String key) {
        return yaml.getLong(base(uuid) + ".cooldowns." + key, 0L);
    }

    public void setCooldown(UUID uuid, String key, long untilMillis) {
        yaml.set(base(uuid) + ".cooldowns." + key, untilMillis);
        save();
    }

    public boolean isPanelActive(UUID uuid) {
        return yaml.getBoolean(base(uuid) + ".panel", false);
    }

    public void setPanelActive(UUID uuid, boolean active) {
        yaml.set(base(uuid) + ".panel", active);
        save();
    }

    public void saveHotbar(UUID uuid, ItemStack[] contents) {
        List<ItemStack> list = new ArrayList<>(9);
        for (int i = 0; i < 9; i++) list.add(contents != null && i < contents.length ? contents[i] : null);
        yaml.set(base(uuid) + ".hotbar", list);
        save();
    }

    public ItemStack[] getHotbar(UUID uuid) {
        ItemStack[] result = new ItemStack[9];
        List<?> values = yaml.getList(base(uuid) + ".hotbar");
        if (values == null) return null;
        for (int i = 0; i < Math.min(9, values.size()); i++) {
            Object value = values.get(i);
            if (value instanceof ItemStack stack) result[i] = stack.clone();
        }
        return result;
    }

    public void clearHotbar(UUID uuid) {
        yaml.set(base(uuid) + ".hotbar", null);
        yaml.set(base(uuid) + ".panel", false);
        save();
    }

    public Location getFlag(UUID uuid) {
        String path = base(uuid) + ".flag";
        if (!yaml.contains(path + ".world")) return null;
        World world = Bukkit.getWorld(yaml.getString(path + ".world", ""));
        if (world == null) return null;
        return new Location(world, yaml.getDouble(path + ".x"), yaml.getDouble(path + ".y"),
                yaml.getDouble(path + ".z"));
    }

    public void setFlag(UUID uuid, Location location) {
        String path = base(uuid) + ".flag";
        if (location == null || location.getWorld() == null) {
            yaml.set(path, null);
        } else {
            yaml.set(path + ".world", location.getWorld().getName());
            yaml.set(path + ".x", location.getX());
            yaml.set(path + ".y", location.getY());
            yaml.set(path + ".z", location.getZ());
        }
        save();
    }

    public ItemStack[] getVillagerInventory(UUID villagerId) {
        List<?> values = yaml.getList("villagers." + villagerId + ".inventory");
        if (values == null) return new ItemStack[27];
        ItemStack[] result = new ItemStack[27];
        for (int i = 0; i < Math.min(27, values.size()); i++) {
            if (values.get(i) instanceof ItemStack stack) result[i] = stack.clone();
        }
        return result;
    }

    public void setVillagerInventory(UUID villagerId, ItemStack[] contents) {
        List<ItemStack> list = new ArrayList<>(27);
        for (int i = 0; i < 27; i++) list.add(contents != null && i < contents.length ? contents[i] : null);
        yaml.set("villagers." + villagerId + ".inventory", list);
        save();
    }

    public void save() {
        try {
            yaml.save(file);
        } catch (IOException exception) {
            Bukkit.getLogger().severe("Не удалось сохранить data.yml: " + exception.getMessage());
        }
    }
}