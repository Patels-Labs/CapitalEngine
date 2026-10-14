package com.patelheet.capitalengine.market.maker;

/** Stock-based reference price for the server market maker (market/Plan.md §5.5). Pure Java. */
public final class ReferencePrice {
    private ReferencePrice() {}

    /** {@code base × (target / max(stock,1)) ^ k}, clamped to [0.25×base, 4×base] and at least 1 unit. Rounded straight to a long. */
    public static long ref(long base, long stock, long target, double k) {
        double r = base * Math.pow((double) target / Math.max(stock, 1), k);
        return Math.max(1, Math.round(Math.min(4.0 * base, Math.max(0.25 * base, r))));
    }

    /** What the server pays: below the reference, never less than 1. */
    public static long bid(long ref, double spread) { return Math.max(1, (long) Math.floor(ref * (1 - spread))); }

    /** What the server charges: above the reference. */
    public static long ask(long ref, double spread) { return Math.max(1, (long) Math.ceil(ref * (1 + spread))); }
}
