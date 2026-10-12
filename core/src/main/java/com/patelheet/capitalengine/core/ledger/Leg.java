package com.patelheet.capitalengine.core.ledger;

/** One leg of a request. {@code from}/{@code to} are null where the Mint or Treasury is implied. */
public record Leg(Kind kind, Acct from, Acct to, long amount) {
    public enum Kind { MOVE, ISSUE, DESTROY, SERVER_PAY }
}
