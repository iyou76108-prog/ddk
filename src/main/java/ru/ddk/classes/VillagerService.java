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
import org.bukkit.Input;

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
    private final Map<UUID, Villager> megaRides = new HashMap<>();

    private record Army(List<ZombieVillager> members, long expiresAt, UUID excludedPlayer) {
        Army withExcludedPlayer(UUID excluded) {
            return new Army(members, expiresAt, excluded);
        }
    }

    public VillagerService(SMPClassesPlugin plugin, PlayerDataStore data) {
        this.plugin = plugin;
        this.data = data;
        this.ownerKey = new NamespacedKey(plugin, "owner_uuid");
        this.kindKey = new NamespacedKey(plugin, "owned_kind");
        this.roleKey = new NamespacedKey(plugin, "villager_role");

        plugin.getServer().getScheduler().runTaskTimer(plugin, this::runWorkers, 20L, 20L);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::updateArmies, 5L, 5L);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::updateMegaRides, 1L, 1L);
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
        flag.setMarker(true);
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
        if (hand.getType() == Material.BREAD || hand.getType() == Material.CARROT) {
            addResident(player, villager, hand);
            return;
        }
        if (isWorkTool(hand)) {
            equipFromHand(player, villager);
            return;
        }

        openVillagerInventory(player, villager);
    }

    private void addResident(Player player, Villager near, ItemStack food) {
        if (food.getAmount() < 20) {
            player.sendMessage("§cНужно 20 хлеба или 20 моркови.");
            return;
        }
        long count = ownedEntities(player.getUniqueId(), "village").stream()
                .filter(entity -> entity instanceof Villager).count();
        int max = plugin.getConfig().getInt("limits.village_villagers", 8);
        if (count >= max) {
            player.sendMessage("§cЛимит деревни — " + max + " жителей.");
            return;
        }
        food.setAmount(food.getAmount() - 20);
        spawnVillager(player, near.getLocation().clone().add(1, 0, 0), "village");
        player.sendMessage("§aВ деревне появился новый житель. Всего: " + (count + 1) + "/" + max + ".");
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
        List<ZombieVillager> members = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            Location spawn = player.getLocation().clone().add((i % 5) - 2, 0, 2 + (i / 5));
            ZombieVillager soldier = (ZombieVillager) spawn.getWorld().spawnEntity(spawn, EntityType.ZOMBIE_VILLAGER);
            tag(soldier, player, "army");
            soldier.setAdult();
            soldier.setCanPickupItems(false);
            soldier.setCustomName("§cБоец помещика §7(" + player.getName() + ")");
            soldier.setCustomNameVisible(true);
            soldier.getAttribute(Attribute.MAX_HEALTH).setBaseValue(28.0);
            soldier.setHealth(28.0);
            soldier.getAttribute(Attribute.MOVEMENT_SPEED).setBaseValue(0.30);
            soldier.getAttribute(Attribute.ATTACK_DAMAGE).setBaseValue(7.0);
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
        long duration = plugin.getConfig().getLong("durations.army", 20L);
        armies.put(player.getUniqueId(), new Army(members, System.currentTimeMillis() + duration * 1000L, null));
        player.sendMessage("§cАрмия из 15 бойцов призвана на 20 секунд. ПКМ по игроку — исключить его из списка целей армии; все остальные игроки в радиусе 75 блоков станут целями. Shift — распустить.");
    }

    private ItemStack enchant(Material material, Enchantment enchantment, int level) {
        ItemStack item = new ItemStack(material);
        item.addUnsafeEnchantment(enchantment, level);
        return item;
    }

    public boolean isArmyActive(UUID ownerId) {
        return armies.containsKey(ownerId);
    }

    // A right-clicked player becomes immune to this army; the army hunts every other player nearby.
    public void setArmyTarget(Player owner, Entity clicked) {
        Army army = armies.get(owner.getUniqueId());
        if (army == null || !(clicked instanceof Player excluded) || excluded.equals(owner)) return;
        armies.put(owner.getUniqueId(), army.withExcludedPlayer(excluded.getUniqueId()));
        owner.sendMessage("§eАрмия не будет атаковать §f" + excluded.getName()
                + "§e. Все остальные игроки в радиусе 75 блоков — цели.");
        excluded.sendMessage("§aАрмия помещика вас не тронет, пока действует этот призыв.");
    }

    public void disbandArmy(UUID ownerId) {
        Army army = armies.remove(ownerId);
        if (army != null) army.members().forEach(Entity::remove);
    }

    public void disbandAll() {
        for (UUID owner : new ArrayList<>(armies.keySet())) disbandArmy(owner);
        for (Villager mount : new ArrayList<>(megaRides.values())) mount.remove();
        megaRides.clear();
        cleanupTransientEntities();
    }

    private void updateArmies() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Army>> iterator = armies.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Army> entry = iterator.next();
            UUID ownerId = entry.getKey();
            Army army = entry.getValue();
            Player owner = Bukkit.getPlayer(ownerId);
            if (owner == null || !owner.isOnline() || now >= army.expiresAt()) {
                army.members().forEach(Entity::remove);
                iterator.remove();
                continue;
            }

            for (int i = 0; i < army.members().size(); i++) {
                ZombieVillager soldier = army.members().get(i);
                if (!soldier.isValid() || soldier.isDead()) continue;
                Player target = soldier.getWorld().getPlayers().stream()
                        .filter(candidate -> !candidate.getUniqueId().equals(ownerId))
                        .filter(candidate -> army.excludedPlayer() == null
                                || !candidate.getUniqueId().equals(army.excludedPlayer()))
                        .filter(candidate -> candidate.getGameMode() != GameMode.SPECTATOR)
                        .filter(candidate -> candidate.getLocation().distanceSquared(owner.getLocation()) <= 75.0 * 75.0)
                        .min(Comparator.comparingDouble(candidate ->
                                candidate.getLocation().distanceSquared(soldier.getLocation())))
                        .orElse(null);

                if (target != null) {
                    soldier.setTarget(target);
                } else {
                    soldier.setTarget(null);
                    int row = i / 5;
                    int col = i % 5;
                    Vector facing = owner.getLocation().getDirection().setY(0).normalize();
                    if (facing.lengthSquared() < 0.01) facing = new Vector(0, 0, 1);
                    Vector side = new Vector(-facing.getZ(), 0, facing.getX());
                    Location formation = owner.getLocation().clone()
                            .subtract(facing.multiply(3 + row * 1.3))
                            .add(side.multiply(col - 2));
                    soldier.getPathfinder().moveTo(formation, 1.1);
                }
            }
        }
    }

    public boolean startMegaRide(Player player) {
        finishMegaRide(player.getUniqueId());
        Villager mount = spawnVillager(player, player.getLocation().clone().add(0, 0.3, 0), "mega");
        mount.setAI(false);
        mount.setAware(false);
        mount.setSilent(true);
        mount.setInvulnerable(false);
        mount.setGravity(true);
        mount.addPotionEffect(new org.bukkit.potion.PotionEffect(
                org.bukkit.potion.PotionEffectType.SPEED, 20 * 16, 3, false, false));
        if (!mount.addPassenger(player)) {
            mount.remove();
            player.sendMessage("§cНе удалось сесть на Мега жителя.");
            return false;
        }
        megaRides.put(player.getUniqueId(), mount);
        player.sendMessage("§aВы оседлали Мега жителя. WASD — ехать, пробел — прыгать. Действует 15 секунд.");
        long duration = plugin.getConfig().getLong("durations.mega_villager", 15L);
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> finishMegaRide(player.getUniqueId()), duration * 20L);
        return true;
    }

    private void updateMegaRides() {
        List<UUID> toFinish = new ArrayList<>();
        for (Map.Entry<UUID, Villager> entry : new ArrayList<>(megaRides.entrySet())) {
            Player rider = Bukkit.getPlayer(entry.getKey());
            Villager mount = entry.getValue();
            if (rider == null || !rider.isOnline() || rider.isDead()
                    || !mount.isValid() || rider.getVehicle() != mount) {
                toFinish.add(entry.getKey());
                continue;
            }

            Input input = rider.getCurrentInput();
            float yaw = rider.getLocation().getYaw();
            Vector forward = new Vector(-Math.sin(Math.toRadians(yaw)), 0, Math.cos(Math.toRadians(yaw)));
            Vector right = new Vector(forward.getZ(), 0, -forward.getX());
            Vector move = new Vector();
            if (input.isForward()) move.add(forward);
            if (input.isBackward()) move.subtract(forward);
            if (input.isRight()) move.add(right);
            if (input.isLeft()) move.subtract(right);
            if (move.lengthSquared() > 0) move.normalize().multiply(input.isSprint() ? 0.75 : 0.58);

            double y = mount.getVelocity().getY();
            if (input.isJump() && mount.isOnGround()) y = 0.42;
            mount.setVelocity(new Vector(move.getX(), y, move.getZ()));
            mount.setRotation(yaw, 0);
            rider.setFallDistance(0);
        }
        for (UUID owner : toFinish) finishMegaRide(owner);
    }

    public void finishRidesForQuit(UUID owner) {
        finishMegaRide(owner);
    }

    private void finishMegaRide(UUID owner) {
        Villager mount = megaRides.remove(owner);
        if (mount == null) return;
        Player rider = Bukkit.getPlayer(owner);
        if (rider != null && rider.isInsideVehicle()) {
            rider.leaveVehicle();
            Location safe = mount.getLocation().clone().add(0, 1, 0);
            rider.teleport(safe);
        }
        mount.remove();
        if (rider != null && rider.isOnline()) rider.sendMessage("§eПоездка на Мега жителе закончена.");
    }

    public void cleanupTransientEntities() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : new ArrayList<>(world.getEntities())) {
                String kind = kindOf(entity);
                if ("army".equals(kind) || "mega".equals(kind) || "minion".equals(kind)) entity.remove();
            }
        }
        armies.clear();
        megaRides.clear();
    }

    private void updatePassiveBonuses() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (data.getClass(player.getUniqueId()) != ClassType.SLAVE_OWNER) continue;
            double count = ownedVillagerCount(player.getUniqueId());
            var maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
            var armor = player.getAttribute(Attribute.ARMOR);
            if (maxHealth != null) {
                double targetHealth = Math.min(2048.0, 20.0 + count * 4.0);
                double oldHealth = maxHealth.getBaseValue();
                maxHealth.setBaseValue(targetHealth);
                if (targetHealth > oldHealth) player.setHealth(Math.min(targetHealth, player.getHealth() + targetHealth - oldHealth));
                else if (player.getHealth() > targetHealth) player.setHealth(targetHealth);
            }
            if (armor != null) armor.setBaseValue(count * 0.5);
        }
    }

    private void runWorkers() {
        long now = System.currentTimeMillis();
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (!(entity instanceof Villager villager) || !"village".equals(kindOf(villager))) continue;
                UUID ownerId = ownerOf(villager);
                if (ownerId == null) continue;
                Location flag = data.getFlag(ownerId);
                if (flag == null || !flag.getWorld().equals(villager.getWorld())) {
                    Player owner = Bukkit.getPlayer(ownerId);
                    if (owner != null && owner.getWorld().equals(villager.getWorld())
                            && owner.getLocation().distanceSquared(villager.getLocation()) > 16) {
                        villager.getPathfinder().moveTo(owner.getLocation(), 1.0);
                    }
                    continue;
                }
                if (flag.distanceSquared(villager.getLocation()) > 180.0 * 180.0) continue;

                String role = villager.getPersistentDataContainer().get(roleKey, PersistentDataType.STRING);
                if (!Set.of("miner", "woodcutter", "farmer").contains(role)) continue;

                Location target = workerTargets.get(villager.getUniqueId());
                if (target == null || !isWorkBlock(target.getBlock(), role)) {
                    if (now < nextSearchAt.getOrDefault(villager.getUniqueId(), 0L)) continue;
                    target = findWorkBlock(flag, role);
                    if (target == null) {
                        nextSearchAt.put(villager.getUniqueId(), now + 10000L);
                        continue;
                    }
                    workerTargets.put(villager.getUniqueId(), target);
                }

                if (target.getWorld() != villager.getWorld()) {
                    villager.teleport(flag.clone().add(0.5, 0, 0.5));
                    continue;
                }
                if (villager.getLocation().distanceSquared(target.clone().add(0.5, 0, 0.5)) > 3.2 * 3.2) {
                    villager.getPathfinder().moveTo(target.clone().add(0.5, 0, 0.5), 1.15);
                    continue;
                }

                long due = nextWorkAt.getOrDefault(villager.getUniqueId(), 0L);
                if (now < due) continue;
                boolean equipped = hasCorrectTool(villager, role);
                long interval = equipped ? 2500L : 37500L; // без инструмента в 15 раз медленнее
                harvestBlock(villager, target.getBlock(), role);
                nextWorkAt.put(villager.getUniqueId(), now + interval);
                workerTargets.remove(villager.getUniqueId());
                nextSearchAt.put(villager.getUniqueId(), now + 1000L);
            }
        }
    }

    private boolean hasCorrectTool(Villager villager, String role) {
        Material tool = villager.getEquipment().getItemInMainHand().getType();
        String name = tool.name();
        return switch (role) {
            case "miner" -> name.endsWith("_PICKAXE");
            case "woodcutter" -> name.endsWith("_AXE");
            case "farmer" -> name.endsWith("_HOE");
            default -> false;
        };
    }

    private Location findWorkBlock(Location flag, String role) {
        World world = flag.getWorld();
        int workRadius = plugin.getConfig().getInt("limits.work_radius", 180);
        Random random = ThreadLocalRandom.current();

        // Сначала детерминированно проверяем область вокруг флага, чтобы ферма или шахта не пропускалась.
        int nearRadius = "miner".equals(role) ? 16 : 32;
        int step = 2;
        int minY = world.getMinHeight() + 5;
        int maxY = Math.min(world.getMaxHeight() - 5, 120);

        for (int dx = -nearRadius; dx <= nearRadius; dx += step) {
            for (int dz = -nearRadius; dz <= nearRadius; dz += step) {
                if (dx * dx + dz * dz > nearRadius * nearRadius) continue;
                int x = flag.getBlockX() + dx;
                int z = flag.getBlockZ() + dz;
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;

                if ("miner".equals(role)) {
                    for (int y = minY; y <= maxY; y += 3) {
                        Block block = world.getBlockAt(x, y, z);
                        if (isWorkBlock(block, role)) return block.getLocation();
                    }
                } else {
                    int top = world.getHighestBlockYAt(x, z);
                    int bottom = Math.max(world.getMinHeight(), top - ("woodcutter".equals(role) ? 32 : 6));
                    for (int y = top; y >= bottom; y--) {
                        Block block = world.getBlockAt(x, y, z);
                        if (isWorkBlock(block, role)) return block.getLocation();
                    }
                }
            }
        }

        // Если рядом нет работы, ищем по всей разрешённой зоне до 180 блоков.
        for (int attempt = 0; attempt < 500; attempt++) {
            int dx = random.nextInt(-workRadius, workRadius + 1);
            int dz = random.nextInt(-workRadius, workRadius + 1);
            if (dx * dx + dz * dz > workRadius * workRadius) continue;
            int x = flag.getBlockX() + dx;
            int z = flag.getBlockZ() + dz;
            if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;

            if ("miner".equals(role)) {
                if (maxY <= minY) continue;
                int y = random.nextInt(minY, maxY);
                Block block = world.getBlockAt(x, y, z);
                if (isWorkBlock(block, role)) return block.getLocation();
            } else {
                int top = world.getHighestBlockYAt(x, z);
                int scan = "woodcutter".equals(role) ? 32 : 6;
                for (int y = top; y >= Math.max(world.getMinHeight(), top - scan); y--) {
                    Block block = world.getBlockAt(x, y, z);
                    if (isWorkBlock(block, role)) return block.getLocation();
                }
            }
        }
        return null;
    }

    private boolean isWorkBlock(Block block, String role) {
        Material type = block.getType();
        if ("miner".equals(role)) return type.name().endsWith("_ORE");
        if ("woodcutter".equals(role)) return type.name().endsWith("_LOG")
                || type.name().endsWith("_WOOD") || type == Material.CRIMSON_STEM || type == Material.WARPED_STEM;
        if ("farmer".equals(role) && block.getBlockData() instanceof Ageable ageable) {
            return ageable.getAge() >= ageable.getMaximumAge();
        }
        return false;
    }

    private void harvestBlock(Villager villager, Block block, String role) {
        if (!isWorkBlock(block, role)) return;
        Material toolMaterial = switch (role) {
            case "miner" -> Material.IRON_PICKAXE;
            case "woodcutter" -> Material.IRON_AXE;
            case "farmer" -> Material.IRON_HOE;
            default -> Material.AIR;
        };
        ItemStack tool = hasCorrectTool(villager, role)
                ? villager.getEquipment().getItemInMainHand().clone() : new ItemStack(toolMaterial);

        Collection<ItemStack> drops = block.getDrops(tool);
        addToStorage(villager, drops);

        if ("farmer".equals(role) && block.getBlockData() instanceof Ageable ageable) {
            ageable.setAge(0);
            block.setBlockData(ageable);
        } else {
            block.setType(Material.AIR);
        }
        villager.getWorld().spawnParticle(Particle.HAPPY_VILLAGER,
                block.getLocation().add(0.5, 0.8, 0.5), 5, 0.2, 0.2, 0.2, 0.02);
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
