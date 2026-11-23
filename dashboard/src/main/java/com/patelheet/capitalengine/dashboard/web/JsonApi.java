package com.patelheet.capitalengine.dashboard.web;

import com.patelheet.capitalengine.api.Currency;
import com.patelheet.capitalengine.dashboard.alerts.BuiltInRules;
import com.patelheet.capitalengine.dashboard.snapshot.Downsampler;
import com.patelheet.capitalengine.dashboard.snapshot.Snapshot;
import com.patelheet.capitalengine.dashboard.storage.Store;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.TreeMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** The numbers behind the web page's read-only endpoints and {@code /cdash}. Everything here only reads. */
public final class JsonApi {
    /** One plugin's flows over a range: issued, destroyed, treasuryIn, treasuryOut, net. */
    public record Flow(String plugin, long[] v) {}

    private final Store store;
    private final Supplier<Snapshot> latest;
    private final Currency currency;
    private final Supplier<BuiltInRules.Thresholds> thresholds;
    private final LongSupplier clock;

    public JsonApi(Store store, Supplier<Snapshot> latest, Currency currency, Supplier<BuiltInRules.Thresholds> thresholds, LongSupplier clock) {
        this.store = store; this.latest = latest; this.currency = currency; this.thresholds = thresholds; this.clock = clock;
    }

    public Snapshot latest() { return latest.get(); }

    /** Money supply change against the end of the day a week ago, in percent, if that day is stored. */
    public OptionalDouble supplyWeekChange() {
        Snapshot s = latest.get();
        if (s == null) return OptionalDouble.empty();
        OptionalDouble before = store.daily("core.moneySupply", s.stats().dayIndex() - 7);
        if (before.isEmpty() || before.getAsDouble() <= 0) return OptionalDouble.empty();
        return OptionalDouble.of(100 * (s.stats().moneySupply() - before.getAsDouble()) / before.getAsDouble());
    }

    /** Flows per plugin over the last {@code days} days including today, biggest net creator first. */
    public List<Flow> flows(int days) {
        Snapshot s = latest.get();
        if (s == null) return List.of();
        Map<String, long[]> by = new TreeMap<>();
        s.stats().flowsToday().forEach((p, f) -> by.put(p, Snapshot.flow(f)));
        if (days > 1) {
            long today = s.stats().dayIndex();
            store.flowSums(today - (days - 1), today - 1).forEach((metric, sum) -> {
                String rest = metric.substring(Snapshot.FLOW.length());
                int dot = rest.lastIndexOf('.');
                int field = Snapshot.FLOW_FIELDS.indexOf(rest.substring(dot + 1));
                if (dot > 0 && field >= 0) by.computeIfAbsent(rest.substring(0, dot), k -> new long[5])[field] += Math.round(sum);
            });
        }
        List<Flow> out = new ArrayList<>();
        by.forEach((p, v) -> out.add(new Flow(p, v)));
        out.sort(Comparator.comparingLong((Flow f) -> -f.v()[4]).thenComparing(Flow::plugin));
        return out;
    }

    public Map<String, Object> summary() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("currency", Map.of("decimals", currency.decimals(), "singular", currency.singular(), "plural", currency.plural(), "symbol", currency.symbol()));
        m.put("openAlerts", store.openAlerts());
        Snapshot s = latest.get();
        if (s == null) return m;
        var st = s.stats();
        m.put("ts", s.ts());
        m.put("day", st.dayIndex());
        m.put("moneySupply", st.moneySupply());
        m.put("activeMoneySupply", st.activeMoneySupply());
        m.put("treasury", st.treasuryBalance());
        m.put("issuedToday", st.issuedToday());
        m.put("destroyedToday", st.destroyedToday());
        m.put("treasuryInToday", st.treasuryInToday());
        m.put("treasuryOutToday", st.treasuryOutToday());
        m.put("activePlayers", st.activePlayersToday());
        OptionalDouble week = supplyWeekChange();
        m.put("supplyWeekChange", week.isPresent() ? week.getAsDouble() : null);
        List<Object> providers = new ArrayList<>();
        s.providers().forEach((id, name) -> {
            List<Object> ind = new ArrayList<>();
            for (Snapshot.Metric x : s.metrics()) if (x.provider().equals(id))
                ind.add(map("key", x.key(), "label", x.label(), "unit", x.unit() == null ? null : x.unit().name(), "value", x.value()));
            providers.add(map("id", id, "name", name, "indicators", ind));
        });
        m.put("providers", providers);
        return m;
    }

    /** {@code range} like 1d, 7d, 30d, 90d, 365d; {@code daily} reads the end-of-day totals instead of snapshots. */
    public Map<String, Object> series(String metric, String range, boolean daily) {
        long days = days(range);
        List<Object> pts = new ArrayList<>();
        if (daily) {
            Snapshot s = latest.get();
            long today = s == null ? Math.floorDiv(clock.getAsLong(), Downsampler.D1) : s.stats().dayIndex();
            // core's day index counts days since 1970-01-01, so the chart can show it as a date
            for (double[] p : store.dailySeries(metric, today - days)) pts.add(new double[]{p[0] * Downsampler.D1, p[1]});
        } else {
            var raw = store.series(metric, clock.getAsLong() - days * Downsampler.D1);
            var pointsOut = days <= 7 ? raw : Downsampler.bucket(raw, days <= 90 ? Downsampler.H1 : Downsampler.D1);
            for (var p : pointsOut) pts.add(new double[]{p.ts(), p.value()});
        }
        Store.MetricInfo info = store.catalogue().stream().filter(i -> i.key().equals(metric)).findFirst().orElse(null);
        return map("metric", metric, "label", info == null ? metric : info.label(), "unit", info == null ? null : info.unit().name(), "points", pts);
    }

    public List<Object> catalogue() {
        List<Object> out = new ArrayList<>();
        for (var i : store.catalogue()) out.add(map("key", i.key(), "label", i.label(), "unit", i.unit().name(), "provider", i.provider()));
        return out;
    }

    public Map<String, Object> plugins(String range) {
        int days = (int) Math.min(days(range), 3650);
        List<Flow> flows = flows(days);
        long issued = 0;
        for (Flow f : flows) issued += f.v()[0];
        double share = thresholds.get().printerShare();
        List<Object> rows = new ArrayList<>();
        for (Flow f : flows) {
            long[] v = f.v();
            rows.add(map("plugin", f.plugin(), "issued", v[0], "destroyed", v[1], "treasuryIn", v[2], "treasuryOut", v[3], "net", v[4],
                    "printer", issued > 0 && v[4] > share * issued && v[4] > thresholds.get().printerMinCoins()));
        }
        return map("range", days + "d", "plugins", rows);
    }

    public List<Object> alerts(boolean open) {
        List<Object> out = new ArrayList<>();
        for (Store.Alert a : store.alerts(open, 200))
            out.add(map("id", a.id(), "firstTs", a.firstTs(), "lastTs", a.lastTs(), "source", a.source(), "code", a.code(), "severity", a.severity().name(),
                    "message", a.message(), "hits", a.hits(), "open", a.open()));
        return out;
    }

    static long days(String range) {
        if (range == null || range.isBlank()) return 7;
        try {
            String r = range.trim().toLowerCase();
            long n = Long.parseLong(r.endsWith("d") ? r.substring(0, r.length() - 1) : r);
            return Math.max(1, Math.min(n, 3650));
        } catch (NumberFormatException e) { return 7; }
    }

    /** A LinkedHashMap from name, value pairs (Map.of rejects nulls and loses order). */
    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }
}
