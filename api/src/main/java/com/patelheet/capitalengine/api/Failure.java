package com.patelheet.capitalengine.api;

/** Why a mutation failed. New values are only ever added at the end. */
public enum Failure {
    /** Not a failure. */
    NONE,
    /** An account would go below zero. */
    INSUFFICIENT_FUNDS,
    /** The caller's owner-set daily limit would be exceeded. */
    POLICY_LIMIT,
    /** The owner denied this plugin from moving money this way. */
    DENIED,
    /** Zero, negative or overflowing amount, or a bad type/memo. */
    INVALID_AMOUNT,
    /** An account doesn't exist. */
    NO_ACCOUNT,
    /** Tried to take money out of another plugin's account, or out of the Treasury directly. */
    NOT_ACCOUNT_OWNER,
    /** The reference was already used by this plugin; {@link TxResult#txId()} is the existing transaction. Treat as success. */
    DUPLICATE_REFERENCE,
    /** Core can't reach its database right now. */
    ECONOMY_PAUSED,
    /** Unexpected error inside core. */
    INTERNAL
}
