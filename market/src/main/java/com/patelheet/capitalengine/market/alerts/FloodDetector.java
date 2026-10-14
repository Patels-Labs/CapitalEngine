package com.patelheet.capitalengine.market.alerts;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Spots dupes and floods (market/Plan.md §5.12): one player selling far more of an item today than their usual, or the
 * whole server's supply of an item jumping. The usual is a 14-day rolling average. Pure Java.
 */
public final class FloodDetector {
    /** {@code who} is a player's UUID, or {@link #SERVER}. */
    public record Alert(String who, String item, int today, double usual, boolean server) {}

    public static final String SERVER = "*";
    private static final int DAYS = 14;

    private final int playerMultiplier, serverMultiplier, min;
    private final Map<String, Double> avg = new HashMap<>();   // "who\titem" -> average per day
    private final Map<String, Integer> today = new HashMap<>();
    private final Set<String> alerted = new HashSet<>();

    /** The server as a whole is held to half the multiplier, so five times for the default of ten. */
    public FloodDetector(int playerMultiplier, int min) { this.playerMultiplier = playerMultiplier; this.serverMultiplier = Math.max(2, playerMultiplier / 2); this.min = min; }

    public void load(Map<String, Double> saved) { avg.putAll(saved); }

    /** Records a sale and returns the alerts it raises (at most one per player or server per item per day). */
    public List<Alert> sold(String player, String item, int qty) {
        List<Alert> out = new ArrayList<>();
        add(player, item, qty, playerMultiplier, false, out);
        add(SERVER, item, qty, serverMultiplier, true, out);
        return out;
    }

    private void add(String who, String item, int qty, int mult, boolean server, List<Alert> out) {
        String k = who + "\t" + item;
        int total = today.merge(k, qty, Integer::sum);
        double usual = avg.getOrDefault(k, 0.0);
        if (total > min && total > mult * usual && alerted.add(k)) out.add(new Alert(who, item, total, usual, server));
    }

    /** End of day: fold today into the averages and return what to save. */
    public Map<String, Double> endDay() {
        Map<String, Double> save = new HashMap<>();
        Set<String> keys = new HashSet<>(avg.keySet());
        keys.addAll(today.keySet());
        for (String k : keys) {
            double a = avg.getOrDefault(k, 0.0);
            a += (today.getOrDefault(k, 0) - a) / DAYS;
            if (a < 0.01) { avg.remove(k); save.put(k, 0.0); } else { avg.put(k, a); save.put(k, a); }
        }
        today.clear();
        alerted.clear();
        return save;
    }
}
