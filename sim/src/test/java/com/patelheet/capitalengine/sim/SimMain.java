package com.patelheet.capitalengine.sim;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code ./gradlew :sim:run --args="small-20.yml [--days N] [--seed S] [--plugins market,bank,trade] [--set key=v]... [--sweep key=v1,v2]... [--seeds 1,2,3]"}
 * for long runs and tuning sweeps. Several {@code --sweep}s run every combination; {@code --seeds} repeats each one per seed. Keys name their module: {@code market.autopilot.floor-per-active-player}, {@code bank.upkeep.per-100-blocks},
 * {@code trade.exports.daily-budget}, {@code core.starter-coins} or {@code sim.land-scale}. A key the module's config.yml doesn't have is refused.
 */
public final class SimMain {
    private SimMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) { System.err.println("usage: <scenario.yml> [--days N] [--seed S] [--plugins a,b] [--set key=v]... [--sweep key=v1,v2]"); return; }
        int days = 0;
        long seed = 0;
        List<String> plugins = null, seeds = null;
        List<Map<String, Object>> grid = new ArrayList<>(List.of(Map.of()));
        Map<String, Object> set = new java.util.LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--days" -> days = Integer.parseInt(args[++i]);
                case "--seed" -> seed = Long.parseLong(args[++i]);
                case "--plugins" -> plugins = List.of(args[++i].split(","));
                case "--set" -> { String[] kv = args[++i].split("=", 2); set.put(kv[0], number(kv[1])); }
                case "--seeds" -> seeds = List.of(args[++i].split(","));
                case "--sweep" -> {
                    String[] kv = args[++i].split("=", 2);
                    List<Map<String, Object>> next = new ArrayList<>();
                    for (var g : grid) for (String v : kv[1].split(",")) { var m = new java.util.LinkedHashMap<>(g); m.put(kv[0], number(v)); next.add(m); }
                    grid = next;
                }
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        Scenario base = load(args[0]);
        if (plugins != null) base = base.withPlugins(plugins);
        base = base.with(days > 0 ? days : base.days(), seed != 0 ? seed : base.seed(), set);
        List<String> summary = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String s : seeds == null ? List.of(String.valueOf(base.seed())) : seeds) {
            for (var g : grid) {
                Scenario sc = base.with(base.days(), Long.parseLong(s), g);
                String label = g.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(java.util.stream.Collectors.joining(" ")) + (seeds == null ? "" : " seed=" + s);
                var result = new Simulation(sc).run();
                Report.write(result, label.replace(' ', '_'));
                if (grid.size() > 1 && !seen.add(result.csv())) System.err.println("WARNING: " + label + " gave exactly the same run as an earlier setting: does the plugin read this key?");
                summary.add(line(sc, label, result));
                System.out.println(summary.get(summary.size() - 1));
            }
        }

    }

    /** One summary line: growth over the run and over its last 28 days (the band's own measure), price swing, trade share, unmet needs, and the money each add-on moved. */
    static String line(Scenario sc, String label, Simulation.Result r) {
        var rows = r.rows();
        double[] last = rows.get(rows.size() - 1);
        double month = rows.size() > 28 ? last[Simulation.MONEY] / Math.max(1, rows.get(rows.size() - 29)[Simulation.MONEY]) - 1 : 0;
        double swing = rows.stream().filter(x -> x[0] > 28).mapToDouble(x -> Math.abs(x[Simulation.INFLATION])).average().orElse(0);
        double interest = 0, upkeep = 0, exports = 0, imports = 0;
        for (double[] x : rows) { interest += x[Simulation.INTEREST]; upkeep += x[Simulation.UPKEEP]; exports += x[Simulation.EXPORTS]; imports += x[Simulation.IMPORTS]; }
        return String.format("%-14s %-46s %s  money x%.1f  last28d %+.0f%%  |infl|/wk %.1f%%  trade share %.0f%%  unmet %.0f%%  interest %.0f  upkeep %.0f  exports %.0f  imports %.0f  loans off %.0f  failures %d",
                sc.name(), label, r.passed() ? "PASS" : "FAIL", last[Simulation.MONEY] / Math.max(1, rows.get(0)[Simulation.MONEY]), month * 100, swing, last[Simulation.TRADE_SHARE],
                last[Simulation.UNMET] * 100, interest / 100, upkeep / 100, exports / 100, imports / 100, last[Simulation.WRITTEN_OFF], r.failures().size());
    }

    private static Object number(String v) {
        try { return v.contains(".") ? (Object) Double.parseDouble(v) : (Object) Long.parseLong(v); } catch (NumberFormatException e) { return v; }
    }

    /** A scenario file path, or the name of a bundled one. */
    static Scenario load(String nameOrPath) throws Exception {
        Path p = Path.of(nameOrPath);
        try (Reader r = Files.exists(p) ? Files.newBufferedReader(p) : new InputStreamReader(SimMain.class.getClassLoader().getResourceAsStream("scenarios/" + nameOrPath), StandardCharsets.UTF_8)) {
            return Scenario.parse(r);
        }
    }

    static String stamp() { return DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(java.time.LocalDateTime.now()); }
}
