package ru.ddk.classes;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public final class VillagerService {
    private final SMPClassesPlugin plugin;
    private final PlayerDataStore data;
    private final NamespacedKey ownerKey;
    private final NamespacedKey kindKey;
    private final NamespacedKey roleKey;
    private final Map<UUID, UUID> openedInventories = new HashMap<>();
    private final Map<UUID, String> pendingRoles = new HashMap<>();
    private final Map<UUID, Army> armies = new HashMap<>();
    private final Map<UUID, List<Villager>> megaRides = new HashMap<>();
    private final Map<UUID, Long> armyAttackTimes = new ConcurrentHashMap<>();

    private record Army(List<Villager> members, long expiresAt, UUID target) {
        Army withTarget(UUID newTarget) { return new Army(members, expiresAt, newTarget); }
    }

    public VillagerService(SMPClassesPlugin plugin, PlayerDataStore data) {
        this.plugin = plugin;
        this.data = data;
        this.ownerKey = new NamespacedKey(plugin, "owner_uuid");
        this.kindKey = new NamespacedKey(plugin, "owned_kind");
        this.roleKey = new NamespacedKey(plugin, "villager_role");
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::runWorkers, 100L, 100L);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::updateArmies, 1L, 1L);
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
        String raw = entity.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        if (raw == null) return null;
        try { return UUID.fromString(raw); } catch (IllegalArgumentException ignored) { return null; }
    }

    public String kindOf(Entity entity) {
        return entity.getPersistentDataContainer().get(kindKey, PersistentDataType.STRING);
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

    private long ownedCount(UUID owner) {
        return ownedEntities(owner, null).stream()
                .filter(entity -> entity instanceof Villager && !"flag".equals(kindOf(entity)))
                .count();
    }

    private Villager spawnVillager(Player owner, Location location, String kind) {
        Villager villager = (Villager) location.getWorld().spawnEntity(location, EntityType.VILLAGER);
        tag(villager, owner, kind);
        villager.setCustomName("§aЖитель §7(" + owner.getName() + ")");
        villager.setCustomNameVisible(true);
        villager.setAdult();
        villager.setCanPickupItems(false);
        return villager;
    }

    public boolean toggleFlag(Player player) {
        List<Entity> flags = ownedEntities(player.getUniqueId(), "flag");
        Location oldFlag = data.getFlag(player.getUniqueId());

        if (oldFlag != null || !flags.isEmpty()) {
            // Убираем видимый флаг, а деревенские жители скрываются в точке флага.
            for (Entity flag : flags) flag.remove();
            Location storage = oldFlag != null ? oldFlag : player.getLocation();
            for (Entity entity : ownedEntities(player.getUniqueId(), "village")) {
                if (entity instanceof Villager villager) {
                    villager.teleport(storage.clone().add(0.5, 0.1, 0.5));
                    villager.setInvisible(true);
                    villager.setInvulnerable(true);
                }
            }
            data.setFlag(player.getUniqueId(), null);
            player.sendMessage("§eФлаг убран. Жители спрятаны внутри флага.");
            return true;
        }

        Location target = player.getTargetBlockExact(6) == null
                ? player.getLocation().add(player.getLocation().getDirection().multiply(3))
                : player.getTargetBlockExact(6).getLocation().add(0, 1, 0);
        target.setYaw(player.getLocation().getYaw());
        data.setFlag(player.getUniqueId(), target);

        ArmorStand flag = (ArmorStand) target.getWorld().spawnEntity(target.clone().add(0.5, 0, 0.5), EntityType.ARMOR_STAND);
        tag(flag, player, "flag");
        flag.setInvisible(true);
        flag.setMarker(true);
        flag.setGravity(false);
        flag.setCustomName("§6Флаг помещика: §f" + player.getName());
        flag.setCustomNameVisible(true);
        flag.getEquipment().setHelmet(new ItemStack(Material.RED_BANNER));

        List<Entity> villagers = ownedEntities(player.getUniqueId(), "village");
        if (villagers.isEmpty()) {
            spawnVillager(player, target.clone().add(1, 0, 0), "village");
            spawnVillager(player, target.clone().add(-1, 0, 0), "village");
        } else {
            int offset = 0;
            for (Entity entity : villagers) {
                if (entity instanceof Villager villager) {
                    villager.setInvisible(false);
                    villager.setInvulnerable(false);
                    villager.teleport(target.clone().add((offset++ % 3) - 1, 0, (offset % 2) + 1));
                }
            }
        }
        player.sendMessage("§aФлаг установлен. Деревня принадлежит вам.");
        return true;
    }

    public void handleVillagerInteract(Player player, Villager villager) {
        UUID ownerId = ownerOf(villager);
        if (ownerId == null || !ownerId.equals(player.getUniqueId())) return;
        String kind = kindOf(villager);
        if (!"village".equals(kind)) return;

        String pending = pendingRoles.remove(player.getUniqueId());
        if (pending != null) {
            villager.getPersistentDataContainer().set(roleKey, PersistentDataType.STRING, pending);
            setProfession(villager, pending);
            player.sendMessage("§aПрофессия жителя изменена: §f" + pending);
            return;
        }

        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType() == Material.BREAD || hand.getType() == Material.CARROT) {
            if (hand.getAmount() < 20) {
                player.sendMessage("§cНужно минимум 20 хлеба или 20 моркови.");
                return;
            }
            long count = ownedEntities(ownerId, "village").stream().filter(Villager.class::isInstance).count();
            int max = plugin.getConfig().getInt("limits.village_villagers", 8);
            if (count >= max) {
                player.sendMessage("§cДостигнут лимит жителей: " + max);
                return;
            }
            hand.setAmount(hand.getAmount() - 20);
            spawnVillager(player, villager.getLocation().add(1, 0, 0), "village");
            player.sendMessage("§aК деревне присоединился новый житель.");
            return;
        }

        if (player.isSneaking()) {
            openRoleMenu(player);
        } else {
            openVillagerInventory(player, villager);
        }
    }

    public void openRoleMenu(Player player) {
        Inventory menu = Bukkit.createInventory(null, 9, "§2Профессия жителя");
        put(menu, 0, Material.IRON_SWORD, "§fМечник", "swordsman");
        put(menu, 1, Material.BOW, "§fЛучник", "archer");
        put(menu, 2, Material.IRON_PICKAXE, "§fШахтёр", "miner");
        put(menu, 3, Material.IRON_AXE, "§fДровосек", "woodcutter");
        put(menu, 4, Material.IRON_HOE, "§fФермер", "farmer");
        player.openInventory(menu);
    }

    public void openWorkMenu(Player player) {
        Inventory menu = Bukkit.createInventory(null, 9, "§2Приказы помещика");
        put(menu, 0, Material.IRON_SWORD, "§fНазначить мечника", "swordsman");
        put(menu, 1, Material.BOW, "§fНазначить лучника", "archer");
        put(menu, 2, Material.IRON_PICKAXE, "§fНазначить шахтёра", "miner");
        put(menu, 3, Material.IRON_AXE, "§fНазначить дровосека", "woodcutter");
        put(menu, 4, Material.IRON_HOE, "§fНазначить фермера", "farmer");
        player.openInventory(menu);
    }

    private void put(Inventory inv, int slot, Material material, String name, String role) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(List.of("§7Выбрать роль", "§0" + role));
        item.setItemMeta(meta);
        inv.setItem(slot, item);
    }

    public boolean handleRoleMenuClick(Player player, Inventory inventory, int slot) {
        if (inventory.getSize() != 9 || slot < 0 || slot > 4) return false;
        ItemStack item = inventory.getItem(slot);
        if (item == null || !item.hasItemMeta() || item.getItemMeta().getLore() == null) return false;
        List<String> lore = item.getItemMeta().getLore();
        if (lore.size() < 2) return false;
        String role = lore.get(1).replace("§0", "");
        pendingRoles.put(player.getUniqueId(), role);
        player.closeInventory();
        player.sendMessage("§aТеперь нажмите ПКМ по своему жителю, чтобы назначить роль: §f" + role);
        return true;
    }

    private void setProfession(Villager villager, String role) {
        switch (role) {
            case "swordsman" -> villager.setProfession(Villager.Profession.WEAPONSMITH);
            case "archer" -> villager.setProfession(Villager.Profession.FLETCHER);
            case "miner" -> villager.setProfession(Villager.Profession.TOOLSMITH);
            case "woodcutter" -> villager.setProfession(Villager.Profession.BUTCHER);
            case "farmer" -> villager.setProfession(Villager.Profession.FARMER);
            default -> villager.setProfession(Villager.Profession.NONE);
        }
    }

    private void openVillagerInventory(Player player, Villager villager) {
        Inventory inventory = Bukkit.createInventory(null, 27, "§8Склад жителя");
        ItemStack[] contents = data.getVillagerInventory(villager.getUniqueId());
        inventory.setContents(contents);
        openedInventories.put(player.getUniqueId(), villager.getUniqueId());
        player.openInventory(inventory);
    }

    public void onInventoryClose(InventoryCloseEvent event) {
        UUID villagerId = openedInventories.remove(event.getPlayer().getUniqueId());
        if (villagerId != null && event.getView().getTitle().equals("§8Склад жителя")) {
            ItemStack[] contents = event.getInventory().getContents();
            // Первый слот — инструмент жителя, второй — стрелы для лучника.
            Villager villager = null;
            Entity entity = Bukkit.getEntity(villagerId);
            if (entity instanceof Villager found) villager = found;
            if (villager != null && contents.length > 0 && contents[0] != null) {
                Material gear = contents[0].getType();
                if (gear.name().endsWith("_SWORD") || gear.name().endsWith("_PICKAXE")
                        || gear.name().endsWith("_AXE") || gear.name().endsWith("_HOE")
                        || gear == Material.BOW) {
                    villager.getEquipment().setItemInMainHand(contents[0].clone());
                    villager.getEquipment().setItemInMainHandDropChance(0.0f);
                    contents[0] = null;
                }
                if (contents.length > 1 && contents[1] != null && contents[1].getType() == Material.ARROW) {
                    villager.getEquipment().setItemInOffHand(contents[1].clone());
                    villager.getEquipment().setItemInOffHandDropChance(0.0f);
                    contents[1] = null;
                }
            }
            data.setVillagerInventory(villagerId, contents);
        }
    }

    public void handleMenuClick(Player player, Inventory inventory, int slot) {
        String title = inventory.getViewers().isEmpty() ? "" : inventory.getViewers().get(0).getOpenInventory().getTitle();
        if (title.equals("§2Профессия жителя") || title.equals("§2Приказы помещика")) {
            handleRoleMenuClick(player, inventory, slot);
        }
    }

    public void startArmy(Player player) {
        Army existing = armies.remove(player.getUniqueId());
        if (existing != null) existing.members().forEach(Entity::remove);

        List<Villager> members = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            Location spawn = player.getLocation().clone().add((i % 5) - 2, 0, (i / 5) + 2);
            Villager villager = spawnVillager(player, spawn, "army");
            villager.setAI(false);
            villager.setSilent(true);
            villager.setCustomName("§cБоец §7(" + player.getName() + ")");
            villager.setProfession(Villager.Profession.WEAPONSMITH);
            ItemStack[] armor = new ItemStack[] {
                    enchanted(Material.LEATHER_BOOTS, Enchantment.PROTECTION, 3),
                    enchanted(Material.LEATHER_LEGGINGS, Enchantment.PROTECTION, 3),
                    enchanted(Material.LEATHER_CHESTPLATE, Enchantment.PROTECTION, 3),
                    enchanted(Material.LEATHER_HELMET, Enchantment.PROTECTION, 3)
            };
            villager.getEquipment().setArmorContents(armor);
            villager.getEquipment().setItemInMainHand(enchanted(Material.STONE_SWORD, Enchantment.SHARPNESS, 3));
            villager.getEquipment().setBootsDropChance(1.0f);
            villager.getEquipment().setLeggingsDropChance(1.0f);
            villager.getEquipment().setChestplateDropChance(1.0f);
            villager.getEquipment().setHelmetDropChance(1.0f);
            villager.getEquipment().setItemInMainHandDropChance(1.0f);
            members.add(villager);
        }
        armies.put(player.getUniqueId(), new Army(members,
                System.currentTimeMillis() + plugin.getConfig().getLong("durations.army", 23) * 1000L, null));
        player.sendMessage("§cАрмия призвана! ПКМ по врагу — приказ атаковать. Shift — распустить.");
    }

    private ItemStack enchanted(Material material, Enchantment enchantment, int level) {
        ItemStack item = new ItemStack(material);
        item.addUnsafeEnchantment(enchantment, level);
        return item;
    }

    public boolean isArmyActive(UUID ownerId) {
        return armies.containsKey(ownerId);
    }

    public void setArmyTarget(Player owner, Entity target) {
        Army army = armies.get(owner.getUniqueId());
        if (army == null || target == owner || !(target instanceof LivingEntity)) return;
        armies.put(owner.getUniqueId(), army.withTarget(target.getUniqueId()));
        owner.sendMessage("§cАрмия атакует: §f" + (target instanceof Player p ? p.getName() : target.getType().name()));
    }

    public void disbandArmy(UUID ownerId) {
        Army army = armies.remove(ownerId);
        if (army != null) army.members().forEach(Entity::remove);
    }

    public void disbandAll() {
        new ArrayList<>(armies.keySet()).forEach(this::disbandArmy);
        for (List<Villager> ride : megaRides.values()) ride.forEach(Entity::remove);
        megaRides.clear();
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
            LivingEntity target = null;
            if (army.target() != null) {
                Entity entity = Bukkit.getEntity(army.target());
                if (entity instanceof LivingEntity living && living.isValid() && !living.isDead()) target = living;
                else armies.put(ownerId, army.withTarget(null));
            }
            int index = 0;
            for (Villager member : army.members()) {
                if (!member.isValid() || member.isDead()) continue;
                Location goal;
                if (target != null) {
                    goal = target.getLocation();
                } else {
                    int row = index / 5;
                    int col = index % 5;
                    Vector forward = owner.getLocation().getDirection().setY(0).normalize();
                    Vector side = new Vector(-forward.getZ(), 0, forward.getX());
                    goal = owner.getLocation().clone().add(forward.multiply(-2 - row * 1.3)).add(side.multiply(col - 2));
                }
                index++;
                Vector delta = goal.toVector().subtract(member.getLocation().toVector());
                if (delta.lengthSquared() > 1.8 * 1.8) {
                    Vector velocity = delta.normalize().multiply(target == null ? 0.32 : 0.38);
                    velocity.setY(Math.min(0.35, Math.max(-0.12, velocity.getY() + 0.08)));
                    member.setVelocity(velocity);
                } else if (target != null && target.getLocation().distanceSquared(member.getLocation()) <= 3.5 * 3.5) {
                    long last = armyAttackTimes.getOrDefault(member.getUniqueId(), 0L);
                    if (now - last >= 1000L) {
                        target.damage(3.5, member);
                        armyAttackTimes.put(member.getUniqueId(), now);
                    }
                }
            }
        }
    }

    public void startMegaRide(Player player) {
        finishMegaRide(player.getUniqueId());
        Location base = player.getLocation().clone().add(0, 0, 1);
        Villager bottom = spawnVillager(player, base, "mega");
        Villager middle = spawnVillager(player, base, "mega");
        Villager top = spawnVillager(player, base, "mega");
        bottom.setAI(false);
        middle.setAI(false);
        top.setAI(false);
        bottom.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20 * 16, 3, false, true));
        bottom.addPassenger(middle);
        middle.addPassenger(top);
        top.addPassenger(player);
        megaRides.put(player.getUniqueId(), List.of(bottom, middle, top));

        plugin.getServer().getScheduler().runTaskTimer(plugin, task -> {
            List<Villager> ride = megaRides.get(player.getUniqueId());
            if (ride == null || !player.isOnline() || player.isDead() || !top.isValid()) {
                task.cancel();
                finishMegaRide(player.getUniqueId());
                return;
            }
            Vector direction = player.getLocation().getDirection().setY(0).normalize();
            bottom.setVelocity(direction.multiply(0.75).setY(player.isOnGround() ? 0.08 : bottom.getVelocity().getY()));
        }, 1L, 1L);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> finishMegaRide(player.getUniqueId()),
                plugin.getConfig().getLong("durations.mega_villager", 15) * 20L);
    }

    public void finishRidesForQuit(UUID owner) {
        finishMegaRide(owner);
    }

    private void finishMegaRide(UUID owner) {
        List<Villager> ride = megaRides.remove(owner);
        Player player = Bukkit.getPlayer(owner);
        if (player != null && player.isInsideVehicle()) player.leaveVehicle();
        if (ride != null) ride.forEach(Entity::remove);
        if (player != null && player.isOnline()) player.sendMessage("§eМега житель завершил поездку.");
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
            ClassType type = data.getClass(player.getUniqueId());
            var maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
            var armor = player.getAttribute(Attribute.ARMOR);
            if (maxHealth == null) continue;

            double bonusHealth = 0.0;
            double bonusArmor = 0.0;
            if (type == ClassType.SLAVE_OWNER) {
                long count = ownedCount(player.getUniqueId());
                bonusHealth = count * 4.0; // +2 сердца за каждого принадлежащего владельцу жителя
                bonusArmor = count * 0.5;
            } else if (type == ClassType.CHAMELEON) {
                int leaves = 0;
                Location center = player.getLocation();
                for (int x = -3; x <= 3; x++) for (int y = -3; y <= 3; y++) for (int z = -3; z <= 3; z++) {
                    Material material = center.clone().add(x, y, z).getBlock().getType();
                    if (material.name().endsWith("_LEAVES")) leaves++;
                }
                bonusHealth = leaves * 0.5;
            }
            double newMax = Math.min(2048.0, 20.0 + bonusHealth);
            double oldMax = maxHealth.getBaseValue();
            maxHealth.setBaseValue(newMax);
            if (newMax > oldMax) player.setHealth(Math.min(newMax, player.getHealth() + newMax - oldMax));
            else if (player.getHealth() > newMax) player.setHealth(newMax);
            if (armor != null) armor.setBaseValue(bonusArmor);
        }
    }

    private void runWorkers() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (!(entity instanceof Villager villager) || !"village".equals(kindOf(villager))) continue;
                UUID ownerId = ownerOf(villager);
                if (ownerId == null || villager.isInvisible()) continue;
                Location flag = data.getFlag(ownerId);
                if (flag == null || !flag.getWorld().equals(villager.getWorld())
                        || flag.distanceSquared(villager.getLocation()) > Math.pow(plugin.getConfig().getDouble("limits.work_radius", 180), 2)) continue;
                String role = villager.getPersistentDataContainer().get(roleKey, PersistentDataType.STRING);
                if (role == null) continue;
                if (!hasRequiredGear(villager, role) && ThreadLocalRandom.current().nextInt(15) != 0) continue;
                harvestNearby(villager, role);
            }
        }
    }

    private boolean hasRequiredGear(Villager villager, String role) {
        EntityEquipment equipment = villager.getEquipment();
        if (equipment == null) return false;
        Material hand = equipment.getItemInMainHand().getType();
        return switch (role) {
            case "swordsman" -> hand.name().endsWith("_SWORD");
            case "archer" -> hand == Material.BOW && equipment.getItemInOffHand().getType() == Material.ARROW;
            case "miner" -> hand.name().endsWith("_PICKAXE");
            case "woodcutter" -> hand.name().endsWith("_AXE");
            case "farmer" -> hand.name().endsWith("_HOE");
            default -> false;
        };
    }

    private void harvestNearby(Villager villager, String role) {
        Inventory inventory = Bukkit.createInventory(null, 27);
        inventory.setContents(data.getVillagerInventory(villager.getUniqueId()));
        Location center = villager.getLocation();
        int radius = 5;
        outer:
        for (int x = -radius; x <= radius; x++) for (int y = -2; y <= 2; y++) for (int z = -radius; z <= radius; z++) {
            Location at = center.clone().add(x, y, z);
            if (!at.getChunk().isLoaded()) continue;
            Material type = at.getBlock().getType();
            if ("farmer".equals(role) && at.getBlock().getBlockData() instanceof org.bukkit.block.data.Ageable ageable
                    && ageable.getAge() == ageable.getMaximumAge()) {
                Collection<ItemStack> drops = at.getBlock().getDrops(villager.getEquipment().getItemInMainHand());
                for (ItemStack drop : drops) inventory.addItem(drop);
                ageable.setAge(0);
                at.getBlock().setBlockData(ageable);
                break outer;
            }
            if ("woodcutter".equals(role) && (type.name().endsWith("_LOG") || type.name().endsWith("_WOOD"))) {
                Collection<ItemStack> drops = at.getBlock().getDrops(villager.getEquipment().getItemInMainHand());
                for (ItemStack drop : drops) inventory.addItem(drop);
                at.getBlock().setType(Material.AIR);
                break outer;
            }
            if ("miner".equals(role) && type.name().endsWith("_ORE")) {
                Collection<ItemStack> drops = at.getBlock().getDrops(villager.getEquipment().getItemInMainHand());
                for (ItemStack drop : drops) inventory.addItem(drop);
                at.getBlock().setType(Material.AIR);
                break outer;
            }
        }
        data.setVillagerInventory(villager.getUniqueId(), inventory.getContents());
    }
}