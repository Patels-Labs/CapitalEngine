package com.patelheet.capitalengine.bank.upkeep;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.ClaimPermission;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import org.bukkit.Location;

/** GriefPrevention: the 2D area of the top-level claims each player owns (subclaims sit inside them, admin claims have no owner). */
final class GriefPreventionSource implements ClaimSource {
    @Override public Map<UUID, Long> areas() {
        Map<UUID, Long> m = new HashMap<>();
        for (Claim c : GriefPrevention.instance.dataStore.getClaims())
            if (c.parent == null && c.getOwnerID() != null) m.merge(c.getOwnerID(), (long) c.getArea(), Long::sum);
        return m;
    }

    @Override public UUID ownerAt(Location loc) {
        Claim c = GriefPrevention.instance.dataStore.getClaimAt(loc, true, null);
        return c == null ? null : c.getOwnerID();
    }

    @Override public boolean trusted(Location loc, UUID player) {
        Claim c = GriefPrevention.instance.dataStore.getClaimAt(loc, true, null);
        return c != null && c.checkPermission(player, ClaimPermission.Inventory, null) == null;   // null means allowed; builders have container access too
    }
}
