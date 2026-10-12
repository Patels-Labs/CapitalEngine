package com.patelheet.capitalengine.core.papi;

import com.patelheet.capitalengine.core.api.CurrencyImpl;
import com.patelheet.capitalengine.core.ledger.Acct;
import com.patelheet.capitalengine.core.ledger.Ledger;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** {@code %capital_balance%}, {@code _formatted}, {@code _short}, {@code %capital_currency%}. Only loaded when PlaceholderAPI is present. */
public final class CapitalExpansion extends PlaceholderExpansion {
    private final Plugin plugin;
    private final Ledger ledger;
    private final CurrencyImpl currency;

    public CapitalExpansion(Plugin plugin, Ledger ledger, CurrencyImpl currency) { this.plugin = plugin; this.ledger = ledger; this.currency = currency; }

    @Override public @NotNull String getIdentifier() { return "capital"; }
    @Override public @NotNull String getAuthor() { return "Heet Patel"; }
    @Override public @NotNull String getVersion() { return plugin.getPluginMeta().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        if (params.equals("currency")) return currency.plural();
        if (player == null) return "";
        Acct a = ledger.byId(player.getUniqueId());
        long balance = a == null ? 0 : a.balance();
        return switch (params) {
            case "balance" -> currency.toDecimal(balance).toPlainString();
            case "balance_formatted" -> currency.format(balance);
            case "balance_short" -> currency.shortNumber(balance);
            default -> null;
        };
    }
}
