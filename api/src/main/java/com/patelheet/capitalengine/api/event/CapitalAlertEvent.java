package com.patelheet.capitalengine.api.event;

import com.patelheet.capitalengine.api.Severity;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** An alert for owners, raised with {@code CapitalApi.alert}. Dashboards listen. May fire on any thread. */
public class CapitalAlertEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final String source;
    private final Severity severity;
    private final String code;
    private final String message;

    /** Creates the event. */
    public CapitalAlertEvent(boolean async, String source, Severity severity, String code, String message) {
        super(async); this.source = source; this.severity = severity; this.code = code; this.message = message;
    }

    /** Name of the plugin that raised it. */
    public String source() { return source; }
    /** Severity. */
    public Severity severity() { return severity; }
    /** Short machine code, for example {@code ledger.audit}. */
    public String code() { return code; }
    /** Plain-language message. */
    public String message() { return message; }

    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    /** Bukkit handler list. */
    public static HandlerList getHandlerList() { return HANDLERS; }
}
