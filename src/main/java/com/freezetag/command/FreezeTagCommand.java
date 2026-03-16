package com.freezetag.command;

import com.freezetag.FreezeTagPlugin;
import com.freezetag.arena.Arena;
import com.freezetag.game.FreezeTagGame;
import com.freezetag.game.GamePlayer;
import com.freezetag.game.GameState;
import com.freezetag.game.RolePreference;
import com.freezetag.hook.WorldEditHook;
import com.freezetag.manager.GameManager;
import com.freezetag.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Handles all /freezetag (and /ft) and /fta commands.
 */
public class FreezeTagCommand implements CommandExecutor, TabCompleter {

    private final FreezeTagPlugin plugin;

    public FreezeTagCommand(FreezeTagPlugin plugin) {
        this.plugin = plugin;
    }

    // -------------------------------------------------------------------------
    // Command dispatch
    // -------------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("fta")) {
            return handleAdmin(sender, args);
        }
        return handlePlayer(sender, args);
    }

    // -------------------------------------------------------------------------
    // Player commands: /freezetag
    // -------------------------------------------------------------------------

    private boolean handlePlayer(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sendPlayerHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "join" -> {
                if (!(sender instanceof Player player)) {
                    MessageUtil.sendMessage(sender, "general.player-only");
                    return true;
                }
                if (!player.hasPermission("freezetag.play")) {
                    MessageUtil.sendMessage(sender, "general.no-permission");
                    return true;
                }
                String arenaName = args.length > 1 ? args[1] : null;
                plugin.getGameManager().joinQueue(player, arenaName, RolePreference.NONE);
            }
            case "leave" -> {
                if (!(sender instanceof Player player)) {
                    MessageUtil.sendMessage(sender, "general.player-only");
                    return true;
                }
                plugin.getGameManager().leaveQueue(player);
            }
            case "role" -> {
                if (!(sender instanceof Player player)) {
                    MessageUtil.sendMessage(sender, "general.player-only");
                    return true;
                }
                if (args.length < 2) {
                    MessageUtil.send(sender, "&cUsage: /freezetag role <runner|tagger|none>");
                    return true;
                }
                String roleStr = args[1].toLowerCase();
                RolePreference pref;
                switch (roleStr) {
                    case "runner" -> {
                        pref = RolePreference.RUNNER;
                        plugin.getGameManager().setRolePreference(player, pref);
                        MessageUtil.sendMessage(player, "role.preference-set-runner");
                    }
                    case "tagger" -> {
                        pref = RolePreference.TAGGER;
                        plugin.getGameManager().setRolePreference(player, pref);
                        MessageUtil.sendMessage(player, "role.preference-set-tagger");
                    }
                    case "none", "any" -> {
                        plugin.getGameManager().setRolePreference(player, RolePreference.NONE);
                        MessageUtil.sendMessage(player, "role.preference-cleared");
                    }
                    default -> MessageUtil.send(sender, "&cValid roles: runner, tagger, none");
                }
            }
            case "class" -> {
                if (!(sender instanceof Player player)) {
                    MessageUtil.sendMessage(sender, "general.player-only");
                    return true;
                }
                if (args.length < 2) {
                    MessageUtil.send(sender, "&cUsage: /freezetag class <classname>");
                    return true;
                }
                plugin.getGameManager().setPlayerClass(player, args[1]);
            }
            case "status" -> {
                if (!(sender instanceof Player player)) {
                    // Show all games for console
                    showAllStatus(sender);
                    return true;
                }
                showPlayerStatus(player);
            }
            case "list" -> {
                listArenas(sender);
            }
            case "votelobby", "vl" -> {
                if (!(sender instanceof Player player)) {
                    MessageUtil.sendMessage(sender, "general.player-only");
                    return true;
                }
                if (!player.hasPermission("freezetag.play")) {
                    MessageUtil.sendMessage(sender, "general.no-permission");
                    return true;
                }
                String vlSub = args.length > 1 ? args[1] : "join";
                switch (vlSub.toLowerCase()) {
                    case "join" -> {
                        com.freezetag.game.VoteLobby vl = plugin.getVoteLobby();
                        if (!vl.isConfigured()) {
                            MessageUtil.send(player, "&cVote lobby is not set up yet.");
                            return true;
                        }
                        if (vl.isInLobby(player.getUniqueId())) {
                            MessageUtil.send(player, "&cYou are already in the vote lobby.");
                            return true;
                        }
                        if (plugin.getGameManager().getPlayerGame(player.getUniqueId()) != null
                                || plugin.getGameManager().isInQueue(player.getUniqueId())) {
                            MessageUtil.send(player, "&cLeave your current game before joining the vote lobby.");
                            return true;
                        }
                        vl.addPlayer(player);
                    }
                    case "leave" -> {
                        com.freezetag.game.VoteLobby vl = plugin.getVoteLobby();
                        if (!vl.isInLobby(player.getUniqueId())) {
                            MessageUtil.send(player, "&cYou are not in the vote lobby.");
                            return true;
                        }
                        vl.removePlayer(player);
                    }
                    default -> MessageUtil.send(player, "&bUsage: /ft vl [join|leave]");
                }
            }
            case "help" -> {
                sendPlayerHelp(sender);
            }
            default -> {
                MessageUtil.sendMessage(sender, "general.unknown-command");
            }
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Admin commands: /fta
    // -------------------------------------------------------------------------

    private boolean handleAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("freezetag.admin")) {
            MessageUtil.sendMessage(sender, "general.no-permission");
            return true;
        }

        if (args.length == 0) {
            sendAdminHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "start" -> {
                if (args.length < 2) {
                    MessageUtil.send(sender, "&cUsage: /fta start <arena>");
                    return true;
                }
                String arenaName = args[1];
                if (!plugin.getArenaManager().arenaExists(arenaName)) {
                    MessageUtil.sendMessage(sender, "general.invalid-arena", Map.of("arena", arenaName));
                    return true;
                }
                FreezeTagGame existing = plugin.getGameManager().getGame(arenaName);
                if (existing != null && existing.getState() == GameState.IN_GAME) {
                    MessageUtil.sendMessage(sender, "game.game-already-running", Map.of("arena", arenaName));
                    return true;
                }
                if (plugin.getGameManager().startGame(arenaName)) {
                    MessageUtil.sendMessage(sender, "admin.game-started", Map.of("arena", arenaName));
                } else {
                    MessageUtil.send(sender, "&cFailed to start game. Check arena configuration.");
                }
            }
            case "stop" -> {
                if (args.length < 2) {
                    MessageUtil.send(sender, "&cUsage: /fta stop <arena>");
                    return true;
                }
                String arenaName = args[1];
                if (plugin.getGameManager().stopGame(arenaName)) {
                    MessageUtil.sendMessage(sender, "admin.game-stopped-admin", Map.of("arena", arenaName));
                } else {
                    MessageUtil.sendMessage(sender, "game.no-game-running", Map.of("arena", arenaName));
                }
            }
            case "reload" -> {
                plugin.reloadPlugin();
                MessageUtil.sendMessage(sender, "general.reload-success");
            }
            case "arena" -> {
                if (args.length < 2) {
                    sendArenaHelp(sender);
                    return true;
                }
                handleArenaCommand(sender, Arrays.copyOfRange(args, 1, args.length));
            }
            case "forcefreeze" -> {
                if (!(sender instanceof Player admin)) {
                    MessageUtil.sendMessage(sender, "general.player-only");
                    return true;
                }
                if (args.length < 2) {
                    MessageUtil.send(sender, "&cUsage: /fta forcefreeze <player>");
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    MessageUtil.sendMessage(sender, "general.invalid-player", Map.of("player", args[1]));
                    return true;
                }
                FreezeTagGame game = plugin.getGameManager().getPlayerGame(target.getUniqueId());
                if (game == null) {
                    MessageUtil.sendMessage(sender, "admin.player-not-in-game", Map.of("player", args[1]));
                    return true;
                }
                game.freezePlayer(admin, target);
                MessageUtil.sendMessage(sender, "admin.force-freeze", Map.of("player", args[1]));
            }
            case "forceunfreeze" -> {
                if (args.length < 2) {
                    MessageUtil.send(sender, "&cUsage: /fta forceunfreeze <player>");
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    MessageUtil.sendMessage(sender, "general.invalid-player", Map.of("player", args[1]));
                    return true;
                }
                FreezeTagGame game = plugin.getGameManager().getPlayerGame(target.getUniqueId());
                if (game == null) {
                    MessageUtil.sendMessage(sender, "admin.player-not-in-game", Map.of("player", args[1]));
                    return true;
                }
                game.unfreezePlayer(null, target);
                MessageUtil.sendMessage(sender, "admin.force-unfreeze", Map.of("player", args[1]));
            }
            case "vl", "votelobby" -> {
                if (!(sender instanceof Player player)) {
                    MessageUtil.send(sender, "&cThis command requires a player."); return true;
                }
                handleVoteLobbyCommand(player, args.length > 1 ? args[1] : "help");
            }
            default -> sendAdminHelp(sender);
        }
        return true;
    }

    private void handleVoteLobbyCommand(Player player, String sub) {
        com.freezetag.game.VoteLobby vl = plugin.getVoteLobby();
        switch (sub.toLowerCase()) {
            case "setspawn" -> {
                plugin.saveVoteLobbySpawn(player.getLocation());
                MessageUtil.send(player, "&aVote lobby spawn set to your location!");
            }
            case "join" -> {
                if (!vl.isConfigured()) {
                    MessageUtil.send(player, "&cVote lobby spawn not set. Use /fta vl setspawn first.");
                    return;
                }
                if (vl.isInLobby(player.getUniqueId())) {
                    MessageUtil.send(player, "&cYou are already in the vote lobby.");
                    return;
                }
                if (plugin.getGameManager().getPlayerGame(player.getUniqueId()) != null
                        || plugin.getGameManager().isInQueue(player.getUniqueId())) {
                    MessageUtil.send(player, "&cLeave your current game before joining the vote lobby.");
                    return;
                }
                vl.addPlayer(player);
            }
            case "leave" -> {
                if (!vl.isInLobby(player.getUniqueId())) {
                    MessageUtil.send(player, "&cYou are not in the vote lobby.");
                    return;
                }
                vl.removePlayer(player);
            }
            case "info" -> {
                MessageUtil.send(player, "&b&lVote Lobby Info");
                MessageUtil.send(player, "&7Players: &f" + vl.getPlayerCount());
                MessageUtil.send(player, "&7State: &f" + vl.getState());
                MessageUtil.send(player, "&7Configured: &f" + vl.isConfigured());
            }
            default -> {
                MessageUtil.send(player, "&b/fta vl setspawn &7— Set vote lobby spawn");
                MessageUtil.send(player, "&b/fta vl join &7— Join the vote lobby");
                MessageUtil.send(player, "&b/fta vl leave &7— Leave the vote lobby");
                MessageUtil.send(player, "&b/fta vl info &7— Show vote lobby status");
            }
        }
    }

    // -------------------------------------------------------------------------
    // Arena sub-commands
    // -------------------------------------------------------------------------

    private void handleArenaCommand(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sendArenaHelp(sender);
            return;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "create" -> {
                if (args.length < 2) { MessageUtil.send(sender, "&cUsage: /fta arena create <name>"); return; }
                String name = args[1];
                if (plugin.getArenaManager().arenaExists(name)) {
                    MessageUtil.sendMessage(sender, "arena.already-exists", Map.of("name", name));
                    return;
                }
                Arena arena = plugin.getArenaManager().createArena(name);
                if (arena != null) {
                    MessageUtil.sendMessage(sender, "arena.created", Map.of("name", name));
                }
            }
            case "delete" -> {
                if (args.length < 2) { MessageUtil.send(sender, "&cUsage: /fta arena delete <name>"); return; }
                String name = args[1];
                // Check if game is running
                FreezeTagGame game = plugin.getGameManager().getGame(name);
                if (game != null && game.getState() != GameState.WAITING) {
                    MessageUtil.sendMessage(sender, "arena.game-running", Map.of("name", name));
                    return;
                }
                if (plugin.getArenaManager().deleteArena(name)) {
                    MessageUtil.sendMessage(sender, "arena.deleted", Map.of("name", name));
                } else {
                    MessageUtil.sendMessage(sender, "general.invalid-arena", Map.of("arena", name));
                }
            }
            case "setlobby", "setspawn" -> {
                if (!(sender instanceof Player player)) { MessageUtil.sendMessage(sender, "general.player-only"); return; }
                if (args.length < 2) { MessageUtil.send(sender, "&cUsage: /fta arena setlobby <name>"); return; }
                String name = args[1];
                if (plugin.getArenaManager().setLobbySpawn(name, player.getLocation())) {
                    MessageUtil.sendMessage(sender, "arena.lobby-set", Map.of("name", name));
                } else {
                    MessageUtil.sendMessage(sender, "general.invalid-arena", Map.of("arena", name));
                }
            }
            case "addrunner" -> {
                if (!(sender instanceof Player player)) { MessageUtil.sendMessage(sender, "general.player-only"); return; }
                if (args.length < 2) { MessageUtil.send(sender, "&cUsage: /fta arena addrunner <name>"); return; }
                String name = args[1];
                if (plugin.getArenaManager().addRunnerSpawn(name, player.getLocation())) {
                    MessageUtil.sendMessage(sender, "arena.runner-spawn-added", Map.of("name", name));
                } else {
                    MessageUtil.sendMessage(sender, "general.invalid-arena", Map.of("arena", name));
                }
            }
            case "addtagger" -> {
                if (!(sender instanceof Player player)) { MessageUtil.sendMessage(sender, "general.player-only"); return; }
                if (args.length < 2) { MessageUtil.send(sender, "&cUsage: /fta arena addtagger <name>"); return; }
                String name = args[1];
                if (plugin.getArenaManager().addTaggerSpawn(name, player.getLocation())) {
                    MessageUtil.sendMessage(sender, "arena.tagger-spawn-added", Map.of("name", name));
                } else {
                    MessageUtil.sendMessage(sender, "general.invalid-arena", Map.of("arena", name));
                }
            }
            case "setboundsmin" -> {
                if (!(sender instanceof Player player)) { MessageUtil.sendMessage(sender, "general.player-only"); return; }
                if (args.length < 2) { MessageUtil.send(sender, "&cUsage: /fta arena setboundsmin <name>"); return; }
                String name = args[1];
                if (plugin.getArenaManager().setBoundsMin(name, player.getLocation())) {
                    MessageUtil.sendMessage(sender, "arena.bounds-min-set", Map.of("name", name));
                } else {
                    MessageUtil.sendMessage(sender, "general.invalid-arena", Map.of("arena", name));
                }
            }
            case "setboundsmax" -> {
                if (!(sender instanceof Player player)) { MessageUtil.sendMessage(sender, "general.player-only"); return; }
                if (args.length < 2) { MessageUtil.send(sender, "&cUsage: /fta arena setboundsmax <name>"); return; }
                String name = args[1];
                if (plugin.getArenaManager().setBoundsMax(name, player.getLocation())) {
                    MessageUtil.sendMessage(sender, "arena.bounds-max-set", Map.of("name", name));
                } else {
                    MessageUtil.sendMessage(sender, "general.invalid-arena", Map.of("arena", name));
                }
            }
            case "setbounds" -> {
                if (!(sender instanceof Player player)) { MessageUtil.sendMessage(sender, "general.player-only"); return; }
                if (args.length < 2) { MessageUtil.send(sender, "&cUsage: /fta arena setbounds <name>"); return; }
                String name = args[1];

                if (!plugin.getArenaManager().arenaExists(name)) {
                    MessageUtil.sendMessage(sender, "general.invalid-arena", Map.of("arena", name));
                    return;
                }

                if (!WorldEditHook.isAvailable()) {
                    MessageUtil.send(sender, "&cWorldEdit is not installed. Use &b/fta arena setboundsmin&c and &b/fta arena setboundsmax&c instead.");
                    return;
                }

                WorldEditHook.SelectionResult sel = WorldEditHook.getSelection(player);
                if (sel == null) {
                    MessageUtil.send(sender, "&cNo WorldEdit selection found. Make a selection with the &bwand &ctool first.");
                    return;
                }

                plugin.getArenaManager().setBoundsMin(name, sel.min());
                plugin.getArenaManager().setBoundsMax(name, sel.max());
                MessageUtil.send(sender, "&aArena bounds for &b" + name + " &aset from WorldEdit selection! ("
                        + formatLoc(sel.min()) + " &7→ &a" + formatLoc(sel.max()) + "&a)");
            }
            case "enable" -> {
                if (args.length < 2) { MessageUtil.send(sender, "&cUsage: /fta arena enable <name>"); return; }
                String name = args[1];
                if (plugin.getArenaManager().setEnabled(name, true)) {
                    MessageUtil.sendMessage(sender, "arena.enabled", Map.of("name", name));
                } else {
                    MessageUtil.sendMessage(sender, "general.invalid-arena", Map.of("arena", name));
                }
            }
            case "disable" -> {
                if (args.length < 2) { MessageUtil.send(sender, "&cUsage: /fta arena disable <name>"); return; }
                String name = args[1];
                if (plugin.getArenaManager().setEnabled(name, false)) {
                    MessageUtil.sendMessage(sender, "arena.disabled", Map.of("name", name));
                } else {
                    MessageUtil.sendMessage(sender, "general.invalid-arena", Map.of("arena", name));
                }
            }
            case "list" -> {
                listArenas(sender);
            }
            case "info" -> {
                if (args.length < 2) { MessageUtil.send(sender, "&cUsage: /fta arena info <name>"); return; }
                String name = args[1];
                Arena arena = plugin.getArenaManager().getArena(name);
                if (arena == null) {
                    MessageUtil.sendMessage(sender, "general.invalid-arena", Map.of("arena", name));
                    return;
                }
                showArenaInfo(sender, arena);
            }
            default -> sendArenaHelp(sender);
        }
    }

    // -------------------------------------------------------------------------
    // Status display
    // -------------------------------------------------------------------------

    private void showPlayerStatus(Player player) {
        MessageUtil.sendRaw(player, MessageUtil.get("status.header"));

        GameManager.QueueEntry qEntry = plugin.getGameManager().getPlayerQueue(player.getUniqueId());
        FreezeTagGame game = plugin.getGameManager().getPlayerGame(player.getUniqueId());

        if (qEntry != null) {
            if (game != null && game.getState() == GameState.IN_GAME) {
                MessageUtil.sendRaw(player, MessageUtil.get("status.your-status-game",
                        Map.of("arena", MessageUtil.colorize(game.getArena().getDisplayName()))));
            } else {
                MessageUtil.sendRaw(player, MessageUtil.get("status.your-status-queue",
                        Map.of("arena", qEntry.arenaName)));
            }

            if (game != null) {
                GamePlayer gp = game.getGamePlayer(player.getUniqueId());
                if (gp != null) {
                    String roleStr = gp.isRunner() ? "&aRunner" : gp.isTagger() ? "&cTagger" : "&7Spectator";
                    MessageUtil.sendRaw(player, MessageUtil.get("status.your-role",
                            Map.of("role", MessageUtil.colorize(roleStr))));

                    if (gp.getPlayerClass() != null) {
                        MessageUtil.sendRaw(player, MessageUtil.get("status.your-class",
                                Map.of("class", MessageUtil.colorize(gp.getPlayerClass().getDisplayName()))));
                    }

                    if (game.getState() == GameState.IN_GAME) {
                        MessageUtil.sendRaw(player, MessageUtil.get("status.game-state",
                                Map.of("state", "In Game")));
                        MessageUtil.sendRaw(player, MessageUtil.get("status.runners-alive",
                                Map.of("alive", String.valueOf(game.getAliveRunnerCount()),
                                        "total", String.valueOf(game.getTotalRunnerCount()))));
                        MessageUtil.sendRaw(player, MessageUtil.get("status.runners-frozen",
                                Map.of("frozen", String.valueOf(game.getFrozenRunnerCount()))));
                        MessageUtil.sendRaw(player, MessageUtil.get("status.taggers",
                                Map.of("taggers", String.valueOf(game.getTaggerCount()))));
                        MessageUtil.sendRaw(player, MessageUtil.get("status.time-left",
                                Map.of("time", String.valueOf(game.getTimeLeft()))));
                    }
                }
            }
        } else {
            MessageUtil.send(player, "&7You are not in a game or queue.");
        }
    }

    private void showAllStatus(CommandSender sender) {
        Collection<FreezeTagGame> games = plugin.getGameManager().getAllGames();
        if (games.isEmpty()) {
            MessageUtil.send(sender, "&7No active games.");
            return;
        }
        for (FreezeTagGame game : games) {
            MessageUtil.send(sender, "&b" + game.getArena().getName() + " &7— State: &f"
                    + game.getState() + " &7Players: &f" + game.getPlayerCount()
                    + " &7Time: &f" + MessageUtil.formatTime(game.getTimeLeft()));
        }
    }

    private void listArenas(CommandSender sender) {
        Collection<Arena> arenas = plugin.getArenaManager().getAllArenas();
        if (arenas.isEmpty()) {
            MessageUtil.sendMessage(sender, "arena.no-arenas");
            return;
        }
        MessageUtil.sendRaw(sender, MessageUtil.get("arena.list-header"));
        for (Arena arena : arenas) {
            FreezeTagGame game = plugin.getGameManager().getGame(arena.getName());
            int playerCount = game != null ? game.getPlayerCount() : 0;
            String status = arena.isEnabled() ? "&aEnabled" : "&cDisabled";
            MessageUtil.sendRaw(sender, MessageUtil.get("arena.list-entry",
                    Map.of("name", MessageUtil.colorize(arena.getDisplayName()),
                            "players", String.valueOf(playerCount),
                            "max", String.valueOf(arena.getMaxPlayers()),
                            "status", MessageUtil.colorize(status))));
        }
    }

    private void showArenaInfo(CommandSender sender, Arena arena) {
        MessageUtil.sendRaw(sender, MessageUtil.get("arena.info-header",
                Map.of("name", MessageUtil.colorize(arena.getDisplayName()))));
        MessageUtil.sendRaw(sender, MessageUtil.get("arena.info-world",
                Map.of("world", arena.getWorldName() != null ? arena.getWorldName() : "not set")));
        MessageUtil.sendRaw(sender, MessageUtil.get("arena.info-players",
                Map.of("min", String.valueOf(arena.getMinPlayers()),
                        "max", String.valueOf(arena.getMaxPlayers()))));
        MessageUtil.sendRaw(sender, MessageUtil.get("arena.info-duration",
                Map.of("duration", String.valueOf(arena.getDuration() > 0
                        ? arena.getDuration()
                        : plugin.getConfig().getInt("game.duration", 180)))));
        MessageUtil.sendRaw(sender, MessageUtil.get("arena.info-enabled",
                Map.of("status", arena.isEnabled() ? "&aYes" : "&cNo")));
        MessageUtil.sendRaw(sender, MessageUtil.get("arena.info-spawns",
                Map.of("runner_spawns", String.valueOf(arena.getRunnerSpawns().size()),
                        "tagger_spawns", String.valueOf(arena.getTaggerSpawns().size()))));
    }

    // -------------------------------------------------------------------------
    // Help messages
    // -------------------------------------------------------------------------

    private void sendPlayerHelp(CommandSender sender) {
        MessageUtil.sendRaw(sender, MessageUtil.get("help.header"));
        MessageUtil.sendRaw(sender, MessageUtil.get("help.join"));
        MessageUtil.sendRaw(sender, MessageUtil.get("help.leave"));
        MessageUtil.sendRaw(sender, MessageUtil.get("help.role"));
        MessageUtil.sendRaw(sender, MessageUtil.get("help.class"));
        MessageUtil.sendRaw(sender, MessageUtil.get("help.status"));
        MessageUtil.sendRaw(sender, MessageUtil.get("help.list"));
    }

    private void sendAdminHelp(CommandSender sender) {
        MessageUtil.sendRaw(sender, MessageUtil.get("help.admin-header"));
        MessageUtil.sendRaw(sender, MessageUtil.get("help.admin-start"));
        MessageUtil.sendRaw(sender, MessageUtil.get("help.admin-stop"));
        MessageUtil.sendRaw(sender, MessageUtil.get("help.admin-reload"));
        MessageUtil.sendRaw(sender, MessageUtil.get("help.admin-arena"));
        MessageUtil.send(sender, "&b/fta forcefreeze <player> &7- Force freeze a player");
        MessageUtil.send(sender, "&b/fta forceunfreeze <player> &7- Force unfreeze a player");
    }

    private void sendArenaHelp(CommandSender sender) {
        MessageUtil.send(sender, "&b--- Arena Management ---");
        MessageUtil.send(sender, "&b/fta arena create <name>");
        MessageUtil.send(sender, "&b/fta arena delete <name>");
        MessageUtil.send(sender, "&b/fta arena setlobby <name> &7- Set lobby spawn at your location");
        MessageUtil.send(sender, "&b/fta arena addrunner <name> &7- Add runner spawn at your location");
        MessageUtil.send(sender, "&b/fta arena addtagger <name> &7- Add tagger spawn at your location");
        MessageUtil.send(sender, "&b/fta arena setboundsmin <name> &7- Set min bound at your location");
        MessageUtil.send(sender, "&b/fta arena setboundsmax <name> &7- Set max bound at your location");
        MessageUtil.send(sender, "&b/fta arena enable/disable <name>");
        MessageUtil.send(sender, "&b/fta arena list");
        MessageUtil.send(sender, "&b/fta arena info <name>");
    }

    // -------------------------------------------------------------------------
    // Tab completion
    // -------------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (command.getName().equalsIgnoreCase("fta")) {
            if (!sender.hasPermission("freezetag.admin")) return completions;
            return getAdminTabCompletions(args);
        }

        return getPlayerTabCompletions(args);
    }

    private List<String> getPlayerTabCompletions(String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            completions.addAll(List.of("join", "leave", "role", "class", "status", "list", "help"));
        } else if (args.length == 2) {
            switch (args[0].toLowerCase()) {
                case "join" -> completions.addAll(getArenaNames());
                case "role" -> completions.addAll(List.of("runner", "tagger", "none"));
                case "class" -> completions.addAll(getClassNames());
            }
        }

        return filterStartingWith(completions, args[args.length - 1]);
    }

    private List<String> getAdminTabCompletions(String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            completions.addAll(List.of("start", "stop", "reload", "arena", "forcefreeze", "forceunfreeze"));
        } else if (args.length == 2) {
            switch (args[0].toLowerCase()) {
                case "start", "stop" -> completions.addAll(getArenaNames());
                case "arena" -> completions.addAll(List.of("create", "delete", "setlobby", "addrunner",
                        "addtagger", "setboundsmin", "setboundsmax", "enable", "disable", "list", "info"));
                case "forcefreeze", "forceunfreeze" -> completions.addAll(getOnlinePlayerNames());
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("arena")) {
            switch (args[1].toLowerCase()) {
                case "delete", "setlobby", "addrunner", "addtagger", "setboundsmin",
                     "setboundsmax", "enable", "disable", "info" -> completions.addAll(getArenaNames());
            }
        }

        return filterStartingWith(completions, args[args.length - 1]);
    }

    private List<String> getArenaNames() {
        return plugin.getArenaManager().getAllArenas().stream()
                .map(Arena::getName)
                .collect(Collectors.toList());
    }

    private List<String> getClassNames() {
        return plugin.getClassManager().getAllClasses().stream()
                .map(c -> c.getId())
                .collect(Collectors.toList());
    }

    private List<String> getOnlinePlayerNames() {
        return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .collect(Collectors.toList());
    }

    private String formatLoc(org.bukkit.Location loc) {
        return loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ();
    }

    private List<String> filterStartingWith(List<String> list, String prefix) {
        String lower = prefix.toLowerCase();
        return list.stream()
                .filter(s -> s.toLowerCase().startsWith(lower))
                .collect(Collectors.toList());
    }
}
