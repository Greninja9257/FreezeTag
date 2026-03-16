package com.freezetag.manager;

import com.freezetag.classes.Ability;
import com.freezetag.classes.AbilityType;
import com.freezetag.classes.PlayerClass;
import com.freezetag.game.RolePreference;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Loads and manages all player class definitions from YAML files.
 * Classes are stored in a single "classes/" directory; each class can define
 * separate runner and tagger abilities via "ability-runner" and "ability-tagger" sections.
 */
public class ClassManager {

    private final JavaPlugin plugin;
    private final Logger logger;
    private final Map<String, PlayerClass> allClasses = new HashMap<>();

    public ClassManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    // -------------------------------------------------------------------------
    // Loading
    // -------------------------------------------------------------------------

    /**
     * Load all class files from the "classes/" directory inside the plugin data folder.
     * Also checks the legacy "classes/runner/" and "classes/tagger/" directories for backward compat.
     */
    public void loadAll() {
        allClasses.clear();

        File classesDir = new File(plugin.getDataFolder(), "classes");

        // Load from unified directory
        loadClassesFromDirectory(classesDir);

        logger.info("[FreezeTag] Loaded " + allClasses.size() + " class(es).");
    }

    private void loadClassesFromDirectory(File dir) {
        if (!dir.exists()) {
            dir.mkdirs();
            return;
        }

        File[] files = dir.listFiles((d, name) -> name.endsWith(".yml") && new File(d, name).isFile());
        if (files != null) {
            for (File file : files) {
                try {
                    String id = file.getName().replace(".yml", "").toLowerCase();
                    YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
                    PlayerClass playerClass = loadClass(id, config);
                    if (playerClass != null) {
                        allClasses.put(id, playerClass);
                    }
                } catch (Exception e) {
                    logger.warning("[FreezeTag] Failed to load class from " + file.getName() + ": " + e.getMessage());
                }
            }
        }

        // Recurse into runner/ and tagger/ subdirectories for backward compatibility,
        // but don't override classes already loaded from the root
        File[] subdirs = dir.listFiles(File::isDirectory);
        if (subdirs != null) {
            for (File subdir : subdirs) {
                File[] subfiles = subdir.listFiles((d, name) -> name.endsWith(".yml"));
                if (subfiles == null) continue;
                for (File file : subfiles) {
                    String id = file.getName().replace(".yml", "").toLowerCase();
                    if (allClasses.containsKey(id)) continue; // Don't overwrite unified class
                    try {
                        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
                        PlayerClass playerClass = loadClass(id, config);
                        if (playerClass != null) {
                            allClasses.put(id + "_" + subdir.getName(), playerClass);
                        }
                    } catch (Exception e) {
                        logger.warning("[FreezeTag] Failed to load class from " + subdir.getName() + "/" + file.getName() + ": " + e.getMessage());
                    }
                }
            }
        }
    }

    private PlayerClass loadClass(String id, YamlConfiguration config) {
        PlayerClass pc = new PlayerClass(id);

        pc.setName(config.getString("name", id));
        pc.setDisplayName(config.getString("display-name", pc.getName()));
        pc.setDescription(config.getStringList("description"));

        // Display item
        String itemStr = config.getString("display-item", "PAPER");
        try {
            pc.setDisplayItem(Material.valueOf(itemStr.toUpperCase()));
        } catch (IllegalArgumentException e) {
            logger.warning("[FreezeTag] Invalid display-item '" + itemStr + "' in class " + id + ", using PAPER.");
            pc.setDisplayItem(Material.PAPER);
        }

        pc.setSpeedModifier(config.getDouble("speed-modifier", 0.0));
        pc.setJumpModifier(config.getDouble("jump-modifier", 0.0));
        pc.setMaxCount(config.getInt("max-count", 0));

        // Potion effects
        List<PotionEffect> effects = new ArrayList<>();
        List<Map<?, ?>> effectList = config.getMapList("potion-effects");
        for (Map<?, ?> effectMap : effectList) {
            try {
                String typeStr = String.valueOf(effectMap.get("type"));
                PotionEffectType effectType = PotionEffectType.getByName(typeStr.toUpperCase());
                if (effectType == null) {
                    logger.warning("[FreezeTag] Unknown potion effect type: " + typeStr + " in class " + id);
                    continue;
                }
                int amplifier = effectMap.containsKey("amplifier") ? Integer.parseInt(String.valueOf(effectMap.get("amplifier"))) : 0;
                int durationTicks = effectMap.containsKey("duration")
                        ? parsePotionDuration(Integer.parseInt(String.valueOf(effectMap.get("duration"))))
                        : Integer.MAX_VALUE;
                effects.add(new PotionEffect(effectType, durationTicks, amplifier, false, false, true));
            } catch (Exception e) {
                logger.warning("[FreezeTag] Error loading potion effect in class " + id + ": " + e.getMessage());
            }
        }
        pc.setPotionEffects(effects);

        // Runner ability
        ConfigurationSection runnerAbilitySection = config.getConfigurationSection("ability-runner");
        if (runnerAbilitySection != null && runnerAbilitySection.getBoolean("enabled", true)) {
            pc.setRunnerAbility(loadAbility(id, runnerAbilitySection));
        }

        // Tagger ability
        ConfigurationSection taggerAbilitySection = config.getConfigurationSection("ability-tagger");
        if (taggerAbilitySection != null && taggerAbilitySection.getBoolean("enabled", true)) {
            pc.setTaggerAbility(loadAbility(id, taggerAbilitySection));
        }

        // Legacy single ability (if no role-specific ones defined)
        if (pc.getRunnerAbility() == null && pc.getTaggerAbility() == null) {
            ConfigurationSection abilitySection = config.getConfigurationSection("ability");
            if (abilitySection != null) {
                boolean abilityEnabled = abilitySection.getBoolean("enabled", true);
                pc.setAbilityEnabled(abilityEnabled);
                if (abilityEnabled) {
                    pc.setAbility(loadAbility(id, abilitySection));
                }
            }
        }

        return pc;
    }

    private Ability loadAbility(String classId, ConfigurationSection section) {
        String name = section.getString("name", "Ability");
        String description = section.getString("description", "");

        String typeStr = section.getString("type", "SPEED_BOOST");
        AbilityType type = AbilityType.SPEED_BOOST;
        try {
            type = AbilityType.valueOf(typeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            logger.warning("[FreezeTag] Unknown ability type '" + typeStr + "' in class " + classId + ", using SPEED_BOOST.");
        }

        int cooldown = section.getInt("cooldown", 15);
        int duration = section.getInt("duration", 5);
        int amplifier = section.getInt("amplifier", 1);
        double radius = section.getDouble("radius", 5.0);

        String itemStr = section.getString("item", "NETHER_STAR");
        Material item = Material.NETHER_STAR;
        try {
            item = Material.valueOf(itemStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            logger.warning("[FreezeTag] Invalid ability item '" + itemStr + "' in class " + classId + ", using NETHER_STAR.");
        }

        return new Ability(name, description, type, cooldown, duration, amplifier, radius, item);
    }

    private int parsePotionDuration(int seconds) {
        if (seconds < 0) return Integer.MAX_VALUE;
        return seconds * 20;
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    public PlayerClass getClass(String id) {
        if (id == null) return null;
        return allClasses.get(id.toLowerCase());
    }

    public Collection<PlayerClass> getAllClasses() {
        return new ArrayList<>(allClasses.values());
    }

    /** Kept for backward compatibility — all classes can be used by runners. */
    public Collection<PlayerClass> getRunnerClasses() {
        return getAllClasses();
    }

    /** Kept for backward compatibility — all classes can be used by taggers. */
    public Collection<PlayerClass> getTaggerClasses() {
        return getAllClasses();
    }

    public boolean classExists(String id) {
        if (id == null) return false;
        return allClasses.containsKey(id.toLowerCase());
    }

    public PlayerClass getDefaultClass() {
        PlayerClass def = allClasses.get("default");
        if (def != null) return def;
        if (!allClasses.isEmpty()) return allClasses.values().iterator().next();
        return null;
    }

    public PlayerClass getDefaultRunnerClass() { return getDefaultClass(); }
    public PlayerClass getDefaultTaggerClass() { return getDefaultClass(); }

    public PlayerClass getRandomClass() {
        if (allClasses.isEmpty()) return null;
        List<PlayerClass> list = new ArrayList<>(allClasses.values());
        return list.get((int) (Math.random() * list.size()));
    }

    public PlayerClass getRandomRunnerClass() { return getRandomClass(); }
    public PlayerClass getRandomTaggerClass() { return getRandomClass(); }
}
