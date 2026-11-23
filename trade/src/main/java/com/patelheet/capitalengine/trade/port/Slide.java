package com.patelheet.capitalengine.trade.port;

/**
 * Quotas and the price slide (trade/Plan.md §5.3, §5.4). Pure Java; amounts are {@code long} units.
 * <p>Exports: the unit price at fill level {@code x} is {@code p × (1 − k·x/Q)}, so it falls as the day's quota {@code Q} fills.
 * Imports: {@code p × (1 + tariff) × (1 + k·x/Q)}, so it rises. A total is the difference of one rounded cumulative function
 * ({@code cum(u+q) − cum(u)}), so selling a pile in several stacks pays exactly what selling it at once would.
 * Exports round down and imports round up, so rounding never favours the player against the economy.
 */
public final class Slide {
    private static final double EPS = 1e-9;   // keeps exact products from rounding the wrong way after floating error

    private Slide() {}

    /** Today's quota: {@code perPlayer × max(1, players)}, so a quiet server gets a small one. */
    public static long quota(long perPlayer, long players) { return Math.max(0, perPlayer) * Math.max(1, players); }

    private static double k(double k) { return Math.max(0, Math.min(1, k)); }

    /** Coins for the first {@code x} units exported today. */
    static long exportCum(long p, long x, long quota, double k) {
        if (quota <= 0) return 0;
        double v = p * (x - k(k) * x * (double) x / (2.0 * quota));
        return (long) Math.floor(v + EPS);
    }

    /** What exporting {@code q} more units pays when {@code used} are already exported today. {@code used + q} must not exceed the quota. */
    public static long exportTotal(long p, long used, long q, long quota, double k) {
        if (q <= 0) return 0;
        if (used + q > quota) throw new IllegalArgumentException("beyond the quota");
        return exportCum(p, used + q, quota, k) - exportCum(p, used, quota, k);
    }

    /** The largest amount up to {@code want} that fits in the quota and pays at most {@code budget}. */
    public static long maxExport(long p, long used, long want, long quota, double k, long budget) {
        long lo = 0, hi = Math.max(0, Math.min(want, quota - used));
        while (lo < hi) {   // the total only grows with q, so binary search finds the largest that fits
            long mid = lo + (hi - lo + 1) / 2;
            if (exportTotal(p, used, mid, quota, k) <= budget) lo = mid; else hi = mid - 1;
        }
        return lo;
    }

    /** The price of the next unit exported, for display. */
    public static long exportUnit(long p, long used, long quota, double k) {
        return quota <= 0 ? 0 : (long) Math.floor(p * (1 - k(k) * Math.min(used, quota) / (double) quota) + EPS);
    }

    /** Cost of the first {@code x} units imported today, tariff included. */
    static long importCum(long p, double tariff, long x, long quota, double k) {
        if (quota <= 0) return 0;
        double v = p * (1 + Math.max(0, tariff)) * (x + k(k) * x * (double) x / (2.0 * quota));
        return (long) Math.ceil(v - EPS);
    }

    /** What importing {@code q} more units costs when {@code used} are already imported today. {@code used + q} must not exceed the quota. */
    public static long importTotal(long p, double tariff, long used, long q, long quota, double k) {
        if (q <= 0) return 0;
        if (used + q > quota) throw new IllegalArgumentException("beyond the quota");
        return importCum(p, tariff, used + q, quota, k) - importCum(p, tariff, used, quota, k);
    }

    /** The tariff share of an import total: it goes to the Treasury, and {@code total − tariffPart} is destroyed. */
    public static long tariffPart(long total, double tariff) {
        if (tariff <= 0 || total <= 0) return 0;
        return Math.min(total, (long) Math.floor(total * (tariff / (1 + tariff)) + EPS));
    }
}
