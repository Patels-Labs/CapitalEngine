package com.patelheet.capitalengine.core.ledger;

import com.patelheet.capitalengine.api.TxResult;
import java.util.concurrent.CompletableFuture;

/** {@code result} is known immediately; {@code persisted} completes once the transaction is in the database. */
public record Applied(TxResult result, CompletableFuture<TxResult> persisted) {}
