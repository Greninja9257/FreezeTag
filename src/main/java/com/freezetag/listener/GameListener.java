package com.freezetag.listener;

import com.freezetag.FreezeTagPlugin;
import com.freezetag.classes.Ability;
import com.freezetag.classes.PlayerClass;
import com.freezetag.game.FreezeTagGame;
import com.freezetag.game.GamePlayer;
import com.freezetag.game.GameState;
import com.freezetag.game.RolePreference;
import com.freezetag.util.MessageUtil;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Handles all in-game events for FreezeTag.
 */
public class GameListener implements Listener {

    private final FreezeTagPlugin plugin;

    public GameListener(FreezeTagPlugin plugin) {
        this.plugin = plugin;
    }

    // -------------------------------------------------------------------------
    // Combat: Freeze / Rescue
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;

        Player attacker = null;

        if (event.getDamager() instanceof Player p) {
            attacker = p;
        } else if (event.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player p) {
            attacker = p;
        }

        if (attacker == null) return;

        FreezeTagGame attackerGame = plugin.getGameManager().getPlayerGame(attacker.getUniqueId());
        FreezeTagGame victimGame = plugin.getGameManager().getPlayerGame(victim.getUniqueId());

        // Both must be in the same game
        if (attackerGame == null || victimGame == null || attackerGame != victimGame) return;
        if (attackerGame.getState() != GameState.IN_GAME) {
            event.setCancelled(true);
            return;
        }

        // Cancel actual damage — we handle it ourselves
        event.setCancelled(true);

        GamePlayer attackerGp = attackerGame.getGamePlayer(attacker.getUniqueId());
        GamePlayer victimGp = victimGame.getGamePlayer(victim.getUniqueId());

        if (attackerGp == null || victimGp == null) return;

        // Case 1: Tagger hits unfrozen Runner → Freeze
        if (attackerGp.isTagger() && victimGp.isRunner() && !victimGp.isFrozen()) {
            attackerGame.freezePlayer(attacker, victim);
            return;
        }

        // Case 2: Runner hits frozen Runner → Rescue (if enabled)
        if (attackerGp.isRunner() && !attackerGp.isFrozen()
                && victimGp.isRunner() && victimGp.isFrozen()) {
            if (plugin.getConfig().getBoolean("game.rescue-enabled", true)) {
                // Check rescue cooldown
                int rescueCooldown = plugin.getConfig().getInt("game.rescue-cooldown", 0);
                if (rescueCooldown > 0 && !attackerGp.canUseAbility("__rescue__")) {
                    long remaining = attackerGp.getRemainingCooldown("__rescue__");
                    MessageUtil.sendMessage(attacker, "rescue.on-cooldown",
                            Map.of("time", String.valueOf(remaining)));
                    return;
                }

                int requiredHits = plugin.getConfig().getInt("game.rescue-hits", 1);
                if (requiredHits <= 1) {
                    attackerGame.unfreezePlayer(attacker, victim);
                } else {
                    attackerGame.unfreezePlayer(attacker, victim);
                }

                // Apply rescue cooldown
                if (rescueCooldown > 0) {
                    attackerGp.setAbilityCooldown("__rescue__", rescueCooldown);
                }
            }
        }

        // Case 3: Tagger hits another Tagger → No effect (just cancel damage)
        // Case 4: Any other combination → No effect
    }

    // -------------------------------------------------------------------------
    // Ability activation
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteract(PlayerInteractEvent event) {
        // Only handle right-click
        if (event.getHand() != EquipmentSlot.HAND) return;

        org.bukkit.event.block.Action action = event.getAction();
        if (action != org.bukkit.event.block.Action.RIGHT_CLICK_AIR
                && action != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Player player = event.getPlayer();
        FreezeTagGame game = plugin.getGameManager().getPlayerGame(player.getUniqueId());
        if (game == null || game.getState() != GameState.IN_GAME) return;

        GamePlayer gp = game.getGamePlayer(player.getUniqueId());
        if (gp == null || gp.isSpectator() || gp.isFrozen()) return;

        PlayerClass playerClass = gp.getPlayerClass();
        if (playerClass == null) return;

        Ability ability = playerClass.getAbilityForRole(gp.getRole());
        if (ability == null) return;

        // Check if player is holding the ability item
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType() != ability.getItem()) return;

        // Check cooldown
        if (!gp.canUseAbility(ability.getName())) {
            long remaining = gp.getRemainingCooldown(ability.getName());
            MessageUtil.sendMessage(player, "ability.on-cooldown",
                    Map.of("time", String.valueOf(remaining)));
            event.setCancelled(true);
            return;
        }

        // Execute ability
        event.setCancelled(true);
        ability.execute(player, game);
        gp.setAbilityCooldown(ability.getName(), ability.getCooldown());

        // Play sound
        String soundName = plugin.getConfig().getString("sounds.ability-use", "");
        if (!soundName.isEmpty()) {
            try {
                org.bukkit.Sound sound = org.bukkit.Registry.SOUNDS.get(org.bukkit.NamespacedKey.minecraft(soundName.toLowerCase()));
                player.playSound(player.getLocation(), sound, 1.0f, 1.0f);
            } catch (IllegalArgumentException ignored) {}
        }

        // Schedule cooldown-end notification
        int cooldownTicks = ability.getCooldown() * 20;
        final String abilityName = ability.getName();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                FreezeTagGame currentGame = plugin.getGameManager().getPlayerGame(player.getUniqueId());
                if (currentGame != null && currentGame.getState() == GameState.IN_GAME) {
                    player.sendMessage(MessageUtil.colorize(MessageUtil.getPrefix())
                            + MessageUtil.get("ability.ready",
                            Map.of("ability", MessageUtil.colorize(abilityName))));
                    // Play ready sound
                    String readySound = plugin.getConfig().getString("sounds.ability-ready", "");
                    if (!readySound.isEmpty()) {
                        try {
                            org.bukkit.Sound s = org.bukkit.Registry.SOUNDS.get(org.bukkit.NamespacedKey.minecraft(readySound.toLowerCase()));
                            if (s != null) player.playSound(player.getLocation(), s, 0.7f, 2.0f);
                        } catch (IllegalArgumentException ignored) {}
                    }
                }
            }
        }, cooldownTicks);
    }

    // -------------------------------------------------------------------------
    // Movement: Frozen position enforcement + boundary
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        FreezeTagGame game = plugin.getGameManager().getPlayerGame(player.getUniqueId());
        if (game == null || game.getState() != GameState.IN_GAME) return;

        GamePlayer gp = game.getGamePlayer(player.getUniqueId());
        if (gp == null) return;

        // Handle frozen players — allow head rotation, block all positional movement
        if (gp.isFrozen()) {
            Location from = event.getFrom();
            Location to = event.getTo();
            if (to == null) return;

            boolean movedXZ = Math.abs(to.getX() - from.getX()) > 0.01
                    || Math.abs(to.getZ() - from.getZ()) > 0.01;
            boolean movedY  = to.getY() > from.getY() + 0.01; // upward = jump attempt

            if (movedXZ || movedY) {
                Location frozenLoc = gp.getFreezeLocation();
                if (frozenLoc != null) {
                    frozenLoc.setYaw(to.getYaw());
                    frozenLoc.setPitch(to.getPitch());
                    event.setTo(frozenLoc);
                    // Zero velocity so the jump impulse doesn't carry over
                    player.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
                }
            }
            return;
        }

        // Boundary enforcement
        if (plugin.getConfig().getBoolean("protection.arena-boundary", true)
                && game.getArena().hasBounds()) {
            Location to = event.getTo();
            if (to == null) return;

            Location boundsMin = game.getArena().getBoundsMin();
            Location boundsMax = game.getArena().getBoundsMax();
            if (boundsMin == null || boundsMax == null) return;

            double minX = Math.min(boundsMin.getX(), boundsMax.getX());
            double minY = Math.min(boundsMin.getY(), boundsMax.getY());
            double minZ = Math.min(boundsMin.getZ(), boundsMax.getZ());
            double maxX = Math.max(boundsMin.getX(), boundsMax.getX());
            double maxY = Math.max(boundsMin.getY(), boundsMax.getY());
            double maxZ = Math.max(boundsMin.getZ(), boundsMax.getZ());

            boolean outOfBounds = to.getX() < minX || to.getX() > maxX
                    || to.getY() < minY || to.getY() > maxY
                    || to.getZ() < minZ || to.getZ() > maxZ;

            if (outOfBounds) {
                String action = plugin.getConfig().getString("protection.boundary-action", "TELEPORT_BACK");
                switch (action.toUpperCase()) {
                    case "TELEPORT_BACK" -> {
                        // Clamp the destination back inside the boundary instead of
                        // just going to event.getFrom() (which may be on the very edge)
                        double safeX = Math.max(minX + 1.0, Math.min(maxX - 1.0, to.getX()));
                        double safeY = Math.max(minY + 0.1, Math.min(maxY - 1.0, to.getY()));
                        double safeZ = Math.max(minZ + 1.0, Math.min(maxZ - 1.0, to.getZ()));
                        event.setTo(new Location(to.getWorld(), safeX, safeY, safeZ, to.getYaw(), to.getPitch()));
                        player.sendMessage(MessageUtil.colorize(MessageUtil.getPrefix())
                                + MessageUtil.get("boundary.teleported-back"));
                    }
                    case "FREEZE" -> {
                        double safeX = Math.max(minX + 1.0, Math.min(maxX - 1.0, to.getX()));
                        double safeY = Math.max(minY + 0.1, Math.min(maxY - 1.0, to.getY()));
                        double safeZ = Math.max(minZ + 1.0, Math.min(maxZ - 1.0, to.getZ()));
                        event.setTo(new Location(to.getWorld(), safeX, safeY, safeZ, to.getYaw(), to.getPitch()));
                    }
                    default -> {} // NOTHING
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Death prevention
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        FreezeTagGame game = plugin.getGameManager().getPlayerGame(player.getUniqueId());
        if (game == null) return;

        // Prevent death — restore health and teleport back to spawn
        event.setCancelled(true);
        player.setHealth(player.getMaxHealth());
        player.setFoodLevel(20);

        GamePlayer gp = game.getGamePlayer(player.getUniqueId());
        if (gp != null) {
            Location spawn;
            if (gp.isRunner()) {
                spawn = game.getArena().getRandomRunnerSpawn();
            } else if (gp.isTagger()) {
                spawn = game.getArena().getRandomTaggerSpawn();
            } else {
                spawn = game.getArena().getLobbySpawn();
            }
            if (spawn != null) player.teleport(spawn);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        FreezeTagGame game = plugin.getGameManager().getPlayerGame(player.getUniqueId());
        if (game == null) return;

        // Cancel fall damage if configured
        if (event.getCause() == EntityDamageEvent.DamageCause.FALL
                && plugin.getConfig().getBoolean("protection.disable-fall-damage", true)) {
            event.setCancelled(true);
            return;
        }

        // In-game: cancel all non-player damage (void, suffocation, etc.) - keep players alive
        if (game.getState() == GameState.IN_GAME) {
            if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK
                    && event.getCause() != EntityDamageEvent.DamageCause.PROJECTILE) {
                event.setCancelled(true);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Block protection
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        FreezeTagGame game = plugin.getGameManager().getPlayerGame(player.getUniqueId());
        if (game == null) return;

        if (plugin.getConfig().getBoolean("protection.disable-block-break", true)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        FreezeTagGame game = plugin.getGameManager().getPlayerGame(player.getUniqueId());
        if (game == null) return;

        if (plugin.getConfig().getBoolean("protection.disable-block-place", true)) {
            event.setCancelled(true);
        }
    }

    // -------------------------------------------------------------------------
    // Item drop prevention
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onItemDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        FreezeTagGame game = plugin.getGameManager().getPlayerGame(player.getUniqueId());
        if (game == null) return;

        if (plugin.getConfig().getBoolean("protection.disable-item-drop", true)) {
            event.setCancelled(true);
        }
    }

    // -------------------------------------------------------------------------
    // Command restriction
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();

        // Bypass for admins
        if (player.hasPermission("freezetag.bypass")) return;

        FreezeTagGame game = plugin.getGameManager().getPlayerGame(player.getUniqueId());
        if (game == null) return;

        if (!plugin.getConfig().getBoolean("protection.restrict-commands", true)) return;

        String command = event.getMessage().toLowerCase().trim();
        List<String> allowedCommands = plugin.getConfig().getStringList("protection.allowed-commands");

        for (String allowed : allowedCommands) {
            if (command.startsWith(allowed.toLowerCase())) return;
        }

        event.setCancelled(true);
        MessageUtil.sendMessage(player, "boundary.warning");
    }

    // -------------------------------------------------------------------------
    // Hunger
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onFoodLevelChange(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        FreezeTagGame game = plugin.getGameManager().getPlayerGame(player.getUniqueId());
        if (game == null) return;

        if (plugin.getConfig().getBoolean("protection.disable-hunger", true)) {
            event.setCancelled(true);
            player.setFoodLevel(20);
        }
    }

    // -------------------------------------------------------------------------
    // Player quit
    // -------------------------------------------------------------------------

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        FreezeTagGame game = plugin.getGameManager().getPlayerGame(uuid);
        if (game != null) {
            game.removePlayer(player);
        }

        // Clean up queue
        plugin.getGameManager().leaveQueue(player);
        plugin.getLobbyGUI().removeOpenMenu(uuid);
    }
}
