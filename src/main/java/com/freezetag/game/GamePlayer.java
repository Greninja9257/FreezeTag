package com.freezetag.game;

import com.freezetag.classes.PlayerClass;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Holds per-player state for an active FreezeTag game session.
 */
public class GamePlayer {

    private final UUID uuid;
    private RolePreference role;       // reused as current role: RUNNER or TAGGER (NONE = spectator)
    private PlayerClass playerClass;
    private boolean frozen;
    private Location freezeLocation;
    private int refreezeImmunityTicks; // ticks of immunity after being unfrozen

    /** ability name -> System.currentTimeMillis() when cooldown expires */
    private final Map<String, Long> abilityCooldowns = new HashMap<>();

    // Session stats
    private int freezeCount;    // times this player was frozen
    private int unfreezeCount;  // times this player rescued someone (or was rescued — based on role)
    private int tagCount;       // times this player tagged someone (tagger stat)
    private long gameJoinTime;  // epoch millis when game started
    private long survivalTime;  // seconds survived (set on game end)

    // Saved player state (restored when leaving game)
    private boolean stateSaved = false;
    private ItemStack[] savedInventory;
    private ItemStack[] savedArmor;
    private Location savedLocation;
    private GameMode savedGameMode;
    private double savedHealth;
    private int savedFoodLevel;
    private float savedExp;
    private int savedExpLevel;
    private Collection<PotionEffect> savedPotionEffects;

    public GamePlayer(UUID uuid) {
        this.uuid = uuid;
        this.role = RolePreference.NONE;
        this.frozen = false;
        this.gameJoinTime = System.currentTimeMillis();
    }

    // -------------------------------------------------------------------------
    // State management
    // -------------------------------------------------------------------------

    /**
     * Save the player's current inventory, location, game mode, and health
     * so it can be restored when they leave the game.
     */
    public boolean isStateSaved() { return stateSaved; }

    public void saveState(Player player) {
        if (stateSaved) return; // Don't overwrite a previously saved state
        stateSaved = true;
        savedInventory = player.getInventory().getContents().clone();
        savedArmor = player.getInventory().getArmorContents().clone();
        savedLocation = player.getLocation().clone();
        savedGameMode = player.getGameMode();
        savedHealth = player.getHealth();
        savedFoodLevel = player.getFoodLevel();
        savedExp = player.getExp();
        savedExpLevel = player.getLevel();
        savedPotionEffects = player.getActivePotionEffects();
    }

    /**
     * Restore the player to their pre-game state.
     */
    public void restoreState(Player player) {
        // Remove all active effects first
        for (PotionEffect effect : player.getActivePotionEffects()) {
            player.removePotionEffect(effect.getType());
        }

        // Restore game mode first (so inventory changes apply correctly)
        if (savedGameMode != null) {
            player.setGameMode(savedGameMode);
        } else {
            player.setGameMode(GameMode.SURVIVAL);
        }

        // Restore inventory
        if (savedInventory != null) {
            player.getInventory().setContents(savedInventory);
        } else {
            player.getInventory().clear();
        }
        if (savedArmor != null) {
            player.getInventory().setArmorContents(savedArmor);
        }

        // Restore health
        double maxHealth = player.getMaxHealth();
        player.setHealth(Math.min(savedHealth > 0 ? savedHealth : maxHealth, maxHealth));
        player.setFoodLevel(savedFoodLevel > 0 ? savedFoodLevel : 20);
        player.setExp(savedExp);
        player.setLevel(savedExpLevel);
        player.setFreezeTicks(0);

        // Restore potion effects
        if (savedPotionEffects != null) {
            for (PotionEffect effect : savedPotionEffects) {
                player.addPotionEffect(effect);
            }
        }

        // Teleport back to saved location
        if (savedLocation != null && savedLocation.getWorld() != null) {
            player.teleport(savedLocation);
        }
    }

    // -------------------------------------------------------------------------
    // Freeze state
    // -------------------------------------------------------------------------

    public void freeze(Location location) {
        this.frozen = true;
        this.freezeLocation = location != null ? location.clone() : null;
        this.freezeCount++;
        this.refreezeImmunityTicks = 0;
    }

    public void unfreeze() {
        this.frozen = false;
        this.freezeLocation = null;
    }

    public boolean isFrozen() {
        return frozen;
    }

    public Location getFreezeLocation() {
        return freezeLocation != null ? freezeLocation.clone() : null;
    }

    public void setFreezeLocation(Location location) {
        this.freezeLocation = location != null ? location.clone() : null;
    }

    public int getRefreezeImmunityTicks() {
        return refreezeImmunityTicks;
    }

    public void setRefreezeImmunityTicks(int ticks) {
        this.refreezeImmunityTicks = ticks;
    }

    public void decrementRefreezeImmunityTicks() {
        if (refreezeImmunityTicks > 0) {
            refreezeImmunityTicks--;
        }
    }

    public boolean hasRefreezeImmunity() {
        return refreezeImmunityTicks > 0;
    }

    // -------------------------------------------------------------------------
    // Ability cooldowns
    // -------------------------------------------------------------------------

    /**
     * Returns true if the ability cooldown has expired (or was never set).
     */
    public boolean canUseAbility(String abilityName) {
        Long expiry = abilityCooldowns.get(abilityName);
        if (expiry == null) return true;
        return System.currentTimeMillis() >= expiry;
    }

    /**
     * Returns remaining cooldown in seconds, or 0 if ready.
     */
    public long getRemainingCooldown(String abilityName) {
        Long expiry = abilityCooldowns.get(abilityName);
        if (expiry == null) return 0;
        long remaining = expiry - System.currentTimeMillis();
        return remaining > 0 ? (remaining / 1000L) + 1 : 0;
    }

    /**
     * Set the ability on cooldown for the given number of seconds.
     */
    public void setAbilityCooldown(String abilityName, int seconds) {
        abilityCooldowns.put(abilityName, System.currentTimeMillis() + (seconds * 1000L));
    }

    // -------------------------------------------------------------------------
    // Stats
    // -------------------------------------------------------------------------

    public void addFreezeCount() { this.freezeCount++; }
    public void addUnfreezeCount() { this.unfreezeCount++; }
    public void addTagCount() { this.tagCount++; }

    public int getFreezeCount() { return freezeCount; }
    public int getUnfreezeCount() { return unfreezeCount; }
    public int getTagCount() { return tagCount; }

    public void calculateSurvivalTime() {
        this.survivalTime = (System.currentTimeMillis() - gameJoinTime) / 1000L;
    }

    public long getSurvivalTime() { return survivalTime; }
    public void setSurvivalTime(long seconds) { this.survivalTime = seconds; }

    // -------------------------------------------------------------------------
    // Getters & setters
    // -------------------------------------------------------------------------

    public UUID getUuid() { return uuid; }

    public RolePreference getRole() { return role; }
    public void setRole(RolePreference role) { this.role = role; }

    public boolean isRunner() { return role == RolePreference.RUNNER; }
    public boolean isTagger() { return role == RolePreference.TAGGER; }
    public boolean isSpectator() { return role == RolePreference.NONE; }

    public PlayerClass getPlayerClass() { return playerClass; }
    public void setPlayerClass(PlayerClass playerClass) { this.playerClass = playerClass; }

    public long getGameJoinTime() { return gameJoinTime; }
    public void setGameJoinTime(long gameJoinTime) { this.gameJoinTime = gameJoinTime; }

    public ItemStack[] getSavedInventory() { return savedInventory; }
    public Location getSavedLocation() { return savedLocation; }
    public GameMode getSavedGameMode() { return savedGameMode; }
}
