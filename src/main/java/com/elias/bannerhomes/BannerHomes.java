package com.elias.bannerhomes;

import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.banner.Pattern;
import org.bukkit.block.banner.PatternType;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
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
import org.bukkit.inventory.meta.BannerMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class BannerHomes extends JavaPlugin implements Listener, CommandExecutor {

    private static final String GUI_MAIN_TITLE = ChatColor.DARK_BLUE + "Deine Homes";
    private static final String GUI_BANNER_TITLE = ChatColor.DARK_PURPLE + "Wähle eine Bannerfarbe";

    private final Map<UUID, PendingHome> pendingHomes = new HashMap<>();

    private File dataFile;
    private FileConfiguration dataConfig;

    private static class PendingHome {
        final int slotIndex;
        final Material bannerMaterial;
        final boolean isOminous;
        final Location location;

        PendingHome(int slotIndex, Material bannerMaterial, boolean isOminous, Location location) {
            this.slotIndex = slotIndex;
            this.bannerMaterial = bannerMaterial;
            this.isOminous = isOminous;
            this.location = location;
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadDataConfig();

        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("home") != null) {
            getCommand("home").setExecutor(this);
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
            case 1:
                return new int[]{13};
            case 2:
                return new int[]{12, 14};
            case 3:
                return new int[]{11, 13, 15};
            case 4:
                return new int[]{10, 12, 14, 16};
            case 5:
                return new int[]{9, 11, 13, 15, 17};
            default:
                return new int[]{11, 13, 15};
        }
    }

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
                boolean isOminous = dataConfig.getBoolean(path + ".isOminous", false);

                Material mat = Material.matchMaterial(matName != null ? matName : "WHITE_BANNER");
                if (mat == null) mat = Material.WHITE_BANNER;

                ItemStack banner = isOminous ? createOminousBanner() : new ItemStack(mat);
                ItemMeta meta = banner.getItemMeta();
                if (meta != null) {
                    meta.setDisplayName(ChatColor.GREEN + name);
                    List<String> lore = new ArrayList<>();
                    lore.add(ChatColor.GRAY + "Klicke zum Teleportieren");
                    meta.setLore(lore);
                    banner.setItemMeta(meta);
                }
                inv.setItem(slot, banner);
            } else {
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

        // Ominous Banner als Extra-Item hinzufügen
        ItemStack ominous = createOminousBanner();
        ItemMeta oMeta = ominous.getItemMeta();
        if (oMeta != null) {
            oMeta.setDisplayName(ChatColor.GOLD + "Slot-Index:" + slotIndex + " (Ominous)");
            ominous.setItemMeta(oMeta);
        }
        inv.addItem(ominous);

        p.openInventory(inv);
    }

    private ItemStack createOminousBanner() {
        ItemStack item = new ItemStack(Material.WHITE_BANNER);
        BannerMeta meta = (BannerMeta) item.getItemMeta();
        if (meta != null) {
            meta.addPattern(new Pattern(DyeColor.CYAN, PatternType.RHOMBUS_MIDDLE));
            meta.addPattern(new Pattern(DyeColor.LIGHT_GRAY, PatternType.STRIPE_BOTTOM));
            meta.addPattern(new Pattern(DyeColor.GRAY, PatternType.CIRCLE_MIDDLE));
            meta.addPattern(new Pattern(DyeColor.LIGHT_GRAY, PatternType.BORDER));
            meta.addPattern(new Pattern(DyeColor.BLACK, PatternType.STRIPE_MIDDLE));
            meta.addPattern(new Pattern(DyeColor.LIGHT_GRAY, PatternType.HALF_HORIZONTAL));
            meta.addPattern(new Pattern(DyeColor.WHITE, PatternType.CIRCLE_MIDDLE));
            meta.addPattern(new Pattern(DyeColor.BLACK, PatternType.BORDER));
            item.setItemMeta(meta);
        }
        return item;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Dieser Befehl ist nur für Spieler.");
            return true;
        }

        Player p = (Player) sender;
        openHomeMenu(p);
        p.playSound(p.getLocation(), Sound.BLOCK_CHEST_OPEN, 1.0f, 1.0f);
        return true;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();
        if (!title.equals(GUI_MAIN_TITLE) && !title.equals(GUI_BANNER_TITLE)) {
            return;
        }

        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player)) return;
        Player p = (Player) event.getWhoClicked();
        ItemStack current = event.getCurrentItem();

        if (current == null || current.getType() == Material.AIR) return;

        int maxHomes = getConfig().getInt("max-homes", 3);
        int[] slots = getSlotsForAmount(maxHomes);

        if (title.equals(GUI_MAIN_TITLE)) {
            int clickedSlot = event.getSlot();
            int homeIndex = -1;

            for (int i = 0; i < slots.length; i++) {
                if (slots[i] == clickedSlot) {
                    homeIndex = i;
                    break;
                }
            }

            if (homeIndex == -1) return;

            String path = "homes." + p.getUniqueId().toString() + "." + homeIndex;

            if (current.getType() == Material.COMPASS) {
                p.closeInventory();
                openBannerSelectMenu(p, homeIndex);
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
            } else if (current.getType().name().contains("BANNER")) {
                p.closeInventory();
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
                    p.sendMessage(ChatColor.GREEN + "Teleportiert zu deinem Home!");
                    p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
                } else {
                    p.sendMessage(ChatColor.RED + "Fehler beim Laden des Homes!");
                }
            }
        } else if (title.equals(GUI_BANNER_TITLE)) {
            ItemMeta meta = current.getItemMeta();
            if (meta == null || !meta.hasDisplayName()) return;

            String display = meta.getDisplayName();
            if (!display.startsWith(ChatColor.GOLD + "Slot-Index:")) return;

            boolean isOminous = display.contains("(Ominous)");
            String cleanDisplay = display.replace(" (Ominous)", "");
            int slotIndex = Integer.parseInt(cleanDisplay.replace(ChatColor.GOLD + "Slot-Index:", ""));
            Material chosenBanner = current.getType();

            pendingHomes.put(p.getUniqueId(), new PendingHome(slotIndex, chosenBanner, isOminous, p.getLocation()));
            p.closeInventory();

            p.sendMessage(ChatColor.YELLOW + "========================================");
            p.sendMessage(ChatColor.GREEN + "Gib nun den Namen für dein Home in den Chat ein.");
            p.sendMessage(ChatColor.GRAY + "(Die Nachricht wird nicht an andere Spieler gesendet)");
            p.sendMessage(ChatColor.YELLOW + "========================================");
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
        }
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player p = event.getPlayer();
        UUID id = p.getUniqueId();

        if (!pendingHomes.containsKey(id)) return;

        event.setCancelled(true);
        PendingHome pending = pendingHomes.remove(id);
        String homeName = event.getMessage().trim();

        Bukkit.getScheduler().runTask(this, () -> {
            String path = "homes." + id.toString() + "." + pending.slotIndex;
            Location loc = pending.location;

            dataConfig.set(path + ".name", homeName);
            dataConfig.set(path + ".banner", pending.bannerMaterial.name());
            dataConfig.set(path + ".isOminous", pending.isOminous);
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
}
