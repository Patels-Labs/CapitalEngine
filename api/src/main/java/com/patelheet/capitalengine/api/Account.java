package com.patelheet.capitalengine.api;

import java.util.Optional;
import java.util.UUID;

/** A read-only view of one account. All getters are instant and thread-safe. */
public interface Account {
    /** Stable unique id of the account. */
    UUID id();

    /** What kind of account this is. */
    AccountKind kind();

    /** Display name (player name, or the namespaced key for plugin accounts). */
    String name();

    /** Current balance in the smallest currency unit, read from memory. */
    long balance();

    /** The player who owns this account, for {@link AccountKind#PLAYER}. */
    Optional<UUID> ownerPlayer();

    /** The plugin that owns this account, for {@link AccountKind#PLUGIN} and {@link AccountKind#EXTERNAL}. */
    Optional<String> ownerPlugin();
}
