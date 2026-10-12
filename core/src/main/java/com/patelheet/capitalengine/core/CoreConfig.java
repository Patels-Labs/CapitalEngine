package com.patelheet.capitalengine.core;

import com.patelheet.capitalengine.core.api.CurrencyImpl;
import com.patelheet.capitalengine.core.policy.PolicyManager.Limit;
import com.patelheet.capitalengine.core.policy.PolicyManager.VaultMode;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;

/** Parsed {@code config.yml}. Amounts in the file are whole coins (or "1.5k" style) and are converted to the smallest unit here. */
public record CoreConfig(CurrencyImpl currency, long starterCoins, long payMin, long payConfirmAbove,
                         LocalTime dayStart, ZoneId zone, DayOfWeek weekStart, int maxCatchUpDays,
                         int afkAfterMinutes, int dormantAfterDays, Limit defaults, Map<String, Limit> plugins) {

    public static CoreConfig parse(ConfigurationSection c, CurrencyImpl currency) {
        ConfigurationSection clock = c.getConfigurationSection("clock");
        String tz = clock == null ? "server" : clock.getString("timezone", "server");
        Limit defaults = limit(c.getConfigurationSection("defaults"), Limit.UNLIMITED, currency);
        Map<String, Limit> plugins = new LinkedHashMap<>();
        ConfigurationSection ps = c.getConfigurationSection("plugins");
        if (ps != null) for (String name : ps.getKeys(false)) plugins.put(name, limit(ps.getConfigurationSection(name), defaults, currency));
        return new CoreConfig(
                currency,
                amount(c.get("starter-coins", 250), currency),
                amount(c.get("pay.min", 1), currency),
                amount(c.get("pay.confirm-above", 1000), currency),
                LocalTime.parse(clock == null ? "04:00" : clock.getString("day-starts-at", "04:00")),
                tz.equalsIgnoreCase("server") ? ZoneId.systemDefault() : ZoneId.of(tz),
                DayOfWeek.valueOf((clock == null ? "MONDAY" : clock.getString("week-starts-on", "MONDAY")).toUpperCase(Locale.ROOT)),
                clock == null ? 7 : clock.getInt("max-catch-up-days", 7),
                c.getInt("activity.afk-after-minutes", 5),
                c.getInt("activity.dormant-after-days", 60),
                defaults, plugins);
    }

    public static CurrencyImpl currency(ConfigurationSection c) {
        ConfigurationSection s = c.getConfigurationSection("currency");
        if (s == null) return new CurrencyImpl("coin", "coins", "⛁", 2, "<amount> <name>");
        return new CurrencyImpl(s.getString("singular", "coin"), s.getString("plural", "coins"), s.getString("symbol", "⛁"),
                s.getInt("decimals", 2), s.getString("format", "<amount> <name>"));
    }

    /** A plain number, "1.5k" style text, or "unlimited" (→ {@link Long#MAX_VALUE}). */
    static long amount(Object v, CurrencyImpl currency) {
        if (v == null || String.valueOf(v).equalsIgnoreCase("unlimited")) return Long.MAX_VALUE;
        return currency.parse(String.valueOf(v));
    }

    private static Limit limit(ConfigurationSection s, Limit base, CurrencyImpl currency) {
        if (s == null) return base;
        return new Limit(
                s.contains("vault-mode") ? VaultMode.valueOf(s.getString("vault-mode", "issue").toUpperCase(Locale.ROOT)) : base.mode(),
                s.contains("issue-per-day") ? amount(s.get("issue-per-day"), currency) : base.issuePerDay(),
                s.contains("treasury-per-day") ? amount(s.get("treasury-per-day"), currency) : base.treasuryPerDay());
    }
}
