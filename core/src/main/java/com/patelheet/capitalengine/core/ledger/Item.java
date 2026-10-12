package com.patelheet.capitalengine.core.ledger;

import com.patelheet.capitalengine.api.TxRecord;
import com.patelheet.capitalengine.api.TxResult;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Work for the persist queue, in the order the ledger produced it. */
public sealed interface Item {
    /** A new account row (must be written before any transaction touching it). */
    record NewAccount(Acct acct, long createdAt) implements Item {}

    /**
     * An applied transaction. {@code counters} is the plugin's absolute counter row after the tx
     * (issued, destroyed, treasuryIn, treasuryOut, transferred) and {@code balances} the absolute balances of touched accounts.
     */
    record PendingTx(TxRecord rec, long day, String plugin, long[] counters, Map<UUID, Long> balances,
                     TxResult result, CompletableFuture<TxResult> done) implements Item {}

    /** Completes once everything queued before it is in the database. */
    record Barrier(CompletableFuture<Void> done) implements Item {}
}
