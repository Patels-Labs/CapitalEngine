package com.patelheet.capitalengine.trade;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

/** {@code %capitaltrade_price_<item>%}, {@code %capitaltrade_quota_<item>%} (ships take N more today), {@code %capitaltrade_boom%}. Reads caches only. */
final class TradeExpansion extends PlaceholderExpansion {
    private final TradeService svc;

    TradeExpansion(TradeService svc) { this.svc = svc; }

    @Override public String getIdentifier() { return "capitaltrade"; }
    @Override public String getAuthor() { return "Heet Patel"; }
    @Override public String getVersion() { return svc.plugin.getPluginMeta().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override public String onRequest(OfflinePlayer player, String params) {
        if (params.equals("boom")) { String b = svc.boomItem; return b == null ? "" : Items.pretty(b); }
        int i = params.indexOf('_');
        if (i < 0) return null;
        String item = Items.parse(params.substring(i + 1));
        if (item == null) return "";
        return switch (params.substring(0, i)) {
            case "price" -> { boolean ex = svc.exports.containsKey(item); long p = svc.priceNow(item, ex); yield p == 0 ? "" : svc.api.currency().format(p); }
            case "quota" -> { Long q = svc.quotaLeftCache.get(item); yield q == null ? "" : String.valueOf(q); }
            default -> null;
        };
    }
}
