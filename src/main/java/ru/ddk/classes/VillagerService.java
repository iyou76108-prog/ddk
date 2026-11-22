package ru.ddk.classes;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.*;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class VillagerService {
    private static final String STOCK_TITLE = "§8Склад жителя";
    private static final String ROLE_TITLE = "§2Приказы помещика";

    private final SMPClassesPlugin plugin;
    private final PlayerDataStore data;
    private final NamespacedKey ownerKey;
    private final NamespacedKey kindKey;
    private final NamespacedKey roleKey;

    private final Map<UUID, UUID> openedInventories = new HashMap<>();
    private final Map<UUID, String> pendingRoles = new HashMap<>();
    private final Map<UUID, Location> workerTargets = new HashMap<>();
    private final Map<UUID, Long> nextWorkAt = new HashMap<>();
    private final Map<UUID, Long> nextSearchAt = new HashMap<>();
    private final Map<UUID, Army> armies = new HashMap<>();
    private final Map<UUID, Long> armyAttackTimes = new HashMap<>();
    private final Map<UUID, Horse> horseRides = new HashMap<>();

    private record Army(List<Villager> members, Set<UUID> excludedPlayers) {
        Army withExcludedPlayer(UUID excluded) {
            Set<UUID> updated = new HashSet<>(excludedPlayers);
            updated.add(excluded);
            return new Army(members, Set.copyOf(updated));
        }
    }

    public VillagerService(SMPClassesPlugin plugin, PlayerDataStore data) {
        this.plugin = plugin;
        this.data = data;
        this.ownerKey = new NamespacedKey(plugin, "owner_uuid");
        this.kindKey = new NamespacedKey(plugin, "owned_kind");
        this.roleKey = new NamespacedKey(plugin, "villager_role");

        plugin.getServer().getScheduler().runTaskTimer(plugin, this::runWorkers, 20L, 20L);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::updateGuards, 20L, 20L);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::updateArmies, 5L, 5L);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::updateHorseRides, 1L, 1L);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::updatePassiveBonuses, 40L, 40L);
        plugin.getServer().getScheduler().runTaskLater(plugin, this::cleanupTransientEntities, 20L);
    }

    public NamespacedKey ownerKey() { return ownerKey; }
    public NamespacedKey kindKey() { return kindKey; }

    private void tag(Entity entity, Player owner, String kind) {
        entity.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, owner.getUniqueId().toString());
        entity.getPersistentDataContainer().set(kindKey, PersistentDataType.STRING, kind);
        entity.setPersistent(true);
    }

    public UUID ownerOf(Entity entity) {
        if (entity == null) return null;
        String raw = entity.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        if (raw == null) return null;
        try { return UUID.fromString(raw); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    public String kindOf(Entity entity) {
        return entity == null ? null
                : entity.getPersistentDataContainer().get(kindKey, PersistentDataType.STRING);
    }

    private List<Entity> ownedEntities(UUID owner, String kind) {
        List<Entity> result = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (owner.equals(ownerOf(entity)) && (kind == null || kind.equals(kindOf(entity)))) result.add(entity);
            }
        }
        return result;
    }

    private long ownedVillagerCount(UUID owner) {
        return ownedEntities(owner, null).stream()
                .filter(entity -> entity instanceof LivingEntity)
                .filter(entity -> Set.of("village", "army", "mega").contains(kindOf(entity)))
                .count();
    }

    private Villager spawnVillager(Player owner, Location location, String kind) {
        Villager villager = (Villager) location.getWorld().spawnEntity(location, EntityType.VILLAGER);
        tag(villager, owner, kind);
        villager.setCustomName("§aЖитель §7(" + owner.getName() + ")");
        villager.setCustomNameVisible(true);
        villager.setAdult();
        villager.setAI(true);
        villager.setAware(true);
        villager.setCanPickupItems(false);
        return villager;
    }

    // Skill 1: place or pick up the flag. Picking it up does not delete the village.
    public boolean toggleFlag(Player player) {
        Location oldFlag = data.getFlag(player.getUniqueId());
        List<Entity> flags = ownedEntities(player.getUniqueId(), "flag");

        if (oldFlag != null || !flags.isEmpty()) {
            for (Entity flag : flags) flag.remove();
            data.setFlag(player.getUniqueId(), null);
            workerTargets.clear();
            player.sendMessage("§eВы подняли флаг. Жители и их профессии сохранены; поставьте флаг снова, чтобы возобновить работу.");
            return true;
        }

        Block targetBlock = player.getTargetBlockExact(6);
        Location target = targetBlock == null
                ? player.getLocation().clone().add(player.getLocation().getDirection().normalize().multiply(3)).getBlock().getLocation()
                : targetBlock.getRelative(0, 1, 0).getLocation();
        target.setYaw(player.getLocation().getYaw());
        target.setPitch(0);

        data.setFlag(player.getUniqueId(), target);
        ArmorStand flag = (ArmorStand) target.getWorld().spawnEntity(
                target.clone().add(0.5, 0, 0.5), EntityType.ARMOR_STAND);
        tag(flag, player, "flag");
        flag.setVisible(false);
        flag.setMarker(false);
        flag.setGravity(false);
        flag.setInvulnerable(false);
        flag.setCustomName("§6Флаг помещика: §f" + player.getName());
        flag.setCustomNameVisible(true);
        flag.getEquipment().setHelmet(new ItemStack(Material.RED_BANNER));

        List<Entity> residents = ownedEntities(player.getUniqueId(), "village");
        if (residents.isEmpty()) {
            spawnVillager(player, target.clone().add(1, 0, 0), "village");
            spawnVillager(player, target.clone().add(-1, 0, 0), "village");
        } else {
            int offset = 0;
            for (Entity entity : residents) {
                if (entity instanceof Villager villager) {
                    villager.setInvisible(false);
                    villager.setInvulnerable(false);
                    villager.teleport(target.clone().add((offset % 3) - 1, 0, 1 + (offset / 3)));
                    offset++;
                }
            }
        }
        player.sendMessage("§aФлаг установлен. Деревня активна. Жителей можно назначать и экипировать.");
        return true;
    }

    public void handleVillagerInteract(Player player, Villager villager) {
        if (!player.getUniqueId().equals(ownerOf(villager)) || !"village".equals(kindOf(villager))) return;

        String pending = pendingRoles.remove(player.getUniqueId());
        if (pending != null) {
            setRole(villager, pending);
            player.sendMessage("§aЖитель назначен: §f" + roleName(pending) + "§a.");
            if (isWorkTool(player.getInventory().getItemInMainHand())) {
                equipFromHand(player, villager);
            } else {
                player.sendMessage("§7ПКМ по жителю с киркой/топором/мотыгой/мечом/луком выдаёт ему инструмент. ПКМ пустой рукой открывает склад.");
            }
            return;
        }

        ItemStack hand = player.getInventory().getItemInMainHand();
        if (isWorkTool(hand)) {
            equipFromHand(player, villager);
            return;
        }

        openVillagerInventory(player, villager);
    }

    public boolean handleFlagInteract(Player player, Entity entity) {
        if (!"flag".equals(kindOf(entity)) || !player.getUniqueId().equals(ownerOf(entity))) return false;
        if (countIngredient(player, Material.BREAD) < 20
                || countIngredient(player, Material.CARROT) < 20) {
            player.sendMessage("§cДля улучшения флага нужно 20 хлеба И 20 моркови.");
            return true;
        }
        long count = ownedEntities(player.getUniqueId(), "village").stream()
                .filter(Villager.class::isInstance).count();
        int max = plugin.getConfig().getInt("limits.village_villagers", 8);
        if (count >= max) {
            player.sendMessage("§cДеревня уже достигла лимита " + max + " жителей.");
            return true;
        }
        removeIngredient(player, Material.BREAD, 20);
        removeIngredient(player, Material.CARROT, 20);
        Location spawn = entity.getLocation().clone().add(1.5, 0, 0.5);
        spawnVillager(player, spawn, "village");
        player.sendMessage("§aФлаг улучшен: появился новый житель. Деревня: " + (count + 1) + "/" + max + ".");
        return true;
    }

    private int countIngredient(Player player, Material material) {
        int total = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null && item.getType() == material) total += item.getAmount();
        }
        return total;
    }

    private void removeIngredient(Player player, Material material, int amount) {
        int remaining = amount;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item == null || item.getType() != material) continue;
            int remove = Math.min(remaining, item.getAmount());
            item.setAmount(item.getAmount() - remove);
            remaining -= remove;
            if (remaining == 0) break;
        }
    }

    private String roleForTool(Material type) {
        String name = type.name();
        if (name.endsWith("_SWORD")) return "swordsman";
        if (type == Material.BOW || type == Material.CROSSBOW) return "archer";
        if (name.endsWith("_PICKAXE")) return "miner";
        if (name.endsWith("_AXE")) return "woodcutter";
        if (name.endsWith("_HOE")) return "farmer";
        return null;
    }

    private boolean isWorkTool(ItemStack item) {
        return item != null && !item.getType().isAir() && roleForTool(item.getType()) != null;
    }

    private void equipFromHand(Player player, Villager villager) {
        ItemStack held = player.getInventory().getItemInMainHand();
        String role = roleForTool(held.getType());
        if (role == null) return;

        ItemStack previous = villager.getEquipment().getItemInMainHand();
        if (previous != null && !previous.getType().isAir()) {
            HashMap<Integer, ItemStack> leftovers = player.getInventory().addItem(previous.clone());
            leftovers.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
        }
        villager.getEquipment().setItemInMainHand(held.clone());
        villager.getEquipment().setItemInMainHandDropChance(0.0f);
        held.setAmount(held.getAmount() - 1);
        setRole(villager, role);
        player.sendMessage("§aВыдали жителю предмет: §f" + held.getType().name() + "§a. Профессия: §f" + roleName(role) + "§a.");
        player.sendMessage("§7Он начнёт работать в пределах 180 блоков от флага. Результаты забирайте ПКМ пустой рукой.");
    }

    private void setRole(Villager villager, String role) {
        villager.getPersistentDataContainer().set(roleKey, PersistentDataType.STRING, role);
        switch (role) {
            case "swordsman" -> villager.setProfession(Villager.Profession.WEAPONSMITH);
            case "archer" -> villager.setProfession(Villager.Profession.FLETCHER);
            case "miner" -> villager.setProfession(Villager.Profession.TOOLSMITH);
            case "woodcutter" -> villager.setProfession(Villager.Profession.BUTCHER);
            case "farmer" -> villager.setProfession(Villager.Profession.FARMER);
            default -> villager.setProfession(Villager.Profession.NONE);
        }
        workerTargets.remove(villager.getUniqueId());
        nextWorkAt.put(villager.getUniqueId(), 0L);
        nextSearchAt.put(villager.getUniqueId(), 0L);
    }

    private String roleName(String role) {
        return switch (role) {
            case "swordsman" -> "Мечник";
            case "archer" -> "Лучник";
            case "miner" -> "Шахтёр";
            case "woodcutter" -> "Дровосек";
            case "farmer" -> "Фермер";
            default -> role;
        };
    }

    public void openWorkMenu(Player player) {
        if (data.getFlag(player.getUniqueId()) == null) {
            player.sendMessage("§cСначала установите флаг способностью «Моё место».");
            return;
        }
        Inventory menu = Bukkit.createInventory(null, 9, ROLE_TITLE);
        put(menu, 0, Material.IRON_SWORD, "§fНазначить мечника", "swordsman");
        put(menu, 1, Material.BOW, "§fНазначить лучника", "archer");
        put(menu, 2, Material.IRON_PICKAXE, "§fНазначить шахтёра", "miner");
        put(menu, 3, Material.IRON_AXE, "§fНазначить дровосека", "woodcutter");
        put(menu, 4, Material.IRON_HOE, "§fНазначить фермера", "farmer");
        player.openInventory(menu);
        player.sendMessage("§7Выберите профессию, затем ПКМ по своему жителю. Для работы выдайте подходящий инструмент.");
    }

    private void put(Inventory inventory, int slot, Material material, String name, String role) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(List.of("§7Выбрать профессию", "§0" + role));
        item.setItemMeta(meta);
        inventory.setItem(slot, item);
    }

    public boolean handleRoleMenuClick(Player player, Inventory inventory, int slot) {
        if (slot < 0 || slot > 4 || inventory.getSize() != 9) return false;
        ItemStack item = inventory.getItem(slot);
        if (item == null || !item.hasItemMeta() || item.getItemMeta().getLore() == null) return false;
        List<String> lore = item.getItemMeta().getLore();
        if (lore.size() < 2) return false;
        pendingRoles.put(player.getUniqueId(), lore.get(1).replace("§0", ""));
        player.closeInventory();
        player.sendMessage("§aТеперь нажмите ПКМ по своему жителю, чтобы назначить ему эту профессию.");
        return true;
    }

    private void openVillagerInventory(Player player, Villager villager) {
        Inventory inventory = Bukkit.createInventory(null, 27, STOCK_TITLE);
        ItemStack[] contents = data.getVillagerInventory(villager.getUniqueId());
        ItemStack hand = villager.getEquipment().getItemInMainHand();
        if (hand != null && !hand.getType().isAir() && contents[0] == null) {
            contents[0] = hand.clone();
            villager.getEquipment().setItemInMainHand(null);
        }
        inventory.setContents(contents);
        openedInventories.put(player.getUniqueId(), villager.getUniqueId());
        player.openInventory(inventory);
        player.sendMessage("§7Склад жителя: положите кирку/меч/топор/мотку/лук в любой слот и закройте окно — предмет экипируется. Добыча остаётся в остальных слотах.");
    }

    public void onInventoryClose(InventoryCloseEvent event) {
        UUID villagerId = openedInventories.remove(event.getPlayer().getUniqueId());
        if (villagerId == null || !STOCK_TITLE.equals(event.getView().getTitle())) return;

        Entity entity = Bukkit.getEntity(villagerId);
        ItemStack[] contents = event.getInventory().getContents();
        if (entity instanceof Villager villager) {
            String role = villager.getPersistentDataContainer().get(roleKey, PersistentDataType.STRING);
            for (int slot = 0; slot < contents.length; slot++) {
                ItemStack item = contents[slot];
                if (item == null || item.getType().isAir()) continue;

                String inferred = roleForTool(item.getType());
                if (inferred != null && (role == null || role.equals(inferred))
                        && villager.getEquipment().getItemInMainHand().getType().isAir()) {
                    villager.getEquipment().setItemInMainHand(item.clone());
                    villager.getEquipment().setItemInMainHandDropChance(0.0f);
                    if (role == null) setRole(villager, inferred);
                    contents[slot] = null;
                    continue;
                }
                if (isArmor(item.getType())) {
                    equipArmor(villager, item);
                    contents[slot] = null;
                }
            }
        }
        data.setVillagerInventory(villagerId, contents);
    }

    private boolean isArmor(Material material) {
        String name = material.name();
        return name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE")
                || name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS");
    }

    private void equipArmor(Villager villager, ItemStack item) {
        EntityEquipment equipment = villager.getEquipment();
        String name = item.getType().name();
        ItemStack old = null;
        if (name.endsWith("_HELMET")) { old = equipment.getHelmet(); equipment.setHelmet(item.clone()); }
        else if (name.endsWith("_CHESTPLATE")) { old = equipment.getChestplate(); equipment.setChestplate(item.clone()); }
        else if (name.endsWith("_LEGGINGS")) { old = equipment.getLeggings(); equipment.setLeggings(item.clone()); }
        else if (name.endsWith("_BOOTS")) { old = equipment.getBoots(); equipment.setBoots(item.clone()); }
        if (old != null && !old.getType().isAir()) {
            Inventory storage = Bukkit.createInventory(null, 27);
            storage.setContents(data.getVillagerInventory(villager.getUniqueId()));
            HashMap<Integer, ItemStack> leftovers = storage.addItem(old);
            leftovers.values().forEach(drop -> villager.getWorld().dropItemNaturally(villager.getLocation(), drop));
            data.setVillagerInventory(villager.getUniqueId(), storage.getContents());
        }
    }

    public void startArmy(Player player) {
        disbandArmy(player.getUniqueId());
        data.clearArmyExcludedPlayers(player.getUniqueId());
        List<Villager> members = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            Location spawn = player.getLocation().clone().add((i % 5) - 2, 0, 2 + (i / 5));
            Villager soldier = spawnVillager(player, spawn, "army");
            soldier.setAI(true);
            soldier.setAware(true);
            soldier.setProfession(Villager.Profession.WEAPONSMITH);
            soldier.setCustomName("§cСтраж помещика §7(" + player.getName() + ")");
            soldier.getAttribute(Attribute.MAX_HEALTH).setBaseValue(32.0);
            soldier.setHealth(32.0);
            soldier.getAttribute(Attribute.MOVEMENT_SPEED).setBaseValue(0.32);
            EntityEquipment gear = soldier.getEquipment();
            gear.setBoots(enchant(Material.LEATHER_BOOTS, Enchantment.PROTECTION, 3));
            gear.setLeggings(enchant(Material.LEATHER_LEGGINGS, Enchantment.PROTECTION, 3));
            gear.setChestplate(enchant(Material.LEATHER_CHESTPLATE, Enchantment.PROTECTION, 3));
            gear.setHelmet(enchant(Material.LEATHER_HELMET, Enchantment.PROTECTION, 3));
            gear.setItemInMainHand(enchant(Material.STONE_SWORD, Enchantment.SHARPNESS, 3));
            gear.setBootsDropChance(1.0f);
            gear.setLeggingsDropChance(1.0f);
            gear.setChestplateDropChance(1.0f);
            gear.setHelmetDropChance(1.0f);
            gear.setItemInMainHandDropChance(1.0f);
            members.add(soldier);
        }
        armies.put(player.getUniqueId(), new Army(members, data.getArmyExcludedPlayers(player.getUniqueId())));
        player.sendMessage("§c15 стражников призваны. Они не исчезают по таймеру и охраняют вас, пока их не убьют.");
        player.sendMessage("§7ПКМ по игроку — исключить его из целей всей армии. Все остальные игроки в радиусе 75 блоков атакуются.");
    }

    private ItemStack enchant(Material material, Enchantment enchantment, int level) {
        ItemStack item = new ItemStack(material);
        item.addUnsafeEnchantment(enchantment, level);
        return item;
    }

    public boolean isArmyActive(UUID ownerId) {
        if (armies.containsKey(ownerId)) return true;
        return !ownedEntities(ownerId, "army").isEmpty();
    }

    public void setArmyTarget(Player owner, Entity clicked) {
        Army army = armies.get(owner.getUniqueId());
        if (army == null) {
            List<Villager> existing = ownedEntities(owner.getUniqueId(), "army").stream()
                    .filter(Villager.class::isInstance).map(Villager.class::cast).toList();
            if (existing.isEmpty()) return;
            army = new Army(existing, data.getArmyExcludedPlayers(owner.getUniqueId()));
        }
        if (!(clicked instanceof Player excluded) || excluded.equals(owner)) return;
        data.addArmyExcludedPlayer(owner.getUniqueId(), excluded.getUniqueId());
        armies.put(owner.getUniqueId(), army.withExcludedPlayer(excluded.getUniqueId()));
        owner.sendMessage("§eИгрок §f" + excluded.getName()
                + " §eдобавлен в список исключений. Все остальные игроки в радиусе 75 блоков остаются целями.");
        excluded.sendMessage("§aСтражники этого помещика не будут вас атаковать.");
    }

    public void disbandArmy(UUID ownerId) {
        Army army = armies.remove(ownerId);
        List<Entity> members = ownedEntities(ownerId, "army");
        if (army != null) army.members().forEach(Entity::remove);
        members.forEach(Entity::remove);
    }

    public void disbandAll() {
        // Армия намеренно переживает reload: она состоит из постоянных помеченных жителей.
        for (Horse horse : new ArrayList<>(horseRides.values())) horse.remove();
        horseRides.clear();
        cleanupTransientEntities();
    }

    private void updateArmies() {
        // Восстанавливаем список стражников после перезагрузки плагина.
        for (Player owner : Bukkit.getOnlinePlayers()) {
            if (!armies.containsKey(owner.getUniqueId())) {
                List<Villager> restored = ownedEntities(owner.getUniqueId(), "army").stream()
                        .filter(Villager.class::isInstance).map(Villager.class::cast).toList();
                if (!restored.isEmpty()) armies.put(owner.getUniqueId(),
                        new Army(restored, data.getArmyExcludedPlayers(owner.getUniqueId())));
            }
        }

        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Army>> iterator = armies.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Army> entry = iterator.next();
            UUID ownerId = entry.getKey();
            Army army = entry.getValue();
            Player owner = Bukkit.getPlayer(ownerId);
            if (owner == null || !owner.isOnline()) continue;

            List<Villager> alive = army.members().stream()
                    .filter(Entity::isValid).filter(v -> !v.isDead()).toList();
            if (alive.isEmpty()) {
                iterator.remove();
                continue;
            }

            for (int i = 0; i < alive.size(); i++) {
                Villager soldier = alive.get(i);
                Player target = soldier.getWorld().getPlayers().stream()
                        .filter(candidate -> !candidate.getUniqueId().equals(ownerId))
                        .filter(candidate -> !army.excludedPlayers().contains(candidate.getUniqueId()))
                        .filter(candidate -> candidate.getGameMode() != GameMode.SPECTATOR)
                        .filter(candidate -> candidate.getLocation().distanceSquared(owner.getLocation()) <= 75.0 * 75.0)
                        .min(Comparator.comparingDouble(candidate ->
                                candidate.getLocation().distanceSquared(soldier.getLocation())))
                        .orElse(null);

                if (target != null) {
                    soldier.getPathfinder().moveTo(target.getLocation(), 1.25);
                    if (target.getLocation().distanceSquared(soldier.getLocation()) <= 2.8 * 2.8) {
                        long last = armyAttackTimes.getOrDefault(soldier.getUniqueId(), 0L);
                        if (now - last >= 1000L) {
                            target.damage(6.0, soldier);
                            target.setNoDamageTicks(0);
                            armyAttackTimes.put(soldier.getUniqueId(), now);
                        }
                    }
                } else {
                    int row = i / 5;
                    int col = i % 5;
                    Vector forward = owner.getLocation().getDirection().setY(0).normalize();
                    if (forward.lengthSquared() < 0.01) forward = new Vector(0, 0, 1);
                    Vector side = new Vector(-forward.getZ(), 0, forward.getX());
                    Location formation = owner.getLocation().clone()
                            .subtract(forward.clone().multiply(3 + row * 1.4))
                            .add(side.multiply(col - 2));
                    if (soldier.getLocation().distanceSquared(formation) > 4.0) {
                        soldier.getPathfinder().moveTo(formation, 1.15);
                    }
                }
            }
        }
    }

    public boolean startHorseRide(Player player) {
        finishHorseRide(player.getUniqueId());

        Location spawn = player.getLocation().clone().add(0, 0.2, 0);
        Horse horse = (Horse) spawn.getWorld().spawnEntity(spawn, EntityType.HORSE);
        tag(horse, player, "horse_ride");
        horse.setAdult();
        horse.setTamed(true);
        horse.setOwner(player);
        horse.setColor(Horse.Color.CHESTNUT);
        horse.setCustomName("§6Конь стремительного ветра");
        horse.setCustomNameVisible(true);
        horse.setSilent(false);
        horse.setInvulnerable(false);
        horse.setJumpStrength(0.95);
        horse.getInventory().setSaddle(new ItemStack(Material.SADDLE));
        horse.addPotionEffect(new org.bukkit.potion.PotionEffect(
                org.bukkit.potion.PotionEffectType.SPEED, 20 * 20, 3, false, false));

        if (!horse.addPassenger(player)) {
            horse.remove();
            player.sendMessage("§cНе удалось оседлать коня.");
            return false;
        }

        horseRides.put(player.getUniqueId(), horse);
        player.sendMessage("§a§lКонный прорыв! §fВы оседлали быстрого коня: Скорость IV на 20 секунд.");
        long duration = Math.max(1L, plugin.getConfig().getLong("durations.horse_ride", 20L));
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> finishHorseRide(player.getUniqueId()), duration * 20L);
        return true;
    }

    private void updateHorseRides() {
        List<UUID> toFinish = new ArrayList<>();
        for (Map.Entry<UUID, Horse> entry : new ArrayList<>(horseRides.entrySet())) {
            Player rider = Bukkit.getPlayer(entry.getKey());
            Horse horse = entry.getValue();
            if (rider == null || !rider.isOnline() || !horse.isValid() || horse.isDead()) {
                toFinish.add(entry.getKey());
            }
        }
        for (UUID owner : toFinish) finishHorseRide(owner);
    }

    public void finishRidesForQuit(UUID owner) {
        finishHorseRide(owner);
    }

    private void finishHorseRide(UUID owner) {
        Horse horse = horseRides.remove(owner);
        if (horse == null) return;

        Player rider = Bukkit.getPlayer(owner);
        Location end = horse.getLocation().clone();
        if (rider != null && rider.isOnline() && rider.getVehicle() == horse) {
            rider.leaveVehicle();
            rider.teleport(end.add(0, 1, 0));
        }

        if (horse.isValid()) {
            horse.getWorld().spawnParticle(Particle.CLOUD, horse.getLocation().add(0, 1, 0),
                    18, 0.45, 0.45, 0.45, 0.03);
            horse.getWorld().playSound(horse.getLocation(), Sound.ENTITY_HORSE_DEATH, 0.8f, 0.8f);
            horse.remove();
        }
        if (rider != null && rider.isOnline()) {
            rider.sendMessage("§eКонь стремительного ветра истощился и исчез.");
        }
    }

    private void updatePassiveBonuses() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            ClassType type = data.getClass(player.getUniqueId());
            var maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
            var armor = player.getAttribute(Attribute.ARMOR);
            if (maxHealth == null) continue;

            double healthBonus = 0.0;
            double armorBonus = 0.0;
            if (type == ClassType.SLAVE_OWNER) {
                long count = ownedVillagerCount(player.getUniqueId());
                healthBonus += count * 4.0;
                armorBonus += count * 0.5;
            }
            if (type == ClassType.SUMMONER) {
                int milestones = data.getSummonerKills(player.getUniqueId()) / 50;
                healthBonus += milestones * 2.0; // +1 сердце за каждые 50 убийств
                armorBonus += milestones * 1.0;
            }
            if (type == ClassType.CHAMELEON) {
                int leaves = 0;
                Location center = player.getLocation();
                for (int x = -3; x <= 3; x++) for (int y = -3; y <= 3; y++) for (int z = -3; z <= 3; z++) {
                    if (center.clone().add(x, y, z).getBlock().getType().name().endsWith("_LEAVES")) leaves++;
                }
                healthBonus += leaves * 0.5;
            }

            double targetHealth = Math.min(2048.0, 20.0 + healthBonus);
            double oldHealth = maxHealth.getBaseValue();
            maxHealth.setBaseValue(targetHealth);
            if (targetHealth > oldHealth) {
                player.setHealth(Math.min(targetHealth, player.getHealth() + targetHealth - oldHealth));
            } else if (player.getHealth() > targetHealth) {
                player.setHealth(targetHealth);
            }
            if (armor != null) armor.setBaseValue(armorBonus);
        }
    }

    // Professions generate goods passively in the villager's own stored inventory.
    private void runWorkers() {
        long now = System.currentTimeMillis();
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (!(entity instanceof Villager villager) || !"village".equals(kindOf(villager))) continue;
                UUID ownerId = ownerOf(villager);
                if (ownerId == null) continue;
                Location flag = data.getFlag(ownerId);
                if (flag == null || flag.getWorld() != villager.getWorld()
                        || flag.distanceSquared(villager.getLocation()) > 180.0 * 180.0) continue;

                String role = villager.getPersistentDataContainer().get(roleKey, PersistentDataType.STRING);
                if (!Set.of("miner", "woodcutter", "farmer").contains(role)) continue;
                long due = nextWorkAt.getOrDefault(villager.getUniqueId(), 0L);
                if (now < due) continue;

                Material tool = villager.getEquipment().getItemInMainHand().getType();
                int tier = toolTier(tool, role);
                long interval = tier == 0 ? 450000L : 30000L;
                nextWorkAt.put(villager.getUniqueId(), now + interval);
                addPassiveProfessionLoot(villager, role, tier);
            }
        }
    }

    private int toolTier(Material tool, String role) {
        String name = tool.name();
        boolean correct = switch (role) {
            case "miner" -> name.endsWith("_PICKAXE");
            case "woodcutter" -> name.endsWith("_AXE");
            case "farmer" -> name.endsWith("_HOE");
            default -> false;
        };
        if (!correct) return 0;
        if (name.startsWith("NETHERITE_")) return 5;
        if (name.startsWith("DIAMOND_")) return 4;
        if (name.startsWith("IRON_")) return 3;
        if (name.startsWith("STONE_")) return 2;
        if (name.startsWith("WOODEN_") || name.startsWith("GOLDEN_")) return 1;
        return 0;
    }

    private void addPassiveProfessionLoot(Villager villager, String role, int tier) {
        List<ItemStack> drops = new ArrayList<>();
        Random random = ThreadLocalRandom.current();
        if ("miner".equals(role)) {
            switch (tier) {
                case 5 -> {
                    drops.add(new ItemStack(Material.RAW_IRON, 4 + random.nextInt(5)));
                    drops.add(new ItemStack(Material.RAW_GOLD, 2 + random.nextInt(3)));
                    drops.add(new ItemStack(Material.DIAMOND, 1 + random.nextInt(2)));
                    if (random.nextInt(5) == 0) drops.add(new ItemStack(Material.ANCIENT_DEBRIS));
                }
                case 4 -> {
                    drops.add(new ItemStack(Material.RAW_IRON, 3 + random.nextInt(4)));
                    drops.add(new ItemStack(Material.RAW_GOLD, 1 + random.nextInt(3)));
                    drops.add(new ItemStack(Material.REDSTONE, 4 + random.nextInt(7)));
                    if (random.nextInt(4) == 0) drops.add(new ItemStack(Material.DIAMOND));
                }
                case 3 -> {
                    drops.add(new ItemStack(Material.RAW_IRON, 3 + random.nextInt(4)));
                    drops.add(new ItemStack(Material.COAL, 3 + random.nextInt(5)));
                    drops.add(new ItemStack(Material.LAPIS_LAZULI, 2 + random.nextInt(4)));
                }
                case 2 -> {
                    drops.add(new ItemStack(Material.COAL, 2 + random.nextInt(4)));
                    drops.add(new ItemStack(Material.RAW_IRON, 1 + random.nextInt(3)));
                }
                default -> drops.add(new ItemStack(Material.COAL, 1 + random.nextInt(2)));
            }
        } else if ("woodcutter".equals(role)) {
            Material log = switch (tier) {
                case 5, 4 -> Material.SPRUCE_LOG;
                case 3 -> Material.OAK_LOG;
                case 2 -> Material.BIRCH_LOG;
                default -> Material.OAK_LOG;
            };
            drops.add(new ItemStack(log, tier == 0 ? 1 : 3 + random.nextInt(Math.max(1, tier * 2))));
            if (tier >= 4 && random.nextInt(3) == 0) drops.add(new ItemStack(Material.STICK, 2 + random.nextInt(4)));
        } else if ("farmer".equals(role)) {
            Material crop = switch (tier) {
                case 5 -> Material.GOLDEN_CARROT;
                case 4 -> Material.CARROT;
                case 3 -> Material.WHEAT;
                case 2 -> Material.POTATO;
                default -> Material.BEETROOT;
            };
            drops.add(new ItemStack(crop, tier == 0 ? 1 : 4 + random.nextInt(5)));
            if (tier >= 3) drops.add(new ItemStack(Material.BREAD, 1 + random.nextInt(3)));
            if (tier >= 5 && random.nextInt(4) == 0) drops.add(new ItemStack(Material.GOLDEN_APPLE));
        }
        addToStorage(villager, drops);
        villager.getWorld().spawnParticle(Particle.HAPPY_VILLAGER,
                villager.getLocation().add(0, 1.2, 0), 3, 0.2, 0.3, 0.2, 0.02);
    }

    private void updateGuards() {
        for (World world : Bukkit.getWorlds()) {
            List<Villager> residents = world.getEntities().stream()
                    .filter(Villager.class::isInstance).map(Villager.class::cast)
                    .filter(v -> "village".equals(kindOf(v))).toList();
            for (Villager guard : residents) {
                UUID ownerId = ownerOf(guard);
                if (ownerId == null) continue;
                String role = guard.getPersistentDataContainer().get(roleKey, PersistentDataType.STRING);
                if (!"swordsman".equals(role) && !"archer".equals(role)) continue;

                Location flag = data.getFlag(ownerId);
                if (flag == null || flag.getWorld() != world
                        || flag.distanceSquared(guard.getLocation()) > 180.0 * 180.0) continue;

                Monster threat = world.getEntities().stream()
                        .filter(Monster.class::isInstance).map(Monster.class::cast)
                        .filter(Monster::isValid).filter(m -> !m.isDead())
                        .filter(m -> m.getLocation().distanceSquared(guard.getLocation()) <= 18.0 * 18.0)
                        .filter(m -> isThreatToOwner(m, ownerId))
                        .min(Comparator.comparingDouble(m -> m.getLocation().distanceSquared(guard.getLocation())))
                        .orElse(null);

                if (threat != null) {
                    guard.getPathfinder().moveTo(threat.getLocation(), 1.15);
                    if ("swordsman".equals(role) && threat.getLocation().distanceSquared(guard.getLocation()) <= 2.8 * 2.8) {
                        long due = nextWorkAt.getOrDefault(guard.getUniqueId(), 0L);
                        if (System.currentTimeMillis() >= due) {
                            threat.damage(5.0, guard);
                            nextWorkAt.put(guard.getUniqueId(), System.currentTimeMillis() + 1000L);
                        }
                    } else if ("archer".equals(role) && threat.getLocation().distanceSquared(guard.getLocation()) <= 18.0 * 18.0) {
                        long due = nextWorkAt.getOrDefault(guard.getUniqueId(), 0L);
                        if (System.currentTimeMillis() >= due) {
                            Vector direction = threat.getEyeLocation().toVector()
                                    .subtract(guard.getEyeLocation().toVector()).normalize();
                            Arrow arrow = world.spawnArrow(guard.getEyeLocation(), direction, 1.7f, 0.5f);
                            arrow.setShooter(guard);
                            arrow.setDamage(4.0);
                            arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
                            nextWorkAt.put(guard.getUniqueId(), System.currentTimeMillis() + 1400L);
                        }
                    }
                } else {
                    Villager worker = residents.stream()
                            .filter(other -> other != guard && ownerId.equals(ownerOf(other)))
                            .filter(other -> !Set.of("swordsman", "archer").contains(
                                    other.getPersistentDataContainer().get(roleKey, PersistentDataType.STRING)))
                            .min(Comparator.comparingDouble(other ->
                                    other.getLocation().distanceSquared(guard.getLocation())))
                            .orElse(null);
                    if (worker != null && worker.getLocation().distanceSquared(guard.getLocation()) > 9.0) {
                        guard.getPathfinder().moveTo(worker.getLocation(), 1.0);
                    } else if (guard.getLocation().distanceSquared(flag) > 16.0) {
                        guard.getPathfinder().moveTo(flag, 1.0);
                    }
                }
            }
        }
    }

    private boolean isThreatToOwner(Monster monster, UUID owner) {
        LivingEntity target = monster.getTarget();
        if (target == null) return true;
        if (target.getUniqueId().equals(owner)) return true;
        return owner.equals(ownerOf(target));
    }

    private void addToStorage(Villager villager, Collection<ItemStack> drops) {
        if (drops == null || drops.isEmpty()) return;
        Inventory storage = Bukkit.createInventory(null, 27);
        storage.setContents(data.getVillagerInventory(villager.getUniqueId()));
        for (ItemStack drop : drops) {
            if (drop == null || drop.getType().isAir()) continue;
            Map<Integer, ItemStack> overflow = storage.addItem(drop.clone());
            overflow.values().forEach(item ->
                    villager.getWorld().dropItemNaturally(villager.getLocation(), item));
        }
        data.setVillagerInventory(villager.getUniqueId(), storage.getContents());
    }
}