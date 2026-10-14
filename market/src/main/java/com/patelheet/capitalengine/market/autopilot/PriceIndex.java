package com.patelheet.capitalengine.market.autopilot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Weekly change of a basket of the most-traded items (market/Plan.md §5.7). Pure Java. */
public final class PriceIndex {
    private PriceIndex() {}

    /** One player-to-player trade. */
    public record Pt(long week, long price, int qty) {}

    /**
     * @param change per-item change this week vs last (1.10 = up 10%), for the news
     * @param p      weighted basket change, 0.02 = +2%
     */
    public record Result(double p, Map<String, Double> change) {}

    /**
     * @param value  traded value per item over the last 4 weeks (picks and weights the basket)
     * @param trades player trades per item over the last 4 weeks, for the prices
     * @param refNow the server's reference price now; {@code refLast} a week ago (used when nobody traded)
     */
    public static Result compute(long week, Map<String, Long> value, Map<String, List<Pt>> trades,
                                 Map<String, Long> refNow, Map<String, Long> refLast) {
        List<String> basket = value.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                .limit(25).map(Map.Entry::getKey).toList();
        long sum = basket.stream().mapToLong(value::get).sum();
        Map<String, Double> change = new HashMap<>();
        double p = 0;
        for (String item : basket) {
            double ratio = itemChange(trades.getOrDefault(item, List.of()), week, refNow.getOrDefault(item, 0L), refLast.getOrDefault(item, 0L));
            change.put(item, ratio);
            p += (double) value.get(item) / sum * (ratio - 1);
        }
        return new Result(sum == 0 ? 0 : p, change);
    }

    /** One item's price change this week vs last (1.10 = up 10%): player trades if any, else the server's reference price. */
    public static double itemChange(List<Pt> trades, long week, long refNow, long refLast) {
        List<Pt> pts = trimOutliers(trades);
        double now = vwap(pts, week, refNow), last = vwap(pts, week - 1, refLast);
        return last > 0 && now > 0 ? now / last : 1;
    }

    /** Drops trades more than 3x above or below the median price. */
    public static List<Pt> trimOutliers(List<Pt> pts) {
        if (pts.isEmpty()) return pts;
        long[] prices = pts.stream().mapToLong(Pt::price).sorted().toArray();
        long med = prices[prices.length / 2];
        List<Pt> ok = new ArrayList<>();
        for (Pt t : pts) if (t.price() <= med * 3 && t.price() * 3 >= med) ok.add(t);
        return ok;
    }

    private static double vwap(List<Pt> pts, long week, long fallback) {
        long v = 0, q = 0;
        for (Pt t : pts) if (t.week() == week) { v += t.price() * t.qty(); q += t.qty(); }
        return q == 0 ? fallback : (double) v / q;
    }
}
