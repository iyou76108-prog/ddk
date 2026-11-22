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
    private final Map<UUID, UUID> recentSummonerDamageOwners = new HashMap<>();

    public GameListener(SMPClassesPlugin plugin, PlayerDataStore data, AbilityItems items,
                        AbilityEngine abilities, VillagerService villagers) {
        this.plugin = plugin;
        this.data = data;
        this.items = items;
        this.abilities = abilities;
        this.villagers = villagers;
        this.classMenuKey = new NamespacedKey(plugin, "class_menu");
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::refreshDynamicAbilitySlots, 20L, 20L);
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
        ItemStack[] panel = items.forClass(type, abilities.getCopiedSkillId(player.getUniqueId()));
        if (type == ClassType.SLAVE_OWNER) panel[1] = items.abilityItem(abilities.getOwnerSlotAbilityId(player.getUniqueId()));
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
        refreshChameleonCopySlot(player);
        refreshSlaveOwnerSlot(player);
    }

    @EventHandler
    public void onArmorStandManipulate(PlayerArmorStandManipulateEvent event) {
        if (villagers.handleFlagInteract(event.getPlayer(), event.getRightClicked())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        Entity clicked = event.getRightClicked();

        if (villagers.isArmyActive(player.getUniqueId()) && clicked instanceof Player) {
            villagers.setArmyTarget(player, clicked);
            event.setCancelled(true);
            return;
        }

        if (villagers.handleFlagInteract(player, clicked)) {
            event.setCancelled(true);
            return;
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
    public void onDamage(EntityDamageByEntityEvent event) {
        Entity heldBy = event.getDamager();
        if (heldBy instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) heldBy = shooter;
        String heldAbilityId = heldBy instanceof Player attacker
                ? items.getAbilityId(attacker.getInventory().getItemInMainHand()) : null;
        abilities.handleSlaveOwnerCombat(event, heldAbilityId);
        abilities.handleDamage(event);
        abilities.handleChameleonPassive(event);

        Entity source = event.getDamager();
        if (source instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
            source = shooter;
        }
        UUID damagerOwner = villagers.ownerOf(source);
        UUID victimOwner = villagers.ownerOf(event.getEntity());

        // Ни один призыв или боец не может нанести урон своему хозяину или союзным сущностям.
        if (damagerOwner != null && (event.getEntity().getUniqueId().equals(damagerOwner)
                || damagerOwner.equals(victimOwner))) {
            event.setCancelled(true);
        }
        if (victimOwner != null && source.getUniqueId().equals(victimOwner)) event.setCancelled(true);

        if (!event.isCancelled()) {
            UUID summonerOwner = resolveSummonerAttacker(event.getDamager());
            if (summonerOwner != null) {
                recentSummonerDamageOwners.put(event.getEntity().getUniqueId(), summonerOwner);
            } else {
                recentSummonerDamageOwners.remove(event.getEntity().getUniqueId());
            }
        }
    }

    private UUID resolveSummonerAttacker(Entity attacker) {
        Entity source = attacker;
        if (source instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
            source = shooter;
        }
        if (source instanceof Player player) {
            return data.getClass(player.getUniqueId()) == ClassType.SUMMONER ? player.getUniqueId() : null;
        }
        UUID owner = villagers.ownerOf(source);
        return owner != null && data.getClass(owner) == ClassType.SUMMONER ? owner : null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        UUID owner = recentSummonerDamageOwners.remove(event.getEntity().getUniqueId());
        if (owner == null && event.getEntity().getLastDamageCause() instanceof EntityDamageByEntityEvent damage) {
            owner = resolveSummonerAttacker(damage.getDamager());
        }
        if (owner == null || data.getClass(owner) != ClassType.SUMMONER) return;

        int kills = data.addSummonerKill(owner);
        if (kills % 50 == 0) {
            Player summoner = Bukkit.getPlayer(owner);
            if (summoner != null) {
                summoner.sendMessage("§5Некротический пакт усилился! Убийств: " + kills
                        + ". Навсегда получено +1 сердце и +1 броня.");
                summoner.playSound(summoner.getLocation(), Sound.ENTITY_WITHER_AMBIENT, 0.8f, 1.5f);
            }
        }
    }

    @EventHandler
    public void onTarget(EntityTargetLivingEntityEvent event) {
        LivingEntity target = event.getTarget();
        UUID attackerOwner = villagers.ownerOf(event.getEntity());
        if (target instanceof Player player && data.getClass(player.getUniqueId()) == ClassType.CHAMELEON) {
            boolean summonerMinion = attackerOwner != null
                    && data.getClass(attackerOwner) == ClassType.SUMMONER
                    && "minion".equals(villagers.kindOf(event.getEntity()));
            if (!summonerMinion) event.setCancelled(true);
        }

        UUID targetOwner = villagers.ownerOf(target);
        if (attackerOwner != null && target != null
                && (target.getUniqueId().equals(attackerOwner) || attackerOwner.equals(targetOwner))) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        abilities.handleProjectileHit(event);
    }

    private void refreshDynamicAbilitySlots() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            refreshChameleonCopySlot(player);
            refreshSlaveOwnerSlot(player);
        }
    }

    private void refreshSlaveOwnerSlot(Player player) {
        if (!data.isPanelActive(player.getUniqueId())
                || data.getClass(player.getUniqueId()) != ClassType.SLAVE_OWNER) return;
        ItemStack expected = items.abilityItem(abilities.getOwnerSlotAbilityId(player.getUniqueId()));
        String expectedId = items.getAbilityId(expected);
        String currentId = items.getAbilityId(player.getInventory().getItem(1));
        if (!Objects.equals(expectedId, currentId)) {
            player.getInventory().setItem(1, expected);
            player.updateInventory();
        }
    }

    private void refreshChameleonCopySlot(Player player) {
        if (!data.isPanelActive(player.getUniqueId())
                || data.getClass(player.getUniqueId()) != ClassType.CHAMELEON) return;
        ItemStack expected = items.forClass(ClassType.CHAMELEON,
                abilities.getCopiedSkillId(player.getUniqueId()))[4];
        String expectedId = items.getAbilityId(expected);
        String currentId = items.getAbilityId(player.getInventory().getItem(4));
        if (!Objects.equals(expectedId, currentId)) {
            player.getInventory().setItem(4, expected);
            player.updateInventory();
        }
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
                    sender.sendMessage("§cИспользование: /classadmin set <игрок> <druid|duelist|summoner|slave_owner|chameleon|archmage>");
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