package com.patelheet.capitalengine.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * A committed transaction, as returned by history lookups and carried by events.
 *
 * @param txId         ledger sequence number
 * @param time         commit time
 * @param type         dotted type, for example {@code market.trade}
 * @param memo         plain-language description players see
 * @param sourcePlugin the plugin that caused it
 * @param actor        the player who caused it, if known
 * @param reference    idempotency key, if any
 * @param reversesTxId the transaction this one reverses, if any
 * @param postings     the legs; they sum to zero
 */
public record TxRecord(long txId, Instant time, String type, String memo, String sourcePlugin,
                       @Nullable UUID actor, @Nullable String reference, @Nullable Long reversesTxId,
                       List<Posting> postings) {}
