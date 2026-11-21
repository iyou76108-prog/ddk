package ru.ddk.classes;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffectType;

import java.util.*;

public final class GameListener implements Listener, CommandExecutor {
    private final SMPClassesPlugin plugin;
    private final PlayerDataStore data;
    private final AbilityItems items;
    private final AbilityEngine abilities;
    private final VillagerService villagers;
    private final NamespacedKey classMenuKey;

    public GameListener(SMPClassesPlugin plugin, PlayerDataStore data, AbilityItems items,
                        AbilityEngine abilities, VillagerService villagers) {
        this.plugin = plugin;
        this.data = data;
        this.items = items;
        this.abilities = abilities;
        this.villagers = villagers;
        this.classMenuKey = new NamespacedKey(plugin, "class_menu");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            if (data.getClass(player.getUniqueId()) == null) openClassMenu(player);
            else if (data.isPanelActive(player.getUniqueId())) restorePanel(player);
        }, 1L);
    }

    public void openClassMenu(Player player) {
        Inventory menu = Bukkit.createInventory(null, 9, "§8Выберите класс");
        for (ClassType type : ClassType.values()) {
            ItemStack item = new ItemStack(type.icon());
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName("§a" + type.displayName());
            meta.setLore(List.of("§7Нажмите, чтобы выбрать класс"));
            meta.getPersistentDataContainer().set(classMenuKey, PersistentDataType.STRING, type.name());
            item.setItemMeta(meta);
            menu.addItem(item);
        }
        player.openInventory(menu);
    }

    @EventHandler
    public void onSwapHand(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        event.setCancelled(true);
        ClassType type = data.getClass(player.getUniqueId());
        if (type == null) {
            openClassMenu(player);
            return;
        }
        if (data.isPanelActive(player.getUniqueId())) restorePanel(player);
        else openAbilityPanel(player, type);
    }

    private void openAbilityPanel(Player player, ClassType type) {
        ItemStack[] current = new ItemStack[9];
        for (int i = 0; i < 9; i++) {
            ItemStack item = player.getInventory().getItem(i);
            current[i] = item == null ? null : item.clone();
        }
        data.saveHotbar(player.getUniqueId(), current);
        ItemStack[] panel = items.forClass(type);
        player.getInventory().setContents(replaceHotbar(player.getInventory().getContents(), panel));
        data.setPanelActive(player.getUniqueId(), true);
        player.sendMessage("§aПанель способностей включена. Выберите слот 1–5 и нажмите ПКМ. F — вернуть инвентарь.");
        player.updateInventory();
    }

    private ItemStack[] replaceHotbar(ItemStack[] contents, ItemStack[] panel) {
        ItemStack[] result = contents.clone();
        for (int i = 0; i < 9; i++) result[i] = i < panel.length ? panel[i] : null;
        return result;
    }

    public void restorePanel(Player player) {
        ItemStack[] stored = data.getHotbar(player.getUniqueId());
        if (stored != null) {
            for (int i = 0; i < 9; i++) player.getInventory().setItem(i, stored[i]);
        }
        data.clearHotbar(player.getUniqueId());
        player.updateInventory();
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        if (!data.isPanelActive(player.getUniqueId())) return;
        String abilityId = items.getAbilityId(event.getItem());
        if (abilityId == null) return;
        event.setCancelled(true);
        abilities.cast(player, abilityId);
    }

    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        Entity clicked = event.getRightClicked();

        if (villagers.isArmyActive(player.getUniqueId()) && clicked instanceof LivingEntity) {
            if (!(clicked instanceof Villager v && player.getUniqueId().equals(villagers.ownerOf(v)))) {
                villagers.setArmyTarget(player, clicked);
                event.setCancelled(true);
                return;
            }
        }

        if (clicked instanceof Villager villager) {
            UUID owner = villagers.ownerOf(villager);
            if (player.getUniqueId().equals(owner)) {
                event.setCancelled(true);
                villagers.handleVillagerInteract(player, villager);
            }
        }
    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent event) {
        if (event.isSneaking()) villagers.disbandArmy(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent event) {
        abilities.handleDamage(event);
        abilities.handleChameleonPassive(event);

        UUID damagerOwner = villagers.ownerOf(event.getDamager());
        if (damagerOwner != null && event.getEntity().getUniqueId().equals(damagerOwner)) event.setCancelled(true);
        UUID victimOwner = villagers.ownerOf(event.getEntity());
        if (victimOwner != null && event.getDamager().getUniqueId().equals(victimOwner)) event.setCancelled(true);
        if (event.getEntity() instanceof Player targetPlayer
                && data.getClass(targetPlayer.getUniqueId()) == ClassType.CHAMELEON
                && event.getDamager() instanceof Projectile projectile
                && projectile.getShooter() instanceof Monster) event.setCancelled(true);
    }

    @EventHandler
    public void onTarget(EntityTargetLivingEntityEvent event) {
        LivingEntity target = event.getTarget();
        if (target instanceof Player player && data.getClass(player.getUniqueId()) == ClassType.CHAMELEON) {
            event.setCancelled(true);
        }
        UUID owner = villagers.ownerOf(event.getEntity());
        if (owner != null && target != null && target.getUniqueId().equals(owner)) event.setCancelled(true);
    }

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        abilities.handleProjectileHit(event);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        villagers.onInventoryClose(event);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();
        Player player = (Player) event.getWhoClicked();
        if (title.equals("§8Выберите класс")) {
            event.setCancelled(true);
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || !clicked.hasItemMeta()) return;
            String raw = clicked.getItemMeta().getPersistentDataContainer()
                    .get(classMenuKey, PersistentDataType.STRING);
            ClassType type = ClassType.fromString(raw);
            if (type == null) return;
            data.setClass(player.getUniqueId(), type);
            player.closeInventory();
            player.sendMessage("§aВы выбрали класс: §f" + type.displayName());
            player.sendMessage("§7Нажмите F для панели способностей.");
            return;
        }
        if (title.equals("§2Профессия жителя") || title.equals("§2Приказы помещика")) {
            event.setCancelled(true);
            villagers.handleRoleMenuClick(player, event.getInventory(), event.getRawSlot());
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!data.isPanelActive(player.getUniqueId())) return;
        event.getDrops().removeIf(item -> items.getAbilityId(item) != null);
        ItemStack[] stored = data.getHotbar(player.getUniqueId());
        if (stored != null) {
            for (ItemStack item : stored) if (item != null && item.getType() != Material.AIR) event.getDrops().add(item);
        }
        data.clearHotbar(player.getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (data.isPanelActive(player.getUniqueId())) restorePanel(player);
        villagers.disbandArmy(player.getUniqueId());
        villagers.finishRidesForQuit(player.getUniqueId());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("class")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Команда доступна игроку.");
                return true;
            }
            ClassType type = data.getClass(player.getUniqueId());
            if (type == null) openClassMenu(player);
            else {
                player.sendMessage("§aВаш класс: §f" + type.displayName());
                player.sendMessage("§7F — открыть/закрыть панель способностей; ПКМ — применить выбранный слот.");
                player.sendMessage("§7Кулдауны сохраняются после выхода и смерти.");
            }
            return true;
        }
        if (command.getName().equalsIgnoreCase("classadmin")) {
            if (!sender.hasPermission("smpclasses.admin")) {
                sender.sendMessage("§cНедостаточно прав.");
                return true;
            }
            if (args.length == 3 && args[0].equalsIgnoreCase("set")) {
                Player target = Bukkit.getPlayerExact(args[1]);
                ClassType type = ClassType.fromString(args[2]);
                if (target == null || type == null) {
                    sender.sendMessage("§cИспользование: /classadmin set <игрок> <druid|duelist|summoner|slave_owner|chameleon>");
                    return true;
                }
                data.setClass(target.getUniqueId(), type);
                if (data.isPanelActive(target.getUniqueId())) restorePanel(target);
                sender.sendMessage("§aИгроку " + target.getName() + " назначен класс " + type.displayName());
                target.sendMessage("§aВаш класс изменён на " + type.displayName());
                return true;
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("reset")) {
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage("§cИгрок должен быть в сети.");
                    return true;
                }
                if (data.isPanelActive(target.getUniqueId())) restorePanel(target);
                data.setClass(target.getUniqueId(), null);
                openClassMenu(target);
                sender.sendMessage("§aВыбор класса сброшен для " + target.getName());
                return true;
            }
            if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                plugin.reloadConfig();
                sender.sendMessage("§aКонфигурация перезагружена.");
                return true;
            }
            sender.sendMessage("§e/classadmin set <игрок> <класс>");
            sender.sendMessage("§e/classadmin reset <игрок>");
            sender.sendMessage("§e/classadmin reload");
            return true;
        }
        return false;
    }
}