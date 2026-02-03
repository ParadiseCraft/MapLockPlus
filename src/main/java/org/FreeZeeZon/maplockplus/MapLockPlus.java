package org.FreeZeeZon.maplockplus;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bstats.bukkit.Metrics;
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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NonNull;

import java.util.*;

public class MapLockPlus extends JavaPlugin implements Listener, TabCompleter {

    private static final NamespacedKey LOCKED_MAP_KEY = new NamespacedKey("maplock", "locked");
    private static final NamespacedKey OWNER_KEY = new NamespacedKey("maplock", "owner");
    private static final NamespacedKey ANONYMOUS_KEY = new NamespacedKey("maplock", "anonymous");

    // Adventure Components
    private Component msgPlayersOnly;
    private Component msgMustHoldMap;
    private Component msgNotOwner;
    private Component msgUnlocked;
    private Component msgLocked;
    private Component msgAnonLocked;
    private Component msgOnlyYouUnlock;
    private Component msgAlreadyLocked;
    private Component msgNotLocked;
    private Component msgCannotCopy;
    private Component msgConfigReloaded;
    private Component msgNoPermission;

    // Lore settings
    private List<Component> lockedMapLore;
    private List<Component> anonymousMapLore;
    private boolean showOwnerInLore;
    private String ownerFormatRaw;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadMessages();
        getServer().getPluginManager().registerEvents(this, this);

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

        // Use Legacy serializer to support '&' from config
        LegacyComponentSerializer serializer = LegacyComponentSerializer.legacyAmpersand();

        msgPlayersOnly = serializer.deserialize(getConfig().getString("messages.players-only", "&cThis command can only be used by players!"));
        msgMustHoldMap = serializer.deserialize(getConfig().getString("messages.must-hold-map", "&cYou must hold a filled map!"));
        msgNotOwner = serializer.deserialize(getConfig().getString("messages.not-owner", "&cYou are not the owner of this map! Only the owner can unlock it."));
        msgUnlocked = serializer.deserialize(getConfig().getString("messages.unlocked", "&aMap unlocked! It can now be copied."));
        msgLocked = serializer.deserialize(getConfig().getString("messages.locked", "&aMap locked! It can no longer be copied."));
        msgOnlyYouUnlock = serializer.deserialize(getConfig().getString("messages.only-you-unlock", "&7Only you can unlock this map."));
        msgAnonLocked = serializer.deserialize(getConfig().getString("messages.anon-locked", "&aMap anonymously locked! It can no longer be copied."));
        msgAlreadyLocked = serializer.deserialize(getConfig().getString("messages.already-locked", "&cThis map is already locked! Use /maplock unlock"));
        msgNotLocked = serializer.deserialize(getConfig().getString("messages.not-locked", "&cThis map is not locked."));

        msgCannotCopy = serializer.deserialize(getConfig().getString("messages.cannot-copy", "&cYou cannot copy a locked map!"));
        msgConfigReloaded = serializer.deserialize(getConfig().getString("messages.config-reloaded", "&aConfig successfully reloaded!"));
        msgNoPermission = serializer.deserialize(getConfig().getString("messages.no-permission", "&cYou don't have permission!"));

        showOwnerInLore = getConfig().getBoolean("lore.show-owner", true);
        ownerFormatRaw = getConfig().getString("lore.owner-format", "&7Owner: &f%owner%");

        lockedMapLore = new ArrayList<>();
        getConfig().getStringList("lore.locked-map").forEach(line ->
                lockedMapLore.add(serializer.deserialize(line).decoration(TextDecoration.ITALIC, false)));

        anonymousMapLore = new ArrayList<>();
        getConfig().getStringList("lore.anonymous-map").forEach(line ->
                anonymousMapLore.add(serializer.deserialize(line).decoration(TextDecoration.ITALIC, false)));
    }

    // ============ TAB COMPLETER ============

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String @NonNull [] args) {
        if (!command.getName().equalsIgnoreCase("maplock")) return Collections.emptyList();

        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            String input = args[0].toLowerCase();

            List<String> options = new ArrayList<>(Arrays.asList("lock", "unlock", "anon", "anonymous"));
            if (sender.hasPermission("maplock.reload")) options.add("reload");

            for (String option : options) {
                if (option.startsWith(input)) completions.add(option);
            }
            return completions;
        }

        // Return an empty list to prevent player name suggestions
        return Collections.emptyList();
    }

    // ============ COMMAND HANDLER ============

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, @NotNull String @NonNull [] args) {
        // --- RELOAD ---
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

        // --- DETERMINE ACTION ---
        String inputArg = args.length > 0 ? args[0].toLowerCase() : "lock";
        String action = switch (inputArg) {
            case "unlock" -> "unlock";
            case "anon", "anonymous", "anonim" -> "anon";
            default -> "lock";
        };

        // --- FIND MAP (MainHand -> OffHand) ---
        ItemStack itemToCheck = player.getInventory().getItemInMainHand();

        if (itemToCheck.getType() != Material.FILLED_MAP) {
            itemToCheck = player.getInventory().getItemInOffHand();

            if (itemToCheck.getType() != Material.FILLED_MAP) {
                player.sendMessage(msgMustHoldMap);
                return true;
            }
        }

        ItemMeta meta = itemToCheck.getItemMeta();
        if (meta == null) return true;

        PersistentDataContainer container = meta.getPersistentDataContainer();
        String playerUUID = player.getUniqueId().toString();
        boolean isLocked = container.has(LOCKED_MAP_KEY, PersistentDataType.BYTE);

        // --- UNLOCK LOGIC ---
        if (action.equals("unlock")) {
            if (!isLocked) {
                player.sendMessage(msgNotLocked);
                return true;
            }

            String ownerUUID = container.get(OWNER_KEY, PersistentDataType.STRING);

            if (ownerUUID != null && !ownerUUID.equals(playerUUID) && !player.hasPermission("maplock.admin")) {
                player.sendMessage(msgNotOwner);
                return true;
            }

            // Remove protection tags
            container.remove(LOCKED_MAP_KEY);
            container.remove(OWNER_KEY);
            container.remove(ANONYMOUS_KEY);

            // Clear Lore
            meta.lore(null);

            itemToCheck.setItemMeta(meta); // Update item
            player.sendMessage(msgUnlocked);
            return true;
        }

        // --- LOCK LOGIC (Normal / Anon) ---
        if (isLocked) {
            player.sendMessage(msgAlreadyLocked);
            return true;
        }

        boolean isAnon = action.equals("anon");

        container.set(LOCKED_MAP_KEY, PersistentDataType.BYTE, (byte) 1);
        container.set(OWNER_KEY, PersistentDataType.STRING, playerUUID);

        List<Component> lore = new ArrayList<>();

        if (isAnon) {
            container.set(ANONYMOUS_KEY, PersistentDataType.BYTE, (byte) 1);
            lore.addAll(anonymousMapLore);
        } else {
            lore.addAll(lockedMapLore);
            if (showOwnerInLore) {
                String ownerLine = ownerFormatRaw.replace("%owner%", player.getName());
                lore.add(LegacyComponentSerializer.legacyAmpersand().deserialize(ownerLine).decoration(TextDecoration.ITALIC, false));
            }
        }

        meta.lore(lore);
        itemToCheck.setItemMeta(meta);

        player.sendMessage(isAnon ? msgAnonLocked : msgLocked);
        if (!isAnon) {
            player.sendMessage(msgOnlyYouUnlock);
        }
        return true;

    }

    // ============ EVENT HANDLERS ============

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        for (ItemStack item : event.getInventory().getMatrix()) {
            if (isMapLocked(item)) {
                event.getInventory().setResult(null); // Block craft result
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryMoveItem(InventoryMoveItemEvent event) {
        if (event.getDestination().getType() == InventoryType.CRAFTER) {
            if (isMapLocked(event.getItem())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        InventoryType type = event.getView().getType();

        // Protect inventories where copying/modification is possible
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

    private boolean isMapLocked(@Nullable ItemStack item) {
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