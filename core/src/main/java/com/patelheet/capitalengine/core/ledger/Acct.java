package com.patelheet.capitalengine.core.ledger;

import com.patelheet.capitalengine.api.Account;
import com.patelheet.capitalengine.api.AccountKind;
import java.util.Optional;
import java.util.UUID;

/** In-memory account. PLAYER accounts use the player's UUID as their id. */
public final class Acct implements Account {
    private final UUID id;
    private final AccountKind kind;
    private final String plugin;   // lower-case owner, PLUGIN/EXTERNAL only
    private final String key;      // PLUGIN: key after "plugin:"; EXTERNAL: name or uuid
    private volatile String name;
    volatile long balance;

    public Acct(UUID id, AccountKind kind, String plugin, String key, String name) {
        this.id = id; this.kind = kind; this.plugin = plugin; this.key = key; this.name = name;
    }

    @Override public UUID id() { return id; }
    @Override public AccountKind kind() { return kind; }
    @Override public String name() { return name; }
    @Override public long balance() { return balance; }
    @Override public Optional<UUID> ownerPlayer() { return kind == AccountKind.PLAYER ? Optional.of(id) : Optional.empty(); }
    @Override public Optional<String> ownerPlugin() { return Optional.ofNullable(plugin); }

    public String plugin() { return plugin; }
    public String key() { return key; }
    public void rename(String n) { name = n; }
    /** The namespaced lookup key, or null for player/treasury/mint accounts. */
    public String namespaced() { return plugin == null ? null : plugin + ":" + key; }
}
