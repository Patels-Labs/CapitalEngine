package com.patelheet.capitalengine.core.clock;

import com.patelheet.capitalengine.core.activity.ActivityTracker;
import java.time.Duration;
import java.util.UUID;

/** Internal hook (not part of the API) so the simulator can move time. Refuses to work unless {@code -Dcapitalengine.sim=true}. */
public final class ClockControl {
    private final EconomyClock clock;
    private final ActivityTracker activity;

    public ClockControl(EconomyClock clock, ActivityTracker activity) { this.clock = clock; this.activity = activity; }

    private static void guard() {
        if (!Boolean.getBoolean("capitalengine.sim")) throw new IllegalStateException("ClockControl is for the simulator only (-Dcapitalengine.sim=true)");
    }

    /** Fires the Day (and Week) boundaries that {@code n} days of time would cross. */
    public void advanceDays(int n) { guard(); clock.advance(Duration.ofDays(n)); }

    public void recordActivity(UUID player, int minutes) { guard(); activity.recordActivity(player, minutes); }
}
