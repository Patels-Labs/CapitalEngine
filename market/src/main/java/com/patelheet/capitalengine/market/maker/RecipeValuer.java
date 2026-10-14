package com.patelheet.capitalengine.market.maker;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Prices crafted items from raw materials (market/Plan.md §5.6). Pure Java; {@code BukkitRecipes} feeds it the server's recipes.
 * An item's value is the cheapest recipe's ingredient cost plus 5% labour per crafting step, divided by the output count.
 * Raw prices from values.yml are never overridden. Cycles (ingots <-> blocks) settle on the raw path because labour only ever adds.
 */
public final class RecipeValuer {
    private RecipeValuer() {}

    /** One way to make {@code count} of {@code out}. Each slot lists the items that fit it; a furnace recipe also burns fuel. */
    public record Recipe(String out, int count, List<List<String>> slots, boolean furnace) {}

    public static Map<String, Long> value(Map<String, Long> raw, List<Recipe> recipes) {
        Map<String, Long> v = new HashMap<>(raw);
        boolean changed = true;
        for (int pass = 0; changed && pass < 50; pass++) {   // every change lowers a whole number, so this ends
            changed = false;
            for (Recipe r : recipes) {
                if (raw.containsKey(r.out())) continue;
                long total = 0;
                boolean ok = true;
                for (List<String> slot : r.slots()) {
                    long best = Long.MAX_VALUE;
                    for (String alt : slot) { Long c = v.get(alt); if (c != null && c < best) best = c; }
                    if (best == Long.MAX_VALUE) { ok = false; break; }
                    total += best;
                }
                if (!ok || total == 0) continue;
                if (r.furnace()) total += Math.max(1, total * 3 / 100);
                long value = Math.max(1, (total * 105 + 100L * r.count() - 1) / (100L * r.count()));
                Long old = v.get(r.out());
                if (old == null || value < old) { v.put(r.out(), value); changed = true; }
            }
        }
        return v;
    }
}
