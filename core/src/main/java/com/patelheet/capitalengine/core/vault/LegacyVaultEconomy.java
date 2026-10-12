package com.patelheet.capitalengine.core.vault;

import com.patelheet.capitalengine.api.TxResult;
import com.patelheet.capitalengine.core.ledger.Acct;
import com.patelheet.capitalengine.core.policy.Attribution;
import java.util.List;
import java.util.UUID;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.economy.EconomyResponse.ResponseType;
import org.bukkit.OfflinePlayer;

/**
 * {@code net.milkbowl.vault.economy.Economy} on top of the ledger. Amounts are doubles here, rounded half-even to the
 * smallest unit; world overloads ignore the world; bank methods are not implemented (CapitalBusiness will add them).
 */
public final class LegacyVaultEconomy implements Economy {
    private final VaultOps ops;
    private final Attribution attribution;

    public LegacyVaultEconomy(VaultOps ops, Attribution attribution) { this.ops = ops; this.attribution = attribution; }

    private static final EconomyResponse NO_BANKS = new EconomyResponse(0, 0, ResponseType.NOT_IMPLEMENTED, "CapitalEngine does not support banks.");

    @Override public boolean isEnabled() { return true; }
    @Override public String getName() { return "CapitalEngine"; }
    @Override public boolean hasBankSupport() { return false; }
    @Override public int fractionalDigits() { return ops.currency.decimals(); }
    @Override public String format(double amount) {
        try { return ops.currency.format(ops.currency.fromDouble(amount)); } catch (IllegalArgumentException e) { return String.valueOf(amount); }
    }
    @Override public String currencyNamePlural() { return ops.currency.plural(); }
    @Override public String currencyNameSingular() { return ops.currency.singular(); }

    // ---- accounts ---------------------------------------------------------------------------------------------

    @Override public boolean hasAccount(String name) { return ops.byName(name) != null; }
    @Override public boolean hasAccount(OfflinePlayer p) { return ops.existing(p.getUniqueId()) != null || ops.isRealPlayer(p.getUniqueId()); }
    @Override public boolean hasAccount(String name, String world) { return hasAccount(name); }
    @Override public boolean hasAccount(OfflinePlayer p, String world) { return hasAccount(p); }

    @Override public boolean createPlayerAccount(OfflinePlayer p) {
        boolean existed = ops.existing(p.getUniqueId()) != null;
        ops.playerAccount(p.getUniqueId());
        return !existed;
    }
    @Override public boolean createPlayerAccount(String name) {
        if (ops.byName(name) != null) return false;
        return ops.createExternal(attribution.caller(), VaultOps.nameId(name), name, name);
    }
    @Override public boolean createPlayerAccount(String name, String world) { return createPlayerAccount(name); }
    @Override public boolean createPlayerAccount(OfflinePlayer p, String world) { return createPlayerAccount(p); }

    // ---- balances ---------------------------------------------------------------------------------------------

    private double balance(Acct a) { return a == null ? 0 : ops.currency.toDouble(a.balance()); }
    private Acct existingOf(OfflinePlayer p) { return ops.existing(p.getUniqueId()); }

    @Override public double getBalance(String name) { return balance(ops.byName(name)); }
    @Override public double getBalance(OfflinePlayer p) { return balance(existingOf(p)); }
    @Override public double getBalance(String name, String world) { return getBalance(name); }
    @Override public double getBalance(OfflinePlayer p, String world) { return getBalance(p); }

    private boolean has(Acct a, double amount) {
        try { return a != null && a.balance() >= ops.currency.fromDouble(amount); } catch (IllegalArgumentException e) { return false; }
    }
    @Override public boolean has(String name, double amount) { return has(ops.byName(name), amount); }
    @Override public boolean has(OfflinePlayer p, double amount) { return has(existingOf(p), amount); }
    @Override public boolean has(String name, String world, double amount) { return has(name, amount); }
    @Override public boolean has(OfflinePlayer p, String world, double amount) { return has(p, amount); }

    // ---- money ------------------------------------------------------------------------------------------------

    private EconomyResponse move(Acct a, double amount, boolean deposit) {
        if (a == null) return fail(amount, null, "That account doesn't exist.");
        long units;
        try { units = ops.currency.fromDouble(amount); } catch (IllegalArgumentException e) { return fail(amount, a, "Invalid amount."); }
        if (amount < 0) return fail(amount, a, "Cannot " + (deposit ? "deposit" : "withdraw") + " negative funds.");
        if (units == 0) return new EconomyResponse(0, balance(a), ResponseType.SUCCESS, null);
        String plugin = attribution.caller();
        TxResult r = deposit ? ops.deposit(plugin, a, units) : ops.withdraw(plugin, a, units);
        return r.success() ? new EconomyResponse(ops.currency.toDouble(units), balance(a), ResponseType.SUCCESS, null) : fail(amount, a, r.message());
    }

    private EconomyResponse fail(double amount, Acct a, String message) { return new EconomyResponse(0, balance(a), ResponseType.FAILURE, message); }

    private Acct writable(OfflinePlayer p) { return ops.playerAccount(p.getUniqueId()); }

    @Override public EconomyResponse withdrawPlayer(String name, double amount) { return move(ops.byName(name), amount, false); }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer p, double amount) { return move(writable(p), amount, false); }
    @Override public EconomyResponse withdrawPlayer(String name, String world, double amount) { return withdrawPlayer(name, amount); }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer p, String world, double amount) { return withdrawPlayer(p, amount); }
    @Override public EconomyResponse depositPlayer(String name, double amount) { return move(ops.byName(name), amount, true); }
    @Override public EconomyResponse depositPlayer(OfflinePlayer p, double amount) { return move(writable(p), amount, true); }
    @Override public EconomyResponse depositPlayer(String name, String world, double amount) { return depositPlayer(name, amount); }
    @Override public EconomyResponse depositPlayer(OfflinePlayer p, String world, double amount) { return depositPlayer(p, amount); }

    // ---- banks (not implemented) ------------------------------------------------------------------------------

    @Override public EconomyResponse createBank(String name, String player) { return NO_BANKS; }
    @Override public EconomyResponse createBank(String name, OfflinePlayer player) { return NO_BANKS; }
    @Override public EconomyResponse deleteBank(String name) { return NO_BANKS; }
    @Override public EconomyResponse bankBalance(String name) { return NO_BANKS; }
    @Override public EconomyResponse bankHas(String name, double amount) { return NO_BANKS; }
    @Override public EconomyResponse bankWithdraw(String name, double amount) { return NO_BANKS; }
    @Override public EconomyResponse bankDeposit(String name, double amount) { return NO_BANKS; }
    @Override public EconomyResponse isBankOwner(String name, String player) { return NO_BANKS; }
    @Override public EconomyResponse isBankOwner(String name, OfflinePlayer player) { return NO_BANKS; }
    @Override public EconomyResponse isBankMember(String name, String player) { return NO_BANKS; }
    @Override public EconomyResponse isBankMember(String name, OfflinePlayer player) { return NO_BANKS; }
    @Override public List<String> getBanks() { return List.of(); }
}
