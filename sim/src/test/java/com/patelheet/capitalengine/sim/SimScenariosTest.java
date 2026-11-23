package com.patelheet.capitalengine.sim;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** The CI gate (sim/Plan.md §8): short scenarios that must keep the books balanced and the economy inside its bands. */
class SimScenariosTest {
    private static Simulation.Result run(String scenario, int days, Map<String, Object> config) throws Exception {
        Scenario sc = SimMain.load(scenario + ".yml");
        var r = new Simulation(sc.with(days, sc.seed(), config)).run();
        Report.write(r, config.isEmpty() ? "ci" : "ci-" + String.join("_", config.keySet()));
        return r;
    }

    @Test void tinyServerIsCarriedByTheMarketMaker() throws Exception {
        var r = run("tiny-2", 60, Map.of());
        assertTrue(r.passed(), r.failures().toString());
    }

    /** small-20 has CapitalBank: every day the bank check is clean, interest stays within its caps and budget, and no loan traps anyone. */
    @Test void healthyTwentyPlayerEconomyStaysInBand() throws Exception {
        var r = run("small-20", 60, Map.of());
        assertTrue(r.passed(), r.failures().toString());
        assertTrue(total(r, Simulation.INTEREST) > 0, "savers earn interest");
        assertTrue(total(r, Simulation.UPKEEP) > 0, "big land owners pay upkeep");
        assertTrue(r.rows().stream().anyMatch(x -> x[Simulation.LOAN_BOOK] > 0), "borrowers borrow");
    }

    private static double total(Simulation.Result r, int col) { return r.rows().stream().mapToDouble(x -> x[col]).sum(); }

    /**
     * trade/Plan.md §11: with CapitalMarket installed, cheap Market items are bought and exported, which lifts the Market price toward the
     * world price. The same seed with and without CapitalTrade: coal, which miners make more of than anyone uses, sells to the server for
     * far below its world price without Trade, and close to it with Trade. Imports are a money sink and exports stay inside their limits.
     */
    @Test void exportsPutAFloorUnderMarketPrices() throws Exception {
        Scenario sc = SimMain.load("with-trade.yml");
        var on = new Simulation(sc).run();
        var off = new Simulation(sc.withPlugins(java.util.List.of("market", "bank"))).run();
        Report.write(on, "ci");
        assertTrue(on.passed(), on.failures().toString());
        double exported = on.rows().get(on.rows().size() - 1)[Simulation.WATCH_EXPORTED];
        double bidOn = avg(on, Simulation.WATCH_BID, 29), bidOff = avg(off, Simulation.WATCH_BID, 29), world = avg(on, Simulation.WATCH_WORLD, 29);
        assertTrue(exported > 0, "players export coal");
        assertTrue(bidOn >= 0.8 * world, "with Trade the server pays close to the world price for coal: " + bidOn + " vs world " + world);
        assertTrue(bidOff < 0.5 * world && bidOn > 1.5 * bidOff, "without Trade coal sells for much less: " + bidOff + " vs " + bidOn + " with Trade");
        assertTrue(total(on, Simulation.IMPORTS) > total(on, Simulation.TARIFFS) && total(on, Simulation.TARIFFS) > 0, "imports destroy coins and pay tariffs to the Treasury");
    }

    /** The average of a column from {@code fromDay} to the end. */
    private static double avg(Simulation.Result r, int col, int fromDay) {
        return r.rows().stream().filter(x -> x[0] >= fromDay).mapToDouble(x -> x[col]).average().orElseThrow();
    }

    /** One 70-day run (the flood is days 30 to 39) covers the books, the server's stock during the flood, and the recovery after it. */
    @Test void aFarmFloodRecoversWithinFourWeeksOfEnding() throws Exception {
        var r = run("farm-flood", 70, Map.of());
        assertTrue(r.passed(), r.failures().toString());
        double most = r.rows().stream().filter(x -> x[0] >= 30).mapToDouble(x -> x[24]).max().orElseThrow();
        assertTrue(most < 4 * 640, "\"the town needs\" jobs use up the server's extra wheat (stock " + most + "; it reached about 12x the 640 target before they did)");
        assertTrue(week(r, 64) >= week(r, 23) * 0.9, "wheat is back within 10% of its old price 4 weeks after the flood ends: " + week(r, 64) + " vs " + week(r, 23));
    }

    /** Wheat's average ask over the 7 days from {@code firstDay}: single days swing by up to 20% as town jobs fill and the server buys. */
    private static double week(Simulation.Result r, int firstDay) {
        return r.rows().stream().filter(x -> x[0] >= firstDay && x[0] < firstDay + 7).mapToDouble(x -> x[23]).average().orElseThrow();
    }

    /** with-trade has every plugin, so this covers the bank's day run and exports as well as the market. */
    @Test void theSameSeedGivesTheSameRun() throws Exception {
        String a = run("with-trade", 20, Map.of()).csv(), b = run("with-trade", 20, Map.of()).csv();
        assertEquals(a.length() == b.length() && a.equals(b) ? "" : firstDifference(a, b), "", "two runs of one seed must match");
    }

    /** Names the first row and columns where two daily.csv files differ. */
    private static String firstDifference(String a, String b) {
        String[] x = a.split("\n"), y = b.split("\n"), cols = x[0].split(",");
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            if (x[i].equals(y[i])) continue;
            String[] p = x[i].split(","), q = y[i].split(",");
            StringBuilder sb = new StringBuilder("row " + i + ":");
            for (int j = 0; j < p.length; j++) if (!p[j].equals(q[j])) sb.append(' ').append(cols[j]).append(' ').append(p[j]).append(" vs ").append(q[j]).append(';');
            return sb.toString();
        }
        return "different lengths";
    }

    @Test void aServerWithNobodyOnItRunsAndPasses() throws Exception {
        var sc = Scenario.parse(new java.io.StringReader("name: empty\nseed: 1\ndays: 10\nplayers: {}\n"));
        assertTrue(new Simulation(sc).run().passed());
    }

    /**
     * The plan asks for a deliberately broken config that the bands catch. No config we tried breaks this economy (money creation is bound by
     * what players sell, and core's per-plugin caps sit above that; see sim/RESULTS.md), so this feeds the checks bad numbers directly.
     */
    @Test void theBandsCatchProblemsWhenTheyHappen() {
        var sc = Scenario.parse(new java.io.StringReader("name: x\nseed: 1\ndays: 90\nplayers: {}\n"));
        var failures = new java.util.ArrayList<String>();
        var asserts = new Asserts(sc, failures);
        var rows = new java.util.ArrayList<double[]>();
        for (int day = 1; day <= 90; day++) {
            double[] r = new double[Simulation.COLUMNS.size()];
            r[0] = day;
            r[1] = 10_000 * Math.pow(1.1, day);   // money grows 10% a day
            r[10] = day > 30 ? 40 : 0;            // and prices jump 40% a week
            r[21] = 0.5;                          // and half of all needs go unmet
            rows.add(r);
        }
        asserts.daily(5, rows.get(4), new long[]{7, 2}, new long[]{100, 90, 5, 6});
        asserts.bank(6, java.util.List.of("loan #3: its payout is not in the ledger"), 500, 400, 450, java.util.List.of("loan #4 is 40 days old"));
        rows.get(6)[Simulation.EXPORTS] = 6000;
        asserts.trade(7, rows.get(6), 5000);
        asserts.finish(rows, java.util.List.of("balance mismatch"));
        String all = String.join("\n", failures);
        for (String expected : new String[]{"add up to 7", "2 account(s) below zero", "market escrow holds 100", "jobs escrow holds 5", "audit: balance mismatch", "weekly inflation", "money grew", "unmet",
                "bank check: loan #3", "more than capped savings", "over the day's budget", "loan #4 is 40 days old", "exports created 6000"})
            assertTrue(all.contains(expected), expected + " should have been reported, got:\n" + all);
        var quiet = new java.util.ArrayList<String>();
        new Asserts(sc, quiet).daily(1, new double[Simulation.COLUMNS.size()], new long[]{0, 0}, new long[]{1, 1, 2, 2});
        new Asserts(sc, quiet).bank(1, java.util.List.of(), 100, 100, 100, java.util.List.of());
        new Asserts(sc, quiet).trade(1, new double[Simulation.COLUMNS.size()], 5000);
        assertTrue(quiet.isEmpty(), "a healthy day reports nothing");
    }
}
