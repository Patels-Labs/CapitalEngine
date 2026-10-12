package com.patelheet.capitalengine.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Economic week boundary, fired after the {@link CapitalDayEvent} that starts the new week. */
public class CapitalWeekEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final long weekIndex;
    private final boolean catchUp;

    /** Creates the event. */
    public CapitalWeekEvent(long weekIndex, boolean catchUp) { this.weekIndex = weekIndex; this.catchUp = catchUp; }

    /** The week that is starting. */
    public long weekIndex() { return weekIndex; }
    /** Whether this boundary is being fired late after downtime. */
    public boolean catchUp() { return catchUp; }

    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    /** Bukkit handler list. */
    public static HandlerList getHandlerList() { return HANDLERS; }
}
