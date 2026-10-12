package com.patelheet.capitalengine.core.gui;

import com.patelheet.capitalengine.api.Posting;
import com.patelheet.capitalengine.api.TxRecord;
import com.patelheet.capitalengine.core.Messages;
import com.patelheet.capitalengine.core.api.ApiImpl;
import com.patelheet.capitalengine.core.api.CurrencyImpl;
import com.patelheet.capitalengine.core.ledger.Acct;
import java.util.List;
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
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * Balance, last 10 transactions in plain words, and a "Pay a friend" button. A plain 36-slot chest so it also works for
 * Bedrock players through Geyser. Everything important is in the item <em>name</em> (Bedrock doesn't show lore).
 */
public final class MoneyMenu implements InventoryHolder, Listener {
    private static final int BALANCE_SLOT = 4, HISTORY_FROM = 9, PAY_SLOT = 31;

    private final Plugin plugin;
    private final ApiImpl api;
    private final CurrencyImpl currency;
    private final Messages msgs;
    private Inventory inventory;

    public MoneyMenu(Plugin plugin, ApiImpl api, CurrencyImpl currency, Messages msgs) {
        this.plugin = plugin; this.api = api; this.currency = currency; this.msgs = msgs;
    }

    @Override public @NotNull Inventory getInventory() { return inventory; }

    /** Loads the history off the main thread, then opens on the player's own thread. */
    public void open(Player p) {
        Acct acct = (Acct) api.accounts().player(p.getUniqueId());
        api.history(acct, 10, Long.MAX_VALUE).whenComplete((history, err) ->
                p.getScheduler().run(plugin, task -> show(p, acct, err == null ? history : List.of()), null));
    }

    private void show(Player p, Acct acct, List<TxRecord> history) {
        MoneyMenu menu = new MoneyMenu(plugin, api, currency, msgs);
        Inventory inv = Bukkit.createInventory(menu, 36, msgs.get("gui.title"));
        menu.inventory = inv;
        inv.setItem(BALANCE_SLOT, item(Material.GOLD_INGOT, msgs.get("gui.balance", "amount", currency.format(acct.balance()))));
        if (history.isEmpty()) inv.setItem(HISTORY_FROM, item(Material.BARRIER, msgs.get("gui.history-empty")));
        for (int i = 0; i < history.size(); i++) {
            TxRecord r = history.get(i);
            long delta = r.postings().stream().filter(x -> x.accountId().equals(acct.id())).mapToLong(Posting::amount).sum();
            inv.setItem(HISTORY_FROM + i, item(delta >= 0 ? Material.LIME_DYE : Material.RED_DYE,
                    msgs.get(delta >= 0 ? "gui.history-in" : "gui.history-out", "amount", currency.format(Math.abs(delta)), "memo", r.memo())));
        }
        inv.setItem(PAY_SLOT, item(Material.EMERALD, msgs.get("gui.pay-button")));
        p.openInventory(inv);
    }

    private static ItemStack item(Material m, Component name) {
        ItemStack s = new ItemStack(m);
        ItemMeta meta = s.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        s.setItemMeta(meta);
        return s;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof MoneyMenu)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() == e.getView().getTopInventory() && e.getSlot() == PAY_SLOT && e.getWhoClicked() instanceof Player p) {
            p.closeInventory();
            msgs.send(p, "gui.pay-hint");   // chat prompt: no anvil/sign input, so Bedrock works too
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof MoneyMenu) e.setCancelled(true);
    }
}
