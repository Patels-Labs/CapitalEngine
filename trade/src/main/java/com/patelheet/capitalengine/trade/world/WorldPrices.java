package com.patelheet.capitalengine.trade.world;

import java.util.Objects;
import java.util.Random;

/**
 * The world price of one item drifts each day (trade/Plan.md §5.2), as a mean-reverting random walk in log space:
 * {@code ln p' = ln p + θ·(ln base − ln p) + σ·ε}, clamped to {@code [minX×base, maxX×base]}. Prices are {@code long} units.
 * The random number comes from the seed, the day and the item, so the walk is reproducible. Pure Java.
 */
public final class WorldPrices {
    public record Cfg(double theta, double sigma, double minX, double maxX, long seed) {}

    private WorldPrices() {}

    /** Tomorrow's price for {@code item} on {@code day}, given today's. */
    public static long step(Cfg c, String item, long day, long base, long price) {
        double eps = new Random(Objects.hash(c.seed(), day, item)).nextGaussian();
        double lnBase = Math.log(base), lnP = Math.log(Math.max(1, price));
        double next = Math.exp(lnP + c.theta() * (lnBase - lnP) + c.sigma() * eps);
        return clamp(c, base, Math.round(next));
    }

    public static long clamp(Cfg c, long base, long price) {
        long lo = Math.max(1, (long) Math.ceil(c.minX() * base)), hi = Math.max(lo, (long) Math.floor(c.maxX() * base));
        return Math.max(lo, Math.min(hi, price));
    }
}
