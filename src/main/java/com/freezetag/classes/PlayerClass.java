package com.freezetag.classes;

import com.freezetag.game.RolePreference;
import com.freezetag.util.MessageUtil;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Represents a player class with stat modifiers, potion effects, and role-specific abilities.
 * Each class has separate abilities for runner and tagger roles.
 */
public class PlayerClass {

    private static final Logger LOGGER = Logger.getLogger("FreezeTag");
    private static final String SPEED_MODIFIER_KEY = "freezetag_speed";
    private static final String JUMP_MODIFIER_KEY = "freezetag_jump";

    private final String id;
    private String name;
    private String displayName;
    private List<String> description = new ArrayList<>();
    private Material displayItem = Material.PAPER;
    private double speedModifier = 0.0;
    private double jumpModifier = 0.0;
    private List<PotionEffect> potionEffects = new ArrayList<>();
    private int maxCount = 0;

    // Role-specific abilities
    private Ability runnerAbility;
    private Ability taggerAbility;

    // Legacy single-ability support
    private Ability ability;
    private boolean abilityEnabled = false;

    public PlayerClass(String id) {
        this.id = id;
    }

    // -------------------------------------------------------------------------
    // Apply / remove class effects
    // -------------------------------------------------------------------------

    /**
     * Apply class stat modifiers, potion effects, and give the role-appropriate ability item.
     */
    public void applyToPlayer(Player player, RolePreference role) {
        if (player == null) return;

        // Apply speed modifier
        if (speedModifier != 0.0) {
            AttributeInstance speedAttr = player.getAttribute(Attribute.MOVEMENT_SPEED);
            if (speedAttr != null) {
                speedAttr.getModifiers().stream()
                        .filter(mod -> mod.key().asString().contains(SPEED_MODIFIER_KEY))
                        .forEach(speedAttr::removeModifier);

                AttributeModifier speedMod = new AttributeModifier(
                        new org.bukkit.NamespacedKey("freezetag", SPEED_MODIFIER_KEY),
                        speedModifier,
                        AttributeModifier.Operation.ADD_NUMBER,
                        EquipmentSlotGroup.ANY
                );
                speedAttr.addModifier(speedMod);
            }
        }

        // Apply jump modifier via potion
        if (jumpModifier > 0) {
            int jumpAmp = (int) Math.floor(jumpModifier * 10);
            if (jumpAmp > 0) {
                player.addPotionEffect(new PotionEffect(
                        PotionEffectType.JUMP_BOOST, Integer.MAX_VALUE, jumpAmp, false, false, false));
            }
        }

        // Apply configured potion effects
        for (PotionEffect effect : potionEffects) {
            player.addPotionEffect(effect);
        }

        // Give the role-specific ability item in hotbar slot 0
        Ability abilityToUse = resolveAbility(role);
        if (abilityToUse != null) {
            player.getInventory().setItem(0, abilityToUse.createItem());
        }
    }

    /**
     * Apply class effects without a specific role (uses runner ability as default).
     */
    public void applyToPlayer(Player player) {
        applyToPlayer(player, RolePreference.RUNNER);
    }

    /**
     * Remove class stat modifiers and clean up ability item.
     */
    public void removeFromPlayer(Player player) {
        if (player == null) return;

        AttributeInstance speedAttr = player.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speedAttr != null) {
            speedAttr.getModifiers().stream()
                    .filter(mod -> mod.key().asString().contains(SPEED_MODIFIER_KEY))
                    .forEach(speedAttr::removeModifier);
        }

        if (jumpModifier > 0) {
            player.removePotionEffect(PotionEffectType.JUMP_BOOST);
        }

        for (PotionEffect effect : potionEffects) {
            player.removePotionEffect(effect.getType());
        }

        // Remove ability item from slot 0 if it matches any known ability item
        ItemStack slot0 = player.getInventory().getItem(0);
        if (slot0 != null) {
            Ability ra = runnerAbility != null ? runnerAbility : ability;
            Ability ta = taggerAbility != null ? taggerAbility : ability;
            boolean isAbilityItem = (ra != null && slot0.getType() == ra.getItem())
                    || (ta != null && slot0.getType() == ta.getItem());
            if (isAbilityItem) {
                player.getInventory().setItem(0, null);
            }
        }
    }

    /**
     * Create a display ItemStack for this class (used in GUIs).
     * Shows both runner and tagger ability info.
     */
    public ItemStack createDisplayItem() {
        ItemStack item = new ItemStack(displayItem != null ? displayItem : Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(MessageUtil.colorize(displayName));
            List<String> lore = new ArrayList<>();
            for (String line : description) {
                lore.add(MessageUtil.colorize(line));
            }
            if (speedModifier != 0.0) {
                lore.add(MessageUtil.colorize("&7Speed: " + (speedModifier > 0 ? "&a+" : "&c") + speedModifier));
            }

            // Show runner ability
            Ability ra = runnerAbility != null ? runnerAbility : (abilityEnabled ? ability : null);
            if (ra != null) {
                lore.add("");
                lore.add(MessageUtil.colorize("&aRunner Ability: &f" + ra.getName()));
                lore.add(MessageUtil.colorize("  &7" + ra.getDescription()));
                lore.add(MessageUtil.colorize("  &7Cooldown: &e" + ra.getCooldown() + "s"));
            }

            // Show tagger ability
            Ability ta = taggerAbility;
            if (ta != null) {
                lore.add("");
                lore.add(MessageUtil.colorize("&cTagger Ability: &f" + ta.getName()));
                lore.add(MessageUtil.colorize("  &7" + ta.getDescription()));
                lore.add(MessageUtil.colorize("  &7Cooldown: &e" + ta.getCooldown() + "s"));
            }

            if (maxCount > 0) {
                lore.add("");
                lore.add(MessageUtil.colorize("&7Max per game: &e" + maxCount));
            }
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Ability resolveAbility(RolePreference role) {
        if (role == RolePreference.TAGGER) {
            if (taggerAbility != null) return taggerAbility;
        } else {
            if (runnerAbility != null) return runnerAbility;
        }
        // Fall back to legacy single ability
        return abilityEnabled ? ability : null;
    }

    /**
     * Get the ability for a given role (for use in ability activation).
     */
    public Ability getAbilityForRole(RolePreference role) {
        return resolveAbility(role);
    }

    // -------------------------------------------------------------------------
    // Getters & setters
    // -------------------------------------------------------------------------

    public String getId() { return id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public List<String> getDescription() { return description; }
    public void setDescription(List<String> description) { this.description = description != null ? description : new ArrayList<>(); }

    public Material getDisplayItem() { return displayItem; }
    public void setDisplayItem(Material displayItem) { this.displayItem = displayItem; }

    public double getSpeedModifier() { return speedModifier; }
    public void setSpeedModifier(double speedModifier) { this.speedModifier = speedModifier; }

    public double getJumpModifier() { return jumpModifier; }
    public void setJumpModifier(double jumpModifier) { this.jumpModifier = jumpModifier; }

    public List<PotionEffect> getPotionEffects() { return potionEffects; }
    public void setPotionEffects(List<PotionEffect> potionEffects) { this.potionEffects = potionEffects != null ? potionEffects : new ArrayList<>(); }

    public int getMaxCount() { return maxCount; }
    public void setMaxCount(int maxCount) { this.maxCount = maxCount; }

    public Ability getRunnerAbility() { return runnerAbility; }
    public void setRunnerAbility(Ability runnerAbility) { this.runnerAbility = runnerAbility; }

    public Ability getTaggerAbility() { return taggerAbility; }
    public void setTaggerAbility(Ability taggerAbility) { this.taggerAbility = taggerAbility; }

    // Legacy single-ability getters (kept for compatibility)
    public Ability getAbility() { return ability != null ? ability : runnerAbility; }
    public void setAbility(Ability ability) { this.ability = ability; }
    public boolean isAbilityEnabled() { return abilityEnabled || runnerAbility != null || taggerAbility != null; }
    public void setAbilityEnabled(boolean abilityEnabled) { this.abilityEnabled = abilityEnabled; }

    // Role field kept for backward-compat but no longer used for filtering
    private RolePreference role = RolePreference.NONE;
    public RolePreference getRole() { return role; }
    public void setRole(RolePreference role) { this.role = role; }
}
