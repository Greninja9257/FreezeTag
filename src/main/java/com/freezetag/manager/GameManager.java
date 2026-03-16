package com.freezetag.manager;

import com.freezetag.FreezeTagPlugin;
import com.freezetag.arena.Arena;
import com.freezetag.game.FreezeTagGame;
import com.freezetag.game.GameState;
import com.freezetag.game.RolePreference;
import com.freezetag.util.MessageUtil;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Central manager for all active FreezeTag games and the player queue.
 */
public class GameManager {

    private final FreezeTagPlugin plugin;
    private final Logger logger;

    /** arenaName (lowercase) -> active game */
    private final Map<String, FreezeTagGame> activeGames = new HashMap<>();

    /** UUID -> [arenaName or null, rolePreference] */
    private final Map<UUID, QueueEntry> queue = new HashMap<>();

    public GameManager(FreezeTagPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    // -------------------------------------------------------------------------
    // Queue management
    // -------------------------------------------------------------------------

    /**
     * Add a player to the queue for a specific arena (or any arena if arenaName is null).
     */
    public boolean joinQueue(Player player, String arenaName, RolePreference preference) {
        UUID uuid = player.getUniqueId();

        // Already in a game?
        if (getPlayerGame(uuid) != null) {
            MessageUtil.sendMessage(player, "game.already-in-game");
            return false;
        }

        // Already in queue?
        if (queue.containsKey(uuid)) {
            MessageUtil.sendMessage(player, "game.already-in-queue");
            return false;
        }

        // Validate arena if specified
        Arena arena = null;
        if (arenaName != null && !arenaName.isEmpty()) {
            arena = plugin.getArenaManager().getArena(arenaName);
            if (arena == null) {
                MessageUtil.sendMessage(player, "general.invalid-arena",
                        Map.of("arena", arenaName));
                return false;
            }
            if (!arena.isEnabled()) {
                MessageUtil.sendMessage(player, "game.arena-disabled");
                return false;
            }
        } else {
            // Pick the first enabled arena if none specified
            List<Arena> enabled = plugin.getArenaManager().getEnabledArenas();
            if (!enabled.isEmpty()) {
                arena = enabled.get(0);
            }
        }

        if (arena == null) {
            player.sendMessage(MessageUtil.colorize(MessageUtil.getPrefix())
                    + "&cNo enabled arenas available. Ask an admin to configure one.");
            return false;
        }

        if (!arena.isFullyConfigured()) {
            player.sendMessage(MessageUtil.colorize(MessageUtil.getPrefix())
                    + "&cArena '" + arena.getName() + "' is not fully configured yet.");
            return false;
        }

        // If there's already an active lobby/waiting game, try to join it
        FreezeTagGame existingGame = activeGames.get(arena.getName().toLowerCase());
        if (existingGame != null) {
            if (existingGame.getState() == GameState.WAITING || existingGame.getState() == GameState.STARTING) {
                int maxPlayers = arena.getMaxPlayers() > 0 ? arena.getMaxPlayers()
                        : plugin.getConfig().getInt("game.max-players", 20);
                if (existingGame.getPlayerCount() >= maxPlayers) {
                    MessageUtil.sendMessage(player, "game.arena-full");
                    return false;
                }
                existingGame.addPlayer(player, preference);
                queue.put(uuid, new QueueEntry(arena.getName(), preference));
                MessageUtil.sendMessage(player, "game.join-queue",
                        Map.of("arena", MessageUtil.colorize(arena.getDisplayName())));
                checkAutoStart(arena.getName());
                return true;
            } else if (existingGame.getState() == GameState.IN_GAME) {
                // Join as spectator
                existingGame.addPlayer(player, preference);
                queue.put(uuid, new QueueEntry(arena.getName(), preference));
                return true;
            } else {
                // Game is ending — tell player to wait
                player.sendMessage(MessageUtil.colorize(MessageUtil.getPrefix())
                        + "&7Game is ending in '" + arena.getDisplayName() + "', please wait...");
                return false;
            }
        }

        // Create a new game for this arena
        FreezeTagGame game = new FreezeTagGame(plugin, arena);
        activeGames.put(arena.getName().toLowerCase(), game);
        game.addPlayer(player, preference);
        queue.put(uuid, new QueueEntry(arena.getName(), preference));

        MessageUtil.sendMessage(player, "game.join-queue",
                Map.of("arena", MessageUtil.colorize(arena.getDisplayName())));

        // Check if we have enough players to start the lobby countdown
        checkAutoStart(arena.getName());
        return true;
    }

    /**
     * Remove a player from the queue or current game.
     */
    public void leaveQueue(Player player) {
        UUID uuid = player.getUniqueId();

        // Check if in active game
        FreezeTagGame game = getPlayerGame(uuid);
        if (game != null) {
            game.removePlayer(player);
            queue.remove(uuid);
            return;
        }

        // Just in queue
        if (queue.remove(uuid) != null) {
            MessageUtil.sendMessage(player, "game.leave-queue");
        } else {
            MessageUtil.sendMessage(player, "game.not-in-queue");
        }
    }

    /**
     * Check if enough players are in the queue for the arena to auto-start the lobby.
     */
    private void checkAutoStart(String arenaName) {
        FreezeTagGame game = activeGames.get(arenaName.toLowerCase());
        if (game == null) return;
        if (game.getState() != GameState.WAITING) return;

        Arena arena = plugin.getArenaManager().getArena(arenaName);
        if (arena == null) return;

        int minPlayers = arena.getMinPlayers() > 0 ? arena.getMinPlayers()
                : plugin.getConfig().getInt("game.min-players", 4);

        if (game.getPlayerCount() >= minPlayers) {
            game.startLobby();
        }
    }

    // -------------------------------------------------------------------------
    // Game control (admin commands)
    // -------------------------------------------------------------------------

    /**
     * Force start a game immediately.
     */
    public boolean startGame(String arenaName) {
        Arena arena = plugin.getArenaManager().getArena(arenaName);
        if (arena == null) return false;

        String key = arenaName.toLowerCase();
        FreezeTagGame existingGame = activeGames.get(key);

        if (existingGame != null) {
            if (existingGame.getState() == GameState.WAITING) {
                existingGame.startLobby();
                return true;
            } else if (existingGame.getState() == GameState.STARTING) {
                existingGame.startGame();
                return true;
            }
            return false; // Already in game or ending
        }

        // Create new game with no players (admin force-start)
        FreezeTagGame game = new FreezeTagGame(plugin, arena);
        activeGames.put(key, game);
        game.startLobby();
        return true;
    }

    /**
     * Force stop a running game.
     */
    public boolean stopGame(String arenaName) {
        FreezeTagGame game = activeGames.get(arenaName.toLowerCase());
        if (game == null) return false;

        game.endGame("stopped", false);
        return true;
    }

    /**
     * Stop all active games.
     */
    public void stopAllGames() {
        for (FreezeTagGame game : new ArrayList<>(activeGames.values())) {
            if (game.getState() != GameState.ENDING) {
                game.endGame("stopped", false);
            }
        }
        activeGames.clear();
        queue.clear();
    }

    /**
     * Called by FreezeTagGame when a game ends naturally — removes from active map.
     */
    public void onGameEnd(String arenaName) {
        FreezeTagGame removed = activeGames.remove(arenaName.toLowerCase());
        if (removed != null) {
            // Remove all players still associated with this game from the queue
            queue.entrySet().removeIf(e ->
                    e.getValue().arenaName.equalsIgnoreCase(arenaName));
        }
    }

    // -------------------------------------------------------------------------
    // Lookup methods
    // -------------------------------------------------------------------------

    /**
     * Get the active game a player is currently in, or null.
     */
    public FreezeTagGame getPlayerGame(UUID uuid) {
        for (FreezeTagGame game : activeGames.values()) {
            if (game.hasPlayer(uuid)) return game;
        }
        return null;
    }

    /**
     * Get a player's queue entry, or null if not queued.
     */
    public QueueEntry getPlayerQueue(UUID uuid) {
        return queue.get(uuid);
    }

    public boolean isInQueue(UUID uuid) {
        return queue.containsKey(uuid);
    }

    public FreezeTagGame getGame(String arenaName) {
        if (arenaName == null) return null;
        return activeGames.get(arenaName.toLowerCase());
    }

    public Collection<FreezeTagGame> getAllGames() {
        return Collections.unmodifiableCollection(activeGames.values());
    }

    /** Register a game created externally (e.g. from VoteLobby) into the active games map. */
    public void registerExternalGame(String arenaName, FreezeTagGame game) {
        activeGames.put(arenaName.toLowerCase(), game);
    }

    // -------------------------------------------------------------------------
    // Role and class management for queued players
    // -------------------------------------------------------------------------

    public void setRolePreference(Player player, RolePreference preference) {
        UUID uuid = player.getUniqueId();
        QueueEntry entry = queue.get(uuid);
        if (entry != null) {
            entry.preference = preference;
            // Also update the game player if in a lobby
            FreezeTagGame game = getPlayerGame(uuid);
            // We track it in the queue entry; game uses it during startGame()
        }
    }

    public void setPlayerClass(Player player, String classId) {
        UUID uuid = player.getUniqueId();
        FreezeTagGame game = getPlayerGame(uuid);
        if (game == null) return;

        if (game.getState() != GameState.WAITING && game.getState() != GameState.STARTING) {
            MessageUtil.sendMessage(player, "class.must-be-in-lobby");
            return;
        }

        var playerClass = plugin.getClassManager().getClass(classId);
        if (playerClass == null) {
            MessageUtil.sendMessage(player, "class.not-found", Map.of("class", classId));
            return;
        }

        var gp = game.getGamePlayer(uuid);
        if (gp == null) return;

        // Check if class is compatible with current preference
        QueueEntry qEntry = queue.get(uuid);
        if (qEntry != null && qEntry.preference != RolePreference.NONE) {
            if (playerClass.getRole() != qEntry.preference) {
                MessageUtil.sendMessage(player, "class.wrong-role");
                return;
            }
        }

        gp.setPlayerClass(playerClass);
        MessageUtil.sendMessage(player, "class.selected",
                Map.of("class", MessageUtil.colorize(playerClass.getDisplayName())));
    }

    // -------------------------------------------------------------------------
    // Inner classes
    // -------------------------------------------------------------------------

    public static class QueueEntry {
        public String arenaName;
        public RolePreference preference;

        public QueueEntry(String arenaName, RolePreference preference) {
            this.arenaName = arenaName;
            this.preference = preference != null ? preference : RolePreference.NONE;
        }
    }
}
