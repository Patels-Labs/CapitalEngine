package com.patelheet.capitalengine.api;

import java.util.UUID;

/**
 * One signed leg of a transaction. The postings of a transaction always sum to zero.
 *
 * @param accountId the account (the Mint's fixed id for created/destroyed money)
 * @param amount    positive = money in, negative = money out
 */
public record Posting(UUID accountId, long amount) {}
