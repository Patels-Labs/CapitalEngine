package com.patelheet.capitalengine.api.event;

import java.util.List;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Fired asynchronously after the nightly ledger self-check. */
public class CapitalAuditEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final boolean ok;
    private final List<String> problems;

    /** Creates the event (async). */
    public CapitalAuditEvent(boolean ok, List<String> problems) { super(true); this.ok = ok; this.problems = List.copyOf(problems); }

    /** Whether the books balanced. */
    public boolean ok() { return ok; }
    /** What was wrong; empty when {@link #ok()}. */
    public List<String> problems() { return problems; }

    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    /** Bukkit handler list. */
    public static HandlerList getHandlerList() { return HANDLERS; }
}
