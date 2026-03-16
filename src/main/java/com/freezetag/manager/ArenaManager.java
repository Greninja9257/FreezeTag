package com.freezetag.manager;

import com.freezetag.arena.Arena;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Manages all FreezeTag arenas — loading, saving, and CRUD operations.
 */
public class ArenaManager {

    private final JavaPlugin plugin;
    private final File arenasFolder;
    private final Map<String, Arena> arenas = new HashMap<>();
    private final Logger logger;

    public ArenaManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.arenasFolder = new File(plugin.getDataFolder(), "arenas");
        this.logger = plugin.getLogger();
        if (!arenasFolder.exists()) {
            arenasFolder.mkdirs();
        }
    }

    // -------------------------------------------------------------------------
    // Loading
    // -------------------------------------------------------------------------

    /**
     * Load all arena YAML files from the arenas directory.
     */
    public void loadAll() {
        arenas.clear();
        File[] files = arenasFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null || files.length == 0) {
            logger.info("[FreezeTag] No arenas found. Use /fta arena create <name> to create one.");
            return;
        }

        int loaded = 0;
        for (File file : files) {
            try {
                YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
                Arena arena = Arena.fromConfig(config);
                if (arena != null) {
                    arenas.put(arena.getName().toLowerCase(), arena);
                    loaded++;
                }
            } catch (Exception e) {
                logger.warning("[FreezeTag] Failed to load arena from file: " + file.getName() + " — " + e.getMessage());
            }
        }
        logger.info("[FreezeTag] Loaded " + loaded + " arena(s).");
    }

    // -------------------------------------------------------------------------
    // Saving
    // -------------------------------------------------------------------------

    /**
     * Save an arena to its YAML file.
     */
    public void saveArena(Arena arena) {
        if (arena == null) return;
        File file = new File(arenasFolder, arena.getName() + ".yml");
        YamlConfiguration config = new YamlConfiguration();
        arena.toConfig(config);
        try {
            config.save(file);
        } catch (IOException e) {
            logger.severe("[FreezeTag] Failed to save arena '" + arena.getName() + "': " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // CRUD
    // -------------------------------------------------------------------------

    /**
     * Create a new arena and save it.
     * Returns null if an arena with that name already exists.
     */
    public Arena createArena(String name) {
        if (name == null || name.isEmpty()) return null;
        String key = name.toLowerCase();
        if (arenas.containsKey(key)) return null;

        Arena arena = new Arena(name);
        arena.setDisplayName("&b" + name);
        arenas.put(key, arena);
        saveArena(arena);
        return arena;
    }

    /**
     * Delete an arena and its file.
     * Returns true if deleted, false if not found.
     */
    public boolean deleteArena(String name) {
        if (name == null) return false;
        String key = name.toLowerCase();
        Arena arena = arenas.remove(key);
        if (arena == null) return false;

        File file = new File(arenasFolder, arena.getName() + ".yml");
        if (file.exists()) {
            file.delete();
        }
        return true;
    }

    /**
     * Get an arena by name (case-insensitive).
     */
    public Arena getArena(String name) {
        if (name == null) return null;
        return arenas.get(name.toLowerCase());
    }

    /**
     * Get all loaded arenas.
     */
    public Collection<Arena> getAllArenas() {
        return new ArrayList<>(arenas.values());
    }

    /**
     * Get all enabled arenas.
     */
    public List<Arena> getEnabledArenas() {
        List<Arena> enabled = new ArrayList<>();
        for (Arena arena : arenas.values()) {
            if (arena.isEnabled()) enabled.add(arena);
        }
        return enabled;
    }

    /**
     * Check if an arena with the given name exists.
     */
    public boolean arenaExists(String name) {
        if (name == null) return false;
        return arenas.containsKey(name.toLowerCase());
    }

    // -------------------------------------------------------------------------
    // Spawn / bounds setters (with auto-save)
    // -------------------------------------------------------------------------

    public boolean setLobbySpawn(String arenaName, Location location) {
        Arena arena = getArena(arenaName);
        if (arena == null) return false;
        arena.setLobbySpawn(location);
        saveArena(arena);
        return true;
    }

    public boolean addRunnerSpawn(String arenaName, Location location) {
        Arena arena = getArena(arenaName);
        if (arena == null) return false;
        arena.addRunnerSpawn(location);
        saveArena(arena);
        return true;
    }

    public boolean addTaggerSpawn(String arenaName, Location location) {
        Arena arena = getArena(arenaName);
        if (arena == null) return false;
        arena.addTaggerSpawn(location);
        saveArena(arena);
        return true;
    }

    public boolean setBoundsMin(String arenaName, Location location) {
        Arena arena = getArena(arenaName);
        if (arena == null) return false;
        arena.setBoundsMin(location);
        saveArena(arena);
        return true;
    }

    public boolean setBoundsMax(String arenaName, Location location) {
        Arena arena = getArena(arenaName);
        if (arena == null) return false;
        arena.setBoundsMax(location);
        saveArena(arena);
        return true;
    }

    public boolean setEnabled(String arenaName, boolean enabled) {
        Arena arena = getArena(arenaName);
        if (arena == null) return false;
        arena.setEnabled(enabled);
        saveArena(arena);
        return true;
    }
}
