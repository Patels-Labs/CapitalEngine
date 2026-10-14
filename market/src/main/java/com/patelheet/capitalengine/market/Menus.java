package com.patelheet.capitalengine.market;

import com.patelheet.capitalengine.market.MarketService.PriceInfo;
import com.patelheet.capitalengine.market.book.Matcher.Order;
import com.patelheet.capitalengine.market.storage.Store.Job;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * The chest menus (market/Plan.md §4, §8). Each screen fetches what it shows from the worker, then builds and opens on the
 * player's own thread. Buttons call {@link Actions}, the same code the commands use, so the two can never disagree.
 */
final class Menus {
    private final Actions a;
    private final MarketService svc;
    private final Messages msg;
    /** Asks "are you sure?" with a Yes/No screen. */
    final Actions.Confirm gui;

    Menus(Actions a, MarketService svc, Messages msg) {
        this.a = a; this.svc = svc; this.msg = msg;
        gui = (p, key, kv, yes) -> confirm(p, msg.get(key, kv), yes);
    }

    private Component t(String key, Object... kv) { return msg.get(key, kv); }

    private void back(Menu m, int slot, Runnable to) { m.set(slot, Menu.icon(Material.ARROW, t("btn-back")), to); }

    void confirm(Player p, Component question, Runnable yes) {
        Menu m = new Menu(t("menu-confirm"), 3);
        m.set(11, Menu.icon(Material.LIME_CONCRETE, t("btn-yes")), () -> { p.closeInventory(); yes.run(); });
        m.set(13, Menu.icon(Material.PAPER, question));
        m.set(15, Menu.icon(Material.RED_CONCRETE, t("btn-no")), p::closeInventory);
        m.open(p);
    }

    // ---- main --------------------------------------------------------------------------------------------------

    void main(Player p) {
        Menu m = new Menu(t("menu-main"), 3);
        m.set(10, Menu.icon(Material.GOLD_INGOT, t("btn-hand")), () -> { String k = a.hand(p); if (k != null) item(p, k); });
        m.set(11, Menu.icon(Material.BARREL, t("btn-browse")), () -> browse(p, 0));
        m.set(12, Menu.icon(Material.ENDER_CHEST, t("btn-stash")), () -> stash(p));
        m.set(13, Menu.icon(Material.WRITABLE_BOOK, t("btn-orders")), () -> orders(p));
        m.set(14, Menu.icon(Material.LECTERN, t("btn-jobs")), () -> jobs(p));
        m.set(15, Menu.icon(Material.EMERALD, t("btn-economy")), () -> economy(p));
        m.open(p);
    }

    // ---- items -------------------------------------------------------------------------------------------------

    void browse(Player p, int page) {
        List<String> all = svc.items();
        int pages = Math.max(1, (all.size() + 44) / 45), pg = Math.max(0, Math.min(page, pages - 1));
        List<String> slice = all.subList(Math.min(all.size(), pg * 45), Math.min(all.size(), pg * 45 + 45));
        a.then(p, svc.prices(slice), prices -> {
            Menu m = new Menu(t("menu-browse", "page", pg + 1, "pages", pages), 6);
            for (int i = 0; i < slice.size(); i++) {
                String key = slice.get(i);
                PriceInfo pi = prices.get(key);
                m.set(i, Menu.icon(key, t("btn-item", "item", Items.pretty(key), "ask", pi == null ? "-" : a.coins(pi.ask()), "bid", pi == null ? "-" : a.coins(pi.bid()))), () -> item(p, key));
            }
            if (pg > 0) m.set(45, Menu.icon(Material.ARROW, t("btn-prev")), () -> browse(p, pg - 1));
            if (pg < pages - 1) m.set(53, Menu.icon(Material.ARROW, t("btn-next")), () -> browse(p, pg + 1));
            back(m, 49, () -> main(p));
            m.open(p);
        });
    }

    void item(Player p, String key) {
        a.then(p, svc.prices(List.of(key)), prices -> {
            PriceInfo pi = prices.get(key);
            Menu m = new Menu(t("menu-item", "item", Items.pretty(key)), 6);
            m.set(4, Menu.icon(key, pi == null ? t("info-players-only", "item", Items.pretty(key))
                    : t("info-price", "item", Items.pretty(key), "ask", a.coins(pi.ask()), "bid", a.coins(pi.bid()), "stock", pi.stock())));
            int[] sells = {1, 16, 64, 0};
            for (int i = 0; i < sells.length; i++) {
                int n = sells[i];
                m.set(10 + i, Menu.icon(Material.LIME_DYE, n == 0 ? t("btn-sell-all") : t("btn-sell", "qty", n)), () -> a.startSell(p, key, n, gui));
            }
            int[] buys = {1, 16, 64};
            for (int i = 0; i < buys.length; i++) {
                int n = buys[i];
                m.set(19 + i, Menu.icon(Material.GOLD_NUGGET, t("btn-buy", "qty", n)), () -> a.startBuy(p, key, n, gui));
            }
            m.set(28, Menu.icon(Material.BOOK, t("btn-listings")), () -> listings(p, key));
            m.set(29, Menu.icon(Material.NAME_TAG, t("btn-list-mine")), () -> a.ask(p, "btn-list-mine", List.of("qty", "price"), v -> {
                int qty = a.whole(p, v.get(0));
                long unit = qty > 0 ? a.amount(p, v.get(1)) : 0;
                if (unit > 0) a.list(p, key, qty, unit);
            }));
            m.set(30, Menu.icon(Material.PAPER, t("btn-wanted")), () -> a.ask(p, "btn-wanted", List.of("qty", "price"), v -> {
                int qty = a.whole(p, v.get(0));
                long unit = qty > 0 ? a.amount(p, v.get(1)) : 0;
                if (unit > 0) a.startWanted(p, key, qty, unit, gui);
            }));
            back(m, 49, () -> browse(p, 0));
            m.open(p);
        });
    }

    /** Player listings (top) and wanted orders (bottom). Clicking one buys or sells up to and including it. */
    void listings(Player p, String key) {
        a.then(p, svc.book(key), book -> {
            Menu m = new Menu(t("menu-listings", "item", Items.pretty(key)), 6);
            int cum = 0;
            for (int i = 0; i < Math.min(18, book.asks().size()); i++) {
                Order o = book.asks().get(i);
                cum += o.qty();
                int upTo = cum;
                m.set(i, Menu.icon(key, t("btn-ask", "qty", o.qty(), "price", a.coins(o.price()), "total", upTo)), () -> a.startBuy(p, key, upTo, gui));
            }
            cum = 0;
            for (int i = 0; i < Math.min(18, book.bids().size()); i++) {
                Order o = book.bids().get(i);
                cum += o.qty();
                int upTo = cum;
                m.set(27 + i, Menu.icon(Material.PAPER, t("btn-bid", "qty", o.qty(), "price", a.coins(o.price()), "total", upTo)), () -> a.startSell(p, key, upTo, gui));
            }
            back(m, 49, () -> item(p, key));
            m.open(p);
        });
    }

    // ---- stash and orders --------------------------------------------------------------------------------------

    void stash(Player p) {
        a.then(p, svc.stash(p.getUniqueId()), stash -> {
            Menu m = new Menu(t("menu-stash"), 6);
            int i = 0;
            for (Map.Entry<String, int[]> e : stash.entrySet()) {
                if (i >= 45) break;
                String key = e.getKey();
                int qty = e.getValue()[0], locked = e.getValue()[1];
                m.set(i++, Menu.icon(key, t("btn-stash-item", "item", Items.pretty(key), "qty", qty, "locked", locked)),
                        () -> a.withdraw(p, key, Math.min(qty - locked, Items.stackSize(key)), () -> stash(p)));
            }
            m.set(48, Menu.icon(Material.HOPPER, t("btn-deposit")), () -> { String k = a.hand(p); if (k != null) a.deposit(p, k, a.count(p, k), () -> stash(p)); });
            back(m, 49, () -> main(p));
            m.open(p);
        });
    }

    void orders(Player p) {
        a.then(p, svc.orders(p.getUniqueId()), rows -> {
            Menu m = new Menu(t("menu-orders"), 6);
            for (int i = 0; i < Math.min(45, rows.size()); i++) {
                var o = rows.get(i);
                m.set(i, Menu.icon(o.item(), t("btn-order", "id", o.id(), "side", o.side().equals("BUY") ? "wanted" : "selling", "left", o.left(), "item", Items.pretty(o.item()), "price", a.coins(o.price()))),
                        () -> a.then(p, svc.cancel(p.getUniqueId(), o.id()), ok -> { a.say(p, ok ? "cancelled" : "cant-cancel", "id", o.id()); orders(p); }));
            }
            back(m, 49, () -> main(p));
            m.open(p);
        });
    }

    // ---- jobs --------------------------------------------------------------------------------------------------

    private String who(Job j) { return j.poster() == null ? (j.project() == null ? "The town" : j.project()) : svc.api.accounts().player(UUID.fromString(j.poster())).name(); }

    void jobs(Player p) {
        a.then(p, svc.jobs.open(), open -> {
            Menu m = new Menu(t("menu-jobs"), 6);
            for (int i = 0; i < Math.min(45, open.size()); i++) {
                Job j = open.get(i);
                Component name = j.kind().equals("BUILD") ? t("btn-job-build", "id", j.id(), "who", who(j), "note", j.note(), "pay", a.coins(j.pay()), "status", j.status().toLowerCase())
                        : t("btn-job", "id", j.id(), "who", who(j), "left", j.left(), "item", Items.pretty(j.item()), "pay", a.coins(j.pay()));
                m.set(i, j.kind().equals("BUILD") ? Menu.icon(Material.WRITABLE_BOOK, name) : Menu.icon(j.item(), name), () -> job(p, j.id()));
            }
            m.set(48, Menu.icon(Material.CHEST, t("btn-post-job")), () -> postDelivery(p));
            m.set(50, Menu.icon(Material.BRICKS, t("btn-post-build")), () -> postBuild(p));
            back(m, 49, () -> main(p));
            m.open(p);
        });
    }

    private void postDelivery(Player p) {
        String key = a.hand(p);
        if (key == null) return;
        a.ask(p, "btn-post-job", List.of("qty", "pay"), v -> {
            int qty = a.whole(p, v.get(0));
            long pay = qty > 0 ? a.amount(p, v.get(1)) : 0;
            if (pay > 0) confirm(p, t("confirm-job", "qty", qty, "item", Items.pretty(key), "pay", a.coins(pay), "total", a.coins(qty * pay)),
                    () -> a.then(p, svc.jobs.post(p.getUniqueId(), key, qty, pay), r -> a.say(p, r.error() != null ? r.error() : "job-posted", "id", r.orderId(), "qty", qty, "item", Items.pretty(key), "pay", a.coins(pay))));
        });
    }

    private void postBuild(Player p) {
        a.ask(p, "btn-post-build", List.of("pay-build", "note"), v -> {
            long pay = a.amount(p, v.get(0));
            String text = v.get(1).length() > 100 ? v.get(1).substring(0, 100) : v.get(1);
            if (pay > 0 && !text.isBlank()) confirm(p, t("confirm-build", "note", text, "pay", a.coins(pay)),
                    () -> a.then(p, svc.jobs.postBuild(p.getUniqueId(), pay, text), r -> a.say(p, r.error() != null ? r.error() : "build-posted", "id", r.orderId(), "pay", a.coins(pay))));
        });
    }

    void job(Player p, long id) {
        a.then(p, svc.jobs.get(id), j -> {
            if (j == null) { a.say(p, "job-none", "id", id); return; }
            boolean mine = p.getUniqueId().toString().equals(j.poster()), builder = p.getUniqueId().toString().equals(j.assignee());
            Menu m = new Menu(t("menu-job", "id", id), 6);
            Runnable again = () -> job(p, id);
            if (j.kind().equals("BUILD")) {
                m.set(4, Menu.icon(Material.WRITABLE_BOOK, t("btn-job-build", "id", id, "who", who(j), "note", j.note(), "pay", a.coins(j.pay()), "status", j.status().toLowerCase())));
                if (j.status().equals("OPEN") && !mine) m.set(11, Menu.icon(Material.LIME_CONCRETE, t("btn-accept")), () -> a.then(p, svc.jobs.accept(p.getUniqueId(), id), r -> { a.say(p, r.error() != null ? r.error() : "build-accepted", "id", id); again.run(); }));
                if (j.status().equals("ACCEPTED") && mine) m.set(11, Menu.icon(Material.EMERALD_BLOCK, t("btn-done")), () -> a.then(p, svc.jobs.finish(p.getUniqueId(), id, true), r -> { a.say(p, r.error() != null ? r.error() : "build-paid", "id", id, "pay", a.coins(j.pay())); jobs(p); }));
                if (j.status().equals("ACCEPTED") && (mine || builder)) m.set(15, Menu.icon(Material.RED_CONCRETE, t("btn-problem")), () -> a.then(p, svc.jobs.finish(p.getUniqueId(), id, false), r -> { a.say(p, r.error() != null ? r.error() : "build-problem", "id", id); again.run(); }));
            } else {
                m.set(4, Menu.icon(j.item(), t("btn-job", "id", id, "who", who(j), "left", j.left(), "item", Items.pretty(j.item()), "pay", a.coins(j.pay()))));
                if (!mine) {
                    int[] ns = {1, 16, 64, 0};
                    for (int i = 0; i < ns.length; i++) {
                        int n = ns[i];
                        m.set(10 + i, Menu.icon(Material.LIME_DYE, n == 0 ? t("btn-deliver-all") : t("btn-deliver", "qty", n)), () -> a.deliver(p, id, j.item(), n == 0 ? j.left() : n, again));
                    }
                }
            }
            if (mine && j.status().equals("OPEN")) m.set(22, Menu.icon(Material.BARRIER, t("btn-cancel-job")), () -> a.then(p, svc.jobs.cancel(p.getUniqueId(), id), r -> { a.say(p, r.error() != null ? r.error() : "job-cancelled", "id", id); jobs(p); }));
            back(m, 49, () -> jobs(p));
            m.open(p);
        });
    }

    // ---- economy -----------------------------------------------------------------------------------------------

    void economy(Player p) {
        svc.tutorial.advance(p.getUniqueId(), 4);
        a.then(p, svc.buyerOpen(), open -> {
            var pol = svc.policy;
            Menu m = new Menu(t("menu-economy"), 6);
            if (pol != null) {
                long pct = Math.round(pol.inflation() * 100);
                m.set(10, Menu.icon(Material.COMPASS, t(pct >= 0 ? "eco-prices-up" : "eco-prices-down", "pct", Math.abs(pct))));
                if (Bukkit.getPluginManager().isPluginEnabled("CapitalBank")) m.set(12, Menu.icon(Material.GOLD_BLOCK, t("eco-savings", "pct", String.format("%.1f", pol.rate() * 100))));
            }
            m.set(14, Menu.icon(open ? Material.LIME_DYE : Material.GRAY_DYE, t(open ? "eco-buying-open" : "eco-buying-paused")));
            List<Map.Entry<String, Double>> moves = new ArrayList<>(svc.trend.entrySet().stream().filter(e -> Math.abs(e.getValue() - 1) >= 0.005).toList());
            moves.sort(Map.Entry.<String, Double>comparingByValue().reversed());
            int slot = 19;
            for (var e : moves.stream().limit(7).toList()) m.set(slot++, trendIcon(e));
            slot = 28;
            for (var e : moves.reversed().stream().limit(7).filter(x -> x.getValue() < 1).toList()) m.set(slot++, trendIcon(e));
            back(m, 49, () -> main(p));
            m.open(p);
        });
    }

    private org.bukkit.inventory.ItemStack trendIcon(Map.Entry<String, Double> e) {
        long pct = Math.round((e.getValue() - 1) * 100);
        return Menu.icon(e.getKey(), t(pct >= 0 ? "eco-item-up" : "eco-item-down", "item", Items.pretty(e.getKey()), "pct", Math.abs(pct)));
    }
}
