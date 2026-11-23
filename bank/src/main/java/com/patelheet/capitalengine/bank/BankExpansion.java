package com.patelheet.capitalengine.bank;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

/** {@code %capitalbank_savings%}, {@code %capitalbank_rate%} ("2% a week"), {@code %capitalbank_loan_left%}, {@code %capitalbank_upkeep_due%}. Reads caches only. */
final class BankExpansion extends PlaceholderExpansion {
    private final BankService svc;

    BankExpansion(BankService svc) { this.svc = svc; }

    @Override public String getIdentifier() { return "capitalbank"; }
    @Override public String getAuthor() { return "Heet Patel"; }
    @Override public String getVersion() { return svc.plugin.getPluginMeta().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override public String onRequest(OfflinePlayer player, String params) {
        if (params.equals("rate")) return svc.msg.plain("rate-text", "rate", Actions.pct(svc.savingsPerDay() * 7));
        if (player == null) return "";
        return switch (params) {
            case "savings" -> svc.coins(svc.savings(player.getUniqueId()));
            case "loan_left", "loan" -> svc.coins(svc.loanLeft.getOrDefault(player.getUniqueId(), 0L));
            case "upkeep_due" -> svc.coins(svc.upkeepDue.getOrDefault(player.getUniqueId(), 0L));
            default -> null;
        };
    }
}
