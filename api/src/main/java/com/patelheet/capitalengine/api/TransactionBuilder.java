package com.patelheet.capitalengine.api;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Builds a multi-leg transaction that applies completely or not at all. */
public interface TransactionBuilder {
    /** Moves coins between two accounts. Moving out of the Treasury is not allowed here; use {@link #serverPay}. */
    TransactionBuilder move(Account from, Account to, long amount);

    /** Creates coins (Mint → {@code to}). Counts against the caller's issue limit. */
    TransactionBuilder issue(Account to, long amount);

    /** Destroys coins ({@code from} → Mint). */
    TransactionBuilder destroy(Account from, long amount);

    /** Pays from the server: Treasury first, newly created coins for any shortfall, within the caller's limits. */
    TransactionBuilder serverPay(Account to, long amount);

    /** Idempotency key, unique per plugin. A repeat commit fails with {@link Failure#DUPLICATE_REFERENCE}. */
    TransactionBuilder reference(String reference);

    /** The player who caused this, for audits. Optional. */
    TransactionBuilder actor(UUID playerId);

    /** Validates and applies in memory now; the future completes once the transaction is in the database. */
    CompletableFuture<TxResult> commit();
}
