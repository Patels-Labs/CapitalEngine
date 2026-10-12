package com.patelheet.capitalengine.api.event;

import com.patelheet.capitalengine.api.TxRecord;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Fired asynchronously after each transaction is safely in the database. */
public class CapitalTransactionEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final TxRecord record;

    /** Creates the event (async). */
    public CapitalTransactionEvent(TxRecord record) { super(true); this.record = record; }

    /** The committed transaction. */
    public TxRecord record() { return record; }

    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    /** Bukkit handler list. */
    public static HandlerList getHandlerList() { return HANDLERS; }
}
