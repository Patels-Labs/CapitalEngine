package com.patelheet.capitalengine.market;

import static org.junit.jupiter.api.Assertions.*;

import com.patelheet.capitalengine.market.autopilot.Autopilot;
import com.patelheet.capitalengine.market.storage.Store;
import com.patelheet.capitalengine.market.storage.Store.Dialect;
import com.patelheet.capitalengine.market.storage.Store.Trade;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every Store statement against SQLite always, and against real MySQL/MariaDB and PostgreSQL servers when pointed at a THROWAWAY
 * database whose name contains "capitalengine_test" (the market's own tables are dropped first):
 *   CAPITAL_TEST_POSTGRES="jdbc:postgresql://localhost:5432/capitalengine_test|user|pass"
 *   CAPITAL_TEST_MYSQL="jdbc:mysql://localhost:3306/capitalengine_test|user|pass"
 */
class StoreDialectTest {
    @TempDir Path dir;

    @Test void sqlite() throws Exception {
        try (Store st = new Store(Dialect.SQLITE, "jdbc:sqlite:" + dir.resolve("m.db"), null, null, 2, "")) { exercise(st); }
    }

    @Test @EnabledIfEnvironmentVariable(named = "CAPITAL_TEST_POSTGRES", matches = ".+")
    void postgres() throws Exception { remote(Dialect.POSTGRES, System.getenv("CAPITAL_TEST_POSTGRES")); }

    @Test @EnabledIfEnvironmentVariable(named = "CAPITAL_TEST_MYSQL", matches = ".+")
    void mysql() throws Exception { remote(Dialect.MYSQL, System.getenv("CAPITAL_TEST_MYSQL")); }

    private void remote(Dialect d, String spec) throws Exception {
        String[] p = spec.split("\\|", -1);
        assertTrue(p[0].contains("capitalengine_test"), "refusing to touch a database that isn't named capitalengine_test");
        try (Connection c = DriverManager.getConnection(p[0], p.length > 1 ? p[1] : "", p.length > 2 ? p[2] : ""); Statement s = c.createStatement()) {
            for (String t : List.of("stash", "orders", "trades", "server_stock", "item_values", "day_stats", "policy_state", "jobs", "job_deliveries", "stalls", "tutorial", "flood_stats", "schema_version"))
                s.execute("DROP TABLE IF EXISTS tmk_" + t);
        }
        try (Store st = new Store(d, p[0], p.length > 1 ? p[1] : "", p.length > 2 ? p[2] : "", 2, "tmk_")) {
            exercise(st);
            try (Store again = new Store(d, p[0], p.length > 1 ? p[1] : "", p.length > 2 ? p[2] : "", 2, "tmk_")) { assertEquals(7, again.stock("iron")); }   // reopening runs no migration twice
        }
    }

    private void exercise(Store st) {
        UUID sam = UUID.randomUUID(), mia = UUID.randomUUID();
        // stash: additive upsert, free vs locked
        st.deposit(sam, "iron", 10);
        st.deposit(sam, "iron", 5);
        assertEquals(15, st.stashFree(sam, "iron"));
        st.lock(sam, "iron", 4);
        assertEquals(11, st.stashFree(sam, "iron"));
        assertFalse(st.withdraw(sam, "iron", 12));
        assertTrue(st.withdraw(sam, "iron", 11));
        assertEquals(4, st.stash(sam).get("iron")[0]);

        // orders and the book order: best price first, then oldest
        long a = st.insertOrder("iron", "SELL", sam, 120, 5, 1, 99, "OPEN");
        long b = st.insertOrder("iron", "SELL", sam, 100, 5, 2, 99, "OPEN");
        long c = st.insertOrder("iron", "SELL", sam, 100, 5, 3, 99, "OPEN");
        st.insertOrder("iron", "BUY", mia, 90, 8, 4, 99, "OPEN");
        assertTrue(a < b && b < c);
        assertEquals(List.of(b, c, a), st.resting("iron", "SELL", null).stream().map(o -> o.id()).toList());
        assertEquals(0, st.resting("iron", "SELL", sam).size(), "own orders are excluded");
        assertEquals(8 * 90, st.buyEscrow());

        // a trade: pending, then finished in one transaction (items, order fill, stock)
        st.deposit(mia, "iron", 0);
        Trade t = new Trade(0, "iron", mia.toString(), sam.toString(), null, 100, 3, 2, 1, 1, b, "SELL");
        long id = st.insertPending(t, 1000);
        assertEquals(1, st.pendingTrades().size());
        st.lock(sam, "iron", 3);
        st.deposit(sam, "iron", 3);
        st.finishTrade(t.withId(id));
        assertTrue(st.pendingTrades().isEmpty());
        assertEquals(3, st.stash(mia).get("iron")[0]);
        assertEquals(2, st.openOrders(sam).stream().filter(o -> o.id() == b).findFirst().orElseThrow().left());
        Trade sell = new Trade(0, "iron", null, sam.toString(), "BUY", 80, 4, 0, 1, 1, 0, null);
        st.finishTrade(sell.withId(st.insertPending(sell, 1001)));
        assertEquals(4, st.stock("iron"), "the server bought 4");
        assertEquals(4 * 80, st.serverSpentToday(1));
        assertEquals(4, st.serverBoughtByItem(1).get("iron"));

        // server stock, daily use-up, clamping at zero
        st.addStock("iron", 6);
        st.addStock("iron", -3);
        assertEquals(7, st.stock("iron"));
        st.setStock("wheat", 100);
        st.useUpStock(0.02);
        assertEquals(98, st.stock("wheat"));
        st.addStock("wheat", -1000);
        assertEquals(0, st.stock("wheat"));

        // price data
        st.saveValue("iron", 500, 480, "recipe");
        st.saveValue("iron", 510, 490, "drift");
        assertEquals(510, st.drifted().get("iron"));
        assertEquals(490, st.lastRefs().get("iron"));
        st.clearDrift();
        assertTrue(st.drifted().isEmpty());
        assertFalse(st.playerTrades(0).isEmpty());
        assertEquals(1, st.playerTrades(0).get("iron").size(), "only the player-to-player trade counts");
        assertTrue(st.playerShare(0) > 0 && st.playerShare(0) < 1);
        assertEquals(1, st.playerTradeCount("iron", 0));
        st.pruneTrades(5);
        assertTrue(st.tradedValue(0).isEmpty());

        // days and policy
        assertEquals(-1, st.avgActive7d());
        st.saveDay(1, 100, 4);
        st.saveDay(1, 300, 6);
        st.saveDay(2, 100, 2);
        assertEquals(200, st.avgTreasuryIn7d());
        assertEquals(4, st.avgActive7d(), 1e-9);
        var pol = new Autopilot.State(3, 0.02, 10, 20, 30, 0.35, 101.5, 0.015, 40);
        st.savePolicy(pol);
        st.savePolicy(new Autopilot.State(3, 0.03, 11, 21, 31, 0.4, 102, 0.02, 41));
        assertEquals(0.03, st.latestPolicy().rate(), 1e-9);
        assertEquals(31, st.latestPolicy().buyer());
        assertEquals(1, st.weeksRun());

        // jobs
        long job = st.insertJob(sam.toString(), "iron", 10, 50, null, 1, 99, "OPEN");
        long build = st.insertBuildJob(mia.toString(), 700, "a bridge", 1, 99, "OPEN");
        assertEquals(10 * 50 + 700, st.jobsEscrow());
        st.assign(build, sam.toString());
        assertEquals("ACCEPTED", st.job(build).status());
        long del = st.insertDelivery(job, mia, 4, 200, 1, false);
        st.deposit(mia, "iron", 10);
        st.finishDelivery(del, job, mia, 4);
        assertEquals(4, st.job(job).done());
        assertTrue(st.pendingDeliveries().isEmpty());
        assertEquals(6 * 50 + 700, st.jobsEscrow());
        assertEquals(0, st.serverJobPaidToday(1), "player jobs don't count against the server budget");
        st.insertJob(null, "iron", 5, 60, "railway", 1, 0, "OPEN");
        assertTrue(st.projectJobExists("railway", "iron"));
        assertTrue(st.serverJobOpen("iron") == false, "a project job isn't a 'town needs' job");
        assertTrue(st.anyServerJobOpen());

        // stalls, tutorial, flood stats (the "ignore" and plain upserts)
        var stall = new Store.Stall("world", 1, 2, 3);
        st.addStall(stall);
        st.addStall(stall);
        assertEquals(1, st.stalls().size());
        st.removeStall(stall);
        assertTrue(st.stalls().isEmpty());
        assertEquals(1, st.tutorialStep(sam));
        st.setTutorialStep(sam, 3);
        assertEquals(3, st.tutorialStep(sam), "an existing row isn't reset");
        st.saveFlood("*", "iron", 3.5);
        st.saveFlood("*", "iron", 4.5);
        assertEquals(4.5, st.floodAll().get("*\tiron"), 1e-9);

        // a failed transaction leaves nothing behind
        assertThrows(IllegalStateException.class, () -> st.tx(() -> { st.setStock("coal", 9); throw new IllegalStateException("boom"); }));
        assertEquals(0, st.stock("coal"));
        st.setStock("iron", 7);
    }
}
