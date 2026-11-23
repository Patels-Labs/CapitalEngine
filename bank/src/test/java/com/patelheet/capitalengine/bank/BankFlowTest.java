package com.patelheet.capitalengine.bank;

import static org.junit.jupiter.api.Assertions.*;

import com.patelheet.capitalengine.api.Account;
import com.patelheet.capitalengine.api.CapitalApi;
import com.patelheet.capitalengine.api.Failure;
import com.patelheet.capitalengine.api.event.CapitalDayEvent;
import com.patelheet.capitalengine.api.service.IndicatorProvider;
import com.patelheet.capitalengine.api.service.MonetaryPolicy;
import com.patelheet.capitalengine.bank.math.BankMath;
import com.patelheet.capitalengine.bank.storage.Store;
import com.patelheet.capitalengine.bank.upkeep.ClaimSource;
import com.patelheet.capitalengine.core.CapitalEnginePlugin;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.bukkit.Location;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** The real CapitalEngine plus CapitalBank, without CapitalMarket: coins really move, the ledger and the bank agree, crashes recover. */
class BankFlowTest {
    ServerMock server;
    CapitalApi api;
    CapitalBankPlugin bank;
    BankService svc;
    Plugin tester;   // another plugin, for giving players coins and trying to break the rules
    PlayerMock samP, miaP, zedP;
    UUID sam, mia, zed;
    long d0;
    final Map<UUID, Long> land = new ConcurrentHashMap<>();
    final Set<UUID> trusted = ConcurrentHashMap.newKeySet();

    static <T> T await(CompletableFuture<T> f) throws Exception { return f.get(10, TimeUnit.SECONDS); }

    @BeforeEach void start() throws Exception {
        server = MockBukkit.mock();
        MockBukkit.loadWith(CapitalEnginePlugin.class, descriptor("CapitalEngine"));
        api = server.getServicesManager().load(CapitalApi.class);
        bank = MockBukkit.loadWith(CapitalBankPlugin.class, descriptor("CapitalBank"));
        tester = MockBukkit.createMockPlugin("Tester");
        samP = server.addPlayer("Sam"); miaP = server.addPlayer("Mia"); zedP = server.addPlayer("Zed");
        sam = samP.getUniqueId(); mia = miaP.getUniqueId(); zed = zedP.getUniqueId();
        server.getScheduler().performTicks(2);   // recover() runs next tick
        svc = bank.service();
        // a fake claim plugin: everything at x >= 0 is Sam's land; Mia can be trusted there
        svc.sources.add(new ClaimSource() {
            @Override public Map<UUID, Long> areas() { return Map.copyOf(land); }
            @Override public UUID ownerAt(Location loc) { return loc.getBlockX() >= 0 ? sam : null; }
            @Override public boolean trusted(Location loc, UUID player) { return trusted.contains(player); }
        });
        until(() -> bal(sam) > 0 && bal(mia) > 0 && bal(zed) > 0, "starter coins");
        await(svc.idle());
        for (PlayerMock p : new PlayerMock[]{samP, miaP, zedP}) empty(p.getUniqueId());
        d0 = api.stats().dayIndex();
    }

    static PluginDescriptionFile descriptor(String name) throws Exception {
        for (var url : java.util.Collections.list(BankFlowTest.class.getClassLoader().getResources("plugin.yml"))) {
            try (var in = url.openStream()) { var d = new PluginDescriptionFile(in); if (d.getName().equals(name)) return d; }
        }
        throw new IllegalStateException("no plugin.yml for " + name);
    }

    @AfterEach void stop() { MockBukkit.unmock(); }

    long c(String coins) { return api.currency().parse(coins); }
    long bal(UUID p) { return api.accounts().player(p).balance(); }
    Account wallet(UUID p) { return api.accounts().player(p); }

    void give(UUID p, String coins) throws Exception { assertTrue(await(api.issue(tester, wallet(p), c(coins), "test.give", "Test coins")).success()); }

    /** Destroys everything in the wallet, so tests start from zero. */
    void empty(UUID p) throws Exception { if (bal(p) > 0) assertTrue(await(api.destroy(tester, wallet(p), bal(p), "test.take", "Test")).success()); }

    void until(BooleanSupplier ok, String what) throws Exception {
        for (int i = 0; i < 200 && !ok.getAsBoolean(); i++) { server.getScheduler().performTicks(1); Thread.sleep(10); }
        assertTrue(ok.getAsBoolean(), "timed out waiting for " + what);
    }

    /** Runs economic day {@code d0 + n} (not catch-up). */
    void day(int n) throws Exception { await(svc.onDay(d0 + n, false, bank.snapshot())); }

    void played(PlayerMock p, int hours) { p.setStatistic(Statistic.PLAY_ONE_MINUTE, hours * 60 * 60 * 20); }

    Store.Loan loanOf(UUID p) throws Exception { return await(svc.loan(p)).orElse(null); }

    // ---- savings -----------------------------------------------------------------------------------------------

    @Test void savingsMoveInstantlyAndNoOtherPluginCanTakeThem() throws Exception {
        give(sam, "1000");
        assertNull(await(svc.deposit(sam, c("300"))).error());
        assertEquals(c("300"), svc.savings(sam));
        assertEquals(c("700"), bal(sam));
        assertEquals("err-savings", await(svc.withdraw(sam, c("301"))).error(), "can't take out more than is saved");
        assertEquals(c("300"), await(svc.withdraw(sam, 0)).amount(), "withdraw all");
        assertEquals(c("1000"), bal(sam));
        assertEquals(c("1000"), await(svc.deposit(sam, 0)).amount(), "deposit all");
        assertEquals(0, bal(sam));
        assertEquals("err-funds", await(svc.deposit(sam, c("1"))).error(), "never below zero");

        var r = await(api.transfer(tester, svc.savingsAccount(sam), wallet(mia), c("1"), "test.steal", "Nope"));
        assertEquals(Failure.NOT_ACCOUNT_OWNER, r.failure(), "only the bank can take money out of savings");
        assertEquals(c("1000"), svc.savings(sam));
    }

    @Test void interestFollowsConfigThenARegisteredPolicyWithoutARestart() throws Exception {
        give(sam, "20000"); give(mia, "5000");
        await(svc.deposit(sam, 0)); await(svc.deposit(mia, 0));
        assertEquals(0.02 / 7, svc.savingsPerDay(), 1e-12, "no MonetaryPolicy: config rate");
        long samOwed = BankMath.owed(c("20000"), c("10000"), 0.02 / 7), miaOwed = BankMath.owed(c("5000"), c("10000"), 0.02 / 7);
        day(1);
        assertEquals(c("20000") + samOwed, svc.savings(sam), "interest only on the first 10,000 coins, into savings");
        assertEquals(c("5000") + miaOwed, svc.savings(mia));
        assertTrue(await(svc.check()).isEmpty());

        // a policy appears (as CapitalMarket's would): a rate above the owner's max, and a small budget
        server.getServicesManager().register(MonetaryPolicy.class, new MonetaryPolicy() {
            @Override public double savingsRatePerDay() { return 0.5; }
            @Override public double loanRatePerDay() { return 0.001; }
            @Override public OptionalLong budget(String channel) { return channel.equals("savings") ? OptionalLong.of(1_000) : OptionalLong.empty(); }
        }, tester, ServicePriority.Normal);
        assertEquals(0.05 / 7, svc.savingsPerDay(), 1e-12, "the policy is followed at once, capped by max-savings-weekly");
        assertEquals(0.001, svc.loanPerDay(), 1e-12);
        long s1 = svc.savings(sam), m1 = svc.savings(mia);
        day(2);
        long gained = svc.savings(sam) - s1 + svc.savings(mia) - m1;
        assertTrue(gained > 0 && gained <= 1_000, "the budget caps the day's interest: " + gained);
        var scale = svc.indicators.stream().filter(i -> i.key().equals("interest_scale")).findFirst().orElseThrow().value();
        assertTrue(scale < 100, "everyone was scaled down equally");

        // a catch-up day, fired through the real event: no interest
        long s2 = svc.savings(sam);
        server.getPluginManager().callEvent(new CapitalDayEvent(d0 + 3, LocalDate.now(), true, api.stats()));
        await(svc.idle());
        assertEquals(s2, svc.savings(sam), "no interest for days the server was off");
        day(2);
        assertEquals(s2, svc.savings(sam), "a day runs once");
    }

    @Test void aDayInterruptedHalfwayResumesWithoutPayingAnyoneTwice() throws Exception {
        give(sam, "1000"); give(mia, "1000");
        await(svc.deposit(sam, 0)); await(svc.deposit(mia, 0));
        long owed = BankMath.owed(c("1000"), c("10000"), svc.savingsPerDay());
        // the run had started and paid Sam when the server stopped
        await(svc.run(() -> { svc.store.startDay(d0 + 1, false); return null; }));
        assertTrue(await(api.transaction(bank, "bank.interest", "Daily savings interest").reference(BankService.interestRef(d0 + 1, sam))
                .serverPay(svc.savingsAccount(sam), owed).commit()).success());
        await(svc.recover(bank.snapshot()));
        assertEquals(c("1000") + owed, svc.savings(sam), "Sam is not paid twice");
        assertEquals(c("1000") + owed, svc.savings(mia), "Mia gets hers");
        assertTrue(await(svc.check()).isEmpty(), "the recorded interest matches the ledger: " + await(svc.check()));
        day(1);
        assertEquals(c("1000") + owed, svc.savings(mia), "the finished day doesn't run again");
    }

    // ---- loans -------------------------------------------------------------------------------------------------

    @Test void aLoanCreatesMoneyAndFullRepaymentDestroysItExactly() throws Exception {
        assertEquals("playtime", await(svc.offer(sam, CapitalBankPlugin.playedMinutes(samP))).reason(), "new players wait 2 hours");
        played(samP, 3);
        long played = CapitalBankPlugin.playedMinutes(samP);
        give(sam, "25");   // enough for the interest
        long supply = api.stats().moneySupply(), treasury = api.treasury().balance();
        assertEquals("loan-over-limit", await(svc.borrow(sam, c("501"), played)).error());
        var r = await(svc.borrow(sam, c("500"), played));
        assertNull(r.error());
        assertEquals(c("75"), r.terms().instalment(), "500 at 5% a week over 7 days is 75 a day");
        assertEquals(supply + c("500"), api.stats().moneySupply(), "the loan creates the coins");
        assertEquals(c("525"), bal(sam));
        assertEquals("loan-has-loan", await(svc.borrow(sam, c("10"), played)).error(), "one loan at a time");

        for (int d = 1; d <= 7; d++) { day(d); assertTrue(bal(sam) >= 0); }
        assertEquals(0, bal(sam));
        assertNull(loanOf(sam), "paid off");
        assertEquals(supply, api.stats().moneySupply(), "the principal is destroyed again, exactly");
        assertEquals(treasury + c("25"), api.treasury().balance(), "the interest went to the Treasury");
        assertEquals(c("750"), await(svc.offer(sam, played)).limit(), "repaid on time: the limit grows by half");
        assertTrue(await(svc.check()).isEmpty());
    }

    @Test void aMissedPaymentOnlyPausesBorrowingAndCoinsArrivingCatchUp() throws Exception {
        played(miaP, 3);
        long played = CapitalBankPlugin.playedMinutes(miaP);
        await(svc.borrow(mia, c("500"), played));
        empty(mia);   // she spent it all
        day(1);
        Store.Loan l = loanOf(mia);
        assertEquals("OVERDUE", l.status());
        assertEquals(c("75"), l.arrears());
        assertEquals(c("525"), l.left(), "no penalty and no extra interest");
        assertEquals(0, bal(mia), "never below zero");
        assertEquals("has-loan", await(svc.offer(mia, played)).reason(), "borrowing is paused");

        give(mia, "100");   // coins arrive: what's behind is taken straight away
        until(() -> { try { return loanOf(mia).arrears() == 0; } catch (Exception e) { return false; } }, "arrears collected");
        l = loanOf(mia);
        assertEquals("ACTIVE", l.status());
        assertEquals(c("25"), bal(mia));
        assertEquals(c("450"), l.left());
        assertTrue(await(svc.check()).isEmpty(), "" + await(svc.check()));

        assertNull(await(svc.repay(mia, 0)).error(), "repay what she can");
        assertEquals(0, bal(mia));
        give(mia, "1000");
        until(() -> !svc.owes(mia), "caches settle");
        assertNull(await(svc.repay(mia, 0)).error());
        assertNull(loanOf(mia));
        assertEquals(c("500"), await(svc.offer(mia, played)).limit(), "a missed day means no limit growth");
    }

    @Test void aLoanNeverRepaidIsWrittenOffAndLeavesTheLedgerConsistent() throws Exception {
        played(zedP, 3);
        await(svc.borrow(zed, c("200"), CapitalBankPlugin.playedMinutes(zedP)));
        assertTrue(await(api.transfer(tester, wallet(zed), wallet(sam), c("200"), "test.spend", "Spent")).success());   // spent: the coins live on with Sam
        long supply = api.stats().moneySupply();
        for (int d = 1; d <= 28; d++) day(d);
        assertEquals("OVERDUE", loanOf(zed).status(), "28 days behind is still just behind");
        day(29);
        assertNull(loanOf(zed));
        assertEquals("WRITTEN_OFF", await(svc.loansOf(zed)).get(0).status());
        assertEquals(supply, api.stats().moneySupply(), "writing off moves no coins: the created ones stay in circulation");
        assertEquals(c("500"), await(svc.offer(zed, 999)).limit(), "the limit is back to the start");
        assertEquals("cooldown", await(svc.offer(zed, CapitalBankPlugin.playedMinutes(zedP))).reason());
        assertTrue(await(svc.check()).isEmpty());
    }

    @Test void aDormantPlayersLoanWaitsForThem() throws Exception {
        UUID ghost = UUID.randomUUID();
        give(ghost, "500");
        await(svc.borrow(ghost, c("100"), 999));
        await(svc.run(() -> { svc.store.seen(ghost, d0 - 70); return null; }));
        day(1);
        Store.Loan l = loanOf(ghost);
        assertEquals(0, l.paidPrincipal() + l.paidInterest(), "nothing collected while away 60+ days");
        assertEquals("ACTIVE", l.status(), "and the write-off clock doesn't move");
    }

    // ---- land upkeep -------------------------------------------------------------------------------------------

    @Test void upkeepFreezesAfterThreeDaysOwedAndUnfreezesWhenPaid() throws Exception {
        World w = server.addSimpleWorld("world");
        land.put(sam, 18_400L);   // 84 coins a day
        for (int d = 1; d <= 3; d++) day(d);
        assertEquals(c("252"), svc.upkeepDue.get(sam));
        assertFalse(svc.frozen.contains(sam), "three days owed: a warning, not frozen");
        day(4);
        assertTrue(svc.frozen.contains(sam));

        trusted.add(mia);
        assertTrue(broken(samP, w, 5), "the owner can't break blocks on frozen land");
        assertTrue(broken(miaP, w, 5), "nor can a trusted player");
        assertFalse(broken(zedP, w, 5), "visitors aren't affected");
        assertFalse(broken(samP, w, -5), "nor is the owner's building elsewhere");

        give(sam, "1000");   // coins arrive: the upkeep is taken and the land unfreezes
        until(() -> !svc.frozen.contains(sam), "unfrozen");
        assertEquals(c("1000") - c("336"), bal(sam));
        assertFalse(broken(samP, w, 5));
        assertEquals(0, svc.upkeepDue.getOrDefault(sam, 0L));
    }

    /** Whether breaking a block at x was blocked. */
    private boolean broken(PlayerMock p, World w, int x) {
        var e = new BlockBreakEvent(w.getBlockAt(x, 64, 5), p);
        server.getPluginManager().callEvent(e);
        return e.isCancelled();
    }

    @Test void upkeepPausesForLongBreaksAndClearsWhenTheLandIsGone() throws Exception {
        UUID ghost = UUID.randomUUID();
        land.put(ghost, 20_000L);
        land.put(sam, 20_000L);   // 100 coins a day each
        await(svc.run(() -> { svc.store.seen(ghost, d0 - 20); return null; }));
        for (int d = 1; d <= 4; d++) day(d);
        assertEquals(0, svc.upkeepDue.getOrDefault(ghost, 0L), "away 14+ days: no upkeep builds up");
        assertTrue(svc.frozen.contains(sam));
        land.remove(sam);   // the claim plugin was removed (or the land given up)
        day(5);
        assertFalse(svc.frozen.contains(sam), "no land: nothing to freeze");
        assertEquals(c("400"), svc.upkeepDue.get(sam), "what is owed is still owed");
        assertEquals(c("400"), await(svc.unfreeze(sam)), "an admin can clear it");
        assertFalse(svc.owes(sam));
    }

    // ---- crashes and the bank check ----------------------------------------------------------------------------

    @Test void inFlightRowsAreFinishedOrRolledBackOnStartup() throws Exception {
        give(sam, "1000");
        var terms = BankMath.terms(c("100"), svc.loanPerDay(), 7);
        long paidOut = await(svc.run(() -> svc.store.insertLoan(sam, terms, d0, "PENDING")));
        long never = await(svc.run(() -> svc.store.insertLoan(mia, terms, d0, "PENDING")));
        assertTrue(await(api.transaction(bank, "bank.loan_payout", "Loan").reference("loan:" + paidOut + ":payout").issue(wallet(sam), c("100")).commit()).success());

        long left = terms.total();
        long[] split = BankMath.split(c("40"), terms.principal(), terms.interest());
        long pay = await(svc.run(() -> svc.store.insertPayment(paidOut, d0, "PAY", 0, split[0], split[1], "loan:" + paidOut + ":pay:1")));
        assertTrue(await(api.transaction(bank, "bank.loan_repay", "Loan payment").reference("loan:" + paidOut + ":pay:1")
                .destroy(wallet(sam), split[0]).move(wallet(sam), api.treasury(), split[1]).commit()).success());
        long lost = await(svc.run(() -> svc.store.insertPayment(paidOut, d0, "PAY", 0, 1, 0, "loan:" + paidOut + ":pay:2")));

        await(svc.run(() -> { svc.store.saveUpkeep(new Store.Upkeep(mia, 20_000, c("50"), d0, false, 0, 1, c("20"))); return null; }));
        give(mia, "20");
        assertTrue(await(api.transaction(bank, "bank.upkeep", "Land upkeep").reference("upk:" + mia + ":1").move(wallet(mia), api.treasury(), c("20")).commit()).success());

        await(svc.recover(bank.snapshot()));
        assertEquals("ACTIVE", await(svc.run(() -> svc.store.loan(paidOut))).status(), "paid out: the loan is live");
        assertEquals("FAILED", await(svc.run(() -> svc.store.loan(never))).status(), "never paid out: dropped");
        assertEquals(left - c("40"), loanOf(sam).left(), "the repayment that went through counts");
        assertEquals("DONE", await(svc.run(() -> svc.store.payment(pay))).status());
        assertNull(await(svc.run(() -> svc.store.payment(lost))), "the one that never moved money is gone");
        assertEquals(c("30"), svc.upkeepDue.get(mia), "the upkeep taken before the crash counts");
        assertTrue(await(svc.check()).isEmpty());
    }

    @Test void theBankCheckPassesAfterAMonthAndCatchesAMismatch() throws Exception {
        played(samP, 3); played(miaP, 3); played(zedP, 3);
        give(sam, "3000"); give(mia, "300"); give(zed, "50");
        await(svc.deposit(sam, c("2000")));
        land.put(mia, 12_000L);
        for (int d = 1; d <= 30; d++) {
            for (UUID p : new UUID[]{sam, mia, zed}) if (loanOf(p) == null && d % 9 == 1) await(svc.borrow(p, c("300"), 999));
            if (d % 5 == 0) give(zed, "40");
            if (d % 7 == 0) await(svc.deposit(mia, c("10")));
            day(d);
            for (UUID p : new UUID[]{sam, mia, zed}) assertTrue(bal(p) >= 0 && svc.savings(p) >= 0);
        }
        await(svc.idle());
        assertEquals(java.util.List.of(), await(svc.check()), "after a month the bank and the ledger agree");
        var ind = server.getServicesManager().load(IndicatorProvider.class);
        assertEquals("capitalbank", ind.id());
        assertEquals(11, ind.indicators().size());

        Store.Loan l = await(svc.openLoans()).stream().filter(x -> x.paidPrincipal() > 0).findFirst().orElseThrow();
        await(svc.run(() -> { svc.store.updateLoan(new Store.Loan(l.id(), l.uuid(), l.principal(), l.interest(), l.instalment(), l.termDays(), l.startDay(),
                l.paidPrincipal() + 1, l.paidInterest(), l.arrears(), l.status(), l.overdueDays(), l.missed(), l.lastDay(), l.closedDay())); return null; }));
        assertEquals(1, await(svc.check()).size(), "a loan that doesn't match the ledger is reported");
    }

    // ---- commands, menus and help ------------------------------------------------------------------------------

    private String received(PlayerMock p) {
        StringBuilder sb = new StringBuilder();
        String m;
        while ((m = p.nextMessage()) != null) sb.append(m).append('\n');
        return sb.toString();
    }

    void settle() throws Exception { for (int i = 0; i < 3; i++) { await(svc.idle()); server.getScheduler().performTicks(2); } }

    @Test void commandsConfirmBeforeBorrowingAndTheMenuDeposits() throws Exception {
        samP.addAttachment(bank, "capitalbank.use", true);   // MockBukkit doesn't apply plugin.yml defaults
        samP.addAttachment(bank, "capitalbank.loans", true);
        played(samP, 3);
        give(sam, "100");
        settle();
        received(samP);
        samP.performCommand("bank loan 200");
        settle();
        assertTrue(received(samP).contains("/bank yes"), "asks first, with the typed fallback");
        assertEquals(c("100"), bal(sam), "nothing borrowed yet");
        samP.performCommand("bank yes");
        settle();
        assertTrue(received(samP).contains("You borrowed"));
        assertEquals(c("300"), bal(sam));

        bank.menus().main(samP);
        settle();
        samP.simulateInventoryClick(samP.getOpenInventory(), 11);   // Savings
        settle();
        samP.simulateInventoryClick(samP.getOpenInventory(), Menus.DEPOSIT);   // Put in 10
        settle();
        assertEquals(c("10"), svc.savings(sam));

        samP.performCommand("bank deposit all");
        settle();
        assertEquals(c("300"), svc.savings(sam));
        assertEquals(0, bal(sam));
    }

    @Test void everyHelpTopicHasRealTextAndTheCommandShowsIt() throws Exception {
        for (String topic : Actions.HELP_TOPICS) {
            String text = svc.msg.plain("help-" + topic);
            assertFalse(text.contains("Missing message"), "help-" + topic + " is missing");
            assertTrue(text.length() > 80, "help-" + topic + " should say something useful");
        }
        samP.addAttachment(bank, "capitalbank.use", true);
        settle();
        received(samP);
        samP.performCommand("bank help");
        assertTrue(received(samP).contains("Bank Help"));
        samP.performCommand("bank help loans");
        String loans = received(samP);
        assertTrue(loans.contains("Loans") && loans.contains("/bank help"), "a topic, with the typed fallback: " + loans);
    }

    @Test void everyMessageKeyTheCodeUsesExists() throws Exception {
        var yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(bank.getResource("messages.yml")));
        Set<String> used = new HashSet<>();
        var key = java.util.regex.Pattern.compile("\"([a-z]+(?:-[a-z]+)+)\"");   // every "word-word" string literal is a message key...
        for (String f : new String[]{"Actions", "Menus", "BankCommand", "BankService", "CapitalBankPlugin", "BankExpansion"}) {
            var mm = key.matcher(java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/patelheet/capitalengine/bank/" + f + ".java")));
            while (mm.find()) used.add(mm.group(1));
        }
        used.remove("has-loan");   // ...except the loan refusal reasons, which are shown as "loan-<reason>", and a config key
        used.remove("pool-size");
        for (String r : new String[]{"has-loan", "cooldown", "playtime"}) used.add("loan-" + r);
        used.addAll(java.util.List.of("form-confirm-loan", "form-confirm-repay", "usage-deposit", "usage-withdraw"));
        for (String k : used) assertTrue(yaml.contains(k), "messages.yml lacks " + k);
    }
}
