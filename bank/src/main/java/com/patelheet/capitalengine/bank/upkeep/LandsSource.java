package com.patelheet.capitalengine.bank.upkeep;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import me.angeschossen.lands.api.LandsIntegration;
import me.angeschossen.lands.api.land.Area;
import me.angeschossen.lands.api.land.Land;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

/** Lands: claimed chunks × 256 for each land a player owns. */
final class LandsSource implements ClaimSource {
    private final LandsIntegration lands;

    LandsSource(Plugin plugin) { lands = LandsIntegration.of(plugin); }

    @Override public Map<UUID, Long> areas() {
        Map<UUID, Long> m = new HashMap<>();
        for (Land l : lands.getLands()) if (l.getOwnerUID() != null) m.merge(l.getOwnerUID(), l.getChunksAmount() * 256L, Long::sum);
        return m;
    }

    @Override public UUID ownerAt(Location loc) {
        Area a = lands.getArea(loc);
        return a == null ? null : a.getOwnerUID();
    }

    @Override public boolean trusted(Location loc, UUID player) {
        Area a = lands.getArea(loc);
        return a != null && a.isTrusted(player);
    }
}
