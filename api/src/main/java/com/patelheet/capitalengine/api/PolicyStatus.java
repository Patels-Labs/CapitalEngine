package com.patelheet.capitalengine.api;

/**
 * A plugin's remaining owner-set limits today. {@link Long#MAX_VALUE} means unlimited.
 *
 * @param issueRemainingToday    coins it may still create today
 * @param treasuryRemainingToday coins it may still take from the Treasury today
 * @param denied                 the owner has denied this plugin from moving money without a counterparty
 */
public record PolicyStatus(long issueRemainingToday, long treasuryRemainingToday, boolean denied) {}
