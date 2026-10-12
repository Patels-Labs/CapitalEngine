package com.patelheet.capitalengine.core.api;

import com.patelheet.capitalengine.api.Currency;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** The server's one currency, from {@code config.yml}. */
public final class CurrencyImpl implements Currency {
    private final String singular, plural, symbol, template;
    private final int decimals;
    private final long scale;

    public CurrencyImpl(String singular, String plural, String symbol, int decimals, String template) {
        if (decimals < 0 || decimals > 6) throw new IllegalArgumentException("currency.decimals must be 0-6");
        this.singular = singular; this.plural = plural; this.symbol = symbol; this.decimals = decimals; this.template = template;
        this.scale = (long) Math.pow(10, decimals);
    }

    @Override public String singular() { return singular; }
    @Override public String plural() { return plural; }
    @Override public String symbol() { return symbol; }
    @Override public int decimals() { return decimals; }

    @Override public String format(long amount) {
        return template.replace("<amount>", number(amount)).replace("<name>", amount == scale ? singular : plural).replace("<symbol>", symbol);
    }

    /** Just the number with thousands separators; decimals only when the amount isn't whole. */
    public String number(long amount) {
        boolean whole = amount % scale == 0;
        DecimalFormat f = new DecimalFormat(whole || decimals == 0 ? "#,##0" : "#,##0." + "0".repeat(decimals), DecimalFormatSymbols.getInstance(Locale.ROOT));
        return f.format(BigDecimal.valueOf(amount, decimals));
    }

    /** 1.2k / 3.4m / 1.1b style, in whole coins. */
    public String shortNumber(long amount) {
        double coins = amount / (double) scale;
        String[] suffix = {"", "k", "m", "b", "t"};
        int i = 0;
        while (Math.abs(coins) >= 1000 && i < suffix.length - 1) { coins /= 1000; i++; }
        return i == 0 ? number(amount) : String.format(Locale.ROOT, "%.1f%s", coins, suffix[i]).replace(".0" + suffix[i], suffix[i]);
    }

    @Override public long parse(String text) {
        String t = text.trim().toLowerCase(Locale.ROOT).replace(",", "").replace("_", "");
        if (t.isEmpty()) throw new IllegalArgumentException("empty amount");
        BigDecimal mult = BigDecimal.ONE;
        char last = t.charAt(t.length() - 1);
        int power = switch (last) { case 'k' -> 3; case 'm' -> 6; case 'b' -> 9; default -> 0; };
        if (power > 0) { mult = BigDecimal.TEN.pow(power); t = t.substring(0, t.length() - 1); }
        try {
            BigDecimal v = new BigDecimal(t).multiply(mult).movePointRight(decimals);
            if (v.signum() < 0) throw new IllegalArgumentException("negative amount");
            if (v.stripTrailingZeros().scale() > 0) throw new IllegalArgumentException("too many decimals");
            return v.longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException("not a valid amount: " + text, e);
        }
    }

    /** Vault's double → smallest unit, rounding half-even. Throws if NaN, infinite or too big. */
    public long fromDouble(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) throw new IllegalArgumentException("not a number");
        try { return BigDecimal.valueOf(d).setScale(decimals, RoundingMode.HALF_EVEN).movePointRight(decimals).longValueExact(); }
        catch (ArithmeticException e) { throw new IllegalArgumentException("too big", e); }
    }

    /** Smallest unit → double, exact for any realistic balance. */
    public double toDouble(long amount) { return BigDecimal.valueOf(amount, decimals).doubleValue(); }

    /** Vault2 BigDecimal → smallest unit; rejects more precision than the configured decimals. */
    public long fromDecimal(BigDecimal d) {
        try {
            BigDecimal v = d.movePointRight(decimals);
            if (v.stripTrailingZeros().scale() > 0) throw new IllegalArgumentException("Only " + decimals + " decimal places are allowed.");
            return v.longValueExact();
        } catch (ArithmeticException e) { throw new IllegalArgumentException("too big", e); }
    }

    public BigDecimal toDecimal(long amount) { return BigDecimal.valueOf(amount, decimals); }
}
