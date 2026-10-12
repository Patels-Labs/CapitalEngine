package com.patelheet.capitalengine.core.ledger;

import java.util.Set;
import java.util.UUID;

/** Activity numbers the ledger needs for {@code Stats}, pushed in by the activity tracker. */
public record Activity(int playersToday, long minutesToday, double avg7d, Set<UUID> dormant) {
    public static final Activity NONE = new Activity(0, 0, 0, Set.of());
}
