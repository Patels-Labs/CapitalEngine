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

/** {@code ./gradlew :sim:run --args="small-20.yml [--days N] [--seed S] [--sweep key=v1,v2]"} for long runs and tuning sweeps. */
public final class SimMain {
    private SimMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) { System.err.println("usage: <scenario.yml> [--days N] [--seed S] [--sweep key=v1,v2]"); return; }
        int days = 0;
        long seed = 0;
        String sweepKey = null;
        List<String> sweepValues = List.of();
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--days" -> days = Integer.parseInt(args[++i]);
                case "--seed" -> seed = Long.parseLong(args[++i]);
                case "--sweep" -> { String[] kv = args[++i].split("=", 2); sweepKey = kv[0]; sweepValues = List.of(kv[1].split(",")); }
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        Scenario base = load(args[0]);
        if (days > 0 || seed != 0) base = base.with(days > 0 ? days : base.days(), seed != 0 ? seed : base.seed(), Map.of());
        List<String> summary = new ArrayList<>();
        for (String v : sweepKey == null ? List.of("") : sweepValues) {
            Scenario sc = sweepKey == null ? base : base.with(base.days(), base.seed(), Map.of(sweepKey.replaceFirst("^market\\.", ""), number(v)));
            var result = new Simulation(sc).run();
            Path dir = Report.write(result, sweepKey == null ? "" : sweepKey + "=" + v);
            double[] last = result.rows().get(result.rows().size() - 1);
            double swing = result.rows().stream().filter(r -> r[0] > 28).mapToDouble(r -> Math.abs(r[10])).average().orElse(0);
            summary.add(String.format("%-14s %-48s %s  money x%.1f  |inflation|/wk %.1f%%  trade share %.0f%%  unmet %.0f%%  failures %d", sc.name(), sweepKey == null ? "" : sweepKey + "=" + v,
                    result.passed() ? "PASS" : "FAIL", last[1] / Math.max(1, result.rows().get(0)[1]), swing, last[13], last[21] * 100, result.failures().size()));
        }
        summary.forEach(System.out::println);
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
