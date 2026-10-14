package com.patelheet.capitalengine.sim;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Writes {@code daily.csv} and {@code summary.md} to {@code sim/build/sim-out/<scenario>/<timestamp>/} (sim/Plan.md §6). */
final class Report {
    private Report() {}

    static Path write(Simulation.Result r, String label) throws IOException {
        Path dir = Path.of("build", "sim-out", r.scenario().name(), SimMain.stamp() + (label.isEmpty() ? "" : "-" + label.replace('.', '_').replace('=', '_')));
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("daily.csv"), r.csv());
        double[] last = r.rows().isEmpty() ? new double[Simulation.COLUMNS.size()] : r.rows().get(r.rows().size() - 1);
        StringBuilder md = new StringBuilder("# ").append(r.scenario().name()).append(r.passed() ? ": PASS" : ": FAIL").append("\n\n");
        md.append("Seed ").append(r.scenario().seed()).append(", ").append(r.scenario().days()).append(" days.\n\n");
        md.append(String.format("- money supply: %.2f coins%n- Treasury: %.2f coins%n- weekly inflation: %.2f%%%n- savings rate: %.2f%% a week%n- player trade share: %.0f%%%n- server price gap: %.0f%%%n- unmet needs: %.1f%%%n- Gini: %.2f%n",
                last[1] / 100, last[3] / 100, last[10], last[11], last[13] * 100, last[12] * 100, last[21] * 100, last[18]));
        md.append("\n## End state of the items agents trade\n");
        r.items().forEach(i -> md.append("- ").append(i).append('\n'));
        md.append("\n## Why needs went unmet\n");
        if (r.whyUnmet().isEmpty()) md.append("Nothing.\n"); else r.whyUnmet().forEach((k, v) -> md.append("- ").append(k).append(": ").append(v).append('\n'));
        md.append("\n## Failures\n");
        if (r.failures().isEmpty()) md.append("None.\n"); else r.failures().forEach(f -> md.append("- ").append(f).append('\n'));
        Files.writeString(dir.resolve("summary.md"), md.toString());
        return dir;
    }
}
