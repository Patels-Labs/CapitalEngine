package com.patelheet.capitalengine.dashboard.snapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Averages points into fixed time buckets (5-minute points older than 7 days become hourly, hourly points older than 90 days
 * become daily). Buckets start at multiples of their size since the epoch, so day buckets are UTC days.
 */
public final class Downsampler {
    private Downsampler() {}

    public static final long M5 = 5 * 60_000L, H1 = 3_600_000L, D1 = 86_400_000L;
    public static final long KEEP_M5 = 7 * D1, KEEP_H1 = 90 * D1;

    public record Point(String metric, long ts, double value) {}

    /** Start of the bucket that holds {@code t}. */
    public static long align(long t, long bucket) { return Math.floorDiv(t, bucket) * bucket; }

    /** One averaged point per metric and bucket, ordered by metric then time. NaN values are left out. */
    public static List<Point> bucket(List<Point> points, long bucket) {
        Map<String, TreeMap<Long, double[]>> sums = new TreeMap<>();
        for (Point p : points) {
            if (Double.isNaN(p.value())) continue;
            double[] s = sums.computeIfAbsent(p.metric(), k -> new TreeMap<>()).computeIfAbsent(align(p.ts(), bucket), k -> new double[2]);
            s[0] += p.value();
            s[1]++;
        }
        List<Point> out = new ArrayList<>();
        sums.forEach((metric, byTs) -> byTs.forEach((ts, s) -> out.add(new Point(metric, ts, s[0] / s[1]))));
        return out;
    }
}
