package com.patelheet.capitalengine.sim;

import com.patelheet.capitalengine.bank.BankService;
import com.patelheet.capitalengine.market.MarketService;
import com.patelheet.capitalengine.market.storage.Store.Job;
import com.patelheet.capitalengine.trade.TradeService;
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
 * economy real demand. Agents call MarketService (and BankService and TradeService when the scenario has them) directly and wait
 * for each answer, so a run is repeatable for a seed.
 */
final class Agent {
    enum Role { MINER, FARMER, TRADER, SAVER, BORROWER, BUILDER, IDLER }

    /** The plugins' services; {@code bank} and {@code trade} are null when the scenario doesn't install them. */
    record Services(MarketService market, BankService bank, TradeService trade) {}

    private static final String FOOD = "carrot", TOOL_MATERIAL = "iron_ingot";
    private static final List<String> WATCHED = List.of("iron_ingot", "wheat", "coal");

    final Role role;
    final PlayerMock player;
    final UUID id;
    private final Random rng;
    private final MarketService svc;
    private final BankService bank;
    private final TradeService trade;
    private final double reliability;
    private final double onlineChance;
    private final Map<String, Integer> production;
    private final LongSupplier wallet;
    private final double spendShare;
    private final List<String> catalog;
    boolean dormant;
    double multiplier = 1;
    int needsTried, needsMet;
    long played;   // minutes, for the bank's playtime rule
    private boolean spendsItAll;   // this loan won't be repaid on time: the borrower spends every coin as it comes in
    final Map<String, Integer> exported = new java.util.TreeMap<>();
    final Map<String, Integer> whyUnmet = new java.util.TreeMap<>();

    /**
     * @param spendShare share of the wallet above a small reserve that is spent each active day on {@code catalog} items from the
     *                   server (building, crafting, consuming), which is what sends coins back to the Treasury
     */
    Agent(Role role, PlayerMock player, long seed, Services s, double onlineChance, Map<String, Integer> production,
          LongSupplier wallet, double spendShare, List<String> catalog, double reliability) {
        this.role = role; this.player = player; this.id = player.getUniqueId(); this.svc = s.market(); this.bank = s.bank(); this.trade = s.trade();
        this.reliability = reliability;
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
            case TRADER -> { if (trade != null) exportCheap(); trade(); sellStash(0.0); }
            case SAVER -> { sellStash(0.1); if (bank != null) save(day); }
            case BORROWER -> { sellStash(0.1); if (bank != null) borrow(); }
        }
        shop();
    }

    // ---- bank ----------------------------------------------------------------------------------------------------

    /** Every third day, half the wallet above the reserve goes into savings; a wallet below the reserve is topped up from savings. */
    private void save(int day) {
        long w = wallet.getAsLong();
        if (w < RESERVE && bank.savings(id) > 0) get(bank.withdraw(id, Math.min(RESERVE, bank.savings(id))));
        else if (day % 3 == 0 && w > RESERVE) get(bank.deposit(id, (w - RESERVE) / 2));
    }

    /** Borrows as much as the bank allows whenever there's no loan. With chance 1 − reliability the loan is not repaid on time. */
    private void borrow() {
        if (get(bank.loan(id)).isPresent()) return;
        spendsItAll = false;
        var offer = get(bank.offer(id, played));
        if (offer.reason() != null) return;
        if (get(bank.borrow(id, offer.limit(), played)).error() == null) spendsItAll = rng.nextDouble() > reliability;
    }

    // ---- trade -----------------------------------------------------------------------------------------------------

    /** Exports from the Stash while the ships pay more than the server's Quick Sell would. Returns how many are left to sell. */
    private int export(String item, int qty) {
        if (trade == null || qty <= 0) return qty;
        var server = get(svc.quote(id, item, qty, false));
        double serverUnit = server.qty() > 0 ? (double) server.total() / server.qty() : 0;
        while (qty > 0) {
            var q = get(trade.quoteExport(item, Math.min(64, qty)));
            if (q.qty() == 0 || (double) q.total() / q.qty() <= serverUnit) break;
            int got = get(svc.withdraw(id, item, q.qty()));
            if (got <= 0) break;
            int sent = get(trade.export(id, item, got)).qty();
            unloadCargo();
            if (sent > 0) exported.merge(item, sent, Integer::sum);
            qty -= sent;
            if (sent < got) break;
        }
        return qty;
    }

    /** Traders buy player listings under what the ships pay and export them. */
    private void exportCheap() {
        for (String item : catalog) {
            var one = get(trade.quoteExport(item, 1));
            if (one.qty() == 0) continue;
            var asks = get(svc.book(item)).asks();
            if (asks.isEmpty() || asks.get(0).price() >= one.total() * 0.95) continue;
            int qty = (int) Math.min(Math.min(64, asks.get(0).qty()), get(trade.quotaLeft(item, true)));
            if (qty <= 0) continue;
            var r = get(svc.quickBuy(id, item, qty, asks.get(0).price() * qty));
            if (r.qty() > 0) export(item, r.qty());
        }
    }

    /** Cargo (what the ships didn't take, and imports) goes back into the Stash; what doesn't fit waits in Cargo. */
    private void unloadCargo() {
        var cargo = get(trade.takeCargo(id));
        Map<String, Integer> back = new java.util.HashMap<>();
        cargo.forEach((item, n) -> { if (!get(svc.deposit(id, item, n))) back.put(item, n); });
        if (!back.isEmpty()) get(trade.stow(id, back));
    }

    /** A need the market couldn't meet is bought from abroad, if the owner lists it as an import. */
    private boolean importFor(String item, int qty) {
        if (trade == null) return false;
        var q = get(trade.quoteImport(item, qty));
        if (q.qty() < qty || q.total() > wallet.getAsLong()) return false;
        var r = get(trade.importItems(id, item, qty, q.total()));
        unloadCargo();
        return r.qty() >= qty && get(svc.free(id, item)) >= qty;
    }

    private static final long RESERVE = 2_000;   // 20 coins kept back

    /** Buys things other players make from the server and uses them up. */
    private void shop() {
        long budget = spendsItAll ? wallet.getAsLong() : (long) (Math.max(0, wallet.getAsLong() - RESERVE) * spendShare);
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
            if (r.qty() < qty && !importFor(item, qty)) { whyUnmet.merge(item + ": " + (r.error() == null ? "server has too little" : r.error()), 1, Integer::sum); return; }
        }
        if (get(svc.withdraw(id, item, qty)) == qty) needsMet++;
    }

    /** Quick Sells the Stash, or with chance {@code listChance} lists an item at about the server's price instead. */
    private void sellStash(double listChance) {
        for (var e : get(svc.stash(id)).entrySet()) {
            String item = e.getKey();
            int free = e.getValue()[0] - e.getValue()[1] - (item.equals(FOOD) ? 5 : 0);   // keep a few days of food
            free = export(item, free);
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
