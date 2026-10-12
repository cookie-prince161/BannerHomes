package com.elias.bannerhomes;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
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
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
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
    private static final String GUI_ADMIN_TITLE = ChatColor.DARK_RED + "Admin-Panel: Homes Settings";
    private static final String GUI_BANNER_PREFIX = ChatColor.DARK_PURPLE + "Banner wählen [Slot ";
    private static final String GUI_SETTINGS_PREFIX = ChatColor.DARK_GRAY + "Einstellungen: ";
    private static final String GUI_CONFIRM_PREFIX = ChatColor.DARK_RED + "Löschen bestätigen: ";
    private static final String GUI_UPGRADE_TITLE = ChatColor.DARK_AQUA + "Home & Cooldown Upgrades";

    private final Map<UUID, Integer> pendingDeletes = new HashMap<>();
    private final Map<UUID, Integer> pendingLastTeleports = new HashMap<>();
    private final Map<UUID, Integer> renamingHomeSlots = new HashMap<>();

    private final Map<UUID, Long> combatTimers = new HashMap<>();
    private final Map<UUID, Map<String, Long>> globalTpCooldowns = new HashMap<>();
    private final Map<UUID, Map<Integer, Long>> homeCooldowns = new HashMap<>();

    private File dataFile;
    private FileConfiguration dataConfig;

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
        registerCmd("tpdelete");

        getLogger().info("BannerHomes v2.6.0 erfolgreich aktiviert!");
    }

    private void registerCmd(String name) {
        if (getCommand(name) != null) {
            getCommand(name).setExecutor(this);
            getCommand(name).setTabCompleter(this);
        }
    }

    @Override
    public void onDisable() {
        pendingDeletes.clear();
        pendingLastTeleports.clear();
        renamingHomeSlots.clear();
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
        int adminMax = getConfig().getInt("max-homes", 5);
        int unlockedHomes = dataConfig.getInt("upgrades." + p.getUniqueId() + ".unlocked_homes", 1);

        Inventory inv = Bukkit.createInventory(null, 27, GUI_MAIN_TITLE);
        int[] slots = getSlotsForAmount(adminMax);
        String uuidStr = p.getUniqueId().toString();

        for (int i = 0; i < slots.length; i++) {
            int slot = slots[i];
            String path = "homes." + uuidStr + "." + i;
            String dim = dataConfig.getString("admin-slots." + i + ".dimension", "OVERWORLD");

            boolean isSlotUnlocked = (i + 1) <= unlockedHomes;

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
                    dyeMeta.setDisplayName(ChatColor.AQUA + "Farbe / Muster ändern " + ChatColor.GRAY + "(" + name + ")");
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
                    lore.add(ChatColor.GRAY + "Klicke für Home-Einstellungen & Umbenennen");
                    setMeta.setLore(lore);
                    setItem.setItemMeta(setMeta);
                }
                inv.setItem(slot + 9, setItem);

            } else {
                ItemStack placeholder;
                String dimLabel;

                if (!isSlotUnlocked) {
                    placeholder = new ItemStack(Material.STRUCTURE_VOID);
                    ItemMeta meta = placeholder.getItemMeta();
                    if (meta != null) {
                        meta.setDisplayName(ChatColor.RED + "🔒 Slot " + (i + 1) + " (" + dim + ")");
                        List<String> lore = new ArrayList<>();
                        lore.add(ChatColor.GRAY + "Schalte diesen Slot in /homeupgrades frei!");
                        meta.setLore(lore);
                        placeholder.setItemMeta(meta);
                    }
                    inv.setItem(slot, placeholder);
                    continue;
                }

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

        for (int i = 0; i < maxUpgrades; i++) {
            int slot = 2 + i;
            boolean unlocked = i < currentCdLevel;
            int cost = (i + 1) * 32;

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

        int unlockedHomes = dataConfig.getInt("upgrades." + p.getUniqueId() + ".unlocked_homes", 1);

        ItemStack slot2 = new ItemStack(unlockedHomes >= 2 ? Material.LIME_DYE : Material.WHITE_BANNER);
        ItemMeta mSlot2 = slot2.getItemMeta();
        if (mSlot2 != null) {
            mSlot2.setDisplayName(ChatColor.GOLD + "Home-Slot #2 Freischalten");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Kosten: " + ChatColor.GOLD + "128 Diamanten (2 Stacks)");
            lore.add(unlockedHomes >= 2 ? ChatColor.GREEN + "✔ Freigeschaltet" : ChatColor.YELLOW + "Klicke zum Kaufen");
            mSlot2.setLore(lore);
            slot2.setItemMeta(mSlot2);
        }
        inv.setItem(20, slot2);

        ItemStack slot3 = new ItemStack(unlockedHomes >= 3 ? Material.LIME_DYE : Material.WHITE_BANNER);
        ItemMeta mSlot3 = slot3.getItemMeta();
        if (mSlot3 != null) {
            mSlot3.setDisplayName(ChatColor.GOLD + "Home-Slot #3 Freischalten");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Kosten: " + ChatColor.GOLD + "256 Diamanten (4 Stacks)");
            lore.add(unlockedHomes >= 3 ? ChatColor.GREEN + "✔ Freigeschaltet" : (unlockedHomes < 2 ? ChatColor.RED + "🔒 Benötigt Slot #2" : ChatColor.YELLOW + "Klicke zum Kaufen"));
            mSlot3.setLore(lore);
            slot3.setItemMeta(mSlot3);
        }
        inv.setItem(21, slot3);

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
        }

        ItemStack minusMax = new ItemStack(Material.REDSTONE_BLOCK);
        ItemMeta mMinus = minusMax.getItemMeta();
        if (mMinus != null) {
            mMinus.setDisplayName(ChatColor.RED + "-1 Max-Homes Slot");
            minusMax.setItemMeta(mMinus);
        }
        inv.setItem(18, minusMax);

        ItemStack maxDisplay = new ItemStack(Material.PAPER);
        ItemMeta mDisp = maxDisplay.getItemMeta();
        if (mDisp != null) {
            mDisp.setDisplayName(ChatColor.YELLOW + "Max-Homes: " + maxHomes);
            maxDisplay.setItemMeta(mDisp);
        }
        inv.setItem(19, maxDisplay);

        ItemStack plusMax = new ItemStack(Material.EMERALD_BLOCK);
        ItemMeta mPlus = plusMax.getItemMeta();
        if (mPlus != null) {
            mPlus.setDisplayName(ChatColor.GREEN + "+1 Max-Homes Slot");
            plusMax.setItemMeta(mPlus);
        }
        inv.setItem(20, plusMax);

        inv.setItem(26, getBackButton());
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

        ItemStack nameTag = new ItemStack(Material.NAME_TAG);
        ItemMeta nameMeta = nameTag.getItemMeta();
        if (nameMeta != null) {
            nameMeta.setDisplayName(ChatColor.YELLOW + "Home umbenennen");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Klicke, um einen neuen Namen im Chat einzugeben.");
            nameMeta.setLore(lore);
            nameTag.setItemMeta(nameMeta);
        }
        inv.setItem(13, nameTag);

        ItemStack del = new ItemStack(Material.BARRIER);
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

        if (cmdName.equals("homeadmin") || cmdName.equals("homesettings")) {
            if (!p.hasPermission("bannerhomes.admin")) {
                p.sendMessage(ChatColor.RED + "Dazu hast du keine Rechte.");
                return true;
            }
            openAdminSettingsMenu(p);
            return true;
        }

        if (cmdName.equals("homeupgrades")) {
            openUpgradeGUI(p);
            return true;
        }

        if (cmdName.equals("homes")) {
            openHomeMenu(p);
            return true;
        }

        if (cmdName.equals("homedelete")) {
            if (args.length == 0) {
                p.sendMessage(ChatColor.RED + "Benutzung: /homedelete <Name|Nummer>");
                return true;
            }
            if (args[0].equalsIgnoreCase("confirm") && args.length >= 2) {
                try {
                    int slotIndex = Integer.parseInt(args[1]) - 1;
                    if (pendingLastTeleports.containsKey(p.getUniqueId()) && pendingLastTeleports.get(p.getUniqueId()) == slotIndex) {
                        pendingLastTeleports.remove(p.getUniqueId());
                        executeLastTeleportAndClear(p, slotIndex);
                    } else if (pendingDeletes.containsKey(p.getUniqueId()) && pendingDeletes.get(p.getUniqueId()) == slotIndex) {
                        pendingDeletes.remove(p.getUniqueId());
                        deleteHomeByIndex(p, slotIndex + 1);
                    } else {
                        p.sendMessage(ChatColor.RED + "Keine ausstehende Bestätigung gefunden.");
                    }
                } catch (NumberFormatException e) {
                    p.sendMessage(ChatColor.RED + "Ungültiger Slot-Index.");
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

        if (cmdName.equals("home")) {
            if (args.length == 0) {
                teleportWithValidation(p, getMainHomeIndex(p));
                return true;
            } else {
                teleportToHomeByArg(p, args[0]);
                return true;
            }
        }

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

        if (cmdName.equals("deltp") || cmdName.equals("tpdelete")) {
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
        
        // Anklickbare Chat-Schaltflächen (wie früher)
        p.sendMessage(ChatColor.YELLOW + "========================================");
        p.sendMessage(ChatColor.RED + "Möchtest du dieses Home wirklich löschen?");

        TextComponent promptMsg = new TextComponent("Bestätigung: ");
        promptMsg.setColor(ChatColor.YELLOW);

        TextComponent yesBtn = new TextComponent("§a§l[JA]");
        yesBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/homedelete confirm " + (targetIndex + 1)));

        TextComponent space = new TextComponent("   ");

        TextComponent noBtn = new TextComponent("§c§l[ABBRECHEN]");
        noBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/homedelete cancel"));

        promptMsg.addExtra(yesBtn);
        promptMsg.addExtra(space);
        promptMsg.addExtra(noBtn);

        p.spigot().sendMessage(promptMsg);
        p.sendMessage(ChatColor.YELLOW + "========================================");
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 1.0f);
    }

    private void deleteHomeByIndex(Player p, int slotDisplayNumber) {
        int slotIndex = slotDisplayNumber - 1;
        String path = "homes." + p.getUniqueId().toString() + "." + slotIndex;
        dataConfig.set(path, null);
        saveDataConfig();
        p.sendMessage(ChatColor.GREEN + "Home erfolgreich gelöscht.");
        p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_DESTROY, 1.0f, 1.0f);
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

        Location loc = (Location) dataConfig.get(path + ".location");
        if (loc == null || loc.getWorld() == null) {
            p.sendMessage(ChatColor.RED + "Fehler: Die Teleportationskoordinaten wurden nicht gefunden.");
            return;
        }

        String targetDim = dataConfig.getString("admin-slots." + index + ".dimension", "OVERWORLD");
        String currentHomeDim = getDimensionName(loc.getWorld().getEnvironment());

        if (!targetDim.equals(currentHomeDim)) {
            pendingLastTeleports.put(p.getUniqueId(), index);

            String dimText;
            if (targetDim.equalsIgnoreCase("NETHER")) dimText = "zu einem NETHER-Home";
            else if (targetDim.equalsIgnoreCase("END")) dimText = "zu einem END-Home";
            else dimText = "zu einem OVERWORLD-Home";

            // Schöne anklickbare Chat-Warnung mit [JA] und [ABBRECHEN] Buttons
            p.sendMessage(ChatColor.YELLOW + "========================================");
            p.sendMessage(ChatColor.RED + "Achtung: Dieser Home-Slot wurde vom Admin " + dimText + " umgeändert.");
            p.sendMessage(ChatColor.GRAY + "Möchtest du dich ein letztes Mal dorthin teleportieren, um deinen Punkt zu verlegen?");

            TextComponent promptMsg = new TextComponent("Einmaliger Teleport: ");
            promptMsg.setColor(ChatColor.YELLOW);

            TextComponent yesBtn = new TextComponent("§a§l[JA]");
            yesBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/homedelete confirm " + (index + 1)));

            TextComponent space = new TextComponent("   ");

            TextComponent noBtn = new TextComponent("§c§l[ABBRECHEN]");
            noBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/homedelete cancel"));

            promptMsg.addExtra(yesBtn);
            promptMsg.addExtra(space);
            promptMsg.addExtra(noBtn);

            p.spigot().sendMessage(promptMsg);
            p.sendMessage(ChatColor.YELLOW + "========================================");
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 1.0f);
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

        p.teleport(loc);
        uCdMap.put(index, now);
        p.sendMessage(ChatColor.GREEN + "Teleportiert zu Home '" + dataConfig.getString(path + ".name") + "'!");
        p.playSound(loc, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
    }

    private void executeLastTeleportAndClear(Player p, int index) {
        String uuidStr = p.getUniqueId().toString();
        String path = "homes." + uuidStr + "." + index;

        Location loc = (Location) dataConfig.get(path + ".location");
        if (loc != null && loc.getWorld() != null) {
            p.teleport(loc);
            p.sendMessage(ChatColor.YELLOW + "Letzter Teleport ausgeführt! Das Home auf diesem Slot wurde nun gelöscht.");
            p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
        }

        dataConfig.set(path, null);
        saveDataConfig();
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
    public void onPlayerQuit(PlayerQuitEvent event) {
        pendingDeletes.remove(event.getPlayer().getUniqueId());
        pendingLastTeleports.remove(event.getPlayer().getUniqueId());
        renamingHomeSlots.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onAsyncPlayerChat(AsyncPlayerChatEvent event) {
        Player p = event.getPlayer();
        if (renamingHomeSlots.containsKey(p.getUniqueId())) {
            event.setCancelled(true);
            int slotIndex = renamingHomeSlots.remove(p.getUniqueId());
            String newName = event.getMessage().trim();

            if (newName.length() > 20) {
                p.sendMessage(ChatColor.RED + "Der Name darf maximal 20 Zeichen lang sein.");
                return;
            }

            String uuidStr = p.getUniqueId().toString();
            String path = "homes." + uuidStr + "." + slotIndex;
            boolean isNewHome = !dataConfig.contains(path + ".name");

            dataConfig.set(path + ".name", newName);
            if (isNewHome) {
                dataConfig.set(path + ".location", p.getLocation());
                boolean isFirst = true;
                for (int i = 0; i < 5; i++) {
                    if (i != slotIndex && dataConfig.contains("homes." + uuidStr + "." + i + ".name")) {
                        isFirst = false;
                        break;
                    }
                }
                dataConfig.set(path + ".isMain", isFirst);
            }
            saveDataConfig();

            p.sendMessage(ChatColor.GREEN + (isNewHome ? "Home '" + newName + "' erfolgreich erstellt!" : "Home erfolgreich in '" + newName + "' umbenannt!"));
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);

            Bukkit.getScheduler().runTask(this, () -> openHomeMenu(p));
        }
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

            int unlockedHomes = dataConfig.getInt("upgrades." + p.getUniqueId() + ".unlocked_homes", 1);

            if (slot == 20) {
                if (unlockedHomes >= 2) {
                    p.sendMessage(ChatColor.YELLOW + "Bereits gekauft!");
                    return;
                }
                if (removeDiamonds(p, 128)) {
                    dataConfig.set("upgrades." + p.getUniqueId() + ".unlocked_homes", 2);
                    saveDataConfig();
                    p.sendMessage(ChatColor.GREEN + "Home-Slot #2 erfolgreich freigeschaltet!");
                    p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
                    openUpgradeGUI(p);
                } else {
                    p.sendMessage(ChatColor.RED + "Du benötigst 128 Diamanten (2 Stacks)!");
                }
            } else if (slot == 21) {
                if (unlockedHomes < 2) {
                    p.sendMessage(ChatColor.RED + "Du musst zuerst Slot #2 kaufen!");
                    return;
                }
                if (unlockedHomes >= 3) {
                    p.sendMessage(ChatColor.YELLOW + "Bereits gekauft!");
                    return;
                }
                if (removeDiamonds(p, 256)) {
                    dataConfig.set("upgrades." + p.getUniqueId() + ".unlocked_homes", 3);
                    saveDataConfig();
                    p.sendMessage(ChatColor.GREEN + "Home-Slot #3 erfolgreich freigeschaltet!");
                    p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
                    openUpgradeGUI(p);
                } else {
                    p.sendMessage(ChatColor.RED + "Du benötigst 256 Diamanten (4 Stacks)!");
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

                        int unlockedHomes = dataConfig.getInt("upgrades." + p.getUniqueId() + ".unlocked_homes", 1);

                        if ((i + 1) > unlockedHomes) {
                            p.sendMessage(ChatColor.RED + "Du musst Home-Slot #" + (i + 1) + " erst in /homeupgrades freischalten!");
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

                boolean isNewHome = !dataConfig.contains(path + ".name");

                dataConfig.set(path + ".banner", clicked.getType().name());
                dataConfig.set(path + ".bannerItem", clicked);
                saveDataConfig();

                if (isNewHome) {
                    renamingHomeSlots.put(p.getUniqueId(), slotIndex);
                    p.closeInventory();
                    p.sendMessage(ChatColor.YELLOW + "Schreibe jetzt den Namen für dein neues Home in den Chat:");
                } else {
                    p.closeInventory();
                    p.sendMessage(ChatColor.GREEN + "Banner-Design für " + dataConfig.getString(path + ".name") + " erfolgreich geändert!");
                    p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
                }
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
            } else if (event.getRawSlot() == 13) {
                renamingHomeSlots.put(p.getUniqueId(), targetIndex);
                p.closeInventory();
                p.sendMessage(ChatColor.YELLOW + "Schreibe jetzt den neuen Namen für dein Home in den Chat:");
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
            if (event.getRawSlot() == 26) {
                openHomeMenu(p);
                return;
            }

            int maxHomes = getConfig().getInt("max-homes", 5);
            int[] slots = getSlotsForAmount(maxHomes);

            for (int i = 0; i < slots.length; i++) {
                if (event.getRawSlot() == slots[i]) {
                    String current = dataConfig.getString("admin-slots." + i + ".dimension", "OVERWORLD");
                    String next = "OVERWORLD";
                    if (current.equals("OVERWORLD")) next = "NETHER";
                    else if (current.equals("NETHER")) next = "END";
                    else if (current.equals("END")) next = "OVERWORLD";

                    dataConfig.set("admin-slots." + i + ".dimension", next);
                    saveDataConfig();
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1.0f, 1.2f);
                    openAdminSettingsMenu(p);
                    return;
                }
            }

            if (event.getRawSlot() == 18) {
                if (maxHomes > 1) {
                    getConfig().set("max-homes", maxHomes - 1);
                    saveConfig();
                    openAdminSettingsMenu(p);
                }
            } else if (event.getRawSlot() == 20) {
                if (maxHomes < 5) {
                    getConfig().set("max-homes", maxHomes + 1);
                    saveConfig();
                    openAdminSettingsMenu(p);
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
        } else if (cmd.equals("settp") || cmd.equals("deltp") || cmd.equals("tpdelete")) {
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
