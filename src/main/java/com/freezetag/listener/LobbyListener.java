package com.freezetag.listener;

import com.freezetag.FreezeTagPlugin;
import com.freezetag.game.FreezeTagGame;
import com.freezetag.util.MessageUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * Handles lobby hotbar item interactions and chest-GUI submenu clicks.
 */
public class LobbyListener implements Listener {

    private final FreezeTagPlugin plugin;

    public LobbyListener(FreezeTagPlugin plugin) {
        this.plugin = plugin;
    }

    // -------------------------------------------------------------------------
    // Hotbar item right-click
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        ItemStack item = event.getItem();

        if (!plugin.getLobbyGUI().isLobbyItem(item)) return;

        // Allow if in a game queue OR in the vote lobby
        FreezeTagGame game = plugin.getGameManager().getPlayerGame(player.getUniqueId());
        boolean inVoteLobby = plugin.getVoteLobby() != null
                && plugin.getVoteLobby().isInLobby(player.getUniqueId());
        if (game == null && !plugin.getGameManager().isInQueue(player.getUniqueId()) && !inVoteLobby) return;

        event.setCancelled(true);
        plugin.getLobbyGUI().handleItemInteract(player, item);
    }

    // -------------------------------------------------------------------------
    // Prevent dropping lobby items
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerDrop(PlayerDropItemEvent event) {
        if (plugin.getLobbyGUI().isLobbyItem(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    // -------------------------------------------------------------------------
    // Chest submenu (class / role) click handling
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        // Block moving lobby items inside the player's own inventory
        if (plugin.getLobbyGUI().isLobbyItem(event.getCurrentItem())
                || plugin.getLobbyGUI().isLobbyItem(event.getCursor())) {
            event.setCancelled(true);
            return;
        }

        String title = event.getView().getTitle();
        if (!plugin.getLobbyGUI().isFreezeTagGUI(title)) return;

        event.setCancelled(true);

        if (event.getClickedInventory() == null) return;
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;

        plugin.getLobbyGUI().handleClick(player, title, event.getSlot());
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        String title = event.getView().getTitle();
        if (plugin.getLobbyGUI().isFreezeTagGUI(title)) {
            plugin.getLobbyGUI().onInventoryClose(player);
        }
    }

    // -------------------------------------------------------------------------
    // Player quit — cleanup
    // -------------------------------------------------------------------------

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (plugin.getGameManager().isInQueue(uuid)
                && plugin.getGameManager().getPlayerGame(uuid) == null) {
            plugin.getGameManager().leaveQueue(player);
        }

        if (plugin.getVoteLobby() != null && plugin.getVoteLobby().isInLobby(uuid)) {
            plugin.getVoteLobby().removePlayer(player);
        }

        plugin.getLobbyGUI().removeOpenMenu(uuid);
    }
}
