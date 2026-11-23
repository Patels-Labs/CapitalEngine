package com.patelheet.capitalengine.bank;

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

    /** Fills every empty slot with a blank pane so the layout reads as a deliberate grid, then shows the menu. */
    void open(Player p) {
        ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        pane.editMeta(m -> { m.setHideTooltip(true); });
        for (int i = 0; i < inv.getSize(); i++) if (inv.getItem(i) == null) inv.setItem(i, pane);
        p.openInventory(inv);
    }

    /** A coloured section label (a named pane). */
    static ItemStack pane(Material glass, Component name, Component... lore) { return icon(glass, name, lore); }

    /** Slots for {@code n} buttons centred on a row of nine (row 0 is the first row). */
    static int[] centered(int row, int n) {
        int[] slots = new int[n];
        int start = (9 - n) / 2;
        for (int i = 0; i < n; i++) slots[i] = row * 9 + start + i;
        return slots;
    }

    @Override public Inventory getInventory() { return inv; }

    static ItemStack icon(Material type, Component name, Component... lore) {
        ItemStack s = new ItemStack(type);
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
