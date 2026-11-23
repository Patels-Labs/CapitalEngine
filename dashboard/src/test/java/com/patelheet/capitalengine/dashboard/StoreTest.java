package com.patelheet.capitalengine.dashboard;

import static org.junit.jupiter.api.Assertions.*;

import com.patelheet.capitalengine.api.PluginFlow;
import com.patelheet.capitalengine.api.Severity;
import com.patelheet.capitalengine.dashboard.alerts.AlertInbox;
import com.patelheet.capitalengine.dashboard.snapshot.Downsampler;
import com.patelheet.capitalengine.dashboard.snapshot.Snapshot;
import com.patelheet.capitalengine.dashboard.storage.Store;
import com.patelheet.capitalengine.dashboard.storage.Store.Dialect;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every Store statement against SQLite always, and against real MySQL/MariaDB and PostgreSQL servers when pointed at a THROWAWAY
 * database whose name contains "capitalengine_test" (the dashboard's own tables are dropped first):
 *   CAPITAL_TEST_POSTGRES="jdbc:postgresql://localhost:5432/capitalengine_test|user|pass"
 *   CAPITAL_TEST_MYSQL="jdbc:mysql://localhost:3306/capitalengine_test|user|pass"
 */
class StoreTest {
    @TempDir Path dir;

    static Store sqlite(Path dir) throws Exception { return new Store(Dialect.SQLITE, "jdbc:sqlite:" + dir.resolve("d.db"), null, null, 4, ""); }

    @Test void sqliteStore() throws Exception { try (Store st = sqlite(dir)) { exercise(st); } }

    @Test @EnabledIfEnvironmentVariable(named = "CAPITAL_TEST_POSTGRES", matches = ".+")
    void postgres() throws Exception { remote(Dialect.POSTGRES, System.getenv("CAPITAL_TEST_POSTGRES")); }

    @Test @EnabledIfEnvironmentVariable(named = "CAPITAL_TEST_MYSQL", matches = ".+")
    void mysql() throws Exception { remote(Dialect.MYSQL, System.getenv("CAPITAL_TEST_MYSQL")); }

    private void remote(Dialect d, String spec) throws Exception {
        String[] p = spec.split("\\|", -1);
        assertTrue(p[0].contains("capitalengine_test"), "refusing to touch a database that isn't named capitalengine_test");
        try (Connection c = DriverManager.getConnection(p[0], p.length > 1 ? p[1] : "", p.length > 2 ? p[2] : ""); Statement s = c.createStatement()) {
            for (String t : List.of("metrics", "snapshots", "daily", "alerts", "sessions", "schema_version")) s.execute("DROP TABLE IF EXISTS tdb_" + t);
        }
        try (Store st = new Store(d, p[0], p.length > 1 ? p[1] : "", p.length > 2 ? p[2] : "", 2, "tdb_")) {
            exercise(st);
            try (Store again = new Store(d, p[0], p.length > 1 ? p[1] : "", p.length > 2 ? p[2] : "", 2, "tdb_")) { assertEquals(1, again.openAlerts()); }
        }
    }

    private void exercise(Store st) {
        long now = 400 * Downsampler.D1 + 12 * Downsampler.H1;   // a fixed "now", well past the epoch
        var stats = PureLogicTest.stats(1_000, 0, 50, Map.of("Jobs", new PluginFlow(50, 0, 0, 0, 0)));

        // snapshots: points and the catalogue; a repeated timestamp is ignored rather than failing
        var snap = Snapshot.of(now, stats, List.of(), f -> "label " + f);
        st.saveSnapshot(snap);
        st.saveSnapshot(snap);
        assertEquals(1, st.series("core.moneySupply", 0).size());
        assertEquals(Set.of("Jobs"), st.knownPlugins());
        assertTrue(st.catalogue().stream().anyMatch(m -> m.key().equals("core.flow.Jobs.net") && m.provider().equals("core")));

        // downsampling: 5-minute points older than 7 days become one hourly average; recent ones stay
        long old = Downsampler.align(now - 8 * Downsampler.D1, Downsampler.H1);
        for (int i = 0; i < 12; i++) st.saveSnapshot(Snapshot.of(old + i * Downsampler.M5, PureLogicTest.stats(100 + i, 0, 0, Map.of()), List.of(), f -> f));
        assertTrue(st.downsample(now) > 0);
        var series = st.series("core.moneySupply", 0);
        assertEquals(2, series.size(), "one hourly point plus today's 5-minute point");
        assertEquals(old, series.get(0).ts());
        assertEquals(105.5, series.get(0).value(), 1e-9);
        assertEquals(0, st.downsample(now), "nothing left to move");
        // ...and hourly points older than 90 days become daily
        st.saveSnapshot(Snapshot.of(now - 100 * Downsampler.D1, PureLogicTest.stats(7, 0, 0, Map.of()), List.of(), f -> f));
        st.downsample(now);   // m5 → h1
        st.downsample(now);   // h1 → d1
        assertEquals(Downsampler.align(now - 100 * Downsampler.D1, Downsampler.D1), st.series("core.moneySupply", 0).get(0).ts());

        // zeroSince: needs a full span of points, all zero
        assertFalse(st.zeroSince("core.treasuryBalance", now, Downsampler.D1), "no 5-minute point is 24 hours old yet (older ones were moved to hourly)");
        st.saveSnapshot(Snapshot.of(now - Downsampler.D1 - 1, PureLogicTest.stats(1, 0, 0, Map.of()), List.of(), f -> f));
        assertTrue(st.zeroSince("core.treasuryBalance", now, Downsampler.D1));
        st.saveSnapshot(Snapshot.of(now - 1, PureLogicTest.stats(1, 9, 0, Map.of()), List.of(), f -> f));
        assertFalse(st.zeroSince("core.treasuryBalance", now, Downsampler.D1));

        // daily totals: first write wins, flows sum over a range
        st.saveDaily(10, Map.of("core.moneySupply", 900.0, "core.flow.Jobs.issued", 40.0));
        st.saveDaily(10, Map.of("core.moneySupply", 1.0));
        st.saveDaily(11, Map.of("core.flow.Jobs.issued", 2.0));
        assertEquals(900.0, st.daily("core.moneySupply", 10).orElseThrow());
        assertTrue(st.daily("core.moneySupply", 9).isEmpty());
        assertEquals(Map.of("core.flow.Jobs.issued", 42.0), st.flowSums(10, 11));
        assertEquals(2, st.dailySeries("core.flow.Jobs.issued", 0).size());

        // alerts: dedupe within the window, then a new one; ack one and all
        AtomicLong clock = new AtomicLong(now);
        List<Store.Alert> delivered = new ArrayList<>();
        AlertInbox inbox = new AlertInbox(st, clock::get, 60 * 60_000L, delivered::add);
        assertNotNull(inbox.raise("Jobs", Severity.WARN, "dash.plugin_printer", "first"));
        clock.addAndGet(30 * 60_000L);
        assertNull(inbox.raise("Jobs", Severity.WARN, "dash.plugin_printer", "second"), "same source and code within an hour only counts up");
        assertNotNull(inbox.raise("Shop", Severity.WARN, "dash.plugin_printer", "other plugin"));
        assertEquals(2, delivered.size());
        Store.Alert a = st.alert(delivered.get(0).id());
        assertEquals(2, a.hits());
        assertEquals("second", a.message());
        clock.addAndGet(61 * 60_000L);
        assertNotNull(inbox.raise("Jobs", Severity.WARN, "dash.plugin_printer", "much later"));
        assertEquals(3, st.openAlerts());
        assertEquals(1, st.ack(a.id(), "console", now));
        assertEquals(0, st.ack(a.id(), "console", now), "already acknowledged");
        assertEquals(2, st.alerts(true, 10).size());
        assertEquals(3, st.alerts(false, 10).size(), "acknowledged alerts stay in the history");
        assertEquals(1, st.ack(delivered.get(1).id(), "console", now));
        assertEquals(1, st.openAlerts());

        // sessions: only live ones count
        st.saveSession("h1", "console", now, now + 1000, "127.0.0.1");
        assertEquals("console", st.session("h1", now));
        assertNull(st.session("h1", now + 1000));
        assertNull(st.session("nope", now));
        assertEquals(1, st.deleteExpiredSessions(now + 1000));
    }
}
