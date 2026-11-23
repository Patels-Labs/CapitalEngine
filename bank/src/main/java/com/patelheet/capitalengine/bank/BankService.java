package com.patelheet.capitalengine.bank;

import com.patelheet.capitalengine.api.Account;
import com.patelheet.capitalengine.api.CapitalApi;
import com.patelheet.capitalengine.api.Failure;
import com.patelheet.capitalengine.api.Posting;
import com.patelheet.capitalengine.api.Severity;
import com.patelheet.capitalengine.api.TransactionBuilder;
import com.patelheet.capitalengine.api.TxRecord;
import com.patelheet.capitalengine.api.TxResult;
import com.patelheet.capitalengine.api.service.Indicator;
import com.patelheet.capitalengine.api.service.MonetaryPolicy;
import com.patelheet.capitalengine.api.service.Unit;
import com.patelheet.capitalengine.bank.math.BankMath;
import com.patelheet.capitalengine.bank.storage.Store;
import com.patelheet.capitalengine.bank.storage.Store.Loan;
import com.patelheet.capitalengine.bank.storage.Store.Payment;
import com.patelheet.capitalengine.bank.storage.Store.Upkeep;
import com.patelheet.capitalengine.bank.upkeep.ClaimSource;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Everything the GUIs, commands and simulator do goes through here (bank/Plan.md §5.6).
 * Every method runs on one worker thread, so nothing needs locks and the main thread never waits on the database.
 * The worker may block on API futures (they complete on core threads).
 * ponytail: one worker for the whole bank; split per player if a very large server needs more throughput.
 */
public final class BankService implements AutoCloseable {
    /** Outcome of a deposit, withdrawal or repayment. {@code error} is a messages.yml key, or null when it worked. */
    public record Result(long amount, String error) {
        static Result err(String key) { return new Result(0, key); }
    }

    /** What a player may borrow. {@code reason} is null when they may, else a messages.yml suffix ("loan-" + reason). */
    public record LoanOffer(long limit, double weeklyRate, int termDays, String reason) {}

    public record LoanResult(String error, long loanId, BankMath.Terms terms) {
        static LoanResult err(String key) { return new LoanResult(key, 0, null); }
    }

    public record UpkeepView(long area, long free, long daily, long due, boolean frozen) {}

    /** Taken on the main thread and handed to the worker: everyone's claimed area and who is online. */
    public record Snapshot(Map<UUID, Long> areas, Set<UUID> online) {}

    /** Core can't reach its database: stop the run and try again later. */
    private static final class Paused extends RuntimeException { Paused() { super(null, null, false, false); } }

    /** ponytail: core's {@code activity.dormant-after-days} isn't in the API, so the bank uses core's default; read it from the API once it is there. */
    static final int DORMANT_DAYS = 60;

    final Plugin plugin;
    final CapitalApi api;
    final Store store;
    final Messages msg;
    final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "CapitalBank-worker"));
    final List<ClaimSource> sources = new CopyOnWriteArrayList<>();
    final Set<UUID> frozen = ConcurrentHashMap.newKeySet();           // owners whose land is frozen, for the freeze listener
    final Map<UUID, Long> upkeepDue = new ConcurrentHashMap<>();      // owed upkeep, for placeholders and "coins arrived"
    final Map<UUID, Long> loanLeft = new ConcurrentHashMap<>();
    final Map<UUID, Long> behind = new ConcurrentHashMap<>();         // loan arrears
    private final Map<UUID, Long> lastAuto = new ConcurrentHashMap<>();
    private final Set<UUID> autoQueued = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> greeted = new ConcurrentHashMap<>();
    volatile List<Indicator> indicators = List.of();
    volatile long today;

    final double savingsWeekly, loanWeekly, maxSavingsWeekly, maxLoanWeekly, growth;
    final long interestCap, savingsBudget, startLimit, maxLimit, loanBookMax, freeBlocks, per100, minPlayMinutes;
    final int termDays, writeOffDays, cooldownDays, freezeAfterDays, pauseOfflineDays;
    final boolean upkeepEnabled;

    public BankService(Plugin plugin, CapitalApi api, Store store, Messages msg, FileConfiguration cfg) {
        this.plugin = plugin; this.api = api; this.store = store; this.msg = msg;
        savingsWeekly = cfg.getDouble("rates.savings-weekly", 0.02);
        loanWeekly = cfg.getDouble("rates.loan-weekly", 0.05);
        maxSavingsWeekly = cfg.getDouble("rates.max-savings-weekly", 0.05);
        maxLoanWeekly = cfg.getDouble("rates.max-loan-weekly", 0.15);
        interestCap = coins(cfg, "savings.interest-cap", "10000");
        savingsBudget = coins(cfg, "savings.savings-daily-budget", "2000");
        startLimit = coins(cfg, "loans.start-limit", "500");
        growth = cfg.getDouble("loans.growth", 0.5);
        maxLimit = coins(cfg, "loans.max-limit", "5000");
        termDays = Math.max(1, cfg.getInt("loans.term-days", 7));
        loanBookMax = coins(cfg, "loans.loan-book-max", "200000");
        minPlayMinutes = Math.round(cfg.getDouble("loans.min-playtime-hours", 2) * 60);
        writeOffDays = cfg.getInt("loans.write-off-days", 28);
        cooldownDays = cfg.getInt("loans.cooldown-days", 14);
        upkeepEnabled = cfg.getBoolean("upkeep.enabled", true);
        freeBlocks = cfg.getLong("upkeep.free-blocks", 10_000);
        per100 = coins(cfg, "upkeep.per-100-blocks", "1");
        freezeAfterDays = cfg.getInt("upkeep.freeze-after-days", 3);
        pauseOfflineDays = cfg.getInt("upkeep.pause-after-offline-days", 14);
        today = api.stats().dayIndex();
    }

    private long coins(FileConfiguration cfg, String key, String def) { return api.currency().parse(cfg.getString(key, def)); }

    // ---- plumbing ----------------------------------------------------------------------------------------------

    <T> CompletableFuture<T> run(Supplier<T> s) {
        return CompletableFuture.supplyAsync(() -> {
            try { return s.get(); }
            catch (RuntimeException e) { plugin.getLogger().log(Level.SEVERE, "Bank error", e); throw e; }
        }, worker);
    }

    /** Completes once everything queued so far has finished (for tests and the simulator). */
    public CompletableFuture<Void> idle() { return run(() -> null); }

    Account wallet(UUID p) { return api.accounts().player(p); }

    /** The player's savings: a core account only this plugin can take money out of. */
    Account savingsAccount(UUID p) { return api.accounts().plugin(plugin, "savings:" + p); }

    String coins(long n) { return api.currency().format(n); }

    static boolean ok(TxResult r) { return r.success() || r.failure() == Failure.DUPLICATE_REFERENCE; }

    static String errKey(Failure f) {
        return switch (f) {
            case INSUFFICIENT_FUNDS -> "err-funds";
            case POLICY_LIMIT, DENIED -> "err-limit";
            case ECONOMY_PAUSED -> "err-paused";
            default -> "err-other";
        };
    }

    private boolean found(String reference) { return api.findByReference(plugin, reference).join().isPresent(); }

    /** A chat line to a player if they are online (from any thread). */
    void tell(UUID p, String key, Object... kv) {
        plugin.getServer().getGlobalRegionScheduler().run(plugin, t -> {
            Player pl = plugin.getServer().getPlayer(p);
            if (pl != null) pl.getScheduler().run(plugin, x -> pl.sendMessage(msg.get("prefix").append(msg.get(key, kv))), null);
        });
    }

    // ---- rates (§5.1) ------------------------------------------------------------------------------------------

    private OptionalDouble policy(boolean savings) {
        Optional<MonetaryPolicy> m = api.monetaryPolicy();   // looked up every time, so a policy that appears later is followed without a restart
        return m.isEmpty() ? OptionalDouble.empty() : OptionalDouble.of(savings ? m.get().savingsRatePerDay() : m.get().loanRatePerDay());
    }

    public double savingsPerDay() { return BankMath.perDay(policy(true), savingsWeekly, maxSavingsWeekly); }

    public double loanPerDay() { return BankMath.perDay(policy(false), loanWeekly, maxLoanWeekly); }

    // ---- savings (§5.2) ----------------------------------------------------------------------------------------

    /** The player's savings balance (instant, any thread). */
    public long savings(UUID p) {
        return api.accounts().find(plugin.getName().toLowerCase(Locale.ROOT) + ":savings:" + p).map(Account::balance).orElse(0L);
    }

    /** Wallet → savings. {@code amount} ≤ 0 means everything in the wallet. */
    public CompletableFuture<Result> deposit(UUID p, long amount) {
        return run(() -> {
            long amt = amount > 0 ? amount : wallet(p).balance();
            if (amt <= 0) return Result.err("err-funds");
            store.markSaver(p, today);
            TxResult r = api.transaction(plugin, "bank.deposit", msg.plain("memo-deposit")).actor(p).move(wallet(p), savingsAccount(p), amt).commit().join();
            return ok(r) ? new Result(amt, null) : Result.err(errKey(r.failure()));
        });
    }

    /** Savings → wallet. {@code amount} ≤ 0 means all savings. */
    public CompletableFuture<Result> withdraw(UUID p, long amount) {
        return run(() -> {
            long amt = amount > 0 ? amount : savings(p);
            if (amt <= 0 || amt > savings(p)) return Result.err("err-savings");
            TxResult r = api.transaction(plugin, "bank.withdraw", msg.plain("memo-withdraw")).actor(p).move(savingsAccount(p), wallet(p), amt).commit().join();
            return ok(r) ? new Result(amt, null) : Result.err(errKey(r.failure()));
        });
    }

    /**
     * Daily interest, paid into each savings account so it compounds. Everyone is scaled down equally when the budget is short;
     * a policy limit from core stops the run (the rest get nothing today). References make a re-run pay nobody twice.
     */
    private void payInterest(long day) {
        double perDay = savingsPerDay();
        List<UUID> savers = store.savers();
        long[] owed = new long[savers.size()];
        long total = 0;
        for (int i = 0; i < owed.length; i++) { owed[i] = BankMath.owed(savings(savers.get(i)), interestCap, perDay); total += owed[i]; }
        OptionalLong fromPolicy = api.monetaryPolicy().map(m -> m.budget("savings")).orElse(OptionalLong.empty());
        long budget = fromPolicy.isPresent() ? fromPolicy.getAsLong() : savingsBudget;
        long[] pay = BankMath.scale(owed, budget);
        String memo = msg.plain("memo-interest");
        long paid = 0;
        boolean limited = false;
        for (int from = 0; from < savers.size() && !limited; from += 100) {   // 100 in flight at a time, so a limit stops the run within one batch
            List<Integer> who = new ArrayList<>();
            List<CompletableFuture<TxResult>> sent = new ArrayList<>();
            for (int i = from; i < Math.min(from + 100, savers.size()); i++) {
                if (pay[i] <= 0) continue;
                who.add(i);
                sent.add(api.transaction(plugin, "bank.interest", memo).reference(interestRef(day, savers.get(i))).serverPay(savingsAccount(savers.get(i)), pay[i]).commit());
            }
            boolean paused = false;
            for (int k = 0; k < sent.size(); k++) {
                TxResult r = sent.get(k).join();
                UUID u = savers.get(who.get(k));
                if (r.success()) paid += pay[who.get(k)];
                else if (r.failure() == Failure.DUPLICATE_REFERENCE) paid += ledgerAmount(interestRef(day, u), savingsAccount(u).id());   // a re-run: count what was really paid
                else if (r.failure() == Failure.POLICY_LIMIT) limited = true;
                else if (r.failure() == Failure.ECONOMY_PAUSED) paused = true;
            }
            if (paused) throw new Paused();
        }
        if (limited) api.alert(plugin, Severity.INFO, "bank.interest_limit", "Savings interest stopped early today: CapitalBank reached its daily limit in CapitalEngine's config.");
        store.saveInterest(day, total, paid, BankMath.scaleFactor(total, budget));
    }

    static String interestRef(long day, UUID p) { return "int:" + day + ":" + p; }

    /** How much a committed transaction paid into {@code account}, or 0 if there is no such transaction. */
    private long ledgerAmount(String reference, UUID account) {
        Optional<TxRecord> rec = api.findByReference(plugin, reference).join();
        return rec.map(t -> t.postings().stream().filter(x -> x.accountId().equals(account)).mapToLong(Posting::amount).sum()).orElse(0L);
    }

    // ---- loans (§5.3) ------------------------------------------------------------------------------------------

    /** What borrowing {@code amount} would cost at today's rate. */
    public BankMath.Terms preview(long amount) { return BankMath.terms(amount, loanPerDay(), termDays); }

    /** {@code playedMinutes} is the player's playtime, read on the main thread by the caller. */
    public CompletableFuture<LoanOffer> offer(UUID p, long playedMinutes) { return run(() -> offerNow(p, playedMinutes)); }

    private LoanOffer offerNow(UUID p, long played) {
        Store.Credit c = store.credit(p, startLimit);
        String reason = store.hasLoan(p) ? "has-loan"
                : c.pausedUntilDay() != null && c.pausedUntilDay() > today ? "cooldown"
                : played < minPlayMinutes ? "playtime" : null;
        return new LoanOffer(c.limit(), loanPerDay() * 7, termDays, reason);
    }

    /**
     * Two-phase (rule 7): a PENDING loan row, then the payout with reference {@code loan:<id>:payout}, then ACTIVE or FAILED.
     * The payout creates the coins, like real bank lending; repayments destroy them again.
     */
    public CompletableFuture<LoanResult> borrow(UUID p, long amount, long playedMinutes) {
        return run(() -> {
            LoanOffer o = offerNow(p, playedMinutes);
            if (o.reason() != null) return LoanResult.err("loan-" + o.reason());
            if (amount <= 0) return LoanResult.err("bad-amount");
            if (amount > o.limit()) return LoanResult.err("loan-over-limit");
            if (store.outstandingPrincipal() + amount > loanBookMax) return LoanResult.err("loan-book-full");
            BankMath.Terms t = preview(amount);
            long id = store.insertLoan(p, t, today, "PENDING");
            TxResult r = api.transaction(plugin, "bank.loan_payout", msg.plain("memo-loan-payout")).reference("loan:" + id + ":payout").actor(p)
                    .issue(wallet(p), amount).commit().join();
            if (!ok(r)) {
                store.loanStatus(id, "FAILED");
                return LoanResult.err(r.failure() == Failure.POLICY_LIMIT ? "loan-book-full" : errKey(r.failure()));
            }
            store.loanStatus(id, "ACTIVE");
            cache(store.loan(id));
            return new LoanResult(null, id, t);
        });
    }

    /** Early repayment. {@code amount} ≤ 0 means everything left, or as much of it as the wallet holds. Remaining interest isn't forgiven. */
    public CompletableFuture<Result> repay(UUID p, long amount) {
        return run(() -> {
            Loan l = store.openLoan(p);
            if (l == null) return Result.err("no-loan");
            long w = wallet(p).balance();
            long amt = amount > 0 ? Math.min(amount, l.left()) : Math.min(l.left(), w);
            if (amt <= 0 || amt > w) return Result.err("err-funds");
            String err = pay(l, "PAY", today, 0, amt, payRef(l));
            return err == null ? new Result(amt, null) : Result.err(err);
        });
    }

    private String payRef(Loan l) { return "loan:" + l.id() + ":pay:" + (store.paymentCount(l.id()) + 1); }

    public CompletableFuture<Optional<Loan>> loan(UUID p) { return run(() -> Optional.ofNullable(store.openLoan(p))); }

    /**
     * Two-phase: the PENDING payment row holds the exact amounts before the money moves, so a crash is finished from the row alone.
     * The principal part is destroyed and the interest goes to the Treasury, in one transaction. Returns null when it worked, else an error key.
     */
    private String pay(Loan l, String kind, long day, long due, long take, String ref) {
        long[] s = BankMath.split(take, l.principalLeft(), l.interestLeft());
        long id = store.insertPayment(l.id(), day, kind, due, s[0], s[1], ref);
        TransactionBuilder tx = api.transaction(plugin, "bank.loan_repay", msg.plain("memo-loan-repay")).reference(ref).actor(l.uuid());
        if (s[0] > 0) tx.destroy(wallet(l.uuid()), s[0]);
        if (s[1] > 0) tx.move(wallet(l.uuid()), api.treasury(), s[1]);
        TxResult r = tx.commit().join();
        if (ok(r)) { settle(store.payment(id)); return null; }
        store.deletePayment(id);   // nothing moved, so nothing to keep
        if (r.failure() == Failure.ECONOMY_PAUSED && kind.equals("DAY")) throw new Paused();
        return errKey(r.failure());
    }

    private void settle(Payment pm) {
        store.tx(() -> { apply(store.loan(pm.loanId()), pm.kind(), pm.day(), pm.due(), pm.principal(), pm.interest()); store.paymentDone(pm.id()); });
    }

    /**
     * The loan after a payment (or, for a day with nothing to take, after none). A daily shortfall becomes arrears and the loan OVERDUE,
     * which only pauses borrowing: no penalty, no extra interest. A loan paid off with no missed days raises the credit limit.
     */
    private Loan apply(Loan l, String kind, long day, long due, long principal, long interest) {
        boolean daily = kind.equals("DAY");
        long take = principal + interest, paidP = l.paidPrincipal() + principal, paidI = l.paidInterest() + interest;
        long left = l.principal() + l.interest() - paidP - paidI;
        long arrears = left == 0 ? 0 : daily ? Math.max(0, due - take) : Math.max(0, l.arrears() - take);
        int overdue = arrears == 0 ? 0 : daily ? l.overdueDays() + 1 : l.overdueDays();
        boolean missed = l.missed() || arrears > 0;
        String status = left == 0 ? "PAID" : arrears > 0 ? "OVERDUE" : "ACTIVE";
        Loan n = new Loan(l.id(), l.uuid(), l.principal(), l.interest(), l.instalment(), l.termDays(), l.startDay(), paidP, paidI, arrears, status,
                overdue, missed, daily ? day : l.lastDay(), left == 0 ? Long.valueOf(Math.max(day, today)) : null);
        store.updateLoan(n);
        if (left == 0 && !missed) {
            Store.Credit c = store.credit(l.uuid(), startLimit);
            store.saveCredit(new Store.Credit(l.uuid(), BankMath.grow(c.limit(), growth, maxLimit), c.cleanRepayments() + 1, c.pausedUntilDay()));
        }
        cache(n);
        return n;
    }

    /** Each day: one instalment plus anything behind, never more than the wallet holds. Dormant players' loans wait for them. */
    private void collectLoans(long day, Snapshot s) {
        for (Loan l : store.loans("ACTIVE", "OVERDUE")) {
            if (l.lastDay() >= day || away(l.uuid(), day, s, DORMANT_DAYS)) continue;
            long due = BankMath.due(l.left(), l.instalment(), l.arrears());
            long take = Math.min(due, wallet(l.uuid()).balance());
            Loan after;
            if (take > 0 && pay(l, "DAY", day, due, take, "loan:" + l.id() + ":day:" + day) == null) after = store.loan(l.id());
            else { Loan[] box = new Loan[1]; store.tx(() -> box[0] = apply(l, "DAY", day, due, 0, 0)); after = box[0]; }   // nothing taken: record what's behind
            if (!after.status().equals("OVERDUE")) continue;
            if (after.overdueDays() > writeOffDays) writeOff(after, day);
            else tell(after.uuid(), "loan-behind", "amount", coins(after.arrears()));
        }
    }

    /** The unpaid principal stays in circulation as created money (the ledger already shows it); the limit resets and a cooldown starts. */
    private void writeOff(Loan l, long day) {
        store.tx(() -> {
            store.updateLoan(new Loan(l.id(), l.uuid(), l.principal(), l.interest(), l.instalment(), l.termDays(), l.startDay(), l.paidPrincipal(),
                    l.paidInterest(), l.arrears(), "WRITTEN_OFF", l.overdueDays(), true, l.lastDay(), day));
            store.saveCredit(new Store.Credit(l.uuid(), startLimit, 0, day + cooldownDays));
        });
        loanLeft.remove(l.uuid());
        behind.remove(l.uuid());
        api.alert(plugin, Severity.INFO, "bank.loan_written_off", "Loan #" + l.id() + " of " + wallet(l.uuid()).name() + " was written off; "
                + coins(l.principalLeft()) + " of lent coins stay in circulation.");
        tell(l.uuid(), "loan-written-off");
    }

    /** {@code /bank admin forgive}: writes the player's loan off now. */
    public CompletableFuture<Boolean> forgive(UUID p) {
        return run(() -> {
            Loan l = store.openLoan(p);
            if (l == null) return false;
            plugin.getLogger().info("Loan #" + l.id() + " of " + p + " forgiven by an admin.");
            writeOff(l, today);
            return true;
        });
    }

    public CompletableFuture<List<Loan>> loansOf(UUID p) { return run(() -> store.loansOf(p)); }

    public CompletableFuture<List<Loan>> openLoans() { return run(() -> store.loans("ACTIVE", "OVERDUE", "PENDING")); }

    private void cache(Loan l) {
        if (l != null && l.open()) {
            loanLeft.put(l.uuid(), l.left());
            if (l.arrears() > 0) behind.put(l.uuid(), l.arrears()); else behind.remove(l.uuid());
        } else if (l != null) { loanLeft.remove(l.uuid()); behind.remove(l.uuid()); }
    }

    // ---- land upkeep (§5.4) ------------------------------------------------------------------------------------

    /** What the upkeep screen shows. {@code area} is read on the main thread by the caller. */
    public CompletableFuture<UpkeepView> upkeep(UUID p, long area) {
        return run(() -> {
            Upkeep u = store.upkeep(p);
            return new UpkeepView(area, freeBlocks, BankMath.upkeep(area, freeBlocks, per100), u == null ? 0 : u.due(), u != null && u.frozen());
        });
    }

    /** Each day: add the day's charge (not while the owner is on a long break), take what the wallet allows, then freeze or unfreeze. */
    private void chargeUpkeep(long day, Snapshot s) {
        if (!upkeepEnabled) return;
        Set<UUID> owners = new HashSet<>(s.areas().keySet());
        store.upkeepRows().forEach(u -> owners.add(u.uuid()));
        for (UUID p : owners) {
            Upkeep u = store.upkeep(p);
            if (u == null) u = new Upkeep(p, 0, 0, day - 1, false, 0, 0, 0);
            long area = s.areas().getOrDefault(p, 0L), daily = BankMath.upkeep(area, freeBlocks, per100);
            boolean wasFrozen = u.frozen();
            if (u.lastChargedDay() < day) {
                long charge = away(p, day, s, pauseOfflineDays) ? 0 : daily;
                u = new Upkeep(p, area, u.due() + charge, day, u.frozen(), u.lastWarnedDay(), u.seq(), u.pending());
                store.saveUpkeep(u);
            }
            u = collectUpkeep(u, day);
            boolean frz = area > 0 && BankMath.frozen(u.due(), daily, freezeAfterDays);   // no land left (or its plugin removed): nothing to freeze
            boolean warn = u.due() > 0 && u.lastWarnedDay() < day && s.online().contains(p);
            u = new Upkeep(p, area, u.due(), u.lastChargedDay(), frz, warn ? day : u.lastWarnedDay(), u.seq(), 0);
            store.saveUpkeep(u);
            cacheUpkeep(u);
            if (frz && !wasFrozen) tell(p, "upkeep-frozen", "amount", coins(u.due()));
            else if (!frz && wasFrozen && u.due() == 0) tell(p, "upkeep-unfrozen");
            else if (warn) tell(p, "upkeep-behind", "amount", coins(u.due()));
        }
    }

    /** Takes as much owed upkeep as the wallet holds. Two-phase: the amount is saved as {@code pending} under reference {@code upk:<uuid>:<seq>} first. */
    private Upkeep collectUpkeep(Upkeep u, long day) {
        if (u.due() <= 0) return u;
        long take = Math.min(u.due(), wallet(u.uuid()).balance());
        if (take <= 0) return u;
        Upkeep pending = new Upkeep(u.uuid(), u.area(), u.due(), u.lastChargedDay(), u.frozen(), u.lastWarnedDay(), u.seq() + 1, take);
        store.saveUpkeep(pending);
        TxResult r = api.transaction(plugin, "bank.upkeep", msg.plain("memo-upkeep")).reference(upkeepRef(pending)).actor(u.uuid())
                .move(wallet(u.uuid()), api.treasury(), take).commit().join();
        if (ok(r)) return finishUpkeep(pending, day);
        Upkeep back = new Upkeep(u.uuid(), u.area(), u.due(), u.lastChargedDay(), u.frozen(), u.lastWarnedDay(), pending.seq(), 0);
        store.saveUpkeep(back);
        if (r.failure() == Failure.ECONOMY_PAUSED) throw new Paused();
        return back;   // the wallet changed meanwhile: take nothing now
    }

    private static String upkeepRef(Upkeep u) { return "upk:" + u.uuid() + ":" + u.seq(); }

    /** Applies a collection that went through. Land unfreezes the moment nothing is owed. */
    private Upkeep finishUpkeep(Upkeep u, long day) {
        long due = u.due() - u.pending();
        Upkeep done = new Upkeep(u.uuid(), u.area(), due, u.lastChargedDay(), u.frozen() && due > 0, u.lastWarnedDay(), u.seq(), 0);
        store.tx(() -> { store.saveUpkeep(done); store.addUpkeepCollected(day, u.pending()); });
        cacheUpkeep(done);
        return done;
    }

    /** {@code /bank admin unfreeze}: forgets what the player owes (the Treasury simply never receives it) and unfreezes their land. */
    public CompletableFuture<Long> unfreeze(UUID p) {
        return run(() -> {
            Upkeep u = store.upkeep(p);
            if (u == null) return 0L;
            Upkeep z = new Upkeep(p, u.area(), 0, u.lastChargedDay(), false, u.lastWarnedDay(), u.seq(), 0);
            store.saveUpkeep(z);
            cacheUpkeep(z);
            plugin.getLogger().info("Upkeep of " + p + " (" + coins(u.due()) + ") cleared and land unfrozen by an admin.");
            return u.due();
        });
    }

    private void cacheUpkeep(Upkeep u) {
        if (u.due() > 0) upkeepDue.put(u.uuid(), u.due()); else upkeepDue.remove(u.uuid());
        if (u.frozen()) frozen.add(u.uuid()); else frozen.remove(u.uuid());
    }

    // ---- coins arriving (§5.4): owed upkeep and loan arrears are taken as soon as there are coins ------------------

    /** Called (from any thread) when coins arrive in the wallet of a player who owes something. Debounced to once a minute per player. */
    void coinsArrived(UUID p) {
        long now = System.currentTimeMillis(), last = lastAuto.getOrDefault(p, 0L);
        if (now - last < 60_000) {
            if (autoQueued.add(p))   // run once more when the minute is up, so these coins aren't left waiting for the next day
                plugin.getServer().getAsyncScheduler().runDelayed(plugin, t -> { autoQueued.remove(p); coinsArrived(p); }, 60_000 - (now - last), TimeUnit.MILLISECONDS);
            return;
        }
        lastAuto.put(p, now);
        run(() -> { autoCollect(p); return null; });
    }

    boolean owes(UUID p) { return upkeepDue.containsKey(p) || behind.containsKey(p); }

    private void autoCollect(UUID p) {
        long day = today;
        try {
            Upkeep u = store.upkeep(p);
            if (u != null && u.due() > 0) {
                Upkeep after = collectUpkeep(u, day);
                if (u.frozen() && !after.frozen()) tell(p, "upkeep-unfrozen");
            }
            Loan l = store.openLoan(p);
            if (l != null && l.arrears() > 0) {
                long take = Math.min(l.arrears(), wallet(p).balance());
                if (take > 0 && pay(l, "PAY", day, 0, take, payRef(l)) == null && store.loan(l.id()).arrears() == 0) tell(p, "loan-caught-up");
            }
        } catch (Paused e) { /* the next coins or the next day try again */ }
    }

    // ---- players -----------------------------------------------------------------------------------------------

    /** Whether the player has been away (offline) for at least {@code days} days. A player the bank has never seen counts as seen today. */
    private boolean away(UUID p, long day, Snapshot s, int days) {
        if (s.online().contains(p)) return false;
        Long seen = store.lastSeen(p);
        if (seen == null) { store.seen(p, day); return false; }
        return day - seen >= days;
    }

    /** On join: remember the day, and say once a day what the player owes and how their savings grew. */
    public CompletableFuture<Void> joined(UUID p) {
        return run(() -> {
            long day = today;
            store.seen(p, day);
            Upkeep u = store.upkeep(p);
            if (u != null && u.due() > 0) tell(p, u.frozen() ? "upkeep-frozen" : "upkeep-behind", "amount", coins(u.due()));
            Loan l = store.openLoan(p);
            if (l != null && l.arrears() > 0) tell(p, "loan-behind", "amount", coins(l.arrears()));
            if (greeted.put(p, day) instanceof Long g && g == day) return null;
            Account acct = savingsAccount(p);
            Instant since = Instant.now().minus(7, ChronoUnit.DAYS);
            long grew = api.history(acct, 50, Long.MAX_VALUE).join().stream().filter(t -> t.type().equals("bank.interest") && t.time().isAfter(since))
                    .flatMap(t -> t.postings().stream()).filter(x -> x.accountId().equals(acct.id())).mapToLong(Posting::amount).sum();
            if (grew > 0) tell(p, "savings-grew", "amount", coins(grew));
            return null;
        });
    }

    public void quit(UUID p) { run(() -> { store.seen(p, today); return null; }); }

    // ---- the day, the week and startup -------------------------------------------------------------------------

    /** On each Day event. Payouts and charges only on days that aren't catch-up; a day is run exactly once, and resumed if interrupted. */
    public CompletableFuture<Void> onDay(long day, boolean catchUp, Snapshot s) { return run(() -> { runDay(day, catchUp, s); return null; }); }

    private void runDay(long day, boolean catchUp, Snapshot s) {
        today = Math.max(today, day);
        Store.DayRun before = store.dayRun(day);
        if (before != null && before.done()) return;
        store.startDay(day, catchUp);   // durable marker first: a crash from here on resumes this day on the next start
        for (UUID p : s.online()) store.seen(p, day);
        if (!store.dayRun(day).catchUp()) {
            try {
                collectLoans(day, s);
                chargeUpkeep(day, s);
                payInterest(day);
            } catch (Paused e) {
                plugin.getLogger().warning("CapitalEngine's database is unavailable; the bank will run day " + day + " again in 5 minutes.");
                plugin.getServer().getAsyncScheduler().runDelayed(plugin, t -> onDay(day, catchUp, s), 5, TimeUnit.MINUTES);
                return;
            }
        }
        store.finishDay(day);
        checkNow();
        refreshIndicators(day);
    }

    /** On each Week event (not catch-up): more than 10% of loans written off in a week raises a warning. */
    public void onWeek(boolean catchUp) {
        if (catchUp) return;
        run(() -> {
            long since = today - 7, off = store.writtenOffSince(since), seen = store.loansSeenSince(since);
            if (seen > 0 && off * 10 > seen)
                api.alert(plugin, Severity.WARN, "bank.write_offs", off + " of " + seen + " loans were written off this week. Consider a lower start limit.");
            return null;
        });
    }

    /** Run once at startup: finishes or rolls back anything in flight when the server stopped (rule 7), and resumes an interrupted day. */
    public CompletableFuture<Void> recover(Snapshot s) {
        return run(() -> {
            for (Loan l : store.loans("PENDING")) store.loanStatus(l.id(), found("loan:" + l.id() + ":payout") ? "ACTIVE" : "FAILED");
            for (Payment pm : store.pendingPayments()) { if (found(pm.reference())) settle(pm); else store.deletePayment(pm.id()); }
            for (Upkeep u : store.upkeepRows()) {
                if (u.pending() <= 0) continue;
                if (found(upkeepRef(u))) finishUpkeep(u, today);
                else store.saveUpkeep(new Upkeep(u.uuid(), u.area(), u.due(), u.lastChargedDay(), u.frozen(), u.lastWarnedDay(), u.seq(), 0));
            }
            for (Store.DayRun d : store.unfinishedDays()) runDay(d.day(), d.catchUp(), s);
            for (Loan l : store.loans("ACTIVE", "OVERDUE")) cache(l);
            for (Upkeep u : store.upkeepRows()) cacheUpkeep(u);
            refreshIndicators(today);
            return null;
        });
    }

    // ---- the bank check (§6) and indicators (§5.5) -------------------------------------------------------------

    /** {@code /bank admin check}: problems found, empty when everything matches the ledger. */
    public CompletableFuture<List<String>> check() { return run(this::checkNow); }

    /**
     * For every open loan, the payout and every collection must be in the ledger, and the principal repaid must equal the coins
     * those collections destroyed. Yesterday's interest the bank recorded must equal what the ledger paid. Any mismatch is CRITICAL.
     */
    private List<String> checkNow() {
        List<String> problems = new ArrayList<>();
        UUID treasury = api.treasury().id();
        for (Loan l : store.loans("ACTIVE", "OVERDUE")) {
            if (!found("loan:" + l.id() + ":payout")) problems.add("loan #" + l.id() + ": its payout is not in the ledger");
            long destroyed = 0;
            for (Payment pm : store.donePayments(l.id())) {
                Optional<TxRecord> rec = api.findByReference(plugin, pm.reference()).join();
                if (rec.isEmpty()) { problems.add("loan #" + l.id() + ": payment " + pm.reference() + " is not in the ledger"); continue; }
                UUID payer = wallet(l.uuid()).id();
                for (Posting x : rec.get().postings()) if (x.amount() > 0 && !x.accountId().equals(treasury) && !x.accountId().equals(payer)) destroyed += x.amount();
            }
            if (destroyed != l.paidPrincipal()) problems.add("loan #" + l.id() + ": " + l.paidPrincipal() + " principal repaid but the ledger destroyed " + destroyed);
        }
        Store.DayRun last = store.dayRun(today);
        if (last != null && last.done() && !last.catchUp()) {
            long ledger = 0;
            for (UUID p : store.savers()) ledger += ledgerAmount(interestRef(last.day(), p), savingsAccount(p).id());
            if (ledger != last.interestPaid()) problems.add("day " + last.day() + ": the bank paid " + last.interestPaid() + " interest but the ledger shows " + ledger);
        }
        if (!problems.isEmpty()) api.alert(plugin, Severity.CRITICAL, "bank.check_failed", "Bank check: " + String.join("; ", problems));
        return problems;
    }

    private void refreshIndicators(long day) {
        long total = 0, savers = 0;
        for (UUID p : store.savers()) { long b = savings(p); total += b; if (b > 0) savers++; }
        Store.DayRun d = store.dayRun(day);
        indicators = List.of(
                new Indicator("total_savings", "Total savings", total, Unit.COINS),
                new Indicator("savers", "Savers", savers, Unit.COUNT),
                new Indicator("interest_paid_today", "Interest paid today", d == null ? 0 : d.interestPaid(), Unit.COINS),
                new Indicator("interest_scale", "Interest paid of interest owed", d == null ? 100 : d.scale() * 100, Unit.PERCENT),
                new Indicator("loan_book", "Coins lent out", store.outstandingPrincipal(), Unit.COINS),
                new Indicator("loans_active", "Loans on time", store.countLoans("ACTIVE"), Unit.COUNT),
                new Indicator("loans_overdue", "Loans behind", store.countLoans("OVERDUE"), Unit.COUNT),
                new Indicator("loans_written_off", "Loans written off", store.countLoans("WRITTEN_OFF"), Unit.COUNT),
                new Indicator("upkeep_collected_today", "Land upkeep collected today", d == null ? 0 : d.upkeepCollected(), Unit.COINS),
                new Indicator("upkeep_owed", "Land upkeep owed", store.totalUpkeepDue(), Unit.COINS),
                new Indicator("frozen_owners", "Frozen land owners", frozen.size(), Unit.COUNT));
    }

    @Override public void close() {
        worker.shutdown();
        try { worker.awaitTermination(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        store.close();
    }
}
