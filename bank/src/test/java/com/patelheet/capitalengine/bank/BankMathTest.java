package com.patelheet.capitalengine.bank;

import static org.junit.jupiter.api.Assertions.*;

import com.patelheet.capitalengine.bank.math.BankMath;
import java.util.Arrays;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

/** The bank's formulas, without a server. Amounts are in the smallest unit (100 = 1 coin). */
class BankMathTest {
    @Test void ratesComeFromThePolicyCappedByTheOwnersMax() {
        assertEquals(0.02 / 7, BankMath.perDay(OptionalDouble.empty(), 0.02, 0.05), 1e-12, "no policy: config weekly / 7");
        assertEquals(0.004, BankMath.perDay(OptionalDouble.of(0.004), 0.02, 0.05), 1e-12, "policy wins");
        assertEquals(0.05 / 7, BankMath.perDay(OptionalDouble.of(0.5), 0.02, 0.05), 1e-12, "but never above the max");
        assertEquals(0, BankMath.perDay(OptionalDouble.of(-1), 0.02, 0.05), "or below zero");
    }

    @Test void interestIsCappedAndRoundedDown() {
        assertEquals(1999, BankMath.owed(1_000_000, 1_000_000, 0.001999), "floor");
        assertEquals(2_000, BankMath.owed(5_000_000, 1_000_000, 0.002), "only the first 10,000 coins earn");
        assertEquals(0, BankMath.owed(0, 1_000_000, 0.002));
    }

    @Test void aShortBudgetScalesEveryoneEquallyAndNeverOverpays() {
        long[] owed = {300, 200, 100, 7};
        assertArrayEquals(owed, BankMath.scale(owed, 10_000), "enough budget: everyone gets what they're owed");
        long[] half = BankMath.scale(owed, 303);
        assertArrayEquals(new long[]{149, 99, 49, 3}, half);
        assertTrue(Arrays.stream(half).sum() <= 303);
        assertArrayEquals(new long[]{0, 0, 0, 0}, BankMath.scale(owed, 0), "zero budget pays nothing");
        assertEquals(0.5, BankMath.scaleFactor(600, 300));
        assertEquals(1, BankMath.scaleFactor(0, 0));
    }

    @Test void loanTermsMatchThePlanExample() {
        var t = BankMath.terms(50_000, 0.05 / 7, 7);   // borrow 500 at 5% a week for 7 days
        assertEquals(2_500, t.interest());
        assertEquals(52_500, t.total());
        assertEquals(7_500, t.instalment(), "75 coins a day");
        var odd = BankMath.terms(1_000, 0.01, 3);
        assertEquals(30, odd.interest());
        assertEquals(344, odd.instalment(), "rounded up, so the term always covers the total");
        assertTrue(odd.instalment() * odd.days() >= odd.total());
    }

    @Test void paymentsSplitWithoutLosingACoin() {
        long p = 50_000, i = 2_500;
        long paidP = 0, paidI = 0, arrears = 0;
        long[] wallet = {7_500, 7_500, 0, 3_000, 30_000, 7_500, 7_500, 7_500, 7_500};
        for (long w : wallet) {
            long left = p - paidP + i - paidI;
            if (left == 0) break;
            long due = BankMath.due(left, 7_500, arrears), take = Math.min(due, w);
            long[] s = BankMath.split(take, p - paidP, i - paidI);
            assertEquals(take, s[0] + s[1]);
            assertTrue(s[0] <= p - paidP && s[1] <= i - paidI);
            paidP += s[0]; paidI += s[1]; arrears = due - take;
        }
        assertEquals(p, paidP, "all principal repaid exactly");
        assertEquals(i, paidI, "all interest repaid exactly");
        assertArrayEquals(new long[]{0, 0}, BankMath.split(100, 0, 0));
        assertArrayEquals(new long[]{3, 0}, BankMath.split(10, 3, 0), "never more than is left");
    }

    @Test void creditGrowsUpToTheCap() {
        assertEquals(75_000, BankMath.grow(50_000, 0.5, 500_000));
        assertEquals(500_000, BankMath.grow(400_000, 0.5, 500_000));
    }

    @Test void upkeepHasAFreeAllowanceAndRoundsUp() {
        assertEquals(0, BankMath.upkeep(10_000, 10_000, 100), "within the free 100×100");
        assertEquals(100, BankMath.upkeep(10_001, 10_000, 100), "one block over is one charge of 100 blocks²");
        assertEquals(8_400, BankMath.upkeep(18_400, 10_000, 100), "84 coins a day for 18,400 blocks²");
        assertEquals(0, BankMath.upkeep(0, 10_000, 100));
        assertFalse(BankMath.frozen(300, 100, 3), "three days owed: not yet");
        assertTrue(BankMath.frozen(301, 100, 3), "more than three days: frozen");
        assertFalse(BankMath.frozen(0, 0, 3));
    }
}
