package com.patelheet.capitalengine.api;

/**
 * Outcome of a mutation.
 *
 * @param success      whether the transaction was applied
 * @param txId         the transaction id (the existing one for {@link Failure#DUPLICATE_REFERENCE}), or 0
 * @param failure      {@link Failure#NONE} on success
 * @param message      short plain-language reason, safe to show players
 * @param fromTreasury part of a {@code serverPay} paid from the Treasury
 * @param issued       part of a {@code serverPay} or {@code issue} that was newly created
 */
public record TxResult(boolean success, long txId, Failure failure, String message, long fromTreasury, long issued) {}
