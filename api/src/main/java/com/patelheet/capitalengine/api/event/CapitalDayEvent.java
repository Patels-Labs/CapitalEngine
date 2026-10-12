package com.patelheet.capitalengine.api.event;

import com.patelheet.capitalengine.api.Stats;
import java.time.LocalDate;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Economic day boundary, on the main/global thread. Fires in order and exactly once per index.
 * {@code catchUp} means the boundary happened while the server was offline; skip payouts such as interest.
 */
public class CapitalDayEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final long dayIndex;
    private final LocalDate date;
    private final boolean catchUp;
    private final Stats endedDay;

    /** Creates the event. */
    public CapitalDayEvent(long dayIndex, LocalDate date, boolean catchUp, Stats endedDay) {
        this.dayIndex = dayIndex; this.date = date; this.catchUp = catchUp; this.endedDay = endedDay;
    }

    /** The day that is starting. */
    public long dayIndex() { return dayIndex; }
    /** Calendar date of the day that is starting. */
    public LocalDate date() { return date; }
    /** Whether this boundary is being fired late after downtime. */
    public boolean catchUp() { return catchUp; }
    /** Final stats of the day that just ended, captured before the counters reset. */
    public Stats endedDay() { return endedDay; }

    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    /** Bukkit handler list. */
    public static HandlerList getHandlerList() { return HANDLERS; }
}
