package com.patelheet.capitalengine.core.vault;

import com.patelheet.capitalengine.api.Failure;
import com.patelheet.capitalengine.api.TxResult;
import com.patelheet.capitalengine.core.Messages;
import com.patelheet.capitalengine.core.activity.ActivityTracker;
import com.patelheet.capitalengine.core.api.CurrencyImpl;
import com.patelheet.capitalengine.core.ledger.Acct;
import com.patelheet.capitalengine.core.ledger.Leg;
import com.patelheet.capitalengine.core.ledger.Leg.Kind;
import com.patelheet.capitalengine.core.ledger.Ledger;
import com.patelheet.capitalengine.core.ledger.Req;
import com.patelheet.capitalengine.core.policy.PolicyManager.VaultMode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

/**
 * What both Vault adapters share: finding accounts and turning a deposit/withdraw with no counterparty into ledger legs
 * according to the calling plugin's {@code vault-mode} (see core/Plan.md §5.5). Deposits and withdrawals mirror each
 * other, so a shop that withdraws from the buyer and deposits to the seller nets to about zero in every mode.
 */
public final class VaultOps {
    final Ledger ledger;
    final CurrencyImpl currency;
    final ActivityTracker players;
    final Messages msgs;

    public VaultOps(Ledger ledger, CurrencyImpl currency, ActivityTracker players, Messages msgs) {
        this.ledger = ledger; this.currency = currency; this.players = players; this.msgs = msgs;
    }

    // ---- accounts ---------------------------------------------------------------------------------------------

    /** A player's account, created on first use (this is what makes balances of never-seen players work). */
    Acct playerAccount(UUID id) {
        String name = players.nameOf(id);
        if (name == null) { OfflinePlayer op = Bukkit.getOfflinePlayer(id); name = op.getName(); }
        return ledger.player(id, name);
    }

    /** An existing account for a UUID, or null. */
    Acct existing(UUID id) { return ledger.byId(id); }

    boolean isRealPlayer(UUID id) {
        return ledger.byId(id) != null && ledger.byId(id).ownerPlayer().isPresent() || players.isKnown(id) || Bukkit.getOfflinePlayer(id).hasPlayedBefore();
    }

    /** An existing account by name: a known player first, then a non-player account. */
    Acct byName(String name) {
        UUID id = players.uuidOf(name);
        if (id != null) return playerAccount(id);
        return ledger.externalByName(name);
    }

    /** Creates a non-player account owned by {@code plugin}; false if it already exists. */
    boolean createExternal(String plugin, UUID id, String key, String name) {
        boolean existed = ledger.byId(id) != null;
        ledger.external(id, plugin, key, name);
        return !existed;
    }

    static UUID nameId(String name) { return UUID.nameUUIDFromBytes(("external:" + name.toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8)); }

    // ---- money ------------------------------------------------------------------------------------------------

    TxResult deposit(String plugin, Acct to, long units) {
        VaultMode mode = ledger.policy().limit(plugin.toLowerCase(Locale.ROOT)).mode();
        if (mode == VaultMode.DENY) return deny(plugin);
        Leg leg = new Leg(mode == VaultMode.TREASURY ? Kind.SERVER_PAY : Kind.ISSUE, null, to, units);
        return ledger.apply(new Req(plugin, "vault.deposit", msgs.plain("memo.vault-deposit", "plugin", plugin), null, null, List.of(leg), false)).result();
    }

    TxResult withdraw(String plugin, Acct from, long units) {
        VaultMode mode = ledger.policy().limit(plugin.toLowerCase(Locale.ROOT)).mode();
        if (mode == VaultMode.DENY) return deny(plugin);
        Leg leg = mode == VaultMode.TREASURY ? new Leg(Kind.MOVE, from, ledger.treasury(), units) : new Leg(Kind.DESTROY, from, null, units);
        return ledger.apply(new Req(plugin, "vault.withdraw", msgs.plain("memo.vault-withdraw", "plugin", plugin), null, null, List.of(leg), false)).result();
    }

    private TxResult deny(String plugin) {
        return new TxResult(false, 0, Failure.DENIED, "CapitalEngine: plugin " + plugin + " is not allowed to create money", 0, 0);
    }
}
