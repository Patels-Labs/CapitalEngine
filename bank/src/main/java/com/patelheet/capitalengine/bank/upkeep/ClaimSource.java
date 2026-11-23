package com.patelheet.capitalengine.bank.upkeep;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

/**
 * Land claimed through one claim plugin. There is one implementation per supported plugin (GriefPrevention, Lands, WorldGuard),
 * each loaded only when its plugin is enabled. Every method is called on the main thread.
 */
public interface ClaimSource {
    /** Every owner's claimed 2D area in blocks². */
    Map<UUID, Long> areas();

    /** The owner of the claim at {@code loc}, or null when it isn't claimed (or has no player owner). */
    @Nullable UUID ownerAt(Location loc);

    /** Whether {@code player} is trusted in the claim at {@code loc} (members, builders and container-trusted players). */
    boolean trusted(Location loc, UUID player);

    /** A source for each supported claim plugin that is enabled. A plugin whose API doesn't match is skipped with a warning. */
    static List<ClaimSource> installed(Plugin bank) {
        var pm = bank.getServer().getPluginManager();
        List<ClaimSource> out = new ArrayList<>();
        for (String name : new String[]{"GriefPrevention", "Lands", "WorldGuard"}) {
            if (!pm.isPluginEnabled(name)) continue;
            try {
                out.add(switch (name) {
                    case "GriefPrevention" -> new GriefPreventionSource();
                    case "Lands" -> new LandsSource(bank);
                    default -> new WorldGuardSource();
                });
                bank.getLogger().info("Land upkeep uses " + name + ".");
            } catch (LinkageError | RuntimeException e) {
                bank.getLogger().warning("Land upkeep can't read " + name + " (" + e + "); its claims are ignored.");
            }
        }
        return out;
    }
}
