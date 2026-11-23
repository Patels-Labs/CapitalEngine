package com.patelheet.capitalengine.trade;

import com.patelheet.capitalengine.api.CapitalApi;
import com.patelheet.capitalengine.api.Failure;
import com.patelheet.capitalengine.api.PolicyStatus;
import com.patelheet.capitalengine.api.Stats;
import com.patelheet.capitalengine.api.TxResult;
import com.patelheet.capitalengine.api.service.Indicator;
import com.patelheet.capitalengine.api.service.IndicatorProvider;
import com.patelheet.capitalengine.api.service.Unit;
import com.patelheet.capitalengine.trade.port.Slide;
import com.patelheet.capitalengine.trade.storage.Store;
import com.patelheet.capitalengine.trade.storage.Store.Price;
import com.patelheet.capitalengine.trade.storage.Store.Shipment;
import com.patelheet.capitalengine.trade.world.Booms;
import com.patelheet.capitalengine.trade.world.WorldPrices;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

/**
 * Everything the menus, commands and simulator do goes through here (trade/Plan.md §5.6). Every method runs on one worker
 * thread, so quotas need no locks and the main thread never waits on the database. The worker may block on API futures
 * (they complete on core threads), which keeps shipments strictly one at a time.
 * <p>Items never pass through here: callers take them out of the inventory first. Anything that is not exported, and every
 * import, goes to the player's Cargo in the same database transaction that settles the shipment, so items survive a crash or a
 * player logging off; the caller then collects Cargo into the inventory.
 */
public final class TradeService implements AutoCloseable, IndicatorProvider {
    /** An item faraway lands buy or sell. Prices are {@code long} units. */
    public record Listing(long base, long quotaPerPlayer, double tariff) {}

    /** What a shipment would do now. {@code reason} is null when the whole amount fits, else quota, budget or closed. */
    public record Quote(int qty, long total, long tariff, String reason) {}

    /** Outcome of a shipment. {@code error} is a messages.yml key, or null when it worked. */
    public record TradeResult(int qty, long coins, String error) {}

    /** One line of the Export or Import tab. {@code unit} is the price of the next item, after the slide. */
    public record Row(String item, long price, long prev, long quotaLeft, long unit) {}

    static final String EXPORT = "EXPORT", IMPORT = "IMPORT";

    final Plugin plugin;
    final CapitalApi api;
    final Store store;
    final Messages msg;
    final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "CapitalTrade-worker"));
    volatile Map<String, Listing> exports = Map.of(), imports = Map.of();
    final Map<String, Price> prices = new ConcurrentHashMap<>();
    final Map<String, Long> quotaLeftCache = new ConcurrentHashMap<>();   // exports, for placeholders, which must never wait on the worker
    volatile String boomItem;
    volatile double boomMult = 1;
    volatile List<Indicator> indicators = List.of();
    final int radius;
    final boolean anywhere;
    private final WorldPrices.Cfg wp;
    private final double exportK, importK, boomChance, boomMultiplier;
    private final long dailyBudget;
    private volatile long yesterdayActive = -1;
    private long cDay = -1;
    private Map<String, long[]> cTotals = new HashMap<>();   // today's "item\tDIRECTION" -> {qty, total, tariff}, kept so quotes never re-sum shipments

    public TradeService(Plugin plugin, CapitalApi api, Store store, Messages msg, FileConfiguration cfg) {
        this.plugin = plugin; this.api = api; this.store = store; this.msg = msg;
        wp = new WorldPrices.Cfg(cfg.getDouble("world-prices.theta", 0.10), cfg.getDouble("world-prices.sigma", 0.05),
                cfg.getDouble("world-prices.min-x", 0.5), cfg.getDouble("world-prices.max-x", 2.0), cfg.getLong("world-prices.seed", 12345));
        exportK = cfg.getDouble("exports.slide-k", 0.5);
        importK = cfg.getDouble("imports.slide-k", 0.5);
        dailyBudget = api.currency().parse(cfg.getString("exports.daily-budget", "5000"));
        boomChance = cfg.getDouble("booms.chance", 0.5);
        boomMultiplier = cfg.getDouble("booms.multiplier", 1.3);
        radius = cfg.getInt("ports.radius", 8);
        anywhere = cfg.getBoolean("ports.anywhere", false);
    }

    /** Reads {@code exports.yml} and {@code imports.yml}. Unknown items and bad lines are skipped with a warning. */
    public void setListings(ConfigurationSection exportsYml, ConfigurationSection importsYml) {
        exports = listings(exportsYml, "exports.yml", false);
        imports = listings(importsYml, "imports.yml", true);
    }

    private Map<String, Listing> listings(ConfigurationSection yml, String file, boolean tariff) {
        Map<String, Listing> m = new TreeMap<>();
        if (yml == null) return m;
        for (String name : yml.getKeys(false)) {
            String item = Items.parse(name);
            ConfigurationSection s = yml.getConfigurationSection(name);
            try {
                if (item == null || s == null) throw new IllegalArgumentException("unknown item");
                long base = api.currency().parse(String.valueOf(s.get("base")));
                long quota = s.getLong("quota-per-active-player", 0);
                if (base <= 0 || quota <= 0) throw new IllegalArgumentException("base and quota-per-active-player must be above zero");
                m.put(item, new Listing(base, quota, tariff ? Math.max(0, s.getDouble("tariff", 0.25)) : 0));
            } catch (IllegalArgumentException e) { plugin.getLogger().warning(file + ": skipping " + name + " (" + e.getMessage() + ")"); }
        }
        return m;
    }

    // ---- plumbing ----------------------------------------------------------------------------------------------

    <T> CompletableFuture<T> run(Supplier<T> s) {
        return CompletableFuture.supplyAsync(() -> {
            try { return s.get(); }
            catch (RuntimeException e) { plugin.getLogger().log(Level.SEVERE, "Trade error", e); throw e; }
        }, worker);
    }

    /** Completes once everything queued so far has finished (for tests and the simulator). */
    public CompletableFuture<Void> idle() { return run(() -> null); }

    long day() { return api.stats().dayIndex(); }

    /** Players the quota is sized for: today's active players, or yesterday's while today's figure is still smaller. */
    long players() { return Math.max(1, Math.max(api.stats().activePlayersToday(), yesterdayActive)); }

    private void syncDay() {
        long d = day();
        if (d == cDay) return;
        cDay = d;
        cTotals = store.dayTotals(d);
    }

    private long[] totals(String item, String dir) { syncDay(); return cTotals.getOrDefault(item + "\t" + dir, new long[3]); }

    private void count(String item, String dir, long qty, long total, long tariff) {
        long[] t = cTotals.computeIfAbsent(item + "\t" + dir, k -> new long[3]);
        t[0] += qty; t[1] += total; t[2] += tariff;
    }

    private long sum(String dir, int col) {
        syncDay();
        long n = 0;
        for (var e : cTotals.entrySet()) if (e.getKey().endsWith("\t" + dir)) n += e.getValue()[col];
        return n;
    }

    private static boolean ok(TxResult r) { return r.success() || r.failure() == Failure.DUPLICATE_REFERENCE; }

    private static String errKey(Failure f) {
        return switch (f) {
            case INSUFFICIENT_FUNDS -> "err-funds";
            case POLICY_LIMIT, DENIED -> "err-full";
            case ECONOMY_PAUSED -> "err-paused";
            default -> "err-other";
        };
    }

    /**
     * Commits and waits. If the commit threw, the ledger is asked by reference, because handing items back after the coins moved
     * would duplicate them. If it is still running after 30 seconds, this returns null: the shipment stays PENDING and is settled
     * by {@link #resolve} once the commit completes.
     */
    private TxResult settle(CompletableFuture<TxResult> f, String reference, long id) {
        try { return f.get(30, TimeUnit.SECONDS); }
        catch (java.util.concurrent.TimeoutException e) {
            plugin.getLogger().warning("The ledger is slow to confirm " + reference + "; settling it when it does");
            f.whenComplete((r, ex) -> run(() -> { Shipment s = store.shipment(id); if (s != null && s.status().equals("PENDING")) resolve(s); return null; }));
            return null;
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Unclear result for " + reference + "; checking the ledger", e);
            boolean done = api.findByReference(plugin, reference).join().isPresent();
            return new TxResult(done, 0, done ? Failure.NONE : Failure.INTERNAL, "", 0, 0);
        }
    }

    // ---- prices ------------------------------------------------------------------------------------------------

    /** Today's world price (with this week's boom for exports), in units. 0 if the item isn't listed. */
    long priceNow(String item, boolean export) {
        Listing l = (export ? exports : imports).get(item);
        if (l == null) return 0;
        Price p = prices.get(item);
        long base = p == null ? l.base() : p.price();
        return export && item.equals(boomItem) ? Math.round(base * boomMult) : base;
    }

    /** Today's world price as a number of coins, for display and the simulator. */
    public double worldPrice(String item) {
        boolean export = exports.containsKey(item);
        return priceNow(item, export) / Math.pow(10, api.currency().decimals());
    }

    long quota(Listing l) { return Slide.quota(l.quotaPerPlayer(), players()); }

    /** Coins exports may still create today: the smallest of our budget, the autopilot's (if any), and core's cap. */
    private long exportBudgetLeft() {
        long budget = dailyBudget;
        var mp = api.monetaryPolicy();
        if (mp.isPresent()) { var b = mp.get().budget("exports"); if (b.isPresent()) budget = Math.min(budget, b.getAsLong()); }
        PolicyStatus ps = api.policy(plugin);
        if (ps.denied()) return 0;
        return Math.max(0, Math.min(budget - sum(EXPORT, 1), ps.issueRemainingToday()));
    }

    private Quote exportQuote(String item, int want) {
        Listing l = exports.get(item);
        if (l == null) return new Quote(0, 0, 0, "unknown");
        long q = quota(l), used = totals(item, EXPORT)[0], p = priceNow(item, true), budget = exportBudgetLeft();
        int n = (int) Slide.maxExport(p, used, want, q, exportK, budget);
        long total = Slide.exportTotal(p, used, n, q, exportK);
        if (total <= 0) n = 0;
        String reason = n >= want ? null : used + n >= q ? "quota" : "budget";
        return new Quote(n, n == 0 ? 0 : total, 0, reason);
    }

    private Quote importQuote(String item, int want) {
        Listing l = imports.get(item);
        if (l == null) return new Quote(0, 0, 0, imports.isEmpty() ? "off" : "unknown");
        long q = quota(l), used = totals(item, IMPORT)[0], p = priceNow(item, false);
        int n = (int) Math.max(0, Math.min(want, q - used));
        long total = Slide.importTotal(p, l.tariff(), used, n, q, importK);
        return new Quote(n, total, Slide.tariffPart(total, l.tariff()), n >= want ? null : "quota");
    }

    public CompletableFuture<Quote> quoteExport(String item, int qty) { return run(() -> exportQuote(item, qty)); }

    public CompletableFuture<Quote> quoteImport(String item, int qty) { return run(() -> importQuote(item, qty)); }

    /** How many more the ships take (export) or bring (import) today. */
    public CompletableFuture<Long> quotaLeft(String item, boolean export) {
        return run(() -> {
            Listing l = (export ? exports : imports).get(item);
            return l == null ? 0L : Math.max(0, quota(l) - totals(item, export ? EXPORT : IMPORT)[0]);
        });
    }

    /** The Export or Import tab: every listed item, A to Z. */
    public CompletableFuture<List<Row>> board(boolean export) {
        return run(() -> {
            List<Row> out = new ArrayList<>();
            (export ? exports : imports).forEach((item, l) -> {
                long q = quota(l), used = totals(item, export ? EXPORT : IMPORT)[0], p = priceNow(item, export);
                Price pr = prices.get(item);
                double boom = export && item.equals(boomItem) ? boomMult : 1;   // the ▲/▼ shows the day's move, not the boom
                long prev = pr == null ? p : Math.round(pr.prev() * boom);
                long unit = export ? Slide.exportUnit(p, used, q, exportK) : used < q ? Slide.importTotal(p, l.tariff(), used, 1, q, importK) : 0;
                out.add(new Row(item, p, prev, Math.max(0, q - used), unit));
            });
            return out;
        });
    }

    // ---- shipments ---------------------------------------------------------------------------------------------

    /**
     * Exports up to {@code qty} items the caller has already taken from the player (trade/Plan.md §5.3). Coins are created with
     * {@code issue}: export money comes from outside and must not drain the Treasury. Whatever isn't exported goes to Cargo.
     */
    public CompletableFuture<TradeResult> export(UUID p, String item, int qty) {
        return run(() -> {
            Quote q = exportQuote(item, qty);
            if (q.qty() == 0) {
                store.addCargo(p.toString(), item, qty);
                return new TradeResult(0, 0, "export-" + (q.reason() == null ? "budget" : q.reason()));
            }
            long[] box = new long[1];
            store.tx(() -> {   // the shipment and the surplus are written together, so a crash can't lose either
                box[0] = store.insertShipment(p, item, EXPORT, q.qty(), q.total(), 0, day(), System.currentTimeMillis());
                if (qty > q.qty()) store.addCargo(p.toString(), item, qty - q.qty());
            });
            long id = box[0];
            count(item, EXPORT, q.qty(), q.total(), 0);
            String ref = "exp:" + id;
            TxResult r = settle(api.transaction(plugin, "trade.export", msg.plain("memo-export", "qty", q.qty(), "item", Items.pretty(item)))
                    .reference(ref).actor(p).issue(api.accounts().player(p), q.total()).commit(), ref, id);
            if (r == null) return new TradeResult(0, 0, "err-slow");
            boolean done = ok(r);
            store.tx(() -> { store.status(id, done ? "DONE" : "FAILED"); if (!done) store.addCargo(p.toString(), item, q.qty()); });
            if (!done) count(item, EXPORT, -q.qty(), -q.total(), 0);
            refresh();
            return done ? new TradeResult(q.qty(), q.total(), q.reason() == null ? null : "export-" + q.reason()) : new TradeResult(0, 0, errKey(r.failure()));
        });
    }

    /**
     * Buys {@code qty} from abroad (trade/Plan.md §5.4), for at most {@code maxTotal} (the price the player agreed to). The tariff
     * goes to the Treasury and the rest is destroyed: it was paid to foreigners. The items go to Cargo.
     */
    public CompletableFuture<TradeResult> importItems(UUID p, String item, int qty, long maxTotal) {
        return run(() -> {
            Quote q = importQuote(item, qty);
            if (q.qty() == 0) return new TradeResult(0, 0, "import-" + q.reason());
            if (q.total() > maxTotal) return new TradeResult(0, q.total(), "too-expensive");
            var acct = api.accounts().player(p);   // core refuses with INSUFFICIENT_FUNDS if the player can't pay: never below zero
            long id = store.insertShipment(p, item, IMPORT, q.qty(), q.total(), q.tariff(), day(), System.currentTimeMillis());
            count(item, IMPORT, q.qty(), q.total(), q.tariff());
            String ref = "imp:" + id;
            var tx = api.transaction(plugin, "trade.import", msg.plain("memo-import", "qty", q.qty(), "item", Items.pretty(item))).reference(ref).actor(p);
            if (q.tariff() > 0) tx.move(acct, api.treasury(), q.tariff());
            if (q.total() - q.tariff() > 0) tx.destroy(acct, q.total() - q.tariff());
            TxResult r = settle(tx.commit(), ref, id);
            if (r == null) return new TradeResult(0, 0, "err-slow");
            boolean done = ok(r);
            store.tx(() -> { store.status(id, done ? "DONE" : "FAILED"); if (done) store.addCargo(p.toString(), item, q.qty()); });
            if (!done) count(item, IMPORT, -q.qty(), -q.total(), -q.tariff());
            refresh();
            return done ? new TradeResult(q.qty(), q.total(), q.qty() < qty ? "import-quota" : null) : new TradeResult(0, 0, errKey(r.failure()));
        });
    }

    /** Empties a player's Cargo; the caller puts it in the inventory and hands back what didn't fit with {@link #stow}. */
    public CompletableFuture<Map<String, Integer>> takeCargo(UUID p) { return run(() -> store.takeCargo(p)); }

    public CompletableFuture<Void> stow(UUID p, Map<String, Integer> items) {
        return run(() -> { store.tx(() -> items.forEach((k, n) -> { if (n > 0) store.addCargo(p.toString(), k, n); })); return null; });
    }

    public CompletableFuture<Map<String, Integer>> cargo(UUID p) { return run(() -> store.cargo(p)); }

    // ---- startup, days and weeks -------------------------------------------------------------------------------

    /** Finishes a PENDING shipment from the ledger: paid means DONE, unpaid means FAILED. An unpaid export's items and a paid import's go to Cargo. */
    private void resolve(Shipment s) {
        boolean paid = api.findByReference(plugin, (s.direction().equals(EXPORT) ? "exp:" : "imp:") + s.id()).join().isPresent();
        store.tx(() -> {
            store.status(s.id(), paid ? "DONE" : "FAILED");
            if (s.direction().equals(EXPORT) ? !paid : paid) store.addCargo(s.uuid(), s.item(), s.qty());
        });
        if (!paid && s.day() == cDay) count(s.item(), s.direction(), -s.qty(), -s.total(), -s.tariff());
    }

    /** At startup: settles anything that was in flight when the server stopped (CLAUDE.md rule 7), then loads prices and the boom. */
    public CompletableFuture<Void> recover() {
        return run(() -> { store.pending().forEach(this::resolve); return null; }).thenCompose(v -> load());
    }

    /** Loads world prices (adding any newly listed item at its base) and this week's boom. Also used by {@code /trade admin reload}. */
    public CompletableFuture<Void> load() {
        return run(() -> {
            cDay = -1;
            long today = day();
            prices.putAll(store.prices());
            store.tx(() -> {
                for (var e : allListings().entrySet()) if (!prices.containsKey(e.getKey())) {
                    Price p = new Price(today, e.getValue().base(), e.getValue().base());
                    prices.put(e.getKey(), p);
                    store.savePrice(e.getKey(), p);
                }
            });
            Object[] b = store.boom(api.stats().weekIndex());
            boomItem = b == null ? null : (String) b[0];
            boomMult = b == null ? 1 : (double) b[1];
            if (yesterdayActive < 0) yesterdayActive = (long) api.stats().avgActivePlayers7d();
            refresh();
            return null;
        });
    }

    private Map<String, Listing> allListings() {
        Map<String, Listing> all = new TreeMap<>(imports);
        all.putAll(exports);
        return all;
    }

    /**
     * On the Day event. World prices move on catch-up days too, so the walk stays continuous (trade/Plan.md §5.2); each day
     * moves them once, however often this is called.
     */
    public void onDay(Stats ended, long newDay, boolean catchUp) {
        run(() -> {
            yesterdayActive = ended.activePlayersToday();
            store.tx(() -> allListings().forEach((item, l) -> {
                Price p = prices.get(item);
                if (p != null && p.day() >= newDay) return;
                long now = p == null ? l.base() : p.price();
                Price next = new Price(newDay, WorldPrices.step(wp, item, newDay, l.base(), now), now);
                prices.put(item, next);
                store.savePrice(item, next);
            }));
            refresh();
            return null;
        });
    }

    /** On the Week event: maybe start a boom, and announce it. Never on catch-up. */
    public void onWeek(long week, boolean catchUp) {
        if (catchUp) { run(() -> { setBoom(week, null, 1); return null; }); return; }   // last week's boom ends; no new one after downtime
        run(() -> {
            String item = Booms.roll(wp.seed(), week, boomChance, List.copyOf(exports.keySet()));
            setBoom(week, item, boomMultiplier);
            return null;
        });
    }

    /** {@code /trade admin boom}: starts a boom for the rest of this week, now. */
    public CompletableFuture<Void> boom(String item, double mult) { return run(() -> { setBoom(api.stats().weekIndex(), item, mult); return null; }); }

    private void setBoom(long week, String item, double mult) {
        boomItem = item;
        boomMult = item == null ? 1 : mult;
        if (item == null) { refresh(); return; }
        store.saveBoom(week, item, mult);
        refresh();
        Component text = msg.get("news-boom", "item", Items.pretty(item).toUpperCase(), "pct", Math.round((mult - 1) * 100));
        Bukkit.getGlobalRegionScheduler().run(plugin, t -> { Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(text)); Bukkit.getConsoleSender().sendMessage(text); });
    }

    /** Port News: this week's boom, and today's busiest exports. */
    public CompletableFuture<List<Component>> news() {
        return run(() -> {
            List<Component> out = new ArrayList<>();
            out.add(boomItem == null ? msg.get("news-quiet") : msg.get("news-boom", "item", Items.pretty(boomItem).toUpperCase(), "pct", Math.round((boomMult - 1) * 100)));
            syncDay();
            List<String> busy = cTotals.entrySet().stream().filter(e -> e.getKey().endsWith("\t" + EXPORT) && e.getValue()[0] > 0)
                    .sorted(Comparator.comparingLong((Map.Entry<String, long[]> e) -> e.getValue()[0]).reversed()).limit(3)
                    .map(e -> Items.pretty(e.getKey().substring(0, e.getKey().indexOf('\t'))) + " (" + e.getValue()[0] + ")").toList();
            out.add(busy.isEmpty() ? msg.get("news-busy-none") : msg.get("news-busy", "items", String.join(", ", busy)));
            return out;
        });
    }

    // ---- indicators (IndicatorProvider "capitaltrade") ---------------------------------------------------------

    /** Recomputes the cached numbers dashboards and placeholders read. Worker thread only. */
    private void refresh() {
        long exp = sum(EXPORT, 1), imp = sum(IMPORT, 1), tariffs = sum(IMPORT, 2), used = 0, quota = 0;
        for (var e : exports.entrySet()) {
            long q = quota(e.getValue()), u = totals(e.getKey(), EXPORT)[0];
            used += Math.min(u, q); quota += q;
            quotaLeftCache.put(e.getKey(), Math.max(0, q - u));
        }
        String boom = boomItem;
        indicators = List.of(
                new Indicator("exports_value_today", "Exports today", exp, Unit.COINS),
                new Indicator("exports_new_money_today", "New money from exports today", exp, Unit.COINS),
                new Indicator("imports_value_today", "Imports today", imp, Unit.COINS),
                new Indicator("tariffs_today", "Import tariffs today", tariffs, Unit.COINS),
                new Indicator("imports_destroyed_today", "Coins sent abroad today", imp - tariffs, Unit.COINS),
                new Indicator("trade_balance_today", "Exports minus imports today", exp - imp, Unit.COINS),
                new Indicator("boom_multiplier", boom == null ? "No boom this week" : "This week's boom: " + Items.pretty(boom), boom == null ? 1 : boomMult, Unit.INDEX),
                new Indicator("export_quota_used", "Export quota used today", quota == 0 ? 0 : 100.0 * used / quota, Unit.PERCENT));
    }

    @Override public String id() { return "capitaltrade"; }

    @Override public String displayName() { return "Trade"; }

    @Override public List<Indicator> indicators() { return indicators; }

    @Override public void close() {
        worker.shutdown();
        try { worker.awaitTermination(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        store.close();
    }
}
