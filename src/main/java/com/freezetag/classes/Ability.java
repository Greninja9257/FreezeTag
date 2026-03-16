package com.freezetag.classes;

import com.freezetag.FreezeTagPlugin;
import com.freezetag.game.FreezeTagGame;
import com.freezetag.game.GamePlayer;
import com.freezetag.game.RolePreference;
import com.freezetag.util.MessageUtil;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Represents a player class ability with its type, cooldown, and effect parameters.
 */
public class Ability {

    private final String name;
    private final String description;
    private final AbilityType type;
    private final int cooldown;   // seconds
    private final int duration;   // seconds (0 = instant / not applicable)
    private final int amplifier;  // effect amplifier (strength)
    private final double radius;  // radius in blocks for AoE abilities
    private final Material item;  // hotbar item that triggers the ability

    public Ability(String name, String description, AbilityType type,
                   int cooldown, int duration, int amplifier, Material item) {
        this(name, description, type, cooldown, duration, amplifier, 5.0, item);
    }

    public Ability(String name, String description, AbilityType type,
                   int cooldown, int duration, int amplifier, double radius, Material item) {
        this.name = name;
        this.description = description;
        this.type = type;
        this.cooldown = cooldown;
        this.duration = duration;
        this.amplifier = amplifier;
        this.radius = radius;
        this.item = item;
    }

    // -------------------------------------------------------------------------
    // Execution
    // -------------------------------------------------------------------------

    /**
     * Execute this ability for the given player in the given game.
     * Assumes the cooldown check has already been performed.
     */
    public void execute(Player player, FreezeTagGame game) {
        if (player == null || game == null) return;

        switch (type) {
            case SPEED_BOOST -> executeSpeedBoost(player);
            case JUMP_BOOST -> executeJumpBoost(player);
            case INVISIBILITY -> executeInvisibility(player);
            case DASH -> executeDash(player);
            case FREEZE_AOE -> executeFreezeAoe(player, game);
            case SLOW_AOE -> executeSlowAoe(player, game);
            case UNFREEZE_AOE -> executeUnfreezeAoe(player, game);
            case DECOY -> executeDecoy(player);
            case SHIELD -> executeShield(player);
        }

        player.sendMessage(MessageUtil.colorize(MessageUtil.getPrefix())
                + MessageUtil.get("ability.activated",
                Map.of("ability", MessageUtil.colorize(name))));
    }

    private void executeSpeedBoost(Player player) {
        int ticks = duration * 20;
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, ticks, amplifier, false, true, true));
    }

    private void executeJumpBoost(Player player) {
        int ticks = duration * 20;
        player.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, ticks, amplifier, false, true, true));
    }

    private void executeInvisibility(Player player) {
        int ticks = duration * 20;
        // Remove armor temporarily so player is truly invisible
        ItemStack[] armor = player.getInventory().getArmorContents().clone();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, ticks, 0, false, false, true));

        new BukkitRunnable() {
            @Override
            public void run() {
                if (player.isOnline()) {
                    player.removePotionEffect(PotionEffectType.INVISIBILITY);
                    // Restore armor if slots are still empty (don't overwrite if changed)
                    ItemStack[] current = player.getInventory().getArmorContents();
                    for (int i = 0; i < 4; i++) {
                        if (current[i] == null || current[i].getType() == Material.AIR) {
                            current[i] = armor[i];
                        }
                    }
                    player.getInventory().setArmorContents(current);
                }
            }
        }.runTaskLater(FreezeTagPlugin.getInstance(), ticks + 1L);
    }

    private void executeDash(Player player) {
        Vector direction = player.getLocation().getDirection().normalize();
        double strength = amplifier + 1.0;
        direction.setY(0.3); // slight upward angle
        direction.multiply(strength);
        player.setVelocity(direction);
        // Particle trail
        Location loc = player.getLocation();
        player.getWorld().spawnParticle(Particle.CLOUD, loc, 15, 0.2, 0.2, 0.2, 0.05);
    }

    private void executeFreezeAoe(Player player, FreezeTagGame game) {
        double radius = Math.max(1, this.radius);
        Location center = player.getLocation();
        List<Player> nearbyRunners = new ArrayList<>();

        for (Map.Entry<java.util.UUID, GamePlayer> entry : game.getGamePlayers().entrySet()) {
            GamePlayer gp = entry.getValue();
            if (!gp.isRunner() || gp.isFrozen()) continue;
            Player target = player.getServer().getPlayer(entry.getKey());
            if (target == null || !target.isOnline()) continue;
            if (target.getLocation().distance(center) <= radius) {
                nearbyRunners.add(target);
            }
        }

        for (Player target : nearbyRunners) {
            game.freezePlayer(player, target);
        }

        center.getWorld().spawnParticle(Particle.SNOWFLAKE, center, 40, radius / 2, 1, radius / 2, 0.05);
    }

    private void executeSlowAoe(Player player, FreezeTagGame game) {
        double radius = Math.max(1, this.radius);
        int ticks = duration * 20;
        Location center = player.getLocation();

        for (Map.Entry<java.util.UUID, GamePlayer> entry : game.getGamePlayers().entrySet()) {
            GamePlayer gp = entry.getValue();
            if (!gp.isRunner() || gp.isFrozen()) continue;
            Player target = player.getServer().getPlayer(entry.getKey());
            if (target == null || !target.isOnline()) continue;
            if (target.getLocation().distance(center) <= radius) {
                target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks, amplifier, false, true, true));
            }
        }

        center.getWorld().spawnParticle(Particle.SQUID_INK, center, 30, radius / 2, 0.5, radius / 2, 0.02);
    }

    private void executeUnfreezeAoe(Player player, FreezeTagGame game) {
        double radius = Math.max(1, this.radius);
        Location center = player.getLocation();
        List<Player> frozenNearby = new ArrayList<>();

        for (Map.Entry<java.util.UUID, GamePlayer> entry : game.getGamePlayers().entrySet()) {
            GamePlayer gp = entry.getValue();
            if (!gp.isFrozen()) continue;
            Player target = player.getServer().getPlayer(entry.getKey());
            if (target == null || !target.isOnline()) continue;
            if (target.getLocation().distance(center) <= radius) {
                frozenNearby.add(target);
            }
        }

        for (Player target : frozenNearby) {
            game.unfreezePlayer(player, target);
        }

        center.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, center, 30, radius / 2, 1, radius / 2, 0.1);
    }

    private void executeDecoy(Player player) {
        Location loc = player.getLocation();
        ArmorStand stand = loc.getWorld().spawn(loc, ArmorStand.class, as -> {
            as.setCustomName(player.getDisplayName());
            as.setCustomNameVisible(true);
            as.setHelmet(player.getInventory().getHelmet());
            as.setChestplate(player.getInventory().getChestplate());
            as.setLeggings(player.getInventory().getLeggings());
            as.setBoots(player.getInventory().getBoots());
            as.setGravity(false);
        });

        // Remove the decoy after the duration
        int removeTicks = Math.max(20, duration * 20);
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!stand.isDead()) {
                    stand.getLocation().getWorld().spawnParticle(
                            Particle.POOF, stand.getLocation(), 10, 0.3, 0.5, 0.3, 0.05);
                    stand.remove();
                }
            }
        }.runTaskLater(FreezeTagPlugin.getInstance(), removeTicks);

        loc.getWorld().spawnParticle(Particle.SMOKE, loc, 10, 0.2, 0.5, 0.2, 0.02);
    }

    private void executeShield(Player player) {
        int ticks = duration * 20;
        player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, ticks, 4, false, true, true));
        player.getWorld().spawnParticle(Particle.ENCHANT, player.getLocation(), 20, 0.5, 0.5, 0.5, 0.1);
    }

    // -------------------------------------------------------------------------
    // Item creation
    // -------------------------------------------------------------------------

    /**
     * Create the hotbar ItemStack that represents this ability.
     */
    public ItemStack createItem() {
        ItemStack itemStack = new ItemStack(item != null ? item : Material.NETHER_STAR);
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(MessageUtil.colorize("&6" + name));
            List<String> lore = new ArrayList<>();
            lore.add(MessageUtil.colorize(description));
            lore.add("");
            lore.add(MessageUtil.colorize("&7Cooldown: &e" + cooldown + "s"));
            if (duration > 0) {
                lore.add(MessageUtil.colorize("&7Duration: &e" + duration + "s"));
            }
            lore.add("");
            lore.add(MessageUtil.colorize("&eRight-click to activate!"));
            meta.setLore(lore);
            itemStack.setItemMeta(meta);
        }
        return itemStack;
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    public String getName() { return name; }
    public String getDescription() { return description; }
    public AbilityType getType() { return type; }
    public int getCooldown() { return cooldown; }
    public int getDuration() { return duration; }
    public int getAmplifier() { return amplifier; }
    public double getRadius() { return radius; }
    public Material getItem() { return item; }
}
