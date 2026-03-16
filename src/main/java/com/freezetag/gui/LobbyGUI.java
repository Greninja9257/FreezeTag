package com.freezetag.gui;

import com.freezetag.FreezeTagPlugin;
import com.freezetag.arena.Arena;
import com.freezetag.classes.PlayerClass;
import com.freezetag.game.FreezeTagGame;
import com.freezetag.game.GamePlayer;
import com.freezetag.game.RolePreference;
import com.freezetag.game.VoteLobby;
import com.freezetag.manager.GameManager;
import com.freezetag.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Manages lobby items placed in the player's hotbar, plus chest-GUI submenus
 * for class, role, arena info, and map voting.
 *
 * Hotbar layout:
 *   Slot 0 — Class Selection   (ENCHANTED_BOOK)
 *   Slot 2 — Role Preference   (colored glass pane)
 *   Slot 4 — Arena Info (MAP) OR Vote for Map (rainbow glass, if arena-voting enabled)
 *   Slot 8 — Leave Queue       (BARRIER)
 */
public class LobbyGUI {

    private static final String PDC_KEY = "lobby_action";

    private static final String CLASS_TITLE = MessageUtil.colorize("&b&lSelect a Class");
    private static final String ROLE_TITLE  = MessageUtil.colorize("&b&lSelect Your Role");
    private static final String INFO_TITLE  = MessageUtil.colorize("&b&lArena Info");
    private static final String VOTE_TITLE  = MessageUtil.colorize("&d&lVote for Map");

    private static final Material[] RAINBOW = {
        Material.RED_STAINED_GLASS,
        Material.ORANGE_STAINED_GLASS,
        Material.YELLOW_STAINED_GLASS,
        Material.LIME_STAINED_GLASS,
        Material.CYAN_STAINED_GLASS,
        Material.BLUE_STAINED_GLASS,
        Material.PURPLE_STAINED_GLASS,
        Material.MAGENTA_STAINED_GLASS
    };

    private int rainbowIndex = 0;
    private BukkitTask rainbowTask;

    /** Which chest submenu each player currently has open */
    private final Map<UUID, String> openMenus = new HashMap<>();

    private final FreezeTagPlugin plugin;
    private final NamespacedKey actionKey;

    public LobbyGUI(FreezeTagPlugin plugin) {
        this.plugin = plugin;
        this.actionKey = new NamespacedKey(plugin, PDC_KEY);
    }

    // -------------------------------------------------------------------------
    // Rainbow task
    // -------------------------------------------------------------------------

    /** Start cycling the vote item color for all players that have it. */
    public void startRainbowTask() {
        if (rainbowTask != null) return;
        rainbowTask = new org.bukkit.scheduler.BukkitRunnable() {
            @Override
            public void run() {
                rainbowIndex = (rainbowIndex + 1) % RAINBOW.length;
                for (Player p : Bukkit.getOnlinePlayers()) {
                    ItemStack slot4 = p.getInventory().getItem(4);
                    if (!isLobbyItem(slot4)) continue;
                    ItemMeta meta = slot4.getItemMeta();
                    if (meta == null) continue;
                    String action = meta.getPersistentDataContainer()
                            .get(actionKey, PersistentDataType.STRING);
                    if (!"vote".equals(action)) continue;
                    FreezeTagGame game = plugin.getGameManager().getPlayerGame(p.getUniqueId());
                    if (game == null) continue;
                    p.getInventory().setItem(4, buildVoteItem(p, game));
                }
            }
        }.runTaskTimer(plugin, 0L, 10L);
    }

    public void stopRainbowTask() {
        if (rainbowTask != null) {
            rainbowTask.cancel();
            rainbowTask = null;
        }
    }

    // -------------------------------------------------------------------------
    // Hotbar item management
    // -------------------------------------------------------------------------

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
            case RUNNER -> { roleMat = Material.LIME_STAINED_GLASS_PANE;  roleDisplay = "&aRunner"; }
            case TAGGER -> { roleMat = Material.RED_STAINED_GLASS_PANE;   roleDisplay = "&cTagger"; }
            default     -> { roleMat = Material.WHITE_STAINED_GLASS_PANE; roleDisplay = "&7No Preference"; }
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
        player.getInventory().setItem(4, buildInfoItem(game));

        // Slot 8 — Leave Queue
        player.getInventory().setItem(8, buildItem(
                Material.BARRIER,
                "&c&lLeave Queue",
                List.of(MessageUtil.colorize("&cRight-click to leave the queue.")),
                "leave"
        ));

        // Only switch to slot 0 if the player doesn't already have lobby items
        // (i.e. first time giving items, not a refresh)
        if (player.getInventory().getHeldItemSlot() > 4
                || !isLobbyItem(player.getInventory().getItem(player.getInventory().getHeldItemSlot()))) {
            player.getInventory().setHeldItemSlot(0);
        }
    }

    private ItemStack buildVoteItem(Player player, FreezeTagGame game) {
        Material mat = RAINBOW[rainbowIndex];
        String myVote = game.getPlayerVote(player.getUniqueId());
        Map<String, Integer> votes = game.getVoteCounts();

        List<String> lore = new ArrayList<>();
        lore.add("");
        if (myVote != null) {
            Arena votedArena = plugin.getArenaManager().getArena(myVote);
            String name = votedArena != null ? votedArena.getDisplayName() : myVote;
            lore.add(MessageUtil.colorize("&7Your vote: &f" + name));
        } else {
            lore.add(MessageUtil.colorize("&7Your vote: &eNone yet"));
        }
        if (!votes.isEmpty()) {
            String topKey = votes.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey).orElse(null);
            if (topKey != null) {
                Arena top = plugin.getArenaManager().getArena(topKey);
                String topName = top != null ? top.getDisplayName() : topKey;
                lore.add(MessageUtil.colorize("&7Leading: &f" + topName
                        + " &7(" + votes.get(topKey) + " vote"
                        + (votes.get(topKey) == 1 ? "" : "s") + ")"));
            }
        } else {
            lore.add(MessageUtil.colorize("&7No votes cast yet."));
        }
        lore.add("");
        lore.add(MessageUtil.colorize("&eRight-click to vote!"));

        return buildItem(mat, "&d&lVote for Map", lore, "vote");
    }

    private ItemStack buildInfoItem(FreezeTagGame game) {
        int minP = game.getArena().getMinPlayers() > 0 ? game.getArena().getMinPlayers()
                : plugin.getConfig().getInt("game.min-players", 4);
        int dur = game.getArena().getDuration() > 0
                ? game.getArena().getDuration()
                : plugin.getConfig().getInt("game.duration", 180);
        return buildItem(
                Material.MAP,
                "&b&lArena Info",
                List.of(
                        MessageUtil.colorize("&7Arena: &f" + MessageUtil.colorize(game.getArena().getDisplayName())),
                        MessageUtil.colorize("&7Players: &f" + game.getPlayerCount() + "/" + game.getArena().getMaxPlayers()),
                        MessageUtil.colorize("&7Min to start: &f" + minP),
                        MessageUtil.colorize("&7Duration: &f" + MessageUtil.formatTime(dur)),
                        "",
                        MessageUtil.colorize("&eRight-click for more info!")
                ),
                "info"
        );
    }

    // -------------------------------------------------------------------------
    // Vote lobby hotbar items
    // -------------------------------------------------------------------------

    /** Give hotbar items to a player in the vote lobby (rainbow vote item at slot 4). */
    public void giveVoteLobbyItems(Player player, VoteLobby voteLobby) {
        if (player == null || voteLobby == null) return;

        // Slot 0 — Class Selection
        String classId = voteLobby.getPlayerClassId(player.getUniqueId());
        PlayerClass currentClass = classId != null ? plugin.getClassManager().getClass(classId) : null;
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
        RolePreference pref = voteLobby.getRolePreference(player.getUniqueId());
        Material roleMat;
        String roleDisplay;
        switch (pref) {
            case RUNNER -> { roleMat = Material.LIME_STAINED_GLASS_PANE;  roleDisplay = "&aRunner"; }
            case TAGGER -> { roleMat = Material.RED_STAINED_GLASS_PANE;   roleDisplay = "&cTagger"; }
            default     -> { roleMat = Material.WHITE_STAINED_GLASS_PANE; roleDisplay = "&7No Preference"; }
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

        // Slot 4 — Vote for Map (rainbow glass)
        player.getInventory().setItem(4, buildVoteItemFromVoteLobby(player, voteLobby));

        // Slot 8 — Leave
        player.getInventory().setItem(8, buildItem(
                Material.BARRIER,
                "&c&lLeave Lobby",
                List.of(MessageUtil.colorize("&cRight-click to leave the vote lobby.")),
                "leave_vl"
        ));

        if (player.getInventory().getHeldItemSlot() > 4
                || !isLobbyItem(player.getInventory().getItem(player.getInventory().getHeldItemSlot()))) {
            player.getInventory().setHeldItemSlot(0);
        }
    }

    private ItemStack buildVoteItemFromVoteLobby(Player player, VoteLobby voteLobby) {
        Material mat = RAINBOW[rainbowIndex];
        String myVote = voteLobby.getPlayerVote(player.getUniqueId());
        Map<String, Integer> votes = voteLobby.getVoteCounts();

        List<String> lore = new ArrayList<>();
        lore.add("");
        if (myVote != null) {
            Arena a = plugin.getArenaManager().getArena(myVote);
            lore.add(MessageUtil.colorize("&7Your vote: &f" + (a != null ? a.getDisplayName() : myVote)));
        } else {
            lore.add(MessageUtil.colorize("&7Your vote: &eNone yet"));
        }
        if (!votes.isEmpty()) {
            String topKey = votes.entrySet().stream()
                    .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
            if (topKey != null) {
                Arena top = plugin.getArenaManager().getArena(topKey);
                int cnt = votes.get(topKey);
                lore.add(MessageUtil.colorize("&7Leading: &f" + (top != null ? top.getDisplayName() : topKey)
                        + " &7(" + cnt + " vote" + (cnt == 1 ? "" : "s") + ")"));
            }
        } else {
            lore.add(MessageUtil.colorize("&7No votes cast yet."));
        }
        lore.add("");
        lore.add(MessageUtil.colorize("&eRight-click to vote!"));
        return buildItem(mat, "&d&lVote for Map", lore, "vote");
    }

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

    public boolean isLobbyItem(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        return meta.getPersistentDataContainer().has(actionKey, PersistentDataType.STRING);
    }

    public void handleItemInteract(Player player, ItemStack item) {
        if (!isLobbyItem(item)) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        String action = meta.getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (action == null) return;

        FreezeTagGame game = plugin.getGameManager().getPlayerGame(player.getUniqueId());
        VoteLobby vl = plugin.getVoteLobby();
        boolean inVL = vl != null && vl.isInLobby(player.getUniqueId());

        switch (action) {
            case "class" -> {
                if (!plugin.getConfig().getBoolean("classes.lobby-selection", true)) break;
                if (game != null)  openClassMenu(player, game);
                else if (inVL)     openVoteLobbyClassMenu(player);
            }
            case "role"     -> openRoleMenu(player);
            case "info"     -> { if (game != null) openInfoMenu(player, game); }
            case "vote"     -> { if (inVL) openVoteMenuVL(player, vl); }
            case "leave"    -> {
                clearItems(player);
                plugin.getGameManager().leaveQueue(player);
            }
            case "leave_vl" -> {
                if (inVL) vl.removePlayer(player);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Chest GUI submenus
    // -------------------------------------------------------------------------

    public void openClassMenu(Player player, FreezeTagGame game) {
        Inventory inv = Bukkit.createInventory(null, 54, CLASS_TITLE);
        populateClassMenu(inv, player, game);
        player.openInventory(inv);
        openMenus.put(player.getUniqueId(), "class");
    }

    public void openRoleMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, ROLE_TITLE);
        populateRoleMenu(inv, player);
        player.openInventory(inv);
        openMenus.put(player.getUniqueId(), "role");
    }

    public void openInfoMenu(Player player, FreezeTagGame game) {
        Inventory inv = Bukkit.createInventory(null, 27, INFO_TITLE);
        populateInfoMenu(inv, game);
        player.openInventory(inv);
        openMenus.put(player.getUniqueId(), "info");
    }

    public void openVoteMenu(Player player, FreezeTagGame game) {
        Inventory inv = Bukkit.createInventory(null, 54, VOTE_TITLE);
        populateVoteMenu(inv, player, game);
        player.openInventory(inv);
        openMenus.put(player.getUniqueId(), "vote");
    }

    /** Open the class selection menu for a vote-lobby player (no game context). */
    public void openVoteLobbyClassMenu(Player player) {
        VoteLobby vl = plugin.getVoteLobby();
        if (vl == null) return;
        Inventory inv = Bukkit.createInventory(null, 54, CLASS_TITLE);
        populateClassMenuVL(inv, player, vl);
        player.openInventory(inv);
        openMenus.put(player.getUniqueId(), "class_vl");
    }

    /** Open the map vote menu for a vote-lobby player. */
    public void openVoteMenuVL(Player player, VoteLobby vl) {
        Inventory inv = Bukkit.createInventory(null, 54, VOTE_TITLE);
        populateVoteMenuVL(inv, player, vl);
        player.openInventory(inv);
        openMenus.put(player.getUniqueId(), "vote_vl");
    }

    // -------------------------------------------------------------------------
    // Menu population
    // -------------------------------------------------------------------------

    private void populateClassMenu(Inventory inv, Player player, FreezeTagGame game) {
        ItemStack filler = createItem(Material.GRAY_STAINED_GLASS_PANE, " ", null);
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);

        Collection<PlayerClass> classes = plugin.getClassManager().getAllClasses();
        int slot = 10;
        for (PlayerClass pc : classes) {
            if (slot >= 44) break;
            ItemStack classItem = pc.createDisplayItem();
            ItemMeta meta = classItem.getItemMeta();
            if (meta != null) {
                List<String> lore = meta.getLore();
                if (lore == null) lore = new ArrayList<>();
                lore.add("");
                lore.add(MessageUtil.colorize("&eClick to select!"));
                meta.setLore(lore);
                classItem.setItemMeta(meta);
            }
            inv.setItem(slot, classItem);
            slot++;
            if ((slot - 9) % 9 == 0) slot += 2;
        }
        inv.setItem(49, createItem(Material.ARROW, "&7Back", null));
    }

    private void populateRoleMenu(Inventory inv, Player player) {
        ItemStack filler = createItem(Material.GRAY_STAINED_GLASS_PANE, " ", null);
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);

        GameManager.QueueEntry qEntry = plugin.getGameManager().getPlayerQueue(player.getUniqueId());
        VoteLobby vl = plugin.getVoteLobby();
        RolePreference current;
        if (qEntry != null) {
            current = qEntry.preference;
        } else if (vl != null && vl.isInLobby(player.getUniqueId())) {
            current = vl.getRolePreference(player.getUniqueId());
        } else {
            current = RolePreference.NONE;
        }

        List<String> runnerLore = new ArrayList<>(List.of(
                MessageUtil.colorize("&7Survive and rescue teammates!"),
                MessageUtil.colorize("&7Win if time runs out with survivors.")));
        if (current == RolePreference.RUNNER) runnerLore.add(MessageUtil.colorize("&a✔ Selected"));
        runnerLore.add(""); runnerLore.add(MessageUtil.colorize("&eClick to select!"));
        inv.setItem(11, createItem(
                current == RolePreference.RUNNER ? Material.LIME_CONCRETE : Material.LIME_STAINED_GLASS,
                "&a&lRunner", runnerLore));

        List<String> noneLore = new ArrayList<>(List.of(
                MessageUtil.colorize("&7Let the game assign your role.")));
        if (current == RolePreference.NONE) noneLore.add(MessageUtil.colorize("&a✔ Selected"));
        noneLore.add(""); noneLore.add(MessageUtil.colorize("&eClick to select!"));
        inv.setItem(13, createItem(
                current == RolePreference.NONE ? Material.WHITE_CONCRETE : Material.WHITE_STAINED_GLASS,
                "&7&lNo Preference", noneLore));

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

    private void populateInfoMenu(Inventory inv, FreezeTagGame game) {
        Arena arena = game.getArena();
        int minP = arena.getMinPlayers() > 0 ? arena.getMinPlayers()
                : plugin.getConfig().getInt("game.min-players", 4);
        int maxP = arena.getMaxPlayers() > 0 ? arena.getMaxPlayers()
                : plugin.getConfig().getInt("game.max-players", 16);
        int dur = arena.getDuration() > 0 ? arena.getDuration()
                : plugin.getConfig().getInt("game.duration", 180);

        // Border
        ItemStack border = createItem(Material.CYAN_STAINED_GLASS_PANE, " ", null);
        for (int i = 0; i < 27; i++) {
            int row = i / 9, col = i % 9;
            if (row == 0 || row == 2 || col == 0 || col == 8) inv.setItem(i, border);
        }

        // Center info item
        inv.setItem(13, createItem(Material.MAP, "&b&l" + MessageUtil.colorize(arena.getDisplayName()), List.of(
                "",
                MessageUtil.colorize("&7Players:     &f" + game.getPlayerCount() + " / " + maxP),
                MessageUtil.colorize("&7Min to start: &f" + minP),
                MessageUtil.colorize("&7Game duration: &f" + MessageUtil.formatTime(dur)),
                MessageUtil.colorize("&7Status: " + (arena.isEnabled() ? "&aEnabled" : "&cDisabled")),
                ""
        )));

        inv.setItem(22, createItem(Material.ARROW, "&7Close", null));
    }

    private void populateVoteMenu(Inventory inv, Player player, FreezeTagGame game) {
        // Full border
        ItemStack border = createItem(Material.PURPLE_STAINED_GLASS_PANE, " ", null);
        for (int i = 0; i < 54; i++) {
            int row = i / 9, col = i % 9;
            if (row == 0 || row == 5 || col == 0 || col == 8) inv.setItem(i, border);
        }

        List<Arena> arenas = plugin.getArenaManager().getEnabledArenas();
        Map<String, Integer> votes = game.getVoteCounts();
        String myVote = game.getPlayerVote(player.getUniqueId());

        // Inner slots: rows 1-4, cols 1-7 → slots 10-16, 19-25, 28-34, 37-43
        int[] innerSlots = buildInnerSlots();
        int idx = 0;
        for (Arena arena : arenas) {
            if (idx >= innerSlots.length) break;
            boolean isMyVote = arena.getName().equalsIgnoreCase(myVote);
            int voteCount = votes.getOrDefault(arena.getName(), 0);

            List<String> lore = new ArrayList<>();
            lore.add("");
            lore.add(MessageUtil.colorize("&7Votes: &f" + voteCount));
            if (isMyVote) lore.add(MessageUtil.colorize("&a✔ Your vote"));
            lore.add("");
            lore.add(MessageUtil.colorize(isMyVote ? "&eClick to change vote" : "&eClick to vote!"));

            Material mat = isMyVote ? Material.LIME_CONCRETE : Material.FILLED_MAP;
            inv.setItem(innerSlots[idx], createItem(mat,
                    (isMyVote ? "&a&l" : "&f&l") + MessageUtil.colorize(arena.getDisplayName()), lore));
            idx++;
        }

        if (arenas.isEmpty()) {
            inv.setItem(22, createItem(Material.BARRIER, "&cNo arenas available", null));
        }

        inv.setItem(49, createItem(Material.ARROW, "&7Back", null));
    }

    private int[] buildInnerSlots() {
        // 4 rows × 7 cols of inner slots
        int[] slots = new int[28];
        int i = 0;
        for (int row = 1; row <= 4; row++) {
            for (int col = 1; col <= 7; col++) {
                slots[i++] = row * 9 + col;
            }
        }
        return slots;
    }

    // -------------------------------------------------------------------------
    // Click handling
    // -------------------------------------------------------------------------

    public boolean handleClick(Player player, String inventoryTitle, int slot) {
        String menuType = openMenus.get(player.getUniqueId());
        if (menuType == null) return false;
        FreezeTagGame game = plugin.getGameManager().getPlayerGame(player.getUniqueId());

        VoteLobby vl = plugin.getVoteLobby();
        return switch (menuType) {
            case "class"    -> handleClassMenuClick(player, game, slot);
            case "class_vl" -> handleClassMenuClickVL(player, vl, slot);
            case "role"     -> handleRoleMenuClick(player, slot);
            case "info"     -> handleInfoMenuClick(player, slot);
            case "vote"     -> handleVoteMenuClick(player, game, slot);
            case "vote_vl"  -> handleVoteMenuClickVL(player, vl, slot);
            default         -> false;
        };
    }

    private boolean handleClassMenuClick(Player player, FreezeTagGame game, int slot) {
        if (slot == 49) { player.closeInventory(); return true; }
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
                    giveItems(player, game);
                    return true;
                }
            }
        }
        return true;
    }

    private boolean handleRoleMenuClick(Player player, int slot) {
        VoteLobby vl = plugin.getVoteLobby();
        boolean inVL = vl != null && vl.isInLobby(player.getUniqueId());

        RolePreference pref = switch (slot) {
            case 11 -> RolePreference.RUNNER;
            case 13 -> RolePreference.NONE;
            case 15 -> RolePreference.TAGGER;
            default -> null;
        };

        if (pref != null) {
            if (inVL) {
                vl.setRolePreference(player.getUniqueId(), pref);
                giveVoteLobbyItems(player, vl); // refresh hotbar role glass
            } else {
                plugin.getGameManager().setRolePreference(player, pref);
                FreezeTagGame game = plugin.getGameManager().getPlayerGame(player.getUniqueId());
                if (game != null) giveItems(player, game); // refresh hotbar role glass
            }
            String msgKey = switch (pref) {
                case RUNNER -> "role.preference-set-runner";
                case TAGGER -> "role.preference-set-tagger";
                default     -> "role.preference-cleared";
            };
            MessageUtil.sendMessage(player, msgKey);
            openRoleMenu(player); // re-open with updated checkmarks
        } else if (slot == 22) {
            player.closeInventory();
        }
        return true;
    }

    private boolean handleInfoMenuClick(Player player, int slot) {
        if (slot == 22) player.closeInventory();
        return true;
    }

    private boolean handleVoteMenuClick(Player player, FreezeTagGame game, int slot) {
        if (slot == 49) { player.closeInventory(); return true; }
        if (game == null) { player.closeInventory(); return true; }

        Inventory inv = player.getOpenInventory().getTopInventory();
        ItemStack clicked = inv.getItem(slot);
        if (clicked == null || clicked.getType() == Material.AIR
                || clicked.getType() == Material.PURPLE_STAINED_GLASS_PANE) return true;

        // Map clicked item display name to an arena
        if (clicked.getItemMeta() != null) {
            String rawName = clicked.getItemMeta().getDisplayName();
            // Strip color codes to compare display names
            String plain = rawName.replaceAll("§[0-9a-fklmnorA-FKLMNOR]", "").trim();
            for (Arena arena : plugin.getArenaManager().getEnabledArenas()) {
                String arenaPlain = MessageUtil.colorize(arena.getDisplayName())
                        .replaceAll("§[0-9a-fklmnorA-FKLMNOR]", "").trim();
                if (arenaPlain.equalsIgnoreCase(plain)) {
                    game.voteArena(player.getUniqueId(), arena.getName());
                    player.sendMessage(MessageUtil.colorize(MessageUtil.getPrefix()
                            + "&eYou voted for &f" + arena.getDisplayName() + "&e!"));
                    // Refresh
                    openVoteMenu(player, game);
                    // Update the hotbar vote item color/lore for this player
                    player.getInventory().setItem(4, buildVoteItem(player, game));
                    return true;
                }
            }
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Vote lobby population + click handling
    // -------------------------------------------------------------------------

    private void populateClassMenuVL(Inventory inv, Player player, VoteLobby vl) {
        ItemStack filler = createItem(Material.GRAY_STAINED_GLASS_PANE, " ", null);
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);

        int slot = 10;
        for (PlayerClass pc : plugin.getClassManager().getAllClasses()) {
            if (slot >= 44) break;
            ItemStack classItem = pc.createDisplayItem();
            ItemMeta meta = classItem.getItemMeta();
            if (meta != null) {
                List<String> lore = meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
                lore.add(""); lore.add(MessageUtil.colorize("&eClick to select!"));
                meta.setLore(lore);
                classItem.setItemMeta(meta);
            }
            inv.setItem(slot, classItem);
            slot++;
            if ((slot - 9) % 9 == 0) slot += 2;
        }
        inv.setItem(49, createItem(Material.ARROW, "&7Back", null));
    }

    private void populateVoteMenuVL(Inventory inv, Player player, VoteLobby vl) {
        ItemStack border = createItem(Material.PURPLE_STAINED_GLASS_PANE, " ", null);
        for (int i = 0; i < 54; i++) {
            int row = i / 9, col = i % 9;
            if (row == 0 || row == 5 || col == 0 || col == 8) inv.setItem(i, border);
        }

        java.util.List<Arena> arenas = plugin.getArenaManager().getEnabledArenas();
        Map<String, Integer> votes = vl.getVoteCounts();
        String myVote = vl.getPlayerVote(player.getUniqueId());

        int[] innerSlots = buildInnerSlots();
        int idx = 0;
        for (Arena arena : arenas) {
            if (idx >= innerSlots.length) break;
            boolean isMyVote = arena.getName().equalsIgnoreCase(myVote);
            int voteCount = votes.getOrDefault(arena.getName(), 0);

            List<String> lore = new ArrayList<>();
            lore.add("");
            lore.add(MessageUtil.colorize("&7Votes: &f" + voteCount));
            if (isMyVote) lore.add(MessageUtil.colorize("&a✔ Your vote"));
            lore.add(""); lore.add(MessageUtil.colorize(isMyVote ? "&eClick to change vote" : "&eClick to vote!"));

            inv.setItem(innerSlots[idx], createItem(
                    isMyVote ? Material.LIME_CONCRETE : Material.FILLED_MAP,
                    (isMyVote ? "&a&l" : "&f&l") + MessageUtil.colorize(arena.getDisplayName()), lore));
            idx++;
        }
        if (arenas.isEmpty()) inv.setItem(22, createItem(Material.BARRIER, "&cNo arenas available", null));
        inv.setItem(49, createItem(Material.ARROW, "&7Back", null));
    }

    private boolean handleClassMenuClickVL(Player player, VoteLobby vl, int slot) {
        if (slot == 49) { player.closeInventory(); return true; }
        if (vl == null) { player.closeInventory(); return true; }

        Inventory inv = player.getOpenInventory().getTopInventory();
        ItemStack clicked = inv.getItem(slot);
        if (clicked == null || clicked.getType() == Material.GRAY_STAINED_GLASS_PANE
                || clicked.getType() == Material.AIR) return true;

        if (clicked.getItemMeta() != null) {
            String clickedName = clicked.getItemMeta().getDisplayName();
            for (PlayerClass pc : plugin.getClassManager().getAllClasses()) {
                if (MessageUtil.colorize(pc.getDisplayName()).equals(clickedName)) {
                    vl.setPlayerClass(player.getUniqueId(), pc);
                    MessageUtil.sendMessage(player, "class.selected",
                            Map.of("class", MessageUtil.colorize(pc.getDisplayName())));
                    player.closeInventory();
                    giveVoteLobbyItems(player, vl);
                    return true;
                }
            }
        }
        return true;
    }

    private boolean handleVoteMenuClickVL(Player player, VoteLobby vl, int slot) {
        if (slot == 49) { player.closeInventory(); return true; }
        if (vl == null) { player.closeInventory(); return true; }

        Inventory inv = player.getOpenInventory().getTopInventory();
        ItemStack clicked = inv.getItem(slot);
        if (clicked == null || clicked.getType() == Material.AIR
                || clicked.getType() == Material.PURPLE_STAINED_GLASS_PANE) return true;

        if (clicked.getItemMeta() != null) {
            String plain = clicked.getItemMeta().getDisplayName()
                    .replaceAll("§[0-9a-fklmnorA-FKLMNOR]", "").trim();
            for (Arena arena : plugin.getArenaManager().getEnabledArenas()) {
                String arenaPlain = MessageUtil.colorize(arena.getDisplayName())
                        .replaceAll("§[0-9a-fklmnorA-FKLMNOR]", "").trim();
                if (arenaPlain.equalsIgnoreCase(plain)) {
                    vl.vote(player.getUniqueId(), arena.getName());
                    player.sendMessage(MessageUtil.colorize(MessageUtil.getPrefix()
                            + "&eYou voted for &f" + arena.getDisplayName() + "&e!"));
                    openVoteMenuVL(player, vl);
                    player.getInventory().setItem(4, buildVoteItemFromVoteLobby(player, vl));
                    return true;
                }
            }
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    public boolean isFreezeTagGUI(String title) {
        return title.equals(CLASS_TITLE) || title.equals(ROLE_TITLE)
                || title.equals(INFO_TITLE) || title.equals(VOTE_TITLE);
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
