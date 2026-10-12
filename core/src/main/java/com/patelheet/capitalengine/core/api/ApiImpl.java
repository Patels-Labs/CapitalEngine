package com.patelheet.capitalengine.core.api;

import com.patelheet.capitalengine.api.Account;
import com.patelheet.capitalengine.api.Accounts;
import com.patelheet.capitalengine.api.CapitalApi;
import com.patelheet.capitalengine.api.Currency;
import com.patelheet.capitalengine.api.Failure;
import com.patelheet.capitalengine.api.PolicyStatus;
import com.patelheet.capitalengine.api.Severity;
import com.patelheet.capitalengine.api.Stats;
import com.patelheet.capitalengine.api.TransactionBuilder;
import com.patelheet.capitalengine.api.TxRecord;
import com.patelheet.capitalengine.api.TxResult;
import com.patelheet.capitalengine.api.event.CapitalAlertEvent;
import com.patelheet.capitalengine.api.service.MonetaryPolicy;
import com.patelheet.capitalengine.core.activity.ActivityTracker;
import com.patelheet.capitalengine.core.ledger.Acct;
import com.patelheet.capitalengine.core.ledger.Applied;
import com.patelheet.capitalengine.core.ledger.Leg;
import com.patelheet.capitalengine.core.ledger.Leg.Kind;
import com.patelheet.capitalengine.core.ledger.Ledger;
import com.patelheet.capitalengine.core.ledger.PersistQueue;
import com.patelheet.capitalengine.core.ledger.Req;
import com.patelheet.capitalengine.core.storage.JournalRepo;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/** {@link CapitalApi} on top of the ledger. Every mutation is a {@link Req} with {@code bypass=false}. */
public final class ApiImpl implements CapitalApi {
    /** Written into the API jar by its build, so there is one place to bump it: api/gradle.properties. */
    public static final String VERSION = readVersion();

    private static String readVersion() {
        try (var in = CapitalApi.class.getResourceAsStream("/capitalengine-api.version")) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (java.io.IOException | NullPointerException e) {
            throw new IllegalStateException("capitalengine-api.version is missing from the API jar", e);
        }
    }

    private final Ledger ledger;
    private final PersistQueue queue;
    private final JournalRepo repo;
    private final CurrencyImpl currency;
    private final ActivityTracker players;
    private final Executor async;
    private final Accounts accounts = new Accounts() {
        @Override public Account player(UUID id) { return ledger.player(Objects.requireNonNull(id), players.nameOf(id)); }
        @Override public Account plugin(Plugin owner, String key) {
            if (key == null || key.isBlank()) throw new IllegalArgumentException("account key is empty");
            return ledger.pluginAccount(owner.getName(), key);
        }
        @Override public Optional<Account> find(String key) { return Optional.ofNullable(ledger.byKey(key)); }
        @Override public Optional<Account> byId(UUID id) {
            Acct a = ledger.byId(id);
            return a == null || a.kind() == com.patelheet.capitalengine.api.AccountKind.MINT ? Optional.empty() : Optional.of(a);
        }
    };

    public ApiImpl(Ledger ledger, PersistQueue queue, JournalRepo repo, CurrencyImpl currency, ActivityTracker players, Executor async) {
        this.ledger = ledger; this.queue = queue; this.repo = repo; this.currency = currency; this.players = players; this.async = async;
    }

    @Override public String apiVersion() { return VERSION; }

    @Override public boolean isCompatible(String min) {
        String[] have = VERSION.split("\\."), want = min.split("\\.");   // "1.0.0-rc.1" counts as 1.0.0
        for (int i = 0; i < 3; i++) {
            int h = Integer.parseInt(have[i].replaceAll("\\D.*", "")), w = i < want.length ? Integer.parseInt(want[i].replaceAll("\\D.*", "")) : 0;
            if (h != w) return h > w;
        }
        return true;
    }

    @Override public Accounts accounts() { return accounts; }
    @Override public Account treasury() { return ledger.treasury(); }
    @Override public Currency currency() { return currency; }

    @Override public CompletableFuture<TxResult> transfer(Plugin caller, Account from, Account to, long amount, String type, String memo) {
        return one(caller, type, memo, new Leg(Kind.MOVE, acct(from), acct(to), amount), from, to);
    }
    @Override public CompletableFuture<TxResult> serverPay(Plugin caller, Account to, long amount, String type, String memo) {
        return one(caller, type, memo, new Leg(Kind.SERVER_PAY, null, acct(to), amount), to);
    }
    @Override public CompletableFuture<TxResult> collect(Plugin caller, Account from, long amount, String type, String memo) {
        return one(caller, type, memo, new Leg(Kind.MOVE, acct(from), ledger.treasury(), amount), from);
    }
    @Override public CompletableFuture<TxResult> issue(Plugin caller, Account to, long amount, String type, String memo) {
        return one(caller, type, memo, new Leg(Kind.ISSUE, null, acct(to), amount), to);
    }
    @Override public CompletableFuture<TxResult> destroy(Plugin caller, Account from, long amount, String type, String memo) {
        return one(caller, type, memo, new Leg(Kind.DESTROY, acct(from), null, amount), from);
    }

    @Override public TransactionBuilder transaction(Plugin caller, String type, String memo) {
        Objects.requireNonNull(caller); Objects.requireNonNull(type); Objects.requireNonNull(memo);
        return new Builder(caller, type, memo);
    }

    private CompletableFuture<TxResult> one(Plugin caller, String type, String memo, Leg leg, Account... mustBeKnown) {
        Objects.requireNonNull(caller); Objects.requireNonNull(type); Objects.requireNonNull(memo);
        for (Account a : mustBeKnown) Objects.requireNonNull(a, "account");
        return run(new Req(caller.getName(), type, memo, null, null, List.of(leg), false));
    }

    private CompletableFuture<TxResult> run(Req r) {
        Applied a = ledger.apply(r);
        return a.persisted();
    }

    /** Foreign Account implementations can't be in our ledger; null makes the ledger answer NO_ACCOUNT. */
    private static Acct acct(Account a) { return a instanceof Acct x ? x : null; }

    private final class Builder implements TransactionBuilder {
        private final Plugin caller; private final String type, memo;
        private final List<Leg> legs = new ArrayList<>();
        private String reference; private UUID actor; private boolean unknownAccount;

        Builder(Plugin caller, String type, String memo) { this.caller = caller; this.type = type; this.memo = memo; }

        private Acct known(Account a) {
            Objects.requireNonNull(a, "account");
            if (!(a instanceof Acct x)) { unknownAccount = true; return null; }
            return x;
        }
        @Override public TransactionBuilder move(Account from, Account to, long amount) { legs.add(new Leg(Kind.MOVE, known(from), known(to), amount)); return this; }
        @Override public TransactionBuilder issue(Account to, long amount) { legs.add(new Leg(Kind.ISSUE, null, known(to), amount)); return this; }
        @Override public TransactionBuilder destroy(Account from, long amount) { legs.add(new Leg(Kind.DESTROY, known(from), null, amount)); return this; }
        @Override public TransactionBuilder serverPay(Account to, long amount) { legs.add(new Leg(Kind.SERVER_PAY, null, known(to), amount)); return this; }
        @Override public TransactionBuilder reference(String ref) { reference = ref; return this; }
        @Override public TransactionBuilder actor(UUID id) { actor = id; return this; }
        @Override public CompletableFuture<TxResult> commit() {
            if (unknownAccount || legs.isEmpty()) {
                return CompletableFuture.completedFuture(new TxResult(false, 0, unknownAccount ? Failure.NO_ACCOUNT : Failure.INVALID_AMOUNT, unknownAccount ? "That account doesn't exist." : "Nothing to move.", 0, 0));
            }
            return run(new Req(caller.getName(), type, memo, actor, reference, List.copyOf(legs), false));
        }
    }

    // ---- reads ------------------------------------------------------------------------------------------------

    /** A transaction by id: still-queued ones are answered from memory, the rest from the database. */
    public CompletableFuture<Optional<TxRecord>> tx(long txId) {
        Optional<TxRecord> inFlight = queue.pending(txId);
        if (inFlight.isPresent()) return CompletableFuture.completedFuture(inFlight);
        return CompletableFuture.supplyAsync(() -> {
            try { return repo.find(txId); } catch (SQLException e) { throw new CompletionException(e); }
        }, async);
    }

    @Override public CompletableFuture<Optional<TxRecord>> findByReference(Plugin caller, String reference) {
        Long id = ledger.referenceTx(caller.getName(), reference);
        return id == null ? CompletableFuture.completedFuture(Optional.empty()) : tx(id);
    }

    @Override public CompletableFuture<List<TxRecord>> history(Account account, int limit, long beforeTxId) {
        return ledger.barrier().thenApplyAsync(v -> {
            try { return repo.history(account.id(), Math.max(1, Math.min(limit, 500)), beforeTxId); }
            catch (SQLException e) { throw new CompletionException(e); }
        }, async);
    }

    @Override public Stats stats() { return ledger.stats(); }
    @Override public PolicyStatus policy(Plugin caller) { return ledger.policyStatus(caller.getName()); }
    @Override public Optional<MonetaryPolicy> monetaryPolicy() { return Optional.ofNullable(Bukkit.getServicesManager().load(MonetaryPolicy.class)); }

    @Override public void alert(Plugin source, Severity severity, String code, String message) {
        Bukkit.getPluginManager().callEvent(new CapitalAlertEvent(!Bukkit.isPrimaryThread(), source.getName(), severity, code, message));
    }
}
