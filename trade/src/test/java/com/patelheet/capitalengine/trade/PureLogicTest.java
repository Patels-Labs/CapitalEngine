package com.patelheet.capitalengine.trade;

import static org.junit.jupiter.api.Assertions.*;

import com.patelheet.capitalengine.trade.port.Slide;
import com.patelheet.capitalengine.trade.world.Booms;
import com.patelheet.capitalengine.trade.world.WorldPrices;
import java.util.List;
import org.junit.jupiter.api.Test;

/** World prices, booms and the quota slide: pure Java, no server. */
class PureLogicTest {
    static final WorldPrices.Cfg CFG = new WorldPrices.Cfg(0.10, 0.05, 0.5, 2.0, 12345);

    @Test void worldPricesAreReproducibleFromTheSeed() {
        assertEquals(WorldPrices.step(CFG, "wheat", 7, 80, 80), WorldPrices.step(CFG, "wheat", 7, 80, 80));
        long a = 80, b = 80;
        for (int d = 0; d < 50; d++) { a = WorldPrices.step(CFG, "wheat", d, 80, a); b = WorldPrices.step(CFG, "wheat", d, 80, b); }
        assertEquals(a, b);
        var other = new WorldPrices.Cfg(0.10, 0.05, 0.5, 2.0, 999);
        boolean differs = false;
        for (int d = 0; d < 20; d++) differs |= WorldPrices.step(CFG, "wheat", d, 10_000, 10_000) != WorldPrices.step(other, "wheat", d, 10_000, 10_000);
        assertTrue(differs, "a different seed gives a different walk");
    }

    @Test void worldPricesRevertToTheBaseAndStayInsideTheClamps() {
        var calm = new WorldPrices.Cfg(0.10, 0, 0.5, 2.0, 1);
        long p = WorldPrices.step(calm, "iron_ingot", 1, 1000, 2000);
        assertTrue(p < 2000 && p > 1000, "pulled back toward the base: " + p);
        assertEquals(2000, WorldPrices.step(calm, "iron_ingot", 1, 1000, 50_000), "never above twice the base");
        assertEquals(500, WorldPrices.step(calm, "iron_ingot", 1, 1000, 1), "never below half the base");
        long x = 1000;
        double logSum = 0;
        for (int d = 0; d < 5000; d++) {
            x = WorldPrices.step(CFG, "iron_ingot", d, 1000, x);
            assertTrue(x >= 500 && x <= 2000);
            logSum += Math.log(x / 1000.0);
        }
        assertEquals(0, logSum / 5000, 0.05, "the long-run average sits at the base");
    }

    @Test void exportsPaidInStacksAddUpToTheClosedFormAndStopAtTheQuota() {
        long p = 80, quota = 1000;
        long stacks = 0;
        for (long u = 0; u < quota; u += 64) stacks += Slide.exportTotal(p, u, Math.min(64, quota - u), quota, 0.5);
        assertEquals(Slide.exportTotal(p, 0, quota, quota, 0.5), stacks, "selling in stacks pays exactly the same as selling at once");
        assertEquals((long) Math.floor(80 * (1000 - 0.5 * 1000 * 1000 / 2000.0)), stacks, "closed form: p·Q·(1 − k/2)");
        assertEquals(40, Slide.exportUnit(p, quota, quota, 0.5), "at a full quota the price is half");
        assertEquals(80, Slide.exportUnit(p, 0, quota, 0.5));
        assertThrows(IllegalArgumentException.class, () -> Slide.exportTotal(p, 990, 20, quota, 0.5), "nothing is paid beyond the quota");
        assertEquals(10, Slide.maxExport(p, 990, 500, quota, 0.5, Long.MAX_VALUE));
        assertEquals(0, Slide.maxExport(p, quota, 1, quota, 0.5, Long.MAX_VALUE));
        long cap = Slide.maxExport(p, 0, 500, quota, 0.5, 1000);
        assertTrue(Slide.exportTotal(p, 0, cap, quota, 0.5) <= 1000 && Slide.exportTotal(p, 0, cap + 1, quota, 0.5) > 1000, "the budget caps the amount exactly");
        assertEquals(128, Slide.quota(128, 0), "a quiet server still counts as one player");
        assertEquals(384, Slide.quota(128, 3));
    }

    @Test void importsRiseWithTheQuotaAndSplitTheTariffExactly() {
        long p = 4000, quota = 10;
        long one = Slide.importTotal(p, 0.25, 0, 1, quota, 0.5);
        assertTrue(one >= 5000, "world price plus 25% tariff: " + one);
        long all = Slide.importTotal(p, 0.25, 0, quota, quota, 0.5), parts = 0;
        for (int u = 0; u < quota; u++) parts += Slide.importTotal(p, 0.25, u, 1, quota, 0.5);
        assertEquals(all, parts);
        assertEquals((long) Math.ceil(4000 * 1.25 * (10 + 0.5 * 100 / 20.0)), all, "closed form: p·(1+t)·Q·(1 + k/2)");
        assertTrue(Slide.importTotal(p, 0.25, 9, 1, quota, 0.5) > one, "the price rises as the quota fills");
        long tariff = Slide.tariffPart(all, 0.25);
        assertEquals(all / 5, tariff, "a 25% tariff is a fifth of the total");
        assertEquals(all, tariff + (all - tariff));
        assertEquals(0, Slide.tariffPart(all, 0));
        for (long t = 1; t < 2000; t += 7) { long f = Slide.tariffPart(t, 0.3); assertTrue(f >= 0 && f <= t); }
    }

    @Test void boomsHappenAboutAsOftenAsConfigured() {
        List<String> items = List.of("pumpkin", "wheat", "iron_ingot");
        int booms = 0;
        for (int w = 0; w < 10_000; w++) if (Booms.roll(12345, w, 0.5, items) != null) booms++;
        assertEquals(5000, booms, 200);
        assertEquals(Booms.roll(12345, 42, 0.5, items), Booms.roll(12345, 42, 0.5, items));
        for (int w = 0; w < 100; w++) assertNull(Booms.roll(12345, w, 0, items));
        assertNull(Booms.roll(12345, 1, 1, List.of()));
    }
}
