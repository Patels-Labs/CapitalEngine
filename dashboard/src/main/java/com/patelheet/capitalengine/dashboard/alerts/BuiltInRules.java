package com.patelheet.capitalengine.dashboard.alerts;

import com.patelheet.capitalengine.api.Severity;
import com.patelheet.capitalengine.api.Stats;
import com.patelheet.capitalengine.dashboard.snapshot.Snapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.LongFunction;

/** The owner-useful checks run after each snapshot (Plan §5.3). Pure: everything it needs is passed in. */
public final class BuiltInRules {
    private BuiltInRules() {}

    /** {@code printerMinCoins} is in the smallest unit. */
    public record Thresholds(double issuanceSpikeX, double printerShare, long printerMinCoins, double supplyDrop) {}

    /**
     * @param issuedLastDays    {@code issuedToday} totals of up to the last 7 finished days
     * @param supplyWeekAgo     money supply at the end of the day 7 days ago, if known
     * @param treasuryZero24h   the Treasury read 0 in every snapshot of the last 24 hours, and there are 24 hours of snapshots
     */
    public record History(List<Double> issuedLastDays, OptionalDouble supplyWeekAgo, boolean treasuryZero24h) {}

    /** A rule that fired. {@code kv} are the message placeholders (name, value, ...); the message key is {@code rule-<code without "dash.">}. */
    public record Fired(String source, String code, Severity severity, Object... kv) {}

    public static final String SELF = "CapitalDashboard";

    /** {@code known} is the plugins already seen in earlier snapshots; any other plugin with flows today is reported once. */
    public static List<Fired> evaluate(Stats s, History h, Thresholds t, Set<String> known, LongFunction<String> coins) {
        List<Fired> out = new ArrayList<>();
        if (h.issuedLastDays().size() >= 3) {
            double avg = h.issuedLastDays().stream().mapToDouble(Double::doubleValue).average().orElse(0);
            if (avg > 0 && s.issuedToday() > t.issuanceSpikeX() * avg)
                out.add(new Fired(SELF, "dash.issuance_spike", Severity.WARN, "today", coins.apply(s.issuedToday()), "usual", coins.apply(Math.round(avg)), "x", t.issuanceSpikeX()));
        }
        for (var e : new TreeMap<>(s.flowsToday()).entrySet()) {
            long net = Snapshot.flow(e.getValue())[4];
            if (s.issuedToday() > 0 && net > t.printerShare() * s.issuedToday() && net > t.printerMinCoins())
                out.add(new Fired(e.getKey(), "dash.plugin_printer", Severity.WARN, "plugin", e.getKey(), "amount", coins.apply(net),
                        "share", Math.round(100.0 * net / s.issuedToday())));
            if (!known.contains(e.getKey()))
                out.add(new Fired(e.getKey(), "dash.unknown_plugin", Severity.INFO, "plugin", e.getKey()));
        }
        if (h.treasuryZero24h() && s.treasuryBalance() == 0)
            out.add(new Fired(SELF, "dash.treasury_empty", Severity.INFO));
        if (h.supplyWeekAgo().isPresent()) {
            double before = h.supplyWeekAgo().getAsDouble();
            if (before > 0 && (before - s.moneySupply()) / before > t.supplyDrop())
                out.add(new Fired(SELF, "dash.supply_drop", Severity.WARN, "before", coins.apply(Math.round(before)), "now", coins.apply(s.moneySupply()),
                        "percent", Math.round(100 * (before - s.moneySupply()) / before)));
        }
        return out;
    }
}
