package com.patelheet.capitalengine.core.ledger;

import com.patelheet.capitalengine.api.AccountKind;
import com.patelheet.capitalengine.api.Failure;
import com.patelheet.capitalengine.api.PluginFlow;
import com.patelheet.capitalengine.api.PolicyStatus;
import com.patelheet.capitalengine.api.Posting;
import com.patelheet.capitalengine.api.Stats;
import com.patelheet.capitalengine.api.TxRecord;
import com.patelheet.capitalengine.api.TxResult;
import com.patelheet.capitalengine.api.event.CapitalPolicyLimitEvent.LimitKind;
import com.patelheet.capitalengine.core.ledger.Item.Barrier;
import com.patelheet.capitalengine.core.ledger.Item.NewAccount;
import com.patelheet.capitalengine.core.ledger.Item.PendingTx;
import com.patelheet.capitalengine.core.policy.PolicyManager;
import com.patelheet.capitalengine.core.policy.PolicyManager.Counter;
import com.patelheet.capitalengine.core.policy.PolicyManager.Limit;
import com.patelheet.capitalengine.core.policy.PolicyManager.VaultMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * In-memory balances with validate-and-apply under one global lock (held for microseconds), then hand-off to the
 * persist queue. Pure Java: no Bukkit imports, so tests and the simulator can drive it directly.
 * ponytail: one global lock plus one writer; shard the lock by account if a big network ever needs more.
 */
public final class Ledger {
    public static final UUID MINT_ID = new UUID(0, 1);
    public static final UUID TREASURY_ID = new UUID(0, 2);
    /** Source name for owner tools (give/take/import/reverse): they bypass limits and don't eat plugin budgets. */
    public static final String ADMIN = "CapitalEngine-admin";
    public static final String PAUSED_MSG = "The bank is having a nap, try again in a minute!";

    /** A cap was hit or a plugin was denied. */
    public record LimitHit(String plugin, LimitKind kind, long requested, long remaining) {}

    /** Balances at the exact moment a barrier was queued, so the auditor can compare like with like. */
    public record Snapshot(Map<UUID, Long> balances, long maxTx, CompletableFuture<Void> barrier) {}

    private final ReentrantLock lock = new ReentrantLock();
    private final Map<UUID, Acct> byId = new ConcurrentHashMap<>();
    private final Map<String, Acct> byKey = new ConcurrentHashMap<>();
    private final Map<String, Acct> externalByName = new ConcurrentHashMap<>();
    private final Map<String, Long> refs = new HashMap<>();
    private final PolicyManager policy;
    private final Consumer<Item> sink;
    private final Acct mint = new Acct(MINT_ID, AccountKind.MINT, null, null, "Mint");
    private final Acct treasury = new Acct(TREASURY_ID, AccountKind.TREASURY, null, null, "Treasury");
    private long lastTx;
    private volatile boolean paused;
    private volatile long dayIndex, weekIndex;
    private volatile Activity activity = Activity.NONE;
    private volatile Consumer<LimitHit> limitHook = h -> {};

    public Ledger(PolicyManager policy, Consumer<Item> sink) {
        this.policy = policy;
        this.sink = sink;
        byId.put(MINT_ID, mint);
        byId.put(TREASURY_ID, treasury);
    }

    // ---- wiring -----------------------------------------------------------------------------------------------

    public void setPaused(boolean p) { paused = p; }
    public boolean paused() { return paused; }
    public void setClock(long day, long week) { dayIndex = day; weekIndex = week; }
    public long dayIndex() { return dayIndex; }
    public void setActivity(Activity a) { activity = a; }
    public void onLimit(Consumer<LimitHit> hook) { limitHook = hook; }
    public PolicyManager policy() { return policy; }
    public Acct treasury() { return treasury; }
    public Acct mint() { return mint; }

    // ---- loading (startup, before any traffic) ----------------------------------------------------------------

    /** Puts a persisted account with its balance into memory. */
    public void restore(Acct a, long balance) {
        a.balance = balance;
        byId.put(a.id(), a);
        if (a.namespaced() != null) byKey.put(a.namespaced(), a);
        if (a.kind() == AccountKind.EXTERNAL) externalByName.putIfAbsent(a.key().toLowerCase(Locale.ROOT), a);
    }

    public void restoreMintAndTreasury(long mintBalance, long treasuryBalance) {
        mint.balance = mintBalance;
        treasury.balance = treasuryBalance;
    }

    public void restoreReference(String plugin, String reference, long txId) { refs.put(plugin.toLowerCase(Locale.ROOT) + '\0' + reference, txId); }
    public void restoreLastTx(long tx) { lastTx = tx; }

    // ---- accounts ---------------------------------------------------------------------------------------------

    public Acct byId(UUID id) { return byId.get(id); }
    public Acct byKey(String namespaced) { return byKey.get(namespaced.toLowerCase(Locale.ROOT)); }
    public Acct externalByName(String name) { return externalByName.get(name.toLowerCase(Locale.ROOT)); }

    public Acct player(UUID id, String name) {
        Acct a = byId.get(id);
        if (a != null) return a;
        return create(new Acct(id, AccountKind.PLAYER, null, null, name != null ? name : id.toString()));
    }

    public Acct pluginAccount(String plugin, String key) {
        String p = plugin.toLowerCase(Locale.ROOT), k = key.toLowerCase(Locale.ROOT);
        Acct a = byKey.get(p + ":" + k);
        if (a != null) return a;
        return create(new Acct(UUID.randomUUID(), AccountKind.PLUGIN, p, k, p + ":" + k));
    }

    /** A non-player account made by a Vault plugin. {@code id} is the caller's UUID, or a name-derived one. */
    public Acct external(UUID id, String plugin, String key, String name) {
        Acct a = byId.get(id);
        if (a != null) return a;
        return create(new Acct(id, AccountKind.EXTERNAL, plugin.toLowerCase(Locale.ROOT), key, name));
    }

    private Acct create(Acct fresh) {
        lock.lock();
        try {
            Acct existing = fresh.namespaced() != null ? byKey.get(fresh.namespaced()) : null;
            if (existing == null) existing = byId.get(fresh.id());
            if (existing != null) return existing;
            restore(fresh, 0);
            sink.accept(new NewAccount(fresh, System.currentTimeMillis()));
            return fresh;
        } finally { lock.unlock(); }
    }

    // ---- mutation ---------------------------------------------------------------------------------------------

    public Applied apply(Req r) {
        lock.lock();
        try {
            if (paused) return fail(Failure.ECONOMY_PAUSED, PAUSED_MSG);
            String pk = r.plugin().toLowerCase(Locale.ROOT);
            Applied dup = duplicate(pk, r.reference());
            if (dup != null) return dup;
            Limit lim = r.bypass() ? null : policy.limit(pk);
            Counter ctr = policy.counter(pk, r.plugin());
            Map<Acct, Long> net = new LinkedHashMap<>();
            List<Leg> pays = new ArrayList<>();
            long payTotal = 0, issueTotal = 0;
            try {
                for (Leg l : r.legs()) {
                    if (l.amount() <= 0) return fail(Failure.INVALID_AMOUNT, "That amount doesn't work.");
                    if (l.from() != null && byId.get(l.from().id()) != l.from() || l.to() != null && byId.get(l.to().id()) != l.to())
                        return fail(Failure.NO_ACCOUNT, "That account doesn't exist.");
                    if (lim != null && lim.mode() == VaultMode.DENY && l.kind() != Leg.Kind.MOVE) return denied(r.plugin(), l.amount());
                    if (l.from() != null && !r.bypass() && !LedgerRules.canSource(l.from(), pk))
                        return fail(Failure.NOT_ACCOUNT_OWNER, "That account belongs to someone else.");
                    switch (l.kind()) {
                        case MOVE -> { add(net, l.from(), -l.amount()); add(net, l.to(), l.amount()); }
                        case ISSUE -> { add(net, mint, -l.amount()); add(net, l.to(), l.amount()); issueTotal = Math.addExact(issueTotal, l.amount()); }
                        case DESTROY -> { add(net, l.from(), -l.amount()); add(net, mint, l.amount()); }
                        case SERVER_PAY -> { pays.add(l); payTotal = Math.addExact(payTotal, l.amount()); }
                    }
                }
                if (payTotal > 0) {
                    long tRemain = lim == null ? Long.MAX_VALUE : Math.max(0, lim.treasuryPerDay() - ctr.treasuryOut);
                    long fromT = LedgerRules.fromTreasury(payTotal, treasury.balance, tRemain);
                    issueTotal = Math.addExact(issueTotal, payTotal - fromT);
                    add(net, treasury, -fromT);
                    add(net, mint, -(payTotal - fromT));
                    for (Leg l : pays) add(net, l.to(), l.amount());
                }
                if (lim != null && issueTotal > 0) {
                    long remain = Math.max(0, lim.issuePerDay() - ctr.issued);
                    if (issueTotal > remain) {
                        limitHook.accept(new LimitHit(r.plugin(), LimitKind.ISSUE, issueTotal, remain));
                        return fail(Failure.POLICY_LIMIT, "That's over today's limit for " + r.plugin() + ".");
                    }
                }
                return commit(r.plugin(), pk, r.type(), r.memo(), r.actor(), r.reference(), null, net, ctr);
            } catch (ArithmeticException overflow) {
                return fail(Failure.INVALID_AMOUNT, "That amount is too big.");
            }
        } finally { lock.unlock(); }
    }

    /** Admin reversal: the negated postings of {@code orig}, refused if any account would go below zero or it was already reversed. */
    public Applied reverse(TxRecord orig, UUID actor, String reason) {
        lock.lock();
        try {
            if (paused) return fail(Failure.ECONOMY_PAUSED, PAUSED_MSG);
            String ref = "reverse:" + orig.txId();
            Applied dup = duplicate(ADMIN.toLowerCase(Locale.ROOT), ref);
            if (dup != null) return new Applied(new TxResult(false, dup.result().txId(), Failure.DUPLICATE_REFERENCE, "Transaction #" + orig.txId() + " was already reversed.", 0, 0), dup.persisted());
            Map<Acct, Long> net = new LinkedHashMap<>();
            try {
                for (Posting p : orig.postings()) {
                    Acct a = byId.get(p.accountId());
                    if (a == null) return fail(Failure.NO_ACCOUNT, "An account in that transaction no longer exists.");
                    add(net, a, Math.negateExact(p.amount()));
                }
                String memo = "Reversal of #" + orig.txId() + (reason == null || reason.isBlank() ? "" : ": " + reason);
                return commit(ADMIN, ADMIN.toLowerCase(Locale.ROOT), "core.reverse", memo, actor, ref, orig.txId(), net, policy.counter(ADMIN.toLowerCase(Locale.ROOT), ADMIN));
            } catch (ArithmeticException e) {
                return fail(Failure.INVALID_AMOUNT, "That amount is too big.");
            }
        } finally { lock.unlock(); }
    }

    private Applied duplicate(String pk, String reference) {
        if (reference == null) return null;
        Long ex = refs.get(pk + '\0' + reference);
        return ex == null ? null : new Applied(new TxResult(false, ex, Failure.DUPLICATE_REFERENCE, "Already done.", 0, 0),
                CompletableFuture.completedFuture(new TxResult(false, ex, Failure.DUPLICATE_REFERENCE, "Already done.", 0, 0)));
    }

    private Applied denied(String plugin, long amount) {
        limitHook.accept(new LimitHit(plugin, LimitKind.DENIED, amount, 0));
        return fail(Failure.DENIED, plugin + " isn't allowed to create or remove money here.");
    }

    private static void add(Map<Acct, Long> net, Acct a, long delta) { net.merge(a, delta, Math::addExact); }

    private Applied commit(String display, String pk, String type, String memo, UUID actor, String reference,
                           Long reverses, Map<Acct, Long> net, Counter ctr) {
        net.values().removeIf(v -> v == 0);
        if (net.isEmpty()) return fail(Failure.INVALID_AMOUNT, "Nothing to move.");
        for (var e : net.entrySet()) {
            Acct a = e.getKey();
            long after = Math.addExact(a.balance, e.getValue());
            if (a.kind() != AccountKind.MINT && after < 0)
                return fail(Failure.INSUFFICIENT_FUNDS, a.kind() == AccountKind.PLAYER && !ADMIN.equals(display) ? "Not enough coins." : a.name() + " doesn't have enough coins.");
        }
        long issued = 0, destroyed = 0, tin = 0, tout = 0;
        List<Posting> postings = new ArrayList<>(net.size());
        Map<UUID, Long> balances = new HashMap<>();
        long txId = lastTx + 1;
        for (var e : net.entrySet()) {
            Acct a = e.getKey();
            long d = e.getValue();
            a.balance += d;
            balances.put(a.id(), a.balance);
            postings.add(new Posting(a.id(), d));
            if (a.kind() == AccountKind.MINT) { if (d < 0) issued -= d; else destroyed += d; }
            else if (a.kind() == AccountKind.TREASURY) { if (d > 0) tin += d; else tout -= d; }
        }
        lastTx = txId;
        ctr.issued += issued; ctr.destroyed += destroyed; ctr.treasuryIn += tin; ctr.treasuryOut += tout;
        ctr.transferred += LedgerRules.transferred(net, issued, tout);
        if (reference != null) refs.put(pk + '\0' + reference, txId);
        String m = memo == null ? "" : memo.length() > 128 ? memo.substring(0, 128) : memo;
        TxRecord rec = new TxRecord(txId, Instant.now(), type, m, display, actor, reference, reverses, List.copyOf(postings));
        TxResult res = new TxResult(true, txId, Failure.NONE, "Done!", tout, issued);
        CompletableFuture<TxResult> done = new CompletableFuture<>();
        sink.accept(new PendingTx(rec, dayIndex, ctr.display, ctr.snapshot(), balances, res, done));
        return new Applied(res, done);
    }

    private static Applied fail(Failure f, String message) {
        TxResult r = new TxResult(false, 0, f, message, 0, 0);
        return new Applied(r, CompletableFuture.completedFuture(r));
    }

    // ---- reads ------------------------------------------------------------------------------------------------

    public PolicyStatus policyStatus(String plugin) {
        String pk = plugin.toLowerCase(Locale.ROOT);
        lock.lock();
        try {
            Limit lim = policy.limit(pk);
            Counter c = policy.counter(pk, plugin);
            return new PolicyStatus(remaining(lim.issuePerDay(), c.issued), remaining(lim.treasuryPerDay(), c.treasuryOut), lim.mode() == VaultMode.DENY);
        } finally { lock.unlock(); }
    }

    private static long remaining(long limit, long used) { return limit == Long.MAX_VALUE ? Long.MAX_VALUE : Math.max(0, limit - used); }

    public Stats stats() {
        lock.lock();
        try {
            long issued = 0, destroyed = 0, tin = 0, tout = 0;
            Map<String, PluginFlow> flows = new LinkedHashMap<>();
            for (Counter c : policy.counters().values()) {
                issued += c.issued; destroyed += c.destroyed; tin += c.treasuryIn; tout += c.treasuryOut;
                flows.put(c.display, new PluginFlow(c.issued, c.destroyed, c.treasuryIn, c.treasuryOut, c.transferred));
            }
            long supply = -mint.balance, dormantSum = 0;
            Activity act = activity;
            for (UUID u : act.dormant()) { Acct a = byId.get(u); if (a != null && a.kind() == AccountKind.PLAYER) dormantSum += a.balance; }
            return new Stats(supply, supply - dormantSum, treasury.balance, issued, destroyed, tin, tout, flows,
                    act.playersToday(), act.minutesToday(), act.avg7d(), dayIndex, weekIndex);
        } finally { lock.unlock(); }
    }

    /** Copies balances and queues a barrier atomically, so a later database read can be compared exactly. */
    public Snapshot snapshot() {
        lock.lock();
        try {
            Map<UUID, Long> copy = new HashMap<>();
            byId.forEach((id, a) -> copy.put(id, a.balance));
            CompletableFuture<Void> b = new CompletableFuture<>();
            sink.accept(new Barrier(b));
            return new Snapshot(copy, lastTx, b);
        } finally { lock.unlock(); }
    }

    /** Completes once everything applied so far is in the database. */
    public CompletableFuture<Void> barrier() {
        CompletableFuture<Void> b = new CompletableFuture<>();
        lock.lock();
        try { sink.accept(new Barrier(b)); } finally { lock.unlock(); }
        return b;
    }

    /** The transaction a plugin's reference was used for, if any. */
    public Long referenceTx(String plugin, String reference) {
        lock.lock();
        try { return refs.get(plugin.toLowerCase(Locale.ROOT) + '\0' + reference); } finally { lock.unlock(); }
    }

    /** Every account, including the Mint and Treasury. */
    public List<Acct> accounts() { return new ArrayList<>(byId.values()); }

    /** Resets the per-day counters at an economic day boundary. */
    public void newDay(long day, long week) {
        lock.lock();
        try { policy.reset(); dayIndex = day; weekIndex = week; } finally { lock.unlock(); }
    }

    /** Loads today's persisted counter rows at startup. */
    public void restoreCounter(String plugin, long[] c) {
        Counter x = policy.counter(plugin.toLowerCase(Locale.ROOT), plugin);
        x.issued = c[0]; x.destroyed = c[1]; x.treasuryIn = c[2]; x.treasuryOut = c[3]; x.transferred = c[4];
    }
}
