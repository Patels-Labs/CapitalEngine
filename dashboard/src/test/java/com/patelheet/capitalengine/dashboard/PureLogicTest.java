package com.patelheet.capitalengine.dashboard;

import static org.junit.jupiter.api.Assertions.*;

import com.patelheet.capitalengine.api.PluginFlow;
import com.patelheet.capitalengine.api.Severity;
import com.patelheet.capitalengine.api.Stats;
import com.patelheet.capitalengine.api.service.Indicator;
import com.patelheet.capitalengine.api.service.Unit;
import com.patelheet.capitalengine.dashboard.alerts.BuiltInRules;
import com.patelheet.capitalengine.dashboard.alerts.BuiltInRules.Fired;
import com.patelheet.capitalengine.dashboard.alerts.BuiltInRules.History;
import com.patelheet.capitalengine.dashboard.snapshot.Downsampler;
import com.patelheet.capitalengine.dashboard.snapshot.Downsampler.Point;
import com.patelheet.capitalengine.dashboard.snapshot.Snapshot;
import com.patelheet.capitalengine.dashboard.web.Json;
import com.patelheet.capitalengine.dashboard.web.Metrics;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Snapshot maths, downsampling, the built-in rules and the hand-written JSON and Prometheus output. */
class PureLogicTest {
    static final BuiltInRules.Thresholds T = new BuiltInRules.Thresholds(3.0, 0.5, 100_000, 0.20);
    static final History NONE = new History(List.of(), OptionalDouble.empty(), false);

    static Stats stats(long supply, long treasury, long issued, Map<String, PluginFlow> flows) {
        return new Stats(supply, supply, treasury, issued, 0, 0, 0, flows, 3, 120, 2.5, 20_000, 2_857);
    }

    static PluginFlow issued(long n) { return new PluginFlow(n, 0, 0, 0, 0); }

    static List<String> codes(List<Fired> f) { return f.stream().map(Fired::code).toList(); }

    @Test void snapshotFlattensCoreFlowsAndIndicators() {
        var s = stats(1_000_000, 5_000, 300, Map.of("Jobs", new PluginFlow(500, 200, 10, 20, 0)));
        var snap = Snapshot.of(42, s, List.of(new Snapshot.Reading("capitalmarket", "Market", List.of(new Indicator("price_index", "Price index", 1.02, Unit.INDEX)))),
                f -> f.startsWith("flow-") ? "<plugin> " + f : "L " + f);
        assertEquals(1_000_000, snap.value("core.moneySupply"));
        assertEquals(500, snap.value("core.flow.Jobs.issued"));
        assertEquals(300, snap.value("core.flow.Jobs.net"), "net new money is issued minus destroyed");
        assertEquals(1.02, snap.value("capitalmarket.price_index"));
        assertTrue(Double.isNaN(snap.value("nope")));
        assertEquals("Jobs flow-issued", snap.metrics().stream().filter(m -> m.key().equals("core.flow.Jobs.issued")).findFirst().orElseThrow().label());
        assertEquals("capitalmarket", snap.metrics().stream().filter(m -> m.key().equals("capitalmarket.price_index")).findFirst().orElseThrow().provider());
        assertEquals(Map.of("capitalmarket", "Market"), snap.providers());
    }

    @Test void downsamplerAveragesPerMetricAndBucket() {
        long h = Downsampler.H1;
        var out = Downsampler.bucket(List.of(new Point("a", 0, 1), new Point("a", h - 1, 3), new Point("a", h, 10), new Point("b", 5, 7), new Point("b", 6, Double.NaN)), h);
        assertEquals(List.of(new Point("a", 0, 2), new Point("a", h, 10), new Point("b", 0, 7)), out);
        assertEquals(-h, Downsampler.align(-1, h), "buckets floor, also before the epoch");
        assertEquals(2 * h, Downsampler.align(2 * h + 59, h));
    }

    @Test void printerFiresForOnePluginMakingMostNewMoney() {
        var s = stats(1_000_000, 10, 600_000, Map.of("Jobs", issued(500_000), "CapitalEngine", issued(100_000)));
        var fired = BuiltInRules.evaluate(s, NONE, T, Set.of("Jobs", "CapitalEngine"), Long::toString);
        assertEquals(List.of("dash.plugin_printer"), codes(fired));
        assertEquals("Jobs", fired.get(0).source(), "per-plugin rules use the plugin as the source, so each plugin dedupes on its own");
        assertEquals(Severity.WARN, fired.get(0).severity());
        assertEquals(List.of("plugin", "Jobs", "amount", "500000", "share", 83L), List.of(fired.get(0).kv()));

        // under the share, or under the minimum: quiet
        assertTrue(BuiltInRules.evaluate(stats(1, 10, 600_000, Map.of("Jobs", issued(250_000), "B", issued(350_000))), NONE,
                new BuiltInRules.Thresholds(3, 0.6, 100_000, 0.2), Set.of("Jobs", "B"), Long::toString).isEmpty());
        assertTrue(BuiltInRules.evaluate(stats(1, 10, 50_000, Map.of("Jobs", issued(50_000))), NONE, T, Set.of("Jobs"), Long::toString).isEmpty());
        // destroying money counts against it (net)
        var net = BuiltInRules.evaluate(stats(1, 10, 500_000, Map.of("Shop", new PluginFlow(500_000, 450_000, 0, 0, 0))), NONE, T, Set.of("Shop"), Long::toString);
        assertTrue(net.isEmpty());
    }

    @Test void unknownPluginIsReportedOnlyWhenNew() {
        var s = stats(1, 10, 0, Map.of("Jobs", new PluginFlow(0, 0, 5, 0, 0)));
        assertEquals(List.of("dash.unknown_plugin"), codes(BuiltInRules.evaluate(s, NONE, T, Set.of(), Long::toString)));
        assertTrue(BuiltInRules.evaluate(s, NONE, T, Set.of("Jobs"), Long::toString).isEmpty());
    }

    @Test void issuanceSpikeNeedsThreeDaysAndThreeTimesTheAverage() {
        var s = stats(1, 10, 3_100, Map.of());
        assertTrue(BuiltInRules.evaluate(s, new History(List.of(1000.0, 1000.0), OptionalDouble.empty(), false), T, Set.of(), Long::toString).isEmpty(), "two days is not enough");
        var fired = BuiltInRules.evaluate(s, new History(List.of(1000.0, 1000.0, 1000.0), OptionalDouble.empty(), false), T, Set.of(), Long::toString);
        assertEquals(List.of("dash.issuance_spike"), codes(fired));
        assertTrue(BuiltInRules.evaluate(stats(1, 10, 2_900, Map.of()), new History(List.of(1000.0, 1000.0, 1000.0), OptionalDouble.empty(), false), T, Set.of(), Long::toString).isEmpty());
    }

    @Test void treasuryEmptyAndSupplyDrop() {
        assertEquals(List.of("dash.treasury_empty"), codes(BuiltInRules.evaluate(stats(1, 0, 0, Map.of()), new History(List.of(), OptionalDouble.empty(), true), T, Set.of(), Long::toString)));
        assertTrue(BuiltInRules.evaluate(stats(1, 5, 0, Map.of()), new History(List.of(), OptionalDouble.empty(), true), T, Set.of(), Long::toString).isEmpty(), "money in the Treasury now");
        assertEquals(List.of("dash.supply_drop"), codes(BuiltInRules.evaluate(stats(790, 5, 0, Map.of()), new History(List.of(), OptionalDouble.of(1000), false), T, Set.of(), Long::toString)));
        assertTrue(BuiltInRules.evaluate(stats(810, 5, 0, Map.of()), new History(List.of(), OptionalDouble.of(1000), false), T, Set.of(), Long::toString).isEmpty());
    }

    @Test void prometheusTextHasOneTypeLinePerFamilyAndEscapedLabels() {
        var s = stats(123_456, 0, 0, Map.of("Jobs", issued(1_000), "We\"ird\\", issued(1)));
        var snap = Snapshot.of(1, s, List.of(new Snapshot.Reading("capitalmarket", "Market", List.of(
                new Indicator("price_index", "Price index", 1.25, Unit.INDEX), new Indicator("new_money", "New money", 5_000, Unit.COINS), new Indicator("bad", "Bad", Double.NaN, Unit.COUNT)))), f -> f);
        String text = Metrics.render(snap, 2, 3);
        assertTrue(text.contains("# TYPE capital_core_money_supply gauge\ncapital_core_money_supply 1234.56\n"), text);
        assertTrue(text.contains("capital_core_avg_active_players7d 2.5\n"));
        assertTrue(text.contains("capital_flow_net{plugin=\"Jobs\"} 10\n"), "coins are whole coins");
        assertTrue(text.contains("capital_flow_issued{plugin=\"We\\\"ird\\\\\"} 0.01\n"));
        assertTrue(text.contains("capital_indicator{provider=\"capitalmarket\",key=\"price_index\"} 1.25\n"));
        assertTrue(text.contains("capital_indicator{provider=\"capitalmarket\",key=\"new_money\"} 50\n"));
        assertTrue(text.contains("capital_indicator{provider=\"capitalmarket\",key=\"bad\"} NaN\n"));
        assertTrue(text.contains("capital_dashboard_open_alerts 3\n"));
        assertEquals(1, text.split("# TYPE capital_indicator gauge", -1).length - 1, "one TYPE line per family");
        assertEquals(1, text.split("# TYPE capital_flow_net gauge", -1).length - 1);
        for (String line : text.split("\n")) assertTrue(line.matches("# TYPE [a-z_0-9]+ gauge|[a-z_0-9]+(\\{.*\\})? (-?[0-9.E]+|NaN|[+-]Inf)"), line);
    }

    @Test void jsonEscapesAndHasNoNaN() {
        assertEquals("{\"a\":\"x\\\"\\\\\\n\\u003c/script\\u003e\",\"b\":[1,2.5,null],\"c\":null,\"d\":true}",
                Json.write(new java.util.LinkedHashMap<>(Map.of()) {{ put("a", "x\"\\\n</script>"); put("b", new double[]{1, 2.5, Double.NaN}); put("c", null); put("d", true); }}));
        assertEquals("[null,3]", Json.write(List.of(Double.POSITIVE_INFINITY, 3L)));
    }
}
