package com.patelheet.capitalengine.api;

import java.util.Optional;
import java.util.UUID;
import org.bukkit.plugin.Plugin;

/** Account lookup and creation. */
public interface Accounts {
    /** The player's account, created on first use. */
    Account player(UUID playerId);

    /** The calling plugin's own account {@code "<plugin>:<key>"} (lower-case), created on first use. */
    Account plugin(Plugin owner, String key);

    /** Finds an account by its namespaced key, for example {@code "capitalbank:savings:<uuid>"}. */
    Optional<Account> find(String namespacedKey);

    /** Finds an account by id. */
    Optional<Account> byId(UUID accountId);
}
