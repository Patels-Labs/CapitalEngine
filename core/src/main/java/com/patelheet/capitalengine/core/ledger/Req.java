package com.patelheet.capitalengine.core.ledger;

import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/** A mutation request. {@code bypass} skips ownership and owner limits (admin tools only, never reachable from the API). */
public record Req(String plugin, String type, String memo, @Nullable UUID actor, @Nullable String reference,
                  List<Leg> legs, boolean bypass) {}
