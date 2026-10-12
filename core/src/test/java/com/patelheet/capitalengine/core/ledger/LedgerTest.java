package com.patelheet.capitalengine.core.ledger;

import static org.junit.jupiter.api.Assertions.*;

import com.patelheet.capitalengine.api.Failure;
import com.patelheet.capitalengine.api.Posting;
import com.patelheet.capitalengine.api.TxResult;
import com.patelheet.capitalengine.core.ledger.Leg.Kind;
import com.patelheet.capitalengine.core.policy.PolicyManager;
import com.patelheet.capitalengine.core.policy.PolicyManager.Limit;
import com.patelheet.capitalengine.core.policy.PolicyManager.VaultMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LedgerTest {
    final List<Item> queued = new ArrayList<>();
    PolicyManager policy;
    Ledger ledger;
    Acct sam, mia;

    @BeforeEach
    void setUp() {
        policy = new PolicyManager();
        ledger = new Ledger(policy, queued::add);
        sam = ledger.player(UUID.randomUUID(), "Sam");
        mia = ledger.player(UUID.randomUUID(), "Mia");
    }

    TxResult run(String plugin, String ref, Leg... legs) { return ledger.apply(new Req(plugin, "t.x", "m", null, ref, List.of(legs), false)).result(); }
    Leg issue(Acct to, long a) { return new Leg(Kind.ISSUE, null, to, a); }
    Leg move(Acct f, Acct t, long a) { return new Leg(Kind.MOVE, f, t, a); }
    Leg pay(Acct to, long a) { return new Leg(Kind.SERVER_PAY, null, to, a); }

    long sumOfAllBalances() { return ledger.accounts().stream().mapToLong(Acct::balance).sum(); }

    @Test void everyTransactionSumsToZeroAndBooksBalance() {
        assertTrue(run("A", null, issue(sam, 1000)).success());
        assertTrue(run("A", null, move(sam, mia, 300)).success());
        assertEquals(700, sam.balance());
        assertEquals(300, mia.balance());
        assertEquals(1000, ledger.stats().moneySupply());
        assertEquals(0, sumOfAllBalances());
        for (Item i : queued) if (i instanceof Item.PendingTx t) assertEquals(0, t.rec().postings().stream().mapToLong(Posting::amount).sum());
    }

    @Test void neverBelowZeroAndFailureChangesNothing() {
        run("A", null, issue(sam, 100));
        TxResult r = run("A", null, move(sam, mia, 101));
        assertEquals(Failure.INSUFFICIENT_FUNDS, r.failure());
        assertEquals(100, sam.balance());
        assertEquals(0, mia.balance());
    }

    @Test void invalidAmountsAndOverflow() {
        assertEquals(Failure.INVALID_AMOUNT, run("A", null, issue(sam, 0)).failure());
        assertEquals(Failure.INVALID_AMOUNT, run("A", null, issue(sam, -5)).failure());
        assertEquals(Failure.INVALID_AMOUNT, run("A", null, issue(sam, Long.MAX_VALUE), issue(sam, 1)).failure());
        assertEquals(Failure.INVALID_AMOUNT, run("A", null, move(sam, sam, 5)).failure(), "moving to yourself is nothing");
        assertEquals(0, ledger.stats().moneySupply());
    }

    @Test void pluginAccountsOnlyLeaveThroughTheirOwner() {
        Acct escrow = ledger.pluginAccount("CapitalMarket", "escrow");
        run("CapitalMarket", null, issue(escrow, 500));
        assertEquals(Failure.NOT_ACCOUNT_OWNER, run("Evil", null, move(escrow, sam, 10)).failure());
        assertTrue(run("Evil", null, issue(sam, 10), move(sam, escrow, 10)).success(), "anyone can pay in");
        assertTrue(run("CapitalMarket", null, move(escrow, sam, 10)).success());
        assertTrue(run("capitalmarket", null, move(escrow, sam, 10)).success(), "plugin names are case-insensitive");
    }

    @Test void treasuryIsOnlyASourceThroughServerPay() {
        run("A", null, issue(sam, 50));
        assertTrue(run("A", null, move(sam, ledger.treasury(), 50)).success());
        assertEquals(Failure.NOT_ACCOUNT_OWNER, run("A", null, move(ledger.treasury(), mia, 10)).failure());
        TxResult r = run("A", null, pay(mia, 30));
        assertTrue(r.success());
        assertEquals(30, r.fromTreasury());
        assertEquals(0, r.issued());
        assertEquals(20, ledger.treasury().balance());
    }

    @Test void serverPayUsesTreasuryFirstThenCreatesMoney() {
        run("A", null, issue(sam, 40));
        run("A", null, move(sam, ledger.treasury(), 40));
        TxResult r = run("A", null, pay(mia, 100));
        assertEquals(40, r.fromTreasury());
        assertEquals(60, r.issued());
        assertEquals(100, mia.balance());
        assertEquals(0, ledger.treasury().balance());
        assertEquals(60, ledger.stats().moneySupply() - 40, "40 existed, 60 created");
    }

    @Test void serverPayRespectsTreasuryLimitAndIsAllOrNothingAtIssueLimit() {
        policy.configure(Limit.UNLIMITED, Map.of("Bank", new Limit(VaultMode.ISSUE, 50, 10)));
        run("Other", null, issue(sam, 100));
        run("Other", null, move(sam, ledger.treasury(), 100));
        TxResult ok = run("Bank", null, pay(mia, 55));
        assertTrue(ok.success());
        assertEquals(10, ok.fromTreasury(), "Treasury limit caps the treasury part");
        assertEquals(45, ok.issued());
        TxResult over = run("Bank", null, pay(mia, 10));
        assertEquals(Failure.POLICY_LIMIT, over.failure(), "only 5 issue-budget left and treasury limit is used up");
        assertEquals(55, mia.balance(), "nothing happened");
        assertEquals(90, ledger.treasury().balance());
        assertEquals(5, ledger.policyStatus("Bank").issueRemainingToday());
        assertEquals(0, ledger.policyStatus("Bank").treasuryRemainingToday());
    }

    @Test void issueLimitAndDenyAreEnforced() {
        policy.configure(Limit.UNLIMITED, Map.of("Capped", new Limit(VaultMode.ISSUE, 100, Long.MAX_VALUE), "Nope", new Limit(VaultMode.DENY, 0, 0)));
        assertTrue(run("Capped", null, issue(sam, 100)).success());
        assertEquals(Failure.POLICY_LIMIT, run("Capped", null, issue(sam, 1)).failure());
        assertEquals(Failure.DENIED, run("Nope", null, issue(sam, 1)).failure());
        assertTrue(run("Nope", null, move(sam, mia, 5)).success(), "deny only blocks creating/removing money");
        ledger.newDay(2, 0);
        assertTrue(run("Capped", null, issue(sam, 100)).success(), "limits reset each day");
    }

    @Test void referencesAreIdempotentPerPlugin() {
        TxResult first = run("A", "r1", issue(sam, 10));
        TxResult again = run("A", "r1", issue(sam, 10));
        assertEquals(Failure.DUPLICATE_REFERENCE, again.failure());
        assertEquals(first.txId(), again.txId());
        assertEquals(10, sam.balance());
        assertTrue(run("B", "r1", issue(sam, 10)).success(), "same reference, different plugin");
    }

    @Test void multiLegIsAllOrNothingOnTheNetEffect() {
        run("A", null, issue(sam, 100));
        // net: sam −100 +60 = −40 is fine even though the first leg alone would drain him
        assertTrue(run("A", null, move(sam, mia, 100), move(mia, sam, 60)).success());
        assertEquals(60, sam.balance());
        assertEquals(Failure.INSUFFICIENT_FUNDS, run("A", null, issue(mia, 5), move(sam, mia, 61)).failure());
        assertEquals(40, mia.balance(), "failed multi-leg applied nothing, not even the issue leg");
    }

    @Test void reverseNegatesAndOnlyOnce() {
        long tx = run("A", null, issue(sam, 100)).txId();
        var rec = queued.stream().filter(i -> i instanceof Item.PendingTx t && t.rec().txId() == tx).map(i -> ((Item.PendingTx) i).rec()).findFirst().orElseThrow();
        run("A", null, move(sam, mia, 100));
        assertEquals(Failure.INSUFFICIENT_FUNDS, ledger.reverse(rec, null, "oops").result().failure(), "Sam has nothing left to take back");
        run("A", null, move(mia, sam, 100));
        assertTrue(ledger.reverse(rec, null, "oops").result().success());
        assertEquals(0, ledger.stats().moneySupply());
        assertEquals(Failure.DUPLICATE_REFERENCE, ledger.reverse(rec, null, "again").result().failure());
    }

    @Test void pausedRefusesEverything() {
        ledger.setPaused(true);
        assertEquals(Failure.ECONOMY_PAUSED, run("A", null, issue(sam, 1)).failure());
    }

    @Test void flowsAndTransferredAreDerivedFromPostings() {
        run("A", null, issue(sam, 100));
        run("A", null, move(sam, mia, 40));
        run("A", null, move(mia, ledger.treasury(), 10));
        var flow = ledger.stats().flowsToday().get("A");
        assertEquals(100, flow.issued());
        assertEquals(10, flow.treasuryIn());
        assertEquals(40, flow.transferred());
    }
}
