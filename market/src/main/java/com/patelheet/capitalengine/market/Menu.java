package com.patelheet.capitalengine.market;

import java.util.HashMap;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * A plain chest menu of icon buttons (at most 54 slots, so Geyser can show it to Bedrock players). Every button's key
 * information is in its name, because Bedrock doesn't reliably show lore.
 */
final class Menu implements InventoryHolder {
    private final Inventory inv;
    private final Map<Integer, Runnable> clicks = new HashMap<>();

    Menu(Component title, int rows) { inv = Bukkit.createInventory(this, rows * 9, title); }

    Menu set(int slot, ItemStack icon, Runnable click) {
        inv.setItem(slot, icon);
        if (click != null) clicks.put(slot, click);
        return this;
    }

    Menu set(int slot, ItemStack icon) { return set(slot, icon, null); }

    void open(Player p) { p.openInventory(inv); }

    @Override public Inventory getInventory() { return inv; }

    static ItemStack icon(Material m, Component name, Component... lore) { return label(new ItemStack(m), name, lore); }

    /** The real item for a key (so books and potions look right), or a barrier if the key is bad. */
    static ItemStack icon(String key, Component name, Component... lore) {
        ItemStack s = Items.one(key);
        return label(s == null ? new ItemStack(Material.BARRIER) : s, name, lore);
    }

    private static ItemStack label(ItemStack s, Component name, Component... lore) {
        s.editMeta(m -> {
            m.displayName(name.decoration(TextDecoration.ITALIC, false));
            if (lore.length > 0) m.lore(java.util.Arrays.stream(lore).map(c -> c.decoration(TextDecoration.ITALIC, false)).toList());
        });
        return s;
    }

    /** Menus are read-only: nothing can be taken out, put in or dragged. */
    static final class Guard implements Listener {
        @EventHandler public void onClick(InventoryClickEvent e) {
            if (!(e.getView().getTopInventory().getHolder() instanceof Menu m)) return;
            e.setCancelled(true);
            if (e.getClickedInventory() == e.getView().getTopInventory()) {
                Runnable r = m.clicks.get(e.getSlot());
                if (r != null) r.run();
            }
        }

        @EventHandler public void onDrag(InventoryDragEvent e) {
            if (e.getView().getTopInventory().getHolder() instanceof Menu) e.setCancelled(true);
        }
    }
}
