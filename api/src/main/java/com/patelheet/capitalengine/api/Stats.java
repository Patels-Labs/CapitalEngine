package com.patelheet.capitalengine.api;

import java.util.Map;

/**
 * Instant economy-wide snapshot, read from memory.
 *
 * @param moneySupply         every coin that exists (the Mint's balance, negated)
 * @param activeMoneySupply   {@code moneySupply} minus balances of players inactive for the dormant period
 * @param treasuryBalance     the Treasury's balance
 * @param issuedToday         coins created today
 * @param destroyedToday      coins destroyed today
 * @param treasuryInToday     coins paid into the Treasury today
 * @param treasuryOutToday    coins paid out of the Treasury today
 * @param flowsToday          today's flows by source plugin
 * @param activePlayersToday  players with at least 5 active (non-AFK) minutes today
 * @param activeMinutesToday  total active minutes today
 * @param avgActivePlayers7d  average of {@code activePlayersToday} over the last 7 days
 * @param dayIndex            current economic day
 * @param weekIndex           current economic week
 */
public record Stats(long moneySupply, long activeMoneySupply, long treasuryBalance,
                    long issuedToday, long destroyedToday, long treasuryInToday, long treasuryOutToday,
                    Map<String, PluginFlow> flowsToday, int activePlayersToday, long activeMinutesToday,
                    double avgActivePlayers7d, long dayIndex, long weekIndex) {}
