package com.patelheet.capitalengine.core.ledger;

import static org.junit.jupiter.api.Assertions.*;

import com.patelheet.capitalengine.core.audit.Auditor;
import com.patelheet.capitalengine.core.policy.PolicyManager;
import com.patelheet.capitalengine.core.storage.Database;
import com.patelheet.capitalengine.core.storage.JournalRepo;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A real SIGKILL in the middle of heavy writing: what the database says must still add up, and nothing reported as saved may be missing. */
class CrashTest {
    @TempDir Path dir;

    @Test void killNineMidLoadLeavesBooksThatBalance() throws Exception {
        Path dbFile = dir.resolve("ledger.db");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process child = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), CrashWriter.class.getName(), dbFile.toString())
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        List<Long> persisted = new ArrayList<>();
        var reader = new BufferedReader(new InputStreamReader(child.getInputStream()));
        assertEquals("READY", reader.readLine());
        long killAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1500);
        while (System.nanoTime() < killAt) {
            if (!reader.ready()) { Thread.sleep(5); continue; }
            String line = reader.readLine();
            if (line != null && line.startsWith("P ")) persisted.add(Long.parseLong(line.substring(2)));
        }
        child.destroyForcibly();   // SIGKILL: no shutdown hooks, no spill, nothing
        child.waitFor();
        while (reader.ready()) {
            String line = reader.readLine();
            if (line != null && line.startsWith("P ")) persisted.add(Long.parseLong(line.substring(2)));
        }
        assertTrue(persisted.size() > 100, "the child should have saved plenty before dying (" + persisted.size() + ")");

        // restart, exactly like core's startup, then run the self-check
        Database db = new Database(Database.Dialect.SQLITE, "jdbc:sqlite:" + dbFile, null, null, 4);
        try {
            JournalRepo repo = new JournalRepo(db);
            PersistQueue queue = new PersistQueue(db, repo, dir.resolve("spill.jsonl"));
            queue.replaySpill();
            Ledger ledger = new Ledger(new PolicyManager(), queue::add);
            var balances = repo.balances();
            for (var r : repo.accounts()) {
                if (r.kind() == com.patelheet.capitalengine.api.AccountKind.MINT || r.kind() == com.patelheet.capitalengine.api.AccountKind.TREASURY) continue;
                ledger.restore(new Acct(r.id(), r.kind(), r.plugin(), r.key(), r.name()), balances.getOrDefault(r.id(), 0L));
            }
            ledger.restoreMintAndTreasury(balances.getOrDefault(Ledger.MINT_ID, 0L), balances.getOrDefault(Ledger.TREASURY_ID, 0L));
            ledger.restoreLastTx(repo.maxTx());
            queue.start();

            assertEquals(5_000_000, ledger.stats().moneySupply(), "money in circulation is exactly what was issued");
            for (long id : persisted) assertTrue(repo.find(id).isPresent(), "tx " + id + " was reported saved but is missing");
            var pool = Executors.newCachedThreadPool();
            try { assertEquals(List.of(), new Auditor(ledger, repo, pool).run().get(10, TimeUnit.SECONDS), "/ce check after the crash"); }
            finally { pool.shutdown(); queue.shutdown(500); }
        } finally { db.close(); }
    }
}
