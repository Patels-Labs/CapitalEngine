package com.patelheet.capitalengine.core.clock;

import static org.junit.jupiter.api.Assertions.*;

import com.patelheet.capitalengine.core.clock.EconomyClock.Boundary;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClockTest {
    static final LocalTime FOUR = LocalTime.of(4, 0);

    @Test void dayStartsAtConfiguredTimeInTheConfiguredZone() {
        ZoneId tokyo = ZoneId.of("Asia/Tokyo");   // UTC+9: 03:59 local is still the previous day, 04:00 is the new one
        long before = EconomyClock.dayIndex(Instant.parse("2026-03-09T18:59:00Z"), tokyo, FOUR);
        long after = EconomyClock.dayIndex(Instant.parse("2026-03-09T19:00:00Z"), tokyo, FOUR);
        assertEquals(LocalDate.of(2026, 3, 9).toEpochDay(), before);
        assertEquals(before + 1, after);
        assertEquals(before, EconomyClock.dayIndex(Instant.parse("2026-03-09T19:00:00Z"), ZoneId.of("UTC"), FOUR), "19:00 UTC is the same day in UTC");
    }

    @Test void dstChangeDaysAreStillOneIndexEach() {
        ZoneId ny = ZoneId.of("America/New_York");   // spring forward 2026-03-08 (23-hour day), fall back 2026-11-01 (25-hour day)
        long mar7 = EconomyClock.dayIndex(Instant.parse("2026-03-07T12:00:00Z"), ny, FOUR);
        assertEquals(mar7 + 1, EconomyClock.dayIndex(Instant.parse("2026-03-08T12:00:00Z"), ny, FOUR));
        assertEquals(mar7 + 2, EconomyClock.dayIndex(Instant.parse("2026-03-09T12:00:00Z"), ny, FOUR));
        long nov1 = EconomyClock.dayIndex(Instant.parse("2026-11-01T12:00:00Z"), ny, FOUR);
        assertEquals(nov1 + 1, EconomyClock.dayIndex(Instant.parse("2026-11-02T12:00:00Z"), ny, FOUR));
        // 03:30 local on the 25-hour day is both before and after the clocks change: still the previous day either way
        assertEquals(nov1 - 1, EconomyClock.dayIndex(Instant.parse("2026-11-01T07:30:00Z"), ny, FOUR));   // 03:30 EST
        assertEquals(nov1 - 1, EconomyClock.dayIndex(Instant.parse("2026-11-01T06:30:00Z"), ny, FOUR));   // 02:30 EST (second pass)
    }

    @Test void weeksStartOnTheConfiguredDay() {
        long monday = LocalDate.of(2026, 3, 9).toEpochDay();
        assertEquals(DayOfWeek.MONDAY, LocalDate.ofEpochDay(monday).getDayOfWeek());
        assertEquals(EconomyClock.weekIndex(monday, DayOfWeek.MONDAY), EconomyClock.weekIndex(monday + 6, DayOfWeek.MONDAY));
        assertEquals(EconomyClock.weekIndex(monday, DayOfWeek.MONDAY) + 1, EconomyClock.weekIndex(monday + 7, DayOfWeek.MONDAY));
        assertEquals(EconomyClock.weekIndex(monday, DayOfWeek.MONDAY) - 1, EconomyClock.weekIndex(monday - 1, DayOfWeek.MONDAY));
        assertEquals(EconomyClock.weekIndex(monday - 1, DayOfWeek.SUNDAY), EconomyClock.weekIndex(monday + 5, DayOfWeek.SUNDAY), "Sunday-start weeks run Sun..Sat");
    }

    @Test void catchUpFiresInOrderOncePerIndexAndMarksLateOnes() {
        long monday = LocalDate.of(2026, 3, 9).toEpochDay();
        var due = EconomyClock.due(monday - 1, monday + 2, DayOfWeek.MONDAY, 7, true);
        List<Boundary> b = due.boundaries();
        assertEquals(List.of(monday, monday + 1, monday + 2), b.stream().map(Boundary::day).toList());
        assertTrue(b.stream().allMatch(Boundary::catchUp), "everything after downtime is late");
        assertTrue(b.get(0).newWeek(), "Monday starts a week");
        assertFalse(b.get(1).newWeek());
        assertEquals(0, due.skipped());
    }

    @Test void maxCatchUpDaysSkipsTheOldest() {
        var due = EconomyClock.due(100, 120, DayOfWeek.MONDAY, 7, true);
        assertEquals(List.of(114L, 115L, 116L, 117L, 118L, 119L, 120L), due.boundaries().stream().map(Boundary::day).toList());
        assertEquals(13, due.skipped());
    }

    @Test void aBoundaryReachedWhileOnlineIsNotCatchUp() {
        assertFalse(EconomyClock.due(100, 101, DayOfWeek.MONDAY, 7, false).boundaries().get(0).catchUp());
        var late = EconomyClock.due(100, 102, DayOfWeek.MONDAY, 7, false);
        assertTrue(late.boundaries().get(0).catchUp(), "an older day found late is catch-up");
        assertFalse(late.boundaries().get(1).catchUp());
        assertTrue(EconomyClock.due(100, 100, DayOfWeek.MONDAY, 7, false).boundaries().isEmpty());
    }
}
