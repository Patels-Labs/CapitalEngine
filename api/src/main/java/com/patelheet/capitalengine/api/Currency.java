package com.patelheet.capitalengine.api;

/** The server's single currency. Amounts are {@code long} counts of the smallest unit. */
public interface Currency {
    /** Singular name, for example "coin". */
    String singular();

    /** Plural name, for example "coins". */
    String plural();

    /** Symbol, for example "⛁". */
    String symbol();

    /** Decimal places (2 means 12345 is 123.45). */
    int decimals();

    /** Formats using the owner's configured format, for example {@code "123.45 coins"}. */
    String format(long amount);

    /**
     * Parses player input such as {@code "12"}, {@code "1.5"}, {@code "1.5k"} or {@code "2m"}.
     *
     * @return the amount in the smallest unit
     * @throws IllegalArgumentException if the text isn't a valid amount for this currency
     */
    long parse(String text);
}
