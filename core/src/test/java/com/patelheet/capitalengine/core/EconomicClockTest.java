package com.patelheet.capitalengine.core;

import static org.junit.jupiter.api.Assertions.*;

import com.patelheet.capitalengine.api.Account;
import com.patelheet.capitalengine.api.Stats;
import com.patelheet.capitalengine.api.event.CapitalAuditEvent;
import com.patelheet.capitalengine.api.event.CapitalDayEvent;
import com.patelheet.capitalengine.api.event.CapitalWeekEvent;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.junit.jupiter.api.Test;

/** The shared clock end to end (core listens first, add-ons see Day/Week events in order), plus the api §5.8 example add-on. */
class EconomicClockTest extends PluginTestBase {
    public static class Seen implements Listener {
        final List<Object> events = new CopyOnWriteArrayList<>();
        @EventHandler public void day(CapitalDayEvent e) { events.add(e); }
        @EventHandler public void week(CapitalWeekEvent e) { events.add(e); }
        @EventHandler public void audit(CapitalAuditEvent e) { events.add(e); }
    }

    @Test void advancingTimeFiresDayAndWeekEventsInOrderAndResetsTodaysCounters() throws Exception {
        System.setProperty("capitalengine.sim", "true");
        server.getScheduler().performTicks(2);   // the clock starts on the first tick
        Seen seen = new Seen();
        server.getPluginManager().registerEvents(seen, other);
        Account sam = api.accounts().player(UUID.randomUUID());
        await(api.issue(other, sam, 1234, "t.x", "m"));
        long day0 = api.stats().dayIndex();
        assertEquals(1234, api.stats().flowsToday().get("Other").issued());

        core.clockControl().advanceDays(7);   // exactly max-catch-up-days, and always crosses one Monday

        List<CapitalDayEvent> days = seen.events.stream().filter(e -> e instanceof CapitalDayEvent).map(e -> (CapitalDayEvent) e).toList();
        assertEquals(7, days.size());
        for (int i = 0; i < 7; i++) assertEquals(day0 + 1 + i, days.get(i).dayIndex(), "once per index, in order");
        assertTrue(days.get(0).catchUp() && days.get(5).catchUp(), "older days found late are catch-up");
        assertFalse(days.get(6).catchUp());
        Stats ended = days.get(0).endedDay();
        assertEquals(1234, ended.flowsToday().get("Other").issued(), "endedDay is captured before counters reset");
        assertTrue(seen.events.stream().anyMatch(e -> e instanceof CapitalWeekEvent), "a Week event fired");
        assertTrue(seen.events.indexOf(days.get(0)) < seen.events.stream().filter(e -> e instanceof CapitalWeekEvent).map(seen.events::indexOf).findFirst().get(),
                "the Week event comes after the Day event that starts it");

        assertEquals(day0 + 7, api.stats().dayIndex());
        assertEquals(1234, api.stats().moneySupply(), "money isn't touched by the clock");
        long end = System.currentTimeMillis() + 3000;
        while (seen.events.stream().noneMatch(e -> e instanceof CapitalAuditEvent) && System.currentTimeMillis() < end) TimeUnit.MILLISECONDS.sleep(20);
        var audit = seen.events.stream().filter(e -> e instanceof CapitalAuditEvent).map(e -> (CapitalAuditEvent) e).findFirst().orElseThrow();
        assertTrue(audit.ok(), "nightly self-check passes: " + audit.problems());
    }

    @Test void clockControlRefusesToWorkOutsideTheSimulator() {
        System.clearProperty("capitalengine.sim");
        assertThrows(IllegalStateException.class, () -> core.clockControl().advanceDays(1));
    }

    /** api/Plan.md §5.8, as written there. */
    public static class DailyReward implements Listener {
        final org.bukkit.plugin.Plugin plugin;
        final com.patelheet.capitalengine.api.CapitalApi api;
        DailyReward(org.bukkit.plugin.Plugin plugin, com.patelheet.capitalengine.api.CapitalApi api) { this.plugin = plugin; this.api = api; }

        @EventHandler
        void onDay(CapitalDayEvent e) {
            if (e.catchUp()) return;
            for (org.bukkit.entity.Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
                api.transaction(plugin, "reward.daily", "Daily login reward")
                   .issue(api.accounts().player(p.getUniqueId()), 10_00)
                   .reference("daily:" + e.dayIndex() + ":" + p.getUniqueId())   // safe to retry
                   .commit()
                   .thenAccept(r -> { if (!r.success()) plugin.getLogger().info("Reward skipped: " + r.failure()); });
            }
        }
    }

    @Test void theExampleAddonFromTheApiPlanRunsAgainstCore() throws Exception {
        System.setProperty("capitalengine.sim", "true");
        core.getConfig().set("starter-coins", 0);
        core.saveConfig();
        core.reloadSettings();
        server.getScheduler().performTicks(2);
        var sam = server.addPlayer("Sam");
        server.getPluginManager().registerEvents(new DailyReward(other, api), other);
        core.clockControl().advanceDays(1);
        await(core.ledger().barrier());
        assertEquals(1000, api.accounts().player(sam.getUniqueId()).balance(), "10.00 coins");
        var again = api.transaction(other, "reward.daily", "Daily login reward").issue(api.accounts().player(sam.getUniqueId()), 1000)
                .reference("daily:" + api.stats().dayIndex() + ":" + sam.getUniqueId()).commit().get(5, TimeUnit.SECONDS);
        assertFalse(again.success(), "retrying the same day's reward is refused");
    }
}
