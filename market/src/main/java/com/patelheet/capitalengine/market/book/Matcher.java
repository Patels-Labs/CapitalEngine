package com.patelheet.capitalengine.market.book;

import com.patelheet.capitalengine.market.maker.ReferencePrice;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds a fill plan from one item's resting orders plus the server's virtual quote (market/Plan.md §5.3). Pure Java.
 * Price-time priority is the caller's sort order; players win ties against the server; trades happen at the resting price.
 */
public final class Matcher {
    private Matcher() {}

    /** A resting player order with its remaining quantity. */
    public record Order(long id, String owner, long price, int qty) {}

    /** One slice of a plan. {@code orderId == 0} means the server. */
    public record Fill(long orderId, String owner, long price, int qty) {
        public boolean server() { return orderId == 0; }
    }

    /**
     * The server's side of the book for one item.
     *
     * @param budgetLeft  coins the server may still spend buying today
     * @param dayCapLeft  units it may still buy of this item today
     */
    public record Maker(long base, long stock, long target, double k, double spread, long budgetLeft, int dayCapLeft) {}

    /**
     * @param buying whether the taker buys (walks the asks) or sells (walks the bids)
     * @param book   resting orders on the other side, best first, without the taker's own
     * @param limit  worst acceptable unit price ({@link Long#MAX_VALUE} to buy / 0 to sell at any price)
     * @param m      the server's quote, or null if the server doesn't deal in this item
     */
    public static List<Fill> plan(boolean buying, List<Order> book, int qty, long limit, Maker m) {
        List<Fill> out = new ArrayList<>();
        int rem = qty, i = 0;
        int headLeft = book.isEmpty() ? 0 : book.get(0).qty();
        long stock = m == null ? 0 : m.stock(), budget = m == null ? 0 : m.budgetLeft();
        int cap = m == null ? 0 : m.dayCapLeft();
        while (rem > 0) {
            Order o = i < book.size() ? book.get(i) : null;
            long sp = 0;
            int sn = 0;
            if (m != null) {   // the server prices each stack of 64 on its own, so big dumps walk the price
                long ref = ReferencePrice.ref(m.base(), stock, m.target(), m.k());
                if (buying) { sp = ReferencePrice.ask(ref, m.spread()); sn = (int) Math.min(Math.min(rem, 64), stock); }
                else { sp = ReferencePrice.bid(ref, m.spread()); sn = (int) Math.min(Math.min(rem, 64), Math.min(cap, budget / sp)); }
            }
            boolean playerOk = o != null && (buying ? o.price() <= limit : o.price() >= limit);
            boolean serverOk = sn > 0 && (buying ? sp <= limit : sp >= limit);
            boolean usePlayer = playerOk && (!serverOk || (buying ? o.price() <= sp : o.price() >= sp));
            if (usePlayer) {
                int n = Math.min(rem, headLeft);
                out.add(new Fill(o.id(), o.owner(), o.price(), n));
                rem -= n; headLeft -= n;
                if (headLeft == 0) { i++; headLeft = i < book.size() ? book.get(i).qty() : 0; }
            } else if (serverOk) {
                out.add(new Fill(0, null, sp, sn));
                rem -= sn;
                if (buying) stock -= sn; else { stock += sn; budget -= sp * sn; cap -= sn; }
            } else break;
        }
        return out;
    }

    public static int qty(List<Fill> plan) { return plan.stream().mapToInt(Fill::qty).sum(); }

    public static long total(List<Fill> plan) { return plan.stream().mapToLong(f -> f.price() * f.qty()).sum(); }
}
