package ru.ddk.classes;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class SMPClassesPlugin extends JavaPlugin {
    private PlayerDataStore dataStore;
    private AbilityItems abilityItems;
    private VillagerService villagerService;
    private AbilityEngine abilityEngine;
    private GameListener gameListener;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataStore = new PlayerDataStore(this);
        abilityItems = new AbilityItems(this);
        villagerService = new VillagerService(this, dataStore);
        abilityEngine = new AbilityEngine(this, dataStore, villagerService);
        gameListener = new GameListener(this, dataStore, abilityItems, abilityEngine, villagerService);

        getServer().getPluginManager().registerEvents(gameListener, this);
        if (getCommand("class") != null) getCommand("class").setExecutor(gameListener);
        if (getCommand("classadmin") != null) getCommand("classadmin").setExecutor(gameListener);

        getLogger().info("SMPClasses включён: 5 классов загружены.");
    }

    @Override
    public void onDisable() {
        if (gameListener != null) {
            for (Player player : getServer().getOnlinePlayers()) {
                gameListener.restorePanel(player);
            }
        }
        if (villagerService != null) villagerService.disbandAll();
        if (dataStore != null) dataStore.save();
        getLogger().info("SMPClasses выключен.");
    }

    public PlayerDataStore dataStore() { return dataStore; }
    public AbilityItems abilityItems() { return abilityItems; }
    public VillagerService villagerService() { return villagerService; }
    public AbilityEngine abilityEngine() { return abilityEngine; }
}