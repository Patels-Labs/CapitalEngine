package com.patelheet.capitalengine.market;

import static org.junit.jupiter.api.Assertions.*;

import com.patelheet.capitalengine.market.autopilot.Autopilot;
import com.patelheet.capitalengine.market.autopilot.PriceIndex;
import com.patelheet.capitalengine.market.book.Matcher;
import com.patelheet.capitalengine.market.book.Matcher.Maker;
import com.patelheet.capitalengine.market.book.Matcher.Order;
import com.patelheet.capitalengine.market.maker.ReferencePrice;
import com.patelheet.capitalengine.market.alerts.FloodDetector;
import com.patelheet.capitalengine.market.maker.RecipeValuer;
import com.patelheet.capitalengine.market.maker.RecipeValuer.Recipe;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PureLogicTest {
    // base 100, stock = target so ref = 100, spread 30% -> server bids 70 and asks 130
    private static Maker server(long stock, long budget) { return new Maker(100, stock, 640, 0.5, 0.30, budget, 1280); }

    @Test void referencePriceFallsAsStockGrowsAndIsClamped() {
        assertEquals(100, ReferencePrice.ref(100, 640, 640, 0.5));
        assertTrue(ReferencePrice.ref(100, 2560, 640, 0.5) < ReferencePrice.ref(100, 640, 640, 0.5));
        assertEquals(25, ReferencePrice.ref(100, 1_000_000, 640, 0.5));
        assertEquals(400, ReferencePrice.ref(100, 0, 640, 0.5));
    }

    @Test void playersBeatTheServerAtTheSamePriceAndOldestFirst() {
        var bids = List.of(new Order(1, "a", 70, 10), new Order(2, "b", 70, 10), new Order(3, "c", 60, 10));
        var plan = Matcher.plan(false, bids, 25, 0, server(640, 1_000_000));
        assertEquals(1, plan.get(0).orderId());      // same price as the server's 70: player first
        assertEquals(2, plan.get(1).orderId());
        assertTrue(plan.get(2).server());            // server's 70 beats c's 60
        assertEquals(25, Matcher.qty(plan));
    }

    @Test void dumpingStockWalksTheServerPriceDown() {
        var plan = Matcher.plan(false, List.of(), 640, 0, server(640, 1_000_000));
        assertEquals(10, plan.size());
        assertTrue(plan.get(9).price() < plan.get(0).price());
    }

    @Test void serverBidHidesWhenBudgetIsGone() {
        assertTrue(Matcher.plan(false, List.of(), 10, 0, server(640, 0)).isEmpty());
        assertEquals(5, Matcher.qty(Matcher.plan(false, List.of(), 10, 0, server(640, 350))));   // 350 / 70 = 5 units
    }

    @Test void serverOnlySellsWhatItHasAndLimitsApply() {
        assertEquals(30, Matcher.qty(Matcher.plan(true, List.of(), 100, Long.MAX_VALUE, server(30, 0))));
        var asks = List.of(new Order(1, "a", 90, 5));
        var plan = Matcher.plan(true, asks, 10, 100, server(640, 0));   // server asks 130 > limit 100
        assertEquals(5, Matcher.qty(plan));
        assertEquals(90, plan.get(0).price());
    }

    @Test void autopilotRaisesRatesWhenPricesRiseAndShiftsBudgets() {
        var c = new Autopilot.Cfg(0.005, 0.001, 15_000, 0.02, 0, 0.08, 0.6, 2);
        var in = new Autopilot.In(0.05, 1_000_000, 4, 0, 0.5, true, 5);
        var s = Autopilot.step(new Autopilot.State(1, 0.02, 0, 0, 0, 0.3, 100, 0, 0), in, c, 2);
        assertTrue(s.rate() > 0.02 && s.rate() <= 0.0225, "rate moves at most 0.25pp");
        assertEquals(0.5, (s.spread() - 0.3) / 0.4, 1e-9);
        assertTrue(s.jobs() > s.buyer());
        var warm = Autopilot.step(new Autopilot.State(1, 0.02, 0, 0, 0, 0.3, 100, 0, 0), new Autopilot.In(0.5, 1_000_000, 4, 0, 0, false, 0), c, 2);
        assertEquals(0.02, warm.rate(), 0, "warm-up keeps the start rate");
        assertEquals(0, warm.savings(), "no bank means no savings budget");
    }

    @Test void priceIndexDropsOutliersAndUsesRefWhenNobodyTraded() {
        var pts = Map.of("iron", List.of(new PriceIndex.Pt(2, 100, 10), new PriceIndex.Pt(2, 110, 10), new PriceIndex.Pt(2, 100, 10),
                new PriceIndex.Pt(2, 9000, 10), new PriceIndex.Pt(1, 100, 10)));
        var r = PriceIndex.compute(2, Map.of("iron", 5000L, "wheat", 5000L), pts, Map.of("iron", 100L, "wheat", 50L), Map.of("iron", 100L, "wheat", 50L));
        assertEquals(1.0333, r.change().get("iron"), 0.001);   // 9000 ignored: (100+110+100)/3 vs 100
        assertEquals(1.0, r.change().get("wheat"), 0);
    }

    @Test void recipesPriceCraftedItemsFromTheCheapestPathAndSurviveCycles() {
        var raw = Map.of("log", 150L, "ingot", 500L, "raw_iron", 400L);
        var recipes = List.of(
                new Recipe("planks", 4, List.of(List.of("log")), false),
                new Recipe("stick", 4, List.of(List.of("planks"), List.of("planks")), false),
                new Recipe("block", 1, java.util.Collections.nCopies(9, List.of("ingot")), false),
                new Recipe("ingot", 9, List.of(List.of("block")), false),              // the reverse recipe: a cycle
                new Recipe("smelted", 1, List.of(List.of("raw_iron")), true),
                new Recipe("fancy", 1, List.of(List.of("block", "planks")), false));   // either ingredient fits: the cheaper wins
        var v = RecipeValuer.value(raw, recipes);
        assertEquals(40, v.get("planks"));                // 150 * 1.05 / 4, rounded up
        assertEquals(500, v.get("ingot"), "a price from values.yml is never replaced");
        assertEquals(4725, v.get("block"));               // 9 * 500 * 1.05
        assertEquals(42, v.get("fancy"));                 // via planks: 40 * 1.05
        assertEquals(433, v.get("smelted"));              // (400 + 3% fuel) * 1.05
        assertTrue(v.get("stick") < v.get("planks"));
    }

    @Test void floodDetectorFlagsBigSalesAgainstTheUsualAndOnlyOncePerDay() {
        var f = new FloodDetector(10, 256);
        assertTrue(f.sold("sam", "diamond", 200).isEmpty(), "under the minimum");
        for (int day = 0; day < 20; day++) { f.sold("sam", "wheat", 40); f.endDay(); }   // a usual of 40 a day
        assertTrue(f.sold("sam", "wheat", 300).stream().noneMatch(a -> !a.server()), "300 is under 10x of about 30 for one player (the server-wide 5x does fire)");
        var alerts = f.sold("sam", "wheat", 3000);
        assertEquals(1, alerts.stream().filter(a -> !a.server()).count());
        assertTrue(f.sold("sam", "wheat", 3000).isEmpty(), "already reported today");
        f.endDay();
        assertFalse(f.sold("sam", "wheat", 3000).isEmpty(), "a new day can report again");
    }
}
