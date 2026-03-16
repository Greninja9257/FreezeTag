package com.freezetag.game;

import com.freezetag.FreezeTagPlugin;
import com.freezetag.arena.Arena;
import com.freezetag.classes.PlayerClass;
import com.freezetag.manager.ClassManager;
import com.freezetag.manager.ScoreboardManager;
import com.freezetag.util.MessageUtil;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Manages one active FreezeTag game session for a specific arena.
 */
public class FreezeTagGame {

    private static final Logger LOGGER = Logger.getLogger("FreezeTag");
    private static final Random RANDOM = new Random();

    private final FreezeTagPlugin plugin;
    private final Arena arena;
    private final Map<UUID, GamePlayer> players = new HashMap<>();
    private final Map<UUID, RolePreference> queuePreferences = new HashMap<>();

    private GameState state = GameState.WAITING;
    private int timeLeft = 0;
    private int lobbyCountdown = 0;

    // Tasks
    private BukkitTask mainTickTask;
    private BukkitTask freezeEnforceTask;
    private BukkitTask scoreboardUpdateTask;

    // Visuals
    private BossBar bossBar;

    // Auto-thaw tracking: uuid -> ticks remaining frozen (if freeze-duration > 0)
    private final Map<UUID, Integer> autoThawCountdown = new HashMap<>();

    public FreezeTagGame(FreezeTagPlugin plugin, Arena arena) {
        this.plugin = plugin;
        this.arena = arena;
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Begin the lobby countdown phase. Players in the queue join the game lobby.
     */
    public void startLobby() {
        if (state != GameState.WAITING) return;
        state = GameState.STARTING;

        FileConfiguration config = plugin.getConfig();
        lobbyCountdown = config.getInt("lobby.countdown", 30);

        // Set up bossbar — destroy any existing one first to prevent duplicates
        if (bossBar != null) {
            bossBar.removeAll();
            bossBar = null;
        }
        if (config.getBoolean("visuals.bossbar", true)) {
            String barColorStr = config.getString("visuals.bossbar-color", "RED");
            String barStyleStr = config.getString("visuals.bossbar-style", "SOLID");
            BarColor barColor = parseBarColor(barColorStr);
            BarStyle barStyle = parseBarStyle(barStyleStr);
            bossBar = Bukkit.createBossBar(
                    MessageUtil.colorize("&b&lFreeze Tag &f— Lobby"),
                    barColor, barStyle
            );
        }

        // Teleport players to lobby spawn
        Location lobbySpawn = arena.getLobbySpawn();
        if (lobbySpawn != null) {
            for (UUID uuid : players.keySet()) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null && p.isOnline()) {
                    p.teleport(lobbySpawn);
                }
            }
        }

        // Set players to adventure mode, clear inventory for game
        for (Map.Entry<UUID, GamePlayer> entry : players.entrySet()) {
            Player p = Bukkit.getPlayer(entry.getKey());
            if (p == null || !p.isOnline()) continue;
            GamePlayer gp = entry.getValue();
            // Only save state if addPlayer() hasn't already done so
            if (!gp.isStateSaved()) {
                gp.saveState(p);
                p.getInventory().clear();
                p.setGameMode(GameMode.ADVENTURE);
                p.setHealth(p.getMaxHealth());
                p.setFoodLevel(20);
            }
            if (bossBar != null) bossBar.addPlayer(p);
        }

        broadcastToGame("&eWaiting for game to start... &b" + lobbyCountdown + "s");

        // Open lobby GUIs if enabled
        if (config.getBoolean("lobby.gui-enabled", true)) {
            for (UUID uuid : players.keySet()) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null && p.isOnline()) {
                    plugin.getLobbyGUI().giveItems(p, this);
                }
            }
        }

        // Start lobby countdown task
        mainTickTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (state != GameState.STARTING) {
                    this.cancel();
                    return;
                }

                // Smart: pause if player count drops below minimum
                if (plugin.getConfig().getBoolean("lobby.pause-on-underpopulation", true)) {
                    int minP = arena.getMinPlayers() > 0 ? arena.getMinPlayers()
                            : plugin.getConfig().getInt("game.min-players", 4);
                    if (getPlayerCount() < minP) {
                        pauseLobby();
                        this.cancel();
                        return;
                    }
                }

                lobbyCountdown--;

                // Milestone announcements
                if (lobbyCountdown == 10 || lobbyCountdown == 5 || lobbyCountdown == 3
                        || lobbyCountdown == 2 || lobbyCountdown == 1) {
                    broadcastToGame("&eGame starting in &c" + lobbyCountdown + " &esecond"
                            + (lobbyCountdown == 1 ? "" : "s") + "!");
                    playSound("countdown-tick");
                }

                // Update bossbar
                if (bossBar != null) {
                    int lobbyMax = plugin.getConfig().getInt("lobby.countdown", 30);
                    bossBar.setProgress(Math.max(0, Math.min(1, (double) lobbyCountdown / lobbyMax)));
                    bossBar.setTitle(MessageUtil.colorize("&b&lGame starting in &f" + lobbyCountdown + "s"));
                }

                // Update scoreboards
                plugin.getScoreboardManager().updateAll(FreezeTagGame.this);

                if (lobbyCountdown <= 0) {
                    this.cancel();
                    startGame();
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    /**
     * Officially start the game — assign roles, teleport, apply classes, begin timer.
     */
    public void startGame() {
        if (state == GameState.ENDING) return;
        state = GameState.IN_GAME;

        FileConfiguration config = plugin.getConfig();
        int configDuration = arena.getDuration() > 0 ? arena.getDuration()
                : config.getInt("game.duration", 180);
        timeLeft = configDuration;

        // Assign roles
        assignRoles();

        // Teleport and prepare each player
        for (Map.Entry<UUID, GamePlayer> entry : players.entrySet()) {
            Player p = Bukkit.getPlayer(entry.getKey());
            GamePlayer gp = entry.getValue();
            if (p == null || !p.isOnline()) continue;

            preparePlayer(p, gp);
        }

        // Update bossbar
        if (bossBar != null) {
            bossBar.setTitle(MessageUtil.colorize("&c&lFreeze Tag &f— Running"));
            bossBar.setProgress(1.0);
        }

        // Create scoreboards
        for (UUID uuid : players.keySet()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                plugin.getScoreboardManager().createScoreboard(p, this);
                plugin.getScoreboardManager().setupTeams(p, this);
            }
        }

        broadcastToGame("&a&lGAME START! &7Runners must survive for &b"
                + MessageUtil.formatTime(timeLeft) + "&7!");
        playSound("game-start");

        // Cancel old task if any
        if (mainTickTask != null) {
            mainTickTask.cancel();
        }

        // Start main game tick (every second)
        mainTickTask = new BukkitRunnable() {
            @Override
            public void run() {
                tickGame();
            }
        }.runTaskTimer(plugin, 20L, 20L);

        // Start freeze enforcement (every 10 ticks = 0.5s)
        freezeEnforceTask = new BukkitRunnable() {
            @Override
            public void run() {
                enforceFreezePositions();
            }
        }.runTaskTimer(plugin, 10L, 10L);

        // Scoreboard update (every 2 seconds)
        scoreboardUpdateTask = new BukkitRunnable() {
            @Override
            public void run() {
                plugin.getScoreboardManager().updateAll(FreezeTagGame.this);
            }
        }.runTaskTimer(plugin, 40L, 40L);
    }

    private void tickGame() {
        if (state != GameState.IN_GAME) {
            return;
        }

        timeLeft--;

        // Decrement re-freeze immunity ticks
        for (GamePlayer gp : players.values()) {
            gp.decrementRefreezeImmunityTicks();
        }

        // Auto-thaw countdown
        int freezeDuration = plugin.getConfig().getInt("game.freeze-duration", 0);
        if (freezeDuration > 0) {
            List<UUID> toThaw = new ArrayList<>();
            for (Map.Entry<UUID, Integer> entry : new HashMap<>(autoThawCountdown).entrySet()) {
                int remaining = entry.getValue() - 1;
                if (remaining <= 0) {
                    toThaw.add(entry.getKey());
                } else {
                    autoThawCountdown.put(entry.getKey(), remaining);
                }
            }
            for (UUID uuid : toThaw) {
                autoThawCountdown.remove(uuid);
                Player p = Bukkit.getPlayer(uuid);
                if (p != null && p.isOnline()) {
                    GamePlayer gp = players.get(uuid);
                    if (gp != null && gp.isFrozen()) {
                        // Auto-unfreeze
                        performUnfreeze(null, p, gp);
                        broadcastToGame(MessageUtil.get("game.auto-thaw",
                                Map.of("runner", p.getName())));
                        p.sendMessage(MessageUtil.colorize(MessageUtil.getPrefix())
                                + MessageUtil.get("game.you-auto-thaw"));
                    }
                }
            }
        }

        // Timer warnings
        if (timeLeft == 60) broadcastToGame(MessageUtil.get("game.time-warning-60"));
        if (timeLeft == 30) {
            broadcastToGame(MessageUtil.get("game.time-warning-30"));
            playSound("countdown-tick");
        }
        if (timeLeft == 10) {
            broadcastToGame(MessageUtil.get("game.time-warning-10"));
            playSound("countdown-tick");
        }

        // Update bossbar
        if (bossBar != null) {
            int totalDuration = arena.getDuration() > 0 ? arena.getDuration()
                    : plugin.getConfig().getInt("game.duration", 180);
            bossBar.setProgress(Math.max(0, Math.min(1, (double) timeLeft / totalDuration)));
            bossBar.setTitle(MessageUtil.colorize("&c&lTime Left: &f" + MessageUtil.formatTime(timeLeft)));
        }

        // Win condition: all runners frozen
        if (isAllRunnersFrozen() && getTotalRunnerCount() > 0) {
            endGame("all_frozen", true);
            return;
        }

        // Win condition: time expired
        if (timeLeft <= 0) {
            endGame("time_expired", false);
        }
    }

    /**
     * End the game, announce result, restore players, give rewards.
     */
    public void endGame(String reason, boolean taggerWin) {
        if (state == GameState.ENDING) return;
        state = GameState.ENDING;

        cancelTasks();

        // Announce winner
        String winMsg;
        if (reason.equals("stopped")) {
            winMsg = MessageUtil.get("game.game-stopped");
        } else if (taggerWin) {
            winMsg = MessageUtil.get("game.taggers-win");
        } else {
            int survived = plugin.getConfig().getInt("game.duration", 180) - timeLeft;
            winMsg = MessageUtil.get("game.runners-win", Map.of("time", String.valueOf(survived)));
        }

        broadcastToGame(winMsg);
        playSound(taggerWin ? "tagger-win" : "runner-win");

        // Victory fireworks
        if (plugin.getConfig().getBoolean("visuals.victory-fireworks", true) && !reason.equals("stopped")) {
            spawnVictoryFireworks(taggerWin);
        }

        // Give rewards and show stats
        for (Map.Entry<UUID, GamePlayer> entry : players.entrySet()) {
            Player p = Bukkit.getPlayer(entry.getKey());
            GamePlayer gp = entry.getValue();
            if (p == null || !p.isOnline()) continue;

            gp.calculateSurvivalTime();
            showStats(p, gp);
            giveRewards(p, gp, taggerWin);
        }

        // Clean up bossbar
        if (bossBar != null) {
            bossBar.removeAll();
            bossBar = null;
        }

        // Remove scoreboards
        plugin.getScoreboardManager().removeAll(this);

        // Restore all players after a short delay
        new BukkitRunnable() {
            @Override
            public void run() {
                List<UUID> toRestore = new ArrayList<>(players.keySet());
                for (UUID uuid : toRestore) {
                    Player p = Bukkit.getPlayer(uuid);
                    GamePlayer gp = players.get(uuid);
                    if (p != null && p.isOnline() && gp != null) {
                        cleanupPlayer(p, gp);
                    }
                }
                players.clear();
                autoThawCountdown.clear();
                state = GameState.WAITING;

                // Notify game manager that game is done
                plugin.getGameManager().onGameEnd(arena.getName());
            }
        }.runTaskLater(plugin, 100L); // 5 second delay before restoring
    }

    // -------------------------------------------------------------------------
    // Role assignment
    // -------------------------------------------------------------------------

    /**
     * Assign Runner/Tagger roles using weighted random based on preferences and config percentage.
     */
    /**
     * Reset the lobby back to WAITING state when player count drops below minimum.
     * The countdown task self-cancels because it checks state == STARTING each tick.
     */
    private void pauseLobby() {
        state = GameState.WAITING;
        lobbyCountdown = plugin.getConfig().getInt("lobby.countdown", 30);
        // Destroy the bossbar entirely — it will be recreated fresh when countdown restarts
        if (bossBar != null) {
            bossBar.removeAll();
            bossBar = null;
        }
        broadcastToGame("&cNot enough players — countdown paused!");
        plugin.getScoreboardManager().updateAll(this);
    }

    /**
     * Called whenever a player joins during STARTING state.
     * Cuts the countdown if the arena hits a fill threshold or is completely full.
     */
    private void checkSpeedUp() {
        FileConfiguration config = plugin.getConfig();
        int maxPlayers = arena.getMaxPlayers() > 0 ? arena.getMaxPlayers()
                : config.getInt("game.max-players", 20);
        int count = getPlayerCount();

        // Full arena
        if (count >= maxPlayers) {
            if (config.getBoolean("lobby.full-instant", false)) {
                if (mainTickTask != null) mainTickTask.cancel();
                startGame();
                return;
            }
            int fullCountdown = config.getInt("lobby.full-countdown", 10);
            if (lobbyCountdown > fullCountdown) {
                lobbyCountdown = fullCountdown;
                broadcastToGame("&aArena full! Starting in &e" + fullCountdown + "s&a!");
                playSound("countdown-tick");
            }
            return;
        }

        // Speed-up threshold
        if (maxPlayers > 0) {
            int threshold = config.getInt("lobby.speed-up-threshold", 80);
            int speedUpCountdown = config.getInt("lobby.speed-up-countdown", 15);
            if ((count * 100 / maxPlayers) >= threshold && lobbyCountdown > speedUpCountdown) {
                lobbyCountdown = speedUpCountdown;
                broadcastToGame("&eAlmost full! Starting in &b" + speedUpCountdown + "s&e!");
                playSound("countdown-tick");
            }
        }
    }

    private void assignRoles() {
        FileConfiguration config = plugin.getConfig();
        int taggerPct = config.getInt("game.tagger-percentage", 25);
        boolean honorPrefs = config.getBoolean("game.honor-role-preferences", true);
        double prefWeight = config.getDouble("game.preference-weight", 2.0);

        List<UUID> playerList = new ArrayList<>(players.keySet());
        int total = playerList.size();
        int numTaggers = Math.max(1, (int) Math.ceil(total * taggerPct / 100.0));
        int numRunners = total - numTaggers;

        Set<UUID> assignedTaggers = new HashSet<>();
        Set<UUID> assignedRunners = new HashSet<>();

        if (honorPrefs) {
            // Separate players by preference
            List<UUID> wantTaggers = new ArrayList<>();
            List<UUID> wantRunners = new ArrayList<>();
            List<UUID> noPreference = new ArrayList<>();

            for (UUID uuid : playerList) {
                RolePreference pref = queuePreferences.getOrDefault(uuid, RolePreference.NONE);
                if (pref == RolePreference.TAGGER) wantTaggers.add(uuid);
                else if (pref == RolePreference.RUNNER) wantRunners.add(uuid);
                else noPreference.add(uuid);
            }

            // Assign taggers first — fill from wantTaggers, then others
            Collections.shuffle(wantTaggers, RANDOM);
            Collections.shuffle(wantRunners, RANDOM);
            Collections.shuffle(noPreference, RANDOM);

            for (UUID uuid : wantTaggers) {
                if (assignedTaggers.size() < numTaggers) assignedTaggers.add(uuid);
                else wantRunners.add(uuid);
            }
            for (UUID uuid : noPreference) {
                if (assignedTaggers.size() < numTaggers) assignedTaggers.add(uuid);
                else wantRunners.add(uuid);
            }
            for (UUID uuid : wantRunners) {
                if (assignedTaggers.size() < numTaggers) assignedTaggers.add(uuid);
                else assignedRunners.add(uuid);
            }

            // Any remaining unassigned become runners
            for (UUID uuid : playerList) {
                if (!assignedTaggers.contains(uuid) && !assignedRunners.contains(uuid)) {
                    assignedRunners.add(uuid);
                }
            }
        } else {
            // Pure random
            Collections.shuffle(playerList, RANDOM);
            for (int i = 0; i < playerList.size(); i++) {
                if (i < numTaggers) assignedTaggers.add(playerList.get(i));
                else assignedRunners.add(playerList.get(i));
            }
        }

        // Set roles in GamePlayer objects
        for (UUID uuid : assignedTaggers) {
            GamePlayer gp = players.get(uuid);
            if (gp != null) gp.setRole(RolePreference.TAGGER);
        }
        for (UUID uuid : assignedRunners) {
            GamePlayer gp = players.get(uuid);
            if (gp != null) gp.setRole(RolePreference.RUNNER);
        }
    }

    // -------------------------------------------------------------------------
    // Player preparation
    // -------------------------------------------------------------------------

    private void preparePlayer(Player player, GamePlayer gp) {
        // Clear effects and inventory
        for (PotionEffect effect : player.getActivePotionEffects()) {
            player.removePotionEffect(effect.getType());
        }
        player.getInventory().clear();
        player.setHealth(player.getMaxHealth());
        player.setFoodLevel(20);
        player.setExp(0);
        player.setLevel(0);
        player.setGameMode(GameMode.ADVENTURE);
        player.setFreezeTicks(0);
        gp.setGameJoinTime(System.currentTimeMillis());

        // Assign class
        assignClass(player, gp);

        // Teleport to spawn
        Location spawn;
        if (gp.isRunner()) {
            spawn = arena.getRandomRunnerSpawn();
        } else {
            spawn = arena.getRandomTaggerSpawn();
        }
        if (spawn != null) {
            player.teleport(spawn);
        }

        // Apply class (use role-specific ability)
        if (gp.getPlayerClass() != null) {
            gp.getPlayerClass().applyToPlayer(player, gp.getRole());
        }

        // Apply role armor
        applyRoleArmor(player, gp);

        // Notify player of their role
        String roleKey = gp.isRunner() ? "game.role-assigned-runner" : "game.role-assigned-tagger";
        player.sendMessage(MessageUtil.colorize(MessageUtil.getPrefix()) + MessageUtil.get(roleKey));

        String classDisplay = gp.getPlayerClass() != null
                ? MessageUtil.colorize(gp.getPlayerClass().getDisplayName()) : "None";
        player.sendMessage(MessageUtil.colorize(MessageUtil.getPrefix())
                + MessageUtil.get("game.class-assigned", Map.of("class", classDisplay)));
    }

    private void assignClass(Player player, GamePlayer gp) {
        ClassManager classManager = plugin.getClassManager();
        FileConfiguration config = plugin.getConfig();

        // Use pre-selected class from lobby if available
        if (gp.getPlayerClass() != null) return;

        // Assign default or random class
        String defaultId = config.getString("classes.default-runner-class", "default");
        PlayerClass pc = classManager.getClass(defaultId);
        if (pc == null) pc = classManager.getDefaultClass();
        if (pc == null && config.getBoolean("classes.random-if-unselected", true)) {
            pc = classManager.getRandomClass();
        }
        gp.setPlayerClass(pc);
    }

    private void applyRoleArmor(Player player, GamePlayer gp) {
        if (!plugin.getConfig().getBoolean("visuals.colored-armor", true)) return;

        Color color;
        if (gp.isRunner()) {
            color = Color.fromRGB(0, 200, 0); // Green
        } else if (gp.isTagger()) {
            color = Color.fromRGB(200, 0, 0); // Red
        } else {
            color = Color.fromRGB(100, 100, 100); // Gray
        }

        player.getInventory().setHelmet(createLeatherPiece(Material.LEATHER_HELMET, color));
        player.getInventory().setChestplate(createLeatherPiece(Material.LEATHER_CHESTPLATE, color));
        player.getInventory().setLeggings(createLeatherPiece(Material.LEATHER_LEGGINGS, color));
        player.getInventory().setBoots(createLeatherPiece(Material.LEATHER_BOOTS, color));
    }

    private void applyFrozenArmor(Player player) {
        if (!plugin.getConfig().getBoolean("visuals.colored-armor", true)) return;
        Color color = Color.fromRGB(0, 200, 255); // Aqua
        player.getInventory().setHelmet(createLeatherPiece(Material.LEATHER_HELMET, color));
        player.getInventory().setChestplate(createLeatherPiece(Material.LEATHER_CHESTPLATE, color));
        player.getInventory().setLeggings(createLeatherPiece(Material.LEATHER_LEGGINGS, color));
        player.getInventory().setBoots(createLeatherPiece(Material.LEATHER_BOOTS, color));
    }

    private ItemStack createLeatherPiece(Material material, Color color) {
        ItemStack item = new ItemStack(material);
        LeatherArmorMeta meta = (LeatherArmorMeta) item.getItemMeta();
        if (meta != null) {
            meta.setColor(color);
            item.setItemMeta(meta);
        }
        return item;
    }

    // -------------------------------------------------------------------------
    // Freeze mechanics
    // -------------------------------------------------------------------------

    /**
     * Freeze a runner (called when tagger hits runner, or FREEZE_AOE triggers).
     * @param tagger The player doing the freezing (can be null for admin/AOE)
     * @param runner The player being frozen
     */
    public void freezePlayer(Player tagger, Player runner) {
        if (runner == null) return;
        GamePlayer gp = players.get(runner.getUniqueId());
        if (gp == null || !gp.isRunner() || gp.isFrozen()) return;

        // Check re-freeze immunity
        if (gp.hasRefreezeImmunity()) return;

        // Perform freeze
        gp.freeze(runner.getLocation());

        // Visual: freeze effect
        runner.setFreezeTicks(Integer.MAX_VALUE);
        runner.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, Integer.MAX_VALUE, 10, false, false, false));
        runner.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, Integer.MAX_VALUE, -2, false, false, false));

        // Frozen armor
        applyFrozenArmor(runner);

        // Particles
        if (plugin.getConfig().getBoolean("visuals.freeze-particles", true)) {
            runner.getWorld().spawnParticle(Particle.SNOWFLAKE, runner.getLocation().add(0, 1, 0),
                    30, 0.3, 0.5, 0.3, 0.05);
            runner.getWorld().spawnParticle(Particle.ITEM_SNOWBALL, runner.getLocation().add(0, 1, 0),
                    15, 0.3, 0.5, 0.3, 0.1);
        }

        // Sound
        playSound("freeze");

        // Start auto-thaw countdown if configured
        int freezeDuration = plugin.getConfig().getInt("game.freeze-duration", 0);
        if (freezeDuration > 0) {
            autoThawCountdown.put(runner.getUniqueId(), freezeDuration);
        }

        // Update tagger stats
        if (tagger != null) {
            GamePlayer taggerGp = players.get(tagger.getUniqueId());
            if (taggerGp != null) taggerGp.addTagCount();
        }

        // Broadcast
        String taggerName = tagger != null ? tagger.getName() : "Unknown";
        broadcastToGame(MessageUtil.get("game.player-frozen",
                Map.of("runner", runner.getName(), "tagger", taggerName)));
        runner.sendMessage(MessageUtil.colorize(MessageUtil.getPrefix()) + MessageUtil.get("game.you-are-frozen"));

        // Update scoreboards
        plugin.getScoreboardManager().updateAll(this);
        for (UUID uuid : players.keySet()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) plugin.getScoreboardManager().setupTeams(p, this);
        }

        // Rewards for freezing
        if (tagger != null) {
            giveInstantReward(tagger, "freeze");
        }

        // Check win condition
        if (isAllRunnersFrozen() && getTotalRunnerCount() > 0) {
            endGame("all_frozen", true);
        }
    }

    /**
     * Unfreeze a runner (called when teammate hits frozen runner, or UNFREEZE_AOE triggers).
     * @param rescuer The player doing the rescuing (can be null for admin/auto-thaw)
     * @param frozen The frozen player to unfreeze
     */
    public void unfreezePlayer(Player rescuer, Player frozen) {
        if (frozen == null) return;
        GamePlayer gp = players.get(frozen.getUniqueId());
        if (gp == null || !gp.isFrozen()) return;

        performUnfreeze(rescuer, frozen, gp);

        // Stats for rescuer
        if (rescuer != null) {
            GamePlayer rescuerGp = players.get(rescuer.getUniqueId());
            if (rescuerGp != null) {
                rescuerGp.addUnfreezeCount();
                giveInstantReward(rescuer, "rescue");
            }
        }

        // Broadcast
        String rescuerName = rescuer != null ? rescuer.getName() : "Auto-thaw";
        broadcastToGame(MessageUtil.get("game.player-unfrozen",
                Map.of("runner", frozen.getName(), "rescuer", rescuerName)));
        frozen.sendMessage(MessageUtil.colorize(MessageUtil.getPrefix()) + MessageUtil.get("game.you-are-unfrozen"));

        // Particles
        if (plugin.getConfig().getBoolean("visuals.unfreeze-particles", true)) {
            frozen.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, frozen.getLocation().add(0, 1, 0),
                    20, 0.3, 0.5, 0.3, 0.1);
        }

        playSound("unfreeze");

        // Update scoreboards
        plugin.getScoreboardManager().updateAll(this);
        for (UUID uuid : players.keySet()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) plugin.getScoreboardManager().setupTeams(p, this);
        }
    }

    private void performUnfreeze(Player rescuer, Player frozen, GamePlayer gp) {
        gp.unfreeze();

        // Remove freeze ticks and effects
        frozen.setFreezeTicks(0);
        frozen.removePotionEffect(PotionEffectType.SLOWNESS);
        frozen.removePotionEffect(PotionEffectType.JUMP_BOOST);

        // Re-apply class effects (use role-specific ability)
        if (gp.getPlayerClass() != null) {
            gp.getPlayerClass().applyToPlayer(frozen, gp.getRole());
        }

        // Restore runner-colored armor
        applyRoleArmor(frozen, gp);

        // Set re-freeze immunity
        int immunitySecs = plugin.getConfig().getInt("game.re-freeze-cooldown", 3);
        gp.setRefreezeImmunityTicks(immunitySecs * 20);

        // Remove from auto-thaw
        autoThawCountdown.remove(frozen.getUniqueId());
    }

    /**
     * Enforce frozen players' positions — teleport them back if they've drifted.
     */
    private void enforceFreezePositions() {
        for (Map.Entry<UUID, GamePlayer> entry : players.entrySet()) {
            GamePlayer gp = entry.getValue();
            if (!gp.isFrozen()) continue;

            Player p = Bukkit.getPlayer(entry.getKey());
            if (p == null || !p.isOnline()) continue;

            Location frozenLoc = gp.getFreezeLocation();
            if (frozenLoc == null) {
                gp.setFreezeLocation(p.getLocation());
                continue;
            }

            Location current = p.getLocation();
            if (frozenLoc.getWorld() == null || current.getWorld() == null) continue;
            if (!frozenLoc.getWorld().equals(current.getWorld())) continue;

            // Check XZ distance (not Y, as server may push them slightly)
            double distSq = Math.pow(current.getX() - frozenLoc.getX(), 2)
                    + Math.pow(current.getZ() - frozenLoc.getZ(), 2);

            if (distSq > 0.25) { // more than 0.5 blocks
                // Preserve pitch/yaw
                frozenLoc.setYaw(current.getYaw());
                frozenLoc.setPitch(current.getPitch());
                p.teleport(frozenLoc);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Player management
    // -------------------------------------------------------------------------

    /**
     * Add a player to this game (in WAITING or STARTING state).
     */
    public boolean addPlayer(Player player, RolePreference preference) {
        if (player == null) return false;
        if (state == GameState.IN_GAME || state == GameState.ENDING) {
            // Late join as spectator
            addSpectator(player);
            return true;
        }

        FileConfiguration config = plugin.getConfig();
        int maxPlayers = arena.getMaxPlayers() > 0 ? arena.getMaxPlayers()
                : config.getInt("game.max-players", 20);

        if (players.size() >= maxPlayers) return false;

        GamePlayer gp = new GamePlayer(player.getUniqueId());
        gp.saveState(player);
        players.put(player.getUniqueId(), gp);
        queuePreferences.put(player.getUniqueId(), preference != null ? preference : RolePreference.NONE);

        // Teleport to lobby spawn and set up the player
        player.getInventory().clear();
        player.setGameMode(GameMode.ADVENTURE);
        player.setHealth(player.getMaxHealth());
        player.setFoodLevel(20);

        Location lobbySpawn = arena.getLobbySpawn();
        if (lobbySpawn != null) player.teleport(lobbySpawn);

        if (bossBar != null) bossBar.addPlayer(player);

        // Always give hotbar items and scoreboard immediately on join
        if (plugin.getConfig().getBoolean("lobby.gui-enabled", true)) {
            plugin.getLobbyGUI().giveItems(player, this);
        }
        plugin.getScoreboardManager().createScoreboard(player, this);

        // Smart speed-up if we just hit a fill threshold
        if (state == GameState.STARTING) {
            checkSpeedUp();
        }

        // Refresh scoreboard for all other waiting players so player count updates
        for (UUID uid : players.keySet()) {
            if (uid.equals(player.getUniqueId())) continue;
            Player other = Bukkit.getPlayer(uid);
            if (other != null && other.isOnline()) {
                plugin.getScoreboardManager().updateScoreboard(other, this);
            }
        }

        return true;
    }

    /**
     * Add a late joiner as spectator.
     */
    public void addSpectator(Player player) {
        if (player == null) return;
        GamePlayer gp = new GamePlayer(player.getUniqueId());
        gp.setRole(RolePreference.NONE);
        gp.saveState(player);
        players.put(player.getUniqueId(), gp);

        player.setGameMode(GameMode.SPECTATOR);
        Location lobbySpawn = arena.getLobbySpawn();
        if (lobbySpawn != null) player.teleport(lobbySpawn);

        player.sendMessage(MessageUtil.colorize(MessageUtil.getPrefix())
                + MessageUtil.get("game.spectator-join"));
        if (bossBar != null) bossBar.addPlayer(player);
        plugin.getScoreboardManager().createScoreboard(player, this);
    }

    /**
     * Remove a player from the game and restore their state.
     */
    public void removePlayer(Player player) {
        if (player == null) return;
        GamePlayer gp = players.remove(player.getUniqueId());
        queuePreferences.remove(player.getUniqueId());
        autoThawCountdown.remove(player.getUniqueId());

        if (gp != null) {
            // Remove class modifiers
            if (gp.getPlayerClass() != null) {
                gp.getPlayerClass().removeFromPlayer(player);
            }
        }

        if (bossBar != null) bossBar.removePlayer(player);
        plugin.getScoreboardManager().removeScoreboard(player);

        // Restore their state
        if (gp != null) {
            cleanupPlayer(player, gp);
        }

        player.sendMessage(MessageUtil.colorize(MessageUtil.getPrefix()) + MessageUtil.get("game.leave-game"));

        // Smart: pause countdown if we drop below minimum mid-lobby
        if (state == GameState.STARTING
                && plugin.getConfig().getBoolean("lobby.pause-on-underpopulation", true)) {
            int minP = arena.getMinPlayers() > 0 ? arena.getMinPlayers()
                    : plugin.getConfig().getInt("game.min-players", 4);
            if (getPlayerCount() < minP) {
                pauseLobby();
            }
        }

        // Check if game should continue
        if (state == GameState.IN_GAME) {
            if (getTotalRunnerCount() == 0 || (isAllRunnersFrozen() && getTotalRunnerCount() > 0)) {
                endGame("no_runners", true);
            } else if (getTaggerCount() == 0) {
                endGame("no_taggers", false);
            }
        }
    }

    private void cleanupPlayer(Player player, GamePlayer gp) {
        // Remove freeze effects
        player.setFreezeTicks(0);
        player.removePotionEffect(PotionEffectType.SLOWNESS);
        player.removePotionEffect(PotionEffectType.JUMP_BOOST);
        player.removePotionEffect(PotionEffectType.SPEED);
        player.removePotionEffect(PotionEffectType.INVISIBILITY);
        player.removePotionEffect(PotionEffectType.RESISTANCE);
        player.removePotionEffect(PotionEffectType.NIGHT_VISION);

        // Remove class modifiers
        if (gp.getPlayerClass() != null) {
            gp.getPlayerClass().removeFromPlayer(player);
        }

        // Restore saved state (inventory, gamemode, health, etc.)
        gp.restoreState(player);

        // Always teleport to world spawn when leaving a game
        org.bukkit.World world = player.getWorld();
        if (world == null) world = Bukkit.getWorlds().get(0);
        if (world != null) {
            player.teleport(world.getSpawnLocation());
        }
    }

    // -------------------------------------------------------------------------
    // Win condition checks
    // -------------------------------------------------------------------------

    /**
     * Returns true if all (non-spectator) runners are frozen.
     */
    public boolean isAllRunnersFrozen() {
        boolean hasActiveRunner = false;
        for (GamePlayer gp : players.values()) {
            if (gp.isRunner()) {
                if (!gp.isFrozen()) return false;
                hasActiveRunner = true;
            }
        }
        return hasActiveRunner;
    }

    // -------------------------------------------------------------------------
    // Rewards and stats
    // -------------------------------------------------------------------------

    private void showStats(Player player, GamePlayer gp) {
        player.sendMessage(MessageUtil.colorize(MessageUtil.getPrefix()) + MessageUtil.get("game.stats-header"));
        if (gp.isRunner()) {
            player.sendMessage(MessageUtil.colorize(MessageUtil.getPrefix())
                    + MessageUtil.get("game.stats-freezes", Map.of("freezes", String.valueOf(gp.getFreezeCount()))));
            player.sendMessage(MessageUtil.colorize(MessageUtil.getPrefix())
                    + MessageUtil.get("game.stats-survival", Map.of("time", String.valueOf(gp.getSurvivalTime()))));
        } else if (gp.isTagger()) {
            player.sendMessage(MessageUtil.colorize(MessageUtil.getPrefix())
                    + MessageUtil.get("game.stats-tags", Map.of("tags", String.valueOf(gp.getTagCount()))));
        }
        player.sendMessage(MessageUtil.colorize(MessageUtil.getPrefix())
                + MessageUtil.get("game.stats-rescues", Map.of("rescues", String.valueOf(gp.getUnfreezeCount()))));
    }

    private void giveRewards(Player player, GamePlayer gp, boolean taggerWin) {
        if (!plugin.getConfig().getBoolean("rewards.enabled", false)) return;
        Economy economy = plugin.getEconomy();
        if (economy == null) return;

        double amount = 0;
        boolean won = (gp.isRunner() && !taggerWin) || (gp.isTagger() && taggerWin);

        if (won) {
            String key = gp.isRunner() ? "rewards.runner-win" : "rewards.tagger-win";
            amount = plugin.getConfig().getDouble(key, 100);
        }

        if (gp.isRunner()) {
            amount += plugin.getConfig().getDouble("rewards.survive", 50)
                    * ((double) gp.getSurvivalTime() / 60.0); // per minute
        }

        if (amount > 0) {
            economy.depositPlayer(player, amount);
            player.sendMessage(MessageUtil.colorize(MessageUtil.getPrefix())
                    + MessageUtil.get("game.reward-earned", Map.of("amount", String.valueOf((int) amount))));
        }
    }

    private void giveInstantReward(Player player, String type) {
        if (!plugin.getConfig().getBoolean("rewards.enabled", false)) return;
        Economy economy = plugin.getEconomy();
        if (economy == null) return;

        double amount = plugin.getConfig().getDouble("rewards." + type, 0);
        if (amount > 0) {
            economy.depositPlayer(player, amount);
        }
    }

    // -------------------------------------------------------------------------
    // Fireworks
    // -------------------------------------------------------------------------

    private void spawnVictoryFireworks(boolean taggerWin) {
        // Spawn fireworks at runner/tagger spawn locations
        List<Location> locations = taggerWin
                ? arena.getTaggerSpawns()
                : arena.getRunnerSpawns();

        if (locations.isEmpty() && arena.getLobbySpawn() != null) {
            locations = List.of(arena.getLobbySpawn());
        }

        Color color1 = taggerWin ? Color.RED : Color.GREEN;
        Color color2 = taggerWin ? Color.ORANGE : Color.LIME;

        final List<Location> finalLocations = locations;
        for (int i = 0; i < 3; i++) {
            final int delay = i * 10;
            new BukkitRunnable() {
                @Override
                public void run() {
                    for (Location loc : finalLocations) {
                        if (loc == null || loc.getWorld() == null) continue;
                        Firework fw = (Firework) loc.getWorld().spawnEntity(
                                loc.clone().add(0, 1, 0), EntityType.FIREWORK_ROCKET);
                        FireworkMeta meta = fw.getFireworkMeta();
                        meta.addEffect(FireworkEffect.builder()
                                .withColor(color1, color2)
                                .with(FireworkEffect.Type.BALL_LARGE)
                                .withFlicker()
                                .build());
                        meta.setPower(1);
                        fw.setFireworkMeta(meta);
                    }
                }
            }.runTaskLater(plugin, delay);
        }
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    public void broadcastToGame(String message) {
        String formatted = MessageUtil.colorize(MessageUtil.getPrefix()) + MessageUtil.colorize(message);
        for (UUID uuid : players.keySet()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                p.sendMessage(formatted);
            }
        }
    }

    private void playSound(String configKey) {
        String soundName = plugin.getConfig().getString("sounds." + configKey, "");
        if (soundName.isEmpty()) return;

        Sound sound;
        try {
            sound = Sound.valueOf(soundName.toUpperCase());
        } catch (IllegalArgumentException e) {
            return; // Invalid sound name — silently ignore
        }

        for (UUID uuid : players.keySet()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                p.playSound(p.getLocation(), sound, 1.0f, 1.0f);
            }
        }
    }

    private void cancelTasks() {
        if (mainTickTask != null) { mainTickTask.cancel(); mainTickTask = null; }
        if (freezeEnforceTask != null) { freezeEnforceTask.cancel(); freezeEnforceTask = null; }
        if (scoreboardUpdateTask != null) { scoreboardUpdateTask.cancel(); scoreboardUpdateTask = null; }
    }

    // -------------------------------------------------------------------------
    // Player queries
    // -------------------------------------------------------------------------

    public Map<UUID, GamePlayer> getGamePlayers() {
        return Collections.unmodifiableMap(players);
    }

    public GamePlayer getGamePlayer(UUID uuid) {
        return players.get(uuid);
    }

    public boolean hasPlayer(UUID uuid) {
        return players.containsKey(uuid);
    }

    public List<Player> getOnlinePlayers() {
        List<Player> online = new ArrayList<>();
        for (UUID uuid : players.keySet()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) online.add(p);
        }
        return online;
    }

    public List<Player> getRunners() {
        List<Player> runners = new ArrayList<>();
        for (Map.Entry<UUID, GamePlayer> entry : players.entrySet()) {
            if (entry.getValue().isRunner()) {
                Player p = Bukkit.getPlayer(entry.getKey());
                if (p != null && p.isOnline()) runners.add(p);
            }
        }
        return runners;
    }

    public List<Player> getTaggers() {
        List<Player> taggers = new ArrayList<>();
        for (Map.Entry<UUID, GamePlayer> entry : players.entrySet()) {
            if (entry.getValue().isTagger()) {
                Player p = Bukkit.getPlayer(entry.getKey());
                if (p != null && p.isOnline()) taggers.add(p);
            }
        }
        return taggers;
    }

    public List<Player> getFrozenPlayers() {
        List<Player> frozen = new ArrayList<>();
        for (Map.Entry<UUID, GamePlayer> entry : players.entrySet()) {
            if (entry.getValue().isFrozen()) {
                Player p = Bukkit.getPlayer(entry.getKey());
                if (p != null && p.isOnline()) frozen.add(p);
            }
        }
        return frozen;
    }

    public int getTotalRunnerCount() {
        int count = 0;
        for (GamePlayer gp : players.values()) {
            if (gp.isRunner()) count++;
        }
        return count;
    }

    public int getAliveRunnerCount() {
        int count = 0;
        for (GamePlayer gp : players.values()) {
            if (gp.isRunner() && !gp.isFrozen()) count++;
        }
        return count;
    }

    public int getFrozenRunnerCount() {
        int count = 0;
        for (GamePlayer gp : players.values()) {
            if (gp.isRunner() && gp.isFrozen()) count++;
        }
        return count;
    }

    public int getTaggerCount() {
        int count = 0;
        for (GamePlayer gp : players.values()) {
            if (gp.isTagger()) count++;
        }
        return count;
    }

    public int getPlayerCount() {
        return players.size();
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    public Arena getArena() { return arena; }
    public GameState getState() { return state; }
    public int getTimeLeft() { return timeLeft; }
    public int getLobbyCountdown() { return lobbyCountdown; }

    // -------------------------------------------------------------------------
    // Enum parsers
    // -------------------------------------------------------------------------

    private BarColor parseBarColor(String s) {
        try { return BarColor.valueOf(s.toUpperCase()); } catch (Exception e) { return BarColor.RED; }
    }

    private BarStyle parseBarStyle(String s) {
        try { return BarStyle.valueOf(s.toUpperCase()); } catch (Exception e) { return BarStyle.SOLID; }
    }
}
