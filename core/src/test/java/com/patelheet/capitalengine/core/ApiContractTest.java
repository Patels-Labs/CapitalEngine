package com.patelheet.capitalengine.core;

import static org.junit.jupiter.api.Assertions.*;

import com.patelheet.capitalengine.api.Account;
import com.patelheet.capitalengine.api.Failure;
import com.patelheet.capitalengine.api.Severity;
import com.patelheet.capitalengine.api.TxResult;
import com.patelheet.capitalengine.api.event.CapitalAlertEvent;
import com.patelheet.capitalengine.api.event.CapitalPolicyLimitEvent;
import com.patelheet.capitalengine.api.event.CapitalTransactionEvent;
import com.patelheet.capitalengine.api.service.MonetaryPolicy;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.Test;

/** Every rule in api/Plan.md §5.4, run against the real implementation with SQLite in a temp folder. */
class ApiContractTest extends PluginTestBase {
    Account player() { return api.accounts().player(UUID.randomUUID()); }

    TxResult issue(Account to, long amount) throws Exception { return await(api.issue(other, to, amount, "test.issue", "memo")); }

    @Test void apiIsRegisteredAndVersioned() {
        assertNotNull(api);
        assertEquals("1.0.0", api.apiVersion());
        assertTrue(api.isCompatible("1.0.0"));
        assertTrue(api.isCompatible("0.9.5"));
        assertFalse(api.isCompatible("1.1.0"));
        assertFalse(api.isCompatible("2.0.0"));
    }

    @Test void currencyFormatsAndParses() {
        assertEquals("123.45 coins", api.currency().format(12345));
        assertEquals("1,500 coins", api.currency().format(150000));
        assertEquals("1 coin", api.currency().format(100));
        assertEquals(150000, api.currency().parse("1.5k"));
        assertEquals(1050, api.currency().parse("10.5"));
        assertThrows(IllegalArgumentException.class, () -> api.currency().parse("1.234"));
        assertThrows(IllegalArgumentException.class, () -> api.currency().parse("abc"));
        assertThrows(IllegalArgumentException.class, () -> api.currency().parse("-5"));
    }

    @Test void balanceIsVisibleBeforeTheFutureCompletesAndStatsFollow() throws Exception {
        Account sam = player();
        var f = api.issue(other, sam, 1000, "test.issue", "gift");
        assertEquals(1000, sam.balance(), "visible immediately");
        assertEquals(1000, api.stats().moneySupply());
        TxResult r = await(f);
        assertTrue(r.success());
        assertEquals(1000, r.issued());
        assertTrue(r.txId() > 0);
    }

    @Test void transferBuilderAndCollect() throws Exception {
        Account sam = player(), mia = player(), escrow = api.accounts().plugin(other, "escrow");
        issue(sam, 1000);
        assertTrue(await(api.transfer(other, sam, escrow, 500, "t.hold", "hold")).success());
        assertTrue(await(api.transaction(other, "t.trade", "Trade").move(escrow, mia, 480).move(escrow, api.treasury(), 20).commit()).success());
        assertEquals(500, sam.balance());
        assertEquals(480, mia.balance());
        assertEquals(20, api.treasury().balance());
        assertTrue(await(api.collect(other, mia, 80, "t.fee", "fee")).success());
        assertEquals(100, api.treasury().balance());
        assertTrue(await(api.destroy(other, mia, 400, "t.burn", "burn")).success());
        assertEquals(600, api.stats().moneySupply());
    }

    @Test void failuresAreValuesNotExceptions() throws Exception {
        Account sam = player(), mia = player();
        assertEquals(Failure.INSUFFICIENT_FUNDS, await(api.transfer(other, sam, mia, 1, "t.x", "m")).failure());
        assertEquals(Failure.INVALID_AMOUNT, await(api.transfer(other, sam, mia, 0, "t.x", "m")).failure());
        assertEquals(Failure.INVALID_AMOUNT, await(api.issue(other, sam, -1, "t.x", "m")).failure());
        assertEquals(Failure.NOT_ACCOUNT_OWNER, await(api.transfer(other, api.treasury(), mia, 1, "t.x", "m")).failure(), "the Treasury is only spent through serverPay");
        Account escrow = api.accounts().plugin(core, "escrow");
        assertEquals(Failure.NOT_ACCOUNT_OWNER, await(api.transfer(other, escrow, mia, 1, "t.x", "m")).failure());
        assertThrows(NullPointerException.class, () -> api.transfer(other, null, mia, 1, "t.x", "m"), "null account is a programmer error");
    }

    @Test void pluginAccountsAreNamespacedAndFindable() {
        Account a = api.accounts().plugin(other, "savings:" + UUID.randomUUID());
        assertTrue(a.name().startsWith("other:savings:"));
        assertSame(a, api.accounts().plugin(other, a.name().substring("other:".length())));
        assertEquals(Optional.of(a), api.accounts().find(a.name()));
        assertEquals(Optional.of("other"), a.ownerPlugin());
        assertEquals(Optional.of(a), api.accounts().byId(a.id()));
    }

    @Test void serverPayUsesTreasuryFirstWithinLimitsAndIsAllOrNothing() throws Exception {
        Account sam = player(), mia = player();
        issue(sam, 300);
        await(api.collect(other, sam, 300, "t.fee", "fee"));
        assertNull(core.setPolicy("Other", "issue-per-day", "5"));   // 5 coins = 500 units
        TxResult r = await(api.serverPay(other, mia, 400, "t.pay", "pay"));
        assertTrue(r.success());
        assertEquals(300, r.fromTreasury());
        assertEquals(100, r.issued());
        TxResult over = await(api.serverPay(other, mia, 500, "t.pay", "pay"));
        assertEquals(Failure.POLICY_LIMIT, over.failure(), "only 100 of issue budget left (300 used before the cap was set, 100 now), 500 asked");
        assertEquals(400, mia.balance());
        assertEquals(100, api.policy(other).issueRemainingToday());
    }

    @Test void referencesMakeCommitsIdempotent() throws Exception {
        Account sam = player();
        var first = await(api.transaction(other, "t.ref", "m").issue(sam, 50).reference("daily:1").commit());
        var again = await(api.transaction(other, "t.ref", "m").issue(sam, 50).reference("daily:1").commit());
        assertEquals(Failure.DUPLICATE_REFERENCE, again.failure());
        assertEquals(first.txId(), again.txId());
        assertEquals(50, sam.balance());
        var found = await(api.findByReference(other, "daily:1"));
        assertTrue(found.isPresent());
        assertEquals(first.txId(), found.get().txId());
        assertTrue(await(api.findByReference(other, "nope")).isEmpty());
    }

    @Test void historyIsNewestFirstAndPaged() throws Exception {
        Account sam = player();
        for (int i = 1; i <= 5; i++) issue(sam, i);
        var page1 = await(api.history(sam, 3, Long.MAX_VALUE));
        assertEquals(List.of(5L, 4L, 3L), page1.stream().map(t -> t.postings().stream().filter(p -> p.accountId().equals(sam.id())).findFirst().get().amount()).toList());
        var page2 = await(api.history(sam, 3, page1.get(2).txId()));
        assertEquals(2, page2.size());
    }

    public static class Events implements Listener {
        final List<Object> seen = new CopyOnWriteArrayList<>();
        @EventHandler public void tx(CapitalTransactionEvent e) { seen.add(e); }
        @EventHandler public void alert(CapitalAlertEvent e) { seen.add(e); }
        @EventHandler public void limit(CapitalPolicyLimitEvent e) { seen.add(e); }
    }

    @Test void eventsFireForTransactionsAlertsAndLimits() throws Exception {
        Events ev = new Events();
        server.getPluginManager().registerEvents(ev, other);
        Account sam = player();
        await(api.issue(other, sam, 10, "t.x", "m"));
        api.alert(other, Severity.WARN, "t.code", "careful");
        core.setPolicy("Other", "issue-per-day", "0");
        assertEquals(Failure.POLICY_LIMIT, await(api.issue(other, sam, 1, "t.x", "m")).failure());
        long end = System.currentTimeMillis() + 3000;
        while (ev.seen.size() < 3 && System.currentTimeMillis() < end) TimeUnit.MILLISECONDS.sleep(20);
        assertTrue(ev.seen.stream().anyMatch(e -> e instanceof CapitalTransactionEvent t && t.record().type().equals("t.x")));
        assertTrue(ev.seen.stream().anyMatch(e -> e instanceof CapitalAlertEvent a && a.code().equals("t.code") && a.severity() == Severity.WARN));
        assertTrue(ev.seen.stream().anyMatch(e -> e instanceof CapitalPolicyLimitEvent l && l.plugin().equals("Other") && l.kind() == CapitalPolicyLimitEvent.LimitKind.ISSUE));
    }

    @Test void monetaryPolicyServiceIsPickedUpWhenAnAddonRegistersIt() {
        assertTrue(api.monetaryPolicy().isEmpty(), "core implements neither service");
        MonetaryPolicy mp = new MonetaryPolicy() {
            public double savingsRatePerDay() { return 0.003; }
            public double loanRatePerDay() { return 0.01; }
            public OptionalLong budget(String channel) { return OptionalLong.of(5); }
        };
        server.getServicesManager().register(MonetaryPolicy.class, mp, other, ServicePriority.Normal);
        assertSame(mp, api.monetaryPolicy().orElseThrow());
    }

    @Test void statsExposeFlowsPerPlugin() throws Exception {
        Account sam = player();
        issue(sam, 700);
        var flow = api.stats().flowsToday().get("Other");
        assertNotNull(flow);
        assertEquals(700, flow.issued());
    }
}
