package com.patelheet.capitalengine.sim;

import com.patelheet.capitalengine.api.CapitalApi;
import com.patelheet.capitalengine.api.event.CapitalAuditEvent;
import com.patelheet.capitalengine.api.service.Indicator;
import com.patelheet.capitalengine.api.service.IndicatorProvider;
import com.patelheet.capitalengine.core.CapitalEnginePlugin;
import com.patelheet.capitalengine.market.CapitalMarketPlugin;
import com.patelheet.capitalengine.market.MarketService;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
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
 * Boots the real CapitalEngine and CapitalMarket on MockBukkit, runs a scenario one economic day at a time and records what
 * happened (sim/Plan.md §5.1, §5.2). Everything runs on the calling thread and waits for each answer, so a seed repeats exactly.
 */
final class Simulation {
    /** Columns of daily.csv, in order. */
    static final List<String> COLUMNS = List.of("day", "money_supply", "active_money", "treasury", "issued", "destroyed", "treasury_in", "treasury_out",
            "active_players", "price_index", "weekly_inflation", "savings_rate_week", "spread", "player_trade_share", "stock_value", "new_money_policy",
            "buyer_budget", "jobs_budget", "gini", "median_balance", "p90_balance", "unmet_needs", "iron_ask", "wheat_ask");

    record Result(Scenario scenario, List<double[]> rows, List<String> failures, String csv, Map<String, Integer> whyUnmet, List<String> items) {
        boolean passed() { return failures.isEmpty(); }
    }

    public static class AuditLog implements Listener {
        final List<String> problems = new CopyOnWriteArrayList<>();
        @EventHandler public void audit(CapitalAuditEvent e) { if (!e.ok()) problems.addAll(e.problems()); }
    }

    private final Scenario sc;
    private ServerMock server;
    private CapitalEnginePlugin core;
    private MarketService svc;
    private CapitalApi api;

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
            AuditLog audits = new AuditLog();
            server.getPluginManager().registerEvents(audits, market);
            server.getScheduler().performTicks(2);   // the clock starts and the market recovers on the first ticks
            svc = market.service();
            get(svc.recover());
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
    }

    /** A bundled resource from one module (core and market both ship a config.yml and a messages.yml). */
    private static URL resource(String name, String module) throws IOException {
        for (URL u : Collections.list(Simulation.class.getClassLoader().getResources(name))) if (u.toString().contains("/" + module + "/")) return u;
        throw new IOException("No " + name + " from module " + module);
    }

    private static void copy(String name, String module, File to) throws IOException {
        try (var in = resource(name, module).openStream()) { Files.write(to.toPath(), in.readAllBytes()); }
    }

    private static void overlay(URL base, Map<String, Object> overrides, File to) throws IOException {
        YamlConfiguration y;
        try (var in = base.openStream()) { y = YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(in, StandardCharsets.UTF_8)); }
        overrides.forEach(y::set);
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
        List<String> catalog = sc.production().values().stream().flatMap(m -> m.keySet().stream()).distinct().sorted().toList();
        double spend = ((Number) sc.config().getOrDefault("sim.spend-share", 0.6)).doubleValue();
        int n = 0;
        for (var e : sc.players().entrySet()) {
            Agent.Role role = Agent.Role.valueOf(e.getKey().toUpperCase());
            for (int i = 0; i < e.getValue(); i++, n++) {
                PlayerMock p = new PlayerMock(server, role.name().toLowerCase() + i, UUID.nameUUIDFromBytes((sc.seed() + ":" + n).getBytes(StandardCharsets.UTF_8)));
                server.addPlayer(p);
                agents.add(new Agent(role, p, sc.seed() * 1_000 + n, svc, sc.onlineChance().getOrDefault(e.getKey(), sc.onlineChance().getOrDefault("default", 0.6)),
                        sc.production().getOrDefault(e.getKey(), Map.of()), () -> api.accounts().player(p.getUniqueId()).balance(), spend, catalog));
            }
        }
        server.getScheduler().performTicks(2);
        drain();

        Random order = new Random(sc.seed());
        List<double[]> rows = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        Asserts asserts = new Asserts(sc, failures);
        for (int day = 1; day <= sc.days(); day++) {
            for (var ev : sc.events()) if (ev.day() == day) apply(ev, agents, order);
            List<Agent> today = new ArrayList<>(agents);
            Collections.shuffle(today, order);
            for (Agent a : today) {
                if (!a.online()) continue;
                core.clockControl().recordActivity(a.id, a.minutes());
                a.act(day);
            }
            var ending = api.stats();   // the day's flows and active players, before the day rolls over and resets them
            core.clockControl().advanceDays(1);
            drain();
            double[] row = capture(day, ending, agents);
            rows.add(row);
            asserts.daily(day, row, books(), get(svc.escrowCheck()));
        }
        get(core.ledger().barrier());
        TimeUnit.MILLISECONDS.sleep(100);   // the nightly audit runs on a core thread
        asserts.finish(rows, audits.problems);
        Map<String, Integer> why = new java.util.TreeMap<>();
        agents.forEach(a -> a.whyUnmet.forEach((k, v) -> why.merge(k, v, Integer::sum)));
        List<String> items = new ArrayList<>();
        for (String item : catalog) {
            var book = get(svc.book(item));
            var price = get(svc.prices(List.of(item))).get(item);
            items.add(String.format("%s: server stock %d, price %.2f to %.2f, %d listings for %d, %d wanted orders for %d", item, price.stock(), price.bid() / 100.0, price.ask() / 100.0,
                    book.asks().size(), book.asks().stream().mapToInt(o -> o.qty()).sum(), book.bids().size(), book.bids().stream().mapToInt(o -> o.qty()).sum()));
        }
        return new Result(sc, rows, failures, csv(rows), why, items);
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
        for (int i = 0; i < 2; i++) { get(svc.idle()); get(core.ledger().barrier()); server.getScheduler().performTicks(1); }
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

    private double[] capture(int day, com.patelheet.capitalengine.api.Stats st, List<Agent> agents) {
        var ind = server.getServicesManager().getRegistrations(IndicatorProvider.class).stream().map(r -> r.getProvider())
                .filter(p -> p.id().equals("capitalmarket")).findFirst().map(IndicatorProvider::indicators).orElse(List.of())
                .stream().collect(Collectors.toMap(Indicator::key, Indicator::value));
        var pol = svc.policy();
        double[] wallets = agents.stream().mapToDouble(a -> api.accounts().player(a.id).balance()).sorted().toArray();
        long tried = agents.stream().mapToLong(a -> a.needsTried).sum(), met = agents.stream().mapToLong(a -> a.needsMet).sum();
        var iron = get2(svc.prices(List.of("iron_ingot"))).get("iron_ingot");
        var wheat = get2(svc.prices(List.of("wheat"))).get("wheat");
        return new double[]{day, st.moneySupply(), st.activeMoneySupply(), st.treasuryBalance(), st.issuedToday(), st.destroyedToday(), st.treasuryInToday(), st.treasuryOutToday(),
                st.activePlayersToday(), ind.getOrDefault("price_index", 100.0), ind.getOrDefault("weekly_inflation", 0.0), pol == null ? 0 : pol.rate() * 100,
                ind.getOrDefault("mm_spread", 0.0), ind.getOrDefault("player_trade_share", 0.0), ind.getOrDefault("server_stock_value", 0.0),
                pol == null ? 0 : pol.newMoney(), pol == null ? 0 : pol.buyer(), pol == null ? 0 : pol.jobs(), gini(wallets),
                wallets.length == 0 ? 0 : wallets[wallets.length / 2], wallets.length == 0 ? 0 : wallets[(int) Math.min(wallets.length - 1, wallets.length * 0.9)],
                tried == 0 ? 0 : 1 - (double) met / tried, iron == null ? 0 : iron.ask(), wheat == null ? 0 : wheat.ask()};
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
