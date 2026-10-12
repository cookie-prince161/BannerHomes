package com.elias.headcollection;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

public final class HeadCollection extends JavaPlugin implements Listener, CommandExecutor {

    private final Map<UUID, Set<String>> playerHeadCollections = new HashMap<>();
    private final Map<UUID, Set<String>> mobHeadCollections = new HashMap<>();

    // Grauer Fragezeichen-Kopf
    private static final String QUESTION_MARK_TEXTURE = "http://textures.minecraft.net/texture/26e6645a440f73bfb8d274f4a1b0cd0271f52233abd07a477b1e7dd7e84b4bd";

    // Echte Texture-URLs für Minecraft-Mobs ohne nativen Vanilla-Kopf
    private static final Map<EntityType, String> MOB_TEXTURES = new HashMap<>() {{
        put(EntityType.ENDERMAN, "http://textures.minecraft.net/texture/7aae3104a79815a375e23da1e309dd0e9f1a0e1b6f007f311c10712f5a0459c");
        put(EntityType.GHAST, "http://textures.minecraft.net/texture/8b6a3b21ac964db0714f970d73760e435f5493da167c1308a32a0d0d82d4");
        put(EntityType.BLAZE, "http://textures.minecraft.net/texture/b720ee28f2334863f6aed5ed1ec93e7f91885a535f21e06915809796856417");
        put(EntityType.WARDEN, "http://textures.minecraft.net/texture/918ed9b80ec18d360b9432f913d758f844781423719875e53e414168ee296a");
        put(EntityType.SPIDER, "http://textures.minecraft.net/texture/cd541541b7b5087c80918712a4413e1150494cf0722e03d360027f3f18e9d");
        put(EntityType.SHEEP, "http://textures.minecraft.net/texture/f31f9ccc6b3e32f35c36149734570162590ec0e6d60ecf640621379ef510a2f");
        put(EntityType.ENDERMITE, "http://textures.minecraft.net/texture/5a133f938fcf52278912d0943265ef0d8be92f7638069a53be86c8f8b80e81c1");
    }};

    // Kategorien: Jede Liste ist genau EINE Kiste im Menü
    private final List<String> categoryNames = Arrays.asList("Overworld", "Nether", "The End");
    private final Map<String, List<EntityType>> categories = new LinkedHashMap<>() {{
        put("Overworld", Arrays.asList(EntityType.ZOMBIE, EntityType.SKELETON, EntityType.CREEPER, EntityType.SPIDER, EntityType.SHEEP, EntityType.COW, EntityType.PIG, EntityType.CHICKEN, EntityType.ENDERMAN));
        put("Nether", Arrays.asList(EntityType.GHAST, EntityType.BLAZE, EntityType.WITHER_SKELETON, EntityType.PIGLIN, EntityType.MAGMA_CUBE, EntityType.PIGLIN_BRUTE, EntityType.STRIDER));
        put("The End", Arrays.asList(EntityType.ENDER_DRAGON, EntityType.SHULKER, EntityType.ENDERMITE));
    }};

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);

        Objects.requireNonNull(getCommand("headcollection")).setExecutor(this);
        Objects.requireNonNull(getCommand("playerheads")).setExecutor(this);
        Objects.requireNonNull(getCommand("mobheads")).setExecutor(this);

        getLogger().info("HeadCollection erfolgreich gestartet!");
    }

    @Override
    public void onDisable() {
        getLogger().info("HeadCollection deaktiviert.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) return true;
        Player player = (Player) sender;

        if (label.equalsIgnoreCase("headcollection") || label.equalsIgnoreCase("heads") || label.equalsIgnoreCase("hc")) {
            if (args.length >= 2 && args[0].equalsIgnoreCase("testplayer")) {
                String targetName = args[1];
                ItemStack head = new ItemStack(Material.PLAYER_HEAD);
                SkullMeta meta = (SkullMeta) head.getItemMeta();
                if (meta != null) {
                    meta.setOwningPlayer(Bukkit.getOfflinePlayer(targetName));
                    meta.setDisplayName(ChatColor.YELLOW + "Kopf von " + targetName);
                    head.setItemMeta(meta);
                }
                player.getWorld().dropItemNaturally(player.getLocation(), head);
                playerHeadCollections.putIfAbsent(player.getUniqueId(), new HashSet<>());
                playerHeadCollections.get(player.getUniqueId()).add(targetName);
                player.sendMessage(ChatColor.GREEN + "[Test] Kopf von " + targetName + " gespawnt!");
                return true;
            }
            openMainChoiceMenu(player);
            return true;
        } else if (label.equalsIgnoreCase("playerheads")) {
            openPlayerHeadCatalog(player, 0);
            return true;
        } else if (label.equalsIgnoreCase("mobheads")) {
            openMobHeadCategory(player, 0);
            return true;
        }

        return false;
    }

    private void openMainChoiceMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, ChatColor.DARK_PURPLE + "Kopf-Sammlungen");

        ItemStack playerIcon = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta pMeta = (SkullMeta) playerIcon.getItemMeta();
        if (pMeta != null) {
            pMeta.setOwningPlayer(player);
            pMeta.setDisplayName(ChatColor.GOLD + "Spieler-Trophäen");
            playerIcon.setItemMeta(pMeta);
        }

        ItemStack mobIcon = createCustomHead(QUESTION_MARK_TEXTURE, ChatColor.GREEN + "Mob-Köpfe Katalog");
        inv.setItem(11, playerIcon);
        inv.setItem(15, mobIcon);
        player.openInventory(inv);
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();

        if (killer != null && !killer.equals(victim)) {
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            if (meta != null) {
                meta.setOwningPlayer(victim);
                meta.setDisplayName(ChatColor.YELLOW + "Kopf von " + victim.getName());
                head.setItemMeta(meta);
            }
            victim.getWorld().dropItemNaturally(victim.getLocation(), head);
            playerHeadCollections.putIfAbsent(killer.getUniqueId(), new HashSet<>());
            playerHeadCollections.get(killer.getUniqueId()).add(victim.getName());
        }
    }

    private void openPlayerHeadCatalog(Player player, int page) {
        Inventory inv = Bukkit.createInventory(null, 54, ChatColor.DARK_BLUE + "Spieler-Katalog (S. " + (page + 1) + ")");
        List<String> allPlayers = Arrays.stream(Bukkit.getOfflinePlayers())
                .map(OfflinePlayer::getName)
                .filter(Objects::nonNull)
                .sorted()
                .collect(Collectors.toList());

        Set<String> collected = playerHeadCollections.getOrDefault(player.getUniqueId(), new HashSet<>());
        int start = page * 45;
        int end = Math.min(start + 45, allPlayers.size());

        for (int i = start; i < end; i++) {
            String targetName = allPlayers.get(i);
            ItemStack head;
            if (collected.contains(targetName)) {
                head = new ItemStack(Material.PLAYER_HEAD);
                SkullMeta meta = (SkullMeta) head.getItemMeta();
                if (meta != null) {
                    meta.setOwningPlayer(Bukkit.getOfflinePlayer(targetName));
                    meta.setDisplayName(ChatColor.GREEN + targetName);
                    head.setItemMeta(meta);
                }
            } else {
                head = createCustomHead(QUESTION_MARK_TEXTURE, ChatColor.RED + "??? (Fehlt)");
            }
            inv.setItem(i - start, head);
        }

        fillBottomRow(inv, page > 0, end < allPlayers.size());
        player.openInventory(inv);
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        Player killer = entity.getKiller();

        if (killer != null) {
            double chance = (killer.getGameMode() == GameMode.CREATIVE)
                    ? 1.0
                    : getConfig().getDouble("drop-chances." + entity.getType().name(), 0.05);

            if (Math.random() <= chance) {
                ItemStack mobHead = getMobHeadItem(entity.getType());
                entity.getWorld().dropItemNaturally(entity.getLocation(), mobHead);

                mobHeadCollections.putIfAbsent(killer.getUniqueId(), new HashSet<>());
                mobHeadCollections.get(killer.getUniqueId()).add(entity.getType().name());
                killer.sendMessage(ChatColor.GREEN + "Du hast den Kopf von " + formatMobName(entity.getType()) + " erhalten!");
            }
        }
    }

    private ItemStack getMobHeadItem(EntityType type) {
        // Native Minecraft-Köpfe direkt verwenden (garantiert perfekte Optik ohne URL-Fehler!)
        Material nativeMat = switch (type) {
            case CREEPER -> Material.CREEPER_HEAD;
            case ZOMBIE -> Material.ZOMBIE_HEAD;
            case SKELETON -> Material.SKELETON_SKULL;
            case WITHER_SKELETON -> Material.WITHER_SKELETON_SKULL;
            case ENDER_DRAGON -> Material.DRAGON_HEAD;
            case PIGLIN -> Material.PIGLIN_HEAD;
            default -> null;
        };

        if (nativeMat != null) {
            ItemStack item = new ItemStack(nativeMat);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.GOLD + formatMobName(type) + " Kopf");
                item.setItemMeta(meta);
            }
            return item;
        }

        // Für Mobs ohne eigenen Block-Kopf nutzen wir die Custom-Textur
        if (MOB_TEXTURES.containsKey(type)) {
            return createCustomHead(MOB_TEXTURES.get(type), ChatColor.GOLD + formatMobName(type) + " Kopf");
        }

        return createCustomHead(QUESTION_MARK_TEXTURE, ChatColor.GOLD + formatMobName(type) + " Kopf");
    }

    private void openMobHeadCategory(Player player, int categoryIndex) {
        if (categoryIndex < 0 || categoryIndex >= categoryNames.size()) return;
        String catName = categoryNames.get(categoryIndex);
        List<EntityType> mobs = categories.get(catName);

        Inventory inv = Bukkit.createInventory(null, 54, ChatColor.DARK_GREEN + "Kategorie: " + catName);
        Set<String> collectedMobs = mobHeadCollections.getOrDefault(player.getUniqueId(), new HashSet<>());

        for (int i = 0; i < mobs.size(); i++) {
            EntityType type = mobs.get(i);
            ItemStack item = collectedMobs.contains(type.name())
                    ? getMobHeadItem(type)
                    : createCustomHead(QUESTION_MARK_TEXTURE, ChatColor.RED + formatMobName(type) + " (Fehlt noch!)");
            inv.setItem(i, item);
        }

        fillBottomRow(inv, categoryIndex > 0, categoryIndex < categoryNames.size() - 1);
        player.openInventory(inv);
    }

    private void fillBottomRow(Inventory inv, boolean hasPrev, boolean hasNext) {
        ItemStack glass = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta gMeta = glass.getItemMeta();
        if (gMeta != null) {
            gMeta.setDisplayName(" ");
            glass.setItemMeta(gMeta);
        }

        for (int i = 45; i < 54; i++) {
            inv.setItem(i, glass);
        }

        if (hasPrev) {
            ItemStack prev = new ItemStack(Material.ARROW);
            ItemMeta pMeta = prev.getItemMeta();
            if (pMeta != null) {
                pMeta.setDisplayName(ChatColor.YELLOW + "◄ Vorherige Kiste");
                prev.setItemMeta(pMeta);
            }
            inv.setItem(45, prev);
        }

        if (hasNext) {
            ItemStack next = new ItemStack(Material.ARROW);
            ItemMeta nMeta = next.getItemMeta();
            if (nMeta != null) {
                nMeta.setDisplayName(ChatColor.YELLOW + "Nächste Kiste ➔");
                next.setItemMeta(nMeta);
            }
            inv.setItem(53, next);
        }
    }

    private String formatMobName(EntityType type) {
        String name = type.name().toLowerCase().replace("_", " ");
        return name.substring(0, 1).toUpperCase() + name.substring(1);
    }

    private ItemStack createCustomHead(String urlString, String displayName) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (urlString == null || urlString.isEmpty()) return head;

        SkullMeta headMeta = (SkullMeta) head.getItemMeta();
        if (headMeta == null) return head;

        try {
            Class<?> gameProfileClass = Class.forName("com.mojang.authlib.GameProfile");
            Class<?> propertyClass = Class.forName("com.mojang.authlib.properties.Property");

            // WICHTIG: Ein valider Profilname verhindert den NullPointerException-Fehler
            Constructor<?> gameProfileConstructor = gameProfileClass.getConstructor(UUID.class, String.class);
            Object profile = gameProfileConstructor.newInstance(UUID.randomUUID(), "HeadProfile");

            byte[] encodedData = Base64.getEncoder().encode(String.format("{\"textures\":{\"SKIN\":{\"url\":\"%s\"}}}", urlString).getBytes());
            Constructor<?> propertyConstructor = propertyClass.getConstructor(String.class, String.class);
            Object property = propertyConstructor.newInstance("textures", new String(encodedData));

            Method getPropertiesMethod = gameProfileClass.getMethod("getProperties");
            Object propertiesMap = getPropertiesMethod.invoke(profile);
            Method putMethod = propertiesMap.getClass().getMethod("put", Object.class, Object.class);
            putMethod.invoke(propertiesMap, "textures", property);

            Field profileField = headMeta.getClass().getDeclaredField("profile");
            profileField.setAccessible(true);
            profileField.set(headMeta, profile);
        } catch (Exception e) {
            e.printStackTrace();
        }

        headMeta.setDisplayName(displayName);
        head.setItemMeta(headMeta);
        return head;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        String title = event.getView().getTitle();

        if (title.contains("Kopf-Sammlungen") || title.contains("Spieler-Katalog") || title.contains("Kategorie:")) {
            event.setCancelled(true);
            Player player = (Player) event.getWhoClicked();
            int slot = event.getSlot();

            if (title.contains("Kopf-Sammlungen")) {
                if (slot == 11) openPlayerHeadCatalog(player, 0);
                else if (slot == 15) openMobHeadCategory(player, 0);
            } else if (title.contains("Kategorie:")) {
                String currentCat = title.replace(ChatColor.DARK_GREEN + "Kategorie: ", "");
                int catIndex = categoryNames.indexOf(currentCat);
                if (slot == 53 && catIndex < categoryNames.size() - 1) {
                    openMobHeadCategory(player, catIndex + 1);
                } else if (slot == 45 && catIndex > 0) {
                    openMobHeadCategory(player, catIndex - 1);
                }
            }
        }
    }
}
