package com.patelheet.capitalengine.core.ledger;

import static org.junit.jupiter.api.Assertions.*;

import com.patelheet.capitalengine.core.audit.Auditor;
import com.patelheet.capitalengine.core.ledger.Leg.Kind;
import com.patelheet.capitalengine.core.policy.PolicyManager;
import com.patelheet.capitalengine.core.storage.ClockRepo;
import com.patelheet.capitalengine.core.storage.Database;
import com.patelheet.capitalengine.core.storage.JournalRepo;
import com.patelheet.capitalengine.core.storage.Migrations;
import com.patelheet.capitalengine.core.storage.PlayerRepo;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * The same storage code against real MySQL/MariaDB and PostgreSQL servers. Skipped unless pointed at a THROWAWAY database whose name
 * contains "capitalengine_test" (it is wiped first):
 *   CAPITAL_TEST_POSTGRES="jdbc:postgresql://localhost:5432/capitalengine_test|user|pass"
 *   CAPITAL_TEST_MYSQL="jdbc:mysql://localhost:3306/capitalengine_test|user|pass"
 */
class DialectTest {
    @TempDir Path dir;

    @Test @EnabledIfEnvironmentVariable(named = "CAPITAL_TEST_POSTGRES", matches = ".+")
    void postgres() throws Exception { run(Database.Dialect.POSTGRES, System.getenv("CAPITAL_TEST_POSTGRES")); }

    @Test @EnabledIfEnvironmentVariable(named = "CAPITAL_TEST_MYSQL", matches = ".+")
    void mysql() throws Exception { run(Database.Dialect.MYSQL, System.getenv("CAPITAL_TEST_MYSQL")); }

    private void run(Database.Dialect dialect, String spec) throws Exception {
        String[] p = spec.split("\\|", -1);
        assertTrue(p[0].contains("capitalengine_test"), "refusing to wipe a database that isn't named capitalengine_test");
        Database db = new Database(dialect, p[0], p.length > 1 ? p[1] : "", p.length > 2 ? p[2] : "", 4);
        try {
            wipe(db, dialect);
            Migrations.run(db);
            Migrations.run(db);   // second run is a no-op
            JournalRepo repo = new JournalRepo(db);
            assertEquals(0, repo.maxTx());

            PersistQueue queue = new PersistQueue(db, repo, dir.resolve("spill.jsonl"));
            Ledger ledger = new Ledger(new PolicyManager(), queue::add);
            queue.start();
            Acct sam = ledger.player(UUID.randomUUID(), "Sam"), mia = ledger.player(UUID.randomUUID(), "Mia");
            Acct escrow = ledger.pluginAccount("CapitalMarket", "escrow");
            ledger.apply(new Req("T", "t.issue", "m", null, "ref-1", List.of(new Leg(Kind.ISSUE, null, sam, 1000)), false));
            for (int i = 0; i < 100; i++) ledger.apply(new Req("T", "t.move", "m", null, null, List.of(new Leg(Kind.MOVE, sam, i % 2 == 0 ? mia : escrow, 3)), false));
            ledger.apply(new Req("T", "t.pay", "m", null, null, List.of(new Leg(Kind.SERVER_PAY, null, mia, 50)), false));
            ledger.barrier().get(10, TimeUnit.SECONDS);

            var balances = repo.balances();
            assertEquals(700, balances.get(sam.id()));
            assertEquals(50 * 3 + 50, balances.get(mia.id()));
            assertEquals(50 * 3, balances.get(escrow.id()));
            assertEquals(-1050, balances.get(Ledger.MINT_ID));
            assertTrue(repo.byPlugin("T", 0, 1000, 0).size() >= 102);
            assertTrue(repo.history(sam.id(), 5, Long.MAX_VALUE).size() == 5);
            long day = 5;
            assertFalse(repo.flows(day - 10).isEmpty());

            var pool = Executors.newCachedThreadPool();
            try { assertEquals(List.of(), new Auditor(ledger, repo, pool).run().get(10, TimeUnit.SECONDS)); } finally { pool.shutdown(); }

            // the other repos: players, activity, clock and the single-server lock
            PlayerRepo players = new PlayerRepo(db);
            players.join(sam.id(), "Sam", 1000);
            players.join(sam.id(), "Sam2", 2000);
            assertEquals("Sam2", players.all().get(0).name());
            players.saveActivity(7, java.util.Map.of(sam.id(), 12));
            players.saveActivity(7, java.util.Map.of(sam.id(), 13));
            assertEquals(13, players.activity(7).get(sam.id()));
            assertEquals(1, players.activePlayersPerDay(6, 5).get(7L));
            ClockRepo clock = new ClockRepo(db);
            clock.set("last_day_index", 41);
            clock.set("last_day_index", 42);
            assertEquals(42, clock.get("last_day_index").orElseThrow());
            assertTrue(clock.tryLock("a", 1000, 30_000));
            assertFalse(clock.tryLock("b", 2000, 30_000), "another live server holds the lock");
            assertTrue(clock.tryLock("b", 100_000, 30_000), "a stale lock is taken over");
            clock.release("b");

            queue.shutdown(1000);
        } finally {
            db.close();
        }
    }

    private static void wipe(Database db, Database.Dialect dialect) throws Exception {
        try (Connection c = db.conn(); Statement s = c.createStatement()) {
            if (dialect == Database.Dialect.POSTGRES) { s.execute("DROP SCHEMA public CASCADE"); s.execute("CREATE SCHEMA public"); }
            else {
                s.execute("SET FOREIGN_KEY_CHECKS=0");
                for (String t : List.of("postings", "balances", "journal", "accounts", "players", "activity_daily", "policy_daily", "clock", "server_lock", "schema_version"))
                    s.execute("DROP TABLE IF EXISTS " + t);
                s.execute("SET FOREIGN_KEY_CHECKS=1");
            }
        }
    }
}
