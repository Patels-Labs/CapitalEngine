package com.patelheet.capitalengine.core.ledger;

import static org.junit.jupiter.api.Assertions.*;

import com.patelheet.capitalengine.api.Failure;
import com.patelheet.capitalengine.core.ledger.Leg.Kind;
import com.patelheet.capitalengine.core.audit.Auditor;
import com.patelheet.capitalengine.core.policy.PolicyManager;
import com.patelheet.capitalengine.core.storage.Database;
import com.patelheet.capitalengine.core.storage.JournalRepo;
import com.patelheet.capitalengine.core.storage.Migrations;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The ledger against a real SQLite file: group commit, restart, paused mode, spill and replay, and the self-check. */
class PersistenceTest {
    @TempDir Path dir;
    Database db;
    JournalRepo repo;
    PersistQueue queue;
    Ledger ledger;

    @BeforeEach void open() throws Exception {
        db = new Database(Database.Dialect.SQLITE, "jdbc:sqlite:" + dir.resolve("ledger.db"), null, null, 4);
        Migrations.run(db);
        repo = new JournalRepo(db);
        boot();
    }

    void boot() throws Exception {
        queue = new PersistQueue(db, repo, dir.resolve("spill.jsonl"));
        queue.replaySpill();
        ledger = new Ledger(new PolicyManager(), queue::add);
        for (var r : repo.accounts()) {
            if (r.kind() == com.patelheet.capitalengine.api.AccountKind.MINT || r.kind() == com.patelheet.capitalengine.api.AccountKind.TREASURY) continue;
            ledger.restore(new Acct(r.id(), r.kind(), r.plugin(), r.key(), r.name()), repo.balances().getOrDefault(r.id(), 0L));
        }
        var b = repo.balances();
        ledger.restoreMintAndTreasury(b.getOrDefault(Ledger.MINT_ID, 0L), b.getOrDefault(Ledger.TREASURY_ID, 0L));
        repo.references(x -> ledger.restoreReference(x.plugin(), x.reference(), x.txId()));
        ledger.restoreLastTx(repo.maxTx());
        queue.onPause(ledger::setPaused);
        queue.start();
    }

    @AfterEach void close() throws Exception { queue.shutdown(500); db.close(); }

    Applied issue(Acct to, long amount, String ref) {
        return ledger.apply(new Req("T", "t.issue", "m", null, ref, List.of(new Leg(Kind.ISSUE, null, to, amount)), false));
    }

    /** Saved balances without the (empty) Treasury row. */
    Map<UUID, Long> balances() throws Exception {
        var m = new java.util.HashMap<>(repo.balances());
        m.remove(Ledger.TREASURY_ID);
        return m;
    }

    void breakDb() throws Exception { exec("ALTER TABLE journal RENAME TO journal_x"); }
    void fixDb() throws Exception { exec("ALTER TABLE journal_x RENAME TO journal"); }
    void exec(String sql) throws Exception { try (Connection c = db.conn(); Statement s = c.createStatement()) { s.execute(sql); } }

    @Test void groupCommitRoundTripAndRestartReloadsBalances() throws Exception {
        Acct sam = ledger.player(UUID.randomUUID(), "Sam"), mia = ledger.player(UUID.randomUUID(), "Mia");
        issue(sam, 1000, "r1");
        for (int i = 0; i < 200; i++) ledger.apply(new Req("T", "t.move", "m", null, null, List.of(new Leg(Kind.MOVE, sam, mia, 1)), false));
        ledger.barrier().get(5, TimeUnit.SECONDS);
        assertEquals(Map.of(sam.id(), 800L, mia.id(), 200L, Ledger.MINT_ID, -1000L), balances());

        queue.shutdown(1000);
        boot();   // "restart"
        assertEquals(800, ledger.byId(sam.id()).balance());
        assertEquals(200, ledger.byId(mia.id()).balance());
        assertEquals(1000, ledger.stats().moneySupply());
        assertEquals(Failure.DUPLICATE_REFERENCE, issue(ledger.byId(sam.id()), 5, "r1").result().failure(), "references survive restarts");
        assertTrue(repo.maxTx() >= 201, "1 issue + 200 moves");
    }

    @Test void queuedTransactionsAreAnsweredFromMemoryUntilWritten() throws Exception {
        Acct sam = ledger.player(UUID.randomUUID(), "Sam");
        var applied = issue(sam, 10, null);
        assertEquals(10, sam.balance(), "visible immediately");
        assertEquals(applied.result().txId(), applied.persisted().get(5, TimeUnit.SECONDS).txId());
        assertTrue(repo.find(applied.result().txId()).isPresent());
    }

    @Test void databaseTroublePausesTheEconomyAndItResumes() throws Exception {
        queue.tune(3, 60_000, 20);
        Acct sam = ledger.player(UUID.randomUUID(), "Sam");
        issue(sam, 10, null).persisted().get(5, TimeUnit.SECONDS);
        breakDb();
        for (int i = 0; i < 6; i++) issue(sam, 1, null);
        assertTrue(ledger.paused(), "backlog over the limit pauses");
        assertEquals(Failure.ECONOMY_PAUSED, issue(sam, 1, null).result().failure());
        fixDb();
        long deadline = System.currentTimeMillis() + 5000;
        while (ledger.paused() && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertFalse(ledger.paused(), "resumes once the queue drains");
        assertTrue(issue(sam, 1, null).result().success());
        ledger.barrier().get(5, TimeUnit.SECONDS);
        assertEquals(Map.of(sam.id(), sam.balance(), Ledger.MINT_ID, -sam.balance()), balances(), "nothing lost, nothing doubled");
    }

    @Test void spillAndReplayGiveIdenticalBalances() throws Exception {
        queue.tune(100_000, 60_000, 20);
        Acct sam = ledger.player(UUID.randomUUID(), "Sam");
        Acct escrow = ledger.pluginAccount("CapitalMarket", "escrow");
        issue(sam, 500, "ref-1").persisted().get(5, TimeUnit.SECONDS);
        breakDb();
        issue(sam, 250, "ref-2");
        ledger.apply(new Req("CapitalMarket", "m.hold", "m", null, null, List.of(new Leg(Kind.MOVE, sam, escrow, 100)), false));
        Map<UUID, Long> expected = Map.of(sam.id(), 650L, escrow.id(), 100L, Ledger.MINT_ID, -750L);

        queue.shutdown(300);   // database still broken: whatever is unwritten goes to the spill file
        assertTrue(Files.exists(dir.resolve("spill.jsonl")));
        fixDb();
        boot();                // replays the spill before anything else
        assertFalse(Files.exists(dir.resolve("spill.jsonl")));
        assertEquals(expected, balances());
        assertEquals(650, ledger.byId(sam.id()).balance());
        assertEquals(100, ledger.byKey("capitalmarket:escrow").balance(), "accounts created only in the spill come back too");
        assertEquals(Failure.DUPLICATE_REFERENCE, issue(ledger.byId(sam.id()), 1, "ref-2").result().failure());
        assertEquals(List.of(), audit(), "the books balance after replay");
    }

    @Test void selfCheckPassesOnAHealthyLedgerAndCatchesCorruption() throws Exception {
        Acct sam = ledger.player(UUID.randomUUID(), "Sam");
        issue(sam, 100, null);
        assertEquals(List.of(), audit());
        exec("UPDATE balances SET amount = amount + 1 WHERE account_id = '" + sam.id() + "'");
        List<String> problems = audit();
        assertFalse(problems.isEmpty(), "a tampered balance row is reported");
        exec("UPDATE postings SET amount = amount + 5 WHERE account_id = '" + sam.id() + "'");
        assertTrue(audit().stream().anyMatch(p -> p.contains("add up to zero")), "books that don't sum to zero are reported");
    }

    List<String> audit() throws Exception {
        var pool = Executors.newCachedThreadPool();
        try { return new Auditor(ledger, repo, pool).run().get(5, TimeUnit.SECONDS); } finally { pool.shutdown(); }
    }
}
