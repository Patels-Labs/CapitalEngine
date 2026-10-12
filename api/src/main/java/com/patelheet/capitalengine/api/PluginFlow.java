package com.patelheet.capitalengine.api;

/**
 * One plugin's money flows for the current economic day.
 *
 * @param issued      newly created coins
 * @param destroyed   destroyed coins
 * @param treasuryIn  coins paid into the Treasury
 * @param treasuryOut coins paid out of the Treasury
 * @param transferred coins moved between ordinary accounts
 */
public record PluginFlow(long issued, long destroyed, long treasuryIn, long treasuryOut, long transferred) {}
