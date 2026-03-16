package com.freezetag.game;

import com.freezetag.FreezeTagPlugin;
import com.freezetag.arena.Arena;
import com.freezetag.classes.PlayerClass;
import com.freezetag.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A universal pre-game lobby where players vote on which arena to play.
 * Separate from arena-specific FreezeTagGame lobbies.
 *
 * Players join via /fta vl join, choose a class + role + arena vote,
 * then when the countdown expires the winning arena starts a FreezeTagGame.
 */
public class VoteLobby {

    public enum State { WAITING, COUNTING_DOWN }

    private final FreezeTagPlugin plugin;
    private Location spawn;

    private final Set<UUID> players = new HashSet<>();
    private final Map<UUID, String>            votes         = new HashMap<>();
    private final Map<UUID, String>            playerClasses = new HashMap<>();
    private final Map<UUID, RolePreference>    playerRoles   = new HashMap<>();

    // Saved player state for restoration on leave
    private final Map<UUID, ItemStack[]>  savedInventories = new HashMap<>();
    private final Map<UUID, GameMode>     savedGameModes   = new HashMap<>();
    private final Map<UUID, Double>       savedHealth      = new HashMap<>();
    private final Map<UUID, Integer>      savedFood        = new HashMap<>();

    private State state = State.WAITING;
    private int   currentCountdown;
    private BukkitTask countdownTask;
    private BukkitTask scoreboardTask;

    public VoteLobby(FreezeTagPlugin plugin) {
        this.plugin = plugin;
        this.currentCountdown = plugin.getConfig().getInt("vote-lobby.countdown", 60);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public FreezeTagPlugin getPlugin()       { return plugin; }
    public boolean isConfigured()            { return spawn != null; }
    public boolean isInLobby(UUID uuid)      { return players.contains(uuid); }
    public int     getPlayerCount()          { return players.size(); }
    public Set<UUID> getPlayers()            { return players; }
    public State   getState()                { return state; }
    public int     getCurrentCountdown()     { return currentCountdown; }
    public Location getSpawn()               { return spawn; }
    public void    setSpawn(Location spawn)  { this.spawn = spawn; }

    public void addPlayer(Player player) {
        if (players.contains(player.getUniqueId())) return;

        // Save state
        UUID uuid = player.getUniqueId();
        savedInventories.put(uuid, player.getInventory().getContents().clone());
        savedGameModes.put(uuid, player.getGameMode());
        savedHealth.put(uuid, player.getHealth());
        savedFood.put(uuid, player.getFoodLevel());

        players.add(uuid);
        playerRoles.put(uuid, RolePreference.NONE);

        // Setup
        player.getInventory().clear();
        player.setGameMode(GameMode.ADVENTURE);
        player.setHealth(player.getMaxHealth());
        player.setFoodLevel(20);
        if (spawn != null) player.teleport(spawn);

        plugin.getLobbyGUI().giveVoteLobbyItems(player, this);
        plugin.getScoreboardManager().createVoteLobbyScoreboard(player, this);

        // Start scoreboard update task once first player joins
        if (scoreboardTask == null) {
            scoreboardTask = new BukkitRunnable() {
                @Override public void run() {
                    plugin.getScoreboardManager().updateAllVoteLobby(VoteLobby.this);
                }
            }.runTaskTimer(plugin, 20L, 20L);
        }

        int minP = plugin.getConfig().getInt("vote-lobby.min-players", 2);
        broadcastToAll("&e" + player.getName() + " &7joined the vote lobby. &f("
                + players.size() + "/" + minP + " needed)");

        checkAutoStart();
    }

    public void removePlayer(Player player) {
        UUID uuid = player.getUniqueId();
        if (!players.remove(uuid)) return;

        votes.remove(uuid);
        playerClasses.remove(uuid);
        playerRoles.remove(uuid);

        plugin.getLobbyGUI().clearItems(player);
        plugin.getScoreboardManager().removeScoreboard(player);
        restorePlayerState(player);

        int minP = plugin.getConfig().getInt("vote-lobby.min-players", 2);
        if (state == State.COUNTING_DOWN && players.size() < minP) {
            pauseCountdown();
        }

        // Stop scoreboard task if lobby is now empty
        if (players.isEmpty() && scoreboardTask != null) {
            scoreboardTask.cancel();
            scoreboardTask = null;
        }

        player.sendMessage(MessageUtil.colorize(MessageUtil.getPrefix() + "&7You left the vote lobby."));
    }

    public void vote(UUID uuid, String arenaName) {
        votes.put(uuid, arenaName);
    }

    public String getPlayerVote(UUID uuid) {
        return votes.get(uuid);
    }

    public Map<String, Integer> getVoteCounts() {
        Map<String, Integer> counts = new HashMap<>();
        for (String v : votes.values()) counts.merge(v, 1, Integer::sum);
        return counts;
    }

    public void setPlayerClass(UUID uuid, PlayerClass pc) {
        playerClasses.put(uuid, pc != null ? pc.getId() : null);
    }

    public String getPlayerClassId(UUID uuid) {
        return playerClasses.get(uuid);
    }

    public void setRolePreference(UUID uuid, RolePreference pref) {
        playerRoles.put(uuid, pref);
    }

    public RolePreference getRolePreference(UUID uuid) {
        return playerRoles.getOrDefault(uuid, RolePreference.NONE);
    }

    public void shutdown() {
        if (countdownTask != null)   { countdownTask.cancel();   countdownTask   = null; }
        if (scoreboardTask != null)  { scoreboardTask.cancel();  scoreboardTask  = null; }
        for (UUID uuid : new HashSet<>(players)) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) restorePlayerState(p);
        }
        players.clear();
        votes.clear();
        playerClasses.clear();
        playerRoles.clear();
    }

    // -------------------------------------------------------------------------
    // Countdown
    // -------------------------------------------------------------------------

    private void checkAutoStart() {
        int minP = plugin.getConfig().getInt("vote-lobby.min-players", 2);
        if (players.size() >= minP && state == State.WAITING) startCountdown();
    }

    private void startCountdown() {
        state = State.COUNTING_DOWN;
        currentCountdown = plugin.getConfig().getInt("vote-lobby.countdown", 60);
        broadcastToAll("&aVote lobby starting in &e" + currentCountdown + "s&a! Vote for your map!");

        countdownTask = new BukkitRunnable() {
            @Override
            public void run() {
                int minP = plugin.getConfig().getInt("vote-lobby.min-players", 2);
                if (players.size() < minP) { pauseCountdown(); return; }

                currentCountdown--;
                if (currentCountdown == 30 || currentCountdown == 10
                        || (currentCountdown <= 5 && currentCountdown > 0)) {
                    broadcastToAll("&aGame starting in &e" + currentCountdown + "s&a!");
                }
                if (currentCountdown <= 0) {
                    cancel();
                    startGame();
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    private void pauseCountdown() {
        if (countdownTask != null) { countdownTask.cancel(); countdownTask = null; }
        state = State.WAITING;
        currentCountdown = plugin.getConfig().getInt("vote-lobby.countdown", 60);
        broadcastToAll("&cNot enough players — countdown paused!");
    }

    // -------------------------------------------------------------------------
    // Game launch
    // -------------------------------------------------------------------------

    private void startGame() {
        // Determine winning arena
        Map<String, Integer> counts = getVoteCounts();
        String winnerKey = counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse(null);
        Arena arena = winnerKey != null ? plugin.getArenaManager().getArena(winnerKey) : null;

        if (arena == null) {
            // Fallback to a random enabled arena
            java.util.List<Arena> enabled = plugin.getArenaManager().getEnabledArenas();
            if (!enabled.isEmpty()) arena = enabled.get(new java.util.Random().nextInt(enabled.size()));
        }

        if (arena == null) {
            broadcastToAll("&cNo arenas available — cannot start game!");
            pauseCountdown();
            return;
        }

        broadcastToAll("&d&lMap Vote: &f" + arena.getDisplayName() + " &dwon! Starting...");

        // Create game and register it
        FreezeTagGame game = new FreezeTagGame(plugin, arena);
        plugin.getGameManager().registerExternalGame(arena.getName(), game);

        // Transfer players
        for (UUID uuid : new HashSet<>(players)) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) continue;

            // Restore normal state first so addPlayer can save it properly
            restorePlayerState(p);

            RolePreference pref = playerRoles.getOrDefault(uuid, RolePreference.NONE);
            game.addPlayer(p, pref);

            // Apply saved class selection
            String classId = playerClasses.get(uuid);
            if (classId != null) {
                PlayerClass pc = plugin.getClassManager().getClass(classId);
                if (pc != null) {
                    GamePlayer gp = game.getGamePlayer(uuid);
                    if (gp != null) gp.setPlayerClass(pc);
                }
            }
        }

        // Start the game immediately (players already waited in vote lobby)
        game.startGame();

        // Stop update tasks
        if (scoreboardTask != null) { scoreboardTask.cancel(); scoreboardTask = null; }

        // Reset vote lobby
        players.clear();
        votes.clear();
        playerClasses.clear();
        playerRoles.clear();
        savedInventories.clear();
        savedGameModes.clear();
        savedHealth.clear();
        savedFood.clear();
        state = State.WAITING;
        currentCountdown = plugin.getConfig().getInt("vote-lobby.countdown", 60);
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private void restorePlayerState(Player player) {
        UUID uuid = player.getUniqueId();
        ItemStack[] inv = savedInventories.remove(uuid);
        if (inv != null) player.getInventory().setContents(inv);
        GameMode gm = savedGameModes.remove(uuid);
        if (gm != null) player.setGameMode(gm);
        Double health = savedHealth.remove(uuid);
        if (health != null) player.setHealth(Math.min(health, player.getMaxHealth()));
        Integer food = savedFood.remove(uuid);
        if (food != null) player.setFoodLevel(food);
    }

    private void broadcastToAll(String msg) {
        String colored = MessageUtil.colorize(MessageUtil.getPrefix() + msg);
        for (UUID uuid : players) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) p.sendMessage(colored);
        }
    }
}
