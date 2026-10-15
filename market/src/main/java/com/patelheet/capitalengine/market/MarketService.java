package com.patelheet.capitalengine.market;

import com.patelheet.capitalengine.api.Account;
import com.patelheet.capitalengine.api.CapitalApi;
import com.patelheet.capitalengine.api.Failure;
import com.patelheet.capitalengine.api.Severity;
import com.patelheet.capitalengine.api.Stats;
import com.patelheet.capitalengine.api.TxResult;
import com.patelheet.capitalengine.market.autopilot.Autopilot;
import com.patelheet.capitalengine.market.autopilot.PriceIndex;
import com.patelheet.capitalengine.market.book.Matcher;
import com.patelheet.capitalengine.market.book.Matcher.Fill;
import com.patelheet.capitalengine.market.alerts.FloodDetector;
import com.patelheet.capitalengine.market.maker.RecipeValuer;
import com.patelheet.capitalengine.market.maker.ReferencePrice;
import com.patelheet.capitalengine.market.storage.Store;
import com.patelheet.capitalengine.market.storage.Store.Trade;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

/**
 * Everything the GUIs, commands and simulator do goes through here (market/Plan.md §5.9).
 * Every method runs on one worker thread, so matching needs no locks and the main thread never waits on the database.
 * The worker may block on API futures (they complete on core threads), which keeps each trade strictly one at a time.
 * ponytail: one worker for the whole market; shard per item if a very busy server needs more throughput.
 */
public final class MarketService implements AutoCloseable {
    /** Outcome of a trade. {@code error} is a messages.yml key, or null when it worked. */
    public record Result(int qty, long coins, String error, long orderId) {
        static Result err(String key) { return new Result(0, 0, key, 0); }
    }

    /** {@code reason} is null when the whole quantity is available, else a messages.yml suffix: budget, daily-limit, no-buyers, no-stock. */
    public record Quote(int qty, long total, String reason) {}
    /** {@code canBuy} is how many units the market will still buy today. */
    public record PriceInfo(long ask, long bid, long stock, int canBuy) {}
    public record Book(List<Matcher.Order> asks, List<Matcher.Order> bids) {}

    final Plugin plugin;
    final CapitalApi api;
    final Store store;
    final Messages msg;
    final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "CapitalMarket-worker"));
    final Map<String, Long> base = new ConcurrentHashMap<>();   // item -> base price; the items the server trades
    final Set<String> blocked = new HashSet<>();
    final Map<String, String> source = new ConcurrentHashMap<>();   // item -> config | recipe | drift
    final Map<String, Long> askCache = new ConcurrentHashMap<>();   // for placeholders, which must never wait on the worker
    final JobsBoard jobs;
    final Tutorial tutorial;
    final FloodDetector flood;
    final boolean withdrawAnywhere;
    volatile Map<String, Double> trend = Map.of();                  // item -> this week's price vs last (1.10 = up 10%)
    volatile Autopilot.State policy;
    volatile double playerShare;
    volatile long stockValue;
    private final Autopilot.Cfg ap;
    private final BigDecimal feeRate;
    private final double k, spread0 = 0.30, useRate;
    final long targetStock;
    final int dailyBuyCap;
    private final int maxOrders, expiryDays, maxStacks;
    private final boolean news;
    private final String webhook;
    private final Map<String, Long> raw = new HashMap<>();
    private final List<String> serverItems;
    private final List<RecipeValuer.Recipe> recipes;
    private java.net.http.HttpClient http;
    private long cDay = -1, cSpent, cJobPaid;                       // today's server spending, kept here so quotes never re-sum the trades table
    private final Map<String, Integer> cBought = new HashMap<>();

    public MarketService(Plugin plugin, CapitalApi api, Store store, Messages msg, FileConfiguration cfg, YamlConfiguration values,
                         List<String> serverItems, List<RecipeValuer.Recipe> recipes) {
        this.plugin = plugin; this.api = api; this.store = store; this.msg = msg;
        feeRate = new BigDecimal(cfg.getString("market.fee", "0.02"));
        maxOrders = cfg.getInt("market.max-open-orders", 20);
        expiryDays = cfg.getInt("market.order-expiry-days", 14);
        blocked.addAll(cfg.getStringList("market.blocked-items"));
        maxStacks = cfg.getInt("stash.max-stacks", 54);
        k = cfg.getDouble("maker.k", 0.5);
        targetStock = cfg.getLong("maker.target-stock-default", 640);
        useRate = cfg.getDouble("maker.stock-use-rate", 0.02);
        dailyBuyCap = cfg.getInt("maker.per-item-daily-buy", 1280);
        news = cfg.getBoolean("news.enabled", true);
        double monthly = cfg.getDouble("autopilot.target-monthly-inflation", 0.02);
        ap = new Autopilot.Cfg(monthly * 7 / 30, cfg.getDouble("autopilot.target-growth-monthly", 0.03) / 30,
                api.currency().parse(cfg.getString("autopilot.floor-per-active-player", "150")),
                cfg.getDouble("autopilot.rate.start-weekly", 0.02), cfg.getDouble("autopilot.rate.min-weekly", 0),
                cfg.getDouble("autopilot.rate.max-weekly", 0.08), cfg.getDouble("autopilot.split-jobs-vs-buyer", 0.6),
                cfg.getInt("autopilot.warmup-weeks", 2));
        webhook = cfg.getString("news.discord-webhook", "");
        withdrawAnywhere = cfg.getBoolean("stash.withdraw-anywhere", false);
        for (String item : values.getKeys(false)) {
            if (Items.parse(item) != null) raw.put(Items.parse(item), api.currency().parse(values.getString(item)));
            else plugin.getLogger().warning("values.yml: unknown item " + item);
        }
        this.serverItems = serverItems.stream().map(Items::parse).filter(java.util.Objects::nonNull).distinct().toList();
        this.recipes = recipes;
        flood = new FloodDetector(cfg.getInt("alerts.flood-multiplier", 10), cfg.getInt("alerts.flood-min", 256));
        jobs = new JobsBoard(this, cfg);
        tutorial = new Tutorial(this, cfg);
    }

    /** Prices every server item: values.yml first, then the cheapest recipe. Real player prices (drift) are kept unless {@code resetDrift}. */
    void applyValues(List<RecipeValuer.Recipe> recipes, boolean resetDrift) {
        Map<String, Long> computed = RecipeValuer.value(raw, recipes);
        if (resetDrift) store.clearDrift();
        Map<String, Long> drift = store.drifted();
        base.clear();
        for (String item : serverItems) {
            Long v = computed.get(item);
            if (v == null) { plugin.getLogger().warning("No price for " + item + ": add it to values.yml or give it a recipe"); continue; }
            base.put(item, drift.getOrDefault(item, v));
            source.put(item, drift.containsKey(item) ? "drift" : raw.containsKey(item) ? "config" : "recipe");
            askCache.put(item, ReferencePrice.ask(ref(item), policy == null ? spread0 : policy.spread()));
        }
    }

    /** {@code /market admin revalue}: recalculate from values.yml and the server's recipes, forgetting drift. Call on the main thread. */
    public CompletableFuture<Void> revalue(List<RecipeValuer.Recipe> recipes) { return run(() -> { applyValues(recipes, true); return null; }); }

    /** Today's counters, reloaded from the database when the day changes or after a restart. */
    private void syncDay() {
        long d = day();
        if (d == cDay) return;
        cDay = d;
        cSpent = store.serverSpentToday(d);
        cJobPaid = store.serverJobPaidToday(d);
        cBought.clear();
        cBought.putAll(store.serverBoughtByItem(d));
    }

    long jobPaidToday() { syncDay(); return cJobPaid; }

    void addJobPaid(long n) { cJobPaid += n; }

    /** Completes once everything queued so far has finished (for tests and the simulator). */
    public CompletableFuture<Void> idle() { return run(() -> null); }

    /** The Jobs Board. */
    public JobsBoard jobs() { return jobs; }

    /** The autopilot's current policy, or null before the first one. */
    public Autopilot.State policy() { return policy; }

    /** Escrow held vs needed: {market held, market needed, jobs held, jobs needed}. All four pairs should match. */
    public CompletableFuture<long[]> escrowCheck() { return run(this::escrowNow); }

    private long[] escrowNow() { return new long[]{escrow().balance(), store.buyEscrow(), jobs.escrow().balance(), store.jobsEscrow()}; }

    // ---- plumbing ----------------------------------------------------------------------------------------------

    <T> CompletableFuture<T> run(Supplier<T> s) {
        return CompletableFuture.supplyAsync(() -> {
            try { return s.get(); }
            catch (RuntimeException e) { plugin.getLogger().log(Level.SEVERE, "Market error", e); throw e; }
        }, worker);
    }

    Account acct(String uuid) { return api.accounts().player(UUID.fromString(uuid)); }
    Account escrow() { return api.accounts().plugin(plugin, "escrow"); }
    long now() { return System.currentTimeMillis(); }
    long day() { return api.stats().dayIndex(); }

    long fee(long gross) { return BigDecimal.valueOf(gross).multiply(feeRate).setScale(0, RoundingMode.DOWN).longValue(); }

    String errKey(Failure f) {
        return switch (f) {
            case INSUFFICIENT_FUNDS -> "err-funds";
            case POLICY_LIMIT, DENIED -> "err-limit";
            case ECONOMY_PAUSED -> "err-paused";
            default -> "err-other";
        };
    }

    boolean ok(TxResult r) { return r.success() || r.failure() == Failure.DUPLICATE_REFERENCE; }

    // ---- prices ------------------------------------------------------------------------------------------------

    long ref(String item) { return ReferencePrice.ref(base.get(item), store.stock(item), targetStock, k); }

    Matcher.Maker maker(String item) {
        Long b = base.get(item);
        if (b == null) return null;
        syncDay();
        // the jobs share of the budget has no use on a server with no server jobs open, so the buyer may spend it too
        long budget = policy == null ? 0 : Math.max(0, policy.buyer() - cSpent + (store.anyServerJobOpen() ? 0 : Math.max(0, policy.jobs() - cJobPaid)));
        double spread = policy == null ? spread0 : policy.spread();
        return new Matcher.Maker(b, store.stock(item), targetStock, k, spread, budget, Math.max(0, dailyBuyCap - cBought.getOrDefault(item, 0)));
    }

    public CompletableFuture<PriceInfo> price(String item) {
        return run(() -> {
            if (!base.containsKey(item)) return null;
            return priceInfo(item, policy == null ? spread0 : policy.spread());
        });
    }

    /** Prices for a menu page in one trip to the worker. */
    public CompletableFuture<Map<String, PriceInfo>> prices(List<String> items) {
        return run(() -> {
            Map<String, PriceInfo> m = new HashMap<>();
            double spread = policy == null ? spread0 : policy.spread();
            for (String item : items) if (base.containsKey(item)) m.put(item, priceInfo(item, spread));
            return m;
        });
    }

    private PriceInfo priceInfo(String item, double spread) {
        long ref = ref(item), bid = ReferencePrice.bid(ref, spread);
        Matcher.Maker m = maker(item);
        return new PriceInfo(ReferencePrice.ask(ref, spread), bid, store.stock(item), m == null ? 0 : (int) Math.min(m.dayCapLeft(), m.budgetLeft() / bid));
    }

    /** Why selling {@code wanted} units fell short once {@code plan} had used what it could, as a messages.yml suffix; null if it didn't. */
    private String sellShortfall(String item, int wanted, List<Fill> plan) {
        if (Matcher.qty(plan) >= wanted) return null;
        Matcher.Maker m = maker(item);
        if (m == null) return "no-buyers";
        long spent = 0;
        int sold = 0;
        for (Fill f : plan) if (f.server()) { spent += f.price() * f.qty(); sold += f.qty(); }
        if (m.dayCapLeft() - sold <= 0) return "daily-limit";
        long nextBid = ReferencePrice.bid(ReferencePrice.ref(m.base(), m.stock() + sold, m.target(), m.k()), m.spread());
        return m.budgetLeft() - spent < nextBid ? "budget" : "no-buyers";
    }

    /** Player listings (cheapest first) and wanted orders (best first) for an item, for the menus. */
    public CompletableFuture<Book> book(String item) { return run(() -> new Book(store.resting(item, "SELL", null), store.resting(item, "BUY", null))); }

    /** Re-reads projects.yml and posts any new project or "the town needs" jobs now instead of at the next day. */
    public CompletableFuture<Void> replan() { return run(() -> { jobs.onDay(day()); return null; }); }

    /** The items the server trades, A to Z. */
    public List<String> items() { return base.keySet().stream().sorted().toList(); }

    /** Whether the server will still buy today (its "buyer" budget isn't used up). */
    public CompletableFuture<Boolean> buyerOpen() { return run(() -> { syncDay(); return policy != null && policy.buyer() > cSpent; }); }

    /** Items a player could sell right now from the Stash. */
    public CompletableFuture<Integer> free(UUID p, String item) { return run(() -> store.stashFree(p, item)); }

    /** What selling (net of fee) or buying {@code qty} would pay or cost right now. Assumes the items are available. */
    public CompletableFuture<Quote> quote(UUID p, String item, int qty, boolean buying) {
        return run(() -> {
            List<Fill> plan = plan(p, item, qty, buying, buying ? Long.MAX_VALUE : 0);
            int got = Matcher.qty(plan);
            return new Quote(got, buying ? Matcher.total(plan) : net(plan), buying ? (got < qty ? "no-stock" : null) : sellShortfall(item, qty, plan));
        });
    }

    private List<Fill> plan(UUID p, String item, int qty, boolean buying, long limit) {
        return Matcher.plan(buying, store.resting(item, buying ? "SELL" : "BUY", p), qty, limit, maker(item));
    }

    private long net(List<Fill> plan) {
        long sum = 0;
        for (Fill f : plan) { long g = f.price() * f.qty(); sum += f.server() ? g : g - fee(g); }
        return sum;
    }

    // ---- stash -------------------------------------------------------------------------------------------------

    private boolean fits(UUID p, String item, int qty) {
        return store.stashStacks(p, Items::stackSize) + (qty + Items.stackSize(item) - 1) / Items.stackSize(item) <= maxStacks;
    }

    /** Moves items (already taken from the inventory) into the Stash. False if the Stash is full. */
    public CompletableFuture<Boolean> deposit(UUID p, String item, int qty) {
        return run(() -> { if (!fits(p, item, qty)) return false; store.deposit(p, item, qty); return true; });
    }

    /** Takes up to {@code qty} free items out of the Stash; the caller puts them in the inventory and re-deposits what doesn't fit. */
    public CompletableFuture<Integer> withdraw(UUID p, String item, int qty) {
        return run(() -> { int n = Math.min(qty, store.stashFree(p, item)); if (n > 0) store.withdraw(p, item, n); return n; });
    }

    public CompletableFuture<Map<String, int[]>> stash(UUID p) { return run(() -> store.stash(p)); }

    // ---- trading -----------------------------------------------------------------------------------------------

    public CompletableFuture<Result> quickSell(UUID p, String item, int qty) {
        return run(() -> {
            if (store.stashFree(p, item) < qty) return Result.err("not-enough-items");
            List<Fill> plan = plan(p, item, qty, false, 0);
            if (plan.isEmpty()) return Result.err("sell-" + sellShortfall(item, qty, plan));
            Result r = execute(p, item, false, plan);
            if (r.qty() > 0) tutorial.advance(p, 2);
            return r;
        });
    }

    public CompletableFuture<Result> quickBuy(UUID p, String item, int qty, long maxTotal) {
        return run(() -> {
            if (!fits(p, item, qty)) return Result.err("stash-full");
            List<Fill> plan = plan(p, item, qty, true, Long.MAX_VALUE);
            if (plan.isEmpty()) return Result.err("buy-no-stock");
            long total = Matcher.total(plan);
            return total > maxTotal ? new Result(0, total, "too-expensive", 0) : execute(p, item, true, plan);
        });
    }

    /** "Set my own price": sells what crosses the book now; the rest waits as an order. */
    public CompletableFuture<Result> list(UUID p, String item, int qty, long unitPrice) {
        return run(() -> {
            if (store.stashFree(p, item) < qty) return Result.err("not-enough-items");
            if (store.openOrders(p).size() >= maxOrders) return Result.err("too-many-orders");
            Result r = execute(p, item, false, plan(p, item, qty, false, unitPrice));
            int rest = qty - r.qty();
            if (r.error() != null || rest == 0) return r;
            long id = store.insertOrder(item, "SELL", p, unitPrice, rest, now(), expires(), "OPEN");
            store.lock(p, item, rest);
            return new Result(r.qty(), r.coins(), null, id);
        });
    }

    /** "Wanted": buys what crosses the book now; the rest waits as an order with its coins held in escrow. */
    public CompletableFuture<Result> wanted(UUID p, String item, int qty, long unitPrice) {
        return run(() -> {
            if (!fits(p, item, qty)) return Result.err("stash-full");
            if (store.openOrders(p).size() >= maxOrders) return Result.err("too-many-orders");
            Result r = execute(p, item, true, plan(p, item, qty, true, unitPrice));
            int rest = qty - r.qty();
            if (r.error() != null || rest == 0) return r;
            long id = store.insertOrder(item, "BUY", p, unitPrice, rest, now(), expires(), "PENDING");
            TxResult t = api.transaction(plugin, "market.order", msg.plain("memo-order", "id", id)).reference("ord:" + id).actor(p)
                    .move(acct(p.toString()), escrow(), rest * unitPrice).commit().join();
            if (!ok(t)) { store.orderStatus(id, "CANCELLED"); return new Result(r.qty(), r.coins(), errKey(t.failure()), 0); }
            store.orderStatus(id, "OPEN");
            return new Result(r.qty(), r.coins(), null, id);
        });
    }

    public CompletableFuture<Boolean> cancel(UUID p, long orderId) {
        return run(() -> {
            Store.OrderRow o = store.order(orderId);
            return o != null && o.owner().equals(p.toString()) && o.status().equals("OPEN") && cancelOrder(o, "CANCELLED");
        });
    }

    public CompletableFuture<List<Store.OrderRow>> orders(UUID p) { return run(() -> store.openOrders(p)); }

    /** Orders expire after a number of economic days, so downtime and the simulator both behave. */
    private long expires() { return day() + expiryDays; }

    boolean cancelOrder(Store.OrderRow o, String status) {
        if (o.side().equals("BUY")) {
            TxResult r = api.transaction(plugin, "market.refund", msg.plain("memo-refund", "id", o.id())).reference("ordx:" + o.id())
                    .move(escrow(), acct(o.owner()), o.left() * o.price()).commit().join();
            if (!ok(r)) return false;
        } else store.lock(UUID.fromString(o.owner()), o.item(), -o.left());
        store.orderStatus(o.id(), status);
        return true;
    }

    /**
     * Two-phase (market/Plan.md §5.4): durable PENDING rows, then the money with references, then the items.
     * All fills of a plan are sent to the ledger before waiting on any of them, so the ledger group-commits them together
     * and a 32-stack dump costs one wait instead of 32. Fills are independent, so a fill that fails only loses itself.
     */
    private Result execute(UUID taker, String item, boolean buying, List<Fill> plan) {
        if (plan.isEmpty()) return new Result(0, 0, null, 0);
        long day = day(), week = api.stats().weekIndex(), ts = now();
        List<Trade> trades = new ArrayList<>(plan.size());
        store.tx(() -> {
            for (Fill f : plan) {
                String other = f.server() ? null : f.owner();
                Trade t = new Trade(0, item, buying ? taker.toString() : other, buying ? other : taker.toString(),
                        f.server() ? (buying ? "SELL" : "BUY") : null, f.price(), f.qty(), f.server() ? 0 : fee(f.price() * f.qty()), day, week, f.orderId(),
                        f.server() ? null : (buying ? "SELL" : "BUY"));
                trades.add(t.withId(store.insertPending(t, ts)));
            }
        });
        List<CompletableFuture<TxResult>> sent = trades.stream().map(t -> money(t, taker)).toList();
        List<Trade> done = new ArrayList<>();
        String error = null;
        for (int i = 0; i < trades.size(); i++) {
            TxResult r = sent.get(i).join();
            if (ok(r)) done.add(trades.get(i));
            else { store.failTrade(trades.get(i).id()); if (error == null) error = errKey(r.failure()); }
        }
        store.tx(() -> done.forEach(store::finishTrade));
        int qty = 0;
        long coins = 0;
        for (Trade t : done) {
            qty += t.qty();
            coins += buying ? t.gross() : t.gross() - t.fee();
            if ("BUY".equals(t.serverSide())) { cSpent += t.gross(); cBought.merge(item, t.qty(), Integer::sum); }
            if (t.seller() != null) watchSale(t);
        }
        if (!done.isEmpty() && base.containsKey(item)) askCache.put(item, ReferencePrice.ask(ref(item), policy == null ? spread0 : policy.spread()));
        return new Result(qty, coins, error, 0);
    }

    private void watchSale(Trade t) {
        for (var a : flood.sold(t.seller(), t.item(), t.qty())) {
            String who = a.server() ? "" : api.accounts().player(UUID.fromString(a.who())).name();
            api.alert(plugin, Severity.WARN, a.server() ? "market.supply_spike" : "market.item_flood",
                    msg.plain(a.server() ? "alert-spike" : "alert-flood", "who", who, "qty", a.today(), "item", Items.pretty(a.item()), "usual", Math.round(a.usual())));
        }
    }

    private CompletableFuture<TxResult> money(Trade t, UUID actor) {
        String ref = "fill:" + t.id();
        Object[] kv = {"qty", t.qty(), "item", Items.pretty(t.item())};
        if ("BUY".equals(t.serverSide()))
            return api.transaction(plugin, "market.server_buy", msg.plain("memo-server-buy", kv)).reference(ref).actor(actor).serverPay(acct(t.seller()), t.gross()).commit();
        if ("SELL".equals(t.serverSide()))
            return api.transaction(plugin, "market.server_sell", msg.plain("memo-server-sell", kv)).reference(ref).actor(actor).move(acct(t.buyer()), api.treasury(), t.gross()).commit();
        Account from = "BUY".equals(t.orderSide()) ? escrow() : acct(t.buyer());   // a resting bid pays from escrow
        var tx = api.transaction(plugin, "market.trade", msg.plain("memo-trade", kv)).reference(ref).actor(actor).move(from, acct(t.seller()), t.gross() - t.fee());
        if (t.fee() > 0) tx.move(from, api.treasury(), t.fee());
        return tx.commit();
    }

    // ---- startup, days and weeks -------------------------------------------------------------------------------

    /** Run once every plugin has enabled (so the Bank check is right). Finishes or rolls back anything that was in flight when the server stopped (rule 7). */
    public CompletableFuture<Void> recover() {
        return run(() -> {
            for (Trade t : store.pendingTrades()) {
                if (api.findByReference(plugin, "fill:" + t.id()).join().isPresent()) store.finishTrade(t); else store.failTrade(t.id());
            }
            for (Store.OrderRow o : store.orders("SELECT * FROM orders WHERE status='PENDING'"))
                store.orderStatus(o.id(), api.findByReference(plugin, "ord:" + o.id()).join().isPresent() ? "OPEN" : "CANCELLED");
            applyValues(recipes, false);
            flood.load(store.floodAll());
            jobs.recover();
            var stocked = store.allStock();
            base.keySet().stream().filter(i -> !stocked.containsKey(i)).forEach(i -> store.setStock(i, targetStock));   // fresh server: start at the base price
            policy = store.latestPolicy();
            if (policy == null) { Stats st = api.stats(); newPolicy(0, 0, st.weekIndex(), st); }
            return null;
        });
    }

    private void newPolicy(double p, int weeksRun, long week, Stats st) {
        double recorded = store.avgActive7d();   // our own average from each Day event: core's own average is written asynchronously and can lag a day
        var in = new Autopilot.In(p, st.activeMoneySupply(), recorded >= 0 ? recorded : st.avgActivePlayers7d(), store.avgTreasuryIn7d(), playerShare,
                Bukkit.getPluginManager().isPluginEnabled("CapitalBank"), weeksRun);
        Autopilot.State prev = policy;
        policy = prev == null ? Autopilot.initial(ap, in, week) : Autopilot.step(prev, in, ap, week);
        store.savePolicy(policy);
    }

    /** On the Day event: {@code newDay} is the day starting. Everything the worker needs from the clock is passed in, not read later. */
    public void onDay(Stats ended, long newDay, boolean catchUp) {
        run(() -> {
            store.saveDay(ended.dayIndex(), ended.treasuryInToday(), ended.activePlayersToday());
            store.useUpStock(useRate);
            for (Store.OrderRow o : store.orders("SELECT * FROM orders WHERE status='OPEN' AND expires<=?", newDay)) cancelOrder(o, "EXPIRED");
            jobs.onDay(newDay);
            flood.endDay().forEach((k, avg) -> store.saveFlood(k.substring(0, k.indexOf('\t')), k.substring(k.indexOf('\t') + 1), avg));
            long value = 0;
            for (var e : store.allStock().entrySet()) if (base.containsKey(e.getKey())) value += e.getValue() * ref(e.getKey());
            stockValue = value;
            long[] e = escrowNow();
            if (e[0] != e[1]) api.alert(plugin, Severity.CRITICAL, "market.escrow_mismatch", "Market escrow holds " + e[0] + " but open buy orders need " + e[1]);
            if (e[2] != e[3]) api.alert(plugin, Severity.CRITICAL, "market.jobs_escrow_mismatch", "Jobs escrow holds " + e[2] + " but open jobs need " + e[3]);
            return null;
        });
    }

    /** Weekly: drift base prices toward what players pay, run the autopilot, and post the news. Never on catch-up. */
    public void onWeek(long week, boolean catchUp) {
        if (catchUp) return;
        Stats now = api.stats();   // read here, on the event thread, so the policy sees the boundary and not whatever the clock does next
        run(() -> {
            long ended = week - 1, from = ended - 3;
            Map<String, List<PriceIndex.Pt>> pts = store.playerTrades(from);
            Map<String, Long> refNow = new TreeMap<>(), refLast = store.lastRefs();
            for (String item : base.keySet()) {
                List<PriceIndex.Pt> t = PriceIndex.trimOutliers(pts.getOrDefault(item, List.of()));
                if (t.size() >= 20) {   // real prices gradually take over
                    long v = 0, q = 0;
                    for (var x : t) { v += x.price() * x.qty(); q += x.qty(); }
                    long b = base.get(item);
                    base.put(item, Math.max(1, b + Math.round(0.1 * ((double) v / q - b))));
                    source.put(item, "drift");
                }
                refNow.put(item, ref(item));
            }
            playerShare = store.playerShare(from);
            var idx = PriceIndex.compute(ended, store.tradedValue(from), pts, refNow, refLast);
            Map<String, Double> change = new HashMap<>();
            base.keySet().forEach(item -> change.put(item, PriceIndex.itemChange(pts.getOrDefault(item, List.of()), ended, refNow.get(item), refLast.getOrDefault(item, 0L))));
            trend = change;
            newPolicy(idx.p(), store.weeksRun(), week, now);
            store.tx(() -> {
                base.forEach((item, b) -> store.saveValue(item, b, refNow.get(item), source.getOrDefault(item, "recipe")));
                store.pruneTrades(ended - 8);
            });
            base.keySet().forEach(item -> askCache.put(item, ReferencePrice.ask(ref(item), policy.spread())));
            if (news) broadcastNews(idx, pts, ended);
            return null;
        });
    }

    private void broadcastNews(PriceIndex.Result idx, Map<String, List<PriceIndex.Pt>> pts, long ended) {
        List<Map.Entry<String, Double>> movers = new ArrayList<>(idx.change().entrySet().stream()
                .filter(e -> pts.getOrDefault(e.getKey(), List.of()).stream().filter(t -> t.week() == ended).mapToInt(PriceIndex.Pt::qty).sum() >= 64)
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed()).toList());
        List<String> parts = new ArrayList<>();
        movers.stream().filter(e -> e.getValue() > 1).limit(2).forEach(e -> parts.add(msg.plain("news-up", "item", Items.pretty(e.getKey()), "pct", Math.round((e.getValue() - 1) * 100))));
        movers.stream().filter(e -> e.getValue() < 1).reduce((a, b) -> b).ifPresent(e -> parts.add(msg.plain("news-down", "item", Items.pretty(e.getKey()), "pct", Math.round((1 - e.getValue()) * 100))));
        if (Bukkit.getPluginManager().isPluginEnabled("CapitalBank")) parts.add(msg.plain("news-rate", "pct", String.format("%.1f", policy.rate() * 100)));
        if (parts.isEmpty()) return;
        var text = msg.get("news", "text", String.join(" ", parts));
        Bukkit.getGlobalRegionScheduler().run(plugin, t -> { Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(text)); Bukkit.getConsoleSender().sendMessage(text); });
        postWebhook(msg.plain("news", "text", String.join(" ", parts)));
    }

    private void postWebhook(String text) {
        if (webhook.isBlank()) return;
        try {
            if (http == null) http = java.net.http.HttpClient.newHttpClient();
            String json = "{\"content\":\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\"}";
            http.sendAsync(java.net.http.HttpRequest.newBuilder(java.net.URI.create(webhook)).header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json)).build(), java.net.http.HttpResponse.BodyHandlers.discarding());
        } catch (RuntimeException e) { plugin.getLogger().warning("news.discord-webhook failed: " + e.getMessage()); }
    }

    @Override public void close() {
        worker.shutdown();
        try { worker.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        store.close();
    }
}
