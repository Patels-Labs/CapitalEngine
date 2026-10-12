package com.patelheet.capitalengine.api;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.bukkit.plugin.Plugin;
import com.patelheet.capitalengine.api.service.MonetaryPolicy;

/**
 * Entry point of the CapitalEngine API. Obtain it from Bukkit's ServicesManager:
 * <pre>{@code CapitalApi api = Bukkit.getServicesManager().load(CapitalApi.class);}</pre>
 *
 * <p>Amounts are {@code long} counts of the smallest unit and must be greater than 0. Every mutation is atomic,
 * attributed to the calling plugin and checked against the owner's limits. Balance changes are visible to every
 * reader as soon as the call returns; the future completes once the transaction is in the database, on a core
 * thread (switch to the main/region thread before touching Bukkit objects, and never block on it).
 * Failures are values ({@link TxResult}), not exceptions.
 */
public interface CapitalApi {
    /** The running API version, for example {@code "1.0.0"}. */
    String apiVersion();

    /** Whether the running API is at least {@code minVersion} (semantic versioning). */
    boolean isCompatible(String minVersion);

    /** Account lookup and creation. */
    Accounts accounts();

    /** The server's wallet. */
    Account treasury();

    /** The currency. */
    Currency currency();

    /** Moves coins between two accounts. {@code memo} is up to 128 characters and shown to players. */
    CompletableFuture<TxResult> transfer(Plugin caller, Account from, Account to, long amount, String type, String memo);

    /** Pays from the server: Treasury first, newly created coins for any shortfall, within the caller's limits. */
    CompletableFuture<TxResult> serverPay(Plugin caller, Account to, long amount, String type, String memo);

    /** Player (or other account) → Treasury. */
    CompletableFuture<TxResult> collect(Plugin caller, Account from, long amount, String type, String memo);

    /** Creates coins: Mint → {@code to}. */
    CompletableFuture<TxResult> issue(Plugin caller, Account to, long amount, String type, String memo);

    /** Destroys coins: {@code from} → Mint. */
    CompletableFuture<TxResult> destroy(Plugin caller, Account from, long amount, String type, String memo);

    /** Starts a multi-leg transaction. Use this whenever you need a {@code reference}. */
    TransactionBuilder transaction(Plugin caller, String type, String memo);

    /** Finds this plugin's transaction by its reference, for crash recovery. */
    CompletableFuture<Optional<TxRecord>> findByReference(Plugin caller, String reference);

    /** An account's transactions, newest first. Pass {@link Long#MAX_VALUE} as {@code beforeTxId} for the first page. */
    CompletableFuture<List<TxRecord>> history(Account account, int limit, long beforeTxId);

    /** Instant economy-wide snapshot. */
    Stats stats();

    /** The caller's remaining limits today. */
    PolicyStatus policy(Plugin caller);

    /** The registered {@link MonetaryPolicy} service with the highest priority, if any. */
    Optional<MonetaryPolicy> monetaryPolicy();

    /** Raises an alert for owners. Fires {@code CapitalAlertEvent} safely from any thread. */
    void alert(Plugin source, Severity severity, String code, String message);
}
