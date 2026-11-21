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
            Map.entry("druid_roots", "cooldowns.druid.roots"),
            Map.entry("druid_dash", "cooldowns.druid.dash"),
            Map.entry("druid_thorn", "cooldowns.druid.thorn"),
            Map.entry("druid_bark", "cooldowns.druid.bark"),
            Map.entry("druid_grove", "cooldowns.druid.grove"),
            Map.entry("duelist_parry", "cooldowns.duelist.parry"),
            Map.entry("duelist_lunge", "cooldowns.duelist.lunge"),
            Map.entry("duelist_challenge", "cooldowns.duelist.challenge"),
            Map.entry("duelist_whirlwind", "cooldowns.duelist.whirlwind"),
            Map.entry("duelist_last", "cooldowns.duelist.last_round"),
            Map.entry("summoner_wolf", "cooldowns.summoner.wolf"),
            Map.entry("summoner_skeletons", "cooldowns.summoner.skeletons"),
            Map.entry("summoner_chains", "cooldowns.summoner.chains"),
            Map.entry("summoner_recall", "cooldowns.summoner.recall"),
            Map.entry("summoner_guardian", "cooldowns.summoner.guardian"),
            Map.entry("owner_place", "cooldowns.slave_owner.place"),
            Map.entry("owner_work", "cooldowns.slave_owner.work_order"),
            Map.entry("owner_army", "cooldowns.slave_owner.army"),
            Map.entry("owner_mega", "cooldowns.slave_owner.mega_villager"),
            Map.entry("chameleon_camouflage", "cooldowns.chameleon.camouflage"),
            Map.entry("chameleon_tongue", "cooldowns.chameleon.tongue"),
            Map.entry("chameleon_copy", "cooldowns.chameleon.copy")
    );

    public AbilityEngine(SMPClassesPlugin plugin, PlayerDataStore data, VillagerService villagers) {
        this.plugin = plugin;
        this.data = data;
        this.villagers = villagers;
        this.ownerKey = villagers.ownerKey();
        this.kindKey = villagers.kindKey();
        this.projectileKey = new NamespacedKey(plugin, "projectile_ability");
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
            case "druid_roots" -> druidRoots(player);
            case "druid_dash" -> druidDash(player);
            case "druid_thorn" -> druidThorn(player);
            case "druid_bark" -> druidBark(player);
            case "druid_grove" -> druidGrove(player);
            case "duelist_parry" -> duelistParry(player);
            case "duelist_lunge" -> duelistLunge(player);
            case "duelist_challenge" -> duelistChallenge(player);
            case "duelist_whirlwind" -> duelistWhirlwind(player);
            case "duelist_last" -> duelistLast(player);
            case "summoner_wolf" -> summonWolf(player);
            case "summoner_skeletons" -> summonSkeletons(player);
            case "summoner_chains" -> summonerChains(player);
            case "summoner_recall" -> summonerRecall(player);
            case "summoner_guardian" -> summonGuardian(player);
            case "owner_place" -> villagers.toggleFlag(player);
            case "owner_work" -> { villagers.openWorkMenu(player); yield true; }
            case "owner_army" -> { villagers.startArmy(player); yield true; }
            case "owner_mega" -> { villagers.startMegaRide(player); yield true; }
            case "chameleon_camouflage" -> chameleonCamouflage(player);
            case "chameleon_tongue" -> chameleonTongue(player);
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
        };
    }

    private boolean isPassive(String id) {
        return Set.of("owner_passive", "chameleon_passive", "chameleon_life").contains(id);
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

    private <T extends Mob> T tagMinion(T entity, Player owner) {
        entity.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, owner.getUniqueId().toString());
        entity.getPersistentDataContainer().set(kindKey, PersistentDataType.STRING, "minion");
        entity.setPersistent(true);
        return entity;
    }

    private boolean summonWolf(Player player) {
        if (minionCount(player) + 1 > plugin.getConfig().getInt("limits.summoner_minions", 5)) {
            player.sendMessage("§cЛимит прислужников — 5.");
            return false;
        }
        Wolf wolf = tagMinion((Wolf) player.getWorld().spawnEntity(player.getLocation().add(1, 0, 1), EntityType.WOLF), player);
        wolf.setTamed(true);
        wolf.setOwner(player);
        wolf.setCustomName("§bДуховный волк");
        wolf.setCustomNameVisible(true);
        wolf.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 700, 0, false, true));
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (wolf.isValid()) wolf.remove();
        }, 35L * 20L);
        return true;
    }

    private boolean summonSkeletons(Player player) {
        if (minionCount(player) + 2 > plugin.getConfig().getInt("limits.summoner_minions", 5)) {
            player.sendMessage("§cДля двух скелетов нужно два свободных места в лимите прислужников.");
            return false;
        }
        for (int i = 0; i < 2; i++) {
            Skeleton skeleton = tagMinion((Skeleton) player.getWorld().spawnEntity(
                    player.getLocation().add(i == 0 ? 2 : -2, 0, 1), EntityType.SKELETON), player);
            skeleton.setCustomName("§7Костяной дозор");
            skeleton.setCustomNameVisible(true);
            skeleton.setRemoveWhenFarAway(false);
            skeleton.getEquipment().setItemInMainHand(new ItemStack(Material.BOW));
            skeleton.getEquipment().setItemInMainHandDropChance(0.0f);
            skeleton.setTarget(target(player, 24));
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (skeleton.isValid()) skeleton.remove();
            }, 25L * 20L);
        }
        return true;
    }

    private boolean summonerChains(Player player) {
        Location center = player.getTargetBlockExact(20) == null
                ? player.getLocation().add(player.getLocation().getDirection().multiply(8))
                : player.getTargetBlockExact(20).getLocation().add(0.5, 0, 0.5);
        for (Entity entity : center.getWorld().getNearbyEntities(center, 4, 3, 4)) {
            if (entity instanceof LivingEntity living && living != player) {
                living.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 1, false, true));
                living.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 80, 0, false, true));
            }
        }
        center.getWorld().spawnParticle(Particle.SOUL, center, 65, 2, 0.4, 2, 0.04);
        return true;
    }

    private boolean summonerRecall(Player player) {
        List<Entity> minions = ownedEntities(player.getUniqueId(), "minion");
        int healed = 0;
        for (int i = 0; i < minions.size(); i++) {
            Entity minion = minions.get(i);
            minion.teleport(player.getLocation().clone().add((i % 3) - 1, 0, 1 + i / 3));
            if (healed < 6) healed++;
        }
        if (healed > 0) heal(player, Math.min(6.0, healed));
        return true;
    }

    private boolean summonGuardian(Player player) {
        if (minionCount(player) + 1 > plugin.getConfig().getInt("limits.summoner_minions", 5)) {
            player.sendMessage("§cНет свободного места для голема в лимите прислужников.");
            return false;
        }
        IronGolem golem = tagMinion((IronGolem) player.getWorld().spawnEntity(
                player.getLocation().add(2, 0, 0), EntityType.IRON_GOLEM), player);
        golem.setPlayerCreated(false);
        golem.setCustomName("§bДревний страж");
        golem.setCustomNameVisible(true);
        golem.setAware(true);
        golem.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 300, 0, false, true));
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (golem.isValid()) golem.remove();
        }, 15L * 20L);
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
            case DRUID -> List.of("druid_roots", "druid_dash", "druid_thorn", "druid_bark", "druid_grove");
            case DUELIST -> List.of("duelist_parry", "duelist_lunge", "duelist_challenge", "duelist_whirlwind", "duelist_last");
            case SUMMONER -> List.of("summoner_wolf", "summoner_skeletons", "summoner_chains", "summoner_recall", "summoner_guardian");
            case SLAVE_OWNER -> List.of("owner_place", "owner_work", "owner_army", "owner_mega");
            case CHAMELEON -> List.of("chameleon_camouflage", "chameleon_tongue");
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
            }
        }
    }

    public void handleProjectileHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Snowball snowball)) return;
        String ability = snowball.getPersistentDataContainer().get(projectileKey, PersistentDataType.STRING);
        if (!"tongue".equals(ability) || !(snowball.getShooter() instanceof Player player)) return;
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
        if (event.getEntity() instanceof Player player
                && data.getClass(player.getUniqueId()) == ClassType.CHAMELEON
                && event.getDamager() instanceof Monster) {
            event.setCancelled(true);
        }
    }
}