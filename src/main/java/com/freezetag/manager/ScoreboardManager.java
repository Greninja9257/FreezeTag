package com.freezetag.manager;

import com.freezetag.game.FreezeTagGame;
import com.freezetag.game.GamePlayer;
import com.freezetag.game.GameState;
import com.freezetag.game.RolePreference;
import com.freezetag.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Manages sidebar scoreboards for active FreezeTag games.
 */
public class ScoreboardManager {

    private static final Logger LOGGER = Logger.getLogger("FreezeTag");
    private static final String OBJECTIVE_NAME = "freezetag";

    // Track which scoreboard belongs to which player
    private final Map<UUID, Scoreboard> playerScoreboards = new HashMap<>();

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Create and assign a scoreboard for the player in the given game.
     */
    public void createScoreboard(Player player, FreezeTagGame game) {
        if (player == null || game == null) return;

        Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();

        Objective obj = board.registerNewObjective(
                OBJECTIVE_NAME,
                Criteria.DUMMY,
                MessageUtil.colorize("&b&lFreeze Tag")
        );
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);

        playerScoreboards.put(player.getUniqueId(), board);
        player.setScoreboard(board);
        updateScoreboard(player, game);
    }

    /**
     * Create and assign a waiting scoreboard (shown before lobby countdown starts).
     */
    public void createWaitingScoreboard(Player player, FreezeTagGame game) {
        if (player == null || game == null) return;

        Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective obj = board.registerNewObjective(
                OBJECTIVE_NAME, Criteria.DUMMY,
                MessageUtil.colorize("&b&lFreeze Tag"));
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);

        playerScoreboards.put(player.getUniqueId(), board);
        player.setScoreboard(board);
        updateWaitingScoreboard(player, game);
    }

    /**
     * Update the waiting scoreboard lines.
     */
    public void updateWaitingScoreboard(Player player, FreezeTagGame game) {
        if (player == null || game == null) return;
        Scoreboard board = playerScoreboards.get(player.getUniqueId());
        if (board == null) return;
        Objective obj = board.getObjective(OBJECTIVE_NAME);
        if (obj == null) return;

        for (String entry : board.getEntries()) board.resetScores(entry);

        int minP = game.getArena().getMinPlayers() > 0 ? game.getArena().getMinPlayers()
                : 4;

        int score = 10;
        obj.getScore("§r§f§r").setScore(score--);
        obj.getScore("§b§lArena: §f" + game.getArena().getDisplayName()).setScore(score--);
        obj.getScore("§r§a§r").setScore(score--);
        obj.getScore("§ePlayers: §f" + game.getPlayerCount() + "§7/§f" + minP).setScore(score--);
        obj.getScore("§r§b§r").setScore(score--);
        obj.getScore("§7Waiting for players...").setScore(score--);
        obj.getScore("§r§c§r").setScore(score--);
    }

    /**
     * Update the scoreboard display for a player.
     */
    public void updateScoreboard(Player player, FreezeTagGame game) {
        if (player == null || game == null) return;

        Scoreboard board = playerScoreboards.get(player.getUniqueId());
        if (board == null) return;

        Objective obj = board.getObjective(OBJECTIVE_NAME);
        if (obj == null) return;

        // Clear old scores
        for (String entry : board.getEntries()) {
            board.resetScores(entry);
        }

        GamePlayer gp = game.getGamePlayer(player.getUniqueId());

        // Build scoreboard lines (top = highest score)
        int score = 15;
        Map<String, Integer> lines = new HashMap<>();

        // Header spacer
        lines.put("§r§f§r", score--);

        // Arena name
        String arenaLine = "§b§lArena: §f" + MessageUtil.colorize(game.getArena().getDisplayName());
        lines.put(truncate(arenaLine, 40), score--);

        // Spacer
        lines.put("§r§a§r", score--);

        // Time remaining
        String timeStr;
        if (game.getState() == GameState.STARTING) {
            timeStr = "§e§lStarting: §f" + game.getLobbyCountdown() + "s";
        } else if (game.getState() == GameState.IN_GAME) {
            timeStr = "§e§lTime: §f" + MessageUtil.formatTime(game.getTimeLeft());
        } else if (game.getState() == GameState.WAITING) {
            int minP = game.getArena().getMinPlayers() > 0 ? game.getArena().getMinPlayers()
                    : 4;
            timeStr = "§7Waiting: §f" + game.getPlayerCount() + "§7/§f" + minP + " §7players";
        } else {
            timeStr = "§7Game Over";
        }
        lines.put(truncate(timeStr, 40), score--);

        // Spacer
        lines.put("§r§b§r", score--);

        // Runner counts
        int aliveRunners = game.getAliveRunnerCount();
        int frozenRunners = game.getFrozenRunnerCount();
        int totalRunners = game.getTotalRunnerCount();

        lines.put("§a§lRunners: §f" + aliveRunners + "/" + totalRunners, score--);
        lines.put("§b§lFrozen: §f" + frozenRunners, score--);
        lines.put("§c§lTaggers: §f" + game.getTaggerCount(), score--);

        // Spacer
        lines.put("§r§c§r", score--);

        // Player's own role and class
        if (gp != null) {
            String roleStr;
            boolean inLobby = game.getState() == GameState.WAITING || game.getState() == GameState.STARTING;
            if (inLobby) {
                roleStr = "§7Waiting...";
            } else if (gp.isSpectator()) {
                roleStr = "§7Spectator";
            } else if (gp.isRunner()) {
                if (gp.isFrozen()) {
                    roleStr = "§b§lFROZEN";
                } else {
                    roleStr = "§aRunner";
                }
            } else {
                roleStr = "§cTagger";
            }
            lines.put("§fRole: " + roleStr, score--);

            String classDisplay = gp.getPlayerClass() != null
                    ? MessageUtil.colorize(gp.getPlayerClass().getDisplayName())
                    : "§7None";
            lines.put(truncate("§fClass: " + classDisplay, 40), score--);
        }

        // Spacer
        lines.put("§r§d§r", score--);

        // Apply all lines
        for (Map.Entry<String, Integer> entry : lines.entrySet()) {
            if (entry.getValue() >= 0) {
                obj.getScore(entry.getKey()).setScore(entry.getValue());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Vote lobby scoreboard
    // -------------------------------------------------------------------------

    /** Create and assign a vote-lobby scoreboard to a player. */
    public void createVoteLobbyScoreboard(Player player, com.freezetag.game.VoteLobby vl) {
        if (player == null || vl == null) return;
        Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective obj = board.registerNewObjective(
                OBJECTIVE_NAME, Criteria.DUMMY, MessageUtil.colorize("&b&lFreeze Tag"));
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        playerScoreboards.put(player.getUniqueId(), board);
        player.setScoreboard(board);
        updateVoteLobbyScoreboard(player, vl);
    }

    /** Refresh the vote-lobby scoreboard lines for one player. */
    public void updateVoteLobbyScoreboard(Player player, com.freezetag.game.VoteLobby vl) {
        if (player == null || vl == null) return;
        Scoreboard board = playerScoreboards.get(player.getUniqueId());
        if (board == null) return;
        Objective obj = board.getObjective(OBJECTIVE_NAME);
        if (obj == null) return;

        for (String entry : board.getEntries()) board.resetScores(entry);

        int minP = vl.getPlugin().getConfig().getInt("vote-lobby.min-players", 2);
        String classId = vl.getPlayerClassId(player.getUniqueId());
        com.freezetag.classes.PlayerClass pc = classId != null
                ? vl.getPlugin().getClassManager().getClass(classId) : null;
        String classDisplay = pc != null ? MessageUtil.colorize(pc.getDisplayName()) : "§7None";

        com.freezetag.game.RolePreference pref = vl.getRolePreference(player.getUniqueId());
        String roleDisplay = switch (pref) {
            case RUNNER -> "§aRunner";
            case TAGGER -> "§cTagger";
            default     -> "§7Waiting...";
        };

        // Status line
        String statusLine;
        if (vl.getState() == com.freezetag.game.VoteLobby.State.COUNTING_DOWN) {
            statusLine = "§e§lStarting: §f" + vl.getCurrentCountdown() + "s";
        } else {
            statusLine = "§7Waiting for players...";
        }

        // Leading vote
        java.util.Map<String, Integer> votes = vl.getVoteCounts();
        String voteStr;
        if (votes.isEmpty()) {
            voteStr = "§7No votes yet";
        } else {
            String topKey = votes.entrySet().stream()
                    .max(java.util.Map.Entry.comparingByValue())
                    .map(java.util.Map.Entry::getKey).orElse(null);
            if (topKey != null) {
                com.freezetag.arena.Arena top = vl.getPlugin().getArenaManager().getArena(topKey);
                String topName = top != null ? MessageUtil.colorize(top.getDisplayName()) : topKey;
                int cnt = votes.get(topKey);
                voteStr = "§f" + topName + " §7(" + cnt + ")";
            } else {
                voteStr = "§7No votes yet";
            }
        }

        int score = 12;
        obj.getScore("§r§f§r").setScore(score--);
        obj.getScore(truncate(statusLine, 40)).setScore(score--);
        obj.getScore("§r§a§r").setScore(score--);
        obj.getScore("§ePlayers: §f" + vl.getPlayerCount() + "§7/§f" + minP).setScore(score--);
        obj.getScore("§r§b§r").setScore(score--);
        obj.getScore("§fRole: " + roleDisplay).setScore(score--);
        obj.getScore(truncate("§fClass: " + classDisplay, 40)).setScore(score--);
        obj.getScore("§r§c§r").setScore(score--);
        obj.getScore("§d§lLeading Vote:").setScore(score--);
        obj.getScore(truncate("  " + voteStr, 40)).setScore(score--);
        obj.getScore("§r§d§r").setScore(score--);
    }

    /** Refresh vote-lobby scoreboards for all players in the lobby. */
    public void updateAllVoteLobby(com.freezetag.game.VoteLobby vl) {
        if (vl == null) return;
        for (java.util.UUID uuid : vl.getPlayers()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) updateVoteLobbyScoreboard(p, vl);
        }
    }

    /**
     * Update scoreboards for all players in the game.
     */
    public void updateAll(FreezeTagGame game) {
        if (game == null) return;
        for (UUID uuid : game.getGamePlayers().keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                updateScoreboard(player, game);
            }
        }
    }

    /**
     * Remove the scoreboard from a player and restore the default.
     */
    public void removeScoreboard(Player player) {
        if (player == null) return;
        playerScoreboards.remove(player.getUniqueId());
        // Restore the main server scoreboard
        try {
            player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        } catch (Exception e) {
            // Player might be offline
        }
    }

    /**
     * Remove scoreboards for all players in the game and clean up.
     */
    public void removeAll(FreezeTagGame game) {
        if (game == null) return;
        for (UUID uuid : game.getGamePlayers().keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                removeScoreboard(player);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Team management for colored names
    // -------------------------------------------------------------------------

    /**
     * Set up teams on the player's scoreboard for colored name display.
     */
    public void setupTeams(Player player, FreezeTagGame game) {
        Scoreboard board = playerScoreboards.get(player.getUniqueId());
        if (board == null) return;

        // Create or get teams
        Team runnerTeam = getOrCreateTeam(board, "ft_runner", "§a");
        Team taggerTeam = getOrCreateTeam(board, "ft_tagger", "§c");
        Team frozenTeam = getOrCreateTeam(board, "ft_frozen", "§b");
        Team spectatorTeam = getOrCreateTeam(board, "ft_spectator", "§7");

        // Add all game players to the appropriate team
        for (Map.Entry<UUID, GamePlayer> entry : game.getGamePlayers().entrySet()) {
            Player target = Bukkit.getPlayer(entry.getKey());
            if (target == null) continue;

            GamePlayer gp = entry.getValue();
            String name = target.getName();

            // Remove from all teams first
            if (runnerTeam.hasEntry(name)) runnerTeam.removeEntry(name);
            if (taggerTeam.hasEntry(name)) taggerTeam.removeEntry(name);
            if (frozenTeam.hasEntry(name)) frozenTeam.removeEntry(name);
            if (spectatorTeam.hasEntry(name)) spectatorTeam.removeEntry(name);

            // Add to correct team
            if (gp.isSpectator()) {
                spectatorTeam.addEntry(name);
            } else if (gp.isRunner()) {
                if (gp.isFrozen()) {
                    frozenTeam.addEntry(name);
                } else {
                    runnerTeam.addEntry(name);
                }
            } else if (gp.isTagger()) {
                taggerTeam.addEntry(name);
            }
        }
    }

    private Team getOrCreateTeam(Scoreboard board, String name, String prefix) {
        Team team = board.getTeam(name);
        if (team == null) {
            team = board.registerNewTeam(name);
        }
        team.setPrefix(prefix);
        return team;
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) : s;
    }

    public boolean hasScoreboard(UUID uuid) {
        return playerScoreboards.containsKey(uuid);
    }
}
