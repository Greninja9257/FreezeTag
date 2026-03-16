package com.freezetag.gui;

import com.freezetag.FreezeTagPlugin;
import com.freezetag.classes.PlayerClass;
import com.freezetag.game.FreezeTagGame;
import com.freezetag.game.GamePlayer;
import com.freezetag.game.RolePreference;
import com.freezetag.manager.GameManager;
import com.freezetag.util.MessageUtil;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Manages lobby items placed in the player's hotbar, plus chest-GUI submenus
 * for class and role selection.
 *
 * Hotbar layout:
 *   Slot 0 — Class Selection   (ENCHANTED_BOOK)
 *   Slot 2 — Role Preference   (colored glass)
 *   Slot 4 — Arena Info        (MAP)
 *   Slot 8 — Leave Queue       (BARRIER)
 *
 * Right-clicking a hotbar item either opens a chest submenu or performs an action.
 * Class / Role submenus remain as chest GUIs.
 */
public class LobbyGUI {

    // PDC key stored on each lobby item so we can identify them reliably
    private static final String PDC_KEY = "lobby_action";

    // Chest GUI titles
    private static final String CLASS_TITLE = MessageUtil.colorize("&b&lSelect a Class");
    private static final String ROLE_TITLE  = MessageUtil.colorize("&b&lSelect Your Role");

    /** Which chest submenu each player currently has open: "class" or "role" */
    private final Map<UUID, String> openMenus = new HashMap<>();

    private final FreezeTagPlugin plugin;
    private final NamespacedKey actionKey;

    public LobbyGUI(FreezeTagPlugin plugin) {
        this.plugin = plugin;
        this.actionKey = new NamespacedKey(plugin, PDC_KEY);
    }

    // -------------------------------------------------------------------------
    // Hotbar item management
    // -------------------------------------------------------------------------

    /**
     * Give the lobby hotbar items to a player. Saves the four hotbar slots
     * so they can be restored later.
     */
    public void giveItems(Player player, FreezeTagGame game) {
        if (player == null || game == null) return;

        GameManager.QueueEntry qEntry = plugin.getGameManager().getPlayerQueue(player.getUniqueId());
        RolePreference pref = qEntry != null ? qEntry.preference : RolePreference.NONE;

        // Slot 0 — Class Selection
        PlayerClass currentClass = null;
        GamePlayer gp = game.getGamePlayer(player.getUniqueId());
        if (gp != null) currentClass = gp.getPlayerClass();

        String classDisplay = currentClass != null
                ? MessageUtil.colorize(currentClass.getDisplayName()) : "&7None selected";
        player.getInventory().setItem(0, buildItem(
                currentClass != null ? currentClass.getDisplayItem() : Material.ENCHANTED_BOOK,
                "&b&lClass Selection",
                List.of(
                        MessageUtil.colorize("&7Selected: " + classDisplay),
                        "",
                        MessageUtil.colorize("&eRight-click to choose a class!")
                ),
                "class"
        ));

        // Slot 2 — Role Preference
        Material roleMat;
        String roleDisplay;
        switch (pref) {
            case RUNNER -> { roleMat = Material.LIME_STAINED_GLASS;  roleDisplay = "&aRunner"; }
            case TAGGER -> { roleMat = Material.RED_STAINED_GLASS;   roleDisplay = "&cTagger"; }
            default     -> { roleMat = Material.WHITE_STAINED_GLASS; roleDisplay = "&7No Preference"; }
        }
        player.getInventory().setItem(2, buildItem(
                roleMat,
                "&b&lRole Preference",
                List.of(
                        MessageUtil.colorize("&7Preference: " + MessageUtil.colorize(roleDisplay)),
                        "",
                        MessageUtil.colorize("&eRight-click to change!")
                ),
                "role"
        ));

        // Slot 4 — Arena Info
        int minP = game.getArena().getMinPlayers() > 0 ? game.getArena().getMinPlayers()
                : plugin.getConfig().getInt("game.min-players", 4);
        int dur = game.getArena().getDuration() > 0
                ? game.getArena().getDuration()
                : plugin.getConfig().getInt("game.duration", 180);
        player.getInventory().setItem(4, buildItem(
                Material.MAP,
                "&b&lArena Info",
                List.of(
                        MessageUtil.colorize("&7Arena: &f" + MessageUtil.colorize(game.getArena().getDisplayName())),
                        MessageUtil.colorize("&7Players: &f" + game.getPlayerCount() + "/" + game.getArena().getMaxPlayers()),
                        MessageUtil.colorize("&7Min to start: &f" + minP),
                        MessageUtil.colorize("&7Duration: &f" + MessageUtil.formatTime(dur))
                ),
                "info"
        ));

        // Slot 8 — Leave Queue
        player.getInventory().setItem(8, buildItem(
                Material.BARRIER,
                "&c&lLeave Queue",
                List.of(MessageUtil.colorize("&cRight-click to leave the queue.")),
                "leave"
        ));

        // Move to slot 0 so they see the items right away
        player.getInventory().setHeldItemSlot(0);
    }

    /**
     * Remove all lobby hotbar items from the player's inventory.
     */
    public void clearItems(Player player) {
        if (player == null) return;
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (isLobbyItem(item)) {
                player.getInventory().setItem(slot, null);
            }
        }
        openMenus.remove(player.getUniqueId());
    }

    /**
     * Returns true if the given ItemStack is a FreezeTag lobby item.
     */
    public boolean isLobbyItem(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        return meta.getPersistentDataContainer().has(actionKey, PersistentDataType.STRING);
    }

    /**
     * Handle a right-click interaction with a lobby hotbar item.
     */
    public void handleItemInteract(Player player, ItemStack item) {
        if (!isLobbyItem(item)) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        String action = meta.getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (action == null) return;

        FreezeTagGame game = plugin.getGameManager().getPlayerGame(player.getUniqueId());

        switch (action) {
            case "class" -> {
                if (plugin.getConfig().getBoolean("classes.lobby-selection", true) && game != null) {
                    openClassMenu(player, game);
                }
            }
            case "role" -> openRoleMenu(player);
            case "info"  -> { /* lore is the info — nothing extra needed */ }
            case "leave" -> {
                clearItems(player);
                plugin.getGameManager().leaveQueue(player);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Chest GUI submenus (class + role)
    // -------------------------------------------------------------------------

    public void openClassMenu(Player player, FreezeTagGame game) {
        if (player == null || game == null) return;
        Inventory inv = org.bukkit.Bukkit.createInventory(null, 54, CLASS_TITLE);
        populateClassMenu(inv, player, game);
        player.openInventory(inv);
        openMenus.put(player.getUniqueId(), "class");
    }

    public void openRoleMenu(Player player) {
        if (player == null) return;
        Inventory inv = org.bukkit.Bukkit.createInventory(null, 27, ROLE_TITLE);
        populateRoleMenu(inv, player);
        player.openInventory(inv);
        openMenus.put(player.getUniqueId(), "role");
    }

    // -------------------------------------------------------------------------
    // Chest submenu population
    // -------------------------------------------------------------------------

    private void populateClassMenu(Inventory inv, Player player, FreezeTagGame game) {
        ItemStack filler = createItem(Material.GRAY_STAINED_GLASS_PANE, " ", null);
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);

        Collection<PlayerClass> classes = plugin.getClassManager().getAllClasses();

        int slot = 10;
        for (PlayerClass pc : classes) {
            if (slot >= 44) break;
            ItemStack item = pc.createDisplayItem();
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                List<String> lore = meta.getLore();
                if (lore == null) lore = new ArrayList<>();
                lore.add("");
                lore.add(MessageUtil.colorize("&eClick to select!"));
                meta.setLore(lore);
                item.setItemMeta(meta);
            }
            inv.setItem(slot, item);
            slot++;
            if ((slot - 9) % 9 == 0) slot += 2;
        }

        inv.setItem(49, createItem(Material.ARROW, "&7Back", null));
    }

    private void populateRoleMenu(Inventory inv, Player player) {
        ItemStack filler = createItem(Material.GRAY_STAINED_GLASS_PANE, " ", null);
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);

        GameManager.QueueEntry qEntry = plugin.getGameManager().getPlayerQueue(player.getUniqueId());
        RolePreference current = qEntry != null ? qEntry.preference : RolePreference.NONE;

        // Runner — slot 11
        List<String> runnerLore = new ArrayList<>(List.of(
                MessageUtil.colorize("&7Survive and rescue teammates!"),
                MessageUtil.colorize("&7Win if time runs out with survivors.")));
        if (current == RolePreference.RUNNER) runnerLore.add(MessageUtil.colorize("&a✔ Selected"));
        runnerLore.add(""); runnerLore.add(MessageUtil.colorize("&eClick to select!"));
        inv.setItem(11, createItem(
                current == RolePreference.RUNNER ? Material.LIME_CONCRETE : Material.LIME_STAINED_GLASS,
                "&a&lRunner", runnerLore));

        // No preference — slot 13
        List<String> noneLore = new ArrayList<>(List.of(
                MessageUtil.colorize("&7Let the game assign your role.")));
        if (current == RolePreference.NONE) noneLore.add(MessageUtil.colorize("&a✔ Selected"));
        noneLore.add(""); noneLore.add(MessageUtil.colorize("&eClick to select!"));
        inv.setItem(13, createItem(
                current == RolePreference.NONE ? Material.WHITE_CONCRETE : Material.WHITE_STAINED_GLASS,
                "&7&lNo Preference", noneLore));

        // Tagger — slot 15
        List<String> taggerLore = new ArrayList<>(List.of(
                MessageUtil.colorize("&7Hunt and freeze every runner!"),
                MessageUtil.colorize("&7Win by freezing all runners.")));
        if (current == RolePreference.TAGGER) taggerLore.add(MessageUtil.colorize("&a✔ Selected"));
        taggerLore.add(""); taggerLore.add(MessageUtil.colorize("&eClick to select!"));
        inv.setItem(15, createItem(
                current == RolePreference.TAGGER ? Material.RED_CONCRETE : Material.RED_STAINED_GLASS,
                "&c&lTagger", taggerLore));

        inv.setItem(22, createItem(Material.ARROW, "&7Back", null));
    }

    // -------------------------------------------------------------------------
    // Chest submenu click handling
    // -------------------------------------------------------------------------

    /**
     * Handle a click inside a FreezeTag chest GUI submenu.
     * Returns true if the click was consumed.
     */
    public boolean handleClick(Player player, String inventoryTitle, int slot) {
        String menuType = openMenus.get(player.getUniqueId());
        if (menuType == null) return false;
        FreezeTagGame game = plugin.getGameManager().getPlayerGame(player.getUniqueId());

        return switch (menuType) {
            case "class" -> handleClassMenuClick(player, game, slot);
            case "role"  -> handleRoleMenuClick(player, slot);
            default      -> false;
        };
    }

    private boolean handleClassMenuClick(Player player, FreezeTagGame game, int slot) {
        if (slot == 49) { // Back
            player.closeInventory();
            return true;
        }

        if (game == null) { player.closeInventory(); return true; }

        Inventory inv = player.getOpenInventory().getTopInventory();
        ItemStack clicked = inv.getItem(slot);
        if (clicked == null || clicked.getType() == Material.GRAY_STAINED_GLASS_PANE
                || clicked.getType() == Material.AIR) return true;

        Collection<PlayerClass> classes = plugin.getClassManager().getAllClasses();

        if (clicked.getItemMeta() != null) {
            String clickedName = clicked.getItemMeta().getDisplayName();
            for (PlayerClass pc : classes) {
                if (MessageUtil.colorize(pc.getDisplayName()).equals(clickedName)) {
                    int maxCount = pc.getMaxCount();
                    if (maxCount > 0) {
                        int currentCount = countPlayersWithClass(game, pc.getId());
                        if (currentCount >= maxCount) {
                            MessageUtil.sendMessage(player, "class.max-reached");
                            return true;
                        }
                    }
                    GamePlayer gp = game.getGamePlayer(player.getUniqueId());
                    if (gp != null) {
                        gp.setPlayerClass(pc);
                        MessageUtil.sendMessage(player, "class.selected",
                                Map.of("class", MessageUtil.colorize(pc.getDisplayName())));
                    }
                    player.closeInventory();
                    // Refresh the hotbar item to reflect new class
                    giveItems(player, game);
                    return true;
                }
            }
        }
        return true;
    }

    private boolean handleRoleMenuClick(Player player, int slot) {
        switch (slot) {
            case 11 -> {
                plugin.getGameManager().setRolePreference(player, RolePreference.RUNNER);
                MessageUtil.sendMessage(player, "role.preference-set-runner");
                openRoleMenu(player); // refresh
            }
            case 13 -> {
                plugin.getGameManager().setRolePreference(player, RolePreference.NONE);
                MessageUtil.sendMessage(player, "role.preference-cleared");
                openRoleMenu(player);
            }
            case 15 -> {
                plugin.getGameManager().setRolePreference(player, RolePreference.TAGGER);
                MessageUtil.sendMessage(player, "role.preference-set-tagger");
                openRoleMenu(player);
            }
            case 22 -> player.closeInventory(); // Back
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    public boolean isFreezeTagGUI(String title) {
        return title.equals(CLASS_TITLE) || title.equals(ROLE_TITLE);
    }

    public void onInventoryClose(Player player) {
        openMenus.remove(player.getUniqueId());
    }

    public void removeOpenMenu(UUID uuid) {
        openMenus.remove(uuid);
    }

    public String getOpenMenu(UUID uuid) {
        return openMenus.get(uuid);
    }

    private int countPlayersWithClass(FreezeTagGame game, String classId) {
        int count = 0;
        for (GamePlayer gp : game.getGamePlayers().values()) {
            if (gp.getPlayerClass() != null
                    && gp.getPlayerClass().getId().equalsIgnoreCase(classId)) count++;
        }
        return count;
    }

    /** Build a lobby hotbar item tagged with the given action via PDC. */
    private ItemStack buildItem(Material mat, String name, List<String> lore, String action) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(MessageUtil.colorize(name));
            if (lore != null) meta.setLore(lore);
            meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createItem(Material mat, String name, List<String> lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(MessageUtil.colorize(name));
            if (lore != null) meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }
}
