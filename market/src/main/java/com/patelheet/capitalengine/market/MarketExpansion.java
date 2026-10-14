package com.patelheet.capitalengine.market;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

/** {@code %capitalmarket_price_<item>%}, {@code %capitalmarket_trend_<item>%} (▲3%), {@code %capitalmarket_savings_rate%}. Reads caches only. */
final class MarketExpansion extends PlaceholderExpansion {
    private final MarketService svc;

    MarketExpansion(MarketService svc) { this.svc = svc; }

    @Override public String getIdentifier() { return "capitalmarket"; }
    @Override public String getAuthor() { return "Heet Patel"; }
    @Override public String getVersion() { return svc.plugin.getPluginMeta().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override public String onRequest(OfflinePlayer player, String params) {
        if (params.equals("savings_rate")) { var s = svc.policy; return s == null ? "" : String.format("%.1f%%", s.rate() * 100); }
        int i = params.indexOf('_');
        if (i < 0) return null;
        String item = Items.parse(params.substring(i + 1));
        if (item == null) return "";
        switch (params.substring(0, i)) {
            case "price": { Long ask = svc.askCache.get(item); return ask == null ? "" : svc.api.currency().format(ask); }
            case "trend": {
                Double t = svc.trend.get(item);
                if (t == null) return "";
                long pct = Math.round((t - 1) * 100);
                return (pct >= 0 ? "▲" : "▼") + Math.abs(pct) + "%";
            }
            default: return null;
        }
    }
}
