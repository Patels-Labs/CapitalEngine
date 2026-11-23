package com.patelheet.capitalengine.trade.world;

import java.util.List;
import java.util.Objects;
import java.util.Random;

/** The weekly demand boom (trade/Plan.md §5.2): with probability {@code chance}, one export item is wanted more this week. Pure Java. */
public final class Booms {
    private Booms() {}

    /** This week's boom item, or null for a quiet week. {@code items} must be in a stable order (sorted). */
    public static String roll(long seed, long week, double chance, List<String> items) {
        if (items.isEmpty()) return null;
        Random r = new Random(Objects.hash(seed, week, "boom"));
        return r.nextDouble() < chance ? items.get(r.nextInt(items.size())) : null;
    }
}
