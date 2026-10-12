package com.patelheet.capitalengine.api;

/** What an {@link Account} is, which decides who may take money out of it. */
public enum AccountKind {
    /** One per player. Any plugin may move money out (same trust as Vault). */
    PLAYER,
    /** An add-on's own account (escrow, savings, ...). Only the owning plugin may take money out. */
    PLUGIN,
    /** A non-player account made by a Vault plugin (for example a Towny town). */
    EXTERNAL,
    /** The server's wallet. Only {@code serverPay} may spend from it; anyone may pay into it. */
    TREASURY,
    /** Creates and destroys money. Never exposed as an {@link Account}; listed for completeness. */
    MINT
}
