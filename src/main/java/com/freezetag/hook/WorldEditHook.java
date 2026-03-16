package com.freezetag.hook;

import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Optional integration with WorldEdit.
 * All methods are safe to call even when WorldEdit is not installed —
 * they return null / false in that case.
 */
public class WorldEditHook {

    /**
     * Returns true if WorldEdit is present on the server.
     */
    public static boolean isAvailable() {
        try {
            Class.forName("com.sk89q.worldedit.WorldEdit");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Result of a WorldEdit selection lookup.
     */
    public record SelectionResult(Location min, Location max) {}

    /**
     * Get the player's current WorldEdit selection as two Bukkit Locations.
     * Returns null if WorldEdit is not available, the player has no selection,
     * or the selection is in a different world.
     */
    public static SelectionResult getSelection(Player player) {
        if (player == null) return null;
        try {
            LocalSession session = WorldEdit.getInstance()
                    .getSessionManager()
                    .get(BukkitAdapter.adapt(player));

            Region region = session.getSelection(BukkitAdapter.adapt(player.getWorld()));

            BlockVector3 min = region.getMinimumPoint();
            BlockVector3 max = region.getMaximumPoint();

            Location minLoc = new Location(player.getWorld(), min.x(), min.y(), min.z());
            Location maxLoc = new Location(player.getWorld(), max.x(), max.y(), max.z());

            return new SelectionResult(minLoc, maxLoc);
        } catch (IncompleteRegionException e) {
            return null; // No selection made yet
        } catch (Exception e) {
            return null; // WorldEdit not available or other error
        }
    }
}
