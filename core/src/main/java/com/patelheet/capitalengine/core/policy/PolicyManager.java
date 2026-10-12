package com.patelheet.capitalengine.core.policy;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owner-set per-plugin limits plus today's counters. Pure Java. Plugin names are matched case-insensitively.
 * Counters are only touched under the ledger lock.
 */
public final class PolicyManager {
    /** What a Vault deposit/withdraw with no counterparty means. */
    public enum VaultMode { ISSUE, TREASURY, DENY }

    /** One plugin's limits. {@link Long#MAX_VALUE} means unlimited. */
    public record Limit(VaultMode mode, long issuePerDay, long treasuryPerDay) {
        public static final Limit UNLIMITED = new Limit(VaultMode.ISSUE, Long.MAX_VALUE, Long.MAX_VALUE);
    }

    /** Today's flows for one plugin. */
    public static final class Counter {
        public final String display;
        public long issued, destroyed, treasuryIn, treasuryOut, transferred;
        public Counter(String display) { this.display = display; }
        public long[] snapshot() { return new long[] {issued, destroyed, treasuryIn, treasuryOut, transferred}; }
    }

    private volatile Limit defaults = Limit.UNLIMITED;
    private volatile Map<String, Limit> plugins = Map.of();
    private final Map<String, Counter> counters = new HashMap<>();

    /** Replaces the limits (live reload). Keys are lower-cased here. */
    public void configure(Limit defaults, Map<String, Limit> byPlugin) {
        Map<String, Limit> m = new ConcurrentHashMap<>();
        byPlugin.forEach((k, v) -> m.put(k.toLowerCase(Locale.ROOT), v));
        this.defaults = defaults;
        this.plugins = m;
    }

    /** Limits for a (lower-case) plugin key; unknown plugins get the defaults. */
    public Limit limit(String key) { return plugins.getOrDefault(key, defaults); }

    /** Today's counter for a plugin, created on first use. */
    public Counter counter(String key, String display) { return counters.computeIfAbsent(key, k -> new Counter(display)); }

    /** All of today's counters, keyed by lower-case plugin. */
    public Map<String, Counter> counters() { return counters; }

    /** Starts a new economic day. */
    public void reset() { counters.clear(); }
}
