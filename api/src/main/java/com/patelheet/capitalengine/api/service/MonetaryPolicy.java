package com.patelheet.capitalengine.api.service;

import java.util.OptionalLong;

/**
 * Shared economic settings, implemented by an add-on (for example CapitalMarket) and registered with Bukkit's
 * ServicesManager. Core never implements it. Only the registration with the highest priority is used, and
 * consumers must keep working without one.
 */
public interface MonetaryPolicy {
    /** Savings rate per day, for example 0.0028 for about 2% a week. */
    double savingsRatePerDay();

    /** Loan rate per day. */
    double loanRatePerDay();

    /**
     * Today's maximum {@code serverPay} spend for a channel. By convention the channels are {@code "savings"},
     * {@code "jobs"} and {@code "buyer"}.
     *
     * @return the budget, or empty for "no opinion, use your own config"
     */
    OptionalLong budget(String channel);
}
