package com.patelheet.capitalengine.bank.upkeep;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionType;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;

/**
 * WorldGuard: the 2D area of every region a player is an owner of. A region with several owners counts in full for each.
 * ponytail: nested regions are counted separately, so an owner of a parent and its child pays for both; subtract children if that matters.
 */
final class WorldGuardSource implements ClaimSource {
    @Override public Map<UUID, Long> areas() {
        Map<UUID, Long> m = new HashMap<>();
        for (RegionManager rm : WorldGuard.getInstance().getPlatform().getRegionContainer().getLoaded()) {
            for (ProtectedRegion r : rm.getRegions().values()) {
                if (r.getType() == RegionType.GLOBAL) continue;
                long height = (long) r.getMaximumPoint().y() - r.getMinimumPoint().y() + 1;
                long area = r.volume() / Math.max(1, height);
                for (UUID owner : r.getOwners().getUniqueIds()) m.merge(owner, area, Long::sum);
            }
        }
        return m;
    }

    /** The owner of the highest-priority region here that has one. */
    @Override public UUID ownerAt(Location loc) {
        ProtectedRegion best = null;
        for (ProtectedRegion r : regionsAt(loc))
            if (!r.getOwners().getUniqueIds().isEmpty() && (best == null || r.getPriority() > best.getPriority())) best = r;
        return best == null ? null : best.getOwners().getUniqueIds().iterator().next();
    }

    @Override public boolean trusted(Location loc, UUID player) {
        for (ProtectedRegion r : regionsAt(loc)) if (r.getOwners().contains(player) || r.getMembers().contains(player)) return true;
        return false;
    }

    private Iterable<ProtectedRegion> regionsAt(Location loc) {
        return WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery().getApplicableRegions(BukkitAdapter.adapt(loc));
    }
}
