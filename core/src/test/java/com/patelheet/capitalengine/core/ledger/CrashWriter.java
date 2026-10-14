package com.patelheet.capitalengine.core.ledger;

import com.patelheet.capitalengine.core.ledger.Leg.Kind;
import com.patelheet.capitalengine.core.policy.PolicyManager;
import com.patelheet.capitalengine.core.storage.Database;
import com.patelheet.capitalengine.core.storage.JournalRepo;
import com.patelheet.capitalengine.core.storage.Migrations;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/** Child process for {@link CrashTest}: hammers the ledger until it is killed, printing "P <txId>" once a transaction is in the database. */
public final class CrashWriter {
    public static void main(String[] args) throws Exception {
        Database db = new Database(Database.Dialect.SQLITE, "jdbc:sqlite:" + Path.of(args[0]), null, null, 4);
        Migrations.run(db);
        PersistQueue queue = new PersistQueue(db, new JournalRepo(db), Path.of(args[0] + ".spill"));
        Ledger ledger = new Ledger(new PolicyManager(), queue::add);
        queue.onPause(ledger::setPaused);
        queue.start();
        List<Acct> accounts = new ArrayList<>();
        for (int i = 0; i < 50; i++) accounts.add(ledger.player(UUID.randomUUID(), "P" + i));
        for (Acct a : accounts) ledger.apply(new Req("T", "t.issue", "m", null, null, List.of(new Leg(Kind.ISSUE, null, a, 100_000)), false));
        Random rnd = new Random(7);
        System.out.println("READY");
        System.out.flush();
        while (true) {
            Acct from = accounts.get(rnd.nextInt(50)), to = accounts.get(rnd.nextInt(50));
            var applied = ledger.apply(new Req("T", "t.move", "m", null, null, List.of(new Leg(Kind.MOVE, from, to, 1 + rnd.nextInt(500))), false));
            applied.persisted().thenAccept(r -> { if (r.success()) { System.out.println("P " + r.txId()); System.out.flush(); } });
            if (rnd.nextInt(50) == 0) Thread.sleep(1);
        }
    }
}
