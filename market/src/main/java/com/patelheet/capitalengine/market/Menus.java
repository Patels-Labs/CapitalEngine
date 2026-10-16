package com.patelheet.capitalengine.market;

import com.patelheet.capitalengine.market.MarketService.PriceInfo;
import com.patelheet.capitalengine.market.book.Matcher.Order;
import com.patelheet.capitalengine.market.storage.Store.Job;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntConsumer;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * The chest menus (market/Plan.md §4, §8). Each screen fetches what it shows from the worker, then builds and opens on the
 * player's own thread. Buttons call {@link Actions}, the same code the commands use, so the two can never disagree.
 * Buttons are named for what they do; the lore lines carry the figures. Where a figure matters for a decision (a total, a price)
 * it is also in the name or in the confirmation chat line, because Bedrock doesn't reliably show lore.
 */
final class Menus {
    /** Slots of the sell and buy button groups on an item page (row 1), mirrored around the centre column. */
    static final int[] SELL_SLOTS = {10, 11, 12}, BUY_SLOTS = {14, 15, 16}, SELL_ALL_SLOT = {20};
    static final int[] QTY = {1, 16, 64};

    private final Actions a;
    private final MarketService svc;
    private final Messages msg;
    /** Asks for confirmation with a Yes/No screen. */
    final Actions.Confirm gui;

    Menus(Actions a, MarketService svc, Messages msg) {
        this.a = a; this.svc = svc; this.msg = msg;
        gui = (p, key, kv, yes) -> confirm(p, msg.get(key, kv), yes);
    }

    private Component t(String key, Object... kv) { return msg.get(key, kv); }

    private void back(Menu m, int slot, Runnable to) { m.set(slot, Menu.icon(Material.ARROW, t("btn-back"), t("lore-back")), to); }

    void confirm(Player p, Component question, Runnable yes) {
        Menu m = new Menu(t("menu-confirm"), 3);
        m.set(11, Menu.icon(Material.LIME_CONCRETE, t("btn-yes"), t("lore-yes")), () -> { p.closeInventory(); yes.run(); });
        m.set(13, Menu.icon(Material.PAPER, question));
        m.set(15, Menu.icon(Material.RED_CONCRETE, t("btn-no"), t("lore-no")), p::closeInventory);
        m.open(p);
    }

    private String change(Double ratio) {
        if (ratio == null) return "-";
        long pct = Math.round((ratio - 1) * 100);
        return (pct >= 0 ? "▲ " : "▼ ") + Math.abs(pct) + "%";
    }

    // ---- main --------------------------------------------------------------------------------------------------

    void main(Player p) {
        Menu m = new Menu(t("menu-main"), 3);
        int[] s = Menu.centered(1, 7);
        m.set(s[0], Menu.icon(Material.GOLD_INGOT, t("btn-hand"), t("lore-hand")), () -> { String k = a.hand(p); if (k != null) item(p, k); });
        m.set(s[1], Menu.icon(Material.BARREL, t("btn-browse"), t("lore-browse")), () -> browse(p, 0));
        m.set(s[2], Menu.icon(Material.SPYGLASS, t("btn-search"), t("lore-search")), () -> search(p));
        m.set(s[3], Menu.icon(Material.ENDER_CHEST, t("btn-stash"), t("lore-stash")), () -> stash(p));
        m.set(s[4], Menu.icon(Material.WRITABLE_BOOK, t("btn-orders"), t("lore-orders")), () -> orders(p));
        m.set(s[5], Menu.icon(Material.LECTERN, t("btn-jobs"), t("lore-jobs")), () -> jobs(p));
        m.set(s[6], Menu.icon(Material.EMERALD, t("btn-economy"), t("lore-economy")), () -> economy(p));
        m.set(26, Menu.icon(Material.KNOWLEDGE_BOOK, t("btn-help"), t("lore-help")), () -> { p.closeInventory(); a.help(p, ""); });
        m.open(p);
    }

    // ---- browsing and search -----------------------------------------------------------------------------------

    void browse(Player p, int page) { itemList(p, svc.items(), page, t("menu-browse"), pg -> browse(p, pg), () -> main(p)); }

    void search(Player p) {
        a.ask(p, "btn-search", List.of("search"), v -> {
            String q = v.get(0).trim().toLowerCase();
            if (q.isEmpty()) return;
            List<String> hits = svc.items().stream().filter(k -> k.contains(q.replace(' ', '_')) || Items.pretty(k).toLowerCase().contains(q)).toList();
            if (!hits.isEmpty()) { itemList(p, hits, 0, t("menu-search", "query", v.get(0).trim()), pg -> searchResults(p, hits, v.get(0).trim(), pg), () -> main(p)); return; }
            String key = Items.parse(q);   // not something the market stocks, but players may still trade it with each other
            if (key != null) item(p, key); else a.say(p, "search-none", "query", v.get(0).trim());
        });
    }

    private void searchResults(Player p, List<String> hits, String query, int page) {
        itemList(p, hits, page, t("menu-search", "query", query), pg -> searchResults(p, hits, query, pg), () -> main(p));
    }

    /** A page of up to 45 items with Buy and Sell prices in the lore, previous/next arrows and a search button along the bottom. */
    private void itemList(Player p, List<String> all, int page, Component title, IntConsumer goTo, Runnable back) {
        int pages = Math.max(1, (all.size() + 44) / 45), pg = Math.max(0, Math.min(page, pages - 1));
        List<String> slice = all.subList(Math.min(all.size(), pg * 45), Math.min(all.size(), pg * 45 + 45));
        a.then(p, svc.prices(slice), prices -> {
            Menu m = new Menu(title.append(t("menu-page", "page", pg + 1, "pages", pages)), 6);
            for (int i = 0; i < slice.size(); i++) {
                String key = slice.get(i);
                PriceInfo pi = prices.get(key);
                m.set(i, Menu.icon(key, Items.name(key), priceLore(key, pi, true)), () -> item(p, key));
            }
            if (pg > 0) m.set(45, Menu.icon(Material.ARROW, t("btn-prev"), t("lore-page", "page", pg, "pages", pages)), () -> goTo.accept(pg - 1));
            m.set(47, Menu.icon(Material.SPYGLASS, t("btn-search"), t("lore-search")), () -> search(p));
            back(m, 49, back);
            if (pg < pages - 1) m.set(53, Menu.icon(Material.ARROW, t("btn-next"), t("lore-page", "page", pg + 2, "pages", pages)), () -> goTo.accept(pg + 1));
            m.open(p);
        });
    }

    private Component[] priceLore(String key, PriceInfo pi, boolean withAction) {
        List<Component> l = new ArrayList<>();
        if (pi == null) l.add(t("lore-players-only"));
        else {
            l.add(t("lore-buy-price", "price", a.coins(pi.ask())));
            l.add(t("lore-sell-price", "price", a.coins(pi.bid())));
            l.add(t("lore-stock", "stock", pi.stock()));
            Double tr = svc.trend.get(key);
            if (tr != null) l.add(t(tr >= 1 ? "lore-trend-up" : "lore-trend-down", "change", change(tr)));
        }
        if (withAction) l.add(t("lore-click-view"));
        return l.toArray(new Component[0]);
    }

    // ---- an item -----------------------------------------------------------------------------------------------

    void item(Player p, String key) {
        a.then(p, svc.prices(List.of(key)), prices -> {
            PriceInfo pi = prices.get(key);
            Menu m = new Menu(Items.name(key), 6);
            m.set(4, Menu.icon(key, Items.name(key), priceLore(key, pi, false)));
            m.set(2, Menu.pane(Material.LIME_STAINED_GLASS_PANE, t("hdr-sell"), t("lore-hdr-sell")));
            m.set(6, Menu.pane(Material.ORANGE_STAINED_GLASS_PANE, t("hdr-buy"), t("lore-hdr-buy")));
            for (int i = 0; i < QTY.length; i++) {
                int n = QTY[i];
                m.set(SELL_SLOTS[i], Menu.icon(Material.LIME_CONCRETE, t("btn-sell", "qty", n), sellLore(pi, n)), () -> a.startSell(p, key, n, gui));
                m.set(BUY_SLOTS[i], Menu.icon(Material.ORANGE_CONCRETE, t("btn-buy", "qty", n), buyLore(pi, n)), () -> a.startBuy(p, key, n, gui));
            }
            m.set(SELL_ALL_SLOT[0], Menu.icon(Material.EMERALD_BLOCK, t("btn-sell-all"), sellLore(pi, 0)), () -> a.startSell(p, key, 0, gui));
            m.set(29, Menu.icon(Material.BOOK, t("btn-listings"), t("lore-listings")), () -> listings(p, key));
            m.set(31, Menu.icon(Material.NAME_TAG, t("btn-list-mine"), t("lore-list-mine")), () -> a.ask(p, "btn-list-mine", List.of("qty", "price"), v -> {
                int qty = a.whole(p, v.get(0));
                long unit = qty > 0 ? a.amount(p, v.get(1)) : 0;
                if (unit > 0) a.list(p, key, qty, unit);
            }));
            m.set(33, Menu.icon(Material.PAPER, t("btn-wanted"), t("lore-wanted")), () -> a.ask(p, "btn-wanted", List.of("qty", "price"), v -> {
                int qty = a.whole(p, v.get(0));
                long unit = qty > 0 ? a.amount(p, v.get(1)) : 0;
                if (unit > 0) a.startWanted(p, key, qty, unit, gui);
            }));
            back(m, 49, () -> browse(p, 0));
            m.open(p);
        });
    }

    private Component[] sellLore(PriceInfo pi, int n) {
        if (pi == null) return new Component[]{t("lore-players-only")};
        List<Component> l = new ArrayList<>();
        if (n > 0) l.add(t("lore-approx", "total", a.coins(pi.bid() * n)));
        l.add(t("lore-rate", "price", a.coins(pi.bid())));
        l.add(pi.canBuy() > 0 ? t("lore-capacity", "units", pi.canBuy()) : t("lore-capacity-none"));
        return l.toArray(new Component[0]);
    }

    private Component[] buyLore(PriceInfo pi, int n) {
        if (pi == null) return new Component[]{t("lore-players-only")};
        return new Component[]{t("lore-approx-cost", "total", a.coins(pi.ask() * n)), t("lore-rate", "price", a.coins(pi.ask())), t("lore-in-stock", "units", pi.stock())};
    }

    /** Player listings (top) and wanted orders (bottom). Clicking one buys or sells up to and including it. */
    void listings(Player p, String key) {
        a.then(p, svc.book(key), book -> {
            Menu m = new Menu(t("menu-listings", "item", Items.pretty(key)), 6);
            m.set(0, Menu.pane(Material.ORANGE_STAINED_GLASS_PANE, t("hdr-asks"), t("lore-hdr-asks")));
            m.set(27, Menu.pane(Material.LIME_STAINED_GLASS_PANE, t("hdr-bids"), t("lore-hdr-bids")));
            int cum = 0;
            for (int i = 0; i < Math.min(8, book.asks().size()); i++) {
                Order o = book.asks().get(i);
                cum += o.qty();
                int upTo = cum;
                m.set(1 + i, Menu.icon(key, t("btn-ask", "qty", o.qty(), "price", a.coins(o.price())), t("lore-cumulative", "total", upTo), t("lore-click-buy", "total", upTo)), () -> a.startBuy(p, key, upTo, gui));
            }
            cum = 0;
            for (int i = 0; i < Math.min(8, book.bids().size()); i++) {
                Order o = book.bids().get(i);
                cum += o.qty();
                int upTo = cum;
                m.set(28 + i, Menu.icon(Material.PAPER, t("btn-bid", "qty", o.qty(), "price", a.coins(o.price())), t("lore-cumulative", "total", upTo), t("lore-click-sell", "total", upTo)), () -> a.startSell(p, key, upTo, gui));
            }
            if (book.asks().isEmpty()) m.set(4, Menu.icon(Material.BARRIER, t("none-asks")));
            if (book.bids().isEmpty()) m.set(31, Menu.icon(Material.BARRIER, t("none-bids")));
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
                m.set(i++, Menu.icon(key, Items.name(key), t("lore-qty", "qty", qty), t("lore-listed", "qty", locked), t("lore-available", "qty", qty - locked), t("lore-click-withdraw")),
                        () -> a.withdraw(p, key, Math.min(qty - locked, Items.stackSize(key)), () -> stash(p)));
            }
            if (stash.isEmpty()) m.set(22, Menu.icon(Material.BARRIER, t("none-stash")));
            m.set(48, Menu.icon(Material.HOPPER, t("btn-deposit"), t("lore-deposit")), () -> { String k = a.hand(p); if (k != null) a.deposit(p, k, a.count(p, k), () -> stash(p)); });
            back(m, 49, () -> main(p));
            m.open(p);
        });
    }

    void orders(Player p) {
        a.then(p, svc.orders(p.getUniqueId()), rows -> {
            Menu m = new Menu(t("menu-orders"), 6);
            for (int i = 0; i < Math.min(45, rows.size()); i++) {
                var o = rows.get(i);
                boolean buy = o.side().equals("BUY");
                m.set(i, Menu.icon(o.item(), t(buy ? "btn-order-buy" : "btn-order-sell", "id", o.id()), t("lore-order", "item", Items.name(o.item()), "left", o.left(), "price", a.coins(o.price())), t("lore-click-cancel")),
                        () -> a.then(p, svc.cancel(p.getUniqueId(), o.id()), ok -> { a.say(p, ok ? "cancelled" : "cant-cancel", "id", o.id()); orders(p); }));
            }
            if (rows.isEmpty()) m.set(22, Menu.icon(Material.BARRIER, t("none-orders")));
            back(m, 49, () -> main(p));
            m.open(p);
        });
    }

    // ---- jobs --------------------------------------------------------------------------------------------------

    private String who(Job j) { return j.poster() == null ? (j.project() == null ? msg.plain("who-town") : j.project()) : svc.api.accounts().player(UUID.fromString(j.poster())).name(); }

    private ItemStack jobIcon(Job j) {
        if (j.kind().equals("BUILD"))
            return Menu.icon(Material.WRITABLE_BOOK, t("btn-job-build", "id", j.id()), t("lore-job-by", "who", who(j)), t("lore-job-note", "note", j.note()), t("lore-job-pay-total", "pay", a.coins(j.pay())), t("lore-job-status", "status", t("status-" + j.status().toLowerCase())));
        return Menu.icon(j.item(), t("btn-job", "id", j.id(), "item", Items.name(j.item())), t("lore-job-by", "who", who(j)), t("lore-job-needs", "left", j.left(), "total", j.total()), t("lore-job-pay", "pay", a.coins(j.pay())));
    }

    void jobs(Player p) {
        a.then(p, svc.jobs.open(), open -> {
            Menu m = new Menu(t("menu-jobs"), 6);
            for (int i = 0; i < Math.min(45, open.size()); i++) { Job j = open.get(i); m.set(i, jobIcon(j), () -> job(p, j.id())); }
            if (open.isEmpty()) m.set(22, Menu.icon(Material.BARRIER, t("none-jobs")));
            m.set(48, Menu.icon(Material.CHEST, t("btn-post-job"), t("lore-post-job")), () -> postDelivery(p));
            m.set(50, Menu.icon(Material.BRICKS, t("btn-post-build"), t("lore-post-build")), () -> postBuild(p));
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
            m.set(4, jobIcon(j));
            if (j.kind().equals("BUILD")) {
                if (j.status().equals("OPEN") && !mine) m.set(20, Menu.icon(Material.LIME_CONCRETE, t("btn-accept"), t("lore-accept")), () -> a.then(p, svc.jobs.accept(p.getUniqueId(), id), r -> { a.say(p, r.error() != null ? r.error() : "build-accepted", "id", id); again.run(); }));
                if (j.status().equals("ACCEPTED") && mine) m.set(20, Menu.icon(Material.EMERALD_BLOCK, t("btn-done"), t("lore-done")), () -> a.then(p, svc.jobs.finish(p.getUniqueId(), id, true), r -> { a.say(p, r.error() != null ? r.error() : "build-paid", "id", id, "pay", a.coins(j.pay())); jobs(p); }));
                if (j.status().equals("ACCEPTED") && (mine || builder)) m.set(24, Menu.icon(Material.RED_CONCRETE, t("btn-problem"), t("lore-problem")), () -> a.then(p, svc.jobs.finish(p.getUniqueId(), id, false), r -> { a.say(p, r.error() != null ? r.error() : "build-problem", "id", id); again.run(); }));
            } else if (!mine) {
                int[] ns = {1, 16, 64, 0};
                int[] slots = Menu.centered(2, ns.length);
                for (int i = 0; i < ns.length; i++) {
                    int n = ns[i];
                    m.set(slots[i], Menu.icon(Material.LIME_CONCRETE, n == 0 ? t("btn-deliver-all") : t("btn-deliver", "qty", n), t("lore-deliver", "pay", a.coins(j.pay()))), () -> a.deliver(p, id, j.item(), n == 0 ? j.left() : n, again));
                }
            }
            if (mine && j.status().equals("OPEN")) m.set(31, Menu.icon(Material.BARRIER, t("btn-cancel-job"), t("lore-cancel-job")), () -> a.then(p, svc.jobs.cancel(p.getUniqueId(), id), r -> { a.say(p, r.error() != null ? r.error() : "job-cancelled", "id", id); jobs(p); }));
            back(m, 49, () -> jobs(p));
            m.open(p);
        });
    }

    // ---- market overview ---------------------------------------------------------------------------------------

    void economy(Player p) {
        svc.tutorial.advance(p.getUniqueId(), 4);
        a.then(p, svc.buyerOpen(), open -> {
            var pol = svc.policy;
            Menu m = new Menu(t("menu-economy"), 6);
            List<ItemStack> tiles = new ArrayList<>();
            if (pol != null) {
                long pct = Math.round(pol.inflation() * 100);
                tiles.add(Menu.icon(Material.COMPASS, t("eco-prices"), t(pct >= 0 ? "eco-prices-up" : "eco-prices-down", "pct", Math.abs(pct)), t("lore-eco-prices")));
                if (Bukkit.getPluginManager().isPluginEnabled("CapitalBank")) tiles.add(Menu.icon(Material.GOLD_BLOCK, t("eco-savings"), t("eco-savings-rate", "pct", String.format("%.1f", pol.rate() * 100))));
            }
            tiles.add(Menu.icon(open ? Material.LIME_DYE : Material.GRAY_DYE, t("eco-purchasing"), t(open ? "eco-buying-open" : "eco-buying-paused")));
            int[] slots = Menu.centered(1, tiles.size());
            for (int i = 0; i < tiles.size(); i++) m.set(slots[i], tiles.get(i));

            List<Map.Entry<String, Double>> moves = new ArrayList<>(svc.trend.entrySet().stream().filter(e -> Math.abs(e.getValue() - 1) >= 0.005).toList());
            moves.sort(Map.Entry.<String, Double>comparingByValue().reversed());
            List<Map.Entry<String, Double>> up = moves.stream().filter(e -> e.getValue() > 1).limit(7).toList();
            List<Map.Entry<String, Double>> down = moves.reversed().stream().filter(e -> e.getValue() < 1).limit(7).toList();
            if (up.isEmpty() && down.isEmpty()) m.set(31, Menu.icon(Material.PAPER, t("eco-none"), t("lore-eco-none")));
            else {
                m.set(18, Menu.pane(Material.LIME_STAINED_GLASS_PANE, t("hdr-rising"), t("lore-hdr-rising")));
                m.set(27, Menu.pane(Material.RED_STAINED_GLASS_PANE, t("hdr-falling"), t("lore-hdr-falling")));
                int[] us = Menu.centered(2, 7), ds = Menu.centered(3, 7);
                for (int i = 0; i < up.size(); i++) { String k = up.get(i).getKey(); m.set(us[i], trendIcon(up.get(i)), () -> item(p, k)); }
                for (int i = 0; i < down.size(); i++) { String k = down.get(i).getKey(); m.set(ds[i], trendIcon(down.get(i)), () -> item(p, k)); }
            }
            back(m, 49, () -> main(p));
            m.open(p);
        });
    }

    private ItemStack trendIcon(Map.Entry<String, Double> e) {
        String key = e.getKey();
        return Menu.icon(key, Items.name(key), t(e.getValue() >= 1 ? "lore-trend-up" : "lore-trend-down", "change", change(e.getValue())));
    }
}
