package ru.ddk.classes;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class AbilityEngine {
    private final SMPClassesPlugin plugin;
    private final PlayerDataStore data;
    private final VillagerService villagers;
    private final NamespacedKey ownerKey;
    private final NamespacedKey kindKey;
    private final NamespacedKey projectileKey;
    private final Map<UUID, Long> parryUntil = new HashMap<>();
    private final Map<UUID, UUID> challengedTargets = new HashMap<>();
    private final Map<UUID, Long> challengeUntil = new HashMap<>();
    private final Map<UUID, CopiedSkill> copies = new HashMap<>();

    private record CopiedSkill(String abilityId, long expiresAt) {}

    private static final Map<String, String> COOLDOWN_PATHS = Map.ofEntries(
            Map.entry("druid_rootstorm", "cooldowns.druid.rootstorm"),
            Map.entry("druid_wildcharge", "cooldowns.druid.wildcharge"),
            Map.entry("druid_sporeburst", "cooldowns.druid.sporeburst"),
            Map.entry("druid_avatar", "cooldowns.druid.avatar"),
            Map.entry("duelist_parry", "cooldowns.duelist.parry"),
            Map.entry("duelist_lunge", "cooldowns.duelist.lunge"),
            Map.entry("duelist_challenge", "cooldowns.duelist.challenge"),
            Map.entry("duelist_whirlwind", "cooldowns.duelist.whirlwind"),
            Map.entry("duelist_last", "cooldowns.duelist.last_round"),
            Map.entry("summoner_hound", "cooldowns.summoner.hound"),
            Map.entry("summoner_archers", "cooldowns.summoner.archers"),
            Map.entry("summoner_knight", "cooldowns.summoner.knight"),
            Map.entry("summoner_legion", "cooldowns.summoner.legion"),
            Map.entry("owner_place", "cooldowns.slave_owner.place"),
            Map.entry("owner_work", "cooldowns.slave_owner.work_order"),
            Map.entry("owner_army", "cooldowns.slave_owner.army"),
            Map.entry("owner_mega", "cooldowns.slave_owner.horse_ride"),
            Map.entry("chameleon_camouflage", "cooldowns.chameleon.camouflage"),
            Map.entry("chameleon_tongue", "cooldowns.chameleon.tongue"),
            Map.entry("chameleon_copy", "cooldowns.chameleon.copy"),
            Map.entry("archmage_firebolt", "cooldowns.archmage.firebolt"),
            Map.entry("archmage_blink", "cooldowns.archmage.blink"),
            Map.entry("archmage_meteor", "cooldowns.archmage.meteor"),
            Map.entry("archmage_lava", "cooldowns.archmage.lava"),
            Map.entry("archmage_golem", "cooldowns.archmage.golem")
    );

    public AbilityEngine(SMPClassesPlugin plugin, PlayerDataStore data, VillagerService villagers) {
        this.plugin = plugin;
        this.data = data;
        this.villagers = villagers;
        this.ownerKey = villagers.ownerKey();
        this.kindKey = villagers.kindKey();
        this.projectileKey = new NamespacedKey(plugin, "projectile_ability");
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::updateClassPassives, 20L, 20L);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::updateMinionAI, 10L, 10L);
    }

    public void cast(Player player, String id) {
        if (id == null) return;
        ClassType playerClass = data.getClass(player.getUniqueId());
        CopiedSkill activeCopy = copies.get(player.getUniqueId());
        boolean borrowedSkill = playerClass == ClassType.CHAMELEON && activeCopy != null
                && activeCopy.expiresAt() > System.currentTimeMillis() && activeCopy.abilityId().equals(id);
        if (playerClass == null || (!belongsToClass(playerClass, id) && !borrowedSkill)) {
            player.sendMessage("§cЭта способность недоступна вашему классу.");
            return;
        }

        if ("chameleon_copy".equals(id)) {
            handleCopy(player);
            return;
        }
        if (isPassive(id)) {
            player.sendMessage("§7Это пассивная способность.");
            return;
        }

        boolean flagAlreadyPlaced = "owner_place".equals(id) && data.getFlag(player.getUniqueId()) != null;
        if (!flagAlreadyPlaced && !ready(player, id)) return;

        boolean success = switch (id) {
            case "druid_rootstorm" -> druidRootstorm(player);
            case "druid_wildcharge" -> druidWildcharge(player);
            case "druid_sporeburst" -> druidSporeburst(player);
            case "druid_avatar" -> druidAvatar(player);
            case "duelist_parry" -> duelistParry(player);
            case "duelist_lunge" -> duelistLunge(player);
            case "duelist_challenge" -> duelistChallenge(player);
            case "duelist_whirlwind" -> duelistWhirlwind(player);
            case "duelist_last" -> duelistLast(player);
            case "summoner_hound" -> summonHound(player);
            case "summoner_archers" -> summonArchers(player);
            case "summoner_knight" -> summonKnight(player);
            case "summoner_legion" -> summonLegion(player);
            case "owner_place" -> villagers.toggleFlag(player);
            case "owner_work" -> { villagers.openWorkMenu(player); yield true; }
            case "owner_army" -> { villagers.startArmy(player); yield true; }
            case "owner_mega" -> villagers.startHorseRide(player);
            case "chameleon_camouflage" -> chameleonCamouflage(player);
            case "chameleon_tongue" -> chameleonTongue(player);
            case "archmage_firebolt" -> archmageFirebolt(player);
            case "archmage_blink" -> archmageBlink(player);
            case "archmage_meteor" -> archmageMeteor(player);
            case "archmage_lava" -> archmageLava(player);
            case "archmage_golem" -> archmageGolem(player);
            default -> false;
        };

        if (success && !flagAlreadyPlaced) startCooldown(player, id);
    }

    private boolean belongsToClass(ClassType type, String id) {
        return switch (type) {
            case DRUID -> id.startsWith("druid_");
            case DUELIST -> id.startsWith("duelist_");
            case SUMMONER -> id.startsWith("summoner_");
            case SLAVE_OWNER -> id.startsWith("owner_");
            case CHAMELEON -> id.startsWith("chameleon_");
            case ARCHMAGE -> id.startsWith("archmage_");
        };
    }

    private boolean isPassive(String id) {
        return Set.of("druid_passive", "summoner_bond", "owner_passive",
                "chameleon_passive", "chameleon_life").contains(id);
    }

    public String getCopiedSkillId(UUID playerId) {
        CopiedSkill skill = copies.get(playerId);
        if (skill == null) return null;
        if (skill.expiresAt() <= System.currentTimeMillis()) {
            copies.remove(playerId);
            return null;
        }
        return skill.abilityId();
    }

    private void updateClassPassives() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            ClassType type = data.getClass(player.getUniqueId());
            if (type == ClassType.DRUID && nearForest(player.getLocation())) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 45, 0, true, false));
                player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 45, 0, true, false));
            }
            if (type == ClassType.ARCHMAGE && isInLava(player)) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 60, 0, true, false));
                player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 60, 2, true, false));
            }
            if (type == ClassType.CHAMELEON) {
                for (Entity entity : player.getNearbyEntities(24, 12, 24)) {
                    if (!(entity instanceof Mob monster)) continue;
                    String rawOwner = monster.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
                    boolean necromancerSummon = false;
                    if (rawOwner != null) {
                        try {
                            UUID owner = UUID.fromString(rawOwner);
                            necromancerSummon = data.getClass(owner) == ClassType.SUMMONER
                                    && "minion".equals(monster.getPersistentDataContainer().get(kindKey, PersistentDataType.STRING));
                        } catch (IllegalArgumentException ignored) { }
                    }
                    if (!necromancerSummon && monster.getTarget() == player) monster.setTarget(null);
                }
            }
        }
    }

    private boolean isInLava(Player player) {
        Material feet = player.getLocation().getBlock().getType();
        Material body = player.getLocation().clone().add(0, 1, 0).getBlock().getType();
        return feet == Material.LAVA || body == Material.LAVA;
    }

    private boolean nearForest(Location location) {
        for (int x = -3; x <= 3; x++) for (int y = -2; y <= 2; y++) for (int z = -3; z <= 3; z++) {
            Material type = location.clone().add(x, y, z).getBlock().getType();
            if (type.name().endsWith("_LEAVES") || type == Material.MOSS_BLOCK
                    || type == Material.GRASS_BLOCK || type == Material.MOSS_CARPET) return true;
        }
        return false;
    }

    private boolean isFriendly(Player owner, Entity entity) {
        if (entity == owner) return true;
        String raw = entity.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        return owner.getUniqueId().toString().equals(raw);
    }

    private boolean canHit(Player owner, Entity entity) {
        return entity instanceof LivingEntity && entity != owner && !isFriendly(owner, entity);
    }

    private boolean ready(Player player, String id) {
        long until = data.getCooldown(player.getUniqueId(), id);
        long left = until - System.currentTimeMillis();
        if (left > 0) {
            player.sendMessage("§cСпособность перезаряжается: §f" + String.format(Locale.US, "%.1f", left / 1000.0) + " сек.");
            return false;
        }
        return true;
    }

    private void startCooldown(Player player, String id) {
        String path = COOLDOWN_PATHS.get(id);
        long seconds = path == null ? 0 : plugin.getConfig().getLong(path, 30);
        data.setCooldown(player.getUniqueId(), id, System.currentTimeMillis() + seconds * 1000L);
    }

    private LivingEntity target(Player player, double range) {
        Vector look = player.getEyeLocation().getDirection().normalize();
        return player.getNearbyEntities(range, range, range).stream()
                .filter(LivingEntity.class::isInstance)
                .map(LivingEntity.class::cast)
                .filter(entity -> entity != player && !entity.isDead() && entity.getLocation().distanceSquared(player.getLocation()) <= range * range)
                .filter(entity -> {
                    Vector to = entity.getEyeLocation().toVector().subtract(player.getEyeLocation().toVector()).normalize();
                    return look.dot(to) > 0.965;
                })
                .min(Comparator.comparingDouble(entity -> entity.getLocation().distanceSquared(player.getLocation())))
                .orElse(null);
    }

    private boolean druidRoots(Player player) {
        LivingEntity target = target(player, 18);
        if (target == null) { player.sendMessage("§cНет цели перед вами (18 блоков)."); return false; }
        target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 50, 8, false, true));
        target.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, 50, 128, false, true));
        target.getWorld().spawnParticle(Particle.BLOCK, target.getLocation().add(0, 0.2, 0),
                35, 0.7, 0.2, 0.7, Bukkit.createBlockData(Material.MANGROVE_ROOTS));
        return true;
    }

    private boolean druidDash(Player player) {
        Vector direction = player.getLocation().getDirection().normalize();
        Location start = player.getLocation();
        double distance = 9;
        var hit = player.getWorld().rayTraceBlocks(player.getEyeLocation(), direction, distance,
                FluidCollisionMode.NEVER, true);
        if (hit != null && hit.getHitPosition() != null) {
            distance = Math.max(1.0, player.getEyeLocation().toVector().distance(hit.getHitPosition()) - 1.2);
        }
        player.setVelocity(direction.multiply(Math.min(1.6, distance / 5.5)).setY(0.32));
        player.getWorld().spawnParticle(Particle.COMPOSTER, start, 25, 0.5, 0.5, 0.5, 0.08);
        return true;
    }

    private boolean druidThorn(Player player) {
        Snowball snowball = player.launchProjectile(Snowball.class);
        snowball.setVelocity(player.getLocation().getDirection().normalize().multiply(1.7));
        snowball.getPersistentDataContainer().set(projectileKey, PersistentDataType.STRING, "thorn");
        return true;
    }

    private boolean druidBark(Player player) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 100, 0, false, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 120, 1, false, true));
        player.getWorld().spawnParticle(Particle.BLOCK, player.getLocation().add(0, 1, 0),
                35, 0.5, 0.8, 0.5, Bukkit.createBlockData(Material.OAK_LOG));
        return true;
    }

    private boolean druidGrove(Player player) {
        for (Player nearby : Bukkit.getOnlinePlayers()) {
            if (nearby.getWorld().equals(player.getWorld()) && nearby.getLocation().distanceSquared(player.getLocation()) <= 64) {
                heal(nearby, 8.0);
                nearby.removePotionEffect(PotionEffectType.POISON);
                nearby.removePotionEffect(PotionEffectType.WITHER);
                nearby.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 100, 0, false, true));
                nearby.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, nearby.getLocation().add(0, 1, 0),
                        15, 0.5, 0.7, 0.5, 0.05);
            }
        }
        return true;
    }

    private void heal(LivingEntity entity, double amount) {
        entity.setHealth(Math.min(entity.getAttribute(Attribute.MAX_HEALTH).getValue(), entity.getHealth() + amount));
    }

    private boolean duelistParry(Player player) {
        parryUntil.put(player.getUniqueId(), System.currentTimeMillis() + 2000L);
        player.sendMessage("§bСтойка парирования активна 2 секунды.");
        return true;
    }

    private boolean duelistLunge(Player player) {
        LivingEntity target = target(player, 7);
        Vector direction = target == null
                ? player.getLocation().getDirection()
                : target.getLocation().toVector().subtract(player.getLocation().toVector());
        player.setVelocity(direction.normalize().multiply(1.35).setY(0.28));
        if (target != null) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (target.isValid() && !target.isDead() && target.getLocation().distanceSquared(player.getLocation()) < 12) {
                    target.damage(4.0, player);
                }
            }, 5L);
        }
        return true;
    }

    private boolean duelistChallenge(Player player) {
        LivingEntity target = target(player, 25);
        if (!(target instanceof Player targetPlayer) || targetPlayer == player) {
            player.sendMessage("§cНаведитесь на игрока в радиусе 25 блоков.");
            return false;
        }
        challengedTargets.put(player.getUniqueId(), targetPlayer.getUniqueId());
        challengeUntil.put(player.getUniqueId(), System.currentTimeMillis() + 8000L);
        targetPlayer.sendMessage("§cВас вызвали на дуэль!");
        player.sendMessage("§aЦель помечена на 8 секунд.");
        return true;
    }

    private boolean duelistWhirlwind(Player player) {
        for (Entity entity : player.getNearbyEntities(4, 3, 4)) {
            if (entity instanceof LivingEntity living && living != player) {
                living.damage(4.0, player);
                Vector knock = living.getLocation().toVector().subtract(player.getLocation().toVector()).normalize().multiply(0.6).setY(0.25);
                living.setVelocity(knock);
            }
        }
        player.getWorld().spawnParticle(Particle.SWEEP_ATTACK, player.getLocation().add(0, 1, 0), 8, 2, 0.4, 2, 0.1);
        return true;
    }

    private boolean duelistLast(Player player) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 120, 0, false, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 120, 1, false, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 120, 0, false, true));
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) player.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 60, 0, false, true));
        }, 120L);
        return true;
    }

    private long minionCount(Player player) {
        return ownedEntities(player.getUniqueId(), "minion").size();
    }

    private List<Entity> ownedEntities(UUID owner, String kind) {
        List<Entity> result = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                String raw = entity.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
                if (owner.toString().equals(raw)
                        && kind.equals(entity.getPersistentDataContainer().get(kindKey, PersistentDataType.STRING))) result.add(entity);
            }
        }
        return result;
    }

    private <T extends Mob> T tagMinion(T mob, Player owner, String name,
                                        double maxHealth, double damage, double speed, long durationSeconds) {
        mob.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, owner.getUniqueId().toString());
        mob.getPersistentDataContainer().set(kindKey, PersistentDataType.STRING, "minion");
        mob.setPersistent(true);
        mob.setAware(true);
        mob.setCanPickupItems(false);
        mob.setCustomName("§5" + name + " §7(" + owner.getName() + ")");
        mob.setCustomNameVisible(true);
        if (mob.getAttribute(Attribute.MAX_HEALTH) != null) {
            mob.getAttribute(Attribute.MAX_HEALTH).setBaseValue(maxHealth);
            mob.setHealth(maxHealth);
        }
        if (mob.getAttribute(Attribute.ATTACK_DAMAGE) != null) mob.getAttribute(Attribute.ATTACK_DAMAGE).setBaseValue(damage);
        if (mob.getAttribute(Attribute.MOVEMENT_SPEED) != null) mob.getAttribute(Attribute.MOVEMENT_SPEED).setBaseValue(speed);
        // Пассивка «Некротический пакт» усиливает каждого призванного бойца.
        mob.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, (int) durationSeconds * 20, 0, true, false));
        mob.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, (int) durationSeconds * 20, 1, true, false));
        mob.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, (int) durationSeconds * 20, 0, true, false));
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (mob.isValid()) mob.remove();
        }, durationSeconds * 20L);
        return mob;
    }

    private boolean hasMinionRoom(Player player, int amount) {
        int limit = plugin.getConfig().getInt("limits.summoner_minions", 8);
        if (minionCount(player) + amount <= limit) return true;
        player.sendMessage("§cЛимит призывов — " + limit + ". Дождитесь исчезновения старых существ.");
        return false;
    }

    private boolean summonHound(Player player) {
        if (!hasMinionRoom(player, 1)) return false;
        Vindicator hound = (Vindicator) player.getWorld().spawnEntity(
                player.getLocation().add(1, 0, 1), EntityType.VINDICATOR);
        tagMinion(hound, player, "Пожиратель душ", 52.0, 13.0, 0.36, 35);
        ItemStack axe = new ItemStack(Material.NETHERITE_AXE);
        axe.addUnsafeEnchantment(Enchantment.SHARPNESS, 4);
        hound.getEquipment().setItemInMainHand(axe);
        hound.getEquipment().setItemInMainHandDropChance(0.0f);
        return true;
    }

    private boolean summonArchers(Player player) {
        if (!hasMinionRoom(player, 2)) return false;
        for (int i = 0; i < 2; i++) {
            Stray archer = (Stray) player.getWorld().spawnEntity(
                    player.getLocation().add(i == 0 ? 2 : -2, 0, 2), EntityType.STRAY);
            tagMinion(archer, player, "Стужник", 34.0, 8.0, 0.30, 35);
            ItemStack bow = new ItemStack(Material.BOW);
            bow.addUnsafeEnchantment(Enchantment.POWER, 3);
            bow.addUnsafeEnchantment(Enchantment.PUNCH, 1);
            archer.getEquipment().setItemInMainHand(bow);
            archer.getEquipment().setItemInMainHandDropChance(0.0f);
        }
        return true;
    }

    private boolean summonKnight(Player player) {
        if (!hasMinionRoom(player, 1)) return false;
        WitherSkeleton knight = (WitherSkeleton) player.getWorld().spawnEntity(
                player.getLocation().add(1, 0, 2), EntityType.WITHER_SKELETON);
        tagMinion(knight, player, "Рыцарь могилы", 72.0, 16.0, 0.34, 40);
        EntityEquipment gear = knight.getEquipment();
        gear.setHelmet(new ItemStack(Material.DIAMOND_HELMET));
        gear.setChestplate(new ItemStack(Material.DIAMOND_CHESTPLATE));
        gear.setLeggings(new ItemStack(Material.DIAMOND_LEGGINGS));
        gear.setBoots(new ItemStack(Material.DIAMOND_BOOTS));
        ItemStack sword = new ItemStack(Material.NETHERITE_SWORD);
        sword.addUnsafeEnchantment(Enchantment.SHARPNESS, 4);
        gear.setItemInMainHand(sword);
        gear.setHelmetDropChance(0.0f);
        gear.setChestplateDropChance(0.0f);
        gear.setLeggingsDropChance(0.0f);
        gear.setBootsDropChance(0.0f);
        gear.setItemInMainHandDropChance(0.0f);
        return true;
    }

    private boolean summonLegion(Player player) {
        if (!hasMinionRoom(player, 3)) return false;
        Ravager beast = (Ravager) player.getWorld().spawnEntity(
                player.getLocation().add(2, 0, 2), EntityType.RAVAGER);
        tagMinion(beast, player, "Тварь бездны", 180.0, 22.0, 0.32, 25);
        beast.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 25 * 20, 1, true, false));
        for (int i = 0; i < 2; i++) {
            WitherSkeleton knight = (WitherSkeleton) player.getWorld().spawnEntity(
                    player.getLocation().add(i == 0 ? -2 : 3, 0, 1), EntityType.WITHER_SKELETON);
            tagMinion(knight, player, "Страж бездны", 48.0, 11.0, 0.34, 25);
            knight.getEquipment().setItemInMainHand(new ItemStack(Material.DIAMOND_SWORD));
            knight.getEquipment().setHelmet(new ItemStack(Material.CHAINMAIL_HELMET));
            knight.getEquipment().setChestplate(new ItemStack(Material.CHAINMAIL_CHESTPLATE));
            knight.getEquipment().setItemInMainHandDropChance(0.0f);
            knight.getEquipment().setHelmetDropChance(0.0f);
            knight.getEquipment().setChestplateDropChance(0.0f);
        }
        return true;
    }

    private void updateMinionAI() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (!(entity instanceof Mob minion)) continue;
                if (!"minion".equals(entity.getPersistentDataContainer().get(kindKey, PersistentDataType.STRING))) continue;
                String raw = entity.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
                if (raw == null) continue;
                UUID ownerId;
                try { ownerId = UUID.fromString(raw); } catch (IllegalArgumentException ignored) { continue; }
                Player owner = Bukkit.getPlayer(ownerId);
                if (owner == null || !owner.isOnline() || owner.getWorld() != world) {
                    minion.setTarget(null);
                    continue;
                }
                Player enemy = world.getPlayers().stream()
                        .filter(candidate -> !candidate.getUniqueId().equals(ownerId))
                        .filter(candidate -> candidate.getGameMode() != GameMode.SPECTATOR)
                        .filter(candidate -> candidate.getLocation().distanceSquared(owner.getLocation()) <= 48.0 * 48.0)
                        .min(Comparator.comparingDouble(candidate ->
                                candidate.getLocation().distanceSquared(minion.getLocation())))
                        .orElse(null);
                LivingEntity current = minion.getTarget();
                if (current != null && isFriendly(owner, current)) minion.setTarget(null);
                if (enemy != null) minion.setTarget(enemy);
                else {
                    minion.setTarget(null);
                    if (minion.getLocation().distanceSquared(owner.getLocation()) > 8.0 * 8.0) {
                        minion.getPathfinder().moveTo(owner.getLocation(), 1.15);
                    }
                }
            }
        }
    }

    private boolean druidRootstorm(Player player) {
        int hits = 0;
        for (Entity entity : player.getNearbyEntities(7, 4, 7)) {
            if (!canHit(player, entity)) continue;
            LivingEntity target = (LivingEntity) entity;
            target.damage(8.0, player);
            target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 70, 8, true, true));
            target.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, 70, 128, true, true));
            target.getWorld().spawnParticle(Particle.BLOCK, target.getLocation().add(0, 0.2, 0),
                    30, 0.7, 0.2, 0.7, Bukkit.createBlockData(Material.MANGROVE_ROOTS));
            hits++;
        }
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_ROOTED_DIRT_BREAK, 1.4f, 0.6f);
        player.sendMessage(hits == 0 ? "§7Корни ударили по земле, но врагов рядом нет." : "§aКорни схватили врагов: " + hits);
        return true;
    }

    private boolean druidWildcharge(Player player) {
        Vector direction = player.getLocation().getDirection().setY(0).normalize();
        if (direction.lengthSquared() == 0) direction = new Vector(0, 0, 1);
        player.setVelocity(direction.clone().multiply(1.9).setY(0.42));
        for (Entity entity : player.getNearbyEntities(12, 3, 12)) {
            if (!canHit(player, entity)) continue;
            Vector to = entity.getLocation().toVector().subtract(player.getLocation().toVector());
            if (to.lengthSquared() < 0.01 || direction.dot(to.normalize()) < 0.72) continue;
            LivingEntity target = (LivingEntity) entity;
            target.damage(7.0, player);
            target.setVelocity(direction.clone().multiply(0.75).setY(0.3));
        }
        player.getWorld().spawnParticle(Particle.CLOUD, player.getLocation(), 35, 0.6, 0.4, 0.6, 0.15);
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 1.0f, 1.3f);
        return true;
    }

    private boolean druidSporeburst(Player player) {
        LivingEntity marked = target(player, 24);
        Location center = marked != null ? marked.getLocation()
                : player.getTargetBlockExact(18) != null
                ? player.getTargetBlockExact(18).getLocation().add(0.5, 1, 0.5)
                : player.getLocation().add(player.getLocation().getDirection().multiply(9));
        int hits = 0;
        for (Entity entity : center.getWorld().getNearbyEntities(center, 5, 4, 5)) {
            if (!canHit(player, entity)) continue;
            LivingEntity target = (LivingEntity) entity;
            target.damage(7.0, player);
            target.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 100, 1, true, true));
            target.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 100, 0, true, true));
            hits++;
        }
        center.getWorld().spawnParticle(Particle.SPORE_BLOSSOM_AIR, center, 100, 3, 1.5, 3, 0.08);
        center.getWorld().spawnParticle(Particle.CRIT, center, 45, 2, 1, 2, 0.2);
        center.getWorld().playSound(center, Sound.BLOCK_HONEY_BLOCK_BREAK, 1.3f, 0.6f);
        player.sendMessage("§aСпоры поразили целей: " + hits);
        return true;
    }

    private boolean druidAvatar(Player player) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 180, 1, true, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 180, 1, true, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 180, 0, true, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 180, 1, true, true));
        heal(player, 6.0);
        player.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING,
                player.getLocation().add(0, 1, 0), 55, 1, 1, 1, 0.15);
        player.getWorld().playSound(player.getLocation(), Sound.ITEM_TOTEM_USE, 1.0f, 0.8f);
        return true;
    }

    private boolean archmageFirebolt(Player player) {
        SmallFireball bolt = player.launchProjectile(SmallFireball.class);
        bolt.setVelocity(player.getEyeLocation().getDirection().normalize().multiply(2.0));
        bolt.setIsIncendiary(false);
        bolt.setYield(0.0f);
        bolt.getPersistentDataContainer().set(projectileKey, PersistentDataType.STRING, "archmage_firebolt");
        return true;
    }

    private boolean archmageBlink(Player player) {
        Location start = player.getLocation().clone();
        Vector direction = player.getLocation().getDirection().normalize();
        var hit = player.getWorld().rayTraceBlocks(player.getEyeLocation(), direction, 14.0,
                FluidCollisionMode.NEVER, true);
        double distance = hit == null ? 13.0
                : Math.max(1.0, player.getEyeLocation().toVector().distance(hit.getHitPosition()) - 1.2);
        Location destination = start.clone().add(direction.multiply(distance));
        destination.setYaw(start.getYaw());
        destination.setPitch(start.getPitch());
        if (!destination.getBlock().isPassable() || !destination.clone().add(0, 1, 0).getBlock().isPassable()) {
            player.sendMessage("§cТам нет места для телепорта.");
            return false;
        }
        player.getWorld().spawnParticle(Particle.FLAME, start.add(0, 1, 0), 35, 0.5, 0.7, 0.5, 0.08);
        player.teleport(destination);
        player.getWorld().spawnParticle(Particle.FLAME, destination.clone().add(0, 1, 0), 40, 0.6, 0.8, 0.6, 0.08);
        player.getWorld().playSound(destination, Sound.ITEM_FIRECHARGE_USE, 1.0f, 1.2f);
        return true;
    }

    private boolean archmageMeteor(Player player) {
        Location aim = player.getTargetBlockExact(32) != null
                ? player.getTargetBlockExact(32).getLocation().add(0.5, 1, 0.5)
                : player.getLocation().add(player.getLocation().getDirection().multiply(20));
        World world = player.getWorld();
        Random random = ThreadLocalRandom.current();

        // 20 отдельных метеоров падают с разных сторон в широкой области вокруг прицела.
        for (int i = 0; i < 20; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double radius = Math.sqrt(random.nextDouble()) * 11.0;
            double x = aim.getX() + Math.cos(angle) * radius;
            double z = aim.getZ() + Math.sin(angle) * radius;
            int groundY = world.getHighestBlockYAt((int) Math.floor(x), (int) Math.floor(z));
            Location spawn = new Location(world, x, Math.max(groundY + 18, aim.getY() + 18), z);
            LargeFireball meteor = (LargeFireball) world.spawnEntity(spawn, EntityType.FIREBALL);
            meteor.setDirection(new Vector((aim.getX() - x) * 0.025, -1.8, (aim.getZ() - z) * 0.025));
            meteor.setVelocity(new Vector((aim.getX() - x) * 0.025, -1.8, (aim.getZ() - z) * 0.025));
            meteor.setShooter(player);
            meteor.setIsIncendiary(false);
            meteor.setYield(0.0f);
            meteor.getPersistentDataContainer().set(projectileKey, PersistentDataType.STRING, "archmage_meteor");
        }
        world.playSound(aim, Sound.ENTITY_WITHER_SPAWN, 2.0f, 0.7f);
        world.spawnParticle(Particle.FLAME, aim.clone().add(0, 12, 0), 180, 10, 5, 10, 0.25);
        return true;
    }

    private boolean archmageLava(Player player) {
        Location center = player.getTargetBlockExact(24) != null
                ? player.getTargetBlockExact(24).getLocation().add(0.5, 1, 0.5)
                : player.getLocation().add(player.getLocation().getDirection().multiply(8));
        World world = center.getWorld();
        UUID owner = player.getUniqueId();
        final int[] ticks = {0};

        plugin.getServer().getScheduler().runTaskTimer(plugin, task -> {
            if (ticks[0]++ >= 10 || !player.isOnline() || player.getWorld() != world) {
                task.cancel();
                return;
            }
            for (int x = -5; x <= 5; x++) for (int z = -5; z <= 5; z++) {
                if (x * x + z * z > 25) continue;
                Location particleAt = center.clone().add(x + 0.5, 0.15, z + 0.5);
                world.spawnParticle(Particle.LAVA, particleAt, 2, 0.15, 0.02, 0.15, 0.01);
                world.spawnParticle(Particle.FLAME, particleAt, 1, 0.1, 0.02, 0.1, 0.01);
            }
            for (Entity entity : world.getNearbyEntities(center, 5.0, 2.5, 5.0)) {
                if (!(entity instanceof LivingEntity living) || entity.getUniqueId().equals(owner)) continue;
                if (entity instanceof Player other && data.getClass(other.getUniqueId()) == ClassType.ARCHMAGE
                        && other.getUniqueId().equals(owner)) continue;
                living.damage(2.0, player);
                living.setFireTicks(Math.max(living.getFireTicks(), 60));
            }
        }, 0L, 20L);
        world.playSound(center, Sound.BLOCK_LAVA_POP, 2.0f, 0.7f);
        return true;
    }

    private boolean archmageGolem(Player player) {
        IronGolem golem = (IronGolem) player.getWorld().spawnEntity(
                player.getLocation().clone().add(2, 0, 0), EntityType.IRON_GOLEM);
        golem.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, player.getUniqueId().toString());
        golem.getPersistentDataContainer().set(kindKey, PersistentDataType.STRING, "archmage_golem");
        golem.setPersistent(true);
        golem.setCustomName("§6Голем магмы §7(" + player.getName() + ")");
        golem.setCustomNameVisible(true);
        golem.setPlayerCreated(false);
        golem.getAttribute(Attribute.MAX_HEALTH).setBaseValue(120.0);
        golem.setHealth(120.0);
        golem.getAttribute(Attribute.ATTACK_DAMAGE).setBaseValue(15.0);
        golem.getAttribute(Attribute.MOVEMENT_SPEED).setBaseValue(0.28);
        golem.getEquipment().setHelmet(new ItemStack(Material.NETHERITE_HELMET));
        golem.getEquipment().setChestplate(new ItemStack(Material.NETHERITE_CHESTPLATE));
        golem.getEquipment().setHelmetDropChance(0.0f);
        golem.getEquipment().setChestplateDropChance(0.0f);

        final int[] ticks = {0};
        plugin.getServer().getScheduler().runTaskTimer(plugin, task -> {
            if (!golem.isValid() || golem.isDead() || !player.isOnline() || ticks[0] >= 25) {
                task.cancel();
                if (golem.isValid()) golem.remove();
                return;
            }
            ticks[0]++;
            Player target = player.getWorld().getPlayers().stream()
                    .filter(candidate -> !candidate.getUniqueId().equals(player.getUniqueId()))
                    .filter(candidate -> candidate.getGameMode() != GameMode.SPECTATOR)
                    .min(Comparator.comparingDouble(candidate -> candidate.getLocation().distanceSquared(golem.getLocation())))
                    .orElse(null);
            if (target != null) {
                golem.setTarget(target);
                if (ticks[0] % 4 == 0 && target.getLocation().distanceSquared(golem.getLocation()) <= 36.0) {
                    target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 20, 2, true, true));
                    target.setFireTicks(Math.max(target.getFireTicks(), 80));
                    target.getWorld().spawnParticle(Particle.FLAME, target.getLocation().add(0, 1, 0),
                            25, 0.4, 0.7, 0.4, 0.08);
                }
            } else {
                golem.setTarget(null);
                if (golem.getLocation().distanceSquared(player.getLocation()) > 16.0) {
                    golem.getPathfinder().moveTo(player.getLocation(), 1.1);
                }
            }
        }, 0L, 20L);
        return true;
    }

    private boolean chameleonCamouflage(Player player) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 80, 0, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 80, 1, false, true));
        return true;
    }

    private boolean chameleonTongue(Player player) {
        Snowball snowball = player.launchProjectile(Snowball.class);
        snowball.setVelocity(player.getLocation().getDirection().normalize().multiply(1.6));
        snowball.getPersistentDataContainer().set(projectileKey, PersistentDataType.STRING, "tongue");
        return true;
    }

    private void handleCopy(Player player) {
        CopiedSkill existing = copies.get(player.getUniqueId());
        if (existing != null && existing.expiresAt() > System.currentTimeMillis()) {
            cast(player, existing.abilityId());
            return;
        }
        if (!ready(player, "chameleon_copy")) return;
        LivingEntity entity = target(player, 20);
        if (!(entity instanceof Player targetPlayer)) {
            player.sendMessage("§cНаведитесь на игрока в радиусе 20 блоков.");
            return;
        }
        ClassType targetClass = data.getClass(targetPlayer.getUniqueId());
        if (targetClass == null) return;
        List<String> skills = switch (targetClass) {
            case DRUID -> List.of("druid_rootstorm", "druid_wildcharge", "druid_sporeburst", "druid_avatar");
            case DUELIST -> List.of("duelist_parry", "duelist_lunge", "duelist_challenge", "duelist_whirlwind", "duelist_last");
            case SUMMONER -> List.of("summoner_hound", "summoner_archers", "summoner_knight", "summoner_legion");
            case SLAVE_OWNER -> List.of("owner_work", "owner_army", "owner_mega");
            case CHAMELEON -> List.of("chameleon_camouflage", "chameleon_tongue");
            case ARCHMAGE -> List.of("archmage_firebolt", "archmage_blink", "archmage_meteor",
                    "archmage_lava", "archmage_golem");
        };
        String selected = skills.get(ThreadLocalRandom.current().nextInt(skills.size()));
        long expiry = System.currentTimeMillis() + plugin.getConfig().getLong("durations.copied_skill", 80) * 1000L;
        copies.put(player.getUniqueId(), new CopiedSkill(selected, expiry));
        startCooldown(player, "chameleon_copy");
        player.sendMessage("§aСкопирована способность: §f" + selected + " §7(80 секунд). Повторный ПКМ по пятому слоту — применить.");
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            CopiedSkill current = copies.get(player.getUniqueId());
            if (current != null && current.expiresAt() <= System.currentTimeMillis()) {
                copies.remove(player.getUniqueId());
                if (player.isOnline()) player.sendMessage("§eСкопированная способность исчезла.");
            }
        }, 80L * 20L);
    }

    public void handleDamage(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Player defender) {
            Long until = parryUntil.get(defender.getUniqueId());
            if (until != null && until >= System.currentTimeMillis()
                    && event.getDamager() instanceof Player attacker) {
                event.setDamage(event.getDamage() * 0.4);
                // Удаляем стойку до контратаки, чтобы два парирующих дуэлянта не зациклили урон.
                parryUntil.remove(defender.getUniqueId());
                attacker.damage(4.0, defender);
                defender.getWorld().spawnParticle(Particle.CRIT, defender.getLocation().add(0, 1, 0), 15, 0.4, 0.6, 0.4, 0.2);
            }
        }
        if (event.getDamager() instanceof Player attacker) {
            UUID targetId = challengedTargets.get(attacker.getUniqueId());
            Long until = challengeUntil.get(attacker.getUniqueId());
            if (targetId != null && until != null && until >= System.currentTimeMillis()
                    && event.getEntity().getUniqueId().equals(targetId)) {
                event.setDamage(event.getDamage() * 1.25);
            }
        }
        if (event.getDamager() instanceof Projectile projectile
                && projectile.getShooter() instanceof Player attacker) {
            String type = projectile.getPersistentDataContainer().get(projectileKey, PersistentDataType.STRING);
            if ("thorn".equals(type) && event.getEntity() instanceof LivingEntity target) {
                target.damage(6.0, attacker);
                target.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 60, 0, false, true));
                projectile.remove();
            } else if ("archmage_firebolt".equals(type) && event.getEntity() instanceof LivingEntity target) {
                event.setDamage(14.0);
                target.setFireTicks(Math.max(target.getFireTicks(), 100));
            } else if ("archmage_meteor".equals(type)) {
                // AoE is applied by ProjectileHitEvent; prevent duplicate direct fireball damage.
                event.setDamage(0.0);
            }
        }
    }

    public void handleProjectileHit(ProjectileHitEvent event) {
        Projectile projectile = event.getEntity();
        String ability = projectile.getPersistentDataContainer().get(projectileKey, PersistentDataType.STRING);

        if ("archmage_meteor".equals(ability) && projectile.getShooter() instanceof Player caster) {
            Location center = event.getHitEntity() != null
                    ? event.getHitEntity().getLocation().clone()
                    : event.getHitBlock() != null
                    ? event.getHitBlock().getLocation().add(0.5, 0.5, 0.5)
                    : projectile.getLocation().clone();
            World world = center.getWorld();
            world.spawnParticle(Particle.EXPLOSION, center, 5, 1.8, 1.2, 1.8, 0.2);
            world.spawnParticle(Particle.FLAME, center, 90, 2.5, 1.5, 2.5, 0.15);
            world.spawnParticle(Particle.LAVA, center, 35, 2.0, 1.0, 2.0, 0.1);
            world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 2.0f, 0.55f);
            for (Entity entity : world.getNearbyEntities(center, 7.0, 6.0, 7.0)) {
                if (!(entity instanceof LivingEntity living) || entity.equals(caster)) continue;
                living.damage(18.0, caster);
                living.setFireTicks(Math.max(living.getFireTicks(), 120));
                Vector push = living.getLocation().toVector().subtract(center.toVector());
                if (push.lengthSquared() > 0.01) living.setVelocity(push.normalize().multiply(0.85).setY(0.45));
            }
            projectile.remove();
            return;
        }

        if (!(projectile instanceof Snowball snowball) || !"tongue".equals(ability)
                || !(snowball.getShooter() instanceof Player player)) return;
        Location destination = event.getHitEntity() != null ? event.getHitEntity().getLocation()
                : event.getHitBlock() != null ? event.getHitBlock().getLocation().add(0.5, 0.5, 0.5) : null;
        if (destination == null || destination.getWorld() != player.getWorld()
                || destination.distanceSquared(player.getLocation()) > Math.pow(plugin.getConfig().getDouble("limits.tongue_range", 33), 2)) return;
        final Location end = destination.clone();
        final int maxTicks = (int) Math.ceil(plugin.getConfig().getDouble("limits.tongue_range", 33)
                / plugin.getConfig().getDouble("limits.tongue_speed_blocks_per_second", 2) * 20.0);
        new org.bukkit.scheduler.BukkitRunnable() {
            private int elapsed;
            @Override public void run() {
                if (!player.isOnline() || player.isDead() || player.getWorld() != end.getWorld()
                        || player.getLocation().distanceSquared(end) < 2.25 || elapsed++ >= maxTicks) {
                    cancel();
                    return;
                }
                Vector direction = end.toVector().subtract(player.getLocation().toVector()).normalize();
                player.setVelocity(direction.multiply(0.1));
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    public void handleChameleonPassive(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player player)
                || data.getClass(player.getUniqueId()) != ClassType.CHAMELEON) return;

        Entity source = event.getDamager();
        if (source instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
            source = shooter;
        }
        if (!(source instanceof Mob)) return;

        String ownerRaw = source.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        if (ownerRaw != null) {
            try {
                UUID ownerId = UUID.fromString(ownerRaw);
                if (data.getClass(ownerId) == ClassType.SUMMONER
                        && "minion".equals(source.getPersistentDataContainer().get(kindKey, PersistentDataType.STRING))) {
                    event.setDamage(event.getDamage() * 0.10);
                    return;
                }
            } catch (IllegalArgumentException ignored) {
                // Неизвестная метка владельца: рассматриваем существо как обычного моба.
            }
        }
        event.setCancelled(true);
    }
}