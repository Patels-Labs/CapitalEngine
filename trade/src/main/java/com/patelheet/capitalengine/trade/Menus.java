package com.patelheet.capitalengine.trade;

import com.patelheet.capitalengine.trade.TradeService.Row;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * The Port menus (trade/Plan.md §5.5): Export, Import (only when imports are configured), Port News and Cargo. Each screen fetches
 * what it shows from the worker, then builds and opens on the player's own thread. Buttons call {@link Actions}, the same code
 * the commands use. Every figure a player decides on is in the button's name or the confirmation line, as Bedrock may hide lore.
 */
final class Menus {
    static final int[] QTY = {1, 16, 64};

    private final Actions a;
    private final TradeService svc;
    private final Messages msg;
    /** Asks for confirmation with a Yes/No screen. */
    final Actions.Confirm gui;

    Menus(Actions a, TradeService svc, Messages msg) {
        this.a = a; this.svc = svc; this.msg = msg;
        gui = (p, key, kv, yes) -> confirm(p, msg.get(key.replace("confirm-", "menu-confirm-"), kv), yes);
    }

    private Component t(String key, Object... kv) { return msg.get(key, kv); }

    void confirm(Player p, Component question, Runnable yes) {
        Menu m = new Menu(t("menu-confirm"), 3);
        m.set(11, Menu.icon(Material.LIME_CONCRETE, t("btn-yes")), () -> { p.closeInventory(); yes.run(); });
        m.set(13, Menu.icon(Material.PAPER, question));
        m.set(15, Menu.icon(Material.RED_CONCRETE, t("btn-no")), p::closeInventory);
        m.open(p);
    }

    /** The Port's front page. Opens only at a Port. */
    void main(Player p) {
        if (!a.atPort(p)) return;
        boolean imports = !svc.imports.isEmpty();
        Menu m = new Menu(t("menu-port"), 3);
        int[] s = Menu.centered(1, imports ? 4 : 3);
        int i = 0;
        m.set(s[i++], Menu.icon(Material.OAK_BOAT, t("btn-export"), t("lore-export")), () -> board(p, true, 0));
        if (imports) m.set(s[i++], Menu.icon(Material.EMERALD, t("btn-import"), t("lore-import")), () -> board(p, false, 0));
        m.set(s[i++], Menu.icon(Material.BELL, t("btn-news"), t("lore-news")), () -> { p.closeInventory(); news(p); });
        m.set(s[i], Menu.icon(Material.BARREL, t("btn-cargo"), t("lore-cargo")), () -> { p.closeInventory(); a.collect(p, false); });
        m.set(26, Menu.icon(Material.KNOWLEDGE_BOOK, t("btn-help")), () -> { p.closeInventory(); a.help(p, ""); });
        m.open(p);
    }

    /** Port News in chat, so every client shows all of it. */
    void news(Player p) { a.then(p, svc.news(), lines -> { p.sendMessage(t("news-header")); lines.forEach(p::sendMessage); }); }

    private String change(long now, long prev) {
        if (prev <= 0 || now == prev) return "=";
        long pct = Math.round((now - prev) * 100.0 / prev);
        return pct == 0 ? "=" : (pct > 0 ? "▲ " : "▼ ") + Math.abs(pct) + "%";
    }

    /** The Export or Import tab: up to 45 items a page. */
    void board(Player p, boolean export, int page) {
        a.then(p, svc.board(export), rows -> {
            int pages = Math.max(1, (rows.size() + 44) / 45), pg = Math.max(0, Math.min(page, pages - 1));
            List<Row> slice = rows.subList(Math.min(rows.size(), pg * 45), Math.min(rows.size(), pg * 45 + 45));
            Menu m = new Menu(t(export ? "menu-export" : "menu-import", "page", pg + 1, "pages", pages), 6);
            for (int i = 0; i < slice.size(); i++) {
                Row r = slice.get(i);
                boolean boom = export && r.item().equals(svc.boomItem);
                m.set(i, Menu.icon(r.item(), t("item-name", "item", Items.name(r.item()), "price", a.coins(r.price()), "change", change(r.price(), r.prev())),
                        t(export ? "lore-ships-take" : "lore-ships-bring", "qty", r.quotaLeft()), t(export ? "lore-next-pays" : "lore-next-costs", "price", a.coins(r.unit())),
                        boom ? t("lore-boom") : Component.empty()), () -> item(p, r.item(), export));
            }
            if (pg > 0) m.set(45, Menu.icon(Material.ARROW, t("btn-prev")), () -> board(p, export, pg - 1));
            m.set(49, Menu.icon(Material.ARROW, t("btn-back")), () -> main(p));
            if (pg < pages - 1) m.set(53, Menu.icon(Material.ARROW, t("btn-next")), () -> board(p, export, pg + 1));
            m.open(p);
        });
    }

    /** One item: Sell 1 / 16 / 64 / All (export) or Buy 1 / 16 / 64 (import), and a typed amount. */
    void item(Player p, String key, boolean export) {
        a.then(p, svc.quotaLeft(key, export), left -> {
            Menu m = new Menu(t(export ? "menu-item-export" : "menu-item-import", "item", Items.name(key)), 3);
            m.set(4, Menu.icon(key, Items.name(key), t(export ? "lore-ships-take" : "lore-ships-bring", "qty", left)));
            int[] s = Menu.centered(1, export ? 5 : 4);
            for (int i = 0; i < QTY.length; i++) {
                int n = QTY[i];
                m.set(s[i], Menu.icon(export ? Material.LIME_DYE : Material.ORANGE_DYE, t(export ? "btn-sell" : "btn-buy", "qty", n)).asQuantity(n), () -> go(p, key, export, n));
            }
            if (export) m.set(s[3], Menu.icon(Material.HOPPER, t("btn-sell-all")), () -> go(p, key, true, 0));
            m.set(s[export ? 4 : 3], Menu.icon(Material.OAK_SIGN, t("btn-amount")), () -> a.askAmount(p, n -> go(p, key, export, n)));
            m.set(18, Menu.icon(Material.ARROW, t("btn-back")), () -> board(p, export, 0));
            m.open(p);
        });
    }

    private void go(Player p, String key, boolean export, int qty) {
        if (export) a.startExport(p, key, qty, gui); else a.startImport(p, key, qty, gui);
    }
}
