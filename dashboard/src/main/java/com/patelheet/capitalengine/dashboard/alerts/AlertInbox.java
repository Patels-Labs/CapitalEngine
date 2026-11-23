package com.patelheet.capitalengine.dashboard.alerts;

import com.patelheet.capitalengine.api.Severity;
import com.patelheet.capitalengine.dashboard.storage.Store;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Stores alerts from every source. The same source and code within {@code dedupe-minutes} of the last time it was seen only
 * counts another hit (acknowledged or not), so a condition that keeps firing is delivered once. New alerts go to {@code deliver}.
 */
public final class AlertInbox {
    private final Store store;
    private final LongSupplier clock;
    private final Consumer<Store.Alert> deliver;
    private volatile long dedupeMs;

    public AlertInbox(Store store, LongSupplier clock, long dedupeMs, Consumer<Store.Alert> deliver) {
        this.store = store; this.clock = clock; this.dedupeMs = dedupeMs; this.deliver = deliver;
    }

    public void dedupeMs(long ms) { dedupeMs = ms; }

    /** Returns the new alert, or null when it was merged into a recent one. Blocks on the database: call it off the main thread. */
    public Store.Alert raise(String source, Severity severity, String code, String message) {
        Store.Alert fresh;
        synchronized (this) {   // check-then-insert must not interleave, or two threads could both create the alert
            long now = clock.getAsLong();
            Store.Alert recent = store.recent(source, code, now - dedupeMs);
            if (recent != null) { store.bumpAlert(recent.id(), now, message); return null; }
            fresh = store.alert(store.insertAlert(now, source, code, severity, message));
        }
        deliver.accept(fresh);
        return fresh;
    }
}
