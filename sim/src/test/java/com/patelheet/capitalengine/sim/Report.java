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
                last[1] / 100, last[3] / 100, last[10], last[11], last[13], last[12], last[21] * 100, last[18]));
        double interest = 0, upkeep = 0, exports = 0, imports = 0, tariffs = 0;
        for (double[] x : r.rows()) { interest += x[Simulation.INTEREST]; upkeep += x[Simulation.UPKEEP]; exports += x[Simulation.EXPORTS]; imports += x[Simulation.IMPORTS]; tariffs += x[Simulation.TARIFFS]; }
        md.append("- plugins: ").append(String.join(", ", r.scenario().plugins())).append('\n');
        if (r.scenario().has("bank")) md.append(String.format("- savings: %.2f coins; coins lent out: %.2f; loans behind: %.0f; written off: %.0f%n- interest paid: %.2f coins; land upkeep collected: %.2f coins%n",
                last[Simulation.SAVINGS] / 100, last[Simulation.LOAN_BOOK] / 100, last[Simulation.OVERDUE], last[Simulation.WRITTEN_OFF], interest / 100, upkeep / 100));
        if (r.scenario().has("trade")) md.append(String.format("- exports: %.2f coins; imports: %.2f coins (%.2f of it tariffs)%n", exports / 100, imports / 100, tariffs / 100));
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
