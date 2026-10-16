package com.patelheet.capitalengine.core;

import com.patelheet.capitalengine.api.AccountKind;
import com.patelheet.capitalengine.api.CapitalApi;
import com.patelheet.capitalengine.api.Failure;
import com.patelheet.capitalengine.api.Severity;
import com.patelheet.capitalengine.api.event.CapitalAlertEvent;
import com.patelheet.capitalengine.api.event.CapitalAuditEvent;
import com.patelheet.capitalengine.api.event.CapitalDayEvent;
import com.patelheet.capitalengine.api.event.CapitalPolicyLimitEvent;
import com.patelheet.capitalengine.api.event.CapitalTransactionEvent;
import com.patelheet.capitalengine.core.activity.ActivityTracker;
import com.patelheet.capitalengine.core.api.ApiImpl;
import com.patelheet.capitalengine.core.api.CurrencyImpl;
import com.patelheet.capitalengine.core.audit.AuditView;
import com.patelheet.capitalengine.core.audit.Auditor;
import com.patelheet.capitalengine.core.clock.ClockControl;
import com.patelheet.capitalengine.core.clock.EconomyClock;
import com.patelheet.capitalengine.core.command.CeCommand;
import com.patelheet.capitalengine.core.command.MoneyCommand;
import com.patelheet.capitalengine.core.command.PayCommand;
import com.patelheet.capitalengine.core.gui.MoneyMenu;
import com.patelheet.capitalengine.core.importer.Importer;
import com.patelheet.capitalengine.core.ledger.Acct;
import com.patelheet.capitalengine.core.ledger.Ledger;
import com.patelheet.capitalengine.core.ledger.PersistQueue;
import com.patelheet.capitalengine.core.papi.CapitalExpansion;
import com.patelheet.capitalengine.core.policy.Attribution;
import com.patelheet.capitalengine.core.policy.PolicyManager;
import com.patelheet.capitalengine.core.policy.PolicyManager.VaultMode;
import com.patelheet.capitalengine.core.storage.ClockRepo;
import com.patelheet.capitalengine.core.storage.Database;
import com.patelheet.capitalengine.core.storage.JournalRepo;
import com.patelheet.capitalengine.core.storage.Migrations;
import com.patelheet.capitalengine.core.storage.PlayerRepo;
import com.patelheet.capitalengine.core.vault.VaultHook;
import com.patelheet.capitalengine.core.vault.VaultOps;
import java.io.File;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/** Bootstrap and shutdown order for core (core/Plan.md §5.1). Everything else is wired here and nowhere else. */
public class CapitalEnginePlugin extends JavaPlugin implements Listener {
    private final String lockHolder = UUID.randomUUID().toString();
    private final Map<String, Long> limitLogged = new ConcurrentHashMap<>();
    private volatile CoreConfig cfg;
    private Messages msgs;
    private ExecutorService async;
    private Database db;
    private ClockRepo clockRepo;
    private PersistQueue queue;
    private Ledger ledger;
    private ActivityTracker tracker;
    private EconomyClock clock;
    private ApiImpl api;
    private Auditor auditor;
    private ClockControl clockControl;

    public CoreConfig settings() { return cfg; }
    public ApiImpl impl() { return api; }
    public Messages messages() { return msgs; }
    public Ledger ledger() { return ledger; }
    public ActivityTracker activity() { return tracker; }
    /** For the simulator; every method refuses to work unless {@code -Dcapitalengine.sim=true}. */
    public ClockControl clockControl() { return clockControl; }

    @Override
    public void onEnable() {
        try {
            start();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "CapitalEngine could not start, so no economy is registered. " + e.getMessage(), e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void start() throws Exception {
        FileUpdater.sync(this, "config.yml");   // new settings arrive on upgrade; the owner's values are never changed
        msgs = new Messages(this);
        CurrencyImpl currency = CoreConfig.currency(getConfig());
        cfg = CoreConfig.parse(getConfig(), currency);
        async = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "CapitalEngine-async"); t.setDaemon(true); return t; });

        // 2. database + migrations; failing here disables the plugin before any provider is registered
        db = openDatabase();
        Migrations.run(db);
        clockRepo = new ClockRepo(db);
        if (!clockRepo.tryLock(lockHolder, System.currentTimeMillis(), 30_000))
            throw new IllegalStateException("Another server is using this ledger database. Each server needs its own (if this one crashed, wait 30 seconds and start again).");
        JournalRepo journal = new JournalRepo(db);
        PlayerRepo playerRepo = new PlayerRepo(db);

        // 3. spill from a previous run goes in first, then everything is loaded into memory
        PolicyManager policy = new PolicyManager();
        policy.configure(cfg.defaults(), cfg.plugins());
        queue = new PersistQueue(db, journal, new File(getDataFolder(), "spill.jsonl").toPath());
        queue.replaySpill();
        ledger = new Ledger(policy, queue::add);
        tracker = new ActivityTracker(playerRepo, ledger, async, getLogger(), cfg);
        clock = new EconomyClock(clockRepo, ledger, getLogger(), cfg);
        load(journal);
        long today = clock.currentDay();
        ledger.setClock(today, clock.weekOf(today));
        journal.counters(today).forEach((plugin, c) -> ledger.restoreCounter(plugin, c));
        tracker.load(today);

        queue.onPause(ledger::setPaused);
        queue.onCommitted(rec -> Bukkit.getPluginManager().callEvent(new CapitalTransactionEvent(rec)));
        queue.onCritical(m -> { getLogger().severe(m); if (api != null) api.alert(this, Severity.CRITICAL, "ledger.storage", m); });
        ledger.onLimit(hit -> async.execute(() -> Bukkit.getPluginManager().callEvent(new CapitalPolicyLimitEvent(true, hit.plugin(), hit.kind(), hit.requested(), hit.remaining()))));
        api = new ApiImpl(ledger, queue, journal, currency, tracker, async);
        auditor = new Auditor(ledger, journal, async);
        clockControl = new ClockControl(clock, tracker);

        // 5. services: the API first, then the Vault providers
        getServer().getServicesManager().register(CapitalApi.class, api, this, ServicePriority.Normal);
        VaultHook.register(this, new VaultOps(ledger, currency, tracker, msgs), new Attribution(), getLogger());

        // 6. writer, events, clock, activity
        queue.start();
        MoneyMenu menu = new MoneyMenu(this, api, currency, msgs);
        var pm = getServer().getPluginManager();
        pm.registerEvents(this, this);
        pm.registerEvents(tracker, this);
        pm.registerEvents(menu, this);
        tracker.hooks(new ActivityTracker.Hooks() {
            @Override public void joined(Player p, boolean firstEver, boolean starterPaid) { if (firstEver && !starterPaid) starterCoins(p); }
        });
        getServer().getGlobalRegionScheduler().run(this, t -> clock.start());   // next tick: add-ons are enabled by then
        getServer().getGlobalRegionScheduler().runAtFixedRate(this, t -> { tracker.tick(); clock.tick(); }, 1200, 1200);
        getServer().getAsyncScheduler().runAtFixedRate(this, t -> {
            try { clockRepo.heartbeat(lockHolder, System.currentTimeMillis()); } catch (Exception e) { getLogger().warning("Ledger lock heartbeat failed: " + e.getMessage()); }
        }, 10, 10, TimeUnit.SECONDS);

        // 7. commands and placeholders
        AuditView view = new AuditView(ledger, journal, tracker, currency, msgs, async);
        command("money", new MoneyCommand(menu, ledger, tracker, currency, msgs));
        command("pay", new PayCommand(this, api, tracker, currency, msgs, () -> cfg));
        command("ce", new CeCommand(this, ledger, api, journal, tracker, currency, msgs, view, auditor, new Importer(this, ledger, tracker, currency, msgs), async));
        if (pm.getPlugin("PlaceholderAPI") != null) new CapitalExpansion(this, ledger, currency).register();

        getLogger().info("Ready: " + currency.format(ledger.stats().moneySupply()) + " in circulation.");
    }

    private <T extends org.bukkit.command.CommandExecutor & org.bukkit.command.TabCompleter> void command(String name, T handler) {
        PluginCommand c = getCommand(name);
        c.setExecutor(handler);
        c.setTabCompleter(handler);
    }

    private Database openDatabase() {
        var s = getConfig().getConfigurationSection("storage");
        String type = s == null ? "sqlite" : s.getString("type", "sqlite").toLowerCase(Locale.ROOT);
        String host = s == null ? "localhost" : s.getString("host", "localhost"), name = s == null ? "capitalengine" : s.getString("database", "capitalengine");
        int port = s == null ? 3306 : s.getInt("port", type.equals("postgres") ? 5432 : 3306), pool = s == null ? 0 : s.getInt("pool-size", 0);
        String user = s == null ? "" : s.getString("user", ""), pass = s == null ? "" : s.getString("password", "");
        return switch (type) {
            case "sqlite" -> new Database(Database.Dialect.SQLITE, "jdbc:sqlite:" + new File(getDataFolder(), s == null ? "ledger.db" : s.getString("file", "ledger.db")).getAbsolutePath(), null, null, pool > 0 ? pool : 4);
            case "mysql", "mariadb" -> new Database(Database.Dialect.MYSQL, "jdbc:mysql://" + host + ":" + port + "/" + name + "?useUnicode=true&characterEncoding=utf8", user, pass, pool > 0 ? pool : 8);
            case "postgres", "postgresql" -> new Database(Database.Dialect.POSTGRES, "jdbc:postgresql://" + host + ":" + port + "/" + name, user, pass, pool > 0 ? pool : 8);
            default -> throw new IllegalArgumentException("storage.type must be sqlite, mysql, mariadb or postgres (got '" + type + "')");
        };
    }

    private void load(JournalRepo journal) throws Exception {
        Map<UUID, Long> balances = journal.balances();
        for (var r : journal.accounts()) {
            if (r.kind() == AccountKind.MINT || r.kind() == AccountKind.TREASURY) continue;
            ledger.restore(new Acct(r.id(), r.kind(), r.plugin(), r.key(), r.name()), balances.getOrDefault(r.id(), 0L));
        }
        ledger.restoreMintAndTreasury(balances.getOrDefault(Ledger.MINT_ID, 0L), balances.getOrDefault(Ledger.TREASURY_ID, 0L));
        journal.references(ref -> ledger.restoreReference(ref.plugin(), ref.reference(), ref.txId()));
        ledger.restoreLastTx(journal.maxTx());
    }

    private void starterCoins(Player p) {
        long amount = cfg.starterCoins();
        if (amount <= 0) return;
        UUID id = p.getUniqueId();
        api.transaction(this, "core.starter", msgs.plain("memo.starter"))
                .serverPay(api.accounts().player(id), amount).reference("starter:" + id).commit().thenAccept(r -> {
                    if (r.success() || r.failure() == Failure.DUPLICATE_REFERENCE) tracker.markStarterPaid(id);
                    if (r.success()) p.getScheduler().run(this, t -> msgs.send(p, "starter", "amount", cfg.currency().format(amount)), null);
                });
    }

    // ---- core's own handlers (LOWEST: they run before add-ons see the Day event) --------------------------------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDay(CapitalDayEvent e) {
        if (e.catchUp()) return;   // counters were loaded for today at startup; the boundary was crossed while offline
        ledger.newDay(e.dayIndex(), clock.weekOf(e.dayIndex()));
        tracker.newDay(e.dayIndex());
        auditor.run().thenAcceptAsync(problems -> {
            Bukkit.getPluginManager().callEvent(new CapitalAuditEvent(problems.isEmpty(), problems));
            if (problems.isEmpty()) getLogger().info("Nightly self-check: the books balance.");
            else problems.forEach(p -> getLogger().severe("Nightly self-check: " + p));
            if (!problems.isEmpty()) api.alert(this, Severity.CRITICAL, "ledger.audit", problems.size() + " problem(s) found: " + problems.get(0));
        }, async);
    }

    @EventHandler
    public void onLimit(CapitalPolicyLimitEvent e) {
        long now = System.currentTimeMillis();
        Long last = limitLogged.put(e.plugin(), now);
        if (last != null && now - last < 3_600_000) { limitLogged.put(e.plugin(), last); return; }   // log at most once per plugin per hour
        getLogger().warning(e.plugin() + " hit its " + e.kind().name().toLowerCase(Locale.ROOT) + " limit (asked for " + cfg.currency().format(e.requested()) + ", " + cfg.currency().format(e.remaining()) + " left).");
    }

    @EventHandler
    public void onAlert(CapitalAlertEvent e) {
        Level l = switch (e.severity()) { case INFO -> Level.INFO; case WARN -> Level.WARNING; case CRITICAL -> Level.SEVERE; };
        getLogger().log(l, "[" + e.source() + "] " + e.message());
    }

    // ---- settings -----------------------------------------------------------------------------------------------

    /** Re-reads config and messages. ponytail: currency names and decimals are fixed until restart (every Vault adapter holds them). */
    public void reloadSettings() {
        reloadConfig();
        cfg = CoreConfig.parse(getConfig(), cfg.currency());
        ledger.policy().configure(cfg.defaults(), cfg.plugins());
        tracker.config(cfg);
        clock.config(cfg);
        msgs.reload();
    }

    /** Edits {@code plugins.<name>.<key>} in config.yml and applies it live. Returns an error message, or null. */
    public String setPolicy(String name, String key, String value) {
        String stored = value;
        try {
            if (key.equals("vault-mode")) stored = VaultMode.valueOf(value.toUpperCase(Locale.ROOT)).name().toLowerCase(Locale.ROOT);
            else if (!value.equalsIgnoreCase("unlimited")) cfg.currency().parse(value);
        } catch (IllegalArgumentException e) {
            return msgs.plain("ce.bad-value", "key", key);
        }
        getConfig().set("plugins." + name + "." + key, stored);
        saveConfig();
        reloadSettings();
        return null;
    }

    @Override
    public void onDisable() {
        if (ledger == null) { if (async != null) async.shutdownNow(); return; }
        ledger.setPaused(true);   // 1. stop accepting mutations
        getServer().getGlobalRegionScheduler().cancelTasks(this);
        getServer().getAsyncScheduler().cancelTasks(this);
        try {
            tracker.saveNow();
            queue.shutdown(10_000);   // 2. drain, spill whatever is left
            clockRepo.release(lockHolder);
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Problem while shutting the ledger down: " + e.getMessage(), e);
        }
        getServer().getServicesManager().unregisterAll(this);
        async.shutdown();
        db.close();   // 3. close the pool
    }
}
