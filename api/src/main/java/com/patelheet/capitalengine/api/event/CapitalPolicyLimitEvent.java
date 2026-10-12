package com.patelheet.capitalengine.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** A call hit an owner-set cap or was denied. May fire on any thread. */
public class CapitalPolicyLimitEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    /** Which limit was hit. */
    public enum LimitKind { ISSUE, TREASURY, DENIED }

    private final String plugin;
    private final LimitKind kind;
    private final long requested;
    private final long remaining;

    /** Creates the event. */
    public CapitalPolicyLimitEvent(boolean async, String plugin, LimitKind kind, long requested, long remaining) {
        super(async); this.plugin = plugin; this.kind = kind; this.requested = requested; this.remaining = remaining;
    }

    /** The plugin that hit the limit. */
    public String plugin() { return plugin; }
    /** Which limit. */
    public LimitKind kind() { return kind; }
    /** Coins it asked for. */
    public long requested() { return requested; }
    /** Coins it had left. */
    public long remaining() { return remaining; }

    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    /** Bukkit handler list. */
    public static HandlerList getHandlerList() { return HANDLERS; }
}
