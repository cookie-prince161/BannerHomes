package com.elias.bannerhomes;

import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BannerHomes extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final String GUI_MAIN_TITLE = ChatColor.DARK_BLUE + "Deine Homes";
    private static final String GUI_ADMIN_TITLE = ChatColor.DARK_RED + "Admin: Slot-Dimensionen";
    private static final String GUI_BANNER_PREFIX = ChatColor.DARK_PURPLE + "Banner wählen [Slot ";
    private static final String GUI_SETTINGS_PREFIX = ChatColor.DARK_GRAY + "Einstellungen: ";
    private static final String GUI_CONFIRM_PREFIX = ChatColor.DARK_RED + "Löschen bestätigen: ";
    private static final String GUI_UPGRADE_TITLE = ChatColor.DARK_AQUA + "Home & Cooldown Upgrades";

    private final Map<UUID, PendingHome> pendingHomes = new HashMap<>();
    private final Map<UUID, Integer> pendingDeletes = new HashMap<>();
    private final Map<UUID, Integer> pendingLastTeleports = new HashMap<>();

    private final Map<UUID, Long> combatTimers = new HashMap<>();
    private final Map<UUID, Map<String, Long>> globalTpCooldowns = new HashMap<>();
    private final Map<UUID, Map<Integer, Long>> homeCooldowns = new HashMap<>();

    private File dataFile;
    private FileConfiguration dataConfig;

    private static class PendingHome {
        final int slotIndex;
        final ItemStack bannerItem;
        final Location location;

        PendingHome(int slotIndex, ItemStack bannerItem, Location location) {
            this.slotIndex = slotIndex;
            this.bannerItem = bannerItem.clone();
            this.location = location;
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadDataConfig();

        getServer().getPluginManager().registerEvents(this, this);

        registerCmd("homes");
        registerCmd("homesettings");
        registerCmd("homeadmin");
        registerCmd("homeupgrades");
        registerCmd("home");
        registerCmd("homedelete");
        registerCmd("tp");
        registerCmd("settp");
        registerCmd("deltp");

        getLogger().info("BannerHomes v2.1.0 erfolgreich aktiviert!");
    }

    private void registerCmd(String name) {
        if (getCommand(name) != null) {
            getCommand(name).setExecutor(this);
            getCommand(name).setTabCompleter(this);
        }
    }

    @Override
    public void onDisable() {
        pendingHomes.clear();
        pendingDeletes.clear();
        pendingLastTeleports.clear();
        combatTimers.clear();
        globalTpCooldowns.clear();
        homeCooldowns.clear();
    }

    private void loadDataConfig() {
        dataFile = new File(getDataFolder(), "homes.yml");
        if (!dataFile.exists()) {
            try {
                getDataFolder().mkdirs();
                dataFile.createNewFile();
            } catch (IOException e) {
                getLogger().severe("Konnte homes.yml nicht erstellen!");
            }
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
    }

    private void saveDataConfig() {
        try {
            dataConfig.save(dataFile);
        } catch (IOException e) {
            getLogger().severe("Konnte homes.yml nicht speichern!");
        }
    }

    // ===== UPGRADE & COOLDOWN HELPERS =====

    public int getBaseCooldownSeconds() {
        return getConfig().getInt("base_cooldown_seconds", 300);
    }

    public int getCooldownReductionPerUpgrade() {
        return getConfig().getInt("cooldown_reduction_per_upgrade", 30);
    }

    public int getMaxCooldownUpgrades() {
        return getConfig().getInt("max_cooldown_upgrades", 5);
    }

    public int getCooldownUpgradeLevel(UUID uuid) {
        return dataConfig.getInt("upgrades." + uuid.toString() + ".cooldown_level", 0);
    }

    public void setCooldownUpgradeLevel(UUID uuid, int level) {
        dataConfig.set("upgrades." + uuid.toString() + ".cooldown_level", level);
        saveDataConfig();
    }

    public int getEffectiveCooldown(UUID uuid) {
        int level = getCooldownUpgradeLevel(uuid);
        int base = getBaseCooldownSeconds();
        int reduction = level * getCooldownReductionPerUpgrade();
        return Math.max(0, base - reduction);
    }

    public boolean isInCombat(Player p) {
        int combatDuration = getConfig().getInt("combat_cooldown_seconds", 60);
        if (!combatTimers.containsKey(p.getUniqueId())) return false;
        long lastCombat = combatTimers.get(p.getUniqueId());
        long elapsedSec = (System.currentTimeMillis() - lastCombat) / 1000;
        return elapsedSec < combatDuration;
    }

    public long getRemainingCombatTime(Player p) {
        int combatDuration = getConfig().getInt("combat_cooldown_seconds", 60);
        if (!combatTimers.containsKey(p.getUniqueId())) return 0;
        long lastCombat = combatTimers.get(p.getUniqueId());
        long elapsedSec = (System.currentTimeMillis() - lastCombat) / 1000;
        return Math.max(0, combatDuration - elapsedSec);
    }

    private boolean removeDiamonds(Player p, int amount) {
        int count = 0;
        for (ItemStack is : p.getInventory().getContents()) {
            if (is != null && is.getType() == Material.DIAMOND) {
                count += is.getAmount();
            }
        }
        if (count < amount) return false;

        int left = amount;
        for (int i = 0; i < p.getInventory().getSize(); i++) {
            ItemStack is = p.getInventory().getItem(i);
            if (is != null && is.getType() == Material.DIAMOND) {
                if (is.getAmount() <= left) {
                    left -= is.getAmount();
                    p.getInventory().setItem(i, null);
                } else {
                    is.setAmount(is.getAmount() - left);
                    left = 0;
                }
                if (left <= 0) break;
            }
        }
        return true;
    }

    private int[] getSlotsForAmount(int amount) {
        switch (amount) {
            case 1: return new int[]{13};
            case 2: return new int[]{12, 14};
            case 3: return new int[]{11, 13, 15};
            case 4: return new int[]{10, 12, 14, 16};
            case 5: return new int[]{9, 11, 13, 15, 17};
            default: return new int[]{11, 13, 15};
        }
    }

    private String getDimensionName(World.Environment env) {
        if (env == World.Environment.NETHER) return "NETHER";
        if (env == World.Environment.THE_END) return "END";
        return "OVERWORLD";
    }

    private int extractSlotIndexFromTitle(String rawTitle) {
        String cleanTitle = ChatColor.stripColor(rawTitle);
        Pattern pattern = Pattern.compile("\\[Slot (\\d+)\\]");
        Matcher matcher = pattern.matcher(cleanTitle);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1)) - 1;
            } catch (NumberFormatException ignored) {}
        }
        return -1;
    }

    @SuppressWarnings("unchecked")
    private List<ItemStack> getCustomBanners(Player p) {
        List<?> list = dataConfig.getList("custom-banners." + p.getUniqueId());
        List<ItemStack> banners = new ArrayList<>();
        if (list != null) {
            for (Object obj : list) {
                if (obj instanceof ItemStack) {
                    ItemStack item = ((ItemStack) obj).clone();
                    item.setAmount(1);
                    banners.add(item);
                }
            }
        }
        return banners;
    }

    private void saveCustomBanner(Player p, ItemStack banner) {
        List<ItemStack> list = getCustomBanners(p);
        ItemStack singleBanner = banner.clone();
        singleBanner.setAmount(1);
        list.add(singleBanner);
        dataConfig.set("custom-banners." + p.getUniqueId(), list);
        saveDataConfig();
    }

    private void removeCustomBanner(Player p, int index) {
        List<ItemStack> list = getCustomBanners(p);
        if (index >= 0 && index < list.size()) {
            list.remove(index);
            dataConfig.set("custom-banners." + p.getUniqueId(), list);
            saveDataConfig();
        }
    }

    // ===== GUIS =====

    public void openHomeMenu(Player p) {
        int maxHomes = getConfig().getInt("max-homes", 5);
        Inventory inv = Bukkit.createInventory(null, 27, GUI_MAIN_TITLE);
        int[] slots = getSlotsForAmount(maxHomes);
        String uuidStr = p.getUniqueId().toString();

        for (int i = 0; i < slots.length; i++) {
            int slot = slots[i];
            String path = "homes." + uuidStr + "." + i;

            if (dataConfig.contains(path + ".name")) {
                String name = dataConfig.getString(path + ".name");
                boolean isMain = dataConfig.getBoolean(path + ".isMain", false);

                ItemStack bannerItem = dataConfig.getItemStack(path + ".bannerItem");
                if (bannerItem == null) {
                    String matName = dataConfig.getString(path + ".banner");
                    Material bannerMat = Material.matchMaterial(matName != null ? matName : "WHITE_BANNER");
                    if (bannerMat == null) bannerMat = Material.WHITE_BANNER;
                    bannerItem = new ItemStack(bannerMat);
                } else {
                    bannerItem = bannerItem.clone();
                }
                bannerItem.setAmount(1);

                ItemStack dyeItem = new ItemStack(getDyeForBanner(bannerItem.getType()));
                ItemMeta dyeMeta = dyeItem.getItemMeta();
                if (dyeMeta != null) {
                    dyeMeta.setDisplayName(ChatColor.AQUA + "Farbe / Muster ändern " + ChatColor.GRAY + "(Home " + (i + 1) + ")");
                    List<String> lore = new ArrayList<>();
                    lore.add(ChatColor.GRAY + "Klicke, um Banner & Muster anzupassen.");
                    dyeMeta.setLore(lore);
                    dyeItem.setItemMeta(dyeMeta);
                }
                inv.setItem(slot - 9, dyeItem);

                ItemMeta meta = bannerItem.getItemMeta();
                if (meta != null) {
                    meta.setDisplayName(ChatColor.GREEN + name + (isMain ? ChatColor.GOLD + " ★ (Main)" : ""));
                    List<String> lore = new ArrayList<>();
                    lore.add(ChatColor.GRAY + "Klicke zum Teleportieren");
                    meta.setLore(lore);
                    bannerItem.setItemMeta(meta);
                }
                inv.setItem(slot, bannerItem);

                ItemStack setItem = new ItemStack(Material.REPEATER);
                ItemMeta setMeta = setItem.getItemMeta();
                if (setMeta != null) {
                    setMeta.setDisplayName(ChatColor.YELLOW + "Einstellungen " + ChatColor.GRAY + "(" + name + ")");
                    List<String> lore = new ArrayList<>();
                    lore.add(ChatColor.GRAY + "Klicke für Home-Einstellungen & Main-State");
                    setMeta.setLore(lore);
                    setItem.setItemMeta(setMeta);
                }
                inv.setItem(slot + 9, setItem);

            } else {
                String dim = dataConfig.getString("admin-slots." + i + ".dimension", "OVERWORLD");
                ItemStack placeholder;
                String dimLabel;

                if (dim.equals("NETHER")) {
                    placeholder = new ItemStack(Material.RECOVERY_COMPASS);
                    dimLabel = "Nether";
                } else if (dim.equals("END")) {
                    placeholder = new ItemStack(Material.CHORUS_FRUIT);
                    dimLabel = "End";
                } else {
                    placeholder = new ItemStack(Material.COMPASS);
                    dimLabel = "Overworld";
                }

                ItemMeta meta = placeholder.getItemMeta();
                if (meta != null) {
                    meta.setDisplayName(ChatColor.YELLOW + "Home-Slot " + (i + 1) + " " + ChatColor.GRAY + "(" + dimLabel + ")");
                    List<String> lore = new ArrayList<>();
                    lore.add(ChatColor.GRAY + "Klicke, um ein Home zu erstellen");
                    meta.setLore(lore);
                    placeholder.setItemMeta(meta);
                }
                inv.setItem(slot, placeholder);
            }
        }

        ItemStack upgradeClock = new ItemStack(Material.CLOCK);
        ItemMeta uMeta = upgradeClock.getItemMeta();
        if (uMeta != null) {
            uMeta.setDisplayName(ChatColor.AQUA + "Home & Cooldown Upgrades");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Klicke, um Upgrades zu kaufen!");
            uMeta.setLore(lore);
            upgradeClock.setItemMeta(uMeta);
        }
        inv.setItem(26, upgradeClock);

        p.openInventory(inv);
        p.playSound(p.getLocation(), Sound.BLOCK_CHEST_OPEN, 1.0f, 1.0f);
    }

    public void openUpgradeGUI(Player p) {
        Inventory inv = Bukkit.createInventory(null, 27, GUI_UPGRADE_TITLE);
        int currentCdLevel = getCooldownUpgradeLevel(p.getUniqueId());
        int maxUpgrades = getMaxCooldownUpgrades();

        // 1. OBERSTE REIHE (Slots 2 bis 6 / -9 von der Mitte): Cooldown-Uhren
        for (int i = 0; i < maxUpgrades; i++) {
            int slot = 2 + i;
            boolean unlocked = i < currentCdLevel;
            int cost = (i + 1) * 32; // Halber Stack mehr pro Upgrade (32, 64, 96, 128, 160)

            ItemStack clock = new ItemStack(unlocked ? Material.LIME_DYE : Material.CLOCK);
            ItemMeta meta = clock.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.AQUA + "Cooldown Upgrade Stufe " + (i + 1));
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.GRAY + "Verringert Cooldown um " + getCooldownReductionPerUpgrade() + "s");
                lore.add(ChatColor.GRAY + "Kosten: " + ChatColor.GOLD + cost + " Diamanten");
                lore.add("");
                if (unlocked) {
                    lore.add(ChatColor.GREEN + "✔ Bereits freigeschaltet");
                } else if (i == currentCdLevel) {
                    lore.add(ChatColor.YELLOW + "Klicke zum Kaufen!");
                } else {
                    lore.add(ChatColor.RED + "🔒 Schalte zuerst Stufe " + i + " frei");
                }
                meta.setLore(lore);
                clock.setItemMeta(meta);
            }
            inv.setItem(slot, clock);
        }

        // 2. UNTERSTE REIHE (Slots 20 bis 23 / +18 von den Uhren): Weiße Banner für Home-Freischaltungen
        int unlockedHomes = dataConfig.getInt("upgrades." + p.getUniqueId() + ".unlocked_homes", 1);

        // Overworld Home 2 (2 Stacks = 128 Dias)
        ItemStack ow2 = new ItemStack(unlockedHomes >= 2 ? Material.LIME_DYE : Material.WHITE_BANNER);
        ItemMeta mOw2 = ow2.getItemMeta();
        if (mOw2 != null) {
            mOw2.setDisplayName(ChatColor.GOLD + "Overworld Home #2");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Kosten: " + ChatColor.GOLD + "128 Diamanten (2 Stacks)");
            lore.add(unlockedHomes >= 2 ? ChatColor.GREEN + "✔ Freigeschaltet" : ChatColor.YELLOW + "Klicke zum Kaufen");
            mOw2.setLore(lore);
            ow2.setItemMeta(mOw2);
        }
        inv.setItem(20, ow2);

        // Overworld Home 3 (4 Stacks = 256 Dias)
        ItemStack ow3 = new ItemStack(unlockedHomes >= 3 ? Material.LIME_DYE : Material.WHITE_BANNER);
        ItemMeta mOw3 = ow3.getItemMeta();
        if (mOw3 != null) {
            mOw3.setDisplayName(ChatColor.GOLD + "Overworld Home #3");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Kosten: " + ChatColor.GOLD + "256 Diamanten (4 Stacks)");
            lore.add(unlockedHomes >= 3 ? ChatColor.GREEN + "✔ Freigeschaltet" : (unlockedHomes < 2 ? ChatColor.RED + "🔒 Benötigt Home #2" : ChatColor.YELLOW + "Klicke zum Kaufen"));
            mOw3.setLore(lore);
            ow3.setItemMeta(mOw3);
        }
        inv.setItem(21, ow3);

        // Nether Home (1 Stack = 64 Dias)
        boolean hasNether = dataConfig.getBoolean("upgrades." + p.getUniqueId() + ".nether_home", false);
        ItemStack netherItem = new ItemStack(hasNether ? Material.LIME_DYE : Material.WHITE_BANNER);
        ItemMeta mNether = netherItem.getItemMeta();
        if (mNether != null) {
            mNether.setDisplayName(ChatColor.RED + "Nether Home");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Kosten: " + ChatColor.GOLD + "64 Diamanten (1 Stack)");
            lore.add(hasNether ? ChatColor.GREEN + "✔ Freigeschaltet" : ChatColor.YELLOW + "Klicke zum Kaufen");
            mNether.setLore(lore);
            netherItem.setItemMeta(mNether);
        }
        inv.setItem(22, netherItem);

        // End Home (1 Stack = 64 Dias)
        boolean hasEnd = dataConfig.getBoolean("upgrades." + p.getUniqueId() + ".end_home", false);
        ItemStack endItem = new ItemStack(hasEnd ? Material.LIME_DYE : Material.WHITE_BANNER);
        ItemMeta mEnd = endItem.getItemMeta();
        if (mEnd != null) {
            mEnd.setDisplayName(ChatColor.DARK_PURPLE + "End Home");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Kosten: " + ChatColor.GOLD + "64 Diamanten (1 Stack)");
            lore.add(hasEnd ? ChatColor.GREEN + "✔ Freigeschaltet" : ChatColor.YELLOW + "Klicke zum Kaufen");
            mEnd.setLore(lore);
            endItem.setItemMeta(mEnd);
        }
        inv.setItem(23, endItem);

        inv.setItem(26, getBackButton());
        p.openInventory(inv);
    }

    public void openAdminSettingsMenu(Player p) {
        int maxHomes = getConfig().getInt("max-homes", 5);

        Inventory inv = Bukkit.createInventory(null, 27, GUI_ADMIN_TITLE);
        int[] slots = getSlotsForAmount(maxHomes);

        for (int i = 0; i < slots.length; i++) {
            int slot = slots[i];
            String dim = dataConfig.getString("admin-slots." + i + ".dimension", "OVERWORLD");

            ItemStack item;
            if (dim.equals("NETHER")) item = new ItemStack(Material.RECOVERY_COMPASS);
            else if (dim.equals("END")) item = new ItemStack(Material.CHORUS_FRUIT);
            else item = new ItemStack(Material.COMPASS);

            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.GOLD + "Slot " + (i + 1) + " - Dimension: " + dim);
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.GRAY + "Klicke, um Dimension zu wechseln");
                meta.setLore(lore);
                item.setItemMeta(meta);
            }
            inv.setItem(slot, item);

            ItemStack blockItem;
            if (dim.equals("NETHER")) blockItem = new ItemStack(Material.FIRE_CHARGE);
            else if (dim.equals("END")) blockItem = new ItemStack(Material.ENDER_PEARL);
            else blockItem = new ItemStack(Material.SLIME_BALL);

            ItemMeta bMeta = blockItem.getItemMeta();
            if (bMeta != null) {
                bMeta.setDisplayName(ChatColor.LIGHT_PURPLE + "Dimension: " + dim);
                blockItem.setItemMeta(bMeta);
            }
            inv.setItem(slot + 9, blockItem);
        }

        inv.setItem(0, getBackButton());
        p.openInventory(inv);
        p.playSound(p.getLocation(), Sound.BLOCK_CHEST_OPEN, 1.0f, 1.0f);
    }

    public void openBannerSelectMenu(Player p, int slotIndex) {
        Inventory inv = Bukkit.createInventory(null, 27, GUI_BANNER_PREFIX + (slotIndex + 1) + "]");

        Material[] standardBanners = {
                Material.WHITE_BANNER, Material.ORANGE_BANNER, Material.MAGENTA_BANNER,
                Material.LIGHT_BLUE_BANNER, Material.YELLOW_BANNER, Material.LIME_BANNER,
                Material.PINK_BANNER, Material.GRAY_BANNER, Material.LIGHT_GRAY_BANNER,
                Material.CYAN_BANNER, Material.PURPLE_BANNER, Material.BLUE_BANNER,
                Material.BROWN_BANNER, Material.GREEN_BANNER, Material.RED_BANNER,
                Material.BLACK_BANNER
        };

        int currentSlot = 0;
        for (Material b : standardBanners) {
            ItemStack item = new ItemStack(b);
            item.setAmount(1);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.GREEN + b.name().replace("_BANNER", "").toLowerCase());
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.GRAY + "Standard-Banner");
                meta.setLore(lore);
                item.setItemMeta(meta);
            }
            inv.setItem(currentSlot++, item);
        }

        List<ItemStack> customBanners = getCustomBanners(p);
        for (ItemStack cBanner : customBanners) {
            ItemStack displayBanner = cBanner.clone();
            displayBanner.setAmount(1);
            ItemMeta meta = displayBanner.getItemMeta();
            if (meta != null) {
                List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
                lore.add("");
                lore.add(ChatColor.RED + "Shift + Linksklick zum Löschen");
                meta.setLore(lore);
                displayBanner.setItemMeta(meta);
            }
            inv.setItem(currentSlot++, displayBanner);
        }

        if (currentSlot < 26) {
            ItemStack importSlot = new ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE);
            ItemMeta iMeta = importSlot.getItemMeta();
            if (iMeta != null) {
                iMeta.setDisplayName(ChatColor.AQUA + "Banner hierhin ziehen / klicken");
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.GRAY + "Ziehe ein Banner aus deinem Inventar");
                lore.add(ChatColor.GRAY + "hierhin, um es als Muster zu speichern.");
                iMeta.setLore(lore);
                importSlot.setItemMeta(iMeta);
            }
            inv.setItem(currentSlot, importSlot);
        }

        inv.setItem(26, getBackButton());
        p.openInventory(inv);
    }

    public void openSettingsMenu(Player p, int homeIndex) {
        String uuidStr = p.getUniqueId().toString();
        String path = "homes." + uuidStr + "." + homeIndex;
        String name = dataConfig.getString(path + ".name", "Home " + (homeIndex + 1));
        boolean isMain = dataConfig.getBoolean(path + ".isMain", false);

        Inventory inv = Bukkit.createInventory(null, 27, GUI_SETTINGS_PREFIX + name);

        ItemStack mainItem = new ItemStack(isMain ? Material.SOUL_TORCH : Material.REDSTONE_TORCH);
        ItemMeta mainMeta = mainItem.getItemMeta();
        if (mainMeta != null) {
            mainMeta.setDisplayName(isMain ? ChatColor.GREEN + "✔ Main-Home (Aktiv)" : ChatColor.RED + "✖ Kein Main-Home");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Klicke, um dieses Home als Standard /home zu setzen.");
            mainMeta.setLore(lore);
            mainItem.setItemMeta(mainMeta);
        }
        inv.setItem(11, mainItem);

        ItemStack del = new ItemStack(Material.LAVA_BUCKET);
        ItemMeta delMeta = del.getItemMeta();
        if (delMeta != null) {
            delMeta.setDisplayName(ChatColor.DARK_RED + "Home löschen");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Klicke, um dieses Home unwiderruflich zu löschen.");
            delMeta.setLore(lore);
            del.setItemMeta(delMeta);
        }
        inv.setItem(15, del);

        inv.setItem(26, getBackButton());
        p.openInventory(inv);
    }

    public void openConfirmDeleteMenu(Player p, int homeIndex) {
        String uuidStr = p.getUniqueId().toString();
        String name = dataConfig.getString("homes." + uuidStr + "." + homeIndex + ".name", "Home");

        Inventory inv = Bukkit.createInventory(null, 27, GUI_CONFIRM_PREFIX + name + " [" + homeIndex + "]");

        ItemStack yes = new ItemStack(Material.GREEN_WOOL);
        ItemMeta yesMeta = yes.getItemMeta();
        if (yesMeta != null) {
            yesMeta.setDisplayName(ChatColor.GREEN + "Löschen bestätigen");
            yes.setItemMeta(yesMeta);
        }
        inv.setItem(12, yes);

        ItemStack no = new ItemStack(Material.RED_WOOL);
        ItemMeta noMeta = no.getItemMeta();
        if (noMeta != null) {
            noMeta.setDisplayName(ChatColor.RED + "Abbrechen");
            no.setItemMeta(noMeta);
        }
        inv.setItem(14, no);

        inv.setItem(26, getBackButton());
        p.openInventory(inv);
    }

    private ItemStack getBackButton() {
        ItemStack back = new ItemStack(Material.RED_STAINED_GLASS_PANE);
        ItemMeta meta = back.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.RED + "Zurück");
            back.setItemMeta(meta);
        }
        return back;
    }

    private Material getDyeForBanner(Material bannerMat) {
        switch (bannerMat) {
            case BLACK_BANNER: return Material.BLACK_DYE;
            case BLUE_BANNER: return Material.BLUE_DYE;
            case BROWN_BANNER: return Material.BROWN_DYE;
            case CYAN_BANNER: return Material.CYAN_DYE;
            case GRAY_BANNER: return Material.GRAY_DYE;
            case GREEN_BANNER: return Material.GREEN_DYE;
            case LIGHT_BLUE_BANNER: return Material.LIGHT_BLUE_DYE;
            case LIGHT_GRAY_BANNER: return Material.LIGHT_GRAY_DYE;
            case LIME_BANNER: return Material.LIME_DYE;
            case MAGENTA_BANNER: return Material.MAGENTA_DYE;
            case ORANGE_BANNER: return Material.ORANGE_DYE;
            case PINK_BANNER: return Material.PINK_DYE;
            case PURPLE_BANNER: return Material.PURPLE_DYE;
            case RED_BANNER: return Material.RED_DYE;
            case YELLOW_BANNER: return Material.YELLOW_DYE;
            default: return Material.WHITE_DYE;
        }
    }

    // ===== BEFEHLE =====

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Dieser Befehl ist nur für Spieler.");
            return true;
        }

        Player p = (Player) sender;
        String cmdName = command.getName().toLowerCase();

        // 1. /homeadmin (Admin Slot-Dimensionen)
        if (cmdName.equals("homeadmin")) {
            if (!p.hasPermission("bannerhomes.admin")) {
                p.sendMessage(ChatColor.RED + "Dazu hast du keine Rechte.");
                return true;
            }
            openAdminSettingsMenu(p);
            return true;
        }

        // 2. /homeupgrades (Spieler-Upgrades)
        if (cmdName.equals("homeupgrades")) {
            openUpgradeGUI(p);
            return true;
        }

        // 3. /homesettings (Global Admin Einstellungen)
        if (cmdName.equals("homesettings")) {
            if (!p.hasPermission("bannerhomes.admin")) {
                p.sendMessage(ChatColor.RED + "Dazu hast du keine Rechte.");
                return true;
            }
            p.sendMessage(ChatColor.GOLD + "=== BannerHomes Admin Settings ===");
            p.sendMessage(ChatColor.YELLOW + "/homes setmax <1-5> " + ChatColor.GRAY + "- Maximale Slot-Anzahl");
            p.sendMessage(ChatColor.YELLOW + "/homes cooldown <Sekunden> " + ChatColor.GRAY + "- Basis Cooldown");
            return true;
        }

        // 4. /homes
        if (cmdName.equals("homes")) {
            if (args.length >= 2 && p.hasPermission("bannerhomes.admin")) {
                if (args[0].equalsIgnoreCase("setmax")) {
                    try {
                        int max = Integer.parseInt(args[1]);
                        if (max < 1 || max > 5) {
                            p.sendMessage(ChatColor.RED + "Bitte eine Zahl zwischen 1 und 5 angeben.");
                            return true;
                        }
                        getConfig().set("max-homes", max);
                        saveConfig();
                        p.sendMessage(ChatColor.GREEN + "Maximale Anzahl an Homes auf " + max + " gesetzt.");
                        return true;
                    } catch (NumberFormatException e) {
                        p.sendMessage(ChatColor.RED + "Das ist keine gültige Zahl.");
                        return true;
                    }
                } else if (args[0].equalsIgnoreCase("cooldown")) {
                    try {
                        long cd = Long.parseLong(args[1]);
                        if (cd < 0) {
                            p.sendMessage(ChatColor.RED + "Der Cooldown darf nicht negativ sein.");
                            return true;
                        }
                        getConfig().set("base_cooldown_seconds", cd);
                        saveConfig();
                        p.sendMessage(ChatColor.GREEN + "Basis Home-Cooldown auf " + cd + " Sekunden gesetzt.");
                        return true;
                    } catch (NumberFormatException e) {
                        p.sendMessage(ChatColor.RED + "Ungültiger Wert.");
                        return true;
                    }
                }
            }

            openHomeMenu(p);
            return true;
        }

        // 5. /homedelete
        if (cmdName.equals("homedelete")) {
            if (args.length == 0) {
                p.sendMessage(ChatColor.RED + "Benutzung: /homedelete <Name|Nummer>");
                return true;
            }
            if (args[0].equalsIgnoreCase("confirm") && args.length >= 2) {
                try {
                    int index = Integer.parseInt(args[1]);
                    if (pendingDeletes.containsKey(p.getUniqueId()) && pendingDeletes.get(p.getUniqueId()) == index) {
                        pendingDeletes.remove(p.getUniqueId());
                        deleteHomeByIndex(p, index);
                    } else if (pendingLastTeleports.containsKey(p.getUniqueId()) && pendingLastTeleports.get(p.getUniqueId()) == index) {
                        pendingLastTeleports.remove(p.getUniqueId());
                        executeLastTeleportAndClear(p, index);
                    } else {
                        p.sendMessage(ChatColor.RED + "Ungültige oder abgelaufene Anfrage.");
                    }
                } catch (NumberFormatException e) {
                    p.sendMessage(ChatColor.RED + "Ungültiger Index.");
                }
                return true;
            }
            if (args[0].equalsIgnoreCase("cancel")) {
                pendingDeletes.remove(p.getUniqueId());
                pendingLastTeleports.remove(p.getUniqueId());
                p.sendMessage(ChatColor.YELLOW + "Vorgang abgebrochen.");
                return true;
            }
            requestInteractiveDelete(p, args[0]);
            return true;
        }

        // 6. /home
        if (cmdName.equals("home")) {
            if (args.length == 0) {
                teleportWithValidation(p, getMainHomeIndex(p));
                return true;
            } else {
                teleportToHomeByArg(p, args[0]);
                return true;
            }
        }

        // 7. /settp <name> [cooldownSekunden]
        if (cmdName.equals("settp")) {
            if (!p.hasPermission("bannerhomes.admin")) {
                p.sendMessage(ChatColor.RED + "Dazu hast du keine Rechte.");
                return true;
            }
            if (args.length == 0) {
                p.sendMessage(ChatColor.RED + "Benutzung: /settp <name> [cooldownSekunden]");
                return true;
            }

            String tpName = args[0].toLowerCase();
            long customCd = 60;
            if (args.length >= 2) {
                try {
                    customCd = Long.parseLong(args[1]);
                } catch (NumberFormatException ignored) {}
            }

            saveGlobalLocation("global_tps." + tpName, p.getLocation(), customCd);
            p.sendMessage(ChatColor.GREEN + "Teleportpunkt '" + tpName + "' erfolgreich gesetzt! (Cooldown: " + customCd + "s)");
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
            return true;
        }

        // 8. /deltp <name>
        if (cmdName.equals("deltp")) {
            if (!p.hasPermission("bannerhomes.admin")) {
                p.sendMessage(ChatColor.RED + "Dazu hast du keine Rechte.");
                return true;
            }
            if (args.length == 0) {
                p.sendMessage(ChatColor.RED + "Benutzung: /deltp <name>");
                return true;
            }

            String tpName = args[0].toLowerCase();
            if (!dataConfig.contains("global_tps." + tpName)) {
                p.sendMessage(ChatColor.RED + "Dieser Teleportpunkt existiert nicht.");
                return true;
            }

            dataConfig.set("global_tps." + tpName, null);
            saveDataConfig();
            p.sendMessage(ChatColor.GREEN + "Teleportpunkt '" + tpName + "' erfolgreich gelöscht!");
            p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_DESTROY, 1.0f, 1.0f);
            return true;
        }

        // 9. /tp <name>
        if (cmdName.equals("tp")) {
            if (args.length == 0) {
                p.sendMessage(ChatColor.RED + "Benutzung: /tp <name>");
                return true;
            }

            String tpName = args[0].toLowerCase();
            if (!dataConfig.contains("global_tps." + tpName + ".world")) {
                p.sendMessage(ChatColor.RED + "Dieser Teleportpunkt ('" + tpName + "') existiert nicht!");
                return true;
            }

            teleportGlobalDynamicWithCooldown(p, "global_tps." + tpName, tpName);
            return true;
        }

        return true;
    }

    private void saveGlobalLocation(String path, Location loc, long cooldownSeconds) {
        dataConfig.set(path + ".world", loc.getWorld().getName());
        dataConfig.set(path + ".x", loc.getX());
        dataConfig.set(path + ".y", loc.getY());
        dataConfig.set(path + ".z", loc.getZ());
        dataConfig.set(path + ".yaw", loc.getYaw());
        dataConfig.set(path + ".pitch", loc.getPitch());
        dataConfig.set(path + ".cooldown", cooldownSeconds);
        saveDataConfig();
    }

    private void teleportGlobalDynamicWithCooldown(Player p, String path, String tpName) {
        if (isInCombat(p)) {
            p.sendMessage(ChatColor.RED + "Du bist im Kampf! Du kannst dich erst in " + getRemainingCombatTime(p) + " Sekunden teleportieren.");
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return;
        }

        long now = System.currentTimeMillis();
        long cooldownSec = dataConfig.getLong(path + ".cooldown", 60);
        long cooldownMs = cooldownSec * 1000;

        globalTpCooldowns.putIfAbsent(p.getUniqueId(), new HashMap<>());
        Map<String, Long> userMap = globalTpCooldowns.get(p.getUniqueId());

        if (userMap.containsKey(tpName)) {
            long lastTime = userMap.get(tpName);
            long diff = now - lastTime;
            if (diff < cooldownMs && cooldownSec > 0) {
                long remainingSec = (cooldownMs - diff) / 1000;
                p.sendMessage(ChatColor.RED + "Du musst noch " + remainingSec + "s warten, bevor du dich wieder zu '" + tpName + "' teleportieren kannst.");
                p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                return;
            }
        }

        Location loc = new Location(
                Bukkit.getWorld(dataConfig.getString(path + ".world")),
                dataConfig.getDouble(path + ".x"),
                dataConfig.getDouble(path + ".y"),
                dataConfig.getDouble(path + ".z"),
                (float) dataConfig.getDouble(path + ".yaw"),
                (float) dataConfig.getDouble(path + ".pitch")
        );

        p.teleport(loc);
        userMap.put(tpName, now);
        p.sendMessage(ChatColor.GREEN + "Teleportiert zu '" + tpName + "'!");
        p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
    }

    private void requestInteractiveDelete(Player p, String arg) {
        String uuidStr = p.getUniqueId().toString();
        int maxHomes = getConfig().getInt("max-homes", 5);
        int targetIndex = -1;

        try {
            int num = Integer.parseInt(arg) - 1;
            if (num >= 0 && num < maxHomes) {
                targetIndex = num;
            }
        } catch (NumberFormatException ignore) {
            String search = arg.toLowerCase();
            for (int i = 0; i < maxHomes; i++) {
                String name = dataConfig.getString("homes." + uuidStr + "." + i + ".name");
                if (name != null && name.toLowerCase().equals(search)) {
                    targetIndex = i;
                    break;
                }
            }
        }

        if (targetIndex == -1 || !dataConfig.contains("homes." + uuidStr + "." + targetIndex + ".name")) {
            p.sendMessage(ChatColor.RED + "Kein Home mit diesem Namen oder Index gefunden.");
            return;
        }

        pendingDeletes.put(p.getUniqueId(), targetIndex);
        p.sendMessage(ChatColor.YELLOW + "Möchtest du das Home wirklich löschen? Nutze /homedelete confirm " + (targetIndex + 1));
    }

    private void deleteHomeByIndex(Player p, int index) {
        int slotIndex = index - 1;
        String path = "homes." + p.getUniqueId().toString() + "." + slotIndex;
        dataConfig.set(path, null);
        saveDataConfig();
        p.sendMessage(ChatColor.GREEN + "Home erfolgreich gelöscht.");
        p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_DESTROY, 1.0f, 1.0f);
    }

    private void executeLastTeleportAndClear(Player p, int index) {
        p.sendMessage(ChatColor.GREEN + "Teleport ausgeführt.");
    }

    private int getMainHomeIndex(Player p) {
        String uuidStr = p.getUniqueId().toString();
        int maxHomes = getConfig().getInt("max-homes", 5);
        for (int i = 0; i < maxHomes; i++) {
            if (dataConfig.getBoolean("homes." + uuidStr + "." + i + ".isMain", false)) {
                return i;
            }
        }
        for (int i = 0; i < maxHomes; i++) {
            if (dataConfig.contains("homes." + uuidStr + "." + i + ".name")) {
                return i;
            }
        }
        return 0;
    }

    private void teleportWithValidation(Player p, int index) {
        String uuidStr = p.getUniqueId().toString();
        String path = "homes." + uuidStr + "." + index;

        if (!dataConfig.contains(path + ".name")) {
            p.sendMessage(ChatColor.RED + "Du hast auf diesem Slot kein Home gesetzt.");
            return;
        }

        if (isInCombat(p)) {
            p.sendMessage(ChatColor.RED + "Du bist im Kampf! Du kannst dich erst in " + getRemainingCombatTime(p) + " Sekunden teleportieren.");
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return;
        }

        long now = System.currentTimeMillis();
        long effectiveCd = getEffectiveCooldown(p.getUniqueId());
        long cooldownMs = effectiveCd * 1000;

        homeCooldowns.putIfAbsent(p.getUniqueId(), new HashMap<>());
        Map<Integer, Long> uCdMap = homeCooldowns.get(p.getUniqueId());

        if (uCdMap.containsKey(index)) {
            long lastTime = uCdMap.get(index);
            long diff = now - lastTime;
            if (diff < cooldownMs && effectiveCd > 0) {
                long remainingSec = (cooldownMs - diff) / 1000;
                p.sendMessage(ChatColor.RED + "Du musst noch " + remainingSec + "s warten, bevor du dieses Home wieder nutzen kannst.");
                p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                return;
            }
        }

        Location loc = (Location) dataConfig.get(path + ".location");
        if (loc == null) {
            p.sendMessage(ChatColor.RED + "Fehler: Die Teleportationskoordinaten wurden nicht gefunden.");
            return;
        }

        p.teleport(loc);
        uCdMap.put(index, now);
        p.sendMessage(ChatColor.GREEN + "Teleportiert zu Home '" + dataConfig.getString(path + ".name") + "'!");
        p.playSound(loc, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
    }

    private void teleportToHomeByArg(Player p, String arg) {
        String uuidStr = p.getUniqueId().toString();
        int maxHomes = getConfig().getInt("max-homes", 5);
        int targetIndex = -1;

        try {
            int num = Integer.parseInt(arg) - 1;
            if (num >= 0 && num < maxHomes) {
                targetIndex = num;
            }
        } catch (NumberFormatException ignore) {
            String search = arg.toLowerCase();
            for (int i = 0; i < maxHomes; i++) {
                String name = dataConfig.getString("homes." + uuidStr + "." + i + ".name");
                if (name != null && name.toLowerCase().equals(search)) {
                    targetIndex = i;
                    break;
                }
            }
        }

        if (targetIndex == -1 || !dataConfig.contains("homes." + uuidStr + "." + targetIndex + ".name")) {
            p.sendMessage(ChatColor.RED + "Kein Home mit diesem Namen oder Nummer gefunden.");
            return;
        }

        teleportWithValidation(p, targetIndex);
    }

    // ===== EVENTS =====

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Player && event.getDamager() instanceof Player) {
            Player victim = (Player) event.getEntity();
            Player damager = (Player) event.getDamager();

            long now = System.currentTimeMillis();
            combatTimers.put(victim.getUniqueId(), now);
            combatTimers.put(damager.getUniqueId(), now);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerDeath(PlayerDeathEvent event) {
        combatTimers.remove(event.getEntity().getUniqueId());
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player p = (Player) event.getWhoClicked();
        String title = event.getView().getTitle();

        // 1. Upgrades Menü Klicks
        if (title.equals(GUI_UPGRADE_TITLE)) {
            event.setCancelled(true);
            int slot = event.getRawSlot();

            if (slot == 26) {
                openHomeMenu(p);
                return;
            }

            int currentCdLevel = getCooldownUpgradeLevel(p.getUniqueId());
            int maxUpgrades = getMaxCooldownUpgrades();

            // Cooldown Upgrades (Slots 2-6)
            for (int i = 0; i < maxUpgrades; i++) {
                if (slot == 2 + i) {
                    if (i == currentCdLevel) {
                        int cost = (i + 1) * 32;
                        if (removeDiamonds(p, cost)) {
                            setCooldownUpgradeLevel(p.getUniqueId(), currentCdLevel + 1);
                            p.sendMessage(ChatColor.GREEN + "Cooldown-Upgrade Stufe " + (i + 1) + " gekauft!");
                            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
                            openUpgradeGUI(p);
                        } else {
                            p.sendMessage(ChatColor.RED + "Du benötigst " + cost + " Diamanten in deinem Inventar!");
                        }
                    } else if (i < currentCdLevel) {
                        p.sendMessage(ChatColor.YELLOW + "Du hast diese Stufe bereits freigeschaltet!");
                    } else {
                        p.sendMessage(ChatColor.RED + "Du musst die vorherigen Stufen zuerst kaufen!");
                    }
                    return;
                }
            }

            // Home Slots Upgrades (Slots 20-23)
            int unlockedHomes = dataConfig.getInt("upgrades." + p.getUniqueId() + ".unlocked_homes", 1);

            if (slot == 20) { // Overworld Home 2 (128 Dias)
                if (unlockedHomes >= 2) {
                    p.sendMessage(ChatColor.YELLOW + "Bereits gekauft!");
                    return;
                }
                if (removeDiamonds(p, 128)) {
                    dataConfig.set("upgrades." + p.getUniqueId() + ".unlocked_homes", 2);
                    saveDataConfig();
                    p.sendMessage(ChatColor.GREEN + "Overworld Home #2 erfolgreich freigeschaltet!");
                    p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
                    openUpgradeGUI(p);
                } else {
                    p.sendMessage(ChatColor.RED + "Du benötigst 128 Diamanten (2 Stacks)!");
                }
            } else if (slot == 21) { // Overworld Home 3 (256 Dias)
                if (unlockedHomes < 2) {
                    p.sendMessage(ChatColor.RED + "Du musst zuerst Home #2 kaufen!");
                    return;
                }
                if (unlockedHomes >= 3) {
                    p.sendMessage(ChatColor.YELLOW + "Bereits gekauft!");
                    return;
                }
                if (removeDiamonds(p, 256)) {
                    dataConfig.set("upgrades." + p.getUniqueId() + ".unlocked_homes", 3);
                    saveDataConfig();
                    p.sendMessage(ChatColor.GREEN + "Overworld Home #3 erfolgreich freigeschaltet!");
                    p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
                    openUpgradeGUI(p);
                } else {
                    p.sendMessage(ChatColor.RED + "Du benötigst 256 Diamanten (4 Stacks)!");
                }
            } else if (slot == 22) { // Nether Home (64 Dias)
                boolean hasNether = dataConfig.getBoolean("upgrades." + p.getUniqueId() + ".nether_home", false);
                if (hasNether) {
                    p.sendMessage(ChatColor.YELLOW + "Bereits gekauft!");
                    return;
                }
                if (removeDiamonds(p, 64)) {
                    dataConfig.set("upgrades." + p.getUniqueId() + ".nether_home", true);
                    saveDataConfig();
                    p.sendMessage(ChatColor.GREEN + "Nether Home erfolgreich freigeschaltet!");
                    p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
                    openUpgradeGUI(p);
                } else {
                    p.sendMessage(ChatColor.RED + "Du benötigst 64 Diamanten (1 Stack)!");
                }
            } else if (slot == 23) { // End Home (64 Dias)
                boolean hasEnd = dataConfig.getBoolean("upgrades." + p.getUniqueId() + ".end_home", false);
                if (hasEnd) {
                    p.sendMessage(ChatColor.YELLOW + "Bereits gekauft!");
                    return;
                }
                if (removeDiamonds(p, 64)) {
                    dataConfig.set("upgrades." + p.getUniqueId() + ".end_home", true);
                    saveDataConfig();
                    p.sendMessage(ChatColor.GREEN + "End Home erfolgreich freigeschaltet!");
                    p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
                    openUpgradeGUI(p);
                } else {
                    p.sendMessage(ChatColor.RED + "Du benötigst 64 Diamanten (1 Stack)!");
                }
            }
            return;
        }

        // Haupt-Home Menü Klicks
        if (title.equals(GUI_MAIN_TITLE)) {
            event.setCancelled(true);
            int slot = event.getRawSlot();

            if (slot == 26) {
                openUpgradeGUI(p);
                return;
            }

            int maxHomes = getConfig().getInt("max-homes", 5);
            int[] slots = getSlotsForAmount(maxHomes);

            for (int i = 0; i < slots.length; i++) {
                int homeSlot = slots[i];
                if (slot == homeSlot) {
                    String path = "homes." + p.getUniqueId().toString() + "." + i;
                    if (dataConfig.contains(path + ".name")) {
                        p.closeInventory();
                        teleportWithValidation(p, i);
                    } else {
                        String slotDim = dataConfig.getString("admin-slots." + i + ".dimension", "OVERWORLD");
                        String playerDim = getDimensionName(p.getWorld().getEnvironment());

                        // Slot-Freischaltungs-Prüfungen
                        int unlockedHomes = dataConfig.getInt("upgrades." + p.getUniqueId() + ".unlocked_homes", 1);
                        boolean hasNether = dataConfig.getBoolean("upgrades." + p.getUniqueId() + ".nether_home", false);
                        boolean hasEnd = dataConfig.getBoolean("upgrades." + p.getUniqueId() + ".end_home", false);

                        if (slotDim.equals("NETHER") && !hasNether) {
                            p.sendMessage(ChatColor.RED + "Du musst das Nether-Home Upgrade erst in /homeupgrades freischalten!");
                            return;
                        }
                        if (slotDim.equals("END") && !hasEnd) {
                            p.sendMessage(ChatColor.RED + "Du musst das End-Home Upgrade erst in /homeupgrades freischalten!");
                            return;
                        }
                        if (slotDim.equals("OVERWORLD") && (i + 1) > unlockedHomes) {
                            p.sendMessage(ChatColor.RED + "Du musst Overworld Home #" + (i + 1) + " erst in /homeupgrades freischalten!");
                            return;
                        }

                        if (!slotDim.equals(playerDim)) {
                            p.sendMessage(ChatColor.RED + "Dieser Home-Slot ist für " + slotDim + " konfiguriert! Du bist in " + playerDim + ".");
                            return;
                        }
                        p.closeInventory();
                        openBannerSelectMenu(p, i);
                    }
                    return;
                } else if (slot == homeSlot - 9) {
                    String path = "homes." + p.getUniqueId().toString() + "." + i;
                    if (dataConfig.contains(path + ".name")) {
                        p.closeInventory();
                        openBannerSelectMenu(p, i);
                    }
                    return;
                } else if (slot == homeSlot + 9) {
                    String path = "homes." + p.getUniqueId().toString() + "." + i;
                    if (dataConfig.contains(path + ".name")) {
                        p.closeInventory();
                        openSettingsMenu(p, i);
                    }
                    return;
                }
            }
            return;
        }

        // Banner wählen Menu
        if (title.startsWith(GUI_BANNER_PREFIX)) {
            event.setCancelled(true);
            int slotIndex = extractSlotIndexFromTitle(title);
            if (slotIndex == -1) return;

            if (event.getRawSlot() == 26) {
                openHomeMenu(p);
                return;
            }

            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || clicked.getType() == Material.AIR) {
                ItemStack cursor = event.getCursor();
                if (cursor != null && cursor.getType().name().endsWith("_BANNER")) {
                    saveCustomBanner(p, cursor);
                    openBannerSelectMenu(p, slotIndex);
                }
                return;
            }

            if (clicked.getType().name().endsWith("_BANNER")) {
                if (event.isShiftClick() && clicked.getItemMeta() != null && clicked.getItemMeta().hasLore()) {
                    List<ItemStack> customBanners = getCustomBanners(p);
                    for (int cb = 0; cb < customBanners.size(); cb++) {
                        if (customBanners.get(cb).isSimilar(clicked)) {
                            removeCustomBanner(p, cb);
                            openBannerSelectMenu(p, slotIndex);
                            break;
                        }
                    }
                    return;
                }

                String uuidStr = p.getUniqueId().toString();
                String path = "homes." + uuidStr + "." + slotIndex;
                dataConfig.set(path + ".name", "Home " + (slotIndex + 1));
                dataConfig.set(path + ".banner", clicked.getType().name());
                dataConfig.set(path + ".bannerItem", clicked);
                dataConfig.set(path + ".location", p.getLocation());
                if (!dataConfig.contains(path + ".isMain")) {
                    dataConfig.set(path + ".isMain", false);
                }
                saveDataConfig();

                p.closeInventory();
                p.sendMessage(ChatColor.GREEN + "Home " + (slotIndex + 1) + " erfolgreich gesetzt!");
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
            }
            return;
        }

        // Settings Menu
        if (title.startsWith(GUI_SETTINGS_PREFIX)) {
            event.setCancelled(true);
            if (event.getRawSlot() == 26) {
                openHomeMenu(p);
                return;
            }

            String homeNameClean = title.replace(GUI_SETTINGS_PREFIX, "");
            String uuidStr = p.getUniqueId().toString();
            int maxHomes = getConfig().getInt("max-homes", 5);
            int targetIndex = -1;

            for (int i = 0; i < maxHomes; i++) {
                String name = dataConfig.getString("homes." + uuidStr + "." + i + ".name");
                if (name != null && name.equals(homeNameClean)) {
                    targetIndex = i;
                    break;
                }
            }

            if (targetIndex == -1) return;

            if (event.getRawSlot() == 11) {
                for (int i = 0; i < maxHomes; i++) {
                    dataConfig.set("homes." + uuidStr + "." + i + ".isMain", false);
                }
                dataConfig.set("homes." + uuidStr + "." + targetIndex + ".isMain", true);
                saveDataConfig();
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.5f);
                openSettingsMenu(p, targetIndex);
            } else if (event.getRawSlot() == 15) {
                openConfirmDeleteMenu(p, targetIndex);
            }
            return;
        }

        // Confirm Delete Menu
        if (title.startsWith(GUI_CONFIRM_PREFIX)) {
            event.setCancelled(true);
            if (event.getRawSlot() == 26) {
                openHomeMenu(p);
                return;
            }

            int indexStart = title.lastIndexOf("[");
            int indexEnd = title.lastIndexOf("]");
            if (indexStart != -1 && indexEnd != -1 && indexEnd > indexStart) {
                try {
                    int slotIndex = Integer.parseInt(title.substring(indexStart + 1, indexEnd));
                    if (event.getRawSlot() == 12) {
                        deleteHomeByIndex(p, slotIndex + 1);
                        p.closeInventory();
                    } else if (event.getRawSlot() == 14) {
                        p.closeInventory();
                        openHomeMenu(p);
                    }
                } catch (NumberFormatException ignored) {}
            }
            return;
        }

        // Admin Settings Menu
        if (title.equals(GUI_ADMIN_TITLE)) {
            event.setCancelled(true);
            if (event.getRawSlot() == 0) {
                openHomeMenu(p);
                return;
            }

            int maxHomes = getConfig().getInt("max-homes", 5);
            int[] slots = getSlotsForAmount(maxHomes);

            for (int i = 0; i < slots.length; i++) {
                if (event.getRawSlot() == slots[i] || event.getRawSlot() == slots[i] + 9) {
                    String current = dataConfig.getString("admin-slots." + i + ".dimension", "OVERWORLD");
                    String next = "OVERWORLD";
                    if (current.equals("OVERWORLD")) next = "NETHER";
                    else if (current.equals("NETHER")) next = "END";
                    else if (current.equals("END")) next = "OVERWORLD";

                    dataConfig.set("admin-slots." + i + ".dimension", next);
                    saveDataConfig();
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1.0f, 1.2f);
                    openAdminSettingsMenu(p);
                    break;
                }
            }
        }
    }

    // ===== TAB COMPLETER =====

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> list = new ArrayList<>();
        String cmd = command.getName().toLowerCase();

        if (cmd.equals("home") || cmd.equals("homedelete")) {
            if (args.length == 1 && sender instanceof Player) {
                Player p = (Player) sender;
                String uuidStr = p.getUniqueId().toString();
                int maxHomes = getConfig().getInt("max-homes", 5);
                for (int i = 0; i < maxHomes; i++) {
                    String name = dataConfig.getString("homes." + uuidStr + "." + i + ".name");
                    if (name != null) {
                        list.add(name);
                        list.add(String.valueOf(i + 1));
                    }
                }
            }
        } else if (cmd.equals("homes")) {
            if (args.length == 1 && sender.hasPermission("bannerhomes.admin")) {
                list.add("setmax");
                list.add("cooldown");
            }
        } else if (cmd.equals("settp") || cmd.equals("deltp")) {
            if (args.length == 1 && sender.hasPermission("bannerhomes.admin")) {
                if (dataConfig.contains("global_tps")) {
                    Set<String> keys = dataConfig.getConfigurationSection("global_tps").getKeys(false);
                    list.addAll(keys);
                }
            }
        } else if (cmd.equals("tp")) {
            if (args.length == 1 && dataConfig.contains("global_tps")) {
                Set<String> keys = dataConfig.getConfigurationSection("global_tps").getKeys(false);
                list.addAll(keys);
            }
        }
        return list;
    }
}
