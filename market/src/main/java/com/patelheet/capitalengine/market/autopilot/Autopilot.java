package com.patelheet.capitalengine.market.autopilot;

/** The weekly steering rule from the root Plan's Appendix A. Pure Java; no Bukkit. */
public final class Autopilot {
    private Autopilot() {}

    /** Loans cost this much more per week than savings pay. */
    static final double LOAN_PREMIUM = 0.03;

    /**
     * @param p              basket change this week; ignored during warm-up
     * @param activeMoney    api.stats().activeMoneySupply()
     * @param avgActive      api.stats().avgActivePlayers7d()
     * @param avgTreasuryIn  average coins/day paid into the Treasury over the last 7 days (recycled money)
     * @param playerShare    player-to-player share of recent trade value, 0..1
     * @param bank           whether CapitalBank is enabled (else the savings share goes to jobs and the buyer)
     * @param weeksRun       weeks the autopilot has already run
     */
    public record In(double p, long activeMoney, double avgActive, long avgTreasuryIn, double playerShare, boolean bank, int weeksRun) {}

    /**
     * @param targetWeekly weekly inflation target; {@code growthDaily} daily growth target of active money
     * @param floorPerActive coins/day (smallest unit) of new money per active player
     */
    public record Cfg(double targetWeekly, double growthDaily, long floorPerActive, double rateStart, double rateMin,
                      double rateMax, double jobsShare, int warmupWeeks) {}

    /** The published policy. Rates are per week here; budgets are per day. */
    public record State(long week, double rate, long savings, long jobs, long buyer, double spread, double index,
                        double inflation, long newMoney) {
        public double savingsPerDay() { return rate / 7; }
        public double loanPerDay() { return (rate + LOAN_PREMIUM) / 7; }
    }

    public static State step(State prev, In in, Cfg c, long week) {
        boolean warm = in.weeksRun() < c.warmupWeeks();
        double p = warm ? c.targetWeekly() : in.p();
        double error = p - c.targetWeekly();
        double rate = warm ? prev.rate() : clamp(prev.rate() + clamp(0.5 * error, -0.0025, 0.0025), c.rateMin(), c.rateMax());
        double base = Math.max(c.growthDaily() * in.activeMoney(), (double) c.floorPerActive() * Math.max(in.avgActive(), 1));
        long newMoney = Math.round(base * clamp(1 - 4 * error, 0.5, 1.5));
        double save = in.bank() ? clamp(0.33 + 4 * error, 0.1, 0.7) : 0;
        long total = newMoney + in.avgTreasuryIn();   // budgets also cover recycled Treasury money
        long spend = Math.round(total * (1 - save));
        return new State(week, rate, Math.round(total * save), Math.round(spend * c.jobsShare()), spend - Math.round(spend * c.jobsShare()),
                clamp(0.30 + 0.4 * in.playerShare(), 0.30, 0.50), prev.index() * (1 + p), p, newMoney);
    }

    /** The state before any week has run: config defaults. */
    public static State initial(Cfg c, In in, long week) {
        return step(new State(week, c.rateStart(), 0, 0, 0, 0.30, 100, 0, 0), in, c, week);
    }

    static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
}
