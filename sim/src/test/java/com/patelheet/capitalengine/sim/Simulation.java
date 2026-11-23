package com.patelheet.capitalengine.sim;

import com.patelheet.capitalengine.api.CapitalApi;
import com.patelheet.capitalengine.api.event.CapitalAlertEvent;
import com.patelheet.capitalengine.api.event.CapitalAuditEvent;
import com.patelheet.capitalengine.api.event.CapitalDayEvent;
import com.patelheet.capitalengine.api.event.CapitalTransactionEvent;
import com.patelheet.capitalengine.api.service.Indicator;
import com.patelheet.capitalengine.api.service.IndicatorProvider;
import com.patelheet.capitalengine.api.Severity;
import com.patelheet.capitalengine.bank.BankService;
import com.patelheet.capitalengine.bank.CapitalBankPlugin;
import com.patelheet.capitalengine.core.CapitalEnginePlugin;
import com.patelheet.capitalengine.market.CapitalMarketPlugin;
import com.patelheet.capitalengine.market.MarketService;
import com.patelheet.capitalengine.trade.CapitalTradePlugin;
import com.patelheet.capitalengine.trade.TradeService;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.PluginDescriptionFile;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Boots the real CapitalEngine, CapitalMarket and (if the scenario asks) CapitalBank and CapitalTrade on MockBukkit, runs a scenario one economic day at a time and records what
 * happened (sim/Plan.md §5.1, §5.2). Everything runs on the calling thread and waits for each answer, so a seed repeats exactly.
 */
final class Simulation {
    /** Columns of daily.csv, in order. */
    static final List<String> COLUMNS = List.of("day", "money_supply", "active_money", "treasury", "issued", "destroyed", "treasury_in", "treasury_out",
            "active_players", "price_index", "weekly_inflation", "savings_rate_week", "spread", "player_trade_share", "stock_value", "new_money_policy",
            "buyer_budget", "jobs_budget", "gini", "median_balance", "p90_balance", "unmet_needs", "iron_ask", "wheat_ask", "wheat_stock",
            "savings", "loan_book", "interest_paid", "upkeep_collected", "loans_overdue", "loans_written_off", "exports", "imports", "tariffs",
            "watch_best_ask", "watch_bid", "watch_world", "watch_exported", "server_stock");

    /** Column numbers the checks and tests read. */
    static final int MONEY = 1, TREASURY = 3, INFLATION = 10, TRADE_SHARE = 13, UNMET = 21, WHEAT_ASK = 23, WHEAT_STOCK = 24, SAVINGS = 25, LOAN_BOOK = 26,
            INTEREST = 27, UPKEEP = 28, OVERDUE = 29, WRITTEN_OFF = 30, EXPORTS = 31, IMPORTS = 32, TARIFFS = 33, WATCH_BEST_ASK = 34, WATCH_BID = 35,
            WATCH_WORLD = 36, WATCH_EXPORTED = 37, SERVER_STOCK = 38;

    record Result(Scenario scenario, List<double[]> rows, List<String> failures, String csv, Map<String, Integer> whyUnmet, List<String> items) {
        boolean passed() { return failures.isEmpty(); }
    }

    /** Core's nightly audit, and any CRITICAL alert (the bank check raises one when the bank and the ledger disagree). */
    public static class AuditLog implements Listener {
        final List<String> problems = new CopyOnWriteArrayList<>();
        @EventHandler public void audit(CapitalAuditEvent e) { if (!e.ok()) problems.addAll(e.problems()); }
        @EventHandler public void alert(CapitalAlertEvent e) { if (e.severity() == Severity.CRITICAL) problems.add(e.source() + " " + e.code() + ": " + e.message()); }
    }

    private final Scenario sc;
    private ServerMock server;
    private CapitalEnginePlugin core;
    private MarketService svc;
    private BankService bank;
    private TradeService trade;
    private CapitalApi api;
    private String watch;

    Simulation(Scenario sc) { this.sc = sc; }

    Result run() throws Exception {
        System.setProperty("capitalengine.sim", "true");
        server = MockBukkit.mock();
        try {
            File parent = server.getPluginManager().getParentTemporaryDirectory();
            prepare(parent);
            core = MockBukkit.loadWith(CapitalEnginePlugin.class, descriptor("CapitalEngine"));
            api = server.getServicesManager().load(CapitalApi.class);
            CapitalMarketPlugin market = MockBukkit.loadWith(CapitalMarketPlugin.class, descriptor("CapitalMarket"));
            if (sc.has("bank")) {
                CapitalBankPlugin b = MockBukkit.loadWith(CapitalBankPlugin.class, descriptor("CapitalBank"));
                // The sim runs the bank's day itself, after the market's day has settled, with the agents' land (MockBukkit has no claim
                // plugin): two workers moving money at once would make a run unrepeatable. "Coins arriving" collection is switched off
                // for the same reason (it debounces on the wall clock); the daily collection still takes arrears.
                CapitalDayEvent.getHandlerList().unregister((Listener) b);
                CapitalTransactionEvent.getHandlerList().unregister((Listener) b);
                bank = b.service();
            }
            if (sc.has("trade")) trade = MockBukkit.loadWith(CapitalTradePlugin.class, descriptor("CapitalTrade")).service();
            AuditLog audits = new AuditLog();
            server.getPluginManager().registerEvents(audits, market);
            server.getScheduler().performTicks(2);   // the clock starts and the plugins recover on the first ticks
            svc = market.service();
            get(svc.recover());
            drain();
            return loop(audits);
        } catch (org.opentest4j.TestAbortedException e) {
            throw new IllegalStateException("MockBukkit doesn't implement something the plugins used (JUnit would call this test skipped)", e);
        } finally {
            MockBukkit.unmock();
        }
    }

    // ---- setup -------------------------------------------------------------------------------------------------

    /** Writes each plugin's config, messages and price files into its data folder so they never depend on classpath order. */
    private void prepare(File parent) throws Exception {
        // MockBukkit names a plugin's data folder <name>-<version>, so read the versions instead of assuming them
        File ce = new File(parent, "CapitalEngine-" + descriptor("CapitalEngine").getVersion()), cm = new File(parent, "CapitalMarket-" + descriptor("CapitalMarket").getVersion());
        ce.mkdirs(); cm.mkdirs();
        overlay(resource("config.yml", "core"), sc.core(), new File(ce, "config.yml"));
        copy("messages.yml", "core", new File(ce, "messages.yml"));
        Map<String, Object> market = new java.util.LinkedHashMap<>(sc.config());
        market.keySet().removeIf(k -> k.startsWith("sim."));   // sim.* settings steer the agents, not the plugin
        market.putIfAbsent("tutorial.enabled", false);   // the tutorial is free money that real players only get once
        overlay(resource("config.yml", "market"), market, new File(cm, "config.yml"));
        for (String f : List.of("messages.yml", "values.yml", "server-items.yml")) copy(f, "market", new File(cm, f));
        YamlConfiguration projects = new YamlConfiguration();
        sc.projects().forEach((name, needs) -> needs.forEach((item, qty) -> projects.set(name + ".needs." + item, qty)));
        projects.save(new File(cm, "projects.yml"));
        if (sc.has("bank")) {
            File cb = folder(parent, "CapitalBank");
            overlay(resource("config.yml", "bank"), sc.bank(), new File(cb, "config.yml"));
            copy("messages.yml", "bank", new File(cb, "messages.yml"));
        }
        if (sc.has("trade")) {
            File ct = folder(parent, "CapitalTrade");
            overlay(resource("config.yml", "trade"), sc.trade(), new File(ct, "config.yml"));
            copy("messages.yml", "trade", new File(ct, "messages.yml"));
            YamlConfiguration exports;   // sim.export-quota-scale tries other quotas without editing the shipped list
            try (var in = resource("exports.yml", "trade").openStream()) { exports = YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(in, StandardCharsets.UTF_8)); }
            double qs = num("sim.export-quota-scale", 1);
            for (String item : exports.getKeys(false)) exports.set(item + ".quota-per-active-player", Math.max(1, Math.round(exports.getLong(item + ".quota-per-active-player") * qs)));
            exports.save(new File(ct, "exports.yml"));
            YamlConfiguration imports = new YamlConfiguration();   // off unless the scenario lists some (and sim.imports isn't false)
            if (!"false".equals(String.valueOf(sc.config().get("sim.imports")))) sc.imports().forEach((k, v) -> { if (!(v instanceof org.bukkit.configuration.ConfigurationSection)) imports.set(k, v); });
            imports.save(new File(ct, "imports.yml"));
        }
    }

    private static File folder(File parent, String plugin) throws Exception {
        File f = new File(parent, plugin + "-" + descriptor(plugin).getVersion());
        f.mkdirs();
        return f;
    }

    /** A bundled resource from one module (core and market both ship a config.yml and a messages.yml). */
    private static URL resource(String name, String module) throws IOException {
        for (URL u : Collections.list(Simulation.class.getClassLoader().getResources(name))) if (u.toString().contains("/" + module + "/")) return u;
        throw new IOException("No " + name + " from module " + module);
    }

    private static void copy(String name, String module, File to) throws IOException {
        try (var in = resource(name, module).openStream()) { Files.write(to.toPath(), in.readAllBytes()); }
    }

    /** The module's shipped config with the scenario's values on top. A key the shipped file doesn't have is refused: the plugin would never read it. */
    private static void overlay(URL base, Map<String, Object> overrides, File to) throws IOException {
        YamlConfiguration y;
        try (var in = base.openStream()) { y = YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(in, StandardCharsets.UTF_8)); }
        overrides.forEach((k, v) -> {
            if (!y.contains(k) && !k.equals("tutorial.enabled")) throw new IllegalArgumentException("'" + k + "' isn't a setting in " + base + " (check the module prefix and spelling)");
            y.set(k, v);
        });
        y.save(to);
    }

    static PluginDescriptionFile descriptor(String name) throws Exception {
        for (URL url : Collections.list(Simulation.class.getClassLoader().getResources("plugin.yml"))) {
            try (var in = url.openStream()) { var d = new PluginDescriptionFile(in); if (d.getName().equals(name)) return d; }
        }
        throw new IllegalStateException("no plugin.yml for " + name);
    }

    static <T> T get(java.util.concurrent.CompletableFuture<T> f) throws Exception { return f.get(60, TimeUnit.SECONDS); }

    // ---- the run -----------------------------------------------------------------------------------------------

    private Result loop(AuditLog audits) throws Exception {
        List<Agent> agents = new ArrayList<>();
        catalog = sc.production().values().stream().flatMap(m -> m.keySet().stream()).distinct().sorted().toList();
        double spend = num("sim.spend-share", 0.6), reliability = num("sim.borrower-reliability", 0.9), landScale = num("sim.land-scale", 1);
        watch = (String) sc.config().getOrDefault("sim.watch", "wheat");
        Map<UUID, Long> land = new HashMap<>();
        int n = 0;
        for (var e : sc.players().entrySet()) {
            Agent.Role role = Agent.Role.valueOf(e.getKey().toUpperCase());
            for (int i = 0; i < e.getValue(); i++, n++) {
                PlayerMock p = new PlayerMock(server, role.name().toLowerCase() + i, UUID.nameUUIDFromBytes((sc.seed() + ":" + n).getBytes(StandardCharsets.UTF_8)));
                server.addPlayer(p);
                agents.add(new Agent(role, p, sc.seed() * 1_000 + n, new Agent.Services(svc, bank, trade), sc.onlineChance().getOrDefault(e.getKey(), sc.onlineChance().getOrDefault("default", 0.6)),
                        sc.production().getOrDefault(e.getKey(), Map.of()), () -> api.accounts().player(p.getUniqueId()).balance(), spend, catalog, reliability));
                // each player's claimed land: the role's typical area, give or take half (its own generator, so the agents' choices don't shift)
                long area = Math.round(sc.land().getOrDefault(e.getKey(), sc.land().getOrDefault("default", 0L)) * landScale * (0.5 + new Random(sc.seed() * 7_919 + n).nextDouble()));
                if (area > 0) land.put(p.getUniqueId(), area);
            }
        }
        server.getScheduler().performTicks(2);
        drain();
        long[] bankCfg = bank == null ? null : bankLimits();

        Random order = new Random(sc.seed());
        List<double[]> rows = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        Asserts asserts = new Asserts(sc, failures);
        for (int day = 1; day <= sc.days(); day++) {
            for (var ev : sc.events()) if (ev.day() == day) apply(ev, agents, order);
            List<Agent> today = new ArrayList<>(agents);
            Collections.shuffle(today, order);
            Set<UUID> online = new HashSet<>();
            for (Agent a : today) {
                if (!a.online()) continue;
                int minutes = a.minutes();
                core.clockControl().recordActivity(a.id, minutes);
                a.played += minutes;
                online.add(a.id);
                a.act(day);
            }
            var ending = api.stats();   // the day's flows and active players, before the day rolls over and resets them
            Map<String, Double> tradeToday = indicators("capitaltrade");   // "today" sums, which reset at the rollover
            core.clockControl().advanceDays(1);
            drain();
            if (bank != null) {
                // what interest may be at most: each saver's savings up to the cap, at today's rate, and the day's budget
                long bound = 0;
                double perDay = bank.savingsPerDay();
                for (Agent a : agents) bound += (long) Math.floor(Math.min(bank.savings(a.id), bankCfg[0]) * perDay);
                long budget = api.monetaryPolicy().flatMap(m -> { var b = m.budget("savings"); return b.isPresent() ? java.util.Optional.of(b.getAsLong()) : java.util.Optional.empty(); }).orElse(bankCfg[2]);
                get(bank.onDay(api.stats().dayIndex(), false, new BankService.Snapshot(land, online)));
                drain();
                long now = api.stats().dayIndex(), maxAge = bankCfg[3] + bankCfg[4] + 2;
                Set<UUID> dormant = agents.stream().filter(a -> a.dormant).map(a -> a.id).collect(Collectors.toSet());
                List<String> trapped = get(bank.openLoans()).stream().filter(l -> !dormant.contains(l.uuid()) && now - l.startDay() > maxAge)
                        .map(l -> "loan #" + l.id() + " is " + (now - l.startDay()) + " days old (term plus write-off is " + (maxAge - 2) + ")").toList();
                asserts.bank(day, get(bank.check()), indicators("capitalbank").getOrDefault("interest_paid_today", 0.0).longValue(), bound, budget, trapped);
            }
            double[] row = capture(day, ending, agents, tradeToday);
            rows.add(row);
            asserts.daily(day, row, books(), get(svc.escrowCheck()));
            if (trade != null) asserts.trade(day, row, tradeBudget());
        }
        get(core.ledger().barrier());
        TimeUnit.MILLISECONDS.sleep(100);   // the nightly audit runs on a core thread
        asserts.finish(rows, audits.problems);
        Map<String, Integer> why = new java.util.TreeMap<>();
        agents.forEach(a -> a.whyUnmet.forEach((k, v) -> why.merge(a.role.name().toLowerCase() + " " + k, v, Integer::sum)));
        List<String> items = new ArrayList<>();
        for (String item : catalog) {
            var book = get(svc.book(item));
            var price = get(svc.prices(List.of(item))).get(item);
            items.add(String.format("%s: server stock %d, price %.2f to %.2f, %d listings for %d, %d wanted orders for %d", item, price.stock(), price.bid() / 100.0, price.ask() / 100.0,
                    book.asks().size(), book.asks().stream().mapToInt(o -> o.qty()).sum(), book.bids().size(), book.bids().stream().mapToInt(o -> o.qty()).sum()));
        }
        return new Result(sc, rows, failures, csv(rows), why, items);
    }

    private double num(String key, double def) { return ((Number) sc.config().getOrDefault(key, def)).doubleValue(); }

    /** {interest cap, unused, daily interest budget without a policy, loan term, write-off days}, as the bank reads them. */
    private long[] bankLimits() {
        var y = YamlConfiguration.loadConfiguration(new File(server.getPluginManager().getPlugin("CapitalBank").getDataFolder(), "config.yml"));
        return new long[]{api.currency().parse(y.getString("savings.interest-cap")), Math.round(y.getDouble("rates.max-savings-weekly") * 1e6),
                api.currency().parse(y.getString("savings.savings-daily-budget")), y.getInt("loans.term-days"), y.getInt("loans.write-off-days")};
    }

    /** The most exports may create in a day: Trade's own budget and core's cap for CapitalTrade. */
    private long tradeBudget() {
        var y = YamlConfiguration.loadConfiguration(new File(server.getPluginManager().getPlugin("CapitalTrade").getDataFolder(), "config.yml"));
        return Math.min(api.currency().parse(y.getString("exports.daily-budget")), api.policy(server.getPluginManager().getPlugin("CapitalTrade")).issueRemainingToday());   // read at the start of a day: the whole cap
    }

    private Map<String, Double> indicators(String provider) {
        return server.getServicesManager().getRegistrations(IndicatorProvider.class).stream().map(r -> r.getProvider())
                .filter(p -> p.id().equals(provider)).findFirst().map(IndicatorProvider::indicators).orElse(List.of())
                .stream().collect(Collectors.toMap(Indicator::key, Indicator::value));
    }

    private void apply(Scenario.Event ev, List<Agent> agents, Random rng) {
        switch (ev.type()) {
            case "production-multiplier" -> agents.stream().filter(a -> a.role.name().equalsIgnoreCase(ev.role())).forEach(a -> a.multiplier = ev.x());
            case "go-dormant" -> {
                List<Agent> live = agents.stream().filter(a -> !a.dormant).collect(Collectors.toCollection(ArrayList::new));
                Collections.shuffle(live, rng);
                live.stream().limit(Math.round(live.size() * ev.share())).forEach(a -> a.dormant = true);
            }
            default -> throw new IllegalArgumentException("Unknown event type " + ev.type());
        }
    }

    /** Waits for the market's worker and core's ledger writer, so the day is fully settled before it is measured. */
    private void drain() throws Exception {
        for (int i = 0; i < 2; i++) {
            get(svc.idle());
            if (bank != null) get(bank.idle());
            if (trade != null) get(trade.idle());
            get(core.ledger().barrier());
            server.getScheduler().performTicks(1);
        }
    }

    /** Every account's balance: they must add up to zero (the Mint holds the opposite of all money), and only the Mint may be negative. */
    private long[] books() {
        long sum = 0, negatives = 0;
        for (var a : core.ledger().accounts()) {
            sum += a.balance();
            if (a.balance() < 0 && !a.id().equals(com.patelheet.capitalengine.core.ledger.Ledger.MINT_ID)) negatives++;
        }
        return new long[]{sum, negatives};
    }

    private List<String> catalog;

    private double[] capture(int day, com.patelheet.capitalengine.api.Stats st, List<Agent> agents, Map<String, Double> tr) {
        var ind = indicators("capitalmarket");
        var bk = indicators("capitalbank");
        var pol = svc.policy();
        double[] wallets = agents.stream().mapToDouble(a -> api.accounts().player(a.id).balance() + (bank == null ? 0 : bank.savings(a.id))).sorted().toArray();
        long tried = agents.stream().mapToLong(a -> a.needsTried).sum(), met = agents.stream().mapToLong(a -> a.needsMet).sum();
        var iron = get2(svc.prices(List.of("iron_ingot"))).get("iron_ingot");
        var wheat = get2(svc.prices(List.of("wheat"))).get("wheat");
        var w = get2(svc.prices(List.of(watch))).get(watch);
        var asks = get2(svc.book(watch)).asks();
        long exported = agents.stream().mapToLong(a -> a.exported.getOrDefault(watch, 0)).sum();
        return new double[]{day, st.moneySupply(), st.activeMoneySupply(), st.treasuryBalance(), st.issuedToday(), st.destroyedToday(), st.treasuryInToday(), st.treasuryOutToday(),
                st.activePlayersToday(), ind.getOrDefault("price_index", 100.0), ind.getOrDefault("weekly_inflation", 0.0), pol == null ? 0 : pol.rate() * 100,
                ind.getOrDefault("mm_spread", 0.0), ind.getOrDefault("player_trade_share", 0.0), ind.getOrDefault("server_stock_value", 0.0),
                pol == null ? 0 : pol.newMoney(), pol == null ? 0 : pol.buyer(), pol == null ? 0 : pol.jobs(), gini(wallets),
                wallets.length == 0 ? 0 : wallets[wallets.length / 2], wallets.length == 0 ? 0 : wallets[(int) Math.min(wallets.length - 1, wallets.length * 0.9)],
                tried == 0 ? 0 : 1 - (double) met / tried, iron == null ? 0 : iron.ask(), wheat == null ? 0 : wheat.ask(), wheat == null ? 0 : wheat.stock(),
                bk.getOrDefault("total_savings", 0.0), bk.getOrDefault("loan_book", 0.0), bk.getOrDefault("interest_paid_today", 0.0), bk.getOrDefault("upkeep_collected_today", 0.0),
                bk.getOrDefault("loans_overdue", 0.0), bk.getOrDefault("loans_written_off", 0.0), tr.getOrDefault("exports_value_today", 0.0), tr.getOrDefault("imports_value_today", 0.0),
                tr.getOrDefault("tariffs_today", 0.0), asks.isEmpty() ? 0 : asks.get(0).price(), w == null ? 0 : w.bid(),
                trade == null ? 0 : Math.round(trade.worldPrice(watch) * Math.pow(10, api.currency().decimals())), exported,
                get2(svc.prices(catalog)).values().stream().mapToLong(x -> x.stock()).sum()};
    }

    private static <T> T get2(java.util.concurrent.CompletableFuture<T> f) { try { return get(f); } catch (Exception e) { throw new IllegalStateException(e); } }

    static double gini(double[] sorted) {
        double sum = 0, weighted = 0;
        for (int i = 0; i < sorted.length; i++) { sum += sorted[i]; weighted += (i + 1) * sorted[i]; }
        return sum <= 0 ? 0 : (2 * weighted) / (sorted.length * sum) - (sorted.length + 1.0) / sorted.length;
    }

    static String csv(List<double[]> rows) {
        StringBuilder sb = new StringBuilder(String.join(",", COLUMNS)).append('\n');
        for (double[] r : rows) {
            for (int i = 0; i < r.length; i++) sb.append(i == 0 ? "" : ",").append(r[i] == Math.rint(r[i]) ? String.valueOf((long) r[i]) : String.format("%.5f", r[i]));
            sb.append('\n');
        }
        return sb.toString();
    }
}
