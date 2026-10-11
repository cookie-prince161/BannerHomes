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
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BannerHomes extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final String GUI_MAIN_TITLE = ChatColor.DARK_BLUE + "Deine Homes";
    private static final String GUI_ADMIN_TITLE = ChatColor.DARK_RED + "Admin: Homes Settings";
    private static final String GUI_BANNER_PREFIX = ChatColor.DARK_PURPLE + "Banner wählen [Slot ";
    private static final String GUI_SETTINGS_PREFIX = ChatColor.DARK_GRAY + "Einstellungen: ";
    private static final String GUI_CONFIRM_PREFIX = ChatColor.DARK_RED + "Löschen bestätigen: ";

    private final Map<UUID, PendingHome> pendingHomes = new HashMap<>();
    private final Map<UUID, Integer> pendingDeletes = new HashMap<>();
    private final Map<UUID, Integer> pendingLastTeleports = new HashMap<>();

    private final Map<UUID, Long> lastHomeTeleportTimes = new HashMap<>();
    private final Map<UUID, Long> lastSpawnTeleportTimes = new HashMap<>();
    private final Map<UUID, Long> lastSdTeleportTimes = new HashMap<>();

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
        registerCmd("home");
        registerCmd("homedelete");
        registerCmd("tp");
        registerCmd("settp");

        getLogger().info("BannerHomes v1.8.5 erfolgreich aktiviert!");
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
        lastHomeTeleportTimes.clear();
        lastSpawnTeleportTimes.clear();
        lastSdTeleportTimes.clear();
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

    private ItemStack cleanBannerItem(ItemStack item) {
        if (item == null) return null;
        ItemStack cleaned = item.clone();
        cleaned.setAmount(1);
        ItemMeta meta = cleaned.getItemMeta();
        if (meta != null) {
            if (meta.hasLore()) {
                List<String> lore = meta.getLore();
                if (lore != null) {
                    lore.removeIf(l -> l.contains("Löschen") || l.contains("Standard-Banner") || l.contains("Shift"));
                    meta.setLore(lore.isEmpty() ? null : lore);
                }
            }
            cleaned.setItemMeta(meta);
        }
        return cleaned;
    }

    // ===== GUIS =====

    public void openHomeMenu(Player p) {
        int maxHomes = getConfig().getInt("max-homes", 3);
        if (maxHomes <= 0) {
            p.sendMessage(ChatColor.RED + "Das Home-Feature ist derzeit abgeschaltet.");
            return;
        }

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

                // 1. Farbstoff-Item über dem Banner (Slot - 9)
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

                // 2. Banner in der Mitte (Slot)
                ItemMeta meta = bannerItem.getItemMeta();
                if (meta != null) {
                    meta.setDisplayName(ChatColor.GREEN + name + (isMain ? ChatColor.GOLD + " ★ (Main)" : ""));
                    List<String> lore = new ArrayList<>();
                    lore.add(ChatColor.GRAY + "Klicke zum Teleportieren");
                    meta.setLore(lore);
                    bannerItem.setItemMeta(meta);
                }
                inv.setItem(slot, bannerItem);

                // 3. Einstellung-Item unter dem Banner (Slot + 9)
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
                // Freier Slot
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

        p.openInventory(inv);
        p.playSound(p.getLocation(), Sound.BLOCK_CHEST_OPEN, 1.0f, 1.0f);
    }

    public void openAdminSettingsMenu(Player p) {
        int maxHomes = getConfig().getInt("max-homes", 3);
        if (maxHomes <= 0) maxHomes = 1;

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

        // Geändert zu COPPER_TORCH wenn Main-Home aktiv, sonst REDSTONE_TORCH
        ItemStack mainItem = new ItemStack(isMain ? Material.COPPER_TORCH : Material.REDSTONE_TORCH);
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

        if (cmdName.equals("homesettings")) {
            if (!p.hasPermission("bannerhomes.admin")) {
                p.sendMessage(ChatColor.RED + "Dazu hast du keine Rechte.");
                return true;
            }
            openAdminSettingsMenu(p);
            return true;
        }

        if (cmdName.equals("homes")) {
            if (args.length >= 2 && p.hasPermission("bannerhomes.admin")) {
                if (args[0].equalsIgnoreCase("setmax")) {
                    try {
                        int max = Integer.parseInt(args[1]);
                        if (max < 0 || max > 5) {
                            p.sendMessage(ChatColor.RED + "Bitte eine Zahl zwischen 0 und 5 angeben.");
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
                    if (args.length == 3) {
                        String target = args[1].toLowerCase();
                        try {
                            long cd = Long.parseLong(args[2]);
                            if (cd < 0) {
                                p.sendMessage(ChatColor.RED + "Der Cooldown darf nicht negativ sein.");
                                return true;
                            }
                            if (target.equals("spawn")) {
                                getConfig().set("spawn-cooldown-seconds", cd);
                                p.sendMessage(ChatColor.GREEN + "Spawn Teleport-Cooldown auf " + cd + " Sekunden gesetzt.");
                            } else if (target.equals("sd") || target.equals("shoppingdistrict")) {
                                getConfig().set("sd-cooldown-seconds", cd);
                                p.sendMessage(ChatColor.GREEN + "Shopping District Cooldown auf " + cd + " Sekunden gesetzt.");
                            } else if (target.equals("homes") || target.equals("home")) {
                                getConfig().set("teleport-cooldown-seconds", cd);
                                p.sendMessage(ChatColor.GREEN + "Home Teleport-Cooldown auf " + cd + " Sekunden gesetzt.");
                            } else {
                                p.sendMessage(ChatColor.RED + "Ungültiger Typ. Nutze: homes, spawn oder sd.");
                                return true;
                            }
                            saveConfig();
                            return true;
                        } catch (NumberFormatException e) {
                            p.sendMessage(ChatColor.RED + "Das ist keine gültige Zahl.");
                            return true;
                        }
                    } else if (args.length == 2) {
                        try {
                            long cd = Long.parseLong(args[1]);
                            if (cd < 0) {
                                p.sendMessage(ChatColor.RED + "Der Cooldown darf nicht negativ sein.");
                                return true;
                            }
                            getConfig().set("teleport-cooldown-seconds", cd);
                            saveConfig();
                            p.sendMessage(ChatColor.GREEN + "Home Teleport-Cooldown auf " + cd + " Sekunden gesetzt.");
                            return true;
                        } catch (NumberFormatException e) {
                            p.sendMessage(ChatColor.RED + "Benutzung: /homes cooldown [homes|spawn|sd] <Sekunden>");
                            return true;
                        }
                    }
                }
            }

            int maxHomes = getConfig().getInt("max-homes", 3);
            if (maxHomes <= 0) {
                p.sendMessage(ChatColor.RED + "Das Home-Feature ist derzeit abgeschaltet.");
                return true;
            }
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

        if (cmdName.equals("home")) {
            int maxHomes = getConfig().getInt("max-homes", 3);
            if (maxHomes <= 0) {
                p.sendMessage(ChatColor.RED + "Das Home-Feature ist derzeit abgeschaltet.");
                return true;
            }

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
                p.sendMessage(ChatColor.RED + "Benutzung: /settp <spawn|sd|shoppingdistrict>");
                return true;
            }

            String type = args[0].toLowerCase();
            Location loc = p.getLocation();

            if (type.equals("spawn")) {
                saveGlobalLocation("global.spawn", loc);
                p.sendMessage(ChatColor.GREEN + "Spawn-Punkt erfolgreich an deiner Position gesetzt!");
                p.playSound(loc, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
            } else if (type.equals("sd") || type.equals("shoppingdistrict")) {
                saveGlobalLocation("global.sd", loc);
                p.sendMessage(ChatColor.GREEN + "Shopping District Punkt erfolgreich an deiner Position gesetzt!");
                p.playSound(loc, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
            } else {
                p.sendMessage(ChatColor.RED + "Ungültiger Typ. Nutze 'spawn' oder 'sd'.");
            }
            return true;
        }

        if (cmdName.equals("tp")) {
            if (args.length == 0) {
                p.sendMessage(ChatColor.RED + "Benutzung: /tp <spawn|sd|shoppingdistrict>");
                return true;
            }

            String type = args[0].toLowerCase();

            if (type.equals("spawn")) {
                teleportGlobalWithCooldown(p, "global.spawn", lastSpawnTeleportTimes, "spawn-cooldown-seconds", "Spawn");
            } else if (type.equals("sd") || type.equals("shoppingdistrict")) {
                teleportGlobalWithCooldown(p, "global.sd", lastSdTeleportTimes, "sd-cooldown-seconds", "Shopping District");
            } else {
                p.sendMessage(ChatColor.RED + "Benutzung: /tp <spawn|sd|shoppingdistrict>");
            }
            return true;
        }

        return true;
    }

    private void saveGlobalLocation(String path, Location loc) {
        dataConfig.set(path + ".world", loc.getWorld().getName());
        dataConfig.set(path + ".x", loc.getX());
        dataConfig.set(path + ".y", loc.getY());
        dataConfig.set(path + ".z", loc.getZ());
        dataConfig.set(path + ".yaw", loc.getYaw());
        dataConfig.set(path + ".pitch", loc.getPitch());
        saveDataConfig();
    }

    private void teleportGlobalWithCooldown(Player p, String path, Map<UUID, Long> cooldownMap, String cooldownKey, String label) {
        if (!dataConfig.contains(path + ".world")) {
            p.sendMessage(ChatColor.RED + "Der " + label + "-Punkt wurde bisher noch nicht gesetzt!");
            return;
        }

        long now = System.currentTimeMillis();
        long cooldownSec = getConfig().getLong(cooldownKey, 60);
        long cooldownMs = cooldownSec * 1000;

        if (cooldownMap.containsKey(p.getUniqueId())) {
            long lastTime = cooldownMap.get(p.getUniqueId());
            long diff = now - lastTime;
            if (diff < cooldownMs) {
                long remainingSec = (cooldownMs - diff) / 1000;
                long minutes = remainingSec / 60;
                long seconds = remainingSec % 60;
                p.sendMessage(ChatColor.RED + "Du musst noch " + (minutes > 0 ? minutes + "m " : "") + seconds + "s warten, bevor du dich wieder zum " + label + " teleportieren kannst.");
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
        cooldownMap.put(p.getUniqueId(), now);
        p.sendMessage(ChatColor.GREEN + "Teleportiert zum " + label + "!");
        p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
    }

    private void requestInteractiveDelete(Player p, String arg) {
        String uuidStr = p.getUniqueId().toString();
        int maxHomes = getConfig().getInt("max-homes", 3);
        int targetIndex = -1;

        try {
            int num = Integer.parseInt(arg) - 1;
            if (num >= 0 && num < maxHomes) {
                targetIndex = num;
            }
        } catch (NumberFormatException ignored) {}

        if (targetIndex == -1) {
            for (int i = 0; i < maxHomes; i++) {
                String path = "homes." + uuidStr + "." + i;
                if (dataConfig.contains(path + ".name")) {
                    String name = dataConfig.getString(path + ".name");
                    if (name != null && name.equalsIgnoreCase(arg)) {
                        targetIndex = i;
                        break;
                    }
                }
            }
        }

        if (targetIndex != -1 && dataConfig.contains("homes." + uuidStr + "." + targetIndex)) {
            String name = dataConfig.getString("homes." + uuidStr + "." + targetIndex + ".name");
            pendingDeletes.put(p.getUniqueId(), targetIndex);

            p.sendMessage(ChatColor.YELLOW + "========================================");
            p.sendMessage(ChatColor.RED + "Möchtest du das Home '" + name + "' wirklich löschen?");

            TextComponent promptMsg = new TextComponent("Bestätigung: ");
            promptMsg.setColor(net.md_5.bungee.api.ChatColor.YELLOW);

            TextComponent yesBtn = new TextComponent("§a§l[JA]");
            yesBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/homedelete confirm " + targetIndex));

            TextComponent space = new TextComponent("   ");

            TextComponent noBtn = new TextComponent("§c§l[ABBRECHEN]");
            noBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/homedelete cancel"));

            promptMsg.addExtra(yesBtn);
            promptMsg.addExtra(space);
            promptMsg.addExtra(noBtn);

            p.spigot().sendMessage(promptMsg);
            p.sendMessage(ChatColor.YELLOW + "========================================");
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 1.0f);
        } else {
            p.sendMessage(ChatColor.RED + "Home '" + arg + "' existiert nicht.");
        }
    }

    // ===== TELEPORT VALIDIERUNG & OVERRIDE =====

    private void teleportToHomeByArg(Player p, String arg) {
        String uuidStr = p.getUniqueId().toString();
        int maxHomes = getConfig().getInt("max-homes", 3);

        try {
            int num = Integer.parseInt(arg) - 1;
            if (num >= 0 && num < maxHomes) {
                if (dataConfig.contains("homes." + uuidStr + "." + num + ".name")) {
                    teleportWithValidation(p, num);
                    return;
                }
            }
        } catch (NumberFormatException ignored) {}

        for (int i = 0; i < maxHomes; i++) {
            String path = "homes." + uuidStr + "." + i;
            if (dataConfig.contains(path + ".name")) {
                String name = dataConfig.getString(path + ".name");
                if (name != null && name.equalsIgnoreCase(arg)) {
                    teleportWithValidation(p, i);
                    return;
                }
            }
        }

        p.sendMessage(ChatColor.RED + "Home '" + arg + "' wurde nicht gefunden.");
    }

    private int getMainHomeIndex(Player p) {
        String uuidStr = p.getUniqueId().toString();
        int maxHomes = getConfig().getInt("max-homes", 3);

        for (int i = 0; i < maxHomes; i++) {
            String path = "homes." + uuidStr + "." + i;
            if (dataConfig.contains(path + ".name") && dataConfig.getBoolean(path + ".isMain", false)) {
                return i;
            }
        }
        return -1;
    }

    private void teleportWithValidation(Player p, int slotIndex) {
        if (slotIndex == -1) {
            p.sendMessage(ChatColor.RED + "Du hast noch kein Main-Home festgelegt! Öffne /homes zum Einstellen.");
            return;
        }

        String uuidStr = p.getUniqueId().toString();
        String path = "homes." + uuidStr + "." + slotIndex;

        if (!dataConfig.contains(path + ".world")) return;

        String homeWorldName = dataConfig.getString(path + ".world");
        World homeWorld = Bukkit.getWorld(homeWorldName);
        String actualHomeDim = (homeWorld != null) ? getDimensionName(homeWorld.getEnvironment()) : "OVERWORLD";
        String adminRequiredDim = dataConfig.getString("admin-slots." + slotIndex + ".dimension", "OVERWORLD");

        if (!actualHomeDim.equalsIgnoreCase(adminRequiredDim)) {
            pendingLastTeleports.put(p.getUniqueId(), slotIndex);

            String dimText;
            if (adminRequiredDim.equalsIgnoreCase("NETHER")) dimText = "zu einem NETHER-Home";
            else if (adminRequiredDim.equalsIgnoreCase("END")) dimText = "zu einem END-Home";
            else dimText = "zu einem OVERWORLD-Home";

            p.sendMessage(ChatColor.YELLOW + "========================================");
            p.sendMessage(ChatColor.RED + "Achtung: Dieser Home-Slot wurde vom Admin " + dimText + " umgeändert.");
            p.sendMessage(ChatColor.GRAY + "Möchtest du dich ein letztes Mal dorthin teleportieren, um deinen Punkt zu verlegen?");

            TextComponent promptMsg = new TextComponent("Einmaliger Teleport: ");
            promptMsg.setColor(net.md_5.bungee.api.ChatColor.YELLOW);

            TextComponent yesBtn = new TextComponent("§a§l[JA]");
            yesBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/homedelete confirm " + slotIndex));

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

        performHomeTeleport(p, path);
    }

    private void executeLastTeleportAndClear(Player p, int slotIndex) {
        String uuidStr = p.getUniqueId().toString();
        String path = "homes." + uuidStr + "." + slotIndex;

        performHomeTeleport(p, path);

        dataConfig.set(path, null);
        saveDataConfig();
        p.sendMessage(ChatColor.YELLOW + "Der veraltete Home-Punkt wurde entfernt. Du kannst hier nun ein neues Home setzen!");
    }

    private void performHomeTeleport(Player p, String path) {
        long now = System.currentTimeMillis();
        long cooldownSec = getConfig().getLong("teleport-cooldown-seconds", 120);
        long cooldownMs = cooldownSec * 1000;

        if (lastHomeTeleportTimes.containsKey(p.getUniqueId())) {
            long lastTime = lastHomeTeleportTimes.get(p.getUniqueId());
            long diff = now - lastTime;
            if (diff < cooldownMs) {
                long remainingSec = (cooldownMs - diff) / 1000;
                long minutes = remainingSec / 60;
                long seconds = remainingSec % 60;
                p.sendMessage(ChatColor.RED + "Du musst noch " + (minutes > 0 ? minutes + "m " : "") + seconds + "s warten, bevor du dich wieder teleportieren kannst.");
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
        lastHomeTeleportTimes.put(p.getUniqueId(), now);

        String name = dataConfig.getString(path + ".name");
        p.sendMessage(ChatColor.GREEN + "Teleportiert zu " + name + "!");
        p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
    }

    private void deleteHomeByIndex(Player p, int targetIndex) {
        String uuidStr = p.getUniqueId().toString();
        if (dataConfig.contains("homes." + uuidStr + "." + targetIndex)) {
            String name = dataConfig.getString("homes." + uuidStr + "." + targetIndex + ".name");
            dataConfig.set("homes." + uuidStr + "." + targetIndex, null);
            saveDataConfig();
            p.sendMessage(ChatColor.GREEN + "Home '" + name + "' wurde gelöscht.");
            p.playSound(p.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
        }
    }

    private void setMainHome(Player p, int index) {
        String uuidStr = p.getUniqueId().toString();
        int maxHomes = getConfig().getInt("max-homes", 3);

        for (int i = 0; i < maxHomes; i++) {
            String path = "homes." + uuidStr + "." + i;
            if (dataConfig.contains(path + ".name")) {
                dataConfig.set(path + ".isMain", i == index);
            }
        }
        saveDataConfig();
    }

    // ===== EVENTS =====

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();
        String cleanTitle = ChatColor.stripColor(title);

        if (!title.equals(GUI_MAIN_TITLE) && !title.equals(GUI_ADMIN_TITLE)
                && !cleanTitle.startsWith("Banner wählen [Slot ")
                && !title.startsWith(GUI_SETTINGS_PREFIX) && !title.startsWith(GUI_CONFIRM_PREFIX)) {
            return;
        }

        if (!(event.getWhoClicked() instanceof Player)) return;
        Player p = (Player) event.getWhoClicked();

        if (event.getClickedInventory() == p.getInventory()) {
            ItemStack clickedItem = event.getCurrentItem();
            if (clickedItem != null && clickedItem.getType().name().contains("BANNER")) {
                return;
            } else {
                event.setCancelled(true);
                return;
            }
        }

        event.setCancelled(true);

        int maxHomes = getConfig().getInt("max-homes", 3);
        int[] slots = getSlotsForAmount(maxHomes);

        ItemStack current = event.getCurrentItem();

        // Banner-Menü Logik
        if (cleanTitle.startsWith("Banner wählen [Slot ")) {
            int slotIndex = extractSlotIndexFromTitle(title);
            if (slotIndex < 0) return;

            List<ItemStack> customBanners = getCustomBanners(p);
            int importSlotIndex = 16 + customBanners.size();
            int clickedSlot = event.getSlot();

            // Klick auf leere Fläche mit Banner am Cursor
            if ((current == null || current.getType() == Material.AIR) && clickedSlot == importSlotIndex) {
                ItemStack cursor = event.getCursor();
                if (cursor != null && cursor.getType().name().contains("BANNER")) {
                    saveCustomBanner(p, cursor);
                    if (cursor.getAmount() > 1) {
                        cursor.setAmount(cursor.getAmount() - 1);
                    } else {
                        p.setItemOnCursor(null);
                    }
                    p.sendMessage(ChatColor.GREEN + "Eigenes Banner-Muster erfolgreich gespeichert!");
                    p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
                    openBannerSelectMenu(p, slotIndex);
                }
                return;
            }

            if (current == null || current.getType() == Material.AIR) return;

            // Zurück Button
            if (clickedSlot == 26 && current.getType() == Material.RED_STAINED_GLASS_PANE) {
                p.closeInventory();
                openHomeMenu(p);
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
                return;
            }

            // Klick auf Import-Glasscheibe
            if (clickedSlot == importSlotIndex && current.getType() == Material.LIGHT_GRAY_STAINED_GLASS_PANE) {
                ItemStack cursor = event.getCursor();
                if (cursor != null && cursor.getType().name().contains("BANNER")) {
                    saveCustomBanner(p, cursor);
                    if (cursor.getAmount() > 1) {
                        cursor.setAmount(cursor.getAmount() - 1);
                    } else {
                        p.setItemOnCursor(null);
                    }
                    p.sendMessage(ChatColor.GREEN + "Eigenes Banner-Muster erfolgreich gespeichert!");
                    p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
                    openBannerSelectMenu(p, slotIndex);
                }
                return;
            }

            // Custom Banner per Shift-Klick entfernen und Lore bereinigen
            if (clickedSlot >= 16 && clickedSlot < importSlotIndex) {
                int customIndex = clickedSlot - 16;
                if (event.isShiftClick()) {
                    removeCustomBanner(p, customIndex);
                    if (current != null && current.getType() != Material.AIR) {
                        ItemStack returnBanner = cleanBannerItem(current);
                        p.getInventory().addItem(returnBanner);
                    }
                    p.sendMessage(ChatColor.YELLOW + "Benutzerdefiniertes Banner aus der Auswahl entfernt und ins Inventar zurückgelegt.");
                    p.playSound(p.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
                    openBannerSelectMenu(p, slotIndex);
                    return;
                }
            }

            // Ausgewähltes Banner als Home-Banner festlegen (mit bereinigter Lore)
            if (current != null && current.getType().name().contains("BANNER")) {
                ItemStack chosenBanner = cleanBannerItem(current);

                String playerUuidStr = p.getUniqueId().toString();
                String targetPath = "homes." + playerUuidStr + "." + slotIndex;

                if (dataConfig.contains(targetPath + ".name")) {
                    dataConfig.set(targetPath + ".bannerItem", chosenBanner);
                    saveDataConfig();
                    p.closeInventory();
                    p.sendMessage(ChatColor.GREEN + "Banner-Design für Home '" + dataConfig.getString(targetPath + ".name") + "' erfolgreich geändert!");
                    p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
                    openHomeMenu(p);
                } else {
                    pendingHomes.put(p.getUniqueId(), new PendingHome(slotIndex, chosenBanner, p.getLocation()));
                    p.closeInventory();
                    p.sendMessage(ChatColor.YELLOW + "========================================");
                    p.sendMessage(ChatColor.GREEN + "Gib nun den Namen für dein Home in den Chat ein.");
                    p.sendMessage(ChatColor.YELLOW + "========================================");
                    p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
                }
            }
            return;
        }

        if (current == null || current.getType() == Material.AIR) return;

        if ((event.getSlot() == 0 || event.getSlot() == 26) && current.getType() == Material.RED_STAINED_GLASS_PANE) {
            p.closeInventory();
            openHomeMenu(p);
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
            return;
        }

        // 1. HAUPTMENÜ (/homes)
        if (title.equals(GUI_MAIN_TITLE)) {
            int slot = event.getSlot();

            for (int i = 0; i < slots.length; i++) {
                int baseSlot = slots[i];

                if (slot == baseSlot - 9) {
                    p.closeInventory();
                    openBannerSelectMenu(p, i);
                    p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
                    return;
                }
                if (slot == baseSlot) {
                    if (current.getType() == Material.COMPASS || current.getType() == Material.RECOVERY_COMPASS || current.getType() == Material.CHORUS_FRUIT) {
                        String requiredDim = dataConfig.getString("admin-slots." + i + ".dimension", "OVERWORLD");
                        String currentDim = getDimensionName(p.getWorld().getEnvironment());

                        if (!currentDim.equalsIgnoreCase(requiredDim)) {
                            p.closeInventory();
                            p.sendMessage(ChatColor.RED + "Du musst dich im " + requiredDim + " befinden, um dieses Home zu setzen!");
                            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                            return;
                        }

                        p.closeInventory();
                        openBannerSelectMenu(p, i);
                        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
                    } else if (current.getType().name().contains("BANNER")) {
                        p.closeInventory();
                        teleportWithValidation(p, i);
                    }
                    return;
                }
                if (slot == baseSlot + 9) {
                    p.closeInventory();
                    openSettingsMenu(p, i);
                    p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
                    return;
                }
            }
        }

        // 2. ADMIN-SETTINGS-MENÜ (/homesettings)
        else if (title.equals(GUI_ADMIN_TITLE)) {
            int slot = event.getSlot();

            for (int i = 0; i < slots.length; i++) {
                int baseSlot = slots[i];
                if (slot == baseSlot || slot == baseSlot + 9) {
                    String currentDim = dataConfig.getString("admin-slots." + i + ".dimension", "OVERWORLD");

                    String nextDim = "OVERWORLD";
                    if (currentDim.equals("OVERWORLD")) nextDim = "NETHER";
                    else if (currentDim.equals("NETHER")) nextDim = "END";
                    else if (currentDim.equals("END")) nextDim = "OVERWORLD";

                    dataConfig.set("admin-slots." + i + ".dimension", nextDim);
                    saveDataConfig();
                    p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
                    openAdminSettingsMenu(p);
                    return;
                }
            }
        }

        // 3. EINSTELLUNGEN
        else if (title.startsWith(GUI_SETTINGS_PREFIX)) {
            String homeName = title.replace(GUI_SETTINGS_PREFIX, "");
            int homeIndex = getHomeIndexByName(p, homeName);

            if (homeIndex == -1) return;

            if (event.getSlot() == 11) {
                setMainHome(p, homeIndex);
                p.sendMessage(ChatColor.GREEN + homeName + " ist nun dein Main-Home!");
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
                openSettingsMenu(p, homeIndex);
            } else if (event.getSlot() == 15) {
                p.closeInventory();
                openConfirmDeleteMenu(p, homeIndex);
            }
        }

        // 4. LÖSCHEN BESTÄTIGEN
        else if (title.startsWith(GUI_CONFIRM_PREFIX)) {
            int openBracket = title.lastIndexOf("[");
            int closeBracket = title.lastIndexOf("]");
            if (openBracket == -1 || closeBracket == -1) return;

            int homeIndex = Integer.parseInt(title.substring(openBracket + 1, closeBracket));

            if (event.getSlot() == 12 && current.getType() == Material.GREEN_WOOL) {
                p.closeInventory();
                deleteHomeByIndex(p, homeIndex);
            } else if (event.getSlot() == 14 && current.getType() == Material.RED_WOOL) {
                p.closeInventory();
                openHomeMenu(p);
            }
        }
    }

    private int getHomeIndexByName(Player p, String name) {
        String uuidStr = p.getUniqueId().toString();
        int maxHomes = getConfig().getInt("max-homes", 3);
        for (int i = 0; i < maxHomes; i++) {
            String path = "homes." + uuidStr + "." + i;
            if (dataConfig.contains(path + ".name")) {
                if (name.equalsIgnoreCase(dataConfig.getString(path + ".name"))) {
                    return i;
                }
            }
        }
        return -1;
    }

    // ===== CHAT EINGABE =====

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player p = event.getPlayer();
        UUID id = p.getUniqueId();

        if (pendingHomes.containsKey(id)) {
            event.setCancelled(true);
            PendingHome pending = pendingHomes.remove(id);
            String homeName = event.getMessage().trim();

            Bukkit.getScheduler().runTask(this, () -> {
                String uuidStr = id.toString();
                String path = "homes." + uuidStr + "." + pending.slotIndex;
                Location loc = pending.location;

                boolean isFirst = true;
                for (int i = 0; i < 5; i++) {
                    if (dataConfig.contains("homes." + uuidStr + "." + i + ".name")) {
                        isFirst = false;
                        break;
                    }
                }

                dataConfig.set(path + ".name", homeName);
                dataConfig.set(path + ".bannerItem", pending.bannerItem);
                dataConfig.set(path + ".isMain", isFirst);
                dataConfig.set(path + ".world", loc.getWorld().getName());
                dataConfig.set(path + ".x", loc.getX());
                dataConfig.set(path + ".y", loc.getY());
                dataConfig.set(path + ".z", loc.getZ());
                dataConfig.set(path + ".yaw", loc.getYaw());
                dataConfig.set(path + ".pitch", loc.getPitch());

                saveDataConfig();

                p.sendMessage(ChatColor.GREEN + "Home '" + homeName + "' wurde erfolgreich gespeichert!");
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
            });
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pendingHomes.remove(event.getPlayer().getUniqueId());
        pendingDeletes.remove(event.getPlayer().getUniqueId());
        pendingLastTeleports.remove(event.getPlayer().getUniqueId());
        lastHomeTeleportTimes.remove(event.getPlayer().getUniqueId());
        lastSpawnTeleportTimes.remove(event.getPlayer().getUniqueId());
        lastSdTeleportTimes.remove(event.getPlayer().getUniqueId());
    }

    // ===== TAB COMPLETER =====

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> list = new ArrayList<>();
        if (!(sender instanceof Player)) return list;
        Player p = (Player) sender;
        String cmd = command.getName().toLowerCase();

        if (cmd.equals("homes")) {
            if (args.length == 1) {
                if (p.hasPermission("bannerhomes.admin")) {
                    list.add("setmax");
                    list.add("cooldown");
                }
            } else if (args.length == 2 && args[0].equalsIgnoreCase("setmax") && p.hasPermission("bannerhomes.admin")) {
                list.add("0"); list.add("1"); list.add("2"); list.add("3"); list.add("4"); list.add("5");
            } else if (args.length == 2 && args[0].equalsIgnoreCase("cooldown") && p.hasPermission("bannerhomes.admin")) {
                list.add("homes"); list.add("spawn"); list.add("sd");
            } else if (args.length == 3 && args[0].equalsIgnoreCase("cooldown") && p.hasPermission("bannerhomes.admin")) {
                list.add("30"); list.add("60"); list.add("120"); list.add("300");
            }
        } else if (cmd.equals("home") || cmd.equals("homedelete")) {
            if (args.length == 1) {
                String uuidStr = p.getUniqueId().toString();
                int maxHomes = getConfig().getInt("max-homes", 3);
                for (int i = 0; i < maxHomes; i++) {
                    String path = "homes." + uuidStr + "." + i;
                    if (dataConfig.contains(path + ".name")) {
                        list.add(dataConfig.getString(path + ".name"));
                        list.add(String.valueOf(i + 1));
                    }
                }
            }
        } else if (cmd.equals("tp") || cmd.equals("settp")) {
            if (args.length == 1) {
                list.add("spawn");
                list.add("sd");
                list.add("shoppingdistrict");
            }
        }

        return list;
    }
}
