package org.FreeZeeZon.maplockplus;

import org.bstats.bukkit.Metrics;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public class MapLockPlus extends JavaPlugin implements Listener, TabCompleter {

    private static final NamespacedKey LOCKED_MAP_KEY = new NamespacedKey("maplock", "locked");
    private static final NamespacedKey OWNER_KEY = new NamespacedKey("maplock", "owner");
    private static final NamespacedKey ANONYMOUS_KEY = new NamespacedKey("maplock", "anonymous");

    // Messages from config
    private String msgPlayersOnly;
    private String msgMustHoldMap;
    private String msgNotOwner;
    private String msgUnlocked;
    private String msgLocked;
    private String msgOnlyYouUnlock;
    private String msgCannotCopy;
    private String msgConfigReloaded;
    private String msgNoPermission;
    private String msgInventoryFull;

    // Lore
    private List<String> lockedMapLore;
    private List<String> anonymousMapLore;
    private boolean showOwnerInLore;
    private String ownerFormat;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadMessages();
        getServer().getPluginManager().registerEvents(this, this);

        // Register TabCompleter
        if (getCommand("maplock") != null) {
            Objects.requireNonNull(getCommand("maplock")).setTabCompleter(this);
        }

        getLogger().info("MapLockPlus enabled!");
        new Metrics(this, 28376);
    }

    @Override
    public void onDisable() {
        getLogger().info("MapLockPlus disabled!");
    }

    private void loadMessages() {
        reloadConfig();
        msgPlayersOnly = colorize(getConfig().getString("messages.players-only", "&cThis command can only be used by players!"));
        msgMustHoldMap = colorize(getConfig().getString("messages.must-hold-map", "&cYou must hold a filled map to use this command!"));
        msgNotOwner = colorize(getConfig().getString("messages.not-owner", "&cYou are not the owner of this map!"));
        msgUnlocked = colorize(getConfig().getString("messages.unlocked", "&aMap unlocked! It can now be copied."));
        msgLocked = colorize(getConfig().getString("messages.locked", "&aMap locked! It can no longer be copied."));
        msgOnlyYouUnlock = colorize(getConfig().getString("messages.only-you-unlock", "&7Only you can unlock this map."));
        msgCannotCopy = colorize(getConfig().getString("messages.cannot-copy", "&cYou cannot copy a locked map!"));
        msgConfigReloaded = colorize(getConfig().getString("messages.config-reloaded", "&aConfig successfully reloaded!"));
        msgNoPermission = colorize(getConfig().getString("messages.no-permission", "&cYou don't have permission!"));
        msgInventoryFull = colorize(getConfig().getString("messages.inventory-full", "&eYour inventory is full! The map was dropped on the ground."));

        showOwnerInLore = getConfig().getBoolean("lore.show-owner", true);
        ownerFormat = colorize(getConfig().getString("lore.owner-format", "&7Owner: &f%owner%"));

        lockedMapLore = new ArrayList<>();
        getConfig().getStringList("lore.locked-map").forEach(line -> lockedMapLore.add(colorize(line)));

        anonymousMapLore = new ArrayList<>();
        getConfig().getStringList("lore.anonymous-map").forEach(line -> anonymousMapLore.add(colorize(line)));
    }

    private String colorize(String message) {
        return message == null ? "" : ChatColor.translateAlternateColorCodes('&', message);
    }

    // ============ TAB COMPLETER ============

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("maplock")) return Collections.emptyList();

        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            String input = args[0].toLowerCase();

            if ("anon".startsWith(input) || "anonymous".startsWith(input)) completions.add("anon");
            if (sender.hasPermission("maplock.reload") && "reload".startsWith(input)) completions.add("reload");

            return completions;
        }

        // Return an empty list to prevent player name suggestions
        return Collections.emptyList();
    }

    // ============ COMMAND HANDLER ============

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        // Reload command
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("maplock.reload")) {
                sender.sendMessage(msgNoPermission);
                return true;
            }
            loadMessages();
            sender.sendMessage(msgConfigReloaded);
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(msgPlayersOnly);
            return true;
        }

        if (!player.hasPermission("maplock.use")) {
            player.sendMessage(msgNoPermission);
            return true;
        }

        ItemStack itemInHand = player.getInventory().getItemInMainHand();
        if (itemInHand.getType() != Material.FILLED_MAP) {
            player.sendMessage(msgMustHoldMap);
            return true;
        }

        boolean anonymous = args.length > 0 && (args[0].equalsIgnoreCase("anon") || args[0].equalsIgnoreCase("anonymous"));

        ItemStack modifiedItem = itemInHand.clone();
        modifiedItem.setAmount(1);

        ItemMeta meta = modifiedItem.getItemMeta();
        if (meta == null) return true;

        PersistentDataContainer container = meta.getPersistentDataContainer();
        String playerUUID = player.getUniqueId().toString();

        if (container.has(LOCKED_MAP_KEY, PersistentDataType.BYTE)) {
            // UNLOCK
            String ownerUUID = container.get(OWNER_KEY, PersistentDataType.STRING);

            if (ownerUUID != null && !ownerUUID.equals(playerUUID) && !player.hasPermission("maplock.admin")) {
                player.sendMessage(msgNotOwner);
                return true;
            }

            container.remove(LOCKED_MAP_KEY);
            container.remove(OWNER_KEY);
            container.remove(ANONYMOUS_KEY);

            meta.setLore(null);

            player.sendMessage(msgUnlocked);
        } else {
            // LOCK
            container.set(LOCKED_MAP_KEY, PersistentDataType.BYTE, (byte) 1);
            container.set(OWNER_KEY, PersistentDataType.STRING, playerUUID);

            List<String> lore;

            if (anonymous) {
                container.set(ANONYMOUS_KEY, PersistentDataType.BYTE, (byte) 1);
                lore = new ArrayList<>(anonymousMapLore);
            } else {
                lore = new ArrayList<>(lockedMapLore);

                if (showOwnerInLore) {
                    lore.add(ownerFormat.replace("%owner%", player.getName()));
                }
            }

            meta.setLore(lore);
            player.sendMessage(msgLocked);
            if (!anonymous) player.sendMessage(msgOnlyYouUnlock);
        }

        modifiedItem.setItemMeta(meta);

        itemInHand.setAmount(itemInHand.getAmount() - 1);

        HashMap<Integer, ItemStack> leftovers = player.getInventory().addItem(modifiedItem);
        if (!leftovers.isEmpty()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftovers.get(0));
            player.sendMessage(msgInventoryFull);
        }

        return true;
    }

    // ============ EVENT HANDLERS ============

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        for (ItemStack item : event.getInventory().getMatrix()) {
            if (isMapLocked(item)) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryMoveItem(InventoryMoveItemEvent event) {
        // Якщо предмет намагаються засунути В Crafter
        if (event.getDestination().getType() == InventoryType.CRAFTER) {
            if (isMapLocked(event.getItem())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        InventoryType type = event.getView().getType();

        boolean isProtectedInventory = type == InventoryType.CARTOGRAPHY ||
                type == InventoryType.CRAFTER ||
                type == InventoryType.WORKBENCH ||
                type == InventoryType.GRINDSTONE;

        if (!isProtectedInventory) return;

        boolean clickedTopInventory = event.getClickedInventory() != null && event.getClickedInventory().getType() != InventoryType.PLAYER;

        if (event.isShiftClick() && !clickedTopInventory) {
            if (isMapLocked(event.getCurrentItem())) {
                event.setCancelled(true);
                sendBlockMessage(event.getWhoClicked());
                return;
            }
        }

        if (clickedTopInventory) {
            if (isMapLocked(event.getCursor())) {
                event.setCancelled(true);
                sendBlockMessage(event.getWhoClicked());
                return;
            }

            if (event.getClick().isKeyboardClick()) {
                int hotbarSlot = event.getHotbarButton();
                if (hotbarSlot >= 0) {
                    ItemStack itemInHotbar = event.getWhoClicked().getInventory().getItem(hotbarSlot);
                    if (isMapLocked(itemInHotbar)) {
                        event.setCancelled(true);
                        sendBlockMessage(event.getWhoClicked());
                    }
                }
            }
        }
    }

    private boolean isMapLocked(ItemStack item) {
        if (item == null || item.getType() != Material.FILLED_MAP) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        return meta.getPersistentDataContainer().has(LOCKED_MAP_KEY, PersistentDataType.BYTE);
    }

    private void sendBlockMessage(CommandSender sender) {
        if (sender instanceof Player player) {
            player.sendMessage(msgCannotCopy);
        }
    }
}