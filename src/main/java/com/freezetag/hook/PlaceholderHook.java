package com.freezetag.hook;

import com.freezetag.FreezeTagPlugin;
import com.freezetag.classes.Ability;
import com.freezetag.game.FreezeTagGame;
import com.freezetag.game.GamePlayer;
import com.freezetag.game.GameState;
import com.freezetag.game.RolePreference;
import com.freezetag.util.MessageUtil;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * PlaceholderAPI expansion for FreezeTag.
 *
 * Full placeholder list:
 *
 * --- Player / game state ---
 *   %freezetag_in_game%            true/false — player is in an active game
 *   %freezetag_in_lobby%           true/false — player is in waiting or starting phase
 *   %freezetag_state%              raw state: WAITING / STARTING / IN_GAME / ENDING / NONE
 *   %freezetag_state_display%      friendly: "Waiting", "Starting", "In Game", "Ending", "Not Playing"
 *
 * --- Role ---
 *   %freezetag_role%               Runner / Tagger / Spectator / None
 *   %freezetag_is_runner%          true/false
 *   %freezetag_is_tagger%          true/false
 *   %freezetag_is_frozen%          true/false
 *   %freezetag_is_spectator%       true/false
 *
 * --- Class ---
 *   %freezetag_class%              selected class display name (colored), or "None"
 *   %freezetag_class_id%           selected class internal id, or "none"
 *
 * --- Ability ---
 *   %freezetag_ability_name%       name of the player's active ability, or "None"
 *   %freezetag_ability_ready%      true/false — ability is off cooldown
 *   %freezetag_ability_cooldown%   seconds remaining on cooldown (0 if ready)
 *
 * --- Arena / game info ---
 *   %freezetag_arena%              arena display name
 *   %freezetag_arena_name%         arena internal name
 *   %freezetag_time_left%          formatted time remaining e.g. "2:45"
 *   %freezetag_time_left_seconds%  raw seconds remaining
 *   %freezetag_lobby_countdown%    countdown seconds during STARTING phase
 *   %freezetag_players%            current player count in the arena
 *   %freezetag_max_players%        max players for the arena
 *   %freezetag_min_players%        min players needed to start
 *   %freezetag_runners_alive%      alive (non-frozen) runner count
 *   %freezetag_runners_frozen%     frozen runner count
 *   %freezetag_runners_total%      total runner count
 *   %freezetag_taggers%            tagger count
 *
 * --- Per-game stats ---
 *   %freezetag_stat_tags%          times this player tagged someone (tagger)
 *   %freezetag_stat_freezes%       times this player was frozen
 *   %freezetag_stat_rescues%       times this player rescued a teammate
 *
 * --- Global ---
 *   %freezetag_active_games%       number of games currently running
 *   %freezetag_total_players%      total players across all active games
 */
public class PlaceholderHook extends PlaceholderExpansion {

    private final FreezeTagPlugin plugin;

    public PlaceholderHook(FreezeTagPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() { return "freezetag"; }

    @Override
    public @NotNull String getAuthor() { return "FreezeTag"; }

    @Override
    public @NotNull String getVersion() { return plugin.getDescription().getVersion(); }

    @Override
    public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer offlinePlayer, @NotNull String params) {
        // Global placeholders (no player context needed)
        switch (params) {
            case "active_games":
                return String.valueOf(plugin.getGameManager().getAllGames().size());
            case "total_players":
                int total = plugin.getGameManager().getAllGames().stream()
                        .mapToInt(FreezeTagGame::getPlayerCount).sum();
                return String.valueOf(total);
        }

        // All remaining placeholders require an online player
        if (offlinePlayer == null || !offlinePlayer.isOnline()) return "";
        Player player = offlinePlayer.getPlayer();
        if (player == null) return "";

        FreezeTagGame game = plugin.getGameManager().getPlayerGame(player.getUniqueId());

        return switch (params) {
            // ---- Game state ----
            case "in_game"         -> game != null ? "true" : "false";
            case "in_lobby"        -> game != null
                    && (game.getState() == GameState.WAITING || game.getState() == GameState.STARTING)
                    ? "true" : "false";
            case "state"           -> game != null ? game.getState().name() : "NONE";
            case "state_display"   -> stateDisplay(game);

            // ---- Role ----
            case "role"            -> roleDisplay(game, player);
            case "is_runner"       -> boolFromGp(game, player, gp -> gp.isRunner() && !gp.isFrozen());
            case "is_tagger"       -> boolFromGp(game, player, gp -> gp.isTagger());
            case "is_frozen"       -> boolFromGp(game, player, gp -> gp.isFrozen());
            case "is_spectator"    -> boolFromGp(game, player, gp -> gp.isSpectator());

            // ---- Class ----
            case "class"           -> classDisplay(game, player);
            case "class_id"        -> classId(game, player);

            // ---- Ability ----
            case "ability_name"    -> abilityName(game, player);
            case "ability_ready"   -> abilityReady(game, player);
            case "ability_cooldown"-> abilityCooldown(game, player);

            // ---- Arena / game numbers ----
            case "arena"           -> game != null ? MessageUtil.colorize(game.getArena().getDisplayName()) : "";
            case "arena_name"      -> game != null ? game.getArena().getName() : "";
            case "time_left"       -> game != null && game.getState() == GameState.IN_GAME
                    ? MessageUtil.formatTime(game.getTimeLeft()) : "";
            case "time_left_seconds" -> game != null && game.getState() == GameState.IN_GAME
                    ? String.valueOf(game.getTimeLeft()) : "0";
            case "lobby_countdown" -> game != null && game.getState() == GameState.STARTING
                    ? String.valueOf(game.getLobbyCountdown()) : "0";
            case "players"         -> game != null ? String.valueOf(game.getPlayerCount()) : "0";
            case "max_players"     -> game != null
                    ? String.valueOf(game.getArena().getMaxPlayers() > 0
                        ? game.getArena().getMaxPlayers()
                        : plugin.getConfig().getInt("game.max-players", 20))
                    : "0";
            case "min_players"     -> game != null
                    ? String.valueOf(game.getArena().getMinPlayers() > 0
                        ? game.getArena().getMinPlayers()
                        : plugin.getConfig().getInt("game.min-players", 4))
                    : "0";
            case "runners_alive"   -> game != null ? String.valueOf(game.getAliveRunnerCount()) : "0";
            case "runners_frozen"  -> game != null ? String.valueOf(game.getFrozenRunnerCount()) : "0";
            case "runners_total"   -> game != null ? String.valueOf(game.getTotalRunnerCount()) : "0";
            case "taggers"         -> game != null ? String.valueOf(game.getTaggerCount()) : "0";

            // ---- Per-game stats ----
            case "stat_tags"       -> statFromGp(game, player, GamePlayer::getTagCount);
            case "stat_freezes"    -> statFromGp(game, player, GamePlayer::getFreezeCount);
            case "stat_rescues"    -> statFromGp(game, player, GamePlayer::getUnfreezeCount);

            default -> null; // Unknown placeholder
        };
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String stateDisplay(FreezeTagGame game) {
        if (game == null) return "Not Playing";
        return switch (game.getState()) {
            case WAITING  -> "Waiting";
            case STARTING -> "Starting";
            case IN_GAME  -> "In Game";
            case ENDING   -> "Ending";
        };
    }

    private String roleDisplay(FreezeTagGame game, Player player) {
        if (game == null) return "None";
        GamePlayer gp = game.getGamePlayer(player.getUniqueId());
        if (gp == null) return "None";
        if (gp.isSpectator()) return "Spectator";
        if (gp.isTagger()) return "Tagger";
        if (gp.isFrozen()) return "Frozen";
        return "Runner";
    }

    private String classDisplay(FreezeTagGame game, Player player) {
        if (game == null) return "None";
        GamePlayer gp = game.getGamePlayer(player.getUniqueId());
        if (gp == null || gp.getPlayerClass() == null) return "None";
        return MessageUtil.colorize(gp.getPlayerClass().getDisplayName());
    }

    private String classId(FreezeTagGame game, Player player) {
        if (game == null) return "none";
        GamePlayer gp = game.getGamePlayer(player.getUniqueId());
        if (gp == null || gp.getPlayerClass() == null) return "none";
        return gp.getPlayerClass().getId();
    }

    private String abilityName(FreezeTagGame game, Player player) {
        Ability ability = resolveAbility(game, player);
        return ability != null ? ability.getName() : "None";
    }

    private String abilityReady(FreezeTagGame game, Player player) {
        if (game == null) return "false";
        GamePlayer gp = game.getGamePlayer(player.getUniqueId());
        Ability ability = resolveAbility(game, player);
        if (gp == null || ability == null) return "false";
        return gp.canUseAbility(ability.getName()) ? "true" : "false";
    }

    private String abilityCooldown(FreezeTagGame game, Player player) {
        if (game == null) return "0";
        GamePlayer gp = game.getGamePlayer(player.getUniqueId());
        Ability ability = resolveAbility(game, player);
        if (gp == null || ability == null) return "0";
        return String.valueOf(gp.getRemainingCooldown(ability.getName()));
    }

    private Ability resolveAbility(FreezeTagGame game, Player player) {
        if (game == null) return null;
        GamePlayer gp = game.getGamePlayer(player.getUniqueId());
        if (gp == null || gp.getPlayerClass() == null) return null;
        return gp.getPlayerClass().getAbilityForRole(gp.getRole());
    }

    @FunctionalInterface
    private interface GpBooleanCheck {
        boolean check(GamePlayer gp);
    }

    private String boolFromGp(FreezeTagGame game, Player player, GpBooleanCheck check) {
        if (game == null) return "false";
        GamePlayer gp = game.getGamePlayer(player.getUniqueId());
        if (gp == null) return "false";
        return check.check(gp) ? "true" : "false";
    }

    @FunctionalInterface
    private interface GpIntGetter {
        int get(GamePlayer gp);
    }

    private String statFromGp(FreezeTagGame game, Player player, GpIntGetter getter) {
        if (game == null) return "0";
        GamePlayer gp = game.getGamePlayer(player.getUniqueId());
        if (gp == null) return "0";
        return String.valueOf(getter.get(gp));
    }
}
