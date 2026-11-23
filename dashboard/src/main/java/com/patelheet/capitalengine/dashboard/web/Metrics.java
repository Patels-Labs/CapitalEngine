package com.patelheet.capitalengine.dashboard.web;

import com.patelheet.capitalengine.api.service.Unit;
import com.patelheet.capitalengine.dashboard.snapshot.Snapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The latest snapshot in Prometheus text exposition format (version 0.0.4), every value a gauge. Coin amounts are shown in
 * whole coins (divided by the currency's decimals), which is what a Grafana panel wants.
 * <pre>
 * capital_core_money_supply 12845.5
 * capital_flow_net{plugin="Jobs"} 1100
 * capital_indicator{provider="capitalmarket",key="price_index"} 1.02
 * </pre>
 */
public final class Metrics {
    private Metrics() {}

    public static String render(Snapshot s, int decimals, int openAlerts) {
        double scale = Math.pow(10, decimals);
        Map<String, List<String>> families = new TreeMap<>();   // one # TYPE line per family, then its samples
        for (Snapshot.Metric m : s.metrics()) {
            double v = m.unit() == Unit.COINS ? m.value() / scale : m.value();
            if (m.key().startsWith(Snapshot.FLOW)) {
                String rest = m.key().substring(Snapshot.FLOW.length());
                int dot = rest.lastIndexOf('.');
                add(families, "capital_flow_" + snake(rest.substring(dot + 1)), "{plugin=\"" + esc(rest.substring(0, dot)) + "\"}", v);
            } else if (m.provider().equals(Snapshot.CORE)) {
                add(families, "capital_core_" + snake(m.key().substring(Snapshot.CORE.length() + 1)), "", v);
            } else {
                add(families, "capital_indicator", "{provider=\"" + esc(m.provider()) + "\",key=\"" + esc(m.key().substring(m.provider().length() + 1)) + "\"}", v);
            }
        }
        add(families, "capital_dashboard_open_alerts", "", openAlerts);
        StringBuilder sb = new StringBuilder();
        families.forEach((name, samples) -> {
            sb.append("# TYPE ").append(name).append(" gauge\n");
            samples.forEach(line -> sb.append(line).append('\n'));
        });
        return sb.toString();
    }

    private static void add(Map<String, List<String>> f, String name, String labels, double v) {
        f.computeIfAbsent(name, k -> new ArrayList<>()).add(name + labels + " " + value(v));
    }

    private static String value(double v) {
        if (Double.isNaN(v)) return "NaN";
        if (Double.isInfinite(v)) return v > 0 ? "+Inf" : "-Inf";
        return v == Math.rint(v) && Math.abs(v) < 1e15 ? Long.toString((long) v) : Double.toString(v);
    }

    /** moneySupply → money_supply, avgActivePlayers7d → avg_active_players7d. Anything outside [a-z0-9_] becomes _. */
    static String snake(String camel) {
        StringBuilder sb = new StringBuilder();
        for (char c : camel.toCharArray()) {
            if (Character.isUpperCase(c)) { if (!sb.isEmpty()) sb.append('_'); sb.append(Character.toLowerCase(c)); }
            else sb.append(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' ? c : '_');
        }
        return sb.toString();
    }

    /** Label values escape backslash, double quote and newline. */
    static String esc(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n"); }
}
