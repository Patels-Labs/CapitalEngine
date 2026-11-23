package com.patelheet.capitalengine.sim;

import java.util.List;

/** The band checks of sim/Plan.md §5.6. Failures are collected with the first day they happened; nothing here stops the run. */
final class Asserts {
    private static final int WARMUP_DAYS = 28;

    private final Scenario sc;
    private final List<String> failures;
    private final double targetWeekly;

    Asserts(Scenario sc, List<String> failures) {
        this.sc = sc; this.failures = failures;
        this.targetWeekly = ((Number) sc.config().getOrDefault("autopilot.target-monthly-inflation", 0.02)).doubleValue() * 7 / 30;
    }

    private void fail(int day, String what) { failures.add("day " + day + ": " + what); }

    private double band(String key) { return sc.bands().get(key); }

    /** Books and escrow, every day. {@code books} is {sum of balances, accounts below zero}; {@code escrow} is held/needed pairs. */
    void daily(int day, double[] row, long[] books, long[] escrow) {
        if (books[0] != 0) fail(day, "balances add up to " + books[0] + " instead of 0");
        if (books[1] != 0) fail(day, books[1] + " account(s) below zero");
        if (escrow[0] != escrow[1]) fail(day, "market escrow holds " + escrow[0] + " but open buy orders need " + escrow[1]);
        if (escrow[2] != escrow[3]) fail(day, "jobs escrow holds " + escrow[2] + " but open jobs need " + escrow[3]);
    }

    /**
     * The bank, every day: its own check against the ledger is clean, interest stays within each saver's capped savings at today's rate and within
     * the day's budget, and no loan outlives its term plus the write-off days (a loan can never trap a player; at worst borrowing pauses).
     */
    void bank(int day, List<String> problems, long interestPaid, long interestBound, long budget, List<String> trapped) {
        problems.forEach(p -> fail(day, "bank check: " + p));
        if (interestPaid > interestBound) fail(day, "the bank paid " + interestPaid + " interest, more than capped savings at today's rate allow (" + interestBound + ")");
        if (interestPaid > budget) fail(day, "the bank paid " + interestPaid + " interest, over the day's budget of " + budget);
        trapped.forEach(t -> fail(day, t));
    }

    /** Exports never create more than Trade's daily budget and core's cap allow. */
    void trade(int day, double[] row, long budget) {
        if (row[Simulation.EXPORTS] > budget) fail(day, String.format("exports created %.0f, over the daily limit of %d", row[Simulation.EXPORTS], budget));
    }

    /** Bands that need the whole run. Inflation and growth are judged once, on the run as a whole, after the warm-up. */
    void finish(List<double[]> rows, List<String> auditProblems) {
        auditProblems.forEach(p -> fail(rows.size(), "audit: " + p));
        for (double[] r : rows) {
            int day = (int) r[0];
            if (day <= WARMUP_DAYS) continue;
            double inflation = r[10] / 100;
            if (Math.abs(inflation - targetWeekly) > band("weekly-inflation-abs-after-warmup")) { fail(day, String.format("weekly inflation %.3f is more than %.3f from the %.4f target", inflation, band("weekly-inflation-abs-after-warmup"), targetWeekly)); break; }
        }
        for (int i = 2 * 28; i < rows.size(); i++) {
            double growth = rows.get(i)[1] / Math.max(1, rows.get(i - 28)[1]) - 1;
            if (growth > band("max-monthly-growth") || growth < band("min-monthly-growth")) { fail((int) rows.get(i)[0], String.format("money grew %.1f%% in 28 days (allowed %.0f%% to %.0f%%)", growth * 100, band("min-monthly-growth") * 100, band("max-monthly-growth") * 100)); break; }
        }
        if (!rows.isEmpty() && rows.get(rows.size() - 1)[21] > band("max-unmet-needs"))
            fail(rows.size(), String.format("%.1f%% of food and tool needs went unmet (allowed %.0f%%)", rows.get(rows.size() - 1)[21] * 100, band("max-unmet-needs") * 100));
    }
}
