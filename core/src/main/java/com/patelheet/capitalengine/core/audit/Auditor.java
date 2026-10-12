package com.patelheet.capitalengine.core.audit;

import com.patelheet.capitalengine.api.AccountKind;
import com.patelheet.capitalengine.core.ledger.Acct;
import com.patelheet.capitalengine.core.ledger.Ledger;
import com.patelheet.capitalengine.core.storage.JournalRepo;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * The self-check. Compares a memory snapshot with the database as of exactly the same transaction, so a busy server
 * doesn't cause false alarms: the books sum to zero, every balance equals its postings, and nobody is below zero.
 */
public final class Auditor {
    private final Ledger ledger;
    private final JournalRepo repo;
    private final Executor async;

    public Auditor(Ledger ledger, JournalRepo repo, Executor async) { this.ledger = ledger; this.repo = repo; this.async = async; }

    /** Completes with the list of problems (empty = all good). */
    public CompletableFuture<List<String>> run() {
        Ledger.Snapshot snap = ledger.snapshot();
        return snap.barrier().thenApplyAsync(v -> {
            List<String> problems = new ArrayList<>();
            try {
                long total = repo.postingsTotal(snap.maxTx());
                if (total != 0) problems.add("The books don't add up to zero (off by " + total + ").");
                Map<UUID, Long> sums = repo.postingSums(snap.maxTx());
                Set<UUID> ids = new HashSet<>(snap.balances().keySet());
                ids.addAll(sums.keySet());
                for (UUID id : ids) {
                    long mem = snap.balances().getOrDefault(id, 0L), books = sums.getOrDefault(id, 0L);
                    if (mem != books) problems.add(name(id) + ": memory says " + mem + " but the ledger adds up to " + books + ".");
                    Acct a = ledger.byId(id);
                    if (mem < 0 && (a == null || a.kind() != AccountKind.MINT)) problems.add(name(id) + " is below zero (" + mem + ").");
                }
                Map<UUID, Long> rows = new HashMap<>(repo.balanceRowsUpTo(snap.maxTx()));
                rows.forEach((id, amount) -> {
                    long books = sums.getOrDefault(id, 0L);
                    if (amount != books) problems.add(name(id) + ": saved balance " + amount + " but the ledger adds up to " + books + ".");
                });
            } catch (Exception e) {
                problems.add("The self-check could not read the database: " + e.getMessage());
            }
            return problems;
        }, async);
    }

    private String name(UUID id) { Acct a = ledger.byId(id); return a == null ? id.toString() : a.name(); }
}
