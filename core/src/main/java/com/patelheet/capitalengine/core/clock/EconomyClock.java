package com.patelheet.capitalengine.core.clock;

import com.patelheet.capitalengine.api.event.CapitalDayEvent;
import com.patelheet.capitalengine.api.event.CapitalWeekEvent;
import com.patelheet.capitalengine.core.CoreConfig;
import com.patelheet.capitalengine.core.ledger.Ledger;
import com.patelheet.capitalengine.core.storage.ClockRepo;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import org.bukkit.Bukkit;

/**
 * The shared economic clock: Day and Week events, exactly once per index and in order. The date maths is pure and static
 * so it can be tested; {@link #tick()} compares the current index with the last one fired and fires what is due.
 */
public final class EconomyClock {
    /** One boundary to fire. {@code newWeek} is true when a Week event follows this Day event. */
    public record Boundary(long day, long week, boolean newWeek, boolean catchUp) {}

    /** What is due, plus how many missed days were too old to fire. */
    public record Due(List<Boundary> boundaries, long skipped) {}

    private final ClockRepo repo;
    private final Ledger ledger;
    private final Logger log;
    private volatile CoreConfig cfg;
    private volatile long offsetMillis;   // ClockControl moves time forward for the simulator
    private long last = Long.MIN_VALUE;
    private boolean started;

    public EconomyClock(ClockRepo repo, Ledger ledger, Logger log, CoreConfig cfg) {
        this.repo = repo; this.ledger = ledger; this.log = log; this.cfg = cfg;
    }

    public void config(CoreConfig c) { cfg = c; }

    // ---- pure date maths --------------------------------------------------------------------------------------

    /** Days since 1970-01-01 in {@code zone}, with the day starting at {@code dayStart} instead of midnight. */
    public static long dayIndex(Instant now, ZoneId zone, LocalTime dayStart) {
        return now.atZone(zone).toLocalDateTime().minusHours(dayStart.getHour()).minusMinutes(dayStart.getMinute()).toLocalDate().toEpochDay();
    }

    /** Weeks since the first {@code weekStart} on or after 1970-01-01. */
    public static long weekIndex(long dayIndex, DayOfWeek weekStart) {
        long first = LocalDate.ofEpochDay(0).with(TemporalAdjusters.nextOrSame(weekStart)).toEpochDay();
        return Math.floorDiv(dayIndex - first, 7);
    }

    /** What to fire when the last fired day is {@code last} and it is now {@code current}. */
    public static Due due(long last, long current, DayOfWeek weekStart, int maxCatchUp, boolean startup) {
        List<Boundary> out = new ArrayList<>();
        long first = Math.max(last + 1, current - maxCatchUp + 1);
        long skipped = Math.max(0, first - (last + 1));
        for (long d = first; d <= current; d++) {
            long w = weekIndex(d, weekStart);
            out.add(new Boundary(d, w, w != weekIndex(d - 1, weekStart), startup || d < current));
        }
        return new Due(out, skipped);
    }

    // ---- runtime ----------------------------------------------------------------------------------------------

    public Instant now() { return Instant.ofEpochMilli(System.currentTimeMillis() + offsetMillis); }
    public long currentDay() { return dayIndex(now(), cfg.zone(), cfg.dayStart()); }
    public long weekOf(long day) { return weekIndex(day, cfg.weekStart()); }

    /** Simulator hook: jumps time forward, then fires what is due. */
    public void advance(java.time.Duration d) { offsetMillis += d.toMillis(); tick(); }

    /** First run (startup): fire anything missed while the server was offline. */
    public void start() {
        try {
            last = repo.get("last_day_index").orElse(currentDay());
        } catch (SQLException e) {
            log.severe("Could not read the economic clock: " + e.getMessage());
            return;
        }
        started = true;
        run(true);
    }

    /** Every 60 seconds on the global scheduler. */
    public void tick() { if (started) run(false); }

    private synchronized void run(boolean startup) {
        long current = currentDay();
        if (current <= last) return;
        Due due = due(last, current, cfg.weekStart(), cfg.maxCatchUpDays(), startup);
        if (due.skipped() > 0) log.info("Skipped " + due.skipped() + " economic day(s) missed while offline (max-catch-up-days is " + cfg.maxCatchUpDays() + ").");
        for (Boundary b : due.boundaries()) {
            var ended = ledger.stats();   // captured before core's own handlers reset the counters
            Bukkit.getPluginManager().callEvent(new CapitalDayEvent(b.day(), LocalDate.ofEpochDay(b.day()), b.catchUp(), ended));
            if (b.newWeek()) Bukkit.getPluginManager().callEvent(new CapitalWeekEvent(b.week(), b.catchUp()));
            last = b.day();
            try { repo.set("last_day_index", last); repo.set("last_week_index", b.week()); }
            catch (SQLException e) { log.severe("Could not save the economic clock: " + e.getMessage()); }
        }
        last = current;
    }
}
