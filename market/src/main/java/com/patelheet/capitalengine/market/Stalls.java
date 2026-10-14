package com.patelheet.capitalengine.market;

import com.patelheet.capitalengine.market.storage.Store.Stall;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Market stalls (market/Plan.md §4): any block an owner marks. Right-clicking one opens the market, and the Stash can be
 * emptied within a few blocks of one unless {@code stash.withdraw-anywhere} is on.
 * ponytail: block stalls only; a Citizens NPC stall would call the same {@code open}.
 */
public final class Stalls implements Listener {
    private static final int REACH = 6;
    private final MarketService svc;
    private final Menus menus;
    private final Set<Stall> all = ConcurrentHashMap.newKeySet();

    Stalls(MarketService svc, Menus menus) { this.svc = svc; this.menus = menus; }

    void load() { svc.run(svc.store::stalls).thenAccept(all::addAll); }

    private static Stall at(Block b) { return new Stall(b.getWorld().getName(), b.getX(), b.getY(), b.getZ()); }

    public boolean create(Block b) { Stall s = at(b); if (!all.add(s)) return false; svc.run(() -> { svc.store.addStall(s); return null; }); return true; }

    public boolean remove(Block b) { Stall s = at(b); if (!all.remove(s)) return false; svc.run(() -> { svc.store.removeStall(s); return null; }); return true; }

    public boolean canWithdraw(Player p) {
        if (svc.withdrawAnywhere) return true;
        var loc = p.getLocation();
        for (Stall s : all)
            if (s.world().equals(loc.getWorld().getName()) && Math.abs(s.x() - loc.getX()) <= REACH && Math.abs(s.y() - loc.getY()) <= REACH && Math.abs(s.z() - loc.getZ()) <= REACH) return true;
        return false;
    }

    @EventHandler public void onClick(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND || e.getClickedBlock() == null) return;
        if (!all.contains(at(e.getClickedBlock()))) return;
        e.setCancelled(true);
        menus.main(e.getPlayer());
    }
}
