package com.patelheet.capitalengine.dashboard;

import com.patelheet.capitalengine.api.CapitalApi;
import com.patelheet.capitalengine.api.Severity;
import com.patelheet.capitalengine.api.Stats;
import com.patelheet.capitalengine.api.event.CapitalAlertEvent;
import com.patelheet.capitalengine.api.event.CapitalAuditEvent;
import com.patelheet.capitalengine.api.event.CapitalDayEvent;
import com.patelheet.capitalengine.api.event.CapitalPolicyLimitEvent;
import com.patelheet.capitalengine.api.service.IndicatorProvider;
import com.patelheet.capitalengine.dashboard.alerts.AlertInbox;
import com.patelheet.capitalengine.dashboard.alerts.BuiltInRules;
import com.patelheet.capitalengine.dashboard.snapshot.Downsampler;
import com.patelheet.capitalengine.dashboard.snapshot.Snapshot;
import com.patelheet.capitalengine.dashboard.storage.Store;
import com.patelheet.capitalengine.dashboard.web.Auth;
import com.patelheet.capitalengine.dashboard.web.JsonApi;
import com.patelheet.capitalengine.dashboard.web.WebServer;
import java.io.File;
import java.net.InetAddress;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Wires CapitalDashboard together. Read-only: it records {@code stats()} and every {@code IndicatorProvider}, collects
 * alerts, and shows them in game and on a local web page. All database work runs on one worker thread or the web threads.
 */
public class CapitalDashboardPlugin extends JavaPlugin implements Listener {
    private CapitalApi api;
    private Messages msg;
    private Store store;
    private AlertInbox inbox;
    private Notifier notifier;
    private Auth auth;
    private JsonApi data;
    private WebServer web;
    private ExecutorService worker, providerPool;
    private volatile long snapshotMs = 5 * 60_000L;
    private volatile Snapshot latest;
    private volatile BuiltInRules.Thresholds thresholds;
    private volatile String webBase = "";
    private final Set<String> known = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> slowLogged = new ConcurrentHashMap<>();

    @Override public void onEnable() {
        api = getServer().getServicesManager().load(CapitalApi.class);
        if (api == null) { getLogger().severe("CapitalEngine's API isn't available."); getServer().getPluginManager().disablePlugin(this); return; }
        FileUpdater.sync(this, "config.yml");   // new settings arrive on upgrade; the owner's values are never changed
        reloadConfig();
        msg = new Messages(this);
        try {
            store = openStore();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Could not open the dashboard database", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        worker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "CapitalDashboard-worker"); t.setDaemon(true); return t; });
        providerPool = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "CapitalDashboard-indicators"); t.setDaemon(true); return t; });
        known.addAll(store.knownPlugins());
        notifier = new Notifier(this, msg);
        inbox = new AlertInbox(store, System::currentTimeMillis, 60 * 60_000L, notifier::deliver);
        auth = new Auth(store, System::currentTimeMillis);
        data = new JsonApi(store, () -> latest, api.currency(), () -> thresholds, System::currentTimeMillis);
        applyConfig();
        getServer().getPluginManager().registerEvents(this, this);
        var cmd = new CdashCommand(this, api, data, store, msg);
        getCommand("cdash").setExecutor(cmd);
        getCommand("cdash").setTabCompleter(cmd);
        getServer().getGlobalRegionScheduler().run(this, t -> snapshotNow());   // next tick: every add-on has registered its indicators
        // checked every minute rather than rescheduled, so /cdash reload can change snapshot-minutes without cancelling anything
        getServer().getAsyncScheduler().runAtFixedRate(this, t -> { var l = latest; if (l == null || System.currentTimeMillis() - l.ts() >= snapshotMs - 5_000) snapshotNow(); }, 1, 1, TimeUnit.MINUTES);
    }

    /** Same {@code storage:} keys as CapitalEngine; MySQL and PostgreSQL tables get the {@code prefix} so a database can be shared. */
    private Store openStore() throws java.sql.SQLException {
        var s = getConfig().getConfigurationSection("storage");
        String type = s == null ? "sqlite" : s.getString("type", "sqlite").toLowerCase(Locale.ROOT), host = s == null ? "localhost" : s.getString("host", "localhost");
        String name = s == null ? "capitaldashboard" : s.getString("database", "capitaldashboard"), user = s == null ? "" : s.getString("user", ""), pass = s == null ? "" : s.getString("password", "");
        int port = s == null ? 3306 : s.getInt("port", type.startsWith("postgres") ? 5432 : 3306), pool = s == null ? 0 : s.getInt("pool-size", 0);
        String prefix = s == null ? "" : s.getString("prefix", type.equals("sqlite") ? "" : "dash_");
        return switch (type) {
            case "sqlite" -> new Store(Store.Dialect.SQLITE, "jdbc:sqlite:" + new File(getDataFolder(), s == null ? "dashboard.db" : s.getString("file", "dashboard.db")).getAbsolutePath(), null, null, pool > 0 ? pool : 4, prefix);
            case "mysql", "mariadb" -> new Store(Store.Dialect.MYSQL, "jdbc:mysql://" + host + ":" + port + "/" + name + "?useUnicode=true&characterEncoding=utf8", user, pass, pool > 0 ? pool : 4, prefix);
            case "postgres", "postgresql" -> new Store(Store.Dialect.POSTGRES, "jdbc:postgresql://" + host + ":" + port + "/" + name, user, pass, pool > 0 ? pool : 4, prefix);
            default -> throw new IllegalArgumentException("storage.type must be sqlite, mysql, mariadb or postgres (got '" + type + "')");
        };
    }

    /** Reads everything except storage (which needs a restart): thresholds, notifications, the snapshot timer and the web server. */
    void applyConfig() {
        var c = getConfig();
        thresholds = new BuiltInRules.Thresholds(c.getDouble("alerts.rules.issuance-spike-x", 3.0), c.getDouble("alerts.rules.printer-share", 0.5),
                coins(c.getString("alerts.rules.printer-min-coins", "1000")), c.getDouble("alerts.rules.supply-drop", 0.20));
        inbox.dedupeMs(Math.max(0, c.getLong("alerts.dedupe-minutes", 60)) * 60_000L);
        notifier.configure(severity(c.getString("alerts.staff-min-severity")), c.getString("alerts.discord.webhook", ""), severity(c.getString("alerts.discord.min-severity")));

        snapshotMs = Math.max(1, c.getLong("snapshot-minutes", 5)) * 60_000L;

        if (web != null) { web.stop(); web = null; }
        boolean webOn = c.getBoolean("web.enabled", false), metricsOn = c.getBoolean("metrics.enabled", false);
        String token = c.getString("metrics.token", "");
        if (metricsOn && (token == null || token.isBlank())) {
            byte[] b = new byte[24];
            new SecureRandom().nextBytes(b);
            token = HexFormat.of().formatHex(b);
            c.set("metrics.token", token);
            saveConfig();
            getLogger().info("Generated a Prometheus token in config.yml (metrics.token).");
        }
        String bind = c.getString("web.bind", "127.0.0.1");
        int port = c.getInt("web.port", 8765);
        String pub = c.getString("web.public-url", "");
        webBase = pub != null && !pub.isBlank() ? pub.replaceAll("/+$", "") : "http://" + (bind.equals("0.0.0.0") || bind.equals("::") ? "localhost" : bind) + ":" + port;
        if (!webOn && !metricsOn) return;
        try {
            if (!InetAddress.getByName(bind).isLoopbackAddress())
                getLogger().warning("Dashboard is reachable from the internet. Put it behind HTTPS (reverse proxy) or keep it local.");
            web = new WebServer(auth, data, this::ack, webOn, metricsOn ? token : null, api.currency().decimals(), store::openAlerts, getLogger());
            web.start(bind, port);
            getLogger().info("Dashboard web server on " + bind + ":" + web.port() + (webOn ? "" : " (/metrics only)") + ".");
        } catch (Exception e) {
            getLogger().severe("Could not start the dashboard web server on " + bind + ":" + port + " (" + e.getMessage() + "). Everything in game still works.");
            if (web != null) web.stop();
            web = null;
        }
    }

    private long coins(String text) {
        try { return api.currency().parse(text); } catch (IllegalArgumentException e) { return api.currency().parse("1000"); }
    }

    private static Severity severity(String s) {
        try { return Severity.valueOf(s == null ? "WARN" : s.trim().toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException e) { return Severity.WARN; }
    }

    void reloadAll() {
        FileUpdater.sync(this, "config.yml");
        reloadConfig();
        msg.reload();
        applyConfig();
    }

    // ---- snapshots and rules (worker thread) ---------------------------------------------------------------------

    /** Takes a snapshot now on the worker thread, stores it and runs the built-in rules on the same reading. */
    public CompletableFuture<Snapshot> snapshotNow() {
        try { return CompletableFuture.supplyAsync(this::takeSnapshot, worker).whenComplete((s, e) -> { if (e != null) getLogger().log(Level.WARNING, "Snapshot failed: " + e.getMessage(), e); }); }
        catch (RejectedExecutionException shuttingDown) { return CompletableFuture.failedFuture(shuttingDown); }
    }

    private Snapshot takeSnapshot() {
        long now = System.currentTimeMillis();
        Stats s = api.stats();
        Snapshot snap = Snapshot.of(now, s, readProviders(), f -> msg.plain("label-" + f));
        store.saveSnapshot(snap);
        latest = snap;
        List<Double> issued = new ArrayList<>();
        for (double[] p : store.dailySeries("core.issuedToday", s.dayIndex() - 7)) if (p[0] < s.dayIndex()) issued.add(p[1]);
        var history = new BuiltInRules.History(issued, store.daily("core.moneySupply", s.dayIndex() - 7), store.zeroSince("core.treasuryBalance", now, Downsampler.D1));
        for (var f : BuiltInRules.evaluate(s, history, thresholds, known, api.currency()::format))
            inbox.raise(f.source(), f.severity(), f.code(), msg.plain("rule-" + f.code().substring("dash.".length()), f.kv()));
        known.addAll(s.flowsToday().keySet());
        return snap;
    }

    /** Every registered IndicatorProvider; one that throws or takes over 100 ms is skipped this time (logged at most hourly). */
    private List<Snapshot.Reading> readProviders() {
        List<Snapshot.Reading> out = new ArrayList<>();
        Set<String> ids = new java.util.HashSet<>();
        for (var reg : getServer().getServicesManager().getRegistrations(IndicatorProvider.class)) {
            IndicatorProvider p = reg.getProvider();
            var f = CompletableFuture.supplyAsync(() -> new Snapshot.Reading(p.id(), p.displayName(), List.copyOf(p.indicators())), providerPool);
            try {
                Snapshot.Reading r = f.get(100, TimeUnit.MILLISECONDS);
                if (r.id() == null || r.id().isBlank() || r.id().equals(Snapshot.CORE) || !ids.add(r.id())) throw new IllegalStateException("its id '" + r.id() + "' is empty, reserved or already used");
                out.add(r);
            } catch (Exception e) {
                f.cancel(true);
                String who = reg.getPlugin().getName();
                long now = System.currentTimeMillis();
                Long last = slowLogged.get(who);
                if (last == null || now - last > 3_600_000L) {
                    slowLogged.put(who, now);
                    String why = e instanceof java.util.concurrent.TimeoutException ? "took longer than 100 ms" : String.valueOf(e.getCause() != null ? e.getCause() : e.getMessage());
                    getLogger().warning("Skipped " + who + "'s dashboard numbers this time: " + why + ".");
                }
            }
        }
        return out;
    }

    // ---- events (handed to the worker so no database work happens on the calling thread) --------------------------

    private void async(Runnable r) {
        try {
            worker.execute(() -> { try { r.run(); } catch (RuntimeException e) { getLogger().log(Level.WARNING, "Dashboard task failed: " + e.getMessage(), e); } });
        } catch (RejectedExecutionException shuttingDown) { /* plugin is stopping */ }
    }

    @EventHandler public void onAlert(CapitalAlertEvent e) { async(() -> inbox.raise(e.source(), e.severity(), e.code(), e.message())); }

    /** Core also raises {@code ledger.audit} itself when the check fails; same source and code, so the two merge into one alert. */
    @EventHandler public void onAudit(CapitalAuditEvent e) {
        if (e.ok()) return;
        async(() -> inbox.raise("CapitalEngine", Severity.CRITICAL, "ledger.audit",
                msg.plain("audit-failed", "count", e.problems().size(), "first", e.problems().isEmpty() ? "?" : e.problems().get(0))));
    }

    @EventHandler public void onLimit(CapitalPolicyLimitEvent e) {
        async(() -> inbox.raise(e.plugin(), Severity.WARN, "core.policy_limit", msg.plain("limit-" + e.kind().name(), "plugin", e.plugin(),
                "requested", api.currency().format(e.requested()), "remaining", api.currency().format(e.remaining()))));
    }

    /** Stores the day that just ended (not on catch-up: core doesn't reset its counters for days missed while offline), then tidies up. */
    @EventHandler public void onDay(CapitalDayEvent e) {
        Stats ended = e.endedDay();
        boolean catchUp = e.catchUp();
        async(() -> {
            if (!catchUp && ended != null) {
                Map<String, Double> values = new LinkedHashMap<>();
                for (var m : Snapshot.of(0, ended, List.of(), f -> f).metrics()) values.put(m.key(), m.value());
                store.saveDaily(ended.dayIndex(), values);
            }
            long now = System.currentTimeMillis();
            store.downsample(now);
            store.deleteExpiredSessions(now);
        });
    }

    // ---- for the command and the web page ------------------------------------------------------------------------

    int ack(Long id, String by) { return store.ack(id, by, System.currentTimeMillis()); }

    /** A one-time login link, or null when the web page is off. */
    String loginLink(String player) { return web == null || !getConfig().getBoolean("web.enabled") ? null : webBase + "/login?code=" + auth.newCode(player); }

    ExecutorService worker() { return worker; }

    /** For tests. */
    public Snapshot latest() { return latest; }

    /** For tests. */
    public Store store() { return store; }

    /** For tests: the web server's port, or -1 when it isn't running. */
    public int webPort() { return web == null ? -1 : web.port(); }

    @Override public void onDisable() {
        if (web != null) web.stop();
        web = null;
        getServer().getAsyncScheduler().cancelTasks(this);
        if (worker != null) {
            worker.shutdown();
            try { worker.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        if (providerPool != null) providerPool.shutdownNow();
        if (store != null) store.close();
    }
}
