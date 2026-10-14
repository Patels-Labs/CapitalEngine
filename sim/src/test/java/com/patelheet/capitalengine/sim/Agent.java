package com.patelheet.capitalengine.sim;

import com.patelheet.capitalengine.market.MarketService;
import com.patelheet.capitalengine.market.storage.Store.Job;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * One simulated player (sim/Plan.md §5.3): simple rules plus seeded noise. Every agent eats and wears out a tool, which gives the
 * economy real demand. Agents call MarketService directly and wait for each answer, so a run is repeatable for a seed.
 */
final class Agent {
    enum Role { MINER, FARMER, TRADER, BUILDER, IDLER }

    private static final String FOOD = "carrot", TOOL_MATERIAL = "iron_ingot";
    private static final List<String> WATCHED = List.of("iron_ingot", "wheat", "coal");

    final Role role;
    final PlayerMock player;
    final UUID id;
    private final Random rng;
    private final MarketService svc;
    private final double onlineChance;
    private final Map<String, Integer> production;
    private final LongSupplier wallet;
    private final double spendShare;
    private final List<String> catalog;
    boolean dormant;
    double multiplier = 1;
    int needsTried, needsMet;
    final Map<String, Integer> whyUnmet = new java.util.TreeMap<>();

    /**
     * @param spendShare share of the wallet above a small reserve that is spent each active day on {@code catalog} items from the
     *                   server (building, crafting, consuming), which is what sends coins back to the Treasury
     */
    Agent(Role role, PlayerMock player, long seed, MarketService svc, double onlineChance, Map<String, Integer> production,
          LongSupplier wallet, double spendShare, List<String> catalog) {
        this.role = role; this.player = player; this.id = player.getUniqueId(); this.svc = svc;
        this.rng = new Random(seed); this.onlineChance = onlineChance; this.production = production;
        this.wallet = wallet; this.spendShare = spendShare; this.catalog = catalog;
    }

    static <T> T get(CompletableFuture<T> f) {
        try { return f.get(30, TimeUnit.SECONDS); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    boolean online() { return !dormant && rng.nextDouble() < onlineChance; }

    int minutes() { return 20 + rng.nextInt(100); }

    /** One active day: produce, meet needs, then do the role's trading. */
    void act(int day) {
        production.forEach((item, perDay) -> {
            int n = (int) Math.round(perDay * multiplier * (0.7 + 0.6 * rng.nextDouble()));
            if (n > 0) get(svc.deposit(id, item, n));   // a full Stash simply loses the day's work, like a full inventory
        });
        need(FOOD, 1);
        if (day % 5 == 0) need(TOOL_MATERIAL, 3);
        switch (role) {
            case MINER, IDLER -> sellStash(0.3);
            case FARMER -> { serveTown(); sellStash(0.1); }
            case BUILDER -> { serveProjects(); sellStash(0.1); }
            case TRADER -> { trade(); sellStash(0.0); }
        }
        shop();
    }

    private static final long RESERVE = 2_000;   // 20 coins kept back

    /** Buys things other players make from the server and uses them up. */
    private void shop() {
        long budget = (long) (Math.max(0, wallet.getAsLong() - RESERVE) * spendShare);
        List<String> items = new java.util.ArrayList<>(catalog);
        java.util.Collections.shuffle(items, rng);
        for (String item : items) {
            if (budget <= 0) break;
            if (production.containsKey(item) || item.equals(FOOD)) continue;   // nobody buys back what they make, and food is for eating
            var p = get(svc.prices(List.of(item))).get(item);
            if (p == null || p.stock() == 0) continue;
            int qty = (int) Math.min(16, Math.min(p.stock(), budget / p.ask()));
            if (qty <= 0) continue;
            var r = get(svc.quickBuy(id, item, qty, budget));
            budget -= r.coins();
            if (r.qty() > 0) get(svc.withdraw(id, item, r.qty()));   // used up: it leaves the economy
        }
    }

    /** Uses an item from the Stash or buys it; counts whether the need was met. */
    private void need(String item, int qty) {
        needsTried++;
        if (get(svc.free(id, item)) < qty) {
            var r = get(svc.quickBuy(id, item, qty, Long.MAX_VALUE));
            if (r.qty() < qty) { whyUnmet.merge(item + ": " + (r.error() == null ? "server has too little" : r.error()), 1, Integer::sum); return; }
        }
        if (get(svc.withdraw(id, item, qty)) == qty) needsMet++;
    }

    /** Quick Sells the Stash, or with chance {@code listChance} lists an item at about the server's price instead. */
    private void sellStash(double listChance) {
        for (var e : get(svc.stash(id)).entrySet()) {
            String item = e.getKey();
            int free = e.getValue()[0] - e.getValue()[1] - (item.equals(FOOD) ? 5 : 0);   // keep a few days of food
            if (free <= 0) continue;
            if (rng.nextDouble() < listChance) {
                var p = get(svc.prices(List.of(item))).get(item);
                if (p != null) { get(svc.list(id, item, free, Math.max(1, Math.round(p.ask() * (0.9 + 0.2 * rng.nextDouble()))))); continue; }
            }
            var sold = get(svc.quickSell(id, item, free));
            int left = free - sold.qty();   // "The server can't buy more today": list the rest for players, just under the server's price
            var p = left > 0 ? get(svc.prices(List.of(item))).get(item) : null;
            if (p != null) get(svc.list(id, item, left, Math.max(1, Math.round(p.ask() * (0.85 + 0.1 * rng.nextDouble())))));
        }
    }

    /** Farmers bring what the town needs when the job pays more than the server's bid. */
    private void serveTown() {
        for (Job j : get(svc.jobs().open())) {
            if (j.poster() != null || j.project() != null || !"DELIVERY".equals(j.kind())) continue;
            var p = get(svc.prices(List.of(j.item()))).get(j.item());
            if (p != null && j.pay() > p.bid() && get(svc.free(id, j.item())) > 0) get(svc.jobs().deliver(id, j.id(), j.left()));
        }
    }

    private void serveProjects() {
        for (Job j : get(svc.jobs().open())) if (j.project() != null && get(svc.free(id, j.item())) > 0) get(svc.jobs().deliver(id, j.id(), j.left()));
    }

    /** Buys player listings well under the server's price and relists them just under it. */
    private void trade() {
        for (String item : WATCHED) {
            var p = get(svc.prices(List.of(item))).get(item);
            var asks = get(svc.book(item)).asks();
            if (p == null || asks.isEmpty() || asks.get(0).price() >= p.ask() * 0.9) continue;
            int qty = Math.min(64, asks.get(0).qty());
            var r = get(svc.quickBuy(id, item, qty, asks.get(0).price() * qty));
            if (r.qty() > 0) get(svc.list(id, item, r.qty(), Math.max(1, Math.round(p.ask() * 0.97))));
        }
    }
}
