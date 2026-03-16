package com.freezetag.arena;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.logging.Logger;

/**
 * Represents a single FreezeTag arena with all its configuration.
 */
public class Arena {

    private final String name;
    private String displayName;
    private String worldName;
    private boolean enabled;
    private boolean voteOnly;
    private int minPlayers;
    private int maxPlayers;
    private int duration; // seconds, 0 = use global config

    private Location lobbySpawn;
    private final List<Location> runnerSpawns = new ArrayList<>();
    private final List<Location> taggerSpawns = new ArrayList<>();
    private Location boundsMin;
    private Location boundsMax;

    private static final Random RANDOM = new Random();
    private static final Logger LOGGER = Logger.getLogger("FreezeTag");

    public Arena(String name) {
        this.name = name;
        this.displayName = name;
        this.enabled = false;
        this.minPlayers = 4;
        this.maxPlayers = 16;
        this.duration = 0;
    }

    // -------------------------------------------------------------------------
    // Location helpers
    // -------------------------------------------------------------------------

    /**
     * Returns a random runner spawn location, or lobby spawn if none configured.
     */
    public Location getRandomRunnerSpawn() {
        if (runnerSpawns.isEmpty()) {
            return lobbySpawn;
        }
        return runnerSpawns.get(RANDOM.nextInt(runnerSpawns.size())).clone();
    }

    /**
     * Returns a random tagger spawn location, or lobby spawn if none configured.
     */
    public Location getRandomTaggerSpawn() {
        if (taggerSpawns.isEmpty()) {
            return lobbySpawn;
        }
        return taggerSpawns.get(RANDOM.nextInt(taggerSpawns.size())).clone();
    }

    /**
     * Returns true if the location is within the configured arena bounds.
     * If no bounds are set, always returns true.
     */
    public boolean isInBounds(Location location) {
        if (boundsMin == null || boundsMax == null) return true;
        if (location == null || location.getWorld() == null) return false;
        if (!location.getWorld().getName().equals(worldName)) return false;

        double x = location.getX();
        double y = location.getY();
        double z = location.getZ();

        double minX = Math.min(boundsMin.getX(), boundsMax.getX());
        double minY = Math.min(boundsMin.getY(), boundsMax.getY());
        double minZ = Math.min(boundsMin.getZ(), boundsMax.getZ());
        double maxX = Math.max(boundsMin.getX(), boundsMax.getX());
        double maxY = Math.max(boundsMin.getY(), boundsMax.getY());
        double maxZ = Math.max(boundsMin.getZ(), boundsMax.getZ());

        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    /**
     * Returns true if bounds are configured.
     */
    public boolean hasBounds() {
        return boundsMin != null && boundsMax != null;
    }

    /**
     * Returns true if at least a lobby spawn, one runner spawn, and one tagger spawn are set.
     */
    public boolean isFullyConfigured() {
        return lobbySpawn != null && !runnerSpawns.isEmpty() && !taggerSpawns.isEmpty();
    }

    // -------------------------------------------------------------------------
    // Serialization
    // -------------------------------------------------------------------------

    /**
     * Save arena data into a YamlConfiguration.
     */
    public void toConfig(YamlConfiguration config) {
        config.set("name", name);
        config.set("display-name", displayName);
        config.set("world", worldName);
        config.set("enabled", enabled);
        config.set("vote-only", voteOnly);
        config.set("min-players", minPlayers);
        config.set("max-players", maxPlayers);
        config.set("duration", duration);

        if (lobbySpawn != null) {
            config.set("lobby-spawn", serializeLocation(lobbySpawn));
        }

        config.set("runner-spawns", null);
        for (int i = 0; i < runnerSpawns.size(); i++) {
            config.set("runner-spawns." + i, serializeLocation(runnerSpawns.get(i)));
        }

        config.set("tagger-spawns", null);
        for (int i = 0; i < taggerSpawns.size(); i++) {
            config.set("tagger-spawns." + i, serializeLocation(taggerSpawns.get(i)));
        }

        if (boundsMin != null) {
            config.set("bounds.min", serializeBoundsLocation(boundsMin));
        }
        if (boundsMax != null) {
            config.set("bounds.max", serializeBoundsLocation(boundsMax));
        }
    }

    /**
     * Load arena data from a YamlConfiguration.
     */
    public static Arena fromConfig(YamlConfiguration config) {
        String name = config.getString("name");
        if (name == null || name.isEmpty()) {
            LOGGER.warning("[FreezeTag] Arena config missing 'name' field, skipping.");
            return null;
        }

        Arena arena = new Arena(name);
        arena.displayName = config.getString("display-name", name);
        arena.worldName = config.getString("world", "world");
        arena.enabled = config.getBoolean("enabled", false);
        arena.voteOnly = config.getBoolean("vote-only", false);
        arena.minPlayers = config.getInt("min-players", 4);
        arena.maxPlayers = config.getInt("max-players", 16);
        arena.duration = config.getInt("duration", 0);

        // Lobby spawn
        ConfigurationSection lobbySection = config.getConfigurationSection("lobby-spawn");
        if (lobbySection != null) {
            arena.lobbySpawn = deserializeLocation(lobbySection);
        }

        // Runner spawns
        ConfigurationSection runnerSection = config.getConfigurationSection("runner-spawns");
        if (runnerSection != null) {
            for (String key : runnerSection.getKeys(false)) {
                ConfigurationSection spawnSection = runnerSection.getConfigurationSection(key);
                if (spawnSection != null) {
                    Location loc = deserializeLocation(spawnSection);
                    if (loc != null) arena.runnerSpawns.add(loc);
                }
            }
        }

        // Tagger spawns
        ConfigurationSection taggerSection = config.getConfigurationSection("tagger-spawns");
        if (taggerSection != null) {
            for (String key : taggerSection.getKeys(false)) {
                ConfigurationSection spawnSection = taggerSection.getConfigurationSection(key);
                if (spawnSection != null) {
                    Location loc = deserializeLocation(spawnSection);
                    if (loc != null) arena.taggerSpawns.add(loc);
                }
            }
        }

        // Bounds
        ConfigurationSection boundsSection = config.getConfigurationSection("bounds");
        if (boundsSection != null) {
            ConfigurationSection minSection = boundsSection.getConfigurationSection("min");
            ConfigurationSection maxSection = boundsSection.getConfigurationSection("max");
            if (minSection != null) arena.boundsMin = deserializeBoundsLocation(minSection, arena.worldName);
            if (maxSection != null) arena.boundsMax = deserializeBoundsLocation(maxSection, arena.worldName);
        }

        return arena;
    }

    private static ConfigurationSection serializeLocation(Location loc) {
        YamlConfiguration section = new YamlConfiguration();
        if (loc.getWorld() != null) section.set("world", loc.getWorld().getName());
        section.set("x", loc.getX());
        section.set("y", loc.getY());
        section.set("z", loc.getZ());
        section.set("yaw", (double) loc.getYaw());
        section.set("pitch", (double) loc.getPitch());
        return section;
    }

    private static Location deserializeLocation(ConfigurationSection section) {
        String worldName = section.getString("world", "world");
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            LOGGER.warning("[FreezeTag] Could not find world '" + worldName + "' for arena location.");
            // Return location with null world — will be resolved later if world loads
            return null;
        }
        double x = section.getDouble("x", 0);
        double y = section.getDouble("y", 64);
        double z = section.getDouble("z", 0);
        float yaw = (float) section.getDouble("yaw", 0);
        float pitch = (float) section.getDouble("pitch", 0);
        return new Location(world, x, y, z, yaw, pitch);
    }

    private static ConfigurationSection serializeBoundsLocation(Location loc) {
        YamlConfiguration section = new YamlConfiguration();
        if (loc.getWorld() != null) section.set("world", loc.getWorld().getName());
        section.set("x", loc.getBlockX());
        section.set("y", loc.getBlockY());
        section.set("z", loc.getBlockZ());
        return section;
    }

    private static Location deserializeBoundsLocation(ConfigurationSection section, String defaultWorld) {
        String worldName = section.getString("world", defaultWorld);
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            LOGGER.warning("[FreezeTag] Could not find world '" + worldName + "' for arena bounds.");
            return null;
        }
        int x = section.getInt("x", 0);
        int y = section.getInt("y", 0);
        int z = section.getInt("z", 0);
        return new Location(world, x, y, z);
    }

    // -------------------------------------------------------------------------
    // Getters & setters
    // -------------------------------------------------------------------------

    public String getName() { return name; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getWorldName() { return worldName; }
    public void setWorldName(String worldName) { this.worldName = worldName; }

    public World getWorld() {
        return worldName != null ? Bukkit.getWorld(worldName) : null;
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isVoteOnly() { return voteOnly; }
    public void setVoteOnly(boolean voteOnly) { this.voteOnly = voteOnly; }

    public int getMinPlayers() { return minPlayers; }
    public void setMinPlayers(int minPlayers) { this.minPlayers = minPlayers; }

    public int getMaxPlayers() { return maxPlayers; }
    public void setMaxPlayers(int maxPlayers) { this.maxPlayers = maxPlayers; }

    public int getDuration() { return duration; }
    public void setDuration(int duration) { this.duration = duration; }

    public Location getLobbySpawn() { return lobbySpawn != null ? lobbySpawn.clone() : null; }
    public void setLobbySpawn(Location lobbySpawn) {
        this.lobbySpawn = lobbySpawn != null ? lobbySpawn.clone() : null;
        if (lobbySpawn != null && lobbySpawn.getWorld() != null) {
            this.worldName = lobbySpawn.getWorld().getName();
        }
    }

    public List<Location> getRunnerSpawns() { return new ArrayList<>(runnerSpawns); }
    public void addRunnerSpawn(Location loc) { if (loc != null) runnerSpawns.add(loc.clone()); }
    public void clearRunnerSpawns() { runnerSpawns.clear(); }

    public List<Location> getTaggerSpawns() { return new ArrayList<>(taggerSpawns); }
    public void addTaggerSpawn(Location loc) { if (loc != null) taggerSpawns.add(loc.clone()); }
    public void clearTaggerSpawns() { taggerSpawns.clear(); }

    public Location getBoundsMin() { return boundsMin != null ? boundsMin.clone() : null; }
    public void setBoundsMin(Location boundsMin) { this.boundsMin = boundsMin != null ? boundsMin.clone() : null; }

    public Location getBoundsMax() { return boundsMax != null ? boundsMax.clone() : null; }
    public void setBoundsMax(Location boundsMax) { this.boundsMax = boundsMax != null ? boundsMax.clone() : null; }
}
