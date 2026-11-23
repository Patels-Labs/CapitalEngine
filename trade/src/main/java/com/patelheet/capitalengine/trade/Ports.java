package com.patelheet.capitalengine.trade;

import com.patelheet.capitalengine.trade.storage.Store.Port;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;

/**
 * Ports (trade/Plan.md §5.5), made the same way as Market stalls: creating one places a barrel on top of the block an administrator
 * is looking at, with a floating "Port" sign above it. An administrator looking at an entity instead (a Citizens NPC, a named
 * villager) makes that entity a Port; it is recognised by its UUID, so no Citizens API is needed.
 * ponytail: an NPC Port remembers where it stood when it was made, for the "near a Port" check; a moved NPC needs its Port made again.
 */
public class Ports implements Listener {
    public enum Result { CREATED, NO_SPACE, ALREADY, NAME_TAKEN, REMOVED, NOT_A_PORT }

    private final TradeService svc;
    private final Menus menus;
    private final NamespacedKey marker;
    private final Set<Port> all = ConcurrentHashMap.newKeySet();

    Ports(TradeService svc, Menus menus) { this.svc = svc; this.menus = menus; this.marker = new NamespacedKey(svc.plugin, "port"); }

    void load() { svc.run(svc.store::ports).thenAccept(all::addAll); }

    public List<Port> list() { return all.stream().sorted(Comparator.comparing(Port::name)).toList(); }

    private Port blockPort(Block b) {
        for (Port p : all) if (p.npcId() == null && p.world().equals(b.getWorld().getName()) && p.x() == b.getX() && p.y() == b.getY() && p.z() == b.getZ()) return p;
        return null;
    }

    private Port npcPort(UUID entity) {
        for (Port p : all) if (entity.toString().equals(p.npcId())) return p;
        return null;
    }

    public boolean isPort(Block b) { return blockPort(b) != null; }

    private boolean nameTaken(String name) { return all.stream().anyMatch(p -> p.name().equalsIgnoreCase(name)); }

    private void save(Port p) {
        all.add(p);
        svc.run(() -> svc.store.addPort(p));
    }

    /** Places a barrel and hologram on top of {@code target} (call on that block's thread). */
    public Result create(Block target, String name) {
        if (nameTaken(name)) return Result.NAME_TAKEN;
        Block barrel = target.getRelative(BlockFace.UP);
        if (isPort(target) || isPort(barrel)) return Result.ALREADY;
        for (int i = 0; i < 3; i++) if (!barrel.getRelative(BlockFace.UP, i).getType().isAir()) return Result.NO_SPACE;
        barrel.setType(Material.BARREL);
        spawnHologram(barrel);
        save(new Port(0, name, barrel.getWorld().getName(), barrel.getX(), barrel.getY(), barrel.getZ(), "BLOCK", null));
        return Result.CREATED;
    }

    /** Makes an entity (for example a Citizens NPC) a Port. */
    public Result create(Entity npc, String name) {
        if (nameTaken(name)) return Result.NAME_TAKEN;
        if (npcPort(npc.getUniqueId()) != null) return Result.ALREADY;
        Location l = npc.getLocation();
        save(new Port(0, name, l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ(), "NPC", npc.getUniqueId().toString()));
        return Result.CREATED;
    }

    /** Removes a Port by name. A block Port's barrel and hologram go too. */
    public Result remove(String name) {
        Port p = all.stream().filter(x -> x.name().equalsIgnoreCase(name)).findFirst().orElse(null);
        if (p == null) return Result.NOT_A_PORT;
        all.remove(p);
        svc.run(() -> { svc.store.removePort(p.name()); return null; });
        var world = svc.plugin.getServer().getWorld(p.world());
        if (p.npcId() == null && world != null) {
            Block barrel = world.getBlockAt(p.x(), p.y(), p.z());
            removeHologram(barrel);
            if (barrel.getType() == Material.BARREL) barrel.setType(Material.AIR);   // never remove a block that isn't the Port's own barrel
        }
        return Result.REMOVED;
    }

    /** The floating "Port" sign above the barrel, tagged so only our own displays are ever removed. */
    void spawnHologram(Block barrel) {
        Location at = barrel.getLocation().add(0.5, 1.45, 0.5);
        barrel.getWorld().spawn(at, TextDisplay.class, d -> {
            d.text(svc.msg.get("port-hologram"));
            d.setBillboard(Display.Billboard.CENTER);
            d.setPersistent(true);
            d.getPersistentDataContainer().set(marker, PersistentDataType.BYTE, (byte) 1);
        });
    }

    void removeHologram(Block barrel) {
        barrel.getWorld().getNearbyEntitiesByType(TextDisplay.class, barrel.getLocation().add(0.5, 1.5, 0.5), 1.2)
                .stream().filter(d -> d.getPersistentDataContainer().has(marker)).forEach(TextDisplay::remove);
    }

    /** Whether the player may trade here: within {@code ports.radius} of a Port, or anywhere if the owner allows it. */
    public boolean atPort(Player p) {
        if (svc.anywhere) return true;
        Location loc = p.getLocation();
        int r = svc.radius;
        for (Port s : all)
            if (s.world().equals(loc.getWorld().getName()) && Math.abs(s.x() - loc.getX()) <= r && Math.abs(s.y() - loc.getY()) <= r && Math.abs(s.z() - loc.getZ()) <= r) return true;
        return false;
    }

    @EventHandler public void onClick(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND || e.getClickedBlock() == null || !isPort(e.getClickedBlock())) return;
        e.setCancelled(true);
        menus.main(e.getPlayer());
    }

    @EventHandler public void onClickNpc(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || npcPort(e.getRightClicked().getUniqueId()) == null) return;
        e.setCancelled(true);
        menus.main(e.getPlayer());
    }

    /** A Port can't be dug up by players; administrators remove it with /trade admin port remove. */
    @EventHandler public void onBreak(BlockBreakEvent e) {
        if (!isPort(e.getBlock())) return;
        e.setCancelled(true);
        svc.msg.send(e.getPlayer(), "port-protected");
    }

    @EventHandler public void onExplode(EntityExplodeEvent e) { e.blockList().removeIf(this::isPort); }
}
