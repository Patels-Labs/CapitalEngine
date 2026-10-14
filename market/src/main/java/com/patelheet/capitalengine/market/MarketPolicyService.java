package com.patelheet.capitalengine.market;

import com.patelheet.capitalengine.api.service.Indicator;
import com.patelheet.capitalengine.api.service.IndicatorProvider;
import com.patelheet.capitalengine.api.service.MonetaryPolicy;
import com.patelheet.capitalengine.api.service.Unit;
import com.patelheet.capitalengine.market.autopilot.Autopilot;
import java.util.List;
import java.util.OptionalLong;

/** Publishes the autopilot's numbers to other plugins (Bank, Dashboard) through Bukkit's ServicesManager. */
public final class MarketPolicyService implements MonetaryPolicy, IndicatorProvider {
    private final MarketService svc;

    MarketPolicyService(MarketService svc) { this.svc = svc; }

    @Override public double savingsRatePerDay() { var s = svc.policy; return s == null ? 0 : s.savingsPerDay(); }

    @Override public double loanRatePerDay() { var s = svc.policy; return s == null ? 0 : s.loanPerDay(); }

    @Override public OptionalLong budget(String channel) {
        Autopilot.State s = svc.policy;
        if (s == null) return OptionalLong.empty();
        return switch (channel) {
            case "savings" -> OptionalLong.of(s.savings());
            case "jobs" -> OptionalLong.of(s.jobs());
            case "buyer" -> OptionalLong.of(s.buyer());
            default -> OptionalLong.empty();
        };
    }

    @Override public String id() { return "capitalmarket"; }

    @Override public String displayName() { return "Market"; }

    @Override public List<Indicator> indicators() {
        Autopilot.State s = svc.policy;
        if (s == null) return List.of();
        return List.of(
                new Indicator("price_index", "Price index", s.index(), Unit.INDEX),
                new Indicator("weekly_inflation", "Prices this week", s.inflation() * 100, Unit.PERCENT),
                new Indicator("new_money_per_day", "New money per day", s.newMoney(), Unit.COINS),
                new Indicator("savings_rate", "Savings rate per day", s.savingsPerDay(), Unit.RATE_PER_DAY),
                new Indicator("loan_rate", "Loan rate per day", s.loanPerDay(), Unit.RATE_PER_DAY),
                new Indicator("mm_spread", "Server price gap", s.spread() * 100, Unit.PERCENT),
                new Indicator("player_trade_share", "Player trade share", svc.playerShare * 100, Unit.PERCENT),
                new Indicator("server_stock_value", "Server stock value", svc.stockValue, Unit.COINS));
    }
}
