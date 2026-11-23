package com.patelheet.capitalengine.dashboard;

import static org.junit.jupiter.api.Assertions.*;

import com.patelheet.capitalengine.api.CapitalApi;
import com.patelheet.capitalengine.api.Severity;
import com.patelheet.capitalengine.api.event.CapitalAuditEvent;
import com.patelheet.capitalengine.api.event.CapitalDayEvent;
import com.patelheet.capitalengine.api.event.CapitalPolicyLimitEvent;
import com.patelheet.capitalengine.api.service.Indicator;
import com.patelheet.capitalengine.api.service.IndicatorProvider;
import com.patelheet.capitalengine.api.service.Unit;
import com.patelheet.capitalengine.core.CapitalEnginePlugin;
import com.patelheet.capitalengine.dashboard.storage.Store;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.Event;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** The real CapitalEngine plus CapitalDashboard and a fake add-on: snapshots, the printer rule, every alert source, /cdash and the web page. */
class DashboardFlowTest {
    ServerMock server;
    CapitalApi api;
    CapitalDashboardPlugin dash;
    Plugin jobs;

    static <T> T await(CompletableFuture<T> f) throws Exception { return f.get(10, TimeUnit.SECONDS); }

    @BeforeEach void start() throws Exception {
        server = MockBukkit.mock();
        // both jars have a plugin.yml on the test classpath, so describe each plugin by hand
        MockBukkit.loadWith(CapitalEnginePlugin.class, descriptor("CapitalEngine"));
        api = server.getServicesManager().load(CapitalApi.class);
        Plugin addon = MockBukkit.createMockPlugin("FakeAddon");
        server.getServicesManager().register(IndicatorProvider.class, provider("fakeaddon", "Fake add-on", 0, new Indicator("price_index", "Prices", 1.05, Unit.INDEX),
                new Indicator("pot", "Prize pot", 12_345, Unit.COINS)), addon, ServicePriority.Normal);
        server.getServicesManager().register(IndicatorProvider.class, provider("slowpoke", "Slow", 500), addon, ServicePriority.Low);
        server.getServicesManager().register(IndicatorProvider.class, provider("broken", "Broken", -1), addon, ServicePriority.Low);
        dash = MockBukkit.loadWith(CapitalDashboardPlugin.class, descriptor("CapitalDashboard"));
        jobs = MockBukkit.createMockPlugin("Jobs");
        server.getScheduler().performTicks(2);
    }

    @AfterEach void stop() { MockBukkit.unmock(); }

    static PluginDescriptionFile descriptor(String name) throws Exception {
        for (var url : java.util.Collections.list(DashboardFlowTest.class.getClassLoader().getResources("plugin.yml"))) {
            try (var in = url.openStream()) { var d = new PluginDescriptionFile(in); if (d.getName().equals(name)) return d; }
        }
        throw new IllegalStateException("no plugin.yml for " + name);
    }

    /** delayMs: 0 answers at once, more sleeps that long, negative throws. */
    static IndicatorProvider provider(String id, String name, long delayMs, Indicator... ind) {
        return new IndicatorProvider() {
            public String id() { return id; }
            public String displayName() { return name; }
            public List<Indicator> indicators() {
                if (delayMs < 0) throw new IllegalStateException("boom");
                if (delayMs > 0) try { Thread.sleep(delayMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return List.of(ind);
            }
        };
    }

    /** Async events must not be fired from the main thread. */
    void fireAsync(Event e) throws Exception { await(CompletableFuture.runAsync(() -> server.getPluginManager().callEvent(e))); }

    /** The worker runs tasks in order, so a snapshot after the events have been handed over is a barrier. */
    void drain() throws Exception { await(dash.snapshotNow()); server.getScheduler().performTicks(1); }

    List<Store.Alert> alerts(String code) { return dash.store().alerts(false, 100).stream().filter(a -> a.code().equals(code)).toList(); }

    static String plain(Component c) { return PlainTextComponentSerializer.plainText().serialize(c); }

    static List<String> messages(PlayerMock p) {
        List<String> out = new ArrayList<>();
        Component c;
        while ((c = p.nextComponentMessage()) != null) out.add(plain(c));
        return out;
    }

    @Test void snapshotHasCoreAndProviderNumbersAndSkipsBadProviders() throws Exception {
        var snap = await(dash.snapshotNow());
        assertEquals(api.stats().moneySupply(), snap.value("core.moneySupply"));
        assertEquals(1.05, snap.value("fakeaddon.price_index"));
        assertEquals(12_345, snap.value("fakeaddon.pot"));
        assertEquals("Fake add-on", snap.providers().get("fakeaddon"));
        assertFalse(snap.providers().containsKey("slowpoke"), "over 100 ms: skipped this time");
        assertFalse(snap.providers().containsKey("broken"), "a provider that throws never breaks the snapshot");
        assertTrue(dash.store().catalogue().stream().anyMatch(m -> m.key().equals("fakeaddon.price_index") && m.label().equals("Prices") && m.provider().equals("fakeaddon")));
        assertEquals("Money in the world", dash.store().catalogue().stream().filter(m -> m.key().equals("core.moneySupply")).findFirst().orElseThrow().label());
    }

    @Test void aPluginPrintingMoneyTriggersThePrinterRuleWithinOneSnapshot() throws Exception {
        PlayerMock staff = server.addPlayer("Owner");
        staff.setOp(true);
        server.getScheduler().performTicks(2);
        messages(staff);   // starter coins and the like
        // a Jobs-style plugin creating 5,000 coins (the default minimum is 1,000 coins)
        assertTrue(await(api.issue(jobs, api.accounts().player(UUID.randomUUID()), 500_000, "jobs.pay", "Job pay")).success());
        drain();
        var printer = alerts("dash.plugin_printer");
        assertEquals(1, printer.size());
        assertEquals("Jobs", printer.get(0).source());
        assertEquals(Severity.WARN, printer.get(0).severity());
        assertTrue(printer.get(0).message().startsWith("Jobs created 5,000 coins"), printer.get(0).message());
        assertEquals(1, alerts("dash.unknown_plugin").stream().filter(a -> a.source().equals("Jobs")).count());
        assertTrue(messages(staff).stream().anyMatch(m -> m.contains("⚠ [Economy] Jobs: Jobs created")), "staff get one chat line for a WARN");

        drain();   // still printing: the same alert counts up instead of a new one
        assertEquals(1, alerts("dash.plugin_printer").size());
        assertEquals(2, alerts("dash.plugin_printer").get(0).hits());
        assertEquals(1, alerts("dash.unknown_plugin").stream().filter(a -> a.source().equals("Jobs")).count(), "a new plugin is reported once");
        assertTrue(messages(staff).isEmpty(), "a repeat is not sent again");
    }

    @Test void alertsFromEveryEventTypeAreStored() throws Exception {
        api.alert(jobs, Severity.WARN, "jobs.flood", "Sam sold 3,200 diamonds today");
        api.alert(jobs, Severity.WARN, "jobs.flood", "Sam sold 3,300 diamonds today");
        fireAsync(new CapitalAuditEvent(false, List.of("account x is off by 5")));
        api.alert(server.getPluginManager().getPlugin("CapitalEngine"), Severity.CRITICAL, "ledger.audit", "core's own message");   // core raises this too
        fireAsync(new CapitalPolicyLimitEvent(true, "Jobs", CapitalPolicyLimitEvent.LimitKind.ISSUE, 10_000, 0));
        fireAsync(new CapitalPolicyLimitEvent(true, "Jobs", CapitalPolicyLimitEvent.LimitKind.ISSUE, 10_000, 0));
        drain();

        var flood = alerts("jobs.flood");
        assertEquals(1, flood.size());
        assertEquals(2, flood.get(0).hits());
        assertEquals("Sam sold 3,300 diamonds today", flood.get(0).message());
        var audit = alerts("ledger.audit");
        assertEquals(1, audit.size(), "the audit event and core's own alert merge");
        assertEquals(Severity.CRITICAL, audit.get(0).severity());
        assertEquals("CapitalEngine", audit.get(0).source());
        var limit = alerts("core.policy_limit");
        assertEquals(1, limit.size(), "deduplicated per plugin per hour");
        assertEquals(Severity.WARN, limit.get(0).severity());
        assertTrue(limit.get(0).message().contains("Jobs reached its daily limit"));
        fireAsync(new CapitalAuditEvent(true, List.of()));
        drain();
        assertEquals(1, alerts("ledger.audit").size(), "a clean audit raises nothing");
    }

    @Test void dayTotalsAreStoredButNotForCatchUpDays() throws Exception {
        var ended = api.stats();
        long day = ended.dayIndex();
        server.getPluginManager().callEvent(new CapitalDayEvent(day + 1, LocalDate.ofEpochDay(day + 1), true, ended));
        drain();
        assertTrue(dash.store().daily("core.moneySupply", day).isEmpty(), "catch-up days carry no real totals");
        server.getPluginManager().callEvent(new CapitalDayEvent(day + 1, LocalDate.ofEpochDay(day + 1), false, ended));
        drain();
        assertEquals((double) ended.moneySupply(), dash.store().daily("core.moneySupply", day).orElseThrow(), "stored under the day that ended");
    }

    @Test void cdashShowsTheSummaryAlertsAndPlugins() throws Exception {
        PlayerMock owner = server.addPlayer("Owner");
        owner.setOp(true);
        assertTrue(await(api.issue(jobs, api.accounts().player(UUID.randomUUID()), 300_000, "jobs.pay", "Job pay")).success());
        drain();
        messages(owner);

        owner.performCommand("cdash");
        drain();
        var lines = messages(owner);
        assertTrue(lines.contains("━━ Economy today ━━"), lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("Money in the world: ")), lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("Most new money from: Jobs 3,000 coins")), lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("Fake add-on: Prices 1.05")), lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("Alerts: ") && l.contains("open")), lines.toString());

        owner.performCommand("cdash plugins");
        drain();
        assertTrue(messages(owner).stream().anyMatch(l -> l.startsWith("Jobs: created 3,000 coins")));

        owner.performCommand("cdash alerts");
        drain();
        var al = messages(owner);
        assertTrue(al.stream().anyMatch(l -> l.contains("Jobs created 3,000 coins") && l.contains("/cdash alerts ack")), "every clickable Ack also says what to type: " + al);
        owner.performCommand("cdash alerts ack all");
        drain();
        assertTrue(messages(owner).stream().anyMatch(l -> l.contains("Acknowledged")));
        assertEquals(0, dash.store().openAlerts());

        PlayerMock kid = server.addPlayer("Kid");
        kid.performCommand("cdash");
        drain();
        assertTrue(messages(kid).stream().anyMatch(l -> l.contains("permission")));
    }

    @Test void webIsOffAndLocalByDefaultAndServesTheSummaryWithASession() throws Exception {
        assertFalse(dash.getConfig().getBoolean("web.enabled"));
        assertEquals("127.0.0.1", dash.getConfig().getString("web.bind"));
        assertEquals(-1, dash.webPort(), "no web server until the owner turns it on");
        assertNull(dash.loginLink("console"));

        dash.getConfig().set("web.enabled", true);
        dash.getConfig().set("web.port", 0);
        dash.applyConfig();
        await(dash.snapshotNow());
        int port = dash.webPort();
        assertTrue(port > 0);
        String link = dash.loginLink("console");
        String code = link.substring(link.indexOf("code=") + 5);
        var http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        String base = "http://127.0.0.1:" + port;
        assertEquals(401, http.send(HttpRequest.newBuilder(URI.create(base + "/api/summary")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        var login = http.send(HttpRequest.newBuilder(URI.create(base + "/login?code=" + code)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(302, login.statusCode());
        String cookie = login.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
        var summary = http.send(HttpRequest.newBuilder(URI.create(base + "/api/summary")).header("Cookie", cookie).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, summary.statusCode());
        assertTrue(summary.body().contains("\"moneySupply\":"), summary.body());
        assertTrue(summary.body().contains("\"name\":\"Fake add-on\""), "add-on sections come from IndicatorProvider registrations alone");
    }
}
