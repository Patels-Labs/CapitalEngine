package com.patelheet.capitalengine.core.ledger;

import com.patelheet.capitalengine.api.AccountKind;
import java.util.Map;

/** The rules from api §5.3/§5.4 as pure functions. */
public final class LedgerRules {
    private LedgerRules() {}

    /** Who may take money out of {@code from}: players/external by anyone, plugin accounts by their owner only, never the Treasury or Mint directly. */
    public static boolean canSource(Acct from, String callerKey) {
        return switch (from.kind()) {
            case PLAYER, EXTERNAL -> true;
            case PLUGIN -> from.plugin().equals(callerKey);
            case TREASURY, MINT -> false;
        };
    }

    /** serverPay step 1: how much comes from the Treasury. */
    public static long fromTreasury(long amount, long treasuryBalance, long treasuryRemaining) {
        return Math.max(0, Math.min(amount, Math.min(treasuryBalance, treasuryRemaining)));
    }

    /** Coins moved between ordinary accounts: positive postings that weren't newly created or paid by the Treasury. */
    public static long transferred(Map<Acct, Long> net, long issued, long treasuryOut) {
        long positive = 0;
        for (var e : net.entrySet()) {
            AccountKind k = e.getKey().kind();
            if (k != AccountKind.MINT && k != AccountKind.TREASURY && e.getValue() > 0) positive += e.getValue();
        }
        return Math.max(0, positive - issued - treasuryOut);
    }
}
