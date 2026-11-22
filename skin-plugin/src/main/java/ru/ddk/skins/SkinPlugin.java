package ru.ddk.skins;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.EquippableComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class SkinPlugin extends JavaPlugin implements Listener, CommandExecutor {
    private record Skin(String id, String name, String category, String rarity, int weight,
                        Material icon, boolean threeD, String armorAsset) { }
    private static final List<Skin> SKINS = List.of(
        new Skin("ruby_edge", "Рубиновый клинок", "SWORD", "§bРедкий", 20, Material.DIAMOND_SWORD, true, null),
        new Skin("malachite_edge", "Малахитовый клинок", "SWORD", "§aОбычный", 34, Material.IRON_SWORD, false, null),
        new Skin("frostfang", "Ледяной клык", "SWORD", "§5Эпический", 10, Material.NETHERITE_SWORD, true, null),
        new Skin("ember_cleaver", "Пепельный колун", "AXE", "§bРедкий", 20, Material.DIAMOND_AXE, true, null),
        new Skin("woodland_hatchet", "Топор хранителя", "AXE", "§aОбычный", 34, Material.IRON_AXE, false, null),
        new Skin("void_drill", "Буря Бездны", "PICKAXE", "§5Эпический", 10, Material.NETHERITE_PICKAXE, true, null),
        new Skin("crystal_pick", "Кристальная кирка", "PICKAXE", "§bРедкий", 20, Material.DIAMOND_PICKAXE, false, null),
        new Skin("rune_shovel", "Рунная лопата", "SHOVEL", "§aОбычный", 34, Material.IRON_SHOVEL, true, null),
        new Skin("harvest_scythe", "Серп жатвы", "HOE", "§bРедкий", 20, Material.DIAMOND_HOE, true, null),
        new Skin("phantom_bow", "Лук фантома", "BOW", "§5Эпический", 10, Material.BOW, true, null),
        new Skin("amethyst_crossbow", "Арбалет аметиста", "CROSSBOW", "§5Эпический", 10, Material.CROSSBOW, true, null),
        new Skin("storm_trident", "Трезубец шторма", "TRIDENT", "§6Легендарный", 4, Material.TRIDENT, true, null),
        new Skin("obsidian_mace", "Булава обсидиана", "MACE", "§6Легендарный", 4, Material.MACE, true, null),
        new Skin("warden_shield", "Щит хранителя", "SHIELD", "§5Эпический", 10, Material.SHIELD, true, null),
        new Skin("royal_armor", "Королевская броня", "ARMOR", "§6Легендарный", 4, Material.NETHERITE_CHESTPLATE, true, "royal"),
        new Skin("frost_armor", "Ледяной комплект", "ARMOR", "§5Эпический", 10, Material.DIAMOND_CHESTPLATE, true, "frost"),
        new Skin("infernal_armor", "Инфернальный комплект", "ARMOR", "§6Легендарный", 4, Material.NETHERITE_CHESTPLATE, true, "infernal"),
        new Skin("ranger_armor", "Броня следопыта", "ARMOR", "§bРедкий", 20, Material.IRON_CHESTPLATE, false, "ranger")
    );
    private final Set<UUID> opening = new HashSet<>();
    private YamlConfiguration db;
    private File dbFile;
    private NamespacedKey skinTag;
    private NamespacedKey keyTag;
    private NamespacedKey adminSkinTag;

    @Override public void onEnable() {
        getDataFolder().mkdirs();
        dbFile = new File(getDataFolder(), "skins.yml");
        db = YamlConfiguration.loadConfiguration(dbFile);
        skinTag = new NamespacedKey(this, "skin_id");
        keyTag = new NamespacedKey(this, "case_key");
        adminSkinTag = new NamespacedKey(this, "admin_skin_id");
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("case")).setExecutor(this);
        Objects.requireNonNull(getCommand("skin")).setExecutor(this);
        Objects.requireNonNull(getCommand("skinadmin")).setExecutor(this);
        Objects.requireNonNull(getCommand("key")).setExecutor(this);
        getLogger().info("SMPClassSkins включён: " + SKINS.size() + " скинов.");
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("key")) {
            if (!sender.hasPermission("smpclassskins.admin")) {
                sender.sendMessage("§cНедостаточно прав.");
                return true;
            }
            if (args.length < 1 || args.length > 2) {
                sender.sendMessage("§eИспользование: /key <ник> [количество]");
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                sender.sendMessage("§cИгрок должен быть онлайн.");
                return true;
            }
            int amount = 1;
            if (args.length == 2) {
                try { amount = Integer.parseInt(args[1]); }
                catch (NumberFormatException ex) { sender.sendMessage("§cКоличество должно быть числом."); return true; }
            }
            if (amount < 1 || amount > 64) {
                sender.sendMessage("§cМожно выдать от 1 до 64 ключей за раз.");
                return true;
            }
            giveKeys(target, amount);
            sender.sendMessage("§aВыдано ключей игроку §f" + target.getName() + "§a: §e" + amount);
            target.sendMessage("§aАдминистратор выдал тебе ключей кейса: §e" + amount);
            return true;
        }

        if (!(sender instanceof Player p)) {
            sender.sendMessage("§cЭта команда доступна только игроку.");
            return true;
        }
        if (name.equals("case")) {
            openCase(p);
        } else if (name.equals("skin")) {
            openCategories(p);
        } else if (name.equals("skinadmin")) {
            if (!p.hasPermission("smpclassskins.admin")) {
                p.sendMessage("§cНедостаточно прав.");
                return true;
            }
            if (args.length > 1) {
                p.sendMessage("§eИспользование: /skinadmin [ник]");
                return true;
            }
            Player target = args.length == 0 ? p : Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                p.sendMessage("§cИгрок должен быть онлайн. Использование: /skinadmin <ник>");
                return true;
            }
            openAdminSkins(p, target);
        }
        return true;
    }

    @EventHandler public void onJoin(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Player p = e.getPlayer(); int pending = db.getInt(path(p) + ".pending_keys", 0);
            if (pending > 0) {
                giveKeys(p, pending);
                db.set(path(p) + ".pending_keys", 0);
                saveDb();
                p.sendMessage("§eОткрытие кейса было прервано выходом. Возвращено ключей: §b" + pending);
            }
            applyAll(p);
        }, 2L);
    }
    @EventHandler public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p) Bukkit.getScheduler().runTaskLater(this, () -> applyAll(p), 1L);
    }
    @EventHandler public void onClose(InventoryCloseEvent e) {
        if (e.getPlayer() instanceof Player p) Bukkit.getScheduler().runTaskLater(this, () -> applyAll(p), 1L);
    }
    @EventHandler public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Menu) {
            e.setCancelled(true);
            return;
        }
        if (e.getWhoClicked() instanceof Player p)
            Bukkit.getScheduler().runTaskLater(this, () -> applyAll(p), 1L);
    }
    @EventHandler public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (top.getHolder() instanceof Menu menu) {
            e.setCancelled(true);
            if (e.getWhoClicked() instanceof Player p && e.getClickedInventory() == top) clickMenu(p, menu, e.getSlot());
        } else if (e.getWhoClicked() instanceof Player p) Bukkit.getScheduler().runTaskLater(this, () -> applyAll(p), 1L);
    }

    private void openCase(Player p) {
        Menu m = new Menu(p.getUniqueId(), "case", null);
        Inventory inv = Bukkit.createInventory(m, 27, "§8✦ §6КЕЙС СО СКИНАМИ"); m.inv = inv; frame(inv);
        inv.setItem(11, item(Material.TRIPWIRE_HOOK, "§eО кейсе",
                "§7Внутри 2D и 3D скины",
                "§7для оружия, инструментов и брони",
                "", "§7Открытие: §a1 ключ",
                "§8Дубликат возвращает ключ"));
        inv.setItem(13, item(Material.ENDER_CHEST, "§6§lОТКРЫТЬ КЕЙС",
                "§7Нажми, чтобы испытать удачу", "",
                "§fТвои ключи: §e" + countKeys(p),
                "§eНажми для открытия"));
        inv.setItem(15, item(Material.BOOK, "§aМоя коллекция",
                "§7Открыто: §f" + owned(p).size() + "§7/§f" + SKINS.size(), "",
                "§eНажми, чтобы выбрать скин"));
        p.openInventory(inv);
    }

    private void openCategories(Player p) {
        Menu m = new Menu(p.getUniqueId(), "categories", null);
        Inventory inv = Bukkit.createInventory(m, 45, "§8✦ §bМОИ СКИНЫ"); m.inv = inv; frame(inv);
        category(inv, 10, Material.NETHERITE_SWORD, "SWORD", "§cМечи", p);
        category(inv, 12, Material.NETHERITE_AXE, "AXE", "§6Топоры", p);
        category(inv, 14, Material.NETHERITE_PICKAXE, "PICKAXE", "§bКирки", p);
        category(inv, 16, Material.NETHERITE_SHOVEL, "SHOVEL", "§eЛопаты", p);
        category(inv, 19, Material.BOW, "BOW", "§dЛуки", p);
        category(inv, 20, Material.CROSSBOW, "CROSSBOW", "§5Арбалеты", p);
        category(inv, 21, Material.TRIDENT, "TRIDENT", "§3Трезубцы", p);
        category(inv, 23, Material.MACE, "MACE", "§5Булавы", p);
        category(inv, 25, Material.SHIELD, "SHIELD", "§7Щиты", p);
        category(inv, 28, Material.DIAMOND_HOE, "HOE", "§aМотыги", p);
        category(inv, 32, Material.NETHERITE_CHESTPLATE, "ARMOR", "§6Броня", p);
        inv.setItem(40, item(Material.BARRIER, "§cСнять все скины", "§7Отключить активные облики", "§eНажми, чтобы снять"));
        inv.setItem(44, item(Material.ARROW, "§7Назад к кейсу", "§eНажми, чтобы вернуться")); p.openInventory(inv);
    }

    private void openAdminSkins(Player admin, Player target) {
        Menu m = new Menu(admin.getUniqueId(), "admin", null, target.getUniqueId(), target.getName());
        Inventory inv = Bukkit.createInventory(m, 54, "§8✦ §4ВЫДАЧА СКИНОВ");
        m.inv = inv;
        frame(inv);
        inv.setItem(4, item(Material.PLAYER_HEAD, "§eИгрок: §f" + target.getName(),
                "§7Нажми на любой скин ниже, чтобы выдать его.",
                "§7Уже открытые скины повторно не выдаются."));
        int[] slots = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34};
        int index = 0;
        List<String> unlocked = owned(target.getUniqueId());
        for (Skin s : SKINS) {
            if (index >= slots.length) break;
            ItemStack icon = new ItemStack(s.icon);
            ItemMeta meta = icon.getItemMeta();
            meta.setDisplayName(s.rarity + "§l" + s.name);
            meta.setLore(List.of(
                    "§7ID: §f" + s.id,
                    "§7Тип: " + (s.threeD ? "§b3D-модель" : "§a2D-текстура"),
                    unlocked.contains(s.id) ? "§aУже есть у игрока" : "§eНажми, чтобы выдать"
            ));
            meta.setItemModel(new NamespacedKey("smpskins", "skin/" + s.id));
            meta.getPersistentDataContainer().set(adminSkinTag, PersistentDataType.STRING, s.id);
            icon.setItemMeta(meta);
            inv.setItem(slots[index++], icon);
        }
        inv.setItem(49, item(Material.BARRIER, "§cЗакрыть панель", "§eНажми, чтобы закрыть"));
        admin.openInventory(inv);
    }

    private void openSkinList(Player p, String category) {
        Menu m = new Menu(p.getUniqueId(), "skins", category);
        Inventory inv = Bukkit.createInventory(m, 54, "§8Скины: " + categoryName(category)); m.inv = inv; frame(inv);
        int[] slots = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43};
        List<String> unlocked = owned(p); int index = 0;
        for (Skin s : SKINS) if (s.category.equals(category) && unlocked.contains(s.id) && index < slots.length) {
            ItemStack icon = new ItemStack(s.icon); ItemMeta meta = icon.getItemMeta();
            meta.setDisplayName(s.rarity + "§l" + s.name);
            meta.setLore(List.of("§7Тип: " + (s.threeD ? "§b3D-модель" : "§a2D-текстура"), "", s.id.equals(active(p, category)) ? "§a✓ Уже надет" : "§eНажми, чтобы надеть"));
            meta.setItemModel(new NamespacedKey("smpskins", "skin/" + s.id)); icon.setItemMeta(meta); inv.setItem(slots[index++], icon);
        }
        if (index == 0) inv.setItem(22, item(Material.BARRIER, "§cПока нет скинов", "§7Открой кейс через /case"));
        inv.setItem(45, item(Material.BARRIER, "§cСнять скин категории", "§7Вернуть стандартный вид", "§eНажми, чтобы снять"));
        inv.setItem(49, item(Material.ARROW, "§7Назад", "§eК списку категорий")); p.openInventory(inv);
    }

    private void clickMenu(Player p, Menu m, int slot) {
        if (!m.owner.equals(p.getUniqueId())) return;
        if (m.type.equals("admin")) {
            if (slot == 49) { p.closeInventory(); return; }
            if (m.target == null) return;
            ItemStack clicked = m.inv.getItem(slot);
            if (clicked == null || !clicked.hasItemMeta()) return;
            String skinId = clicked.getItemMeta().getPersistentDataContainer().get(adminSkinTag, PersistentDataType.STRING);
            Skin selected = skinId == null ? null : find(skinId);
            if (selected == null) return;
            List<String> unlocked = owned(m.target);
            if (unlocked.contains(selected.id)) {
                p.sendMessage("§eУ игрока §f" + m.targetName + " §eуже есть скин §f" + selected.name + "§e.");
                return;
            }
            unlocked.add(selected.id);
            db.set(path(m.target) + ".owned", unlocked);
            saveDb();
            Player target = Bukkit.getPlayer(m.target);
            if (target != null) target.sendMessage("§aАдминистратор выдал тебе скин: " + selected.rarity + selected.name);
            p.sendMessage("§aВыдан скин §f" + selected.name + " §aигроку §f" + m.targetName + "§a.");
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.4f);
            openAdminSkins(p, target != null ? target : p);
            return;
        }
        if (m.type.equals("case")) {
            if (slot == 13) openCaseRoll(p, m.inv);
            else if (slot == 15) openCategories(p);
            return;
        }
        if (m.type.equals("categories")) {
            if (slot == 44) { openCase(p); return; }
            if (slot == 40) {
                for (String c : List.of("SWORD","AXE","PICKAXE","SHOVEL","HOE","BOW","CROSSBOW","TRIDENT","MACE","SHIELD","ARMOR")) setActive(p,c,null);
                applyAll(p); p.sendMessage("§aВсе скины сняты."); openCategories(p); return;
            }
            String c = switch (slot) {
                case 10 -> "SWORD"; case 12 -> "AXE"; case 14 -> "PICKAXE"; case 16 -> "SHOVEL";
                case 19 -> "BOW"; case 20 -> "CROSSBOW"; case 21 -> "TRIDENT"; case 23 -> "MACE";
                case 25 -> "SHIELD"; case 28 -> "HOE"; case 32 -> "ARMOR"; default -> null;
            };
            if (c != null) openSkinList(p,c); return;
        }
        if (m.type.equals("skins")) {
            if (slot == 49) { openCategories(p); return; }
            if (slot == 45) { setActive(p,m.category,null); applyAll(p); p.sendMessage("§aСкин снят."); openSkinList(p,m.category); return; }
            ItemStack clicked = m.inv.getItem(slot); if (clicked == null || !clicked.hasItemMeta()) return;
            NamespacedKey model = clicked.getItemMeta().getItemModel();
            if (model == null || !model.getNamespace().equals("smpskins") || !model.getKey().startsWith("skin/")) return;
            String id = model.getKey().substring(5); Skin s = find(id);
            if (s == null || !owned(p).contains(id)) return;
            setActive(p,s.category,s.id); applyAll(p); p.playSound(p.getLocation(),Sound.ITEM_ARMOR_EQUIP_DIAMOND,0.8f,1.2f);
            p.sendMessage("§aНадет скин: " + s.rarity + s.name); openSkinList(p,m.category);
        }
    }

    private void openCaseRoll(Player p, Inventory inv) {
        UUID id = p.getUniqueId(); if (!opening.add(id)) { p.sendMessage("§eКейс уже открывается."); return; }
        if (!takeKey(p)) {
            opening.remove(id);
            p.sendMessage("§cУ тебя нет ключа от кейса. Попроси администратора выдать его командой /key.");
            p.playSound(p.getLocation(), Sound.BLOCK_CHEST_LOCKED, 0.7f, 1f);
            return;
        }
        p.sendMessage("§eКейс открывается...");
        new BukkitRunnable() {
            int step;
            public void run() {
                if (!p.isOnline()) {
                    db.set(path(p) + ".pending_keys", db.getInt(path(p) + ".pending_keys", 0) + 1);
                    saveDb();
                    opening.remove(id);
                    cancel();
                    return;
                }
                boolean visible = p.getOpenInventory().getTopInventory() == inv;
                if (step++ < 10) { if (visible) { Skin s=SKINS.get(ThreadLocalRandom.current().nextInt(SKINS.size())); inv.setItem(13,item(s.icon,"§e✦ " + s.name,"§7Определяем награду...")); p.playSound(p.getLocation(),Sound.UI_BUTTON_CLICK,0.5f,1.2f+step*0.04f); } return; }
                Skin prize = roll(); List<String> unlocked = owned(p);
                if (unlocked.contains(prize.id)) {
                    giveKeys(p, 1);
                    if (visible) inv.setItem(13, item(Material.TRIPWIRE_HOOK, "§bДубликат: " + prize.name, "§aКлюч возвращён."));
                    p.sendMessage("§eДубликат " + prize.name + "§e — ключ возвращён.");
                }
                else { unlocked.add(prize.id); db.set(path(p)+".owned",unlocked); saveDb(); if (visible) inv.setItem(13,item(prize.icon,prize.rarity+"§l"+prize.name,"§aНовый скин в коллекции!","§7Тип: " + (prize.threeD?"§b3D":"§a2D"),"","§eИспользуй /skin")); p.sendMessage("§aНовый скин: " + prize.rarity + prize.name + "§a. Используй /skin."); }
                p.playSound(p.getLocation(),Sound.UI_TOAST_CHALLENGE_COMPLETE,0.9f,1f); opening.remove(id); cancel();
            }
        }.runTaskTimer(this,0L,2L);
    }

    private Skin roll() { int n=SKINS.stream().mapToInt(Skin::weight).sum(), r=ThreadLocalRandom.current().nextInt(n); for(Skin s:SKINS){r-=s.weight;if(r<0)return s;} return SKINS.get(0); }
    private void applyAll(Player p) {
        if (!p.isOnline()) return; PlayerInventory inv=p.getInventory();
        for(int i=0;i<36;i++){ItemStack it=inv.getItem(i);apply(p,it);inv.setItem(i,it);}
        ItemStack[] armor=inv.getArmorContents(); for(ItemStack it:armor)apply(p,it); inv.setArmorContents(armor);
        ItemStack off=inv.getItemInOffHand();apply(p,off);inv.setItemInOffHand(off);
    }
    private void apply(Player p, ItemStack it) {
        if(it==null||it.getType().isAir())return; String cat=category(it.getType()); if(cat==null)return;
        ItemMeta meta=it.getItemMeta(); if(meta==null)return; String selected=active(p,cat); Skin s=selected==null?null:find(selected);
        if(s!=null&&s.category.equals(cat)){
            meta.setItemModel(new NamespacedKey("smpskins","skin/"+s.id)); meta.getPersistentDataContainer().set(skinTag,PersistentDataType.STRING,s.id);
            if(cat.equals("ARMOR")&&s.armorAsset!=null){EquippableComponent eq=meta.getEquippable();eq.setModel(new NamespacedKey("smpskins",s.armorAsset));meta.setEquippable(eq);}
            it.setItemMeta(meta);
        } else if(meta.getPersistentDataContainer().has(skinTag,PersistentDataType.STRING)){
            meta.setItemModel(null);meta.getPersistentDataContainer().remove(skinTag);
            if(cat.equals("ARMOR")&&meta.hasEquippable()){EquippableComponent eq=meta.getEquippable();eq.setModel(null);meta.setEquippable(eq);} it.setItemMeta(meta);
        }
    }
    private String category(Material m) {
        String n=m.name(); if(n.endsWith("_SWORD"))return "SWORD";if(n.endsWith("_AXE"))return "AXE";if(n.endsWith("_PICKAXE"))return "PICKAXE";if(n.endsWith("_SHOVEL"))return "SHOVEL";if(n.endsWith("_HOE"))return "HOE";
        if(m==Material.BOW)return "BOW";if(m==Material.CROSSBOW)return "CROSSBOW";if(m==Material.TRIDENT)return "TRIDENT";if(m==Material.MACE)return "MACE";if(m==Material.SHIELD)return "SHIELD";
        if(n.endsWith("_HELMET")||n.endsWith("_CHESTPLATE")||n.endsWith("_LEGGINGS")||n.endsWith("_BOOTS"))return "ARMOR";return null;
    }
    private String path(Player p){return path(p.getUniqueId());}
    private String path(UUID id){return "players."+id;}
    private List<String> owned(Player p){return owned(p.getUniqueId());}
    private List<String> owned(UUID id){return new ArrayList<>(db.getStringList(path(id)+".owned"));}
    private String active(Player p,String cat){return db.getString(path(p)+".active."+cat);}
    private void setActive(Player p,String cat,String id){db.set(path(p)+".active."+cat,id);saveDb();}
    private void saveDb(){try{db.save(dbFile);}catch(IOException ex){getLogger().severe("Cannot save skins.yml: "+ex.getMessage());}}
    private Skin find(String id){for(Skin s:SKINS)if(s.id.equals(id))return s;return null;}
    private int countKeys(Player p) {
        int count = 0;
        for (ItemStack stack : p.getInventory().getStorageContents()) {
            if (isCaseKey(stack)) count += stack.getAmount();
        }
        return count;
    }
    private boolean takeKey(Player p) {
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = p.getInventory().getItem(slot);
            if (!isCaseKey(stack)) continue;
            if (stack.getAmount() <= 1) p.getInventory().setItem(slot, null);
            else stack.setAmount(stack.getAmount() - 1);
            return true;
        }
        return false;
    }
    private boolean isCaseKey(ItemStack stack) {
        return stack != null && stack.hasItemMeta()
                && "case_key".equals(stack.getItemMeta().getPersistentDataContainer().get(keyTag, PersistentDataType.STRING));
    }
    private ItemStack createKey(int amount) {
        ItemStack key = new ItemStack(Material.TRIPWIRE_HOOK, amount);
        ItemMeta meta = key.getItemMeta();
        meta.setDisplayName("§6§lКлюч от кейса");
        meta.setLore(List.of("§7Открывает кейс со скинами", "§eПКМ не требуется — используй /case"));
        meta.getPersistentDataContainer().set(keyTag, PersistentDataType.STRING, "case_key");
        key.setItemMeta(meta);
        return key;
    }
    private void giveKeys(Player p, int amount) {
        Map<Integer, ItemStack> rest = p.getInventory().addItem(createKey(amount));
        for (ItemStack stack : rest.values()) p.getWorld().dropItemNaturally(p.getLocation(), stack);
    }
    private ItemStack item(Material type,String name,String... lore){ItemStack it=new ItemStack(type);ItemMeta meta=it.getItemMeta();meta.setDisplayName(name);meta.setLore(Arrays.asList(lore));it.setItemMeta(meta);return it;}
    private void category(Inventory inv,int slot,Material icon,String cat,String title,Player p){long count=SKINS.stream().filter(s->s.category.equals(cat)&&owned(p).contains(s.id)).count();inv.setItem(slot,item(icon,title,"§7Открыто: §f"+count,"","§eНажми, чтобы открыть"));}
    private void frame(Inventory inv){ItemStack pane=item(Material.BLACK_STAINED_GLASS_PANE,"§0 ");for(int i=0;i<inv.getSize();i++){int row=i/9,col=i%9;if(row==0||row==inv.getSize()/9-1||col==0||col==8)inv.setItem(i,pane);}}
    private String categoryName(String c){return switch(c){case "SWORD"->"мечи";case "AXE"->"топоры";case "PICKAXE"->"кирки";case "SHOVEL"->"лопаты";case "HOE"->"мотыги";case "BOW"->"луки";case "CROSSBOW"->"арбалеты";case "TRIDENT"->"трезубцы";case "MACE"->"булавы";case "SHIELD"->"щиты";case "ARMOR"->"броня";default->c.toLowerCase(Locale.ROOT);};}

    private static final class Menu implements InventoryHolder {
        private final UUID owner;
        private final String type, category;
        private final UUID target;
        private final String targetName;
        private Inventory inv;
        private Menu(UUID owner, String type, String category) {
            this(owner, type, category, null, null);
        }
        private Menu(UUID owner, String type, String category, UUID target, String targetName) {
            this.owner = owner;
            this.type = type;
            this.category = category;
            this.target = target;
            this.targetName = targetName;
        }
        @Override public Inventory getInventory(){return inv;}
    }
}