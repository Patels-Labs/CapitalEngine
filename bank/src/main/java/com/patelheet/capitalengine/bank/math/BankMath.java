package com.patelheet.capitalengine.bank.math;

import java.math.BigInteger;
import java.util.OptionalDouble;

/**
 * Every formula the bank uses (bank/Plan.md §5), as pure functions so they can be unit-tested and driven by the simulator.
 * Money is always a {@code long} count of the smallest unit; rates are plain fractions per day.
 */
public final class BankMath {
    private BankMath() {}

    // ---- rates (§5.1) ------------------------------------------------------------------------------------------

    /** The rate per day: the policy's when one is registered, else {@code configWeekly / 7}; never above {@code maxWeekly / 7} or below 0. */
    public static double perDay(OptionalDouble policyPerDay, double configWeekly, double maxWeekly) {
        double r = policyPerDay.isPresent() ? policyPerDay.getAsDouble() : configWeekly / 7;
        return Math.max(0, Math.min(r, maxWeekly / 7));
    }

    // ---- savings interest (§5.2) -------------------------------------------------------------------------------

    /** Interest owed today on one savings balance: {@code floor(min(balance, cap) × perDay)}. */
    public static long owed(long balance, long cap, double perDay) {
        return (long) Math.floor(Math.max(0, Math.min(balance, cap)) * perDay);
    }

    /** What everyone is actually paid when the budget is short: each share scaled down by the same factor, rounded down. Never more than {@code budget} in total. */
    public static long[] scale(long[] owed, long budget) {
        long total = 0;
        for (long o : owed) total = Math.addExact(total, o);
        long[] pay = owed.clone();
        if (total <= budget) return pay;
        for (int i = 0; i < pay.length; i++) pay[i] = budget <= 0 ? 0 : mulDiv(owed[i], budget, total);
        return pay;
    }

    /** The scale factor that {@link #scale} applied: 1 when the budget covers everything. */
    public static double scaleFactor(long totalOwed, long budget) {
        return totalOwed <= budget ? 1 : Math.max(0, (double) budget / totalOwed);
    }

    // ---- loans (§5.3) ------------------------------------------------------------------------------------------

    /** A loan's terms. Flat interest; the last instalment is whatever is left, so no coin is lost to rounding. */
    public record Terms(long principal, long interest, long instalment, int days) {
        public long total() { return principal + interest; }
    }

    /** {@code interest = floor(principal × perDay × days)}, {@code instalment = ceil(total / days)}. */
    public static Terms terms(long principal, double perDay, int days) {
        long interest = (long) Math.floor(principal * perDay * days);
        long total = principal + interest;
        return new Terms(principal, interest, Math.ceilDiv(total, days), days);
    }

    /** What to take today: one instalment plus anything behind, but never more than is left. */
    public static long due(long left, long instalment, long arrears) { return Math.min(left, instalment + arrears); }

    /**
     * Splits a payment into {principal, interest} in proportion to what is still owed of each. A payment of everything left
     * pays both off exactly, and neither part can exceed what is owed of it.
     */
    public static long[] split(long take, long principalLeft, long interestLeft) {
        long left = principalLeft + interestLeft;
        if (take <= 0 || left <= 0) return new long[]{0, 0};
        take = Math.min(take, left);
        long p = mulDiv(take, principalLeft, left);
        return new long[]{p, take - p};
    }

    /** The next credit limit after a loan repaid with no missed days: {@code limit × (1 + growth)}, up to {@code max}. */
    public static long grow(long limit, double growth, long max) {
        return Math.min(max, (long) Math.floor(limit * (1 + growth)));
    }

    // ---- land upkeep (§5.4) ------------------------------------------------------------------------------------

    /** Daily upkeep: {@code ceil(max(0, area − free) / 100) × per100}. Areas are in blocks², the result in coins' smallest unit. */
    public static long upkeep(long area, long freeBlocks, long per100) {
        long billable = Math.max(0, area - freeBlocks);
        return Math.ceilDiv(billable, 100) * per100;
    }

    /** Land is frozen once more than {@code days} days' upkeep is owed (or anything is owed when the land costs nothing a day). */
    public static boolean frozen(long due, long dailyCharge, int days) { return due > 0 && due > dailyCharge * days; }

    /** {@code a × b / c}, rounded down, without overflow. */
    static long mulDiv(long a, long b, long c) {
        return BigInteger.valueOf(a).multiply(BigInteger.valueOf(b)).divide(BigInteger.valueOf(c)).longValueExact();
    }
}
