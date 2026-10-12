package com.patelheet.capitalengine.core.vault;

import com.patelheet.capitalengine.api.TxResult;
import com.patelheet.capitalengine.core.ledger.Acct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.milkbowl.vault2.economy.AccountPermission;
import net.milkbowl.vault2.economy.Economy;
import net.milkbowl.vault2.economy.EconomyResponse;
import net.milkbowl.vault2.economy.EconomyResponse.ResponseType;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;

/**
 * VaultUnlocked's {@code vault2} economy. The only class that touches vault2 types, so a Vault-only server never loads it.
 * The calling plugin's name is passed in by the caller, so no stack walking is needed. One currency, no shared accounts.
 * ponytail: VaultUnlocked 2.20+ adds account-to-account {@code transfer}; the interface default (withdraw + deposit) is used until the 2.20 API is published.
 */
public final class Vault2Economy implements Economy {
    private final VaultOps ops;

    private Vault2Economy(VaultOps ops) { this.ops = ops; }

    public static void register(Plugin core, VaultOps ops) {
        Bukkit.getServicesManager().register(Economy.class, new Vault2Economy(ops), core, ServicePriority.Highest);
    }

    private String currencyKey() { return ops.currency.plural(); }

    @Override public boolean isEnabled() { return true; }
    @Override public String getName() { return "CapitalEngine"; }
    @Override public boolean hasSharedAccountSupport() { return false; }
    @Override public boolean hasMultiCurrencySupport() { return false; }
    @Override public int fractionalDigits(String pluginName) { return ops.currency.decimals(); }

    private String fmt(BigDecimal a) { return ops.currency.format(a.setScale(ops.currency.decimals(), RoundingMode.HALF_EVEN).movePointRight(ops.currency.decimals()).longValue()); }
    @Override public String format(BigDecimal amount) { return fmt(amount); }
    @Override public String format(String pluginName, BigDecimal amount) { return fmt(amount); }
    @Override public String format(BigDecimal amount, String currency) { return fmt(amount); }
    @Override public String format(String pluginName, BigDecimal amount, String currency) { return fmt(amount); }

    @Override public boolean hasCurrency(String currency) {
        return currency.equalsIgnoreCase(ops.currency.plural()) || currency.equalsIgnoreCase(ops.currency.singular());
    }
    @Override public String getDefaultCurrency(String pluginName) { return currencyKey(); }
    @Override public String defaultCurrencyNamePlural(String pluginName) { return ops.currency.plural(); }
    @Override public String defaultCurrencyNameSingular(String pluginName) { return ops.currency.singular(); }
    @Override public Collection<String> currencies() { return List.of(currencyKey()); }

    // ---- accounts ---------------------------------------------------------------------------------------------

    @Override public boolean createAccount(UUID id, String name) { return createAccount(id, name, ops.isRealPlayer(id)); }
    @Override public boolean createAccount(UUID id, String name, boolean player) {
        if (ops.existing(id) != null) return false;
        if (player) { ops.playerAccount(id); return true; }
        return ops.createExternal("vault", id, id.toString(), name);
    }
    @Override public boolean createAccount(UUID id, String name, String worldName) { return createAccount(id, name); }
    @Override public boolean createAccount(UUID id, String name, String worldName, boolean player) { return createAccount(id, name, player); }

    @Override public Map<UUID, String> getUUIDNameMap() {
        Map<UUID, String> m = new HashMap<>();
        for (Acct a : ops.ledger.accounts()) if (a.ownerPlayer().isPresent()) m.put(a.id(), a.name());
        return m;
    }
    @Override public Optional<String> getAccountName(UUID id) { return Optional.ofNullable(ops.existing(id)).map(Acct::name); }
    @Override public boolean hasAccount(UUID id) { return ops.existing(id) != null; }
    @Override public boolean hasAccount(UUID id, String worldName) { return hasAccount(id); }
    @Override public boolean renameAccount(UUID id, String name) {
        Acct a = ops.existing(id);
        if (a == null) return false;
        a.rename(name);   // ponytail: in memory only; names are re-synced on join
        return true;
    }
    @Override public boolean renameAccount(String plugin, UUID id, String name) { return renameAccount(id, name); }
    @Override public boolean deleteAccount(String plugin, UUID id) { return false; }   // the ledger never deletes
    @Override public boolean accountSupportsCurrency(String plugin, UUID id, String currency) { return hasCurrency(currency); }
    @Override public boolean accountSupportsCurrency(String plugin, UUID id, String currency, String world) { return hasCurrency(currency); }

    // ---- balances ---------------------------------------------------------------------------------------------

    private BigDecimal bal(UUID id) { Acct a = ops.existing(id); return a == null ? BigDecimal.ZERO : ops.currency.toDecimal(a.balance()); }
    @Override public BigDecimal getBalance(String plugin, UUID id) { return bal(id); }
    @Override public BigDecimal getBalance(String plugin, UUID id, String world) { return bal(id); }
    @Override public BigDecimal getBalance(String plugin, UUID id, String world, String currency) { return bal(id); }

    private boolean has(UUID id, BigDecimal amount) {
        Acct a = ops.existing(id);
        try { return a != null && a.balance() >= ops.currency.fromDecimal(amount); } catch (IllegalArgumentException e) { return false; }
    }
    @Override public boolean has(String plugin, UUID id, BigDecimal amount) { return has(id, amount); }
    @Override public boolean has(String plugin, UUID id, String world, BigDecimal amount) { return has(id, amount); }
    @Override public boolean has(String plugin, UUID id, String world, String currency, BigDecimal amount) { return has(id, amount); }

    // ---- money ------------------------------------------------------------------------------------------------

    private EconomyResponse move(String plugin, UUID id, BigDecimal amount, boolean deposit) {
        String who = plugin == null || plugin.isBlank() ? "unknown" : plugin;
        Acct a = ops.existing(id);
        if (a == null && ops.isRealPlayer(id)) a = ops.playerAccount(id);
        if (a == null) return new EconomyResponse(BigDecimal.ZERO, BigDecimal.ZERO, ResponseType.FAILURE, "That account doesn't exist.");
        if (amount.signum() < 0) return new EconomyResponse(BigDecimal.ZERO, bal(id), ResponseType.FAILURE, "Cannot " + (deposit ? "deposit" : "withdraw") + " negative funds.");
        long units;
        try { units = ops.currency.fromDecimal(amount); }
        catch (IllegalArgumentException e) { return new EconomyResponse(BigDecimal.ZERO, bal(id), ResponseType.FAILURE, e.getMessage()); }
        if (units == 0) return new EconomyResponse(BigDecimal.ZERO, bal(id), ResponseType.SUCCESS, "");
        TxResult r = deposit ? ops.deposit(who, a, units) : ops.withdraw(who, a, units);
        return r.success() ? new EconomyResponse(ops.currency.toDecimal(units), bal(id), ResponseType.SUCCESS, "")
                : new EconomyResponse(BigDecimal.ZERO, bal(id), ResponseType.FAILURE, r.message());
    }

    @Override public EconomyResponse withdraw(String plugin, UUID id, BigDecimal amount) { return move(plugin, id, amount, false); }
    @Override public EconomyResponse withdraw(String plugin, UUID id, String world, BigDecimal amount) { return move(plugin, id, amount, false); }
    @Override public EconomyResponse withdraw(String plugin, UUID id, String world, String currency, BigDecimal amount) { return move(plugin, id, amount, false); }
    @Override public EconomyResponse deposit(String plugin, UUID id, BigDecimal amount) { return move(plugin, id, amount, true); }
    @Override public EconomyResponse deposit(String plugin, UUID id, String world, BigDecimal amount) { return move(plugin, id, amount, true); }
    @Override public EconomyResponse deposit(String plugin, UUID id, String world, String currency, BigDecimal amount) { return move(plugin, id, amount, true); }

    // ---- shared accounts (later, for CapitalBusiness) ---------------------------------------------------------

    @Override public boolean createSharedAccount(String plugin, UUID id, String name, UUID owner) { return false; }
    @Override public boolean isAccountOwner(String plugin, UUID id, UUID player) { return false; }
    @Override public boolean setOwner(String plugin, UUID id, UUID player) { return false; }
    @Override public boolean isAccountMember(String plugin, UUID id, UUID player) { return false; }
    @Override public boolean addAccountMember(String plugin, UUID id, UUID player) { return false; }
    @Override public boolean addAccountMember(String plugin, UUID id, UUID player, AccountPermission... permissions) { return false; }
    @Override public boolean removeAccountMember(String plugin, UUID id, UUID player) { return false; }
    @Override public boolean hasAccountPermission(String plugin, UUID id, UUID player, AccountPermission permission) { return false; }
    @Override public boolean updateAccountPermission(String plugin, UUID id, UUID player, AccountPermission permission, boolean value) { return false; }
}
