package com.patelheet.capitalengine.market;

import com.patelheet.capitalengine.market.storage.Store.Stall;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;

/**
 * Market stalls (market/Plan.md §4). Creating one places a barrel on top of the block an administrator is looking at, with a
 * hologram above it, so the stall stays visible and the original block is untouched. It needs three clear blocks above. Right-clicking
 * the barrel opens the market, and the Stash can be emptied within a few blocks of one unless {@code stash.withdraw-anywhere} is on.
 * ponytail: block stalls only; a Citizens NPC stall would call the same {@code canWithdraw} and {@code Menus.main} (BACKLOG.md).
 */
public class Stalls implements Listener {
    public enum Result { CREATED, NO_SPACE, ALREADY, REMOVED, NOT_A_STALL }

    private static final int REACH = 6;
    private final MarketService svc;
    private final Menus menus;
    private final NamespacedKey marker;
    private final Set<Stall> all = ConcurrentHashMap.newKeySet();

    Stalls(MarketService svc, Menus menus) { this.svc = svc; this.menus = menus; this.marker = new NamespacedKey(svc.plugin, "stall"); }

    void load() { svc.run(svc.store::stalls).thenAccept(all::addAll); }

    private static Stall at(Block b) { return new Stall(b.getWorld().getName(), b.getX(), b.getY(), b.getZ()); }

    public boolean isStall(Block b) { return all.contains(at(b)); }

    /** Places a barrel and hologram on top of {@code target} (call on that block's thread). */
    public Result create(Block target) {
        Block barrel = target.getRelative(BlockFace.UP);
        if (all.contains(at(target)) || all.contains(at(barrel))) return Result.ALREADY;
        for (int i = 0; i < 3; i++) if (!barrel.getRelative(BlockFace.UP, i).getType().isAir()) return Result.NO_SPACE;
        barrel.setType(Material.BARREL);
        spawnHologram(barrel);
        Stall s = at(barrel);
        all.add(s);
        svc.run(() -> { svc.store.addStall(s); return null; });
        return Result.CREATED;
    }

    /** Removes the stall whose barrel is {@code barrel}, with its hologram. */
    public Result remove(Block barrel) {
        Stall s = at(barrel);
        if (!all.remove(s)) return Result.NOT_A_STALL;
        svc.run(() -> { svc.store.removeStall(s); return null; });
        removeHologram(barrel);
        if (barrel.getType() == Material.BARREL) barrel.setType(Material.AIR);   // never remove a block that isn't the stall's own barrel
        return Result.REMOVED;
    }

    /** The floating "Market Stall" sign above the barrel, tagged so only our own displays are ever removed. */
    void spawnHologram(Block barrel) {
        Location at = barrel.getLocation().add(0.5, 1.45, 0.5);
        barrel.getWorld().spawn(at, TextDisplay.class, d -> {
            d.text(svc.msg.get("stall-hologram"));
            d.setBillboard(Display.Billboard.CENTER);
            d.setPersistent(true);
            d.getPersistentDataContainer().set(marker, PersistentDataType.BYTE, (byte) 1);
        });
    }

    void removeHologram(Block barrel) {
        barrel.getWorld().getNearbyEntitiesByType(TextDisplay.class, barrel.getLocation().add(0.5, 1.5, 0.5), 1.2)
                .stream().filter(d -> d.getPersistentDataContainer().has(marker)).forEach(TextDisplay::remove);
    }

    public boolean canWithdraw(Player p) {
        if (svc.withdrawAnywhere) return true;
        var loc = p.getLocation();
        for (Stall s : all)
            if (s.world().equals(loc.getWorld().getName()) && Math.abs(s.x() - loc.getX()) <= REACH && Math.abs(s.y() - loc.getY()) <= REACH && Math.abs(s.z() - loc.getZ()) <= REACH) return true;
        return false;
    }

    @EventHandler public void onClick(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND || e.getClickedBlock() == null) return;
        if (!isStall(e.getClickedBlock())) return;
        e.setCancelled(true);
        menus.main(e.getPlayer());
    }

    /** A stall can't be dug up by players; administrators remove it with /market admin stall remove. */
    @EventHandler public void onBreak(BlockBreakEvent e) {
        if (!isStall(e.getBlock())) return;
        e.setCancelled(true);
        svc.msg.send(e.getPlayer(), "stall-protected");
    }

    @EventHandler public void onExplode(EntityExplodeEvent e) { e.blockList().removeIf(this::isStall); }
}
