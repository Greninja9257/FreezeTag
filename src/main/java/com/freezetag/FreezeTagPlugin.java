package com.freezetag;

import com.freezetag.command.FreezeTagCommand;
import com.freezetag.gui.LobbyGUI;
import com.freezetag.hook.PlaceholderHook;
import com.freezetag.listener.GameListener;
import com.freezetag.listener.LobbyListener;
import com.freezetag.manager.ArenaManager;
import com.freezetag.manager.ClassManager;
import com.freezetag.manager.GameManager;
import com.freezetag.manager.ScoreboardManager;
import com.freezetag.util.MessageUtil;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Main entry point for the FreezeTag plugin.
 */
public class FreezeTagPlugin extends JavaPlugin {

    private static FreezeTagPlugin instance;

    // Managers
    private ArenaManager arenaManager;
    private ClassManager classManager;
    private GameManager gameManager;
    private ScoreboardManager scoreboardManager;

    // GUI
    private LobbyGUI lobbyGUI;

    // Vote Lobby
    private com.freezetag.game.VoteLobby voteLobby;

    // Economy (Vault)
    private Economy economy;

    // Messages
    private FileConfiguration messagesConfig;

    @Override
    public void onEnable() {
        instance = this;

        // Save default config files
        saveDefaultConfig();
        saveDefaultResource("messages.yml");

        // Save default class files (unified — each class has runner + tagger abilities)
        saveDefaultResource("classes/default.yml");
        saveDefaultResource("classes/sprinter.yml");
        saveDefaultResource("classes/jumper.yml");
        saveDefaultResource("classes/acrobat.yml");
        saveDefaultResource("classes/tactician.yml");

        // Load messages
        loadMessages();

        // Initialize MessageUtil
        MessageUtil.init(messagesConfig, getConfig().getString("general.prefix", "&b[FreezeTag] &r"), getLogger());

        // Hook Vault economy (optional)
        setupEconomy();

        // Initialize managers
        arenaManager = new ArenaManager(this);
        classManager = new ClassManager(this);
        gameManager = new GameManager(this);
        scoreboardManager = new ScoreboardManager();
        lobbyGUI = new LobbyGUI(this);
        voteLobby = new com.freezetag.game.VoteLobby(this);
        loadVoteLobbySpawn();

        // Load data
        arenaManager.loadAll();
        classManager.loadAll();

        // Register commands
        FreezeTagCommand commandHandler = new FreezeTagCommand(this);
        registerCommand("freezetag", commandHandler);
        registerCommand("fta", commandHandler);

        // Register listeners
        getServer().getPluginManager().registerEvents(new GameListener(this), this);
        getServer().getPluginManager().registerEvents(new LobbyListener(this), this);

        // Hook PlaceholderAPI (optional)
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new PlaceholderHook(this).register();
            getLogger().info("PlaceholderAPI hooked — %freezetag_*% placeholders registered.");
        }

        lobbyGUI.startRainbowTask();

        getLogger().info("FreezeTag v" + getDescription().getVersion() + " enabled!");
        getLogger().info("Loaded " + arenaManager.getAllArenas().size() + " arena(s) and "
                + (classManager.getRunnerClasses().size() + classManager.getTaggerClasses().size()) + " class(es).");
    }

    @Override
    public void onDisable() {
        if (lobbyGUI != null) lobbyGUI.stopRainbowTask();
        if (voteLobby != null) voteLobby.shutdown();

        // Stop all active games and restore players
        if (gameManager != null) {
            gameManager.stopAllGames();
        }

        getLogger().info("FreezeTag disabled. All games stopped.");
        instance = null;
    }

    // -------------------------------------------------------------------------
    // Reload
    // -------------------------------------------------------------------------

    /**
     * Reload all configuration files and data without restarting the plugin.
     */
    public void reloadPlugin() {
        // Stop all active games first
        if (gameManager != null) {
            gameManager.stopAllGames();
        }

        // Reload config
        reloadConfig();
        loadMessages();
        MessageUtil.init(messagesConfig, getConfig().getString("general.prefix", "&b[FreezeTag] &r"), getLogger());

        // Reload managers
        if (arenaManager != null) arenaManager.loadAll();
        if (classManager != null) classManager.loadAll();

        getLogger().info("FreezeTag configuration reloaded.");
    }

    // -------------------------------------------------------------------------
    // Messages
    // -------------------------------------------------------------------------

    private void loadMessages() {
        File messagesFile = new File(getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) {
            saveDefaultResource("messages.yml");
        }
        messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);

        // Merge with defaults from jar
        InputStream defaultStream = getResource("messages.yml");
        if (defaultStream != null) {
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaultStream, StandardCharsets.UTF_8));
            messagesConfig.setDefaults(defaults);
        }
    }

    // -------------------------------------------------------------------------
    // Vault economy hook
    // -------------------------------------------------------------------------

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            if (getConfig().getBoolean("rewards.enabled", false)) {
                getLogger().warning("Vault not found! Rewards will be disabled.");
            }
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager()
                .getRegistration(Economy.class);
        if (rsp == null) {
            getLogger().warning("No economy provider found! Rewards will be disabled.");
            return false;
        }
        economy = rsp.getProvider();
        getLogger().info("Vault economy hooked: " + economy.getName());
        return true;
    }

    // -------------------------------------------------------------------------
    // Resource saving
    // -------------------------------------------------------------------------

    /**
     * Save a default resource file from the jar without overwriting if it already exists.
     */
    private void saveDefaultResource(String resourcePath) {
        File outFile = new File(getDataFolder(), resourcePath);
        if (!outFile.exists()) {
            // Create parent directories if needed
            File parent = outFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            saveResource(resourcePath, false);
        }
    }

    // -------------------------------------------------------------------------
    // Command registration helper
    // -------------------------------------------------------------------------

    private void registerCommand(String name, FreezeTagCommand handler) {
        PluginCommand cmd = getCommand(name);
        if (cmd != null) {
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        } else {
            getLogger().warning("Command '" + name + "' not found in plugin.yml!");
        }
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    public static FreezeTagPlugin getInstance() {
        return instance;
    }

    public ArenaManager getArenaManager() { return arenaManager; }
    public ClassManager getClassManager() { return classManager; }
    public GameManager getGameManager() { return gameManager; }
    public ScoreboardManager getScoreboardManager() { return scoreboardManager; }
    public LobbyGUI getLobbyGUI() { return lobbyGUI; }
    public com.freezetag.game.VoteLobby getVoteLobby() { return voteLobby; }
    public Economy getEconomy() { return economy; }
    public FileConfiguration getMessagesConfig() { return messagesConfig; }

    // -------------------------------------------------------------------------
    // Vote lobby spawn persistence
    // -------------------------------------------------------------------------

    public void saveVoteLobbySpawn(org.bukkit.Location loc) {
        if (voteLobby == null) return;
        voteLobby.setSpawn(loc);
        File dataFile = new File(getDataFolder(), "vote-lobby.yml");
        org.bukkit.configuration.file.YamlConfiguration cfg =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(dataFile);
        cfg.set("spawn.world",  loc.getWorld() != null ? loc.getWorld().getName() : "world");
        cfg.set("spawn.x",  loc.getX());
        cfg.set("spawn.y",  loc.getY());
        cfg.set("spawn.z",  loc.getZ());
        cfg.set("spawn.yaw",   loc.getYaw());
        cfg.set("spawn.pitch", loc.getPitch());
        try { cfg.save(dataFile); } catch (java.io.IOException e) {
            getLogger().warning("Could not save vote-lobby.yml: " + e.getMessage());
        }
    }

    private void loadVoteLobbySpawn() {
        File dataFile = new File(getDataFolder(), "vote-lobby.yml");
        if (!dataFile.exists()) return;
        org.bukkit.configuration.file.YamlConfiguration cfg =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(dataFile);
        String worldName = cfg.getString("spawn.world");
        if (worldName == null) return;
        org.bukkit.World world = org.bukkit.Bukkit.getWorld(worldName);
        if (world == null) return;
        double x   = cfg.getDouble("spawn.x");
        double y   = cfg.getDouble("spawn.y");
        double z   = cfg.getDouble("spawn.z");
        float  yaw   = (float) cfg.getDouble("spawn.yaw");
        float  pitch = (float) cfg.getDouble("spawn.pitch");
        voteLobby.setSpawn(new org.bukkit.Location(world, x, y, z, yaw, pitch));
        getLogger().info("Vote lobby spawn loaded.");
    }
}
