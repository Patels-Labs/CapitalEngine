package com.patelheet.capitalengine.dashboard.snapshot;

import com.patelheet.capitalengine.api.PluginFlow;
import com.patelheet.capitalengine.api.Stats;
import com.patelheet.capitalengine.api.service.Indicator;
import com.patelheet.capitalengine.api.service.Unit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.UnaryOperator;

/**
 * One reading of the economy: core's {@code stats()} plus every {@code IndicatorProvider}, flattened into named numbers.
 * Coin amounts stay in the smallest unit (as {@code Stats} and market's {@code Unit.COINS} indicators give them); only the
 * display layers divide by the currency's decimals.
 *
 * @param providers provider id → display name, for the providers that answered this time
 */
public record Snapshot(long ts, Stats stats, List<Metric> metrics, Map<String, String> providers) {
    /** {@code provider} is {@code "core"} for core's own numbers. */
    public record Metric(String key, String label, Unit unit, String provider, double value) {}

    /** What one provider said. */
    public record Reading(String id, String name, List<Indicator> indicators) {}

    public static final String CORE = "core";
    public static final String FLOW = "core.flow.";
    public static final List<String> FLOW_FIELDS = List.of("issued", "destroyed", "treasuryIn", "treasuryOut", "net");

    /** {@code label} turns a core field name ({@code moneySupply}, {@code flow-issued}) into owner-facing text; {@code <plugin>} is filled in for flows. */
    public static Snapshot of(long ts, Stats s, List<Reading> readings, UnaryOperator<String> label) {
        List<Metric> m = new ArrayList<>();
        core(m, label, "moneySupply", Unit.COINS, s.moneySupply());
        core(m, label, "activeMoneySupply", Unit.COINS, s.activeMoneySupply());
        core(m, label, "treasuryBalance", Unit.COINS, s.treasuryBalance());
        core(m, label, "issuedToday", Unit.COINS, s.issuedToday());
        core(m, label, "destroyedToday", Unit.COINS, s.destroyedToday());
        core(m, label, "treasuryInToday", Unit.COINS, s.treasuryInToday());
        core(m, label, "treasuryOutToday", Unit.COINS, s.treasuryOutToday());
        core(m, label, "activePlayersToday", Unit.COUNT, s.activePlayersToday());
        core(m, label, "activeMinutesToday", Unit.COUNT, s.activeMinutesToday());
        core(m, label, "avgActivePlayers7d", Unit.COUNT, s.avgActivePlayers7d());
        for (var e : new TreeMap<>(s.flowsToday()).entrySet()) {
            long[] v = flow(e.getValue());
            for (int i = 0; i < FLOW_FIELDS.size(); i++)
                m.add(new Metric(FLOW + e.getKey() + "." + FLOW_FIELDS.get(i), label.apply("flow-" + FLOW_FIELDS.get(i)).replace("<plugin>", e.getKey()), Unit.COINS, CORE, v[i]));
        }
        Map<String, String> names = new LinkedHashMap<>();
        for (Reading r : readings) {
            names.put(r.id(), r.name());
            for (Indicator i : r.indicators()) m.add(new Metric(r.id() + "." + i.key(), i.label(), i.unit(), r.id(), i.value()));
        }
        return new Snapshot(ts, s, List.copyOf(m), names);
    }

    /** issued, destroyed, treasuryIn, treasuryOut, net (net new money: issued minus destroyed). */
    public static long[] flow(PluginFlow f) {
        return new long[]{f.issued(), f.destroyed(), f.treasuryIn(), f.treasuryOut(), f.issued() - f.destroyed()};
    }

    private static void core(List<Metric> m, UnaryOperator<String> label, String field, Unit unit, double v) {
        m.add(new Metric(CORE + "." + field, label.apply(field), unit, CORE, v));
    }

    /** The value of one metric, or NaN when this snapshot doesn't have it. */
    public double value(String key) {
        for (Metric x : metrics) if (x.key().equals(key)) return x.value();
        return Double.NaN;
    }
}
