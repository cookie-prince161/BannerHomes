package com.elias.bannerhomes;

import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
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

public class BannerHomes extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final String GUI_MAIN_TITLE = ChatColor.DARK_BLUE + "Deine Homes";
    private static final String GUI_BANNER_TITLE = ChatColor.DARK_PURPLE + "Wähle eine Bannerfarbe";
    private static final String GUI_SETTINGS_PREFIX = ChatColor.DARK_GRAY + "Einstellungen: ";
    private static final String GUI_CONFIRM_PREFIX = ChatColor.DARK_RED + "Löschen bestätigen: ";

    private final Map<UUID, PendingHome> pendingHomes = new HashMap<>();

    private File dataFile;
    private FileConfiguration dataConfig;

    private static class PendingHome {
        final int slotIndex;
        final Material bannerMaterial;
        final Location location;

        PendingHome(int slotIndex, Material bannerMaterial, Location location) {
            this.slotIndex = slotIndex;
            this.bannerMaterial = bannerMaterial;
            this.location = location;
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadDataConfig();

        getServer().getPluginManager().registerEvents(this, this);

        if (getCommand("homes") != null) getCommand("homes").setExecutor(this);
        if (getCommand("home") != null) {
            getCommand("home").setExecutor(this);
            getCommand("home").setTabCompleter(this);
        }
        if (getCommand("homedelete") != null) {
            getCommand("homedelete").setExecutor(this);
            getCommand("homedelete").setTabCompleter(this);
        }

        getLogger().info("BannerHomes erfolgreich aktiviert!");
    }

    @Override
    public void onDisable() {
        pendingHomes.clear();
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

    // ===== GUIS =====

    public void openHomeMenu(Player p) {
        Inventory inv = Bukkit.createInventory(null, 27, GUI_MAIN_TITLE);

        int maxHomes = getConfig().getInt("max-homes", 3);
        if (maxHomes < 1) maxHomes = 1;
        if (maxHomes > 5) maxHomes = 5;

        int[] slots = getSlotsForAmount(maxHomes);
        String uuidStr = p.getUniqueId().toString();

        for (int i = 0; i < slots.length; i++) {
            int slot = slots[i];
            String path = "homes." + uuidStr + "." + i;

            if (dataConfig.contains(path + ".name")) {
                String name = dataConfig.getString(path + ".name");
                String matName = dataConfig.getString(path + ".banner");
                boolean isMain = dataConfig.getBoolean(path + ".isMain", false);

                Material bannerMat = Material.matchMaterial(matName != null ? matName : "WHITE_BANNER");
                if (bannerMat == null) bannerMat = Material.WHITE_BANNER;

                // 1. Farbstoff-Item über dem Banner (Slot - 9)
                ItemStack dyeItem = new ItemStack(getDyeForBanner(bannerMat));
                ItemMeta dyeMeta = dyeItem.getItemMeta();
                if (dyeMeta != null) {
                    dyeMeta.setDisplayName(ChatColor.AQUA + "Farbe ändern " + ChatColor.GRAY + "(Home " + (i + 1) + ")");
                    List<String> lore = new ArrayList<>();
                    lore.add(ChatColor.GRAY + "Klicke, um die Bannerfarbe anzupassen.");
                    dyeMeta.setLore(lore);
                    dyeItem.setItemMeta(dyeMeta);
                }
                inv.setItem(slot - 9, dyeItem);

                // 2. Banner in der Mitte (Slot)
                ItemStack banner = new ItemStack(bannerMat);
                ItemMeta meta = banner.getItemMeta();
                if (meta != null) {
                    meta.setDisplayName(ChatColor.GREEN + name + (isMain ? ChatColor.GOLD + " ★ (Main)" : ""));
                    List<String> lore = new ArrayList<>();
                    lore.add(ChatColor.GRAY + "Klicke zum Teleportieren");
                    meta.setLore(lore);
                    banner.setItemMeta(meta);
                }
                inv.setItem(slot, banner);

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
                // Freier Slot (Kompass)
                ItemStack compass = new ItemStack(Material.COMPASS);
                ItemMeta meta = compass.getItemMeta();
                if (meta != null) {
                    meta.setDisplayName(ChatColor.YELLOW + "Home-Slot " + (i + 1) + " " + ChatColor.GRAY + "(Frei)");
                    List<String> lore = new ArrayList<>();
                    lore.add(ChatColor.GRAY + "Klicke, um ein Home zu erstellen");
                    meta.setLore(lore);
                    compass.setItemMeta(meta);
                }
                inv.setItem(slot, compass);
            }
        }

        p.openInventory(inv);
    }

    public void openBannerSelectMenu(Player p, int slotIndex) {
        Inventory inv = Bukkit.createInventory(null, 27, GUI_BANNER_TITLE);

        Material[] banners = {
                Material.WHITE_BANNER, Material.ORANGE_BANNER, Material.MAGENTA_BANNER,
                Material.LIGHT_BLUE_BANNER, Material.YELLOW_BANNER, Material.LIME_BANNER,
                Material.PINK_BANNER, Material.GRAY_BANNER, Material.LIGHT_GRAY_BANNER,
                Material.CYAN_BANNER, Material.PURPLE_BANNER, Material.BLUE_BANNER,
                Material.BROWN_BANNER, Material.GREEN_BANNER, Material.RED_BANNER,
                Material.BLACK_BANNER
        };

        for (Material b : banners) {
            ItemStack item = new ItemStack(b);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.GOLD + "Slot-Index:" + slotIndex);
                item.setItemMeta(meta);
            }
            inv.addItem(item);
        }

        p.openInventory(inv);
    }

    public void openSettingsMenu(Player p, int homeIndex) {
        String uuidStr = p.getUniqueId().toString();
        String path = "homes." + uuidStr + "." + homeIndex;
        String name = dataConfig.getString(path + ".name", "Home " + (homeIndex + 1));
        boolean isMain = dataConfig.getBoolean(path + ".isMain", false);

        Inventory inv = Bukkit.createInventory(null, 27, GUI_SETTINGS_PREFIX + name);

        // Glasscheibe für Main-Home Status (Slot 11)
        ItemStack glass = new ItemStack(isMain ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE);
        ItemMeta glassMeta = glass.getItemMeta();
        if (glassMeta != null) {
            glassMeta.setDisplayName(isMain ? ChatColor.GREEN + "✔ Main-Home (Aktiv)" : ChatColor.RED + "✖ Kein Main-Home");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Klicke, um dieses Home als Standard /home zu setzen.");
            glassMeta.setLore(lore);
            glass.setItemMeta(glassMeta);
        }
        inv.setItem(11, glass);

        // Löschen Button (Slot 15)
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

        p.openInventory(inv);
    }

    public void openConfirmDeleteMenu(Player p, int homeIndex) {
        String uuidStr = p.getUniqueId().toString();
        String name = dataConfig.getString("homes." + uuidStr + "." + homeIndex + ".name", "Home");

        Inventory inv = Bukkit.createInventory(null, 27, GUI_CONFIRM_PREFIX + name + " [" + homeIndex + "]");

        // Bestätigen (Grüne Wolle auf Slot 13 - im Prompt war Slot 13/15 gewünscht)
        ItemStack yes = new ItemStack(Material.GREEN_WOOL);
        ItemMeta yesMeta = yes.getItemMeta();
        if (yesMeta != null) {
            yesMeta.setDisplayName(ChatColor.GREEN + "Löschen bestätigen");
            yes.setItemMeta(yesMeta);
        }
        inv.setItem(13, yes);

        // Abbrechen (Rote Wolle auf Slot 15)
        ItemStack no = new ItemStack(Material.RED_WOOL);
        ItemMeta noMeta = no.getItemMeta();
        if (noMeta != null) {
            noMeta.setDisplayName(ChatColor.RED + "Abbrechen");
            no.setItemMeta(noMeta);
        }
        inv.setItem(15, no);

        p.openInventory(inv);
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

        // /homes
        if (cmdName.equals("homes")) {
            openHomeMenu(p);
            p.playSound(p.getLocation(), Sound.BLOCK_CHEST_OPEN, 1.0f, 1.0f);
            return true;
        }

        // /homedelete <Name|Nummer>
        if (cmdName.equals("homedelete")) {
            if (args.length == 0) {
                p.sendMessage(ChatColor.RED + "Benutzung: /homedelete <Name|Nummer>");
                return true;
            }
            deleteHomeByArg(p, args[0]);
            return true;
        }

        // /home [Name|Nummer]
        if (cmdName.equals("home")) {
            if (args.length == 0) {
                // Teleport zum Main-Home
                teleportToMainHome(p);
                return true;
            } else {
                teleportToHomeByArg(p, args[0]);
                return true;
            }
        }

        return true;
    }

    // ===== TELEPORT LOGIK =====

    private void teleportToMainHome(Player p) {
        String uuidStr = p.getUniqueId().toString();
        int maxHomes = getConfig().getInt("max-homes", 3);

        for (int i = 0; i < maxHomes; i++) {
            String path = "homes." + uuidStr + "." + i;
            if (dataConfig.contains(path + ".name") && dataConfig.getBoolean(path + ".isMain", false)) {
                teleportToPath(p, path);
                return;
            }
        }
        p.sendMessage(ChatColor.RED + "Du hast noch kein Main-Home festgelegt! Öffne /homes zum Einstellen.");
    }

    private void teleportToHomeByArg(Player p, String arg) {
        String uuidStr = p.getUniqueId().toString();
        int maxHomes = getConfig().getInt("max-homes", 3);

        // Prüfen, ob als Zahl angegeben (z.B. /home 1)
        try {
            int num = Integer.parseInt(arg) - 1;
            if (num >= 0 && num < maxHomes) {
                String path = "homes." + uuidStr + "." + num;
                if (dataConfig.contains(path + ".name")) {
                    teleportToPath(p, path);
                    return;
                }
            }
        } catch (NumberFormatException ignored) {}

        // Prüfen, ob als Name angegeben (z.B. /home Test)
        for (int i = 0; i < maxHomes; i++) {
            String path = "homes." + uuidStr + "." + i;
            if (dataConfig.contains(path + ".name")) {
                String name = dataConfig.getString(path + ".name");
                if (name != null && name.equalsIgnoreCase(arg)) {
                    teleportToPath(p, path);
                    return;
                }
            }
        }

        p.sendMessage(ChatColor.RED + "Home '" + arg + "' wurde nicht gefunden.");
    }

    private void teleportToPath(Player p, String path) {
        if (dataConfig.contains(path + ".world")) {
            Location loc = new Location(
                    Bukkit.getWorld(dataConfig.getString(path + ".world")),
                    dataConfig.getDouble(path + ".x"),
                    dataConfig.getDouble(path + ".y"),
                    dataConfig.getDouble(path + ".z"),
                    (float) dataConfig.getDouble(path + ".yaw"),
                    (float) dataConfig.getDouble(path + ".pitch")
            );
            p.teleport(loc);
            String name = dataConfig.getString(path + ".name");
            p.sendMessage(ChatColor.GREEN + "Teleportiert zu " + name + "!");
            p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
        }
    }

    private void deleteHomeByArg(Player p, String arg) {
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
            dataConfig.set("homes." + uuidStr + "." + targetIndex, null);
            saveDataConfig();
            p.sendMessage(ChatColor.GREEN + "Home '" + name + "' wurde gelöscht.");
            p.playSound(p.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
        } else {
            p.sendMessage(ChatColor.RED + "Home '" + arg + "' existiert nicht.");
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
        if (!title.equals(GUI_MAIN_TITLE) && !title.equals(GUI_BANNER_TITLE)
                && !title.startsWith(GUI_SETTINGS_PREFIX) && !title.startsWith(GUI_CONFIRM_PREFIX)) {
            return;
        }

        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player p = (Player) event.getWhoClicked();
        ItemStack current = event.getCurrentItem();
        if (current == null || current.getType() == Material.AIR) return;

        int maxHomes = getConfig().getInt("max-homes", 3);
        int[] slots = getSlotsForAmount(maxHomes);

        // 1. HAUPTMENÜ (/homes)
        if (title.equals(GUI_MAIN_TITLE)) {
            int slot = event.getSlot();

            for (int i = 0; i < slots.length; i++) {
                int baseSlot = slots[i];

                // Klick auf Farbstoff (Bannerfarbe ändern)
                if (slot == baseSlot - 9) {
                    p.closeInventory();
                    openBannerSelectMenu(p, i);
                    p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
                    return;
                }
                // Klick auf Banner / Kompass
                if (slot == baseSlot) {
                    if (current.getType() == Material.COMPASS) {
                        p.closeInventory();
                        openBannerSelectMenu(p, i);
                        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
                    } else if (current.getType().name().contains("BANNER")) {
                        p.closeInventory();
                        teleportToPath(p, "homes." + p.getUniqueId() + "." + i);
                    }
                    return;
                }
                // Klick auf Einstellungen (Verstärker)
                if (slot == baseSlot + 9) {
                    p.closeInventory();
                    openSettingsMenu(p, i);
                    p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
                    return;
                }
            }
        }

        // 2. BANNER-AUSWAHL
        else if (title.equals(GUI_BANNER_TITLE)) {
            ItemMeta meta = current.getItemMeta();
            if (meta == null || !meta.hasDisplayName()) return;

            String display = meta.getDisplayName();
            if (!display.startsWith(ChatColor.GOLD + "Slot-Index:")) return;

            int slotIndex = Integer.parseInt(display.replace(ChatColor.GOLD + "Slot-Index:", ""));
            Material chosenBanner = current.getType();

            String path = "homes." + p.getUniqueId() + "." + slotIndex;

            // Falls Home schon existiert -> Nur Farbe anpassen
            if (dataConfig.contains(path + ".name")) {
                dataConfig.set(path + ".banner", chosenBanner.name());
                saveDataConfig();
                p.closeInventory();
                p.sendMessage(ChatColor.GREEN + "Bannerfarbe erfolgreich geändert!");
                p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
                openHomeMenu(p);
            } else {
                // Neues Home anlegen -> Name abfragen
                pendingHomes.put(p.getUniqueId(), new PendingHome(slotIndex, chosenBanner, p.getLocation()));
                p.closeInventory();
                p.sendMessage(ChatColor.GREEN + "Gib nun den Namen für dein Home in den Chat ein.");
                p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
            }
        }

        // 3. EINSTELLUNGEN
        else if (title.startsWith(GUI_SETTINGS_PREFIX)) {
            String homeName = title.replace(GUI_SETTINGS_PREFIX, "");
            int homeIndex = getHomeIndexByName(p, homeName);

            if (homeIndex == -1) return;

            if (event.getSlot() == 11) { // Glasscheibe -> Main-Home umschalten
                setMainHome(p, homeIndex);
                p.sendMessage(ChatColor.GREEN + homeName + " ist nun dein Main-Home!");
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
                openSettingsMenu(p, homeIndex);
            } else if (event.getSlot() == 15) { // Löschen Button -> Bestätigen
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

            if (event.getSlot() == 13 && current.getType() == Material.GREEN_WOOL) {
                p.closeInventory();
                deleteHomeByArg(p, String.valueOf(homeIndex + 1));
            } else if (event.getSlot() == 15 && current.getType() == Material.RED_WOOL) {
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

        if (!pendingHomes.containsKey(id)) return;

        event.setCancelled(true);
        PendingHome pending = pendingHomes.remove(id);
        String homeName = event.getMessage().trim();

        Bukkit.getScheduler().runTask(this, () -> {
            String uuidStr = id.toString();
            String path = "homes." + uuidStr + "." + pending.slotIndex;
            Location loc = pending.location;

            // Erstes Home automatisch als Main-Home setzen
            boolean isFirst = true;
            for (int i = 0; i < 5; i++) {
                if (dataConfig.contains("homes." + uuidStr + "." + i + ".name")) {
                    isFirst = false;
                    break;
                }
            }

            dataConfig.set(path + ".name", homeName);
            dataConfig.set(path + ".banner", pending.bannerMaterial.name());
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

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pendingHomes.remove(event.getPlayer().getUniqueId());
    }

    // ===== TAB COMPLETER =====

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> list = new ArrayList<>();
        if (!(sender instanceof Player)) return list;
        Player p = (Player) sender;

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
        return list;
    }
}
